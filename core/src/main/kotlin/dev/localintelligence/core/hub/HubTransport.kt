package dev.localintelligence.core.hub

import java.io.IOException
import java.io.InputStream

/**
 * The one thing the hub needs from the network, and the seam that makes every
 * hub test runnable with no network at all.
 *
 * ## Why this exists instead of calling `HttpURLConnection` inline
 *
 * Two of the things most worth testing here are the things a real network makes
 * untestable: a server that answers 429, a connection that drops 40% through a
 * transfer, a CDN that returns 206 with a `Content-Range` that does not match
 * what was asked for. Against a live HF endpoint those cases can only be
 * provoked by waiting for a bad day.
 *
 * So the transport is an interface, [UrlConnectionTransport] is the production
 * implementation, and the tests drive a fake that can be told to fail in any of
 * those ways deterministically. The real socket path is then the *only* part
 * that is unverified, and it is a thin layer over an interface whose contract
 * has been tested.
 *
 * ## The contract
 *
 * - [open] must not throw for a non-2xx status. A 404 is a legitimate answer
 *   and the caller maps it to [HubError]. Throwing would force a try/catch
 *   around every call site to distinguish "the server said no" from "the
 *   network broke", which are different user-facing messages.
 * - [open] throws [IOException] only when no response was obtained at all.
 * - The returned stream must be closed by the caller.
 */
interface HubTransport {
    /**
     * @param rangeFrom first byte to request, or null for a full body. Set for
     *   resume; the server may answer 200 (range ignored) and the caller must
     *   handle that by restarting rather than appending.
     */
    @Throws(IOException::class)
    fun open(request: HubRequest): HubResponse
}

/**
 * A request. Header names are case-insensitive by convention, so a fake only
 * has to match the case it is given.
 *
 * [authToken] is a field rather than a pre-baked header so that the one place
 * a token is attached is a single line, and so a `toString` of this object can
 * be made safe (it holds no token, and the URL is built from validated
 * [HubRepoId] and [HubFilePath] values that cannot carry a query string).
 */
data class HubRequest(
    val url: String,
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
    val rangeFrom: Long? = null,
    /**
     * Last byte of a CLOSED range, or null for an open-ended one.
     *
     * WHY this exists alongside [rangeFrom]: resume needs `bytes=N-` (to the
     * end of the entity), while the pre-download header probe needs
     * `bytes=0-4194303` (a bounded prefix). An open-ended `bytes=0-` is a
     * request for the entire 668 MB model, which is the one thing the probe
     * exists to avoid. Defaulting to null keeps every existing construction site
     * byte-for-byte identical.
     */
    val rangeTo: Long? = null,
    /**
     * Sent as `Authorization: Bearer *** Never logged, never placed in a
     * URL, never persisted outside the secure store.
     */
    val authToken: String? = null,
) {
    /**
     * The `Range` header value this request implies, or null for a full body.
     *
     * WHY a function rather than an inline `when` at the transport: the open and
     * closed spellings must agree with each other, and a second construction of
     * the same header in the transport is a second place to get it wrong.
     */
    fun rangeHeader(): String? {
        val from = rangeFrom ?: return null
        val to = rangeTo
        return if (to != null) "bytes=$from-$to" else "bytes=$from-"
    }
    /**
     * WHY this is hand-written: as a `data class` the compiler generates a
     * `toString` that prints every property, including this one. A token in a
     * log line is a credential in a log file, and a crash reporter or a
     * `Log.d(request)` away from being leaked. The generated version is
     * replaced so the value is structurally unreachable, not merely unused.
     */
    override fun toString(): String =
        "HubRequest(url=$url, method=$method, headers=${headers.keys}, " +
            "rangeFrom=$rangeFrom, authToken=${if (authToken == null) "none" else "***"})"
}

/**
 * A response. [body] is null for a bodyless status, which is what a HEAD or a
 * 416 comes back as.
 */
class HubResponse(
    val status: Int,
    val headers: Map<String, String>,
    val body: InputStream?,
    /** Total size of the entity, from `Content-Length` or `Content-Range`. -1 if unknown. */
    val contentLength: Long,
) {
    fun header(name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    /** True when the server honoured a Range request and is sending 206. */
    val isPartial: Boolean get() = status == 206

    /**
     * The first byte offset of the returned body within the whole entity.
     *
     * Parsed from `Content-Range: bytes start-end/total`, which is the only
     * correct source: the response status alone does not say *where* the body
     * starts, and appending a 200 response to a partial file is how you produce
     * a file that is the right length and completely wrong.
     *
     * Returns null when there is no `Content-Range`, which for a 200 means the
     * server ignored the range and is sending the whole entity from byte 0.
     */
    fun contentRangeStart(): Long? {
        val header = header("Content-Range") ?: return null
        // "bytes 100-199/1000" or "bytes 0-99/*". The unit is required by the
        // spec; a header without it is not a byte range and must not be read
        // as one.
        val trimmedHeader = header.trim()
        if (!trimmedHeader.startsWith("bytes", ignoreCase = true)) return null
        val afterSpace = trimmedHeader.substringAfter(' ').trim()
        if (afterSpace.isEmpty()) return null
        val rangePart = afterSpace.substringBefore('/').trim()
        val start = rangePart.substringBefore('-').trim().toLongOrNull() ?: return null
        return start
    }

    /** The total entity size from `Content-Range`, or -1. */
    fun contentRangeTotal(): Long {
        val header = header("Content-Range") ?: return -1L
        val total = header.substringAfter('/', "").trim()
        return total.toLongOrNull() ?: -1L
    }
}
