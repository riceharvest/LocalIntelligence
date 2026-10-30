package dev.localintelligence.android.cloud

import android.util.Base64
import dev.localintelligence.core.model.ChatMessage
import dev.localintelligence.core.model.GenerationRequest
import dev.localintelligence.core.model.GenerationResult
import dev.localintelligence.core.model.MediaInput
import dev.localintelligence.core.model.ModelCapabilities
import dev.localintelligence.core.model.ModelSpec
import dev.localintelligence.core.model.SamplingParams
import dev.localintelligence.core.model.StopReason
import dev.localintelligence.core.model.StreamingModelBackend
import dev.localintelligence.core.settings.SettingsReader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.util.concurrent.atomic.AtomicBoolean

// ===========================================================================
// OpenRouter as the chat model. Off unless the user picks it on the Models screen.
//
// WHY THIS IS A BACKEND AND NOT A TOOL: the agent loop asks one model for every
// step, so a remote chat model has to be the thing that answers. A tool would
// leave the local model in charge and send only the parts it chose.
//
// What leaves the phone while this is the resident model: the whole conversation
// (system prompt, user turns, tool observations as fenced text) and any attached
// image or audio, in the request body, plus the key in the Bearer header. Nothing
// else. The model id and sampling come from the user's settings.
//
// Every request is gated on the plugin being on and a key being set, read at the
// moment of the request. Switching the plugin off, or clearing the key, stops the
// next message without a restart and sends nothing.
// ===========================================================================

/**
 * Streams chat completions from OpenRouter's OpenAI-compatible API.
 *
 * [enabled] is supplied by the app container because the plugin switch lives in the
 * app's settings store, which this module cannot see.
 */
class OpenRouterChatBackend(
    private val reader: SettingsReader,
    private val enabled: () -> Boolean,
) : StreamingModelBackend {

    override val id: String = BACKEND_ID

    /**
     * Read on every access, so the context budget follows the setting. Vision and audio
     * are reported as available because the model is whatever the user typed, and this
     * property is read before any request exists. A model that refuses an attachment
     * returns an HTTP error, which is shown as the run's failure.
     */
    override val capabilities: ModelCapabilities
        get() = ModelCapabilities(
            contextLength = reader[OpenRouterSettings.CHAT_CONTEXT_LENGTH],
            supportsToolCalling = true,
            supportsGrammar = false,
            supportsVision = true,
            supportsKvCache = false,
            supportsAudioInput = true,
        )

    /** The connection of the request in flight, so [cancel] can close it from any thread. */
    @Volatile
    private var active: HttpURLConnection? = null

    private val cancelRequested = AtomicBoolean(false)

    /** Nothing is loaded on the phone, so there is nothing to do. */
    override suspend fun load(model: ModelSpec) = Unit

    override suspend fun unload() {
        cancel()
    }

    override suspend fun generate(request: GenerationRequest): GenerationResult =
        generateStreaming(request) { }

    override suspend fun generateStreaming(
        request: GenerationRequest,
        onToken: (String) -> Unit,
    ): GenerationResult = withContext(Dispatchers.IO) {
        cancelRequested.set(false)
        val key = reader[OpenRouterSettings.API_KEY]
        if (!enabled() || key.isBlank()) {
            return@withContext failed("OpenRouter chat is off or has no key. Pick a local model on the Models page.")
        }

        val partial = StringBuilder()
        val stream = Stream()
        val job = currentCoroutineContext()[Job]
        val onCancel = closeOnCancel(job)
        val startedAt = System.nanoTime()
        try {
            val body = requestBody(request, reader[OpenRouterSettings.CHAT_MODEL]).toString()
            val connection = openOpenRouterConnection("POST", "$API/chat/completions", key)
            active = connection
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "text/event-stream")
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val status = connection.responseCode
            if (status !in 200..299) {
                val detail = connection.errorStream?.use { readSmall(it) }.orEmpty()
                return@withContext failed(httpFailure(status, detail, key))
            }
            connection.inputStream.use { input ->
                readStream(input, key, partial, stream, startedAt, onToken)
            }
            stream.error?.let { return@withContext failed(it) }
            GenerationResult(
                text = partial.toString(),
                promptTokens = stream.promptTokens,
                completionTokens = stream.completionTokens,
                stopReason = stream.stop,
                prefillMs = stream.firstTokenMs,
                decodeMs = stream.decodeMs(startedAt),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            // A cancel closes the socket, so the read fails with an IOException. That is
            // a stop the user asked for, and the partial answer is kept.
            if (cancelRequested.get() || job?.isCancelled == true) {
                GenerationResult(
                    text = partial.toString(),
                    stopReason = StopReason.CANCELLED,
                    promptTokens = stream.promptTokens,
                    completionTokens = stream.completionTokens,
                )
            } else {
                failed("could not reach OpenRouter (${e.javaClass.simpleName}). Check the connection.")
            }
        } catch (e: IllegalArgumentException) {
            // Raised for an attachment this backend cannot send. The message is ours.
            failed(e.message ?: "request not sent")
        } catch (e: Exception) {
            failed("OpenRouter chat could not run (${e.javaClass.simpleName}).")
        } finally {
            onCancel?.dispose()
            active?.disconnect()
            active = null
        }
    }

    override fun countTokens(text: String): Int {
        if (text.isEmpty()) return 0
        // The same approximation LlamaCppBackend uses when no tokenizer is loaded: a
        // remote tokenizer is not available here, and the figure only plans the budget.
        return (text.length + 3) / 4 + text.count { it == '\n' }
    }

    override fun cancel() {
        cancelRequested.set(true)
        active?.disconnect()
    }

    /**
     * Closes the connection when the calling coroutine is cancelled. A blocking read
     * does not observe cancellation on its own, and the job is not complete until the
     * read returns, so the hook has to run on the cancelling edge.
     */
    @OptIn(InternalCoroutinesApi::class)
    private fun closeOnCancel(job: Job?): DisposableHandle? =
        job?.invokeOnCompletion(onCancelling = true, invokeImmediately = false) {
            active?.disconnect()
        }

    private fun failed(reason: String) = GenerationResult(text = reason, stopReason = StopReason.ERROR)

    /** Mutable state of one streamed reply. */
    private class Stream {
        var stop: StopReason = StopReason.COMPLETED
        var promptTokens = 0
        var completionTokens = 0
        var error: String? = null
        var firstTokenMs = 0L
        private var firstTokenAt = 0L

        fun firstToken(startedAt: Long) {
            if (firstTokenAt == 0L) {
                firstTokenAt = System.nanoTime()
                firstTokenMs = (firstTokenAt - startedAt) / 1_000_000
            }
        }

        fun decodeMs(startedAt: Long): Long {
            val from = if (firstTokenAt == 0L) startedAt else firstTokenAt
            return (System.nanoTime() - from) / 1_000_000
        }
    }

    /**
     * Reads server-sent events. Each `data:` line is one JSON chunk; `:` lines are
     * keep-alives (OpenRouter sends `: OPENROUTER PROCESSING` while a provider works).
     */
    private suspend fun readStream(
        input: InputStream,
        key: String,
        partial: StringBuilder,
        stream: Stream,
        startedAt: Long,
        onToken: (String) -> Unit,
    ) {
        BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { lines ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val line = lines.readLine() ?: break
                if (!line.startsWith("data:")) continue
                val payload = line.removePrefix("data:").trim()
                if (payload == "[DONE]") break
                val chunk = Json.parseToJsonElement(payload).jsonObject

                val error = chunk["error"]?.jsonObject
                if (error != null) {
                    stream.error = "OpenRouter stopped the reply: " +
                        scrubbed(error["message"]?.jsonPrimitive?.contentOrNull.orEmpty(), key)
                    break
                }

                chunk["usage"]?.jsonObject?.let { usage ->
                    stream.promptTokens = usage["prompt_tokens"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
                    stream.completionTokens = usage["completion_tokens"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
                }

                val choice = chunk["choices"]?.jsonArray?.firstOrNull()?.jsonObject ?: continue
                val piece = choice["delta"]?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
                if (!piece.isNullOrEmpty()) {
                    stream.firstToken(startedAt)
                    partial.append(piece)
                    onToken(piece)
                }
                when (choice["finish_reason"]?.jsonPrimitive?.contentOrNull) {
                    "length" -> stream.stop = StopReason.MAX_TOKENS
                    "stop", "tool_calls" -> stream.stop = StopReason.COMPLETED
                }
            }
        }
    }

    companion object {
        /** Stable id for this backend. */
        const val BACKEND_ID = "openrouter-chat"

        /** Longest error text that reaches the model or the run trace. */
        private const val HTTP_ERROR_CHARS = 160

        /**
         * The OpenAI-compatible request body. Sampling values that mean "off" in this app
         * (top_k 0, a negative seed) are omitted, because OpenRouter rejects some of them.
         */
        internal fun requestBody(request: GenerationRequest, model: String): JsonObject {
            val p: SamplingParams = request.params
            return buildJsonObject {
                put("model", model)
                put("messages", messagesJson(request))
                put("stream", true)
                put("temperature", p.temperature)
                put("top_p", p.topP)
                if (p.topK > 0) put("top_k", p.topK)
                put("min_p", p.minP)
                put("repetition_penalty", p.repeatPenalty)
                if (p.seed >= 0) put("seed", p.seed)
                put("max_tokens", p.maxOutputTokens)
            }
        }

        /**
         * Maps the app's transcript onto chat roles. A tool observation has no role of its
         * own, so it is a user turn; the fence from [dev.localintelligence.core.model.UntrustedContent]
         * is what tells the model it is quoted text.
         */
        private fun messagesJson(request: GenerationRequest): JsonArray {
            val lastUser = request.messages.indexOfLast { it is ChatMessage.User }
            return buildJsonArray {
                request.messages.forEachIndexed { index, message ->
                    when (message) {
                        is ChatMessage.System -> add(turn("system", JsonPrimitive(message.text)))
                        is ChatMessage.Assistant -> add(turn("assistant", JsonPrimitive(message.text)))
                        is ChatMessage.User -> add(
                            turn(
                                "user",
                                if (index == lastUser) withMedia(message.text, request.media)
                                else JsonPrimitive(message.text),
                            ),
                        )
                        is ChatMessage.ToolObservation -> add(
                            turn("user", JsonPrimitive("Observation (tool): " + message.modelFacing())),
                        )
                    }
                }
                if (lastUser < 0 && request.media.isNotEmpty()) {
                    add(turn("user", withMedia("", request.media)))
                }
            }
        }

        private fun turn(role: String, content: JsonElement): JsonObject = buildJsonObject {
            put("role", role)
            put("content", content)
        }

        /**
         * Plain text when there is nothing attached; otherwise the content-part form.
         * Base64 is written without line breaks, because a wrapped data URI is not a URI.
         */
        private fun withMedia(text: String, media: List<MediaInput>): JsonElement {
            if (media.isEmpty()) return JsonPrimitive(text)
            return buildJsonArray {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", text)
                })
                for (item in media) {
                    val encoded = Base64.encodeToString(item.bytes, Base64.NO_WRAP)
                    when {
                        item.isImage -> add(buildJsonObject {
                            put("type", "image_url")
                            putJsonObject("image_url") {
                                put("url", "data:${item.mimeType};base64,$encoded")
                            }
                        })
                        item.isAudio -> add(buildJsonObject {
                            put("type", "input_audio")
                            putJsonObject("input_audio") {
                                put("data", encoded)
                                put("format", audioFormat(item.mimeType))
                            }
                        })
                    }
                }
            }
        }

        /** OpenRouter takes wav or mp3 for input audio. Anything else is refused, not guessed. */
        private fun audioFormat(mimeType: String): String {
            val type = mimeType.lowercase()
            return when {
                "wav" in type -> "wav"
                "mpeg" in type || "mp3" in type -> "mp3"
                else -> throw IllegalArgumentException(
                    "audio type $mimeType is not sent to OpenRouter. Attach wav or mp3.",
                )
            }
        }

        /** Non-2xx replies: one short line, with the key removed in case a reply echoes it. */
        private fun httpFailure(status: Int, body: String, key: String): String {
            val message = runCatching {
                Json.parseToJsonElement(body).jsonObject["error"]?.jsonObject
                    ?.get("message")?.jsonPrimitive?.contentOrNull
            }.getOrNull() ?: body
            val hint = when (status) {
                401, 403 -> "Check the key in Settings."
                404 -> "Check the chat model id in Settings."
                else -> "Try again later."
            }
            return "OpenRouter returned HTTP $status. $hint " + scrubbed(message, key)
        }

        private fun scrubbed(text: String, key: String): String {
            val cleaned = if (key.isBlank()) text else text.replace(key, "[key]")
            return cleaned.replace(Regex("\\s+"), " ").trim().take(HTTP_ERROR_CHARS)
        }

        /** An error body is a few hundred bytes. One read of at most 16 KB is enough, and avoids readNBytes (API 33). */
        private fun readSmall(input: InputStream): String {
            val buffer = ByteArray(16 * 1024)
            val n = input.read(buffer)
            return if (n <= 0) "" else String(buffer, 0, n, Charsets.UTF_8)
        }
    }
}
