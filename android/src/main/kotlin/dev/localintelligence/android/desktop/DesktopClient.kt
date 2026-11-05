package dev.localintelligence.android.desktop

import dev.localintelligence.core.desktop.DesktopAgent
import dev.localintelligence.core.desktop.DesktopKeys
import dev.localintelligence.core.desktop.DesktopRules
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * The phone's side of the desklink bridge (`desktop-bridge/desklink.py`), over `HttpURLConnection`.
 *
 * WHY no login header here: the phone is on the user's tailnet, and tailscale serve adds the identity
 * header itself. The app holds no secret for the desktop. A request reaches the bridge only from a
 * device on the tailnet, and the bridge refuses any user but the one it was started for.
 *
 * WHY https only: [DesktopRules.normalizeServeUrl] has already checked the address before it reaches here.
 */
class DesktopClient(serveUrl: String, private val timeoutMs: Int = 10_000) {

    private val base: String = requireNotNull(DesktopRules.normalizeServeUrl(serveUrl)) {
        "the desktop address must be https"
    }

    /** One pane of text as the bridge read it, and the sequence number that goes with a key press. */
    data class Text(val text: String, val stateSeq: Long)

    /** Every agent the bridge can see. Throws [DesktopException] when the desktop cannot be reached. */
    suspend fun agents(): List<DesktopAgent> {
        val body = request("GET", "/v1/agents", null)
        return body["agents"]?.jsonArray.orEmpty().map { element ->
            val item = element.jsonObject
            DesktopAgent(
                target = item.string("target"),
                name = item.string("name"),
                status = item.string("status"),
                stateSeq = item["state_seq"]?.jsonPrimitive?.long ?: 0L,
                cwd = item.string("cwd"),
            )
        }
    }

    /** The agent's recent terminal text. */
    suspend fun text(target: String): Text {
        val body = request("GET", "/v1/agents/${encode(target)}/text", null)
        return Text(body.string("text"), body["state_seq"]?.jsonPrimitive?.long ?: 0L)
    }

    /** Sends a message. Refused with `agent_blocked` (409) while the agent waits for an answer. */
    suspend fun prompt(target: String, text: String) {
        request("POST", "/v1/agents/${encode(target)}/prompt", buildJsonObject { put("text", text) })
    }

    /**
     * Sends keys. [expectSeq] is the sequence number the user saw; the bridge refuses with `stale` (409)
     * when the agent has moved on, so the key lands on the question the user actually read.
     */
    suspend fun keys(target: String, keys: List<String>, expectSeq: Long) {
        require(keys.isNotEmpty() && keys.all { it in DesktopKeys.ALL }) { "key not on the list" }
        request(
            "POST", "/v1/agents/${encode(target)}/keys",
            buildJsonObject {
                put("keys", JsonArray(keys.map { JsonPrimitive(it) }))
                put("expect_seq", expectSeq)
            },
        )
    }

    /**
     * Streams the agent's text over server-sent events until the caller cancels or the connection ends.
     * The bridge writes an event or a keep-alive comment at least every 5 s, so a 30 s read timeout only fires when the
     * desktop has gone. [keepGoing] is checked on every line; [onText] runs on the calling thread. An `event: error` from the bridge ends the stream.
     */
    fun stream(target: String, keepGoing: () -> Boolean, onText: (Text) -> Unit) {
        val connection = open("GET", "/v1/agents/${encode(target)}/stream", readTimeoutMs = 30_000)
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw DesktopException(errorCode(code), "stream refused", false)
            connection.inputStream.bufferedReader().useLines { lines ->
                val data = StringBuilder()
                for (line in lines) {
                    // The caller cancels by turning keepGoing off. A keep-alive arrives every 1.5 s, so this is prompt.
                    if (!keepGoing()) break
                    when {
                        line.isEmpty() -> {
                            if (data.isNotEmpty()) {
                                val item = runCatching { Json.parseToJsonElement(data.toString()).jsonObject }.getOrNull()
                                data.clear()
                                if (item != null) onText(Text(item.string("text"), item["state_seq"]?.jsonPrimitive?.long ?: 0L))
                            }
                        }
                        line.startsWith("event: error") -> throw DesktopException("stream_error", "the desktop stopped the stream", false)
                        line.startsWith("data:") -> data.append(line.removePrefix("data:").trimStart())
                        // Comment lines (": keep-alive") and event names need no handling: only the data matters.
                    }
                }
            }
        } catch (e: IOException) {
            throw DesktopException("unreachable", "the desktop did not answer", unreachable = true)
        } finally {
            connection.disconnect()
        }
    }

    private fun request(method: String, path: String, body: JsonObject?): JsonObject {
        val connection = open(method, path, readTimeoutMs = timeoutMs)
        try {
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw DesktopException(errorCode(code, text), "the desktop refused", false)
            return runCatching { Json.parseToJsonElement(text).jsonObject }
                .getOrElse { throw DesktopException("bad_response", "unexpected answer from the desktop", false) }
        } catch (e: IOException) {
            throw DesktopException("unreachable", "the desktop did not answer", unreachable = true)
        } finally {
            connection.disconnect()
        }
    }

    private fun open(method: String, path: String, readTimeoutMs: Int): HttpURLConnection {
        val url = URL(base + path)
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = timeoutMs
        connection.readTimeout = readTimeoutMs
        connection.useCaches = false
        connection.setRequestProperty("Accept", if (path.endsWith("/stream")) "text/event-stream" else "application/json")
        return connection
    }

    /** The bridge's own error code when it sent one, else a code built from the HTTP status. */
    private fun errorCode(status: Int, body: String? = null): String {
        val fromBody = body?.let { runCatching { Json.parseToJsonElement(it).jsonObject["error"]?.jsonPrimitive?.content }.getOrNull() }
        return fromBody ?: "http_$status"
    }

    private fun encode(target: String): String = URLEncoder.encode(target, "UTF-8").replace("+", "%20")

    private fun JsonObject.string(key: String): String = this[key]?.jsonPrimitive?.content.orEmpty()
}

/**
 * A failed call to the desktop. [code] is the bridge's short code (`agent_blocked`, `stale`, `not_found`,
 * `forbidden`, ...) or `unreachable` when the desktop did not answer at all. [unreachable] tells the
 * caller a message may be kept and sent later.
 */
class DesktopException(
    val code: String,
    message: String,
    val unreachable: Boolean,
) : Exception(message)
