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

    /**
     * A native handle plus the close that must accompany it.
     *
     * Calls that cannot wait for the backend's lock (cancel, the token counts) use
     * [withPinned]. A generation holds that lock for its whole run, and a cancel has to
     * reach it while it runs, so these calls read the handle without the lock. The pin
     * keeps the native memory alive for the length of the call: [close] only marks the
     * handle, and the memory is freed when the last pin is released, never under a call
     * that is still using it.
     *
     * One atomic int holds both facts. Zero or more is the number of pins; once closed,
     * [CLOSED] is added to it, so the value is negative and no new pin can be taken.
     */
    class Handle internal constructor(internal val ptr: Long) : AutoCloseable {
        private val state = java.util.concurrent.atomic.AtomicInteger(0)

        val isClosed: Boolean get() = state.get() < 0

        /** Takes a pin, or returns false once the handle is closed. Never blocks. */
        internal fun pin(): Boolean {
            while (true) {
                val current = state.get()
                if (current < 0) return false
                if (state.compareAndSet(current, current + 1)) return true
            }
        }

        internal fun unpin() {
            if (state.decrementAndGet() == CLOSED) destroy()
        }

        /**
         * Runs [call] with the native pointer while the handle is open, or returns null
         * once it is closed. Never blocks on anything but the call itself.
         */
        internal inline fun <R> withPinned(call: (Long) -> R): R? {
            if (!pin()) return null
            try {
                return call(ptr)
            } finally {
                unpin()
            }
        }

        override fun close() {
            while (true) {
                val current = state.get()
                // Closing twice is a no-op: destroy() must run exactly once.
                if (current < 0) return
                if (state.compareAndSet(current, current + CLOSED)) {
                    // No pins held: nothing can be using the memory, so free it now.
                    // Otherwise the last unpin does.
                    if (current == 0) destroy()
                    return
                }
            }
        }

        private fun destroy() {
            if (ptr != 0L) nativeDestroy(ptr)
        }

        private companion object {
            /** Added to the pin count on close. Far below any real pin count, so the value stays negative. */
            const val CLOSED = Int.MIN_VALUE
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
        compute: LlamaCompute = LlamaCompute.CPU,
        /** Logits rows one batch may ask for; 0 is a whole batch. See llama_jni.cpp. */
        outputsMax: Int = 0,
    ): String? = if (isAvailable) {
        nativeLoadModel(handle.ptr, path, contextLength, threads, useMmap, compute.gpuBackend, compute.gpuLayers, outputsMax)
    } else {
        "the llama.cpp native library is not available on this device"
    }

    /**
     * The GPUs llama.cpp can run a model on here, one per backend that found one.
     * The first call starts the Vulkan and OpenCL drivers, so it is made only when
     * a GPU is actually wanted (the speed test's comparison), never at launch.
     */
    fun gpuDevices(): List<GpuDevice> {
        if (!isAvailable) return emptyList()
        return nativeGpuDevices().mapNotNull { entry ->
            val parts = entry.split('\t', limit = 2)
            if (parts.size == 2) GpuDevice(backend = parts[0], description = parts[1]) else null
        }
    }

    /** A GPU as llama.cpp names it: [backend] is "Vulkan" or "OpenCL". */
    data class GpuDevice(val backend: String, val description: String)

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
        if (isAvailable) handle.withPinned { nativeCountTokens(it, text) } ?: -1 else -1

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
    const val CAP_SPEECH = 4

    /**
     * Speaks [text] with the loaded model's speech projector: a WAV file, or
     * null when it cannot. Blocking; call it under the backend's generation lock.
     */
    fun speak(handle: Handle, text: String, language: String?, voice: String?, maxFrames: Int = 0): ByteArray? =
        if (isAvailable) nativeSpeak(handle.ptr, text, language, voice, maxFrames) else null

    /**
     * Renders [roles]/[contents] with the loaded model's own chat template, and
     * returns the prompt text, or null when there is no template or llama.cpp
     * does not recognise it. Roles are "system", "user" or "assistant".
     *
     * Null is the signal to fall back to the flat transcript. It is also what a
     * model with no template returns, which is the common case for a base model.
     */
    fun applyTemplate(
        handle: Handle,
        roles: List<String>,
        contents: List<String>,
        addAssistant: Boolean,
    ): String? = if (isAvailable) {
        nativeApplyTemplate(handle.ptr, roles.toTypedArray(), contents.toTypedArray(), addAssistant)
    } else {
        null
    }

    /**
     * Generates, streaming to [onToken]. Returns null on success, or the error.
     * The [GenerationOutcome] is then taken with [takeResult].
     *
     * [parseSpecial] is true for a prompt rendered by [applyTemplate], so the
     * template's control tokens become single special tokens. It is false for the
     * flat transcript, whose text must not be able to produce one.
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
        parseSpecial: Boolean = false,
        /**
         * UTF-8 bytes of [prompt] that are the system prompt, which stays the
         * same from message to message; its state is kept and restored (see
         * llama_jni.cpp, "system prompt anchor"). 0: none.
         */
        stablePrefixBytes: Int = 0,
        /**
         * The reply prepared by [chatPrepare]: its prompt, grammar and parser. [prompt]
         * must be the text [chatPrepare] returned, and [grammar] is then not used.
         * The reply itself is read with [chatResult]; [takeResult] still gives the stop
         * reason and the raw text.
         */
        nativeChat: Boolean = false,
    ): String? = if (isAvailable) {
        nativeGenerate(
            handle.ptr, prompt, grammar, temperature, topP, topK, minP, repeatPenalty,
            penaltyLastN, frequencyPenalty, presencePenalty, seed, maxTokens, onToken, parseSpecial,
            stablePrefixBytes, nativeChat,
        )
    } else {
        "the llama.cpp native library is not available on this device"
    }

    /**
     * Renders [messagesJson] (OpenAI chat messages) with the loaded model's own chat
     * template, and prepares its tool-call parser for [callableToolsJson]. The prompt
     * lists [promptToolsJson] (the same tools every turn of a conversation). Returns
     * `{"prompt": "...", "format": "..."}` as JSON, or null when the model has no
     * chat engine or its template cannot render tools.
     */
    fun chatPrepare(
        handle: Handle,
        messagesJson: String,
        promptToolsJson: String,
        callableToolsJson: String,
    ): String? = if (isAvailable) {
        nativeChatPrepare(handle.ptr, messagesJson, promptToolsJson, callableToolsJson)
    } else {
        null
    }

    /**
     * The reply of the last native generation as `{"content": "...", "tool_calls":
     * [{"name": "...", "arguments": "..."}]}` JSON, or null if there is none.
     */
    fun chatResult(handle: Handle): String? =
        if (isAvailable && !handle.isClosed) nativeChatResult(handle.ptr) else null

    /**
     * The token count of the prompt [chatPrepare] would render for these messages and
     * tools, or -1 when native does not apply. Prepares nothing, so it is safe while the
     * handle's prepared reply is still in use.
     */
    fun chatCount(handle: Handle, messagesJson: String, promptToolsJson: String): Int =
        if (isAvailable) {
            handle.withPinned { nativeChatCount(it, messagesJson, promptToolsJson) } ?: -1
        } else {
            -1
        }

    /** Takes the last outcome, or null if the handle is gone. */
    fun takeResult(handle: Handle): GenerationOutcome? {
        if (!isAvailable) return null
        val raw = nativeTakeResult(handle.ptr) ?: return null
        return parseOutcome(raw)
    }

    /**
     * Cooperative cancellation. Safe when idle, and safe to call while a generation holds
     * the backend's lock: it does not wait for that lock (see [Handle]).
     */
    fun cancel(handle: Handle) {
        if (isAvailable) handle.withPinned { nativeCancel(it) }
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

    /**
     * Loads an embedding model (a GGUF such as EmbeddingGemma) into [handle]. Use a
     * handle of its own, not the chat model's, so both can stay resident. Returns
     * null on success, or the error.
     */
    fun loadEmbeddingModel(handle: Handle, path: String, threads: Int = 0): String? =
        if (isAvailable) nativeLoadEmbeddingModel(handle.ptr, path, threads)
        else "the llama.cpp native library is not available on this device"

    /** Width of each vector [embed] returns, or 0 when no embedding model is loaded. */
    fun embeddingSize(handle: Handle): Int =
        if (isAvailable && !handle.isClosed) nativeEmbeddingSize(handle.ptr) else 0

    /**
     * Embeds [texts] and returns their unit-length vectors as one flat array of
     * `texts.size * embeddingSize(handle)` floats, in input order. Null on failure.
     * Blocking; the native side holds the handle's lock for the whole call.
     */
    fun embed(handle: Handle, texts: List<String>): FloatArray? =
        if (isAvailable && !handle.isClosed) nativeEmbed(handle.ptr, texts.toTypedArray()) else null

    // ---- native methods --------------------------------------------------------
    // All guarded by [isAvailable]; calling one when the library is absent
    // throws UnsatisfiedLinkError, which is why every caller checks first.

    @JvmStatic private external fun nativeCreate(): Long
    @JvmStatic private external fun nativeDestroy(ptr: Long)
    @JvmStatic private external fun nativeSystemInfo(): String
    @JvmStatic private external fun nativeLoadModel(
        ptr: Long, path: String, contextLength: Int, threads: Int, useMmap: Boolean,
        gpuBackend: String?, gpuLayers: Int, outputsMax: Int,
    ): String?
    @JvmStatic private external fun nativeGpuDevices(): Array<String>
    @JvmStatic private external fun nativeUnload(ptr: Long)
    @JvmStatic private external fun nativeModelContextLength(ptr: Long): Int
    @JvmStatic private external fun nativeCountTokens(ptr: Long, text: String): Int
    @JvmStatic private external fun nativeGenerate(
        ptr: Long, prompt: String, grammar: String?, temperature: Float, topP: Float,
        topK: Int, minP: Float, repeatPenalty: Float, penaltyLastN: Int,
        frequencyPenalty: Float, presencePenalty: Float, seed: Int, maxTokens: Int,
        sink: TokenSink?, parseSpecial: Boolean, stablePrefixBytes: Int, nativeChat: Boolean,
    ): String?
    @JvmStatic private external fun nativeChatPrepare(
        ptr: Long, messagesJson: String, promptToolsJson: String, callableToolsJson: String,
    ): String?
    @JvmStatic private external fun nativeChatResult(ptr: Long): String?
    @JvmStatic private external fun nativeChatCount(ptr: Long, messagesJson: String, promptToolsJson: String): Int
    @JvmStatic private external fun nativeSetPrefixCache(ptr: Long, dir: String, key: String)

    /**
     * Where the system prompt's state is saved between runs, and the key that
     * names this model load (the file, the context, the placement). A state is
     * only ever restored under the key it was saved with.
     */
    fun setPrefixCache(handle: Handle, dir: String, key: String) {
        if (isAvailable) nativeSetPrefixCache(handle.ptr, dir, key)
    }
    @JvmStatic private external fun nativeApplyTemplate(
        ptr: Long, roles: Array<String>, contents: Array<String>, addAssistant: Boolean,
    ): String?
    @JvmStatic private external fun nativeTakeResult(ptr: Long): String?
    @JvmStatic private external fun nativeLoadProjector(ptr: Long, path: String): String?
    @JvmStatic private external fun nativeProjectorCaps(ptr: Long): Int
    @JvmStatic private external fun nativeSetMedia(ptr: Long, media: Array<ByteArray>)
    @JvmStatic private external fun nativeMediaMarker(): String
    @JvmStatic private external fun nativeSpeak(
        ptr: Long, text: String, language: String?, voice: String?, maxFrames: Int,
    ): ByteArray?
    @JvmStatic private external fun nativeCancel(ptr: Long)
    @JvmStatic private external fun nativeLoadEmbeddingModel(ptr: Long, path: String, threads: Int): String?
    @JvmStatic private external fun nativeEmbeddingSize(ptr: Long): Int
    @JvmStatic private external fun nativeEmbed(ptr: Long, texts: Array<String>): FloatArray?
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
