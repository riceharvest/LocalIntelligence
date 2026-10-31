package dev.localintelligence.android.cloud

import android.util.Base64
import dev.localintelligence.android.cloud.ChatGptEndpoints.RESOURCE
import dev.localintelligence.core.model.ChatMessage
import dev.localintelligence.core.model.GenerationRequest
import dev.localintelligence.core.model.GenerationResult
import dev.localintelligence.core.model.MediaInput
import dev.localintelligence.core.model.ModelCapabilities
import dev.localintelligence.core.model.ModelSpec
import dev.localintelligence.core.model.StopReason
import dev.localintelligence.core.model.StreamingModelBackend
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
import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.util.concurrent.atomic.AtomicBoolean

// ===========================================================================
// ChatGPT as the chat model, with the user's ChatGPT plan. Off unless the user signs in
// and picks it on the Models screen (or in Settings, in Simple mode).
//
// WHY THIS IS A BACKEND AND NOT A TOOL: the agent loop asks one model for every step, so a
// remote chat model has to be the thing that answers, the same as OpenRouter.
//
// The request is POST /v1/responses with store:false and stream:true. The system prompt
// goes in `instructions` (system-role items are refused by this route), the history in
// `input`. No sampling fields and no output limit: the route refuses them. Tools are the
// app's own text format inside the prompt, not the Responses tools API.
//
// A reply counts as done only on response.completed. Usage-limit errors can arrive as a
// failed response after streaming has begun, and are shown as the user's to fix.
// ===========================================================================

/** A model the account can use, from GET /v1/models. */
class ChatGptModel(val slug: String, val displayName: String)

/**
 * Streams replies from the Responses API with the signed-in account's plan.
 *
 * [enabled] comes from the app container, because the plugin switch lives in the app's
 * settings store. The account itself is read from [plugin] on each request.
 */
class ChatGptChatBackend(
    private val plugin: ChatGptPlugin,
    private val enabled: () -> Boolean,
) : StreamingModelBackend {

    override val id: String = BACKEND_ID

    /**
     * The window is the one the app plans with. The account's models do not report theirs,
     * so this is a fixed budget, not a measured one.
     */
    override val capabilities: ModelCapabilities
        get() = ModelCapabilities(
            contextLength = CONTEXT_TOKENS,
            supportsToolCalling = true,
            supportsGrammar = false,
            supportsVision = true,
            supportsKvCache = false,
            supportsAudioInput = false,
        )

    /** The connection of the request in flight, so [cancel] can close it from any thread. */
    @Volatile
    private var active: HttpURLConnection? = null

    private val cancelRequested = AtomicBoolean(false)

    /** The first listed model, remembered per account until the account changes. */
    @Volatile
    private var firstModel: Pair<String, String>? = null

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
        if (!enabled() || plugin.account() == null) {
            return@withContext failed("ChatGPT is off or not signed in. Pick a local model on the Models page.")
        }

        val partial = StringBuilder()
        val stream = Stream()
        val job = currentCoroutineContext()[Job]
        val onCancel = closeOnCancel(job)
        val startedAt = System.nanoTime()
        try {
            val account = plugin.account()
                ?: throw ChatGptError("Sign in with ChatGPT first.", needsSignIn = true)
            val body = requestBody(request, modelSlug(account.subject)).toString()

            var token = ChatGptAuth.accessToken(plugin)
            var connection = openResponses(body, token)
            if (connection.responseCode == 401) {
                // One refresh, then one more try. A second 401 is reported as a sign-in problem.
                connection.disconnect()
                token = ChatGptAuth.accessToken(plugin, force = true)
                connection = openResponses(body, token)
            }
            val status = connection.responseCode
            if (status !in 200..299) {
                val detail = connection.errorStream?.use { readCapped(it, ERROR_BYTES) }.orEmpty()
                return@withContext failed(httpFailure(status, detail, token))
            }
            connection.inputStream.use { input ->
                readStream(input, token, partial, stream, startedAt, onToken)
            }

            stream.error?.let { return@withContext failed(it) }
            if (cancelRequested.get() || job?.isCancelled == true) {
                return@withContext GenerationResult(
                    text = partial.toString(),
                    stopReason = StopReason.CANCELLED,
                    promptTokens = stream.promptTokens,
                    completionTokens = stream.completionTokens,
                )
            }
            if (!stream.completed && stream.incompleteReason == null) {
                return@withContext failed("ChatGPT's reply ended before it finished. Try again.")
            }
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
        } catch (e: ChatGptError) {
            failed(e.userMessage)
        } catch (e: IOException) {
            // A cancel closes the socket, so the read fails. That is a stop the user asked for.
            if (cancelRequested.get() || job?.isCancelled == true) {
                GenerationResult(
                    text = partial.toString(),
                    stopReason = StopReason.CANCELLED,
                    promptTokens = stream.promptTokens,
                    completionTokens = stream.completionTokens,
                )
            } else {
                failed("could not reach ChatGPT (${e.javaClass.simpleName}). Check the connection.")
            }
        } catch (e: IllegalArgumentException) {
            // Raised for an attachment this backend cannot send. The message is ours.
            failed(e.message ?: "request not sent")
        } catch (e: Exception) {
            failed("ChatGPT chat could not run (${e.javaClass.simpleName}).")
        } finally {
            onCancel?.dispose()
            active?.disconnect()
            active = null
        }
    }

    /** The models the account can use, the ones marked for display, in the server's order. */
    suspend fun listModels(): List<ChatGptModel> {
        val reply = apiGet("$RESOURCE/models")
        val models = (parseJsonObject(reply.body)?.get("models") as? JsonArray).orEmpty()
        return models.mapNotNull { element ->
            val model = element as? JsonObject ?: return@mapNotNull null
            if (model.string("visibility") != "list") return@mapNotNull null
            val slug = model.string("slug") ?: return@mapNotNull null
            ChatGptModel(slug, model.string("display_name") ?: slug)
        }
    }

    /** The model to send: the user's Technical choice, else the account's first listed model. */
    private suspend fun modelSlug(subject: String): String {
        plugin.chosenModel().takeIf { it.isNotBlank() }?.let { return it }
        firstModel?.takeIf { it.first == subject }?.let { return it.second }
        val first = listModels().firstOrNull()?.slug
            ?: throw ChatGptError("ChatGPT lists no models for this account.")
        firstModel = subject to first
        return first
    }

    /** A GET to the resource API, with one refresh if the first answer is 401. */
    private suspend fun apiGet(url: String): HttpReply {
        var token = ChatGptAuth.accessToken(plugin)
        var reply = chatGptCall("GET", url, bearer = token)
        if (reply.status == 401) {
            token = ChatGptAuth.accessToken(plugin, force = true)
            reply = chatGptCall("GET", url, bearer = token)
        }
        if (!reply.ok) throw ChatGptError(httpFailure(reply.status, reply.body, token))
        return reply
    }

    private fun openResponses(body: String, token: String): HttpURLConnection {
        val connection = openChatGptConnection(
            "POST", "$RESOURCE/responses",
            bearer = token, json = body, accept = "text/event-stream",
        )
        active = connection
        return connection
    }

    override fun countTokens(text: String): Int {
        if (text.isEmpty()) return 0
        // The same approximation the other remote backend uses: the tokenizer is not here.
        return (text.length + 3) / 4 + text.count { it == '\n' }
    }

    override fun cancel() {
        cancelRequested.set(true)
        active?.disconnect()
    }

    /**
     * Closes the connection when the calling coroutine is cancelled. A blocking read does not
     * observe cancellation on its own, so the hook has to run on the cancelling edge.
     */
    @OptIn(InternalCoroutinesApi::class)
    private fun closeOnCancel(job: Job?): DisposableHandle? =
        job?.invokeOnCompletion(onCancelling = true, invokeImmediately = false) {
            active?.disconnect()
        }

    private fun failed(reason: String) = GenerationResult(text = reason, stopReason = StopReason.ERROR)

    /** Mutable state of one streamed reply. */
    private class Stream {
        var completed = false
        var incompleteReason: String? = null
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
     * Reads the server-sent events of one reply. Each `data:` line is one JSON event, keyed by
     * its `type`. The loop ends at the first terminal event: completed, incomplete, failed.
     */
    private suspend fun readStream(
        input: InputStream,
        token: String,
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
                if (payload.isEmpty()) continue
                val event = parseJsonObject(payload) ?: continue

                when (event.string("type")) {
                    "response.output_text.delta" -> {
                        // Not trimmed: a delta can be a single space, and dropping it joins words.
                        val piece = (event["delta"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                        if (!piece.isNullOrEmpty()) {
                            stream.firstToken(startedAt)
                            partial.append(piece)
                            onToken(piece)
                        }
                    }
                    "response.completed" -> {
                        readUsage(event, stream)
                        stream.completed = true
                        break
                    }
                    "response.incomplete" -> {
                        readUsage(event, stream)
                        val reason = (event.response()?.get("incomplete_details") as? JsonObject)?.string("reason")
                        stream.incompleteReason = reason ?: "unknown"
                        stream.stop = if (reason == "max_output_tokens") StopReason.MAX_TOKENS else StopReason.ERROR
                        break
                    }
                    "response.failed" -> {
                        val error = event.response()?.get("error") as? JsonObject
                        stream.error = describe(null, error?.string("code"), error?.string("message"), token)
                        break
                    }
                    "error" -> {
                        stream.error = describe(null, event.string("code"), event.string("message"), token)
                        break
                    }
                }
            }
        }
    }

    private fun JsonObject.response(): JsonObject? = this["response"] as? JsonObject

    private fun readUsage(event: JsonObject, stream: Stream) {
        val usage = event.response()?.get("usage") as? JsonObject ?: return
        stream.promptTokens = (usage["input_tokens"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
        stream.completionTokens = (usage["output_tokens"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
    }

    companion object {
        /** Stable id for this backend. */
        const val BACKEND_ID = "chatgpt-chat"

        /** The context budget the app plans with for this backend. */
        const val CONTEXT_TOKENS = 128_000

        /** An error body is a few hundred bytes. */
        private const val ERROR_BYTES = 16 * 1024

        /** Longest error text that reaches the model or the run trace. */
        private const val MESSAGE_CHARS = 160

        /**
         * The Responses request body. Only the fields the route accepts: no sampling, no output
         * limit, no conversation state. The history is sent whole, on each request.
         */
        internal fun requestBody(request: GenerationRequest, model: String): JsonObject {
            val instructions = request.messages
                .filterIsInstance<ChatMessage.System>()
                .joinToString("\n\n") { it.text }
            val lastUser = request.messages.indexOfLast { it is ChatMessage.User && !it.note }
            return buildJsonObject {
                put("model", model)
                if (instructions.isNotBlank()) put("instructions", instructions)
                put("input", inputJson(request, lastUser))
                put("store", false)
                put("stream", true)
            }
        }

        /**
         * The transcript as input items. System text is not an item here; it is `instructions`.
         * A tool observation has no role of its own, so it is a user turn, fenced by its own text.
         */
        private fun inputJson(request: GenerationRequest, lastUser: Int): JsonArray = buildJsonArray {
            request.messages.forEachIndexed { index, message ->
                when (message) {
                    is ChatMessage.System -> Unit
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

        private fun turn(role: String, content: JsonElement): JsonObject = buildJsonObject {
            put("role", role)
            put("content", content)
        }

        /**
         * Plain text when nothing is attached; otherwise input parts. Images go as data URLs with
         * no line breaks. Audio is refused here: this route does not take it.
         */
        private fun withMedia(text: String, media: List<MediaInput>): JsonElement {
            if (media.isEmpty()) return JsonPrimitive(text)
            return buildJsonArray {
                add(buildJsonObject {
                    put("type", "input_text")
                    put("text", text)
                })
                for (item in media) {
                    when {
                        item.isImage -> {
                            val encoded = Base64.encodeToString(item.bytes, Base64.NO_WRAP)
                            add(buildJsonObject {
                                put("type", "input_image")
                                put("image_url", "data:${item.mimeType};base64,$encoded")
                            })
                        }
                        item.isAudio -> throw IllegalArgumentException(
                            "Audio is not sent to ChatGPT. Attach an image, or use the phone's model.",
                        )
                    }
                }
            }
        }

        /** A non-2xx reply as one short line. A 401 means the sign-in no longer works. */
        private fun httpFailure(status: Int, body: String, token: String): String {
            if (status == 401) return "Sign in with ChatGPT again."
            val json = parseJsonObject(body)
            val error = json?.get("error") as? JsonObject
            val code = error?.string("code") ?: json?.string("code")
            val message = error?.string("message") ?: json?.string("detail") ?: json?.string("message")
                ?: body.takeIf { json == null }
            return describe(status, code, message, token)
        }

        /**
         * One user-facing line for a ChatGPT error code. Codes from the docs get their own wording;
         * anything else shows HTTP status and the message, with the token removed in case it is echoed.
         */
        internal fun describe(status: Int?, code: String?, message: String?, token: String): String = when (code) {
            "subscription_sharing_usage_limit_exceeded" ->
                "Your ChatGPT plan's limit has been reached. Check usage and limits in ChatGPT settings: " +
                    ChatGptSettings.USAGE_URL
            "subscription_sharing_usage_unavailable", "subscription_sharing_user_not_eligible" ->
                "ChatGPT plan use isn't available for this account right now."
            "subscription_sharing_unsupported_capability" ->
                "ChatGPT does not accept part of this request. Try again, or use the phone's model."
            "subscription_sharing_invalid_user" -> "Sign in with ChatGPT again."
            else -> {
                val detail = scrubbed(message.orEmpty(), token)
                val head = if (status != null) "ChatGPT returned HTTP $status." else "ChatGPT stopped the reply."
                if (detail.isBlank()) head else "$head $detail"
            }
        }

        private fun scrubbed(text: String, token: String): String {
            val cleaned = if (token.isBlank()) text else text.replace(token, "[token]")
            return cleaned.replace(Regex("\\s+"), " ").trim().take(MESSAGE_CHARS)
        }
    }
}
