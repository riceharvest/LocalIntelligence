package dev.localintelligence.android.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

// ===========================================================================
// A minimal client for one remote MCP server, Streamable HTTP transport.
//
// One [McpSession] lives for one run. The plugin opens it when the run's tool
// list is built, lists the server's tools, and the tools call through the same
// session, so the Mcp-Session-Id the server issued at initialize is kept for the
// run and dropped after it. No session outlives a run, and nothing is cached
// across runs: a server that changes its tools shows the change at the next
// message.
//
// What leaves the phone: every JSON-RPC body, the tool arguments the model
// chose, and the bearer token in the Authorization header. Redirects are not
// followed, so the token cannot be carried to a host the user did not name.
//
// The reply to a POST is either one JSON document or an SSE stream. For a
// stream, the first `data:` event whose id matches the request is the reply;
// anything else the server sends (notifications, its own requests) is ignored.
// ===========================================================================

/** The protocol revision this client speaks. Sent at initialize and on every request. */
internal const val MCP_PROTOCOL_VERSION = "2025-06-18"

/** Connect and read timeout for each request, in milliseconds. */
private const val TIMEOUT_MS = 20_000

/** Largest reply accepted, in bytes. A tool list is the biggest thing a server sends. */
private const val MAX_REPLY_BYTES = 1024 * 1024

/** Pages of tools/list followed before the listing is cut. */
private const val MAX_PAGES = 20

/**
 * A failure the model may be told about. Its text never contains the bearer
 * token: [McpSession] scrubs it before building the message.
 */
internal class McpException(message: String) : IOException(message)

/** One initialised session with one server, for one run. */
internal class McpSession private constructor(
    private val url: String,
    private val token: String?,
) {
    @Volatile
    private var sessionId: String? = null
    private var nextId = 1L

    /** Every tool the server lists, following pagination. */
    fun listTools(): List<JsonObject> {
        val tools = mutableListOf<JsonObject>()
        var cursor: String? = null
        repeat(MAX_PAGES) {
            val result = request("tools/list", buildJsonObject { cursor?.let { put("cursor", it) } })
            (result["tools"] as? JsonArray)?.forEach { element ->
                (element as? JsonObject)?.let(tools::add)
            }
            cursor = (result["nextCursor"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }
                ?: return tools
        }
        return tools
    }

    /** `tools/call`. The result object is returned whole: the caller reads `content` and `isError`. */
    fun callTool(name: String, arguments: JsonObject): JsonObject =
        request(
            "tools/call",
            buildJsonObject {
                put("name", name)
                put("arguments", arguments)
            },
        )

    private fun handshake() {
        request(
            "initialize",
            buildJsonObject {
                put("protocolVersion", MCP_PROTOCOL_VERSION)
                put("capabilities", buildJsonObject {})
                put(
                    "clientInfo",
                    buildJsonObject {
                        put("name", "LocalIntelligence")
                        put("version", "1.0")
                    },
                )
            },
        )
        notify("notifications/initialized")
    }

    private fun request(method: String, params: JsonObject): JsonObject {
        val id = nextId++
        val body = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            put("params", params)
        }.toString()
        val reply = post(body, id) ?: throw McpException("the server sent no reply to $method")
        reply["error"]?.let { error ->
            val message = ((error as? JsonObject)?.get("message") as? JsonPrimitive)?.contentOrNull.orEmpty()
            throw McpException("the server refused $method: ${scrub(message)}")
        }
        return reply["result"] as? JsonObject
            ?: throw McpException("the server's reply to $method had no result")
    }

    private fun notify(method: String) {
        post(buildJsonObject { put("jsonrpc", "2.0"); put("method", method) }.toString(), id = null)
    }

    /**
     * One POST. Returns the reply to request [id], or null for a notification
     * (the server answers 202 with no body). Throws [McpException] for anything
     * that is not a 2xx answer.
     */
    private fun post(body: String, id: Long?): JsonObject? {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.instanceFollowRedirects = false
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json, text/event-stream")
            connection.setRequestProperty("MCP-Protocol-Version", MCP_PROTOCOL_VERSION)
            token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            sessionId?.let { connection.setRequestProperty("Mcp-Session-Id", it) }
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val status = connection.responseCode
            connection.getHeaderField("Mcp-Session-Id")?.takeIf { it.isNotBlank() }?.let { sessionId = it }
            if (status !in 200..299) {
                val detail = connection.errorStream?.use { readCapped(it) }.orEmpty()
                throw McpException(
                    "the server answered HTTP $status" +
                        if (detail.isNotBlank()) ": ${scrub(detail)}" else "",
                )
            }
            if (id == null) return null

            val type = connection.contentType.orEmpty().substringBefore(';').trim().lowercase()
            return connection.inputStream.use { stream ->
                when (type) {
                    "text/event-stream" -> firstMatchingEvent(stream, id)
                    "application/json" -> matchingReply(parse(readCapped(stream)), id)
                    else -> throw McpException("the server replied with an unexpected type: $type")
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    /** Reads SSE events until one carries a reply to [id]. The stream is not read past it. */
    private fun firstMatchingEvent(stream: InputStream, id: Long): JsonObject? {
        val reader = BufferedReader(InputStreamReader(stream, Charsets.UTF_8))
        val data = StringBuilder()
        var budget = MAX_REPLY_BYTES
        while (true) {
            val line = reader.readLine()
            if (line == null) return eventReply(data, id)
            budget -= line.length + 1
            if (budget < 0) throw McpException("the server's reply is over 1 MB")
            if (line.isEmpty()) {
                eventReply(data, id)?.let { return it }
                data.setLength(0)
            } else if (line.startsWith("data:")) {
                if (data.isNotEmpty()) data.append('\n')
                data.append(line.removePrefix("data:").removePrefix(" "))
            }
        }
    }

    /** An event that is not JSON, or is not the reply we want, is skipped rather than failing the call. */
    private fun eventReply(data: StringBuilder, id: Long): JsonObject? {
        if (data.isEmpty()) return null
        val element = runCatching { Json.parseToJsonElement(data.toString()) }.getOrNull() ?: return null
        return matchingReply(element, id)
    }

    /** A single document, or a batch array. Returns the element that answers [id]. */
    private fun matchingReply(root: JsonElement, id: Long): JsonObject? {
        val candidates = if (root is JsonArray) root.toList() else listOf(root)
        return candidates.firstNotNullOfOrNull { element ->
            (element as? JsonObject)?.takeIf { isReplyTo(it, id) }
        }
    }

    /**
     * A reply, not a request or notification from the server. The server's own
     * requests use their own id space, so a bare id match is not enough.
     */
    private fun isReplyTo(message: JsonObject, id: Long): Boolean =
        (message["id"] as? JsonPrimitive)?.longOrNull == id &&
            (message.containsKey("result") || message.containsKey("error"))

    private fun parse(text: String): JsonElement =
        try {
            Json.parseToJsonElement(text)
        } catch (e: kotlinx.serialization.SerializationException) {
            throw McpException("the server's reply was not JSON")
        }

    private fun readCapped(stream: InputStream): String {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val n = stream.read(buffer)
            if (n < 0) break
            if (out.size() + n > MAX_REPLY_BYTES) throw McpException("the server's reply is over 1 MB")
            out.write(buffer, 0, n)
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    /** Removes the bearer token from text that may reach the model, and bounds its length. */
    private fun scrub(text: String): String {
        val clean = token?.let { text.replace(it, "[token]") } ?: text
        return clean.replace(Regex("\\s+"), " ").take(160)
    }

    companion object {
        /**
         * Opens a session: initialize, then notifications/initialized. Throws
         * [McpException] or an [IOException] if the server does not answer.
         */
        fun open(url: String, token: String?): McpSession =
            McpSession(url, token).also { it.handshake() }
    }
}
