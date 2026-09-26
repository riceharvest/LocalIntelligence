package dev.localintelligence.inference.litertlm

import dev.localintelligence.core.model.AcceleratorAware
import dev.localintelligence.core.model.AcceleratorKind
import dev.localintelligence.core.model.AcceleratorPreference
import dev.localintelligence.core.model.AcceleratorProbe
import dev.localintelligence.core.model.AcceleratorReport
import dev.localintelligence.core.model.AcceleratorSelector
import dev.localintelligence.core.model.ChatMessage
import dev.localintelligence.core.model.GenerationRequest
import dev.localintelligence.core.model.GenerationResult
import dev.localintelligence.core.model.ModelBackend
import dev.localintelligence.core.model.ModelCapabilities
import dev.localintelligence.core.model.ModelSpec
import dev.localintelligence.core.model.SamplingParams
import dev.localintelligence.core.model.StopReason
import dev.localintelligence.core.model.StreamingModelBackend
import dev.localintelligence.core.model.token.ContextCeiling
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * LiteRT-LM as a [ModelBackend].
 *
 * This exists so the repo can answer "which runtime is faster on a phone" with a
 * measurement instead of an opinion. The llama.cpp backend is already there and
 * already frozen behind the same interface, so the comparison is one config
 * change away — and the whole point of freezing `ModelBackend` is that adding a
 * second runtime touches exactly one module and nothing else.
 *
 * ## The differences that actually matter from llama.cpp
 *
 * **Conversation per request, not one long-lived context.** `GenerationRequest`
 * carries the entire transcript, and `AgentController` compacts it between turns.
 * A persistent server-side conversation would mean the engine held a transcript
 * the core may already have rewritten. Rebuilding from the supplied transcript is
 * the only way this backend can never be wrong about what the model has seen. See
 * [LiteRtLmEngineFactory].
 *
 * **The model must be a real file.** LiteRT-LM opens the model by path and
 * cannot use a SAF descriptor, so a SAF-picked GGUF will not work here. See
 * [LiteRtLmModelSource].
 *
 * **Benchmarking is on by default.** `ExperimentalFlags.enableBenchmark` is
 * process-global and is read only when an engine is constructed, so it is set
 * here rather than left to a caller who would have to remember. Without it the
 * runtime reports no prefill or decode timings at all, and a benchmark that
 * cannot report tok/s is not worth having.
 *
 * ## Errors never escape
 *
 * `ModelBackend` requires generation failures to be reported through
 * [GenerationResult.stopReason] rather than thrown. So `load()` throws — the
 * caller must know a model did not arrive — but `generate()` and
 * `generateStreaming()` always return: no engine loaded, a missing native
 * library, an OOM inside the engine, a cancelled generation, all of it comes
 * back as `StopReason.ERROR` or `StopReason.CANCELLED` with whatever text was
 * produced still intact.
 *
 * ## Acceleration degrades, it does not fail
 *
 * `acceleratorPreference` is resolved against `capabilityProbe` at load time, and
 * the resulting tier is reported through [acceleratorReport]. A phone with no NPU
 * delegate, or no OpenCL driver, loads the model on CPU and says why — it does
 * not throw, because "this phone has no NPU" is a fact about the phone, not an
 * error in the request. See [LiteRtLmCapabilityProbe] for what is actually
 * checked, and [loadWithAcceleratorFallback] for the second fallback that catches
 * an accelerator which passed the probe and then failed to initialise.
 *
 * The reason this lives in the backend rather than in the UI: a tok/s number
 * without the hardware it came from is not a measurement. [acceleratorReport] is
 * what makes `CPU: 8.2 tok/s` and `NPU: 21.4 tok/s` distinguishable rows rather
 * than two numbers a reader has to guess at.
 *
 * ## Cancellation
 *
 * [cancel] sets a flag and calls through to the runtime's own cancellation. A
 * cancelled generation returns within roughly one token's latency with the
 * partial text preserved, because a cancelled answer is still worth showing and
 * the caller decides what to do with it. Safe to call when idle, and safe from
 * any thread.
 *
 * ## One generation at a time
 *
 * Generation holds [generationLock] for its whole duration, so a second
 * `generate()` waits rather than interleaving two decodes into one runtime. This
 * is not a stylistic choice: the LiteRT-LM conversation is stateful, and two
 * overlapping sends on one engine produce interleaved output that neither caller
 * can attribute. `load()` is *rejected* rather than serialized while a generation
 * is in flight, because swapping the engine mid-generation would leave the
 * running decode pointing at a closed handle.
 */
class LiteRtLmBackend(
    private val modelSource: LiteRtLmModelSource,
    private val engineFactory: LiteRtLmEngineFactory =
        NativeLiteRtLmEngine.defaultFactory(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** Where the runtime may cache compiled state. Null disables the cache. */
    private val cacheDir: String? = null,
    private val numThreads: Int = 0,
    /** `topK` has no `SamplingParams` equivalent; 0 means "let the engine decide". */
    private val topK: Int = DEFAULT_TOP_K,
    /**
     * Context window to allocate. `ModelSpec` deliberately carries no context
     * length — it is backend-neutral, and a GGUF header is not readable here — so
     * this is a constructor knob rather than something derived from the spec.
     * Clamped on the way in; see [MIN_CONTEXT_LENGTH] and [MAX_CONTEXT_LENGTH].
     */
    private val contextLength: Int = DEFAULT_CONTEXT_LENGTH,
    /**
     * Whether the loaded model was trained for tool use. Declared by the caller
     * rather than guessed, because LiteRT-LM has no way to ask: a base model will
     * happily emit JSON that is not a valid call, and the failure surfaces as a
     * malformed action in `ActionParserImpl` several layers up.
     */
    private val supportsToolCalling: Boolean = false,
    /**
     * Which hardware the user asked for. [AcceleratorPreference.AUTO] by default.
     *
     * Exposed as a constructor knob rather than a `Build.*` read because the
     * whole point is that the answer is *decided at runtime* and *reported*; a
     * value chosen at construction time is a decision the user can make and a
     * benchmark can pin.
     */
    private val acceleratorPreference: AcceleratorPreference =
        AcceleratorPreference.DEFAULT,
    /**
     * What the device can actually do.
     *
     * Injected, and defaulting to [CpuOnlyAcceleratorProbe] rather than to a real
     * probe, so constructing this backend from a JVM test or a headless harness
     * cannot accidentally reach for a device. On Android, pass
     * `LiteRtLmCapabilityProbe(context.applicationInfo.nativeLibraryDir)`. See that
     * class for why a library check beats a `Build.SOC_MODEL` match.
     */
    private val capabilityProbe: AcceleratorProbe = CpuOnlyAcceleratorProbe,
    /**
     * The `Backend.NPU` dispatch directory, threaded to the runtime only for NPU.
     *
     * Separate from [capabilityProbe] because a probe *searches* a directory while
     * the runtime *uses* it, and the probe's directory is a place to look rather
     * than a value to hand over. Defaults to null, which makes NPU resolve to
     * unavailable with a stated reason instead of silently constructing
     * `Backend.NPU("")`.
     */
    private val nativeLibraryDir: String? = null,
) : StreamingModelBackend, AcceleratorAware {

    override val id: String = BACKEND_LITERTLM

    @Volatile
    private var engine: LiteRtLmEngine? = null

    @Volatile
    private var currentCapabilities: ModelCapabilities = ModelCapabilities.UNKNOWN

    /**
     * The accelerator actually in use, and why it is not what was asked for.
     *
     * [AcceleratorReport.UNKNOWN] until the first successful load, because before
     * that there is no engine and no answer to give. Reported through
     * [ModelBackend.acceleratorReport] so the UI and the benchmark read the same
     * value the load path used.
     */
    @Volatile
    override var acceleratorReport: AcceleratorReport = AcceleratorReport.UNKNOWN
        private set

    override val capabilities: ModelCapabilities
        get() = currentCapabilities

    /** Serializes generations. A load in flight makes [load] fail fast. */
    private val generationLock = Any()
    private val loadInProgress = AtomicBoolean(false)

    /** Set by [cancel]; observed once per chunk so a stop is not a busy-wait. */
    private val cancelRequested = AtomicBoolean(false)

    /** The conversation currently generating, so [cancel] can reach the runtime. */
    private val activeConversation = AtomicReference<LiteRtLmConversation?>(null)

    override suspend fun load(model: ModelSpec): Unit = withContext(ioDispatcher) {
        // Resolve and validate BEFORE claiming the load slot: a bad path should
        // not make a concurrent load look busy, and it costs nothing to check.
        val resolved = modelSource.resolve(model)
        val baseConfig = LiteRtLmEngineConfig(
            modelPath = resolved.absolutePath,
            // Clamped here rather than trusting the caller: below the minimum
            // nothing fits, and above the ceiling a typo asks for an allocation
            // that never returns and takes the app down with it.
            contextLength = contextLength.coerceIn(MIN_CONTEXT_LENGTH, MAX_CONTEXT_LENGTH),
            numThreads = numThreads,
            cacheDir = cacheDir,
            enableBenchmark = true,
        )

        if (!loadInProgress.compareAndSet(false, true)) {
            throw LiteRtLmModelException(
                "a LiteRT-LM load is already in progress; wait for it to finish before " +
                    "loading ${model.id}",
            )
        }
        try {
            // Drop the previous engine only once the new one is known good. A
            // failed load must leave a working model working -- tearing down first
            // and failing second would turn a bad path into a lost model.
            val outcome = loadWithAcceleratorFallback(resolved.absolutePath, baseConfig)
            synchronized(generationLock) {
                val previous = engine
                engine = outcome.engine
                currentCapabilities = deriveCapabilities(baseConfig.contextLength)
                acceleratorReport = outcome.report
                cancelRequested.set(false)
                // Close outside the swap but inside the lock, so no generation can
                // start against the engine being retired.
                previous?.close()
            }
        } finally {
            loadInProgress.set(false)
        }
    }

    /**
     * Builds an engine, degrading through the accelerator ladder if one fails.
     *
     * ## Why there is a second fallback after the probe
     *
     * [AcceleratorSelector] answers "can this device do this", from a directory
     * listing and a load attempt. That is a strong signal, not a guarantee:
     *
     * - A vendor NPU delegate can be present in the APK and still be built for a
     *   different Hexagon generation than the SoC in this phone.
     * - `Engine.initialize()` for the GPU backend can fail on a driver that
     *   enumerates an OpenCL device but cannot compile the model's kernels.
     *
     * Both failures happen *after* the model file has been opened, and both would
     * otherwise be a `load()` that throws on a phone that is perfectly capable of
     * running the model on its GPU. So the ladder is walked a second time here,
     * using the engine factory as the oracle: whatever tier finally builds is the
     * tier that is reported. CPU is always tried, so this terminates.
     *
     * The runtime message from the failed attempt is preserved, because "the NPU
     * failed and here is the driver's own words" is the difference between a
     * reportable bug and an unexplained slowdown.
     */
    private fun loadWithAcceleratorFallback(
        modelPath: String,
        baseConfig: LiteRtLmEngineConfig,
    ): LoadOutcome {
        val selection = AcceleratorSelector.resolve(acceleratorPreference, capabilityProbe)
        // Start at the tier the probe cleared, NOT at the top of the preference.
        // Walking from the top would re-attempt an accelerator the probe already
        // established is absent -- on a phone with no NPU that means a doomed
        // Engine() call on every load, which is the slow, noisy thing this whole
        // class exists to avoid.
        val ladder = selection.requested.candidates()
            .dropWhile { it != selection.active }
        var lastError: Throwable? = null
        val failures = mutableListOf<String>()

        for (kind in ladder) {
            val config = baseConfig.copy(
                accelerator = kind,
                nativeLibraryDir = nativeLibraryDir.takeIf { kind == AcceleratorKind.NPU },
            )
            try {
                val created = engineFactory.create(config)
                if (!created.isAlive) {
                    created.close()
                    throw LiteRtLmEngineException(
                        "the LiteRT-LM engine reported a dead model at $modelPath " +
                            "on ${kind.name}",
                    )
                }
                return LoadOutcome(
                    engine = created,
                    report = reportFor(selection, kind, failures),
                )
            } catch (e: Throwable) {
                lastError = e
                failures += "${kind.name}: ${e.message ?: e::class.java.simpleName}"
            }
        }

        // Unreachable while CPU is in every candidate list, which the selector
        // guarantees. Kept so that a future edit to the ladder degrades into a
        // clear error rather than a null engine.
        throw LiteRtLmEngineException(
            "LiteRT-LM could not load $modelPath on any accelerator " +
                "(${failures.joinToString("; ")})",
            lastError,
        )
    }

    /**
     * Folds runtime failures into the pre-selected report.
     *
     * Two separate degradations are merged into one sentence, because they are
     * two separate reasons and a reader needs both: the probe may already have
     * passed over a tier, and the engine may then have failed on the tier the
     * probe chose. Dropping either one makes the report lie about what happened.
     */
    private fun reportFor(
        selection: AcceleratorReport,
        active: AcceleratorKind,
        failures: List<String>,
    ): AcceleratorReport {
        val probeReason = selection.fallbackReason
        val runtimeReason = if (failures.isEmpty()) {
            null
        } else {
            "the ${selection.active.name} engine failed to initialise (" +
                failures.joinToString("; ") + ")"
        }
        val combined = listOfNotNull(probeReason, runtimeReason)
        return selection.copy(
            active = active,
            fallbackReason = when {
                combined.isEmpty() -> null
                combined.size == 1 -> combined.first()
                else -> combined.joinToString("; ")
            },
        )
    }

    override suspend fun unload() = withContext(ioDispatcher) {
        synchronized(generationLock) {
            releaseLocked()
            currentCapabilities = ModelCapabilities.UNKNOWN
            // Nothing is running, so there is no accelerator to report. Leaving
            // the old one would have the UI claiming an NPU run is in progress
            // after the engine is gone.
            acceleratorReport = AcceleratorReport.UNKNOWN
        }
    }

    override suspend fun generate(request: GenerationRequest): GenerationResult =
        generateInternal(request, onToken = null)

    override suspend fun generateStreaming(
        request: GenerationRequest,
        onToken: (String) -> Unit,
    ): GenerationResult = generateInternal(request, onToken = onToken)

    private suspend fun generateInternal(
        request: GenerationRequest,
        onToken: ((String) -> Unit)?,
    ): GenerationResult = withContext(ioDispatcher) {
        val startedAt = System.nanoTime()

        synchronized(generationLock) {
            val active = engine
            if (active == null || !active.isAlive) {
                return@withContext GenerationResult(
                    text = "",
                    stopReason = StopReason.ERROR,
                )
            }
            if (loadInProgress.get()) {
                // Swapping the engine under a running decode is a use-after-free
                // with a latency-shaped disguise. Refuse instead.
                return@withContext GenerationResult(text = "", stopReason = StopReason.ERROR)
            }

            cancelRequested.set(false)

            val prepared = prepare(request)
            if (prepared.failure != null) {
                return@withContext GenerationResult(text = "", stopReason = StopReason.ERROR)
            }
            val plan = prepared.plan!!

            val budget = request.params.maxOutputTokens
                .coerceIn(MIN_OUTPUT_TOKENS, MAX_OUTPUT_TOKENS)
            val tracker = LiteRtLmDeltaTracker()
            val outcome = AtomicReference<GenerationOutcome>()
            val firstTokenAt = AtomicReference(0L)
            val budgetHit = AtomicBoolean(false)
            val conversation = try {
                active.openConversation(plan.conversation)
            } catch (_: Throwable) {
                // The runtime refused the conversation. generate() must not throw.
                return@withContext GenerationResult(text = "", stopReason = StopReason.ERROR)
            }
            activeConversation.set(conversation)

            try {
                conversation.send(plan.turn, object : LiteRtLmListener {
                    override fun onDelta(text: String) {
                        // The runtime's output budget is enforced here, not passed
                        // through, because LiteRT-LM's SamplerConfig has no
                        // max-tokens field. Ignoring it would let one turn run for
                        // minutes on a phone.
                        val fresh = tracker.accept(text)
                        if (fresh.isEmpty()) return
                        if (firstTokenAt.get() == 0L) firstTokenAt.set(System.nanoTime())
                        onToken?.invoke(fresh)
                        if (approximateTokenCount(tracker.text()) > budget) {
                            budgetHit.set(true)
                            conversation.cancel()
                        }
                    }

                    override fun onDone() {
                        outcome.compareAndSet(null, GenerationOutcome.COMPLETED)
                    }

                    override fun onError(error: Throwable) {
                        outcome.compareAndSet(null, GenerationOutcome.ERROR)
                    }
                })
            } catch (e: CancellationException) {
                // Structured concurrency: a cancelled coroutine must keep
                // propagating, or the caller's scope is silently ignored and the
                // UI waits on a result nobody is waiting for.
                conversation.cancel()
                throw e
            } catch (_: Throwable) {
                // The runtime can fail in native code on a malformed prompt or a
                // model that does not support the requested features. The
                // interface says do not throw; say ERROR.
                conversation.cancel()
                outcome.compareAndSet(null, GenerationOutcome.ERROR)
            } finally {
                activeConversation.compareAndSet(conversation, null)
                closeQuietly(conversation)
            }

            buildResult(
                request = request,
                tracker = tracker,
                outcome = outcome.get(),
                startedAt = startedAt,
                firstTokenAt = firstTokenAt.get(),
                budgetHit = budgetHit.get(),
            )
        }
    }

    override fun countTokens(text: String): Int = approximateTokenCount(text)

    /**
     * No engine-level token counter is reachable without opening a conversation
     * and running a prefill, which costs a forward pass — an absurd price for the
     * per-keystroke budgeting this is called for. So this is the same documented
     * approximation the llama.cpp backend uses when no model is resident, and for
     * the same reason: context budgeting runs *before* and *between* generations,
     * so it has to work with nothing loaded.
     *
     * Deliberately biased high — 4 characters per token plus one per newline.
     * Under-counting overflows the context window and loses the turn; counting
     * high only triggers compaction slightly early, which costs a little context
     * and is recoverable.
     */
    internal fun approximateTokenCount(text: String): Int {
        if (text.isEmpty()) return 0
        var tokens = (text.length + 3) / 4
        tokens += text.count { it == '\n' }
        return tokens
    }

    override fun cancel() {
        cancelRequested.set(true)
        activeConversation.get()?.cancel()
    }

    /** Test seam: true while a generation holds the lock. */
    internal fun isGenerating(): Boolean = activeConversation.get() != null

    /**
     * Test seam: did [cancel] actually reach the runtime's conversation?
     *
     * Exposed because "cancel sets a flag" and "cancel stops the decode" are
     * different claims, and only the second one matters on a phone.
     */
    internal fun isLastGenerationCancelled(): Boolean = cancelRequested.get()

    // ------------------------------------------------------------------ planning

    /**
     * The request, mapped onto the runtime's vocabulary.
     *
     * Split out from [generateInternal] so the mapping is testable without a
     * runtime — this is the part where a mistake is silent rather than loud, since
     * a mislabelled role still produces fluent text, just the wrong answer.
     */
    internal fun prepare(request: GenerationRequest): PreparedRequest {
        if (request.messages.isEmpty()) {
            return PreparedRequest(failure = "the request has no messages")
        }
        if (request.grammar != null) {
            // The frozen interface says a backend that cannot honour a grammar
            // must say so via capabilities, so the runtime can fall back. LiteRT-LM
            // exposes constrained decoding as a process-global flag aimed at tool
            // calls, not as a per-request GBNF, so there is nothing to pass here.
            // Failing loudly beats silently ignoring a constraint the caller
            // believed was in force.
            return PreparedRequest(
                failure = "LiteRT-LM cannot enforce a per-request GBNF grammar; " +
                    "supportsGrammar is reported false so the runtime falls back",
            )
        }

        // A system message is hoisted into the conversation's systemInstruction
        // and must NOT also appear in history, or the model sees the system prompt
        // twice and the second copy is context the caller did not budget for.
        val system = request.messages.filterIsInstance<ChatMessage.System>()
        val turns = request.messages
            .filterNot { it is ChatMessage.System }
            .map { it.toLiteRtLmTurn() }
        if (turns.isEmpty()) {
            return PreparedRequest(failure = "the request has no non-system messages")
        }
        val last = turns.last()
        if (last.role != LiteRtLmRole.USER) {
            // A conversation must end on a user turn. Handing the engine a
            // history that ends on the model's own output asks it to continue its
            // own answer, which is a different request than the caller made.
            return PreparedRequest(
                failure = "the transcript ends on a ${last.role} turn; it must end on a user turn",
            )
        }

        val systemInstruction = system.joinToString("\n\n") { it.text }.ifBlank { null }
        return PreparedRequest(
            plan = GenerationPlan(
                conversation = LiteRtLmConversationRequest(
                    systemInstruction = systemInstruction,
                    history = turns.dropLast(1),
                    sampler = request.params.toSampler(topK),
                ),
                turn = last,
            ),
        )
    }

    private fun ChatMessage.toLiteRtLmTurn(): LiteRtLmTurn = when (this) {
        is ChatMessage.System -> LiteRtLmTurn(LiteRtLmRole.SYSTEM, text)
        is ChatMessage.User -> LiteRtLmTurn(LiteRtLmRole.USER, text)
        is ChatMessage.Assistant -> LiteRtLmTurn(LiteRtLmRole.MODEL, text)
        // Fenced via `modelFacing()` rather than passed through verbatim. The
        // TOOL role is a real boundary here rather than a textual convention, so
        // a page cannot forge a *turn* the way it can in the llama.cpp
        // transcript — but the body still arrives as plain text inside that
        // turn, and the fence is what tells the model the text is quoted. The
        // tool name travels beside it because LiteRT-LM models a tool result as
        // a named response, not free text.
        is ChatMessage.ToolObservation -> LiteRtLmTurn(LiteRtLmRole.TOOL, modelFacing(), toolName)
    }

    private fun SamplingParams.toSampler(topK: Int) = LiteRtLmSampler(
        topK = topK,
        topP = topP.toDouble(),
        temperature = temperature.toDouble(),
        seed = seed,
    )

    // ------------------------------------------------------------------- results

    /**
     * Turns the collected text plus the terminal event into a [GenerationResult].
     *
     * Precedence matters and is deliberate: an error outranks everything, because
     * the outcome in that case is the default ERROR with no text and the reason
     * is the only useful thing in it. Cancellation outranks completion, because a
     * generation that was stopped did not complete, whatever the runtime said on
     * its way out. The output budget is checked before completion for the same
     * reason: a reply cut off at the budget did not complete either.
     */
    private fun buildResult(
        request: GenerationRequest,
        tracker: LiteRtLmDeltaTracker,
        outcome: GenerationOutcome?,
        startedAt: Long,
        firstTokenAt: Long,
        budgetHit: Boolean,
    ): GenerationResult {
        val text = tracker.text()
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
        val firstTokenMs = if (firstTokenAt > 0L) (firstTokenAt - startedAt) / 1_000_000 else 0L
        // Decode is everything after the first token. On a cancel that is still
        // the honest figure for the work actually done.
        val decodeMs = (elapsedMs - firstTokenMs).coerceAtLeast(0L)
        val completionTokens = approximateTokenCount(text)

        val stop = when {
            outcome == GenerationOutcome.ERROR -> StopReason.ERROR
            cancelRequested.get() -> StopReason.CANCELLED
            budgetHit -> StopReason.MAX_TOKENS
            outcome == GenerationOutcome.COMPLETED -> StopReason.COMPLETED
            // The runtime returned without a terminal event. That is a broken
            // stream, not a successful empty answer, and reporting COMPLETED here
            // would let a silent failure look like a model that had nothing to say.
            else -> StopReason.ERROR
        }

        return GenerationResult(
            text = text,
            // No exact prompt count is reachable without a prefill the runtime
            // only reports when benchmarking is on; the approximation is biased
            // high for the same reason countTokens is.
            promptTokens = approximateTokenCount(request.messages.joinToString("\n") { it.textOf() }),
            completionTokens = completionTokens,
            stopReason = stop,
            prefillMs = firstTokenMs,
            decodeMs = decodeMs,
        )
    }

    private fun ChatMessage.textOf(): String = when (this) {
        is ChatMessage.System -> text
        is ChatMessage.User -> text
        is ChatMessage.Assistant -> text
        // Fenced, to match what `toLiteRtLmTurn` actually sends. Pricing the
        // bare body here would make the reported prompt-token count a fiction.
        is ChatMessage.ToolObservation -> modelFacing()
    }

    /** Frees the engine. Only ever called with [generationLock] held. */
    private fun releaseLocked() {
        val current = engine
        engine = null
        current?.close()
    }

    private fun closeQuietly(closeable: AutoCloseable) {
        try {
            closeable.close()
        } catch (_: Throwable) {
            // Teardown must not mask the result the caller is about to get. A
            // close that throws has already released or failed to release native
            // memory; either way the generation's outcome is the more useful fact.
        }
    }

    // -------------------------------------------------------------- capabilities

    /**
     * What this model can actually do, reported honestly.
     *
     * `supportsGrammar` is **false**, and it is the one field that differs
     * materially from the llama.cpp backend. LiteRT-LM's constrained decoding is a
     * process-global `ExperimentalFlags` switch read when a conversation is
     * created, aimed at forcing valid tool-call JSON — there is no per-request
     * GBNF entry point. Reporting `true` would make the runtime build a grammar
     * and believe it was enforced when it was not, which is the failure mode the
     * frozen interface explicitly warns about.
     *
     * `supportsToolCalling` is true only when the caller supplied a tool schema
     * set, because LiteRT-LM's tool calling needs a model trained for it; a base
     * 1-3B will emit plausible-looking JSON that is not a valid call.
     */
    private fun deriveCapabilities(contextLength: Int): ModelCapabilities =
        ModelCapabilities(
            contextLength = contextLength,
            // LiteRT-LM does function calling natively, but only for a model
            // trained for it -- a base 1-3B emits plausible JSON that is not a
            // valid call. So this follows what the caller declared, not a guess.
            supportsToolCalling = supportsToolCalling,
            supportsGrammar = false,
            // Vision needs a Gemma3n-class model plus a separate vision backend in
            // EngineConfig. Not wired, so not claimed.
            supportsVision = false,
            // LiteRT-LM is a prefill/decode engine with a resident KV cache, and
            // `maxNumTokens` is a real allocation rather than a text-window limit.
            supportsKvCache = true,
        )

    companion object {
        const val BACKEND_LITERTLM = "litertlm"

        /**
         * `topK` for every request. 40 is the usual "greedy-ish but not
         * degenerate" default for 1-4B instruct models; it is a backend-level
         * knob because `SamplingParams` has no field for it.
         */
        const val DEFAULT_TOP_K = 40

        /**
         * The context allocated when the caller does not state one.
         *
         * [ContextCeiling.ALLOCATED_CONTEXT_TOKENS] so this backend, the GGUF
         * loader and the prompt budget in `:core` all read one number. A
         * LiteRT model that reports a different context at load has its
         * reported value used instead (see [deriveCapabilities]) — the constant
         * is only the floor, never the ceiling.
         */
        const val DEFAULT_CONTEXT_LENGTH = ContextCeiling.ALLOCATED_CONTEXT_TOKENS

        /** Below this the model cannot fit a prompt and an answer together. */
        private const val MIN_CONTEXT_LENGTH = 128

        /** A ceiling so a corrupt spec cannot ask for a context that never returns. */
        private const val MAX_CONTEXT_LENGTH = 131_072

        private const val MIN_OUTPUT_TOKENS = 1
        private const val MAX_OUTPUT_TOKENS = 8192
    }
}

/** Terminal state, internal because only this package produces it. */
internal enum class GenerationOutcome { COMPLETED, ERROR }

/**
 * What a load produced: the engine, and the accelerator it is really on.
 *
 * Paired rather than returned separately because the two must be swapped in
 * together under [LiteRtLmBackend]'s generation lock. A window in which the new
 * engine is visible but the old accelerator is still reported is exactly how a
 * benchmark ends up labelling a CPU run as an NPU run.
 */
internal class LoadOutcome(
    val engine: LiteRtLmEngine,
    val report: AcceleratorReport,
)

/** A validated request, or the reason it is not one. */
internal class PreparedRequest(
    val plan: GenerationPlan? = null,
    val failure: String? = null,
)

/** A request that survived validation: the conversation to open, and the turn to send. */
internal class GenerationPlan(
    val conversation: LiteRtLmConversationRequest,
    val turn: LiteRtLmTurn,
)
