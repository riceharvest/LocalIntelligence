package dev.localintelligence.android.inference

/**
 * The complete JNI surface over the llama.cpp native library.
 *
 * Everything here is a thin, allocation-conscious wrapper. There is no state
 * beyond the native handle, and no logic that could live on the JVM side: token
 * counting, sampling and decoding all have to happen in native memory.
 *
 * ## When the native library is missing
 *
 * [isAvailable] is false when `liblocalintelligence_llama_jni.so` did not load, which is
 * the normal state on a JVM unit-test host and on an ABI the build did not
 * produce. Every method then returns a typed failure instead of throwing
 * `UnsatisfiedLinkError`, so [LlamaCppBackend] can report
 * `StopReason.ERROR` and the app degrades to a clear message rather than
 * crashing. That is the difference between a backend that is missing and a bug.
 *
 * ## Handle lifetime
 *
 * [create] returns an opaque `Long`. [destroy] frees the model, the context and
 * the handle. Use [use] so it cannot leak.
 */
object LlamaBridge {

    const val LIBRARY_NAME = "localintelligence_llama_jni"

    /**
     * Loads the native library once. A failure here is expected and handled,
     * so it is recorded rather than thrown.
     */
    val isAvailable: Boolean = try {
        System.loadLibrary(LIBRARY_NAME)
        true
    } catch (e: UnsatisfiedLinkError) {
        false
    } catch (e: SecurityException) {
        false
    }

    /** llama.cpp's own build description, for the diagnostics screen. */
    fun systemInfo(): String? =
        if (isAvailable) nativeSystemInfo() else null

    /** Receives streamed text. Called on the generation thread, never on main. */
    fun interface TokenSink {
        fun onToken(piece: String)
    }

    /** A native handle plus the close that must accompany it. */
    class Handle internal constructor(internal val ptr: Long) : AutoCloseable {
        private val closed = java.util.concurrent.atomic.AtomicBoolean(false)

        val isClosed: Boolean get() = closed.get()

        override fun close() {
            // destroy() frees the model and the context; it is safe on an
            // already-freed pointer, but the guard keeps a double close from
            // becoming a double free of the handle itself.
            if (closed.compareAndSet(false, true) && ptr != 0L) {
                nativeDestroy(ptr)
            }
        }
    }

    /** Creates a native handle, or null if the library is absent. */
    fun create(): Handle? {
        if (!isAvailable) return null
        val ptr = nativeCreate()
        return if (ptr == 0L) null else Handle(ptr)
    }

    /**
     * Loads a model from [path], which is `/proc/self/fd/N` for a SAF-selected
     * file. Returns null on success, or the reason it failed.
     *
     * [contextLength] is clamped to the model's trained length natively; 0 means
     * "use the trained length".
     */
    fun loadModel(
        handle: Handle,
        path: String,
        contextLength: Int = 0,
        threads: Int = 0,
        useMmap: Boolean = true,
    ): String? = if (isAvailable) {
        nativeLoadModel(handle.ptr, path, contextLength, threads, useMmap)
    } else {
        "the llama.cpp native library is not available on this device"
    }

    /** Frees the model and context. The handle remains usable. */
    fun unload(handle: Handle) {
        if (isAvailable && !handle.isClosed) nativeUnload(handle.ptr)
    }

    /** Trained context length, or 0 if no model is loaded. */
    fun modelContextLength(handle: Handle): Int =
        if (isAvailable) nativeModelContextLength(handle.ptr) else 0

    /**
     * Exact token count via `llama_tokenize`, or -1 when no model is loaded.
     * The backend falls back to a heuristic in that case, because context
     * budgeting has to work before a model is resident.
     */
    fun countTokens(handle: Handle, text: String): Int =
        if (isAvailable) nativeCountTokens(handle.ptr, text) else -1

    /**
     * Loads a multimodal projector (an `mmproj-*.gguf`) for the loaded model.
     * Returns null on success, or the error. Replaced by the next call, freed
     * with the model.
     */
    fun loadProjector(handle: Handle, path: String): String? =
        if (isAvailable) nativeLoadProjector(handle.ptr, path) else "the llama.cpp native library is not available"

    /** What the loaded projector reads: [CAP_IMAGE] and/or [CAP_AUDIO], 0 without one. */
    fun projectorCaps(handle: Handle): Int = if (isAvailable) nativeProjectorCaps(handle.ptr) else 0

    /**
     * Encoded attachments (jpg, png, wav, mp3...) for the next [generate] only.
     * The prompt must contain one [mediaMarker] per attachment, in order.
     */
    fun setMedia(handle: Handle, media: List<ByteArray>) {
        if (isAvailable) nativeSetMedia(handle.ptr, media.toTypedArray())
    }

    /** The placeholder libmtmd replaces with an attachment's embeddings. */
    val mediaMarker: String by lazy { if (isAvailable) nativeMediaMarker() else "<__media__>" }

    const val CAP_IMAGE = 1
    const val CAP_AUDIO = 2

    /**
     * Generates, streaming to [onToken]. Returns null on success, or the error.
     * The [GenerationOutcome] is then taken with [takeResult].
     */
    fun generate(
        handle: Handle,
        prompt: String,
        grammar: String?,
        temperature: Float,
        topP: Float,
        topK: Int,
        minP: Float,
        repeatPenalty: Float,
        penaltyLastN: Int,
        frequencyPenalty: Float,
        presencePenalty: Float,
        seed: Int,
        maxTokens: Int,
        onToken: TokenSink?,
    ): String? = if (isAvailable) {
        nativeGenerate(
            handle.ptr, prompt, grammar, temperature, topP, topK, minP, repeatPenalty,
            penaltyLastN, frequencyPenalty, presencePenalty, seed, maxTokens, onToken,
        )
    } else {
        "the llama.cpp native library is not available on this device"
    }

    /** Takes the last outcome, or null if the handle is gone. */
    fun takeResult(handle: Handle): GenerationOutcome? {
        if (!isAvailable) return null
        val raw = nativeTakeResult(handle.ptr) ?: return null
        return parseOutcome(raw)
    }

    /** Cooperative cancellation. Safe when idle. */
    fun cancel(handle: Handle) {
        if (isAvailable) nativeCancel(handle.ptr)
    }

    /** True while a generation is in flight on another thread. */
    fun isGenerating(handle: Handle): Boolean =
        isAvailable && nativeIsGenerating(handle.ptr)

    /**
     * Parses the wire format from `nativeTakeResult`:
     * `stop|prompt|completion|prefillMs|decodeMs|error|<text>`.
     *
     * Split on the first six separators only, so a newline or pipe inside the
     * generated text survives intact.
     */
    internal fun parseOutcome(raw: String): GenerationOutcome {
        var idx = 0
        fun next(): String {
            val end = raw.indexOf('|', idx)
            if (end < 0) {
                val s = if (idx <= raw.length) raw.substring(idx) else ""
                idx = raw.length + 1
                return s
            }
            val s = raw.substring(idx, end)
            idx = end + 1
            return s
        }
        val stop = next().toIntOrNull() ?: GenerationOutcome.STOP_ERROR
        val promptTokens = next().toIntOrNull() ?: 0
        val completionTokens = next().toIntOrNull() ?: 0
        val prefillMs = next().toLongOrNull() ?: 0L
        val decodeMs = next().toLongOrNull() ?: 0L
        val rawError = next()
        val error = rawError.ifEmpty { null }
        val text = if (idx <= raw.length) raw.substring(idx) else ""
        return GenerationOutcome(
            text = text,
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            stopCode = stop,
            prefillMs = prefillMs,
            decodeMs = decodeMs,
            error = error,
        )
    }

    // ---- native methods --------------------------------------------------------
    // All guarded by [isAvailable]; calling one when the library is absent
    // throws UnsatisfiedLinkError, which is why every caller checks first.

    @JvmStatic private external fun nativeCreate(): Long
    @JvmStatic private external fun nativeDestroy(ptr: Long)
    @JvmStatic private external fun nativeSystemInfo(): String
    @JvmStatic private external fun nativeLoadModel(
        ptr: Long, path: String, contextLength: Int, threads: Int, useMmap: Boolean,
    ): String?
    @JvmStatic private external fun nativeUnload(ptr: Long)
    @JvmStatic private external fun nativeModelContextLength(ptr: Long): Int
    @JvmStatic private external fun nativeCountTokens(ptr: Long, text: String): Int
    @JvmStatic private external fun nativeGenerate(
        ptr: Long, prompt: String, grammar: String?, temperature: Float, topP: Float,
        topK: Int, minP: Float, repeatPenalty: Float, penaltyLastN: Int,
        frequencyPenalty: Float, presencePenalty: Float, seed: Int, maxTokens: Int,
        sink: TokenSink?,
    ): String?
    @JvmStatic private external fun nativeTakeResult(ptr: Long): String?
    @JvmStatic private external fun nativeLoadProjector(ptr: Long, path: String): String?
    @JvmStatic private external fun nativeProjectorCaps(ptr: Long): Int
    @JvmStatic private external fun nativeSetMedia(ptr: Long, media: Array<ByteArray>)
    @JvmStatic private external fun nativeMediaMarker(): String
    @JvmStatic private external fun nativeCancel(ptr: Long)
    @JvmStatic private external fun nativeIsGenerating(ptr: Long): Boolean
}

/** What a finished generation produced. Mirrors `GenerationResult` in :core. */
data class GenerationOutcome(
    val text: String,
    val promptTokens: Int,
    val completionTokens: Int,
    val stopCode: Int,
    val prefillMs: Long,
    val decodeMs: Long,
    val error: String?,
) {
    companion object {
        const val STOP_COMPLETED = 0
        const val STOP_MAX_TOKENS = 1
        const val STOP_CANCELLED = 2
        const val STOP_ERROR = 3
    }
}
