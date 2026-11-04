package dev.localintelligence.android.inference

import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.common.StreamingCallback
import com.google.mlkit.genai.prompt.Candidate
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerateContentRequest
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.SystemInstruction
import com.google.mlkit.genai.prompt.TextPart
import dev.localintelligence.core.model.ChatMessage
import dev.localintelligence.core.model.GenerationRequest
import dev.localintelligence.core.model.GenerationResult
import dev.localintelligence.core.model.ModelCapabilities
import dev.localintelligence.core.model.ModelSpec
import dev.localintelligence.core.model.StopReason
import dev.localintelligence.core.model.StreamingModelBackend
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException

/**
 * Gemini Nano, the model Android's AICore runs on the phone, through ML Kit GenAI
 * Prompt (`com.google.mlkit:genai-prompt`, beta).
 *
 * ## Why this is a backend with no file
 *
 * Every other backend loads a file the user holds. Gemini Nano is not a file on this
 * app's storage: AICore keeps the weights, updates them and shares them between apps.
 * So [load] asks the system whether the model is there, nothing is copied, and the
 * `ModelSpec` it receives is not used. The router and the container pick this backend
 * by a plain choice on the Models screen, not by sniffing bytes.
 *
 * ## What the library offers, and what this backend uses
 *
 * The library's own API was read from the release AAR (`classes.jar`), not from the web
 * docs, which gave no signatures:
 *  - `Generation.client` gives a [GenerativeModel]. `checkStatus()` returns a
 *    [FeatureStatus] int: UNAVAILABLE 0, DOWNLOADABLE 1, DOWNLOADING 2, AVAILABLE 3.
 *  - `download()` returns a Flow of [DownloadStatus]; `warmup()` prepares the model.
 *  - `generateContent(request, StreamingCallback)` streams text while it generates.
 *  - A [GenerateContentRequest] has no grammar, no tool schema and no per-request
 *    sampler beyond temperature, seed, top-k and the output limit. So this backend
 *    reports `supportsGrammar = false`, and the agent takes its no-grammar path.
 *  - [Content] has no role field: the library takes parts only. The conversation is
 *    therefore written into one text prompt with role labels. A system prompt goes into
 *    [SystemInstruction] when [GenerativeModel.isSystemPromptAvailable] says so, and is
 *    prepended to the text otherwise.
 *
 * ## Minimum SDK
 *
 * The library's manifest says minSdk 26 (`genai-prompt` and `genai-common`); ML Kit
 * `common` says 21. The app is minSdk 26, so the library never needs an `SDK_INT`
 * gate. A phone without AICore is handled by [status], not by the SDK level.
 *
 * ## Not claimed
 *
 * Speed, the real token limit and the quality of the flat prompt are only known on a
 * phone with AICore. Nothing here has run on a device.
 */
class GeminiNanoBackend(
    /** Builds the client. A seam so the status check can be tested without AICore. */
    private val clientFactory: () -> GenerativeModel = { Generation.getClient() },
) : StreamingModelBackend {

    override val id: String = BACKEND_ID

    /** Before [load] the window is the conservative default; after it, the library's own limit. */
    @Volatile
    private var contextTokens: Int = DEFAULT_CONTEXT_TOKENS

    @Volatile
    private var systemInstructionOk: Boolean = false

    override val capabilities: ModelCapabilities
        get() = ModelCapabilities(
            contextLength = contextTokens,
            // The app's own text format carries tool calls; the library has no tool schema.
            supportsToolCalling = false,
            supportsGrammar = false,
            // The library accepts images, but this backend does not attach them: see generateStreaming.
            supportsVision = false,
            supportsKvCache = false,
        )

    @Volatile
    private var client: GenerativeModel? = null

    /** The generation in flight, so [cancel] can stop it from any thread. */
    @Volatile
    private var inFlight: Deferred<*>? = null

    private val cancelRequested = AtomicBoolean(false)

    /**
     * What AICore says about this phone: a [FeatureStatus] int. Any failure, such as a
     * phone without AICore where the client cannot be built, reads as UNAVAILABLE, so the
     * Models screen hides the row instead of crashing.
     */
    suspend fun status(): Int = runCatching { clientOrCreate().checkStatus() }
        .getOrDefault(FeatureStatus.UNAVAILABLE)

    /**
     * Downloads the model after the user asked for it. Returns true when the download
     * completed. A failure is reported as false, never thrown, so the caller shows one line.
     */
    suspend fun download(): Boolean {
        var completed = false
        return runCatching {
            clientOrCreate().download().collect { update ->
                if (update is DownloadStatus.DownloadCompleted) completed = true
            }
            completed
        }.getOrDefault(false)
    }

    /**
     * Makes the model ready to answer. Throws when AICore does not have it yet, so the
     * caller can say why. Nothing here copies a file.
     */
    override suspend fun load(model: ModelSpec) {
        val gm = clientOrCreate()
        val status = gm.checkStatus()
        check(status == FeatureStatus.AVAILABLE) {
            "Gemini Nano is not ready on this phone (status $status). Download it first."
        }
        contextTokens = gm.getTokenLimit().takeIf { it > 0 } ?: DEFAULT_CONTEXT_TOKENS
        systemInstructionOk = gm.isSystemPromptAvailable()
        gm.warmup()
    }

    override suspend fun unload() {
        cancel()
        val current = client
        client = null
        current?.close()
        contextTokens = DEFAULT_CONTEXT_TOKENS
        systemInstructionOk = false
    }

    override suspend fun generate(request: GenerationRequest): GenerationResult =
        generateStreaming(request) { }

    override suspend fun generateStreaming(
        request: GenerationRequest,
        onToken: (String) -> Unit,
    ): GenerationResult {
        val gm = client ?: return failed("Gemini Nano is not loaded.")
        // Vision and audio are off in the capabilities, so the agent should not send
        // them. Refused here rather than dropped, so a dropped picture is never silent.
        if (request.media.isNotEmpty()) {
            return failed("Gemini Nano takes text only; the attachment was not sent.")
        }
        cancelRequested.set(false)
        if (request.cancellation.isCancelled()) return cancelled()

        val (system, transcript) = flatten(request.messages)
        val apiRequest = buildRequest(request, system, transcript)

        val startedAt = System.nanoTime()
        val firstTokenAt = AtomicLong(0L)
        val callback = object : StreamingCallback {
            override fun onNewText(text: String) {
                if (text.isEmpty()) return
                firstTokenAt.compareAndSet(0L, System.nanoTime())
                onToken(text)
            }
        }

        return coroutineScope {
            val work = async { gm.generateContent(apiRequest, callback) }
            inFlight = work
            try {
                val response = work.await()
                val candidate = response.candidates.firstOrNull()
                    ?: return@coroutineScope failed("Gemini Nano returned no answer.")
                resultOf(candidate, startedAt, firstTokenAt.get())
            } catch (e: CancellationException) {
                // Our own cancel() stops only `work`, so this scope is still active. A
                // cancel of the caller's coroutine is passed on, as coroutines require.
                if (currentCoroutineContext().isActive) cancelled() else throw e
            } catch (e: GenAiException) {
                failed("Gemini Nano failed (code ${e.errorCode}): ${e.message ?: "no detail"}")
            } catch (e: Exception) {
                failed("Gemini Nano failed: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                inFlight = null
            }
        }
    }

    override fun countTokens(text: String): Int {
        if (text.isEmpty()) return 0
        // The library's tokenizer is not reachable without a suspend call, so the same
        // four-characters-per-token estimate the other backends use for a fallback.
        return (text.length + 3) / 4
    }

    override fun cancel() {
        cancelRequested.set(true)
        inFlight?.cancel()
    }

    private fun clientOrCreate(): GenerativeModel =
        client ?: clientFactory().also { client = it }

    private fun buildRequest(
        request: GenerationRequest,
        system: String?,
        transcript: String,
    ): GenerateContentRequest {
        val text = TextPart(if (system != null && !systemInstructionOk) "$system\n\n$transcript" else transcript)
        val builder = if (system != null && systemInstructionOk) {
            GenerateContentRequest.Builder(SystemInstruction(system), text)
        } else {
            GenerateContentRequest.Builder(text)
        }
        builder.temperature = request.params.temperature
        builder.maxOutputTokens = request.params.maxOutputTokens
        if (request.params.seed >= 0) builder.seed = request.params.seed
        return builder.build()
    }

    private fun resultOf(candidate: Candidate, startedAt: Long, firstTokenAt: Long): GenerationResult {
        val now = System.nanoTime()
        val firstMs = if (firstTokenAt > 0L) (firstTokenAt - startedAt) / 1_000_000 else 0L
        val text = candidate.text.orEmpty()
        val stop = when {
            cancelRequested.get() -> StopReason.CANCELLED
            candidate.finishReason == Candidate.FinishReason.MAX_TOKENS -> StopReason.MAX_TOKENS
            else -> StopReason.COMPLETED
        }
        return GenerationResult(
            text = text,
            completionTokens = countTokens(text),
            stopReason = stop,
            prefillMs = firstMs,
            decodeMs = ((now - startedAt) / 1_000_000 - firstMs).coerceAtLeast(0L),
        )
    }

    private fun failed(reason: String) = GenerationResult(text = reason, stopReason = StopReason.ERROR)

    private fun cancelled() = GenerationResult(text = "", stopReason = StopReason.CANCELLED)

    companion object {
        const val BACKEND_ID = "gemini-nano"

        /**
         * What [status] can return, named here so the app module needs no ML Kit import: the app
         * does not depend on the library directly, only on this module.
         */
        const val STATUS_UNAVAILABLE = FeatureStatus.UNAVAILABLE
        const val STATUS_DOWNLOADABLE = FeatureStatus.DOWNLOADABLE
        const val STATUS_DOWNLOADING = FeatureStatus.DOWNLOADING
        const val STATUS_AVAILABLE = FeatureStatus.AVAILABLE

        /** Used until [load] reads the library's limit. Small on purpose: the first plan must fit. */
        const val DEFAULT_CONTEXT_TOKENS = 4096

        /**
         * Splits the conversation into a system text and one transcript. The library's
         * [Content] has no role, so each turn carries its role as a label the model reads.
         * Tool results use the same fenced text the other backends send.
         */
        internal fun flatten(messages: List<ChatMessage>): Pair<String?, String> {
            val system = messages.filterIsInstance<ChatMessage.System>()
                .joinToString("\n\n") { it.text }
                .ifBlank { null }
            val turns = messages.mapNotNull { message ->
                when (message) {
                    is ChatMessage.System -> null
                    // The rendered form carries the clock line and memories for this turn.
                    is ChatMessage.User -> "User: ${message.rendered ?: message.text}"
                    is ChatMessage.Assistant ->
                        message.text.takeIf { it.isNotBlank() }?.let { "Assistant: $it" }
                    is ChatMessage.ToolObservation -> "Tool ${message.toolName}: ${message.modelFacing()}"
                }
            }
            return system to (turns + "Assistant:").joinToString("\n\n")
        }
    }
}
