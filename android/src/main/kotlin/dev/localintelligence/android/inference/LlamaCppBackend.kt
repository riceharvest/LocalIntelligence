package dev.localintelligence.android.inference

import dev.localintelligence.core.context.SystemPrompts
import java.io.File

import dev.localintelligence.core.model.ChatMessage
import dev.localintelligence.core.model.GenerationRequest
import dev.localintelligence.core.model.GenerationResult
import dev.localintelligence.core.model.ModelBackend
import dev.localintelligence.core.model.SpeechOutput
import dev.localintelligence.core.model.ModelCapabilities
import dev.localintelligence.core.model.ModelSpec
import dev.localintelligence.core.model.SamplingParams
import dev.localintelligence.core.model.StopReason
import dev.localintelligence.core.model.StreamingModelBackend
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
 *
 * ## CPU or GPU
 *
 * [computeSource] says where each load runs ([LlamaCompute]). A GPU load that
 * fails (an old driver, no memory for the GPU buffers) falls back to the CPU
 * rather than failing: the user asked for a model, not for a GPU.
 * [activeCompute] says where the resident model actually is.
 */
class LlamaCppBackend(
    private val importer: ModelImporter? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val computeSource: LlamaComputeSource = LlamaComputeSource.CPU_ONLY,
    /**
     * Where the system prompt's state is saved, so the first message after a
     * restart does not prefill it again. Null keeps it in memory only.
     */
    private val prefixCacheDir: java.io.File? = null,
) : StreamingModelBackend, SpeechOutput {

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

    /** Where the resident model runs, or null when none is loaded. */
    @Volatile
    var activeCompute: LlamaCompute? = null
        private set

    /** The spec of the resident model, so [reloadOn] can load it again elsewhere. */
    @Volatile
    private var residentSpec: ModelSpec? = null

    override suspend fun load(model: ModelSpec) =
        loadOn(model, computeSource.forNextLoad(), cpuFallback = true)

    /**
     * Loads the resident model again on [compute], for the speed test's comparison.
     * Returns null on success, else why it failed; the model is then back where it
     * was. The old copy is freed first, so two copies never share the phone's RAM.
     */
    suspend fun reloadOn(compute: LlamaCompute): String? {
        val spec = residentSpec ?: return "no model is loaded"
        val previous = activeCompute ?: LlamaCompute.CPU
        unload()
        return try {
            loadOn(spec, compute, cpuFallback = false)
            null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            loadOn(spec, previous, cpuFallback = true)
            t.message ?: t::class.java.simpleName
        }
    }

    private suspend fun loadOn(model: ModelSpec, wanted: LlamaCompute, cpuFallback: Boolean) {
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
        // The context the app asked for (setting + phone, see ModelSpec.contextLength),
        // never beyond what the model was trained for. The RAM gate prices this same
        // number, so the two cannot drift apart.
        val requested = model.contextLength ?: DEFAULT_CONTEXT_LENGTH
        val contextLength = minOf(requested, meta.contextLength ?: requested)
            .coerceIn(MIN_CONTEXT_LENGTH, MAX_CONTEXT_LENGTH)

        // A text-only model asks for one logits row per batch, which saves the
        // output head's reserve; a model with a projector may speak, and the
        // audio head reads every row, so it keeps the default.
        val outputsMax = if (findProjector(uri) == null) 1 else 0

        fun loadNative(compute: LlamaCompute): String? {
            computeSource.loadStarting(compute)
            var error = LlamaBridge.loadModel(
                handle = bridgeHandle,
                path = opened.nativePath(),
                contextLength = contextLength,
                threads = 0,
                useMmap = opened.isMmapCapable,
                compute = compute,
                outputsMax = outputsMax,
            )
            // A GPU builds its compute pipelines on the first prefill and decode,
            // which is where a driver bug most likely crashes or fails. Running
            // both here keeps that inside the crash guard, turns a GPU that loads
            // but cannot compute into a load error (so the CPU takes over), and
            // takes the delay off the user's first message.
            if (error == null && compute.isGpu) {
                val warmUpError = LlamaBridge.generate(
                    handle = bridgeHandle, prompt = WARM_UP_PROMPT, grammar = null, temperature = 0f,
                    topP = 1f, topK = 0, minP = 0f, repeatPenalty = 1f, penaltyLastN = 0,
                    frequencyPenalty = 0f, presencePenalty = 0f, seed = 0, maxTokens = 2, onToken = null,
                )
                val outcome = LlamaBridge.takeResult(bridgeHandle)
                error = warmUpError ?: outcome?.error ?: when {
                    outcome == null -> "the GPU warm-up returned no result"
                    outcome.stopCode == GenerationOutcome.STOP_ERROR -> "the GPU warm-up failed"
                    else -> null
                }
            }
            computeSource.loadReturned(compute, error)
            return error
        }
        var compute = wanted
        var loadError = loadNative(compute)
        if (loadError != null && compute.isGpu && cpuFallback) {
            android.util.Log.w(TAG, "${compute.label} could not load the model, using the CPU: $loadError")
            compute = LlamaCompute.CPU
            loadError = loadNative(compute)
        }
        if (loadError != null) {
            opened.close()
            LlamaBridge.unload(bridgeHandle)
            bridgeHandle.close()
            error("could not load the model: $loadError")
        }

        // A projector next to the model gives it eyes and/or ears. Optional: a
        // model without one is text-only, and a projector that fails to load
        // leaves the text model working.
        val projectorCaps = findProjector(uri)?.let { projector ->
            if (LlamaBridge.loadProjector(bridgeHandle, projector.absolutePath) == null) {
                LlamaBridge.projectorCaps(bridgeHandle)
            } else {
                0
            }
        } ?: 0

        // The saved system-prompt state is valid only for this file, context and
        // placement, so all three name it (with the file's size, in case a file
        // is replaced under the same name).
        prefixCacheDir?.let { dir ->
            val key = listOf(model.id, model.sizeBytes, contextLength, compute.label, outputsMax).joinToString("|")
            LlamaBridge.setPrefixCache(bridgeHandle, dir.absolutePath, key)
        }

        synchronized(lock) {
            // One model resident at a time.
            releaseLocked()
            handle = bridgeHandle
            loaded = opened
            activeCompute = compute
            residentSpec = model
            currentCapabilities = deriveCapabilities(meta, contextLength).copy(
                supportsVision = projectorCaps and LlamaBridge.CAP_IMAGE != 0,
                supportsAudioInput = projectorCaps and LlamaBridge.CAP_AUDIO != 0,
                supportsSpeechOutput = projectorCaps and LlamaBridge.CAP_SPEECH != 0,
            )
        }
        cancelRequested.set(false)
    }

    override suspend fun unload() {
        synchronized(lock) {
            residentSpec = null
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

            val rendered = buildPrompt(request, h)
            // Attachments travel next to the prompt; buildPrompt put one marker
            // per attachment where they belong.
            LlamaBridge.setMedia(h, request.media.map { it.bytes })
            val sink = onToken?.let { cb ->
                LlamaBridge.TokenSink { piece -> cb(piece) }
            }

            val params = request.params
            val error = try {
                LlamaBridge.generate(
                    handle = h,
                    prompt = rendered.text,
                    grammar = request.grammar,
                    temperature = params.temperature,
                    topP = params.topP,
                    topK = params.topK,
                    minP = params.minP,
                    repeatPenalty = params.repeatPenalty,
                    penaltyLastN = params.penaltyLastN,
                    frequencyPenalty = params.frequencyPenalty,
                    presencePenalty = params.presencePenalty,
                    seed = params.seed,
                    maxTokens = params.maxOutputTokens,
                    onToken = sink,
                    parseSpecial = rendered.fromTemplate,
                    stablePrefixBytes = if (request.reusePrefix) rendered.stableBytes else 0,
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

    /**
     * Prefills [request]'s prompt and stops before the first token (a negative
     * token limit, see nativeGenerate). The prompt's state stays in the cache,
     * with the copies the next request restores, so a request that continues
     * this prompt prefills only what it adds.
     */
    override suspend fun warm(request: GenerationRequest) {
        generateInternal(request.copy(params = request.params.copy(maxOutputTokens = -1), grammar = null), null)
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
        activeCompute = null
        if (h != null) {
            LlamaBridge.unload(h)
            h.close()
        }
        // Closing the descriptor last: /proc/self/fd/N must stay valid for as
        // long as the native model might still read it.
        m?.close()
    }

    /**
     * The `mmproj-*.gguf` beside a model file, or null.
     *
     * Matched by name stem: `LFM3-Omni-2B-Q4_K_M.gguf` pairs with
     * `mmproj-LFM3-Omni-2B-Q8_0.gguf`. Only plain files are searched; a model
     * opened through the document picker has no directory to look in.
     */
    private fun findProjector(modelUri: android.net.Uri): java.io.File? {
        val modelFile = modelUri.path?.let(::File)?.takeIf { modelUri.scheme == "file" && it.isFile }
            ?: return null
        val stem = stemOf(modelFile.name)
        return modelFile.parentFile
            ?.listFiles { f -> f.isFile && f.name.startsWith("mmproj", ignoreCase = true) && f.name.endsWith(".gguf", true) }
            ?.firstOrNull { stemOf(it.name.removePrefix("mmproj-").removePrefix("mmproj_")).equals(stem, ignoreCase = true) }
    }

    private fun stemOf(fileName: String): String =
        fileName.removeSuffix(".gguf").removeSuffix(".GGUF").replace(QUANT_SUFFIX, "")

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
     * Speaks [text] in the model's own voice when its projector has an audio
     * head. Holds the generation lock: speech and a generate share one context.
     */
    override suspend fun speak(text: String, language: String?, voice: String?): ByteArray? =
        withContext(ioDispatcher) {
            synchronized(lock) {
                val h = handle
                if (h == null || h.isClosed || !currentCapabilities.supportsSpeechOutput) return@withContext null
                LlamaBridge.speak(h, text, language, voice)
            }
        }

    /**
     * A rendered prompt, and whether it came from the model's chat template.
     * The template's control tokens must be parsed as special tokens; the flat
     * transcript's text must not be, so the flag travels with the text.
     */
    internal class RenderedPrompt(
        val text: String,
        val fromTemplate: Boolean,
        /** UTF-8 bytes at the start of [text] that are the system message alone. 0: unknown. */
        val stableBytes: Int = 0,
    )

    /**
     * Renders the conversation for [handle]'s model.
     *
     * With [GenerationRequest.useModelTemplate] on, the model's own chat template
     * is used (see [templatePrompt]). That is the format the model was trained
     * on, and small instruct models answer noticeably better in it. When the
     * model has no template, or llama.cpp does not recognise it, this falls back
     * to [buildFlatPrompt], the same transcript the app has always sent.
     *
     * ## ROLE FORGERY, AND WHAT ACTUALLY STOPS IT
     *
     * The flat transcript has literal `### User` markers, so anything able to put
     * text in a message could write a turn boundary. [UntrustedContent.neutralise]
     * removes that for observation bodies, and the flat path depends on it.
     *
     * The template path does not depend on the markers being unforgeable in the
     * same way, because its turn markers are the model's *control tokens*:
     *
     *  - Native code breaks every control token in the model's vocabulary inside
     *    the message contents before rendering (`neutralise_control_text` in
     *    llama_jni.cpp). That is the vocabulary's own list, so it is not limited
     *    to ChatML's `<|` prefix.
     *  - The rendered prompt is then tokenised with `parse_special = true`, so
     *    the only control tokens left are the ones the template emitted.
     *
     * A message that contains `<|im_start|>` therefore reaches the model as the
     * characters `< |im_start|>`, not as a turn boundary. The observation body is
     * still fenced by [ChatMessage.ToolObservation.modelFacing], which is the
     * right thing for the model to read whichever path renders it.
     *
     * ## WHAT IS NOT COVERED
     *
     * Attachments. With a projector loaded, the native path tokenises the prompt
     * with `parse_special = true` whichever renderer produced it, so a flat
     * transcript sent with an attachment can still produce a control token from
     * message text. That behaviour predates this function and is unchanged here.
     * A templated prompt is neutralised before that point, so it is covered.
     *
     * A renderer that writes BOS text itself gets a second BOS, because the
     * tokenizer also adds one. Of the built-in renderers in llama-chat.cpp, only
     * seed-oss does this for the first message; llama 2 writes `<s>` only between
     * turns.
     */
    internal fun buildPrompt(request: GenerationRequest, handle: LlamaBridge.Handle): RenderedPrompt {
        if (request.useModelTemplate) {
            val templated = templatePrompt(request, handle)
            if (templated != null) {
                return RenderedPrompt(templated, fromTemplate = true, stableBytes = systemPrefixBytes(request, templated))
            }
        }
        return RenderedPrompt(buildFlatPrompt(request), fromTemplate = false)
    }

    /**
     * How many UTF-8 bytes of [rendered] come before the system prompt's tool
     * part ([SystemPrompts.TOOLS_START]): the template's opening, the name, the
     * rules and the profile. That part changes only when Settings do, so its
     * state is kept across messages and restarts; the tool list after it changes
     * with the conversation and is not part of the key. 0 when there is none.
     */
    private fun systemPrefixBytes(request: GenerationRequest, rendered: String): Int {
        val system = request.messages.firstOrNull() as? ChatMessage.System ?: return 0
        val cut = system.text.indexOf(SystemPrompts.TOOLS_START)
        val at = rendered.indexOf(system.text)
        if (cut <= 0 || at < 0) return 0
        return rendered.substring(0, at + cut).toByteArray(Charsets.UTF_8).size
    }

    /**
     * The conversation rendered by the model's own chat template, or null when
     * the model has none or llama.cpp cannot render it.
     *
     * Roles map as: system to "system", user to "user", assistant to "assistant".
     * A tool observation is sent as a "user" turn with the same untrusted fence
     * the flat transcript uses, because a template has no role for tool output.
     * The attachment markers go at the start of the last user turn's content, the
     * same place the flat transcript puts them.
     */
    private fun templatePrompt(request: GenerationRequest, handle: LlamaBridge.Handle): String? {
        val lastUser = request.messages.lastOrNull { it is ChatMessage.User }
        val roles = ArrayList<String>(request.messages.size)
        val contents = ArrayList<String>(request.messages.size)
        for (m in request.messages) {
            when (m) {
                is ChatMessage.System -> {
                    roles += "system"
                    contents += m.text
                }
                is ChatMessage.User -> {
                    roles += "user"
                    contents += buildString {
                        if (m === lastUser) {
                            repeat(request.media.size) { append(LlamaBridge.mediaMarker).append('\n') }
                        }
                        append(m.text)
                    }
                }
                is ChatMessage.Assistant -> {
                    roles += "assistant"
                    contents += m.text
                }
                is ChatMessage.ToolObservation -> {
                    roles += "user"
                    contents += buildString {
                        append("Observation (").append(m.toolName)
                        append(if (m.success) "" else ", failed").append("):\n")
                        append(m.modelFacing())
                    }
                }
            }
        }
        return LlamaBridge.applyTemplate(handle, roles, contents, addAssistant = true)
    }

    /**
     * Renders the conversation as the flat text llama.cpp tokenizes. This is the
     * fallback when the model's own template is off or unavailable.
     *
     * Deliberately a simple, explicit transcript: the role markers are the
     * portable subset every instruct model in the 1-4B range recognises, and it
     * needs no template engine. Its text is tokenised with control tokens as
     * plain characters, so the markers here are the only structure it has.
     *
     * ## THE COST OF THIS FORMAT, AND WHAT PAYS IT
     *
     * A flat transcript with literal `### User` markers means the *format itself*
     * is forgeable by anything that can put text in a message. That is not a
     * theoretical weakness of this function: a fetched web page containing the
     * seven characters `### User` followed by a newline does not need to
     * persuade the model of anything, because this renderer will emit it as a
     * genuine user turn and the model cannot tell the difference.
     *
     * The fix is not in this function. It is that
     * [dev.localintelligence.core.model.UntrustedContent.neutralise] has already
     * removed the ability of an observation body to contain a role marker, at
     * construction. `modelFacing()` is used for the body so that this function
     * has exactly one way to render an observation, and cannot drift into a
     * second one that forgets the fence.
     */
    internal fun buildFlatPrompt(request: GenerationRequest): String = buildString {
        val lastUser = request.messages.lastOrNull { it is ChatMessage.User }
        for (m in request.messages) {
            when (m) {
                is ChatMessage.System -> append("### System\n").append(m.text).append("\n\n")
                is ChatMessage.User -> {
                    append("### User\n")
                    // The attachments belong to the message being answered: the last user turn.
                    if (m === lastUser) {
                        repeat(request.media.size) { append(LlamaBridge.mediaMarker).append('\n') }
                    }
                    append(m.text).append("\n\n")
                }
                is ChatMessage.Assistant -> append("### Assistant\n").append(m.text).append("\n\n")
                is ChatMessage.ToolObservation ->
                    append("### Observation (")
                        .append(m.toolName)
                        .append(if (m.success) "" else ", failed")
                        .append(")\n")
                        .append(m.modelFacing())
                        .append("\n\n")
            }
        }
        append("### Assistant\n")
    }


    companion object {
        const val DEFAULT_CONTEXT_LENGTH = 4096

        private const val TAG = "LlamaCppBackend"

        /** Long enough that the warm-up prefill takes the batched path, not the single-token one. */
        private const val WARM_UP_PROMPT =
            "The quick brown fox jumps over the lazy dog while the five boxing wizards jump quickly " +
                "and a wizard's job is to vex chumps quickly in fog."

        /** `-Q4_K_M`, `-QAD-Q4_0`, `-IQ3_XS`, `-F16`, `-BF16`, `-MXFP4_MOE`... at the end of a file stem. */
        private val QUANT_SUFFIX = Regex("""(?i)[-_.](QAD-)?(IQ\d\w*|Q\d\w*|BF16|F16|F32|MXFP4\w*|NVFP4)$""")

        /**
         * Smallest context worth creating: below this a prompt plus an answer
         * does not fit, and llama.cpp fails every decode.
         */
        private const val MIN_CONTEXT_LENGTH = 128

        /**
         * Hard ceiling on the context the app will ever allocate: the largest option of
         * the context-length setting. The RAM fit gate prices the same
         * [ModelSpec.contextLength] this loader reads, so the two agree.
         */
        private const val MAX_CONTEXT_LENGTH = 32_768
    }
}
