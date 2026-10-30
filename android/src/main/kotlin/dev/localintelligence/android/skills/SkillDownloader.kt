package dev.localintelligence.android.skills

import dev.localintelligence.android.tools.web.BodyReader
import dev.localintelligence.android.tools.web.HttpUrlConnectionOpener
import dev.localintelligence.android.tools.web.MAX_REDIRECTS
import dev.localintelligence.android.tools.web.RedirectVerdict
import dev.localintelligence.android.tools.web.UrlVerdict
import dev.localintelligence.android.tools.web.WebUrls
import dev.localintelligence.android.tools.web.isRedirectStatus
import dev.localintelligence.core.tool.CancellationSignal
import java.io.IOException

/**
 * Fetches a raw SKILL.md for "Add skill from URL", and a plugin .zip for "Add
 * script plugin from URL".
 *
 * Same network rules as web.fetch, because it is the same code: http and https
 * only, private and loopback addresses refused, every redirect hop re-checked,
 * no automatic redirects. The body is capped; a file cut off part way is not
 * returned, so a truncated body is an error rather than a partial file.
 *
 * Blocking. Call it on Dispatchers.IO.
 */
object SkillDownloader {

    const val MAX_BYTES: Int = 64 * 1024

    sealed interface Result {
        data class Ok(val text: String) : Result
        data class Failed(val reason: String) : Result
    }

    sealed interface Fetched {
        data class Ok(val bytes: ByteArray) : Fetched
        data class Failed(val reason: String) : Fetched
    }

    fun download(url: String): Result = when (val fetched = fetch(url, MAX_BYTES)) {
        is Fetched.Ok -> Result.Ok(String(fetched.bytes, Charsets.UTF_8))
        is Fetched.Failed -> Result.Failed(fetched.reason)
    }

    /** The raw body of [url], at most [maxBytes]. */
    fun fetch(url: String, maxBytes: Int): Fetched {
        var current = url
        var redirects = 0
        val opener = HttpUrlConnectionOpener()
        while (true) {
            current = when (val verdict = WebUrls.validate(current)) {
                is UrlVerdict.Rejected -> return Fetched.Failed(verdict.reason)
                is UrlVerdict.Accepted -> verdict.url
            }
            val response = try {
                opener.open(current, CancellationSignal.None)
            } catch (e: IOException) {
                return Fetched.Failed("Could not reach ${hostOf(current)}: ${e.message ?: "network error"}")
            }
            try {
                if (isRedirectStatus(response.status)) {
                    if (redirects >= MAX_REDIRECTS) return Fetched.Failed("Too many redirects.")
                    when (val hop = WebUrls.resolveRedirect(current, response.location, redirects)) {
                        is RedirectVerdict.Rejected -> return Fetched.Failed(hop.reason)
                        is RedirectVerdict.Follow -> {
                            current = hop.url
                            redirects = hop.redirects
                        }
                    }
                    continue
                }
                if (response.status !in 200..299) {
                    return Fetched.Failed("The server answered HTTP ${response.status}. Check the link.")
                }
                val body = BodyReader.readCapped(response.body, maxBytes, CancellationSignal.None)
                if (body.truncated) return Fetched.Failed("The file is larger than ${maxBytes / 1024} KB.")
                if (body.interrupted) return Fetched.Failed("The download stopped part way. Try again.")
                return Fetched.Ok(body.bytes)
            } finally {
                response.close()
            }
        }
    }

    private fun hostOf(url: String): String = runCatching { java.net.URI(url).host }.getOrNull() ?: url
}
