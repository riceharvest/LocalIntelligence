package dev.localintelligence.android.hub

import dev.localintelligence.core.hub.HubRequest
import dev.localintelligence.core.hub.HubResponse
import dev.localintelligence.core.hub.HubTransport
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * The production [HubTransport]: `HttpURLConnection` and nothing else.
 *
 * ## Why `HttpURLConnection` and not OkHttp
 *
 * The project's hard constraint is zero new Gradle dependencies — kotlin
 * stdlib, kotlinx.serialization and the JDK. `HttpURLConnection` is in the JDK,
 * it is present on every Android API level this app supports (26+), and a
 * resumable download needs exactly two things from it: a `Range` header and a
 * stream. OkHttp would add ~800 KB and a dependency to do the same.
 *
 * ## Why the seam still exists
 *
 * This class is the one part of the hub with no offline test coverage: its
 * behaviour is the network's behaviour. Every decision *around* it — whether
 * to resume, whether a 206 is trustworthy, when to give up, what the user is
 * told — lives behind [HubTransport] and is covered by tests that never open a
 * socket. See the PR body for the explicit statement about what is unverified.
 *
 * ## Redirects
 *
 * HF's `resolve/` endpoint 302s to a CDN. `HttpURLConnection` follows
 * http->http and https->https redirects automatically but refuses a
 * cross-protocol one. The loop below therefore follows a redirect manually,
 * with a hop cap, and — importantly — **strips the Authorization header on the
 * hop to a different host**. Forwarding a bearer token to a CDN origin would
 * leak a credential to a host the user never authenticated with, and the
 * download works fine without it: the redirect URL is already signed.
 */
class UrlConnectionTransport(
    private val connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
) : HubTransport {

    override fun open(request: HubRequest): HubResponse {
        var url = URL(request.url)
        var auth = request.authToken
        var hops = 0
        while (true) {
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = request.method
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                // Manual, so the header can be dropped on a host change.
                instanceFollowRedirects = false
                request.headers.forEach { (k, v) -> setRequestProperty(k, v) }
                auth?.let { setRequestProperty("Authorization", "Bearer $it") }
                request.rangeFrom?.let { setRequestProperty("Range", "bytes=$it-") }
            }
            val status = try {
                connection.responseCode
            } catch (e: IOException) {
                connection.disconnect()
                throw e
            }
            if (status in REDIRECT_STATUSES && hops < MAX_REDIRECTS) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                if (location.isNullOrBlank()) break
                url = URL(url, location)
                // Drop the credential when the redirect leaves the origin host.
                if (url.host != request.url.toHttpUrlHost()) auth = null
                hops++
                continue
            }
            val headers = connection.headerFields
                .filterKeys { it != null }
                .mapValues { (_, values) -> values.firstOrNull().orEmpty() }
            val length = connection.contentLengthLong
            val body: InputStream? = when {
                status in REDIRECT_STATUSES -> null
                status >= 400 -> connection.errorStream
                else -> connection.inputStream
            }
            return HubResponse(
                status = status,
                headers = headers,
                body = body,
                contentLength = if (length > 0) length else -1L,
            )
        }
        // Fell out of the redirect loop: treat as a bad answer rather than
        // looping forever.
        throw IOException("too many redirects for the model download")
    }

    private fun String.toHttpUrlHost(): String = try {
        URL(this).host
    } catch (_: Exception) {
        ""
    }

    companion object {
        /** WHY 30 s: a TLS handshake on a bad mobile link can take 10 s. */
        const val DEFAULT_CONNECT_TIMEOUT_MS = 30_000

        /**
         * WHY 60 s and not infinite: a stalled socket on a phone that has
         * quietly lost signal otherwise hangs the download thread forever and
         * the user sees a frozen bar. 60 s of no data is unambiguously dead.
         */
        const val DEFAULT_READ_TIMEOUT_MS = 60_000

        private val REDIRECT_STATUSES = setOf(301, 302, 303, 307, 308)
        private const val MAX_REDIRECTS = 5
    }
}
