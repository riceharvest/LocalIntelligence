package dev.localintelligence.inference.litertlm

import dev.localintelligence.core.model.AcceleratorKind

/**
 * The seam between [LiteRtLmBackend] and Google's LiteRT-LM runtime.
 *
 * ## Why this file exists at all
 *
 * `com.google.ai.edge.litertlm:litertlm-android:0.13.1` ships **Java 21 bytecode**
 * (class file major version 65). This module compiles with `jvmToolchain(17)`,
 * and the unit-test JVM is therefore JDK 17, which refuses to load anything above
 * class file 61:
 *
 * ```
 * java.lang.UnsupportedClassVersionError:
 *   com/google/ai/edge/litertlm/Message$Companion has been compiled by a more
 *   recent version of the Java Runtime (class file version 65.0), this version
 *   of the Java Runtime only recognizes class file versions up to 61.0
 * ```
 *
 * So a test that touched `com.google.ai.edge.litertlm.Engine` directly would fail
 * at class-load, before asserting anything. Every LiteRT-LM type in this package
 * is reached only through these interfaces, and the whole unit suite runs against
 * `FakeLiteRtLmEngine` (in `src/test`) instead. That is not a workaround dressed
 * up as a design: the backend's job — mapping the frozen `GenerationRequest` onto
 * a streaming runtime, deciding when to stop, mapping failures onto
 * `StopReason` — is pure logic and deserves to be tested without a 22 MB native
 * engine attached.
 *
 * The one file that *does* import `com.google.ai.edge.litertlm` is
 * [NativeLiteRtLmEngine], and it is deliberately the only one. If the LiteRT-LM
 * API changes shape, that file is the only one that breaks.
 *
 * ## Shape
 *
 * This mirrors LiteRT-LM's own lifecycle — engine, then a conversation per
 * request — because the mapping has to be faithful. See [LiteRtLmEngineFactory]
 * for why a conversation is per-request rather than long-lived.
 */
interface LiteRtLmEngine : AutoCloseable {

    /** True while the native engine holds a loaded model. */
    val isAlive: Boolean

    /**
     * Opens a conversation for one request.
     *
     * @throws LiteRtLmEngineException if the runtime refuses the request.
     */
    fun openConversation(request: LiteRtLmConversationRequest): LiteRtLmConversation

    /**
     * Cooperative cancellation. Must be safe when idle, and safe to call from any
     * thread, because [LiteRtLmBackend.cancel] is a plain non-suspending call that
     * a UI thread makes while the generation runs on an IO thread.
     */
    fun cancel()

    override fun close()
}

/**
 * One in-flight generation. Closed by the backend exactly once, whether it
 * completed, failed, or was cancelled.
 */
interface LiteRtLmConversation : AutoCloseable {

    /**
     * Sends [turn] and streams the reply.
     *
     * [listener] is called from a runtime-owned thread, never the caller's. This
     * call **blocks** until the runtime reports a terminal event
     * ([LiteRtLmListener.onDone] or [LiteRtLmListener.onError]) — LiteRT-LM's
     * callback API is fire-and-forget, so somebody has to block, and hiding that
     * behind a Flow would only move the problem to the caller.
     */
    fun send(turn: LiteRtLmTurn, listener: LiteRtLmListener)

    /** True while the underlying native conversation is usable. */
    val isAlive: Boolean

    /**
     * Cooperative cancellation of the in-flight [send]. Must be safe to call
     * when nothing is running, and from any thread, because [LiteRtLmBackend.cancel]
     * is a plain non-suspending call made from a UI thread while the generation
     * runs on an IO thread.
     *
     * Safe to call repeatedly: only the first call reaches the runtime.
     */
    fun cancel()

    override fun close()
}

/** Terminal and incremental events from a running generation. */
interface LiteRtLmListener {

    /**
     * A piece of newly generated text.
     *
     * LiteRT-LM's callback is specified as *cumulative* — each callback carries
     * the whole reply so far — but the runtime has also been observed emitting
     * deltas. The backend does not guess: it reconciles the two itself. See
     * [LiteRtLmDeltaTracker], which is the tested answer to "which is it?".
     */
    fun onDelta(text: String)

    /** The runtime finished on its own (EOS, or its own token ceiling). */
    fun onDone()

    /** The runtime failed. The backend reports this as `StopReason.ERROR`. */
    fun onError(error: Throwable)
}

/**
 * Creates the engine. Injected so tests never reach native code.
 *
 * ## One engine, many conversations
 *
 * A LiteRT-LM `Engine` owns the weights and is expensive to build — the docs warn
 * `initialize()` can take ten seconds. It is created once per [LiteRtLmBackend.load]
 * and torn down by `unload()`. The *conversation* is cheap and is opened per
 * request instead.
 *
 * That is a deliberate departure from how a chat UI would use this API, where one
 * conversation is kept alive across turns to reuse its KV cache. It is forced by
 * the frozen `ModelBackend` contract: `GenerationRequest` carries the entire
 * message history on every call, and `AgentController` compacts that history
 * between turns. Holding server-side conversational state would mean the engine
 * believed in a transcript the core may already have rewritten, and the two would
 * silently disagree. Rebuilding from the supplied transcript is the only way the
 * backend can never be wrong about what the model has seen.
 */
fun interface LiteRtLmEngineFactory {

    /**
     * Builds and initializes an engine for [config].
     *
     * @throws LiteRtLmEngineException if the model cannot be loaded.
     */
    fun create(config: LiteRtLmEngineConfig): LiteRtLmEngine
}
/**
 * Everything needed to build an engine, in backend-neutral terms.
 *
 * Deliberately carries a filesystem [modelPath] and nothing Android-specific.
 * LiteRT-LM opens the model file itself and takes a path, not a descriptor, so a
 * SAF document cannot be handed over the way `llama.cpp` receives
 * `/proc/self/fd/N`. See [LiteRtLmModelSource] for how a `ModelSpec` becomes a
 * path, and for why copying a 2 GB model into app storage is the wrong answer.
 */
data class LiteRtLmEngineConfig(
    val modelPath: String,
    val contextLength: Int,
    val numThreads: Int,
    val cacheDir: String?,
    val enableBenchmark: Boolean,
    /**
     * Which piece of hardware this engine should run on.
     *
     * Additive and defaulted to [AcceleratorKind.CPU] so every existing call site
     * keeps its behaviour. The resolved report travels separately rather than
     * riding in here, because this config is the *request* and the report is the
     * *outcome* — a field claiming to be the outcome would be a guess until the
     * engine is built.
     */
    val accelerator: AcceleratorKind = AcceleratorKind.CPU,
    /**
     * `Backend.NPU(nativeLibraryDir)` — the directory LiteRT-LM dlopens vendor
     * dispatch delegates from.
     *
     * Null for every non-NPU tier, and ignored by them. Carried here rather than
     * captured in the native adapter so the whole engine request stays one value a
     * test can assert on.
     */
    val nativeLibraryDir: String? = null,
)

/** One request's worth of conversation state. */
data class LiteRtLmConversationRequest(
    /** The system prompt, or null when the request has none. */
    val systemInstruction: String?,
    /**
     * Everything before the final turn. These are replayed into a fresh
     * conversation on every call, because the frozen request is the whole truth
     * about the transcript.
     */
    val history: List<LiteRtLmTurn>,
    val sampler: LiteRtLmSampler,
)

/**
 * A single message, in backend-neutral form.
 *
 * This is the *intermediate representation* between `dev.localintelligence.core.model.ChatMessage`
 * and LiteRT-LM's `Message`. It exists so the mapping is testable: `ChatMessage`
 * is a pure-JVM type that a JDK 17 test JVM can load, and LiteRT-LM's `Message` is
 * a Java 21 type that it cannot. Mapping `ChatMessage` to this, and this to
 * `Message`, means the interesting half — which role, which turn is last, where
 * the system prompt goes — is covered by real tests.
 */
data class LiteRtLmTurn(
    val role: LiteRtLmRole,
    val text: String,
    /**
     * Set only for [LiteRtLmRole.TOOL]: the tool that produced [text]. Needed
     * because LiteRT-LM models a tool result as a named response rather than as
     * free text, and a bare string would lose which tool it came from.
     */
    val toolName: String? = null,
)

enum class LiteRtLmRole { SYSTEM, USER, MODEL, TOOL }

/**
 * Sampling settings, in the subset LiteRT-LM actually accepts.
 *
 * ## What was dropped, and why it is not a bug
 *
 * `core`'s `SamplingParams` has six fields. LiteRT-LM's `SamplerConfig` has four,
 * and they do not line up one-to-one:
 *
 * - `minP` — no equivalent. LiteRT-LM's `topK` + `topP` already do this job.
 *   Dropped rather than silently folded into `topP`, because folding would change
 *   the meaning of a value the caller set on purpose.
 * - `repeatPenalty` — no equivalent in the engine. Dropped; documented here so
 *   nobody reads its absence as an oversight.
 * - `maxOutputTokens` — **not** dropped, because silently ignoring it would let a
 *   runaway generation run unbounded on a phone. The backend enforces it itself as
 *   a hard budget; see [LiteRtLmBackend].
 * - `seed` — maps straight through. `-1` means "let the engine choose", which is
 *   also LiteRT-LM's own convention.
 *
 * `topK` is not in `SamplingParams` at all, but LiteRT-LM wants a value, so it is
 * a backend-level knob with a documented default.
 */
data class LiteRtLmSampler(
    val topK: Int,
    val topP: Double,
    val temperature: Double,
    val seed: Int,
)

/**
 * What the runtime measured, in units the backend can map onto `GenerationResult`
 * without inventing anything.
 *
 * LiteRT-LM's `BenchmarkInfo` is only populated when its `ExperimentalFlags.enableBenchmark`
 * is set before the engine is constructed. When it is not, the runtime reports
 * nulls, and the backend falls back to its own wall clock rather than reporting
 * zeros that would look like a very fast phone. See [LiteRtLmBackend].
 */
data class LiteRtLmStatistics(
    /** Wall time to the first generated token. Prefill-dominated. */
    val timeToFirstTokenMs: Long,
    val prefillTokens: Int,
    val decodeTokens: Int,
    val prefillTokensPerSecond: Double,
    val decodeTokensPerSecond: Double,
)

/** A failure from the runtime, as a checked exception rather than a silent null. */
class LiteRtLmEngineException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
