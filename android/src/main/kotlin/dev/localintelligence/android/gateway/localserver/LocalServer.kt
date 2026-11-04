package dev.localintelligence.android.gateway.localserver

import dev.localintelligence.core.model.ChatMessage
import dev.localintelligence.core.model.SamplingParams
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Semaphore

/**
 * What one request came back with, from the app's side of the run gate.
 *
 * The server does not know about the model, the run gate or the chat. The app answers with one of
 * these, so this file stays plain JVM code that can be read on its own.
 */
sealed interface LocalRun {
    /** The model answered. [length] is true when it stopped at the token limit. */
    data class Done(val text: String, val promptTokens: Int, val completionTokens: Int, val length: Boolean) : LocalRun

    /** Another run holds the model: a chat run, a scheduled run, or another request. */
    data object Busy : LocalRun

    /** No model is loaded. The server never starts a load on a request's behalf. */
    data object NoModel : LocalRun

    /** The model could not finish. The text is not sent to the client, so no detail leaks. */
    data object Failed : LocalRun
}

/**
 * The app side of the server. One call is one request: the implementation claims the process run
 * gate, runs the model on [messages] alone, and releases the gate.
 */
interface LocalServerEngine {
    /** The id of the resident model, or null when none can answer. */
    fun modelId(): String?

    /**
     * Runs one request. [onStarted] fires once the gate is held and the model is ready, so a
     * streamed response only starts after a run has been granted. [onToken] gets each piece of text.
     */
    suspend fun run(
        messages: List<ChatMessage>,
        params: SamplingParams,
        onStarted: () -> Unit,
        onToken: (String) -> Unit,
    ): LocalRun

    /** Stops the request in flight, if any. Safe to call when idle. */
    fun cancelInFlight()
}

/**
 * A loopback HTTP server that offers the loaded model to other apps on this phone.
 *
 * ```
 * GET  /v1/models            -> the loaded model, or an empty list
 * POST /v1/chat/completions  -> one answer, or an SSE stream when "stream": true
 * ```
 *
 * ## Why hand-rolled, on java.net
 *
 * Two routes and a bearer token do not justify a web framework or a new dependency, and every
 * dependency added to the app is one more thing to audit for a socket this exposed. The HTTP
 * subset here is what the two routes need and no more: no keep-alive, no chunked request bodies,
 * one response per connection.
 *
 * ## Why loopback only
 *
 * Bound to 127.0.0.1, nothing on the network can reach it. Other apps on the phone still can, which is
 * why the bearer token is the only barrier (`docs/threat-model.md` T11).
 *
 * ## Limits
 *
 * 1 MiB request body, 16 KiB of headers, a 30 s read timeout, and at most [MAX_CONNECTIONS] open
 * connections. A connection past the limit is answered 503 at once and closed, so a client that
 * opens sockets and stalls cannot hold the model or the process.
 */
class LocalServer(
    private val engine: LocalServerEngine,
    /** Read on every request, so Regenerate takes effect at once and no restart is needed. */
    private val expectedToken: () -> String,
    private val port: Int = LocalServerSettings.PORT,
) {
    private val slots = Semaphore(MAX_CONNECTIONS)
    private val open: MutableSet<Socket> = ConcurrentHashMap.newKeySet()

    private var listener: ServerSocket? = null
    private var pool: ExecutorService? = null

    /**
     * Binds the loopback port and starts accepting. Throws [IOException] when the port is taken,
     * and the caller reports that in settings rather than crashing.
     */
    @Synchronized
    fun start() {
        check(listener == null) { "The local server is already running." }
        val socket = ServerSocket()
        try {
            socket.reuseAddress = true
            // The literal address, not "localhost": a name could resolve to something other than loopback.
            socket.bind(InetSocketAddress(LocalServerSettings.HOST, port), BACKLOG)
        } catch (e: IOException) {
            socket.close()
            throw e
        }
        listener = socket
        pool = Executors.newFixedThreadPool(MAX_CONNECTIONS)
        Thread({ acceptLoop(socket) }, "local-server-accept").apply { isDaemon = true }.start()
    }

    /** Closes the port, drops open connections and cancels a generation in flight. */
    @Synchronized
    fun stop() {
        val socket = listener ?: return
        listener = null
        runCatching { socket.close() }
        open.forEach { runCatching { it.close() } }
        pool?.shutdownNow()
        pool = null
        engine.cancelInFlight()
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (e: IOException) {
                break
            }
            client.soTimeout = READ_TIMEOUT_MS
            if (!slots.tryAcquire()) {
                runCatching { writeJson(client.getOutputStream(), 503, errorJson("Too many connections. Try again.")) }
                runCatching { client.close() }
                continue
            }
            open.add(client)
            val executor = pool
            try {
                executor?.execute { handle(client) } ?: release(client)
            } catch (e: RejectedExecutionException) {
                release(client)
            }
        }
    }

    private fun release(client: Socket) {
        open.remove(client)
        runCatching { client.close() }
        slots.release()
    }

    private fun handle(client: Socket) {
        try {
            val input = BufferedInputStream(client.getInputStream())
            val out = client.getOutputStream()
            try {
                val head = readHead(input)
                route(head, input, out)
            } catch (e: HttpError) {
                runCatching { writeJson(out, e.status, errorJson(e.message ?: "Request refused.")) }
            } catch (e: IOException) {
                // A timeout or a client that went away. There is nobody left to answer.
            }
        } finally {
            release(client)
        }
    }

    // ------------------------------------------------------------ request head

    private class Head(val method: String, val path: String, val headers: Map<String, String>)

    private fun readHead(input: InputStream): Head {
        val budget = intArrayOf(MAX_HEADER_BYTES)
        val requestLine = readLine(input, budget)
        val parts = requestLine.split(' ')
        if (parts.size != 3 || !parts[2].startsWith("HTTP/1.")) throw HttpError(400, "Malformed request line.")
        val target = parts[1]
        if (!target.startsWith("/")) throw HttpError(400, "Malformed request line.")
        val path = target.substringBefore('?')
        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(input, budget)
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon <= 0) throw HttpError(400, "Malformed header.")
            headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
        return Head(parts[0], path, headers)
    }

    private fun readLine(input: InputStream, budget: IntArray): String {
        val line = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) throw IOException("The client closed the connection.")
            if (b == '\n'.code) break
            budget[0]--
            if (budget[0] < 0) throw HttpError(431, "The request headers are too large.")
            line.write(b)
        }
        return line.toString(StandardCharsets.ISO_8859_1.name()).trimEnd('\r')
    }

    private fun readBody(head: Head, input: InputStream, out: OutputStream): ByteArray {
        if (head.headers.containsKey("transfer-encoding")) {
            throw HttpError(400, "Chunked request bodies are not supported. Send a Content-Length header.")
        }
        val raw = head.headers["content-length"] ?: throw HttpError(411, "Send a Content-Length header.")
        val length = raw.toLongOrNull()?.takeIf { it >= 0 } ?: throw HttpError(400, "Malformed Content-Length.")
        if (length > MAX_BODY_BYTES) throw HttpError(413, "The request is larger than 1 MiB.")
        // Clients such as curl send this for larger bodies and wait for the go-ahead.
        if (head.headers["expect"]?.equals("100-continue", ignoreCase = true) == true) {
            out.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray(StandardCharsets.ISO_8859_1))
            out.flush()
        }
        val body = ByteArray(length.toInt())
        var read = 0
        while (read < body.size) {
            val n = input.read(body, read, body.size - read)
            if (n < 0) throw HttpError(400, "The request body ended early.")
            read += n
        }
        return body
    }

    // ------------------------------------------------------------ routing

    private fun route(head: Head, input: InputStream, out: OutputStream) {
        if (head.path != PATH_MODELS && head.path != PATH_CHAT) throw HttpError(404, "Not found.")
        if (!authorized(head.headers["authorization"])) {
            throw HttpError(401, "Send the access token as a Bearer header.")
        }
        when (head.path) {
            PATH_MODELS -> {
                if (head.method != "GET") throw HttpError(405, "Use GET.")
                writeJson(out, 200, modelsJson())
            }
            else -> {
                if (head.method != "POST") throw HttpError(405, "Use POST.")
                chat(readBody(head, input, out), out)
            }
        }
    }

    /**
     * Compares SHA-256 digests of the two tokens rather than the tokens, so the comparison takes the
     * same time whatever the lengths are. An empty expected token refuses everything.
     */
    private fun authorized(header: String?): Boolean {
        val expected = expectedToken()
        if (expected.isBlank() || header == null) return false
        val prefix = "bearer "
        if (!header.regionMatches(0, prefix, 0, prefix.length, ignoreCase = true)) return false
        val given = header.substring(prefix.length).trim()
        val digest = MessageDigest.getInstance("SHA-256")
        val a = digest.digest(expected.toByteArray(StandardCharsets.UTF_8))
        val b = digest.digest(given.toByteArray(StandardCharsets.UTF_8))
        return MessageDigest.isEqual(a, b)
    }

    private fun modelsJson(): JsonObject {
        val id = engine.modelId()
        return buildJsonObject {
            put("object", "list")
            put("data", buildJsonArray {
                if (id != null) {
                    add(buildJsonObject {
                        put("id", id)
                        put("object", "model")
                        put("created", 0)
                        put("owned_by", "local")
                    })
                }
            })
        }
    }

    // ------------------------------------------------------------ chat

    private fun chat(body: ByteArray, out: OutputStream) {
        val root = parseObject(body)
        if (hasTools(root)) throw HttpError(400, "Tools are not supported by this server.")
        val stream = root["stream"]?.let { value ->
            (value as? JsonPrimitive)?.booleanOrNull ?: throw HttpError(400, "\"stream\" must be true or false.")
        } ?: false
        val messages = parseMessages(root["messages"])
        val params = parseParams(root)
        val model = engine.modelId() ?: "local"
        val id = "chatcmpl-" + UUID.randomUUID().toString().replace("-", "")
        val created = System.currentTimeMillis() / 1000

        if (!stream) {
            when (val result = runBlocking {
                engine.run(messages, params, onStarted = {}, onToken = {})
            }) {
                is LocalRun.Done -> writeJson(out, 200, completionJson(id, created, model, result))
                is LocalRun.Busy -> throw HttpError(429, "The model is busy with another request. Try again shortly.")
                is LocalRun.NoModel -> throw HttpError(503, "No model is loaded in LocalIntelligence.")
                is LocalRun.Failed -> throw HttpError(500, "The model could not finish the answer.")
            }
            return
        }

        streamChat(messages, params, id, created, model, out)
    }

    private fun streamChat(
        messages: List<ChatMessage>,
        params: SamplingParams,
        id: String,
        created: Long,
        model: String,
        out: OutputStream,
    ) {
        var started = false
        // Set when a write fails: the client has gone, so the generation is stopped.
        var clientGone = false
        val result = runBlocking {
            engine.run(
                messages, params,
                onStarted = {
                    started = true
                    writeRaw(out, SSE_HEADERS)
                    writeEvent(out, chunkJson(id, created, model, delta = buildJsonObject { put("role", "assistant"); put("content", "") }, finish = null))
                },
                onToken = { piece ->
                    if (!clientGone) {
                        try {
                            writeEvent(out, chunkJson(id, created, model, delta = buildJsonObject { put("content", piece) }, finish = null))
                        } catch (e: IOException) {
                            clientGone = true
                            engine.cancelInFlight()
                        }
                    }
                },
            )
        }
        if (!started) {
            // Nothing was sent yet, so the failure can still be a proper HTTP error.
            when (result) {
                is LocalRun.Busy -> throw HttpError(429, "The model is busy with another request. Try again shortly.")
                is LocalRun.NoModel -> throw HttpError(503, "No model is loaded in LocalIntelligence.")
                else -> throw HttpError(500, "The model could not finish the answer.")
            }
        }
        if (clientGone) return
        when (result) {
            is LocalRun.Done -> writeEvent(
                out,
                chunkJson(id, created, model, delta = buildJsonObject {}, finish = if (result.length) "length" else "stop"),
            )
            else -> writeEvent(out, errorJson("The model could not finish the answer."))
        }
        writeRaw(out, "data: [DONE]\n\n".toByteArray(StandardCharsets.UTF_8))
    }

    private fun hasTools(root: JsonObject): Boolean {
        val tools = root["tools"]
        val functions = root["functions"]
        return (tools is JsonArray && tools.isNotEmpty()) || (functions is JsonArray && functions.isNotEmpty())
    }

    private fun parseObject(body: ByteArray): JsonObject {
        val element = try {
            Json.parseToJsonElement(String(body, StandardCharsets.UTF_8))
        } catch (e: Exception) {
            throw HttpError(400, "The body is not valid JSON.")
        }
        return element as? JsonObject ?: throw HttpError(400, "The body must be a JSON object.")
    }

    /**
     * Turns the OpenAI message list into the app's own messages. Text only: an image part, a tool
     * call or a tool result is refused, because the model here has no tools and no media in this path.
     */
    private fun parseMessages(element: JsonElement?): List<ChatMessage> {
        val array = element as? JsonArray ?: throw HttpError(400, "\"messages\" must be a list.")
        if (array.isEmpty()) throw HttpError(400, "\"messages\" must not be empty.")
        if (array.size > MAX_MESSAGES) throw HttpError(400, "Too many messages. The limit is $MAX_MESSAGES.")
        return array.map { item ->
            val message = item as? JsonObject ?: throw HttpError(400, "Each message must be an object.")
            if (message["tool_calls"] != null && message["tool_calls"] !is JsonNull) {
                throw HttpError(400, "Tool calls are not supported by this server.")
            }
            val role = (message["role"] as? JsonPrimitive)?.contentOrNull ?: throw HttpError(400, "A message has no role.")
            val text = messageText(message["content"])
            when (role) {
                "system", "developer" -> ChatMessage.System(text)
                "user" -> ChatMessage.User(text)
                "assistant" -> ChatMessage.Assistant(text)
                else -> throw HttpError(400, "Unsupported role \"$role\". Use system, user or assistant.")
            }
        }
    }

    /** A string, or a list of parts where every part is text. Anything else is refused. */
    private fun messageText(content: JsonElement?): String = when (content) {
        null, is JsonNull -> ""
        is JsonPrimitive -> if (content.isString) content.content else throw HttpError(400, "\"content\" must be text.")
        is JsonArray -> content.joinToString("\n") { part ->
            val obj = part as? JsonObject ?: throw HttpError(400, "Each content part must be an object.")
            if ((obj["type"] as? JsonPrimitive)?.contentOrNull != "text") {
                throw HttpError(400, "Only text content is supported by this server.")
            }
            (obj["text"] as? JsonPrimitive)?.contentOrNull ?: throw HttpError(400, "A text part has no text.")
        }
        else -> throw HttpError(400, "\"content\" must be text.")
    }

    private fun parseParams(root: JsonObject): SamplingParams {
        val defaults = SamplingParams()
        val temperature = number(root, "temperature")?.also {
            if (it < 0.0 || it > 2.0) throw HttpError(400, "\"temperature\" must be between 0 and 2.")
        }
        val topP = number(root, "top_p")?.also {
            if (it <= 0.0 || it > 1.0) throw HttpError(400, "\"top_p\" must be above 0 and at most 1.")
        }
        val maxTokens = (root["max_tokens"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull?.also {
            if (it < 1) throw HttpError(400, "\"max_tokens\" must be at least 1.")
        }
        return defaults.copy(
            temperature = temperature?.toFloat() ?: defaults.temperature,
            topP = topP?.toFloat() ?: defaults.topP,
            maxOutputTokens = minOf(maxTokens ?: defaults.maxOutputTokens, MAX_OUTPUT_TOKENS),
        )
    }

    private fun number(root: JsonObject, key: String): Double? {
        val value = root[key] ?: return null
        if (value is JsonNull) return null
        val primitive = value as? JsonPrimitive ?: throw HttpError(400, "\"$key\" must be a number.")
        if (primitive.isString) throw HttpError(400, "\"$key\" must be a number.")
        return primitive.doubleOrNull ?: throw HttpError(400, "\"$key\" must be a number.")
    }

    // ------------------------------------------------------------ responses

    private fun completionJson(id: String, created: Long, model: String, done: LocalRun.Done): JsonObject =
        buildJsonObject {
            put("id", id)
            put("object", "chat.completion")
            put("created", created)
            put("model", model)
            put("choices", buildJsonArray {
                add(buildJsonObject {
                    put("index", 0)
                    put("message", buildJsonObject {
                        put("role", "assistant")
                        put("content", done.text)
                    })
                    put("finish_reason", if (done.length) "length" else "stop")
                })
            })
            put("usage", buildJsonObject {
                put("prompt_tokens", done.promptTokens)
                put("completion_tokens", done.completionTokens)
                put("total_tokens", done.promptTokens + done.completionTokens)
            })
        }

    private fun chunkJson(id: String, created: Long, model: String, delta: JsonObject, finish: String?): JsonObject =
        buildJsonObject {
            put("id", id)
            put("object", "chat.completion.chunk")
            put("created", created)
            put("model", model)
            put("choices", buildJsonArray {
                add(buildJsonObject {
                    put("index", 0)
                    put("delta", delta)
                    put("finish_reason", finish?.let { JsonPrimitive(it) } ?: JsonNull)
                })
            })
        }

    private fun errorJson(message: String): JsonObject = buildJsonObject {
        put("error", buildJsonObject {
            put("message", message)
            put("type", "server_error")
        })
    }

    private fun writeJson(out: OutputStream, status: Int, body: JsonObject) {
        val bytes = body.toString().toByteArray(StandardCharsets.UTF_8)
        val head = "HTTP/1.1 $status ${reason(status)}\r\n" +
            "Content-Type: application/json\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n\r\n"
        writeRaw(out, head.toByteArray(StandardCharsets.ISO_8859_1) + bytes)
    }

    private fun writeEvent(out: OutputStream, json: JsonObject) {
        writeRaw(out, "data: $json\n\n".toByteArray(StandardCharsets.UTF_8))
    }

    private fun writeRaw(out: OutputStream, bytes: ByteArray) {
        out.write(bytes)
        out.flush()
    }

    private fun reason(status: Int): String = when (status) {
        200 -> "OK"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        411 -> "Length Required"
        413 -> "Payload Too Large"
        429 -> "Too Many Requests"
        431 -> "Request Header Fields Too Large"
        500 -> "Internal Server Error"
        503 -> "Service Unavailable"
        else -> "Error"
    }

    /** A refusal with a message that is safe to show a client. Never carries a stack trace. */
    private class HttpError(val status: Int, message: String) : RuntimeException(message)

    companion object {
        const val PATH_MODELS = "/v1/models"
        const val PATH_CHAT = "/v1/chat/completions"

        const val MAX_CONNECTIONS = 4
        const val MAX_BODY_BYTES = 1L shl 20
        const val MAX_HEADER_BYTES = 16 * 1024
        const val READ_TIMEOUT_MS = 30_000
        const val MAX_MESSAGES = 256
        const val MAX_OUTPUT_TOKENS = 2048
        private const val BACKLOG = 4

        private val SSE_HEADERS = (
            "HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/event-stream\r\n" +
                "Cache-Control: no-cache\r\n" +
                "Connection: close\r\n\r\n"
            ).toByteArray(StandardCharsets.ISO_8859_1)
    }
}
