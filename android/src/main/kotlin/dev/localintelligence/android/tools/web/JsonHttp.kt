package dev.localintelligence.android.tools.web

import dev.localintelligence.core.tool.CancellationSignal
import java.net.HttpURLConnection
import java.net.URL

// ===========================================================================
// One JSON exchange, shared by the keyed search providers and the free info tools.
//
// WHY ITS OWN HELPER AND NOT ResponseOpener: ResponseOpener is GET-only and carries
// no headers, which a keyed API needs (the key, a JSON body). It stays the seam for
// web.fetch and web.search's HTML path, which are unchanged.
//
// WHY NO REDIRECTS: a keyed request must not carry its key to whatever host a 3xx
// names. Every call here goes to a fixed address the code chose, so a redirect is
// reported as a failure instead of followed.
// ===========================================================================

/** The largest JSON reply read. A reply past this is cut by [BodyReader], and the parse then fails. */
internal const val MAX_JSON_BYTES: Int = 512 * 1024

/** A finished exchange: the HTTP status and the body as text. */
internal class JsonReply(val status: Int, val body: String) {
    val ok: Boolean get() = status in 200..299
}

/**
 * One HTTP exchange that expects JSON. Throws IOException subclasses on network
 * failure; callers map them to a result. Blocking: call it on Dispatchers.IO.
 */
internal fun exchangeJson(
    method: String,
    url: String,
    headers: Map<String, String>,
    body: String?,
    userAgent: String,
    signal: CancellationSignal,
): JsonReply {
    val connection = URL(url).openConnection() as HttpURLConnection
    try {
        connection.requestMethod = method
        connection.instanceFollowRedirects = false
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.useCaches = false
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("User-Agent", userAgent)
        for ((name, value) in headers) connection.setRequestProperty(name, value)
        if (body != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        val status = connection.responseCode
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        val bytes = stream?.use { BodyReader.readCapped(it, MAX_JSON_BYTES, signal).bytes } ?: ByteArray(0)
        return JsonReply(status, String(bytes, Charsets.UTF_8))
    } finally {
        connection.disconnect()
    }
}
