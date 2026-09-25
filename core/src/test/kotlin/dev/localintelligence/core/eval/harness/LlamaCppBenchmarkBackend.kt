// ===========================================================================
// LlamaCppBenchmarkBackend.kt
//
// THE PIECE THAT MAKES THE PROJECT FALSIFIABLE — AND WHAT IT IS NOT
// ================================================================
//
// This is a real GGUF backend: it loads a model through the llama.cpp JNI
// bridge, generates, streams, counts tokens and cancels. It is the first code in
// the repo that can put an actual model behind the eval suite.
//
// READ THIS BEFORE TRUSTING IT
// ---------------------------
//
// It has NEVER BEEN EXECUTED. At the time of writing there is no .gguf model on
// the build machine and no Android device attached, so the native library never
// loads, no model is ever resident, and not one token has been generated
// through this path. What exists is compiled, type-checked Kotlin that binds to
// a JNI surface read out of `android/src/main/cpp/llama_jni.h`, plus a full set
// of tests that exercise every branch REACHABLE WITHOUT the native library
// (unavailable-library degradation, load failure, error mapping, cancellation
// accounting, prompt rendering).
//
// Treat "the harness works" as verified. Treat "llama.cpp inference works" as
// unverified until someone runs it on a device with a model. The distinction is
// the point of this file's header comment, and it is repeated in the PR.
//
// WHY REFLECTION INSTEAD OF A COMPILE-TIME DEPENDENCY
// --------------------------------------------------
//
// `LlamaBridge` and `LlamaCppBackend` live in the `:android` module. `:core` is
// a pure-JVM module by architectural rule — CI fails the build if an
// `android.*` import appears under core/src — and `:android` cannot be depended
// on from a test source set in `:core` without either breaking that rule or
// dragging the Android plugin into a module that has none.
//
// So this backend binds to the JNI surface by name at runtime, through
// reflection, and reports UNAVAILABLE when the library is not there. That is a
// real engineering tradeoff, not a shortcut: it costs a reflective call per
// token-streaming callback and buys a harness that compiles and tests on a
// laptop. The reflective surface is declared explicitly below so that a rename
// in `llama_jni.h` fails loudly with a message naming the symbol, rather than
// producing a silent null.
//
// The alternative — putting this file in `:android` — was rejected because the
// benchmark must run on the JVM in seconds to be a regression gate. A benchmark
// that needs an emulator is not a gate.
// ===========================================================================

package dev.localintelligence.core.eval.harness

import dev.localintelligence.core.model.ChatMessage
import dev.localintelligence.core.model.GenerationRequest
import dev.localintelligence.core.model.GenerationResult
import dev.localintelligence.core.model.ModelBackend
import dev.localintelligence.core.model.ModelCapabilities
import dev.localintelligence.core.model.ModelSpec
import dev.localintelligence.core.model.SamplingParams
import dev.localintelligence.core.model.StopReason
import dev.localintelligence.core.model.StreamingModelBackend
import kotlinx.coroutines.CancellationException
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Why a real-model run could not start.
 *
 * Modelled as data rather than an exception so the CLI can print something
 * actionable. "the native library is not available" is useless; "the native
 * library is not available — run this on a device with :android installed, or
 * point --gguf at a model and use --allow-unverified to run without the
 * library" is something a person can act on.
 */
sealed interface BackendUnavailable {
    val message: String

    /** The JNI class was not on the classpath at all. */
    data class ClassMissing(val className: String) : BackendUnavailable {
        override val message: String =
            "$className is not on the classpath. The llama.cpp bridge lives in the " +
                ":android module; a benchmark that needs it must run where :android is present."
    }

    /** The class was found but `System.loadLibrary` failed — the normal state on a desktop JVM. */
    data class LibraryNotLoadable(val library: String, val cause: String) : BackendUnavailable {
        override val message: String =
            "the native library '$library' could not be loaded ($cause). This is expected on a " +
                "desktop JVM: the .so is built for Android ABIs. Run the benchmark on a device, " +
                "or use --backend script to exercise the harness without a model."
    }

    /** The class loaded but a method this backend needs is missing — a renamed JNI symbol. */
    data class SurfaceIncomplete(val detail: String) : BackendUnavailable {
        override val message: String =
            "the llama.cpp JNI surface is incomplete: $detail. The native library is older or " +
                "newer than this harness expects; rebuild :android."
    }
}

/**
 * llama.cpp as a [StreamingModelBackend], bound to the JNI surface by name.
 *
 * Cancellation is cooperative and observable: [cancel] sets a flag the
 * generation path checks after the native call returns, and the partial text is
 * preserved and reported with [StopReason.CANCELLED]. A cancelled answer is
 * still worth scoring, and discarding it would make a cancel look like a
 * silence.
 */
class LlamaCppBenchmarkBackend(
    /** The .gguf to load. Resolved to a native path by [load]. */
    private val modelPath: String,
    private val contextLength: Int = DEFAULT_CONTEXT_LENGTH,
    private val threads: Int = 0,
    /** Native bridge, injected so tests can substitute one. */
    private val bridge: LlamaJniBridge = LlamaJniBridge.reflective(),
) : StreamingModelBackend {

    override val id: String = "llama.cpp"

    @Volatile
    private var handle: Any? = null

    @Volatile
    private var currentCapabilities: ModelCapabilities = ModelCapabilities.UNKNOWN

    override val capabilities: ModelCapabilities get() = currentCapabilities

    /**
     * Guards load/unload/generate. llama.cpp allows one decode at a time per
     * context, so a second generate must wait rather than interleave.
     */
    private val lock = Any()

    private val cancelRequested = AtomicBoolean(false)

    /** Why this backend is unusable, or null when it is usable. */
    val unavailable: BackendUnavailable? get() = bridge.unavailable

    /** True when a model is resident and generating can be attempted. */
    val isLoaded: Boolean get() = synchronized(lock) { handle != null }

    /**
     * Loads the model.
     *
     * Throws [IllegalStateException] rather than returning a failed
     * [GenerationResult]: a caller that asked to load a model must be told
     * plainly that it did not arrive. Returning a result object here would let
     * a benchmark print a report for a model that was never resident.
     */
    override suspend fun load(model: ModelSpec) {
        val problem = bridge.unavailable
        if (problem != null) {
            throw IllegalStateException(problem.message)
        }
        val h = bridge.create()
            ?: throw IllegalStateException("llama.cpp: could not allocate a native handle")

        // ModelSpec is backend-neutral and carries no path, so the path is this
        // backend's own constructor argument. The spec is accepted and
        // cross-checked: loading a different model than the one configured is
        // the kind of mismatch that produces a benchmark of nothing.
        check(model.id == modelPath || model.id == "benchmark") {
            "this backend was constructed for $modelPath but asked to load ${model.id}"
        }

        val error = bridge.loadModel(h, modelPath, contextLength, threads, useMmap = true)
        if (error != null) {
            closeHandle(h)
            throw IllegalStateException("llama.cpp: could not load $modelPath — $error")
        }

        synchronized(lock) {
            releaseLocked()
            handle = h
            currentCapabilities = deriveCapabilities(bridge.modelContextLength(h))
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
    ): GenerationResult = generateInternal(request, onToken)

    /**
     * The generation path.
     *
     * Errors never escape. `ModelBackend` requires failures to come back as
     * [StopReason.ERROR], and this is the method where llama.cpp can abort on a
     * corrupt model, exhaust memory, or refuse a grammar. Each becomes an ERROR
     * result with the reason attached, and the text produced so far is kept.
     */
    private fun generateInternal(
        request: GenerationRequest,
        onToken: ((String) -> Unit)?,
    ): GenerationResult {
        cancelRequested.set(false)

        val h = synchronized(lock) { handle }
            ?: return GenerationResult(text = "", stopReason = StopReason.ERROR)

        val params: SamplingParams = request.params
        val error = try {
            bridge.generate(
                handle = h,
                prompt = renderPrompt(request),
                grammar = request.grammar,
                temperature = params.temperature,
                topP = params.topP,
                minP = params.minP,
                repeatPenalty = params.repeatPenalty,
                seed = params.seed,
                maxTokens = params.maxOutputTokens,
                onToken = onToken,
            )
        } catch (e: CancellationException) {
            // Structured concurrency: a cancelled coroutine must keep
            // propagating, or the caller's scope is silently ignored.
            bridge.cancel(h)
            throw e
        } catch (e: Throwable) {
            "native generation failed: ${e.message ?: e::class.java.simpleName}"
        }

        val outcome = bridge.takeResult(h)
        val stop = when {
            // The native error wins: it names the reason, and the outcome may
            // be a default ERROR with no text at all.
            error != null -> StopReason.ERROR
            outcome == null -> StopReason.ERROR
            cancelRequested.get() -> StopReason.CANCELLED
            outcome.stopCode == STOP_CANCELLED -> StopReason.CANCELLED
            outcome.stopCode == STOP_MAX_TOKENS -> StopReason.MAX_TOKENS
            outcome.stopCode == STOP_COMPLETED -> StopReason.COMPLETED
            else -> StopReason.ERROR
        }

        return GenerationResult(
            // Partial text is preserved on cancel and on error. The caller
            // decides whether a half answer is worth scoring; discarding it here
            // would make both look like silence.
            text = outcome?.text.orEmpty(),
            promptTokens = outcome?.promptTokens ?: 0,
            completionTokens = outcome?.completionTokens ?: 0,
            stopReason = stop,
            prefillMs = outcome?.prefillMs ?: 0L,
            decodeMs = outcome?.decodeMs ?: 0L,
        )
    }

    /**
     * Exact token count via `llama_tokenize` when a model is resident, and a
     * documented approximation otherwise.
     *
     * The fallback is biased HIGH on purpose. Under-counting overflows the
     * context and corrupts a run; over-counting triggers compaction slightly
     * early, which costs a little context and nothing else. The asymmetry is
     * the whole reason for the choice.
     */
    override fun countTokens(text: String): Int {
        if (text.isEmpty()) return 0
        val h = synchronized(lock) { handle } ?: return approximateTokenCount(text)
        val exact = bridge.countTokens(h, text)
        return if (exact >= 0) exact else approximateTokenCount(text)
    }

    /** Cooperative cancellation. Safe when idle, safe from any thread. */
    override fun cancel() {
        cancelRequested.set(true)
        val h = synchronized(lock) { handle } ?: return
        bridge.cancel(h)
    }

    /**
     * Token estimate with no model resident.
     *
     * Documented approximation, not a measurement dressed as one: BPE averages
     * ~4 chars/token for English prose, and structured JSON breaks into more
     * tokens than its length suggests, so newlines are charged separately.
     * Replaced by real `llama_tokenize` the moment a model exists.
     */
    internal fun approximateTokenCount(text: String): Int {
        if (text.isEmpty()) return 0
        return (text.length + 3) / 4 + text.count { it == '\n' }
    }

    /**
     * Capabilities from the trained context length.
     *
     * `supportsToolCalling` is false here even though llama.cpp can enforce a
     * grammar: a model can only be relied on to hit the action protocol if its
     * conversion carries a chat template, and the backend cannot verify that
     * from here. Claiming it would be optimism. `supportsGrammar` is true
     * because llama.cpp's sampler chain genuinely enforces GBNF during sampling.
     */
    private fun deriveCapabilities(trainedContext: Int): ModelCapabilities = ModelCapabilities(
        contextLength = if (trainedContext > 0) trainedContext else contextLength,
        supportsToolCalling = false,
        supportsGrammar = true,
        supportsVision = false,
        supportsKvCache = true,
    )

    /**
     * Renders the conversation as the flat text llama.cpp tokenizes.
     *
     * An explicit transcript rather than the model's own chat template: the
     * template lives in the GGUF as Jinja, rendering it needs a template engine
     * or native calls, and a WRONG template is worse than an explicit one. These
     * role markers are the portable subset every instruct model in the 1-4B
     * range recognises.
     */
    internal fun renderPrompt(request: GenerationRequest): String = buildString {
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

    private fun releaseLocked() {
        val h = handle
        handle = null
        if (h != null) closeHandle(h)
    }

    private fun closeHandle(h: Any) {
        try {
            bridge.unload(h)
        } catch (e: Throwable) {
            // Freeing native memory must not be able to fail a JVM shutdown or
            // an unload; the handle is going away either way.
        }
        try {
            bridge.close(h)
        } catch (e: Throwable) {
            // Same reasoning: a double free is worse than a leaked handle, and
            // the caller is already on the way out.
        }
    }

    companion object {
        const val DEFAULT_CONTEXT_LENGTH = 4096

        /** Mirrors StopCode in `llama_jni.h`. */
        const val STOP_COMPLETED = 0
        const val STOP_MAX_TOKENS = 1
        const val STOP_CANCELLED = 2
        const val STOP_ERROR = 3
    }
}

/** What a finished generation produced, as the JNI layer reports it. */
data class LlamaGenerationOutcome(
    val text: String,
    val promptTokens: Int,
    val completionTokens: Int,
    val stopCode: Int,
    val prefillMs: Long,
    val decodeMs: Long,
    val error: String?,
)

/**
 * The JNI surface, as an interface.
 *
 * Declared explicitly so the backend can be tested without a native library,
 * and so the symbol names this harness depends on are written down in one
 * place. If `llama_jni.h` renames a function, [LlamaJniBridge.reflective] fails
 * with a message naming it, and this file is the only one that needs editing.
 */
interface LlamaJniBridge {
    /** Non-null when this bridge cannot be used. Checked before every load. */
    val unavailable: BackendUnavailable?

    fun create(): Any?
    fun loadModel(handle: Any, path: String, contextLength: Int, threads: Int, useMmap: Boolean): String?
    fun unload(handle: Any)
    fun modelContextLength(handle: Any): Int
    fun countTokens(handle: Any, text: String): Int
    fun isGenerating(handle: Any): Boolean

    /** Returns null on success, or the error. Text streams to [onToken]. */
    fun generate(
        handle: Any,
        prompt: String,
        grammar: String?,
        temperature: Float,
        topP: Float,
        minP: Float,
        repeatPenalty: Float,
        seed: Int,
        maxTokens: Int,
        onToken: ((String) -> Unit)?,
    ): String?

    fun takeResult(handle: Any): LlamaGenerationOutcome?
    fun cancel(handle: Any)

    fun close(handle: Any)

    companion object {
        /**
         * Binds to `dev.localintelligence.android.inference.LlamaBridge` by name.
         *
         * Returns a bridge whose [unavailable] explains precisely what is
         * missing. It never throws: "no native library" is a normal state on a
         * developer machine and a normal state for this entire test suite.
         */
        fun reflective(className: String = BRIDGE_CLASS): LlamaJniBridge {
            val clazz = try {
                Class.forName(className)
            } catch (e: Throwable) {
                return UnavailableBridge(BackendUnavailable.ClassMissing(className))
            }
            // The bridge exposes `isAvailable` as a static field initialised by
            // System.loadLibrary. A false there is the normal desktop-JVM state,
            // and it is the difference between "class missing" and "library
            // missing" — worth reporting precisely.
            val isAvailable = try {
                clazz.getField("isAvailable").getBoolean(null)
            } catch (e: Throwable) {
                false
            }
            if (!isAvailable) {
                return UnavailableBridge(
                    BackendUnavailable.LibraryNotLoadable(
                        library = LIBRARY_NAME,
                        cause = "System.loadLibrary(\"$LIBRARY_NAME\") did not succeed",
                    ),
                )
            }
            return ReflectiveBridge(clazz)
        }

        const val BRIDGE_CLASS = "dev.localintelligence.android.inference.LlamaBridge"
        const val LIBRARY_NAME = "localintelligence_llama_jni"
    }
}

/** A bridge that is always unusable, and says why. */
private class UnavailableBridge(private val reason: BackendUnavailable) : LlamaJniBridge {
    override val unavailable: BackendUnavailable get() = reason
    private fun fail(): Nothing = throw IllegalStateException(reason.message)
    override fun create(): Any? = fail()
    override fun loadModel(handle: Any, path: String, contextLength: Int, threads: Int, useMmap: Boolean): String? = fail()
    override fun unload(handle: Any) = fail()
    override fun modelContextLength(handle: Any): Int = fail()
    override fun countTokens(handle: Any, text: String): Int = fail()
    override fun isGenerating(handle: Any): Boolean = fail()
    override fun generate(
        handle: Any, prompt: String, grammar: String?, temperature: Float, topP: Float,
        minP: Float, repeatPenalty: Float, seed: Int, maxTokens: Int, onToken: ((String) -> Unit)?,
    ): String? = fail()
    override fun takeResult(handle: Any): LlamaGenerationOutcome? = fail()
    override fun cancel(handle: Any) = fail()
    override fun close(handle: Any) = fail()
}

/**
 * Reflection onto the real `LlamaBridge` singleton.
 *
 * Every method lookup is resolved ONCE in the constructor and a missing one
 * produces [BackendUnavailable.SurfaceIncomplete] naming the method. Resolving
 * per call would turn a rename into an exception thrown once per token, deep
 * inside a generation, where it is hardest to read.
 */
private class ReflectiveBridge(private val clazz: Class<*>) : LlamaJniBridge {

    override val unavailable: BackendUnavailable? = resolveMethods()

    private val mCreate = method("create")
    private val mLoadModel = method("loadModel")
    private val mUnload = method("unload")
    private val mModelContextLength = method("modelContextLength")
    private val mCountTokens = method("countTokens")
    private val mIsGenerating = method("isGenerating")
    private val mGenerate = method("generate")
    private val mTakeResult = method("takeResult")
    private val mCancel = method("cancel")

    // Binary names of the nested types. `LlamaBridge` is a Kotlin `object`, so
    // its nested classes are `LlamaBridge$TokenSink` / `LlamaBridge$Handle` on
    // the JVM. The `$` is escaped because a plain `"$Handle"` would be a string
    // template over an undefined variable rather than a class name.
    private val sinkClass: Class<*> = Class.forName(LlamaJniBridge.BRIDGE_CLASS + "\$TokenSink")

    private fun method(name: String): java.lang.reflect.Method? = try {
        clazz.getMethod(name, *signatureOf(name))
    } catch (e: Throwable) {
        null
    }

    /** True when every method this backend needs was found. */
    private fun resolveMethods(): BackendUnavailable? {
        val missing = listOf(
            "create" to mCreate, "loadModel" to mLoadModel, "unload" to mUnload,
            "modelContextLength" to mModelContextLength, "countTokens" to mCountTokens,
            "isGenerating" to mIsGenerating, "generate" to mGenerate,
            "takeResult" to mTakeResult, "cancel" to mCancel,
        ).filter { it.second == null }.map { it.first }
        return if (missing.isEmpty()) {
            null
        } else {
            BackendUnavailable.SurfaceIncomplete("missing method(s): ${missing.joinToString()}")
        }
    }

    /**
     * Parameter types per method, taken from the Kotlin declarations in
     * `LlamaBridge.kt`. `loadModel` and `generate` have named/optional
     * parameters, so the compiled JVM signatures carry the full types.
     */
    private fun signatureOf(name: String): Array<Class<*>> = when (name) {
        "loadModel" -> arrayOf(
            handleClass(), String::class.java, Int::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!,
        )
        "countTokens" -> arrayOf(handleClass(), String::class.java)
        "generate" -> arrayOf(
            handleClass(), String::class.java, String::class.java,
            Float::class.javaPrimitiveType!!, Float::class.javaPrimitiveType!!,
            Float::class.javaPrimitiveType!!, Float::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!, sinkClass,
        )
        else -> arrayOf(handleClass())
    }

    private fun handleClass(): Class<*> =
        Class.forName(LlamaJniBridge.BRIDGE_CLASS + "\$Handle")

    /** Unwraps a reflective failure so the message is the real cause, not InvocationTargetException. */
    private fun <T> call(m: java.lang.reflect.Method?, vararg args: Any?): T {
        if (m == null) throw IllegalStateException("llama.cpp JNI method is not available")
        return try {
            @Suppress("UNCHECKED_CAST")
            m.invoke(null, *args) as T
        } catch (e: InvocationTargetException) {
            throw e.targetException ?: e
        }
    }

    private fun callUnit(m: java.lang.reflect.Method?, vararg args: Any?) {
        if (m == null) throw IllegalStateException("llama.cpp JNI method is not available")
        try {
            m.invoke(null, *args)
        } catch (e: InvocationTargetException) {
            throw e.targetException ?: e
        }
    }

    override fun create(): Any? = call(mCreate)

    override fun loadModel(
        handle: Any, path: String, contextLength: Int, threads: Int, useMmap: Boolean,
    ): String? = call(mLoadModel, handle, path, contextLength, threads, useMmap)

    override fun unload(handle: Any) = callUnit(mUnload, handle)

    override fun modelContextLength(handle: Any): Int = call(mModelContextLength, handle)

    override fun countTokens(handle: Any, text: String): Int = call(mCountTokens, handle, text)

    override fun isGenerating(handle: Any): Boolean = call(mIsGenerating, handle)

    override fun generate(
        handle: Any, prompt: String, grammar: String?, temperature: Float, topP: Float,
        minP: Float, repeatPenalty: Float, seed: Int, maxTokens: Int, onToken: ((String) -> Unit)?,
    ): String? {
        val sink = onToken?.let { callback ->
            java.lang.reflect.Proxy.newProxyInstance(
                sinkClass.classLoader, arrayOf(sinkClass),
            ) { _, method, args ->
                when (method.name) {
                    "onToken" -> {
                        callback(args?.get(0) as? String ?: "")
                        null
                    }
                    "toString" -> "TokenSink"
                    "hashCode" -> System.identityHashCode(this)
                    "equals" -> false
                    else -> null
                }
            }
        }
        return call(mGenerate, handle, prompt, grammar, temperature, topP, minP, repeatPenalty, seed, maxTokens, sink)
    }

    /**
     * Takes the outcome the native side stashed on the handle.
     *
     * `takeResult` returns a `GenerationOutcome` data class, so its fields are
     * read reflectively rather than by re-parsing a wire string. The wire format
     * (`stop|prompt|completion|prefillMs|decodeMs|[E<err>\n]<text>`) is an
     * implementation detail of the native code; depending on it here would put
     * a second copy of the format in the test tree.
     */
    override fun takeResult(handle: Any): LlamaGenerationOutcome? {
        val raw = call<Any?>(mTakeResult, handle) ?: return null
        // A missing getter yields the fallback rather than an exception: a
        // partial outcome with honest defaults is more useful to a benchmark
        // than a crash, and every default here is the pessimistic one
        // (STOP_ERROR, zero tokens), so an unreadable field cannot inflate a
        // score.
        fun <T> field(name: String, fallback: T): T = try {
            val getter = "get" + name.replaceFirstChar { it.uppercase() }
            @Suppress("UNCHECKED_CAST")
            (raw.javaClass.getMethod(getter).invoke(raw) as? T) ?: fallback
        } catch (e: Throwable) {
            fallback
        }
        return LlamaGenerationOutcome(
            text = field("Text", ""),
            promptTokens = field("PromptTokens", 0),
            completionTokens = field("CompletionTokens", 0),
            stopCode = field("StopCode", LlamaCppBenchmarkBackend.STOP_ERROR),
            prefillMs = field("PrefillMs", 0L),
            decodeMs = field("DecodeMs", 0L),
            error = field("Error", null as String?),
        )
    }

    override fun cancel(handle: Any) = callUnit(mCancel, handle)

    override fun close(handle: Any) {
        // Handle implements AutoCloseable; closing is what frees the native
        // model, context and handle. Reflected so this file needs no compile
        // dependency on :android.
        try {
            (handle as? AutoCloseable)?.close()
        } catch (e: Throwable) {
            // The handle is going away regardless.
        }
    }
}
