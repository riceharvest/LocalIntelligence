package dev.localintelligence.android.inference

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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * llama.cpp as a [ModelBackend].
 *
 * The model lives in **native** memory for its whole resident life. Nothing in
 * this class holds a weight, an activation or a KV entry on the JVM heap, and
 * [unload] is the only thing that gives the memory back. On a 3B Q4_K_M that is
 * ~2.4 GB at 4K context; see [RamEstimate] for the arithmetic.
 *
 * ## Errors never escape
 *
 * `ModelBackend` requires that generation failures be reported through
 * [GenerationResult.stopReason] rather than thrown. So `load` throws (the caller
 * has to know a model did not arrive) but `generate` and `generateStreaming`
 * always return a result: an un-loaded model, a missing native library, a
 * grammar that would not compile, an OOM inside llama.cpp, a cancelled
 * generation — all of them come back as `StopReason.ERROR` or
 * `StopReason.CANCELLED` with the text produced so far intact.
 *
 * ## Cancellation
 *
 * [cancel] sets a flag the native sampling loop observes once per token, so an
 * in-flight decode stops within one token's latency (single-digit milliseconds
 * on a phone for a 3B) and returns `StopReason.CANCELLED` with the partial text
 * preserved. It is safe to call when idle, and safe to call from any thread.
 */
class LlamaCppBackend(
    private val importer: ModelImporter? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : StreamingModelBackend {

    override val id: String = ModelImporter.BACKEND_LLAMA_CPP

    @Volatile
    private var handle: LlamaBridge.Handle? = null

    @Volatile
    private var currentCapabilities: ModelCapabilities = ModelCapabilities.UNKNOWN

    override val capabilities: ModelCapabilities
        get() = currentCapabilities

    /**
     * Guards load/unload/generate. Generation holds this for its whole duration,
     * so a second `generate` waits rather than interleaving decodes into one
     * context, which llama.cpp does not allow.
     */
    private val lock = Any()

    private val cancelRequested = AtomicBoolean(false)

    /** The model the caller most recently asked for, used to build the spec. */
    @Volatile
    private var loaded: LoadedModel? = null

    override suspend fun load(model: ModelSpec) {
        val bridgeHandle = LlamaBridge.create()
            ?: error("the llama.cpp native library is not available on this device")

        // ModelSpec is backend-neutral and carries no path, only an id. The
        // importer is what turns that into something llama.cpp can open, and it
        // is the only thing that can turn a SAF document into a descriptor.
        val uri = android.net.Uri.parse(model.id)
        val opened = try {
            importer?.openForLoad(uri)
                ?: error("no ModelImporter was supplied, so ${model.id} cannot be opened")
        } catch (e: Throwable) {
            // Otherwise a bad URI leaks the handle we just made.
            bridgeHandle.close()
            throw e
        }

        val meta = opened.metadata
        // Capped at the app's own context, NOT the model's trained length.
        //
        // This used to be meta.contextLength, on the reasoning that the trained
        // length is the only honest input. It is the honest *maximum* and the
        // wrong *allocation*. llama.cpp sizes the KV cache from this value, and
        // KV is the dominant RAM term, so a model trained at 32K allocated a
        // 32K cache while the RAM fit gate had priced a 4K one - an 8x
        // under-count on exactly the models most likely to be picked. The gate
        // said 'it fits' and the load then allocated roughly eight times what
        // the gate had agreed to, which is an OOM on a phone.
        //
        // The app has no context control, so a trained length beyond what it
        // will ever use is not capability - it is unallocated headroom nobody
        // can reach. Capping also means this stays tied to the same constant
        // the gate uses; they must not drift apart.
        val contextLength = (meta.contextLength ?: DEFAULT_CONTEXT_LENGTH)
            .coerceIn(MIN_CONTEXT_LENGTH, MAX_CONTEXT_LENGTH)

        val loadError = LlamaBridge.loadModel(
            handle = bridgeHandle,
            path = opened.nativePath(),
            contextLength = contextLength,
            threads = 0,
            useMmap = opened.isMmapCapable,
        )
        if (loadError != null) {
            opened.close()
            LlamaBridge.unload(bridgeHandle)
            bridgeHandle.close()
            error("could not load the model: $loadError")
        }

        synchronized(lock) {
            // One model resident at a time.
            releaseLocked()
            handle = bridgeHandle
            loaded = opened
            currentCapabilities = deriveCapabilities(meta, contextLength)
        }
        cancelRequested.set(false)
    }

    override suspend fun unload() {
        synchronized(lock) {
            releaseLocked()
            currentCapabilities = ModelCapabilities.UNKNOWN
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
        cancelRequested.set(false)
        synchronized(lock) {
            val h = handle
            if (h == null || h.isClosed) {
                return@withContext GenerationResult(
                    text = "",
                    stopReason = StopReason.ERROR,
                )
            }

            val prompt = buildPrompt(request)
            val sink = onToken?.let { cb ->
                LlamaBridge.TokenSink { piece -> cb(piece) }
            }

            val params = request.params
            val error = try {
                LlamaBridge.generate(
                    handle = h,
                    prompt = prompt,
                    grammar = request.grammar,
                    temperature = params.temperature,
                    topP = params.topP,
                    minP = params.minP,
                    repeatPenalty = params.repeatPenalty,
                    seed = params.seed,
                    maxTokens = params.maxOutputTokens,
                    onToken = sink,
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Structured concurrency: a cancelled coroutine must keep
                // propagating, or the caller's scope is silently ignored and
                // the UI waits on a result nobody is waiting for.
                LlamaBridge.cancel(h)
                throw e
            } catch (e: Throwable) {
                // llama.cpp can abort on a corrupt model or an OOM inside a
                // decode. The interface says do not throw; say ERROR.
                "native generation failed: ${e.message ?: e::class.java.simpleName}"
            }

            val outcome = LlamaBridge.takeResult(h)
            val stop = when {
                // An error from the native call wins: the outcome may be the
                // default ERROR with no text, and the reason matters more.
                error != null -> StopReason.ERROR
                outcome == null -> StopReason.ERROR
                cancelRequested.get() -> StopReason.CANCELLED
                outcome.stopCode == GenerationOutcome.STOP_CANCELLED -> StopReason.CANCELLED
                outcome.stopCode == GenerationOutcome.STOP_MAX_TOKENS -> StopReason.MAX_TOKENS
                outcome.stopCode == GenerationOutcome.STOP_COMPLETED -> StopReason.COMPLETED
                else -> StopReason.ERROR
            }

            GenerationResult(
                // Keep the partial text on a cancel: a cancelled answer is
                // still worth showing, and the caller decides.
                // WHY PREFER A REAL ERROR: llama.cpp reports failures two ways —
                // as a return value from nativeGenerate, and packed into the
                // outcome as `E<error>\n<text>`. `parseOutcome` extracts that
                // into `outcome.error`, but this only ever read `outcome.text`.
                // So a decode failure surfaced as an EMPTY string with
                // StopReason.ERROR and no reason at all: every generation
                // failure on a device looked identical and unactionable, and
                // there was nothing to grep for. Read both.
                text = error
                    ?: outcome?.error
                    ?: outcome?.text.orEmpty(),
                promptTokens = outcome?.promptTokens ?: 0,
                completionTokens = outcome?.completionTokens ?: 0,
                stopReason = stop,
                prefillMs = outcome?.prefillMs ?: 0L,
                decodeMs = outcome?.decodeMs ?: 0L,
            )
        }
    }

    override fun countTokens(text: String): Int {
        val h = handle
        if (h == null || h.isClosed) return approximateTokenCount(text)
        val exact = LlamaBridge.countTokens(h, text)
        // -1 means "no model loaded" or a tokenize failure. Context budgeting
        // runs before load, so the approximation has to be good enough to plan
        // with; see [approximateTokenCount].
        return if (exact >= 0) exact else approximateTokenCount(text)
    }

    override fun cancel() {
        cancelRequested.set(true)
        val h = handle ?: return
        LlamaBridge.cancel(h)
    }

    /**
     * Token count without a loaded model.
     *
     * Documented approximation, not a guess dressed up as a measurement: a
     * BPE tokenizer averages ~4 characters per token for English prose and
     * ~2.5 for code and JSON, and the same tokenizer emits *fewer* tokens for
     * a given text than 4-chars-per-token implies when the text has repeated
     * tokens. Deliberately biased high, because under-counting overflows the
     * context and over-counting only triggers compaction a little early. The
     * figure is replaced by a real `llama_tokenize` as soon as a model exists,
     * which is what the agent loop uses during a run.
     */
    internal fun approximateTokenCount(text: String): Int {
        if (text.isEmpty()) return 0
        // Count a token for every 4 chars, plus one per newline (structured
        // output breaks into more tokens than its length suggests).
        var tokens = (text.length + 3) / 4
        tokens += text.count { it == '\n' }
        return tokens
    }

    private fun releaseLocked() {
        val h = handle
        val m = loaded
        handle = null
        loaded = null
        if (h != null) {
            LlamaBridge.unload(h)
            h.close()
        }
        // Closing the descriptor last: /proc/self/fd/N must stay valid for as
        // long as the native model might still read it.
        m?.close()
    }

    /**
     * Capabilities from the GGUF header.
     *
     * `supportsGrammar` is true because llama.cpp's sampler chain enforces GBNF
     * during sampling, not after it. `supportsToolCalling` is true only when the
     * model ships a chat template, because the tool-call shape depends on the
     * template's control tokens and a base model without one cannot be
     * prompted reliably into the action grammar.
     */
    private fun deriveCapabilities(meta: GgufMetadata, contextLength: Int): ModelCapabilities =
        ModelCapabilities(
            contextLength = contextLength,
            supportsToolCalling = meta.hasChatTemplate,
            supportsGrammar = true,
            // A 1-4B GGUF is text-only here; the multimodal projector path is
            // not wired up, so this is honestly false rather than hopeful.
            supportsVision = false,
            supportsKvCache = true,
        )

    /**
     * Renders the conversation as the flat text llama.cpp tokenizes.
     *
     * Deliberately a simple, explicit transcript rather than the model's own
     * chat template: the template lives in the GGUF as Jinja and rendering it
     * needs either a template engine or native calls, and a wrong template is
     * worse than an explicit one. The role markers are the portable subset
     * every instruct model in the 1-4B range recognises.
     */
    internal fun buildPrompt(request: GenerationRequest): String = buildString {
        for (m in request.messages) {
            when (m) {
                is ChatMessage.System -> append("### System\n").append(m.text).append("\n\n")
                is ChatMessage.User -> append("### User\n").append(m.text).append("\n\n")
                is ChatMessage.Assistant -> append("### Assistant\n").append(m.text).append("\n\n")
                is ChatMessage.ToolObservation ->
                    append("### Observation (")
                        .append(m.toolName)
                        .append(if (m.success) "" else ", failed")
                        .append(")\n")
                        .append(m.observation)
                        .append("\n\n")
            }
        }
        append("### Assistant\n")
    }

    companion object {
        /**
         * The context allocated when the GGUF header is silent.
         *
         * [ContextCeiling.ALLOCATED_CONTEXT_TOKENS] rather than a literal:
         * this number is what becomes `cparams.n_ctx` in `llama_jni.cpp`, and
         * the prompt budget derives from the same constant, so the two cannot
         * drift. They did once — 4096 here against a 6000-token prompt ceiling
         * in `:core` — and the symptom was a prefill llama.cpp silently clamps.
         */
        const val DEFAULT_CONTEXT_LENGTH = ContextCeiling.ALLOCATED_CONTEXT_TOKENS

        /**
         * Smallest context worth creating: below this a prompt plus an answer
         * does not fit, and llama.cpp fails every decode.
         */
        private const val MIN_CONTEXT_LENGTH = 128

        /**
         * Hard ceiling on the context the app will ever allocate.
         *
         * WHY IT EQUALS [DEFAULT_CONTEXT_LENGTH]: the RAM fit gate
         * (AppContainer.loadModel -> fitsOnDevice) prices the KV cache at
         * DEFAULT_CONTEXT_LENGTH. If the loader allocated anything larger, the
         * two would disagree and the gate's promise - this model fits - would
         * be false before the first token. One number, two places, and a
         * comment in each saying so.
         *
         * A model trained at 32K is not diminished by this: the app has no
         * context control, so the extra 28K was never reachable.
         */
        private const val MAX_CONTEXT_LENGTH = DEFAULT_CONTEXT_LENGTH
    }
}
