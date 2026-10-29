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
 * Fetches a raw SKILL.md for "Add skill from URL".
 *
 * Same network rules as web.fetch, because it is the same code: http and https
 * only, private and loopback addresses refused, every redirect hop re-checked,
 * no automatic redirects. The body is capped at [MAX_BYTES]. A skill cut off
 * part way is not saved, so a truncated body is an error rather than a
 * partial file.
 *
 * Blocking. Call it on Dispatchers.IO.
 */
object SkillDownloader {

    const val MAX_BYTES: Int = 64 * 1024

    sealed interface Result {
        data class Ok(val text: String) : Result
        data class Failed(val reason: String) : Result
    }

    fun download(url: String): Result {
        var current = url
        var redirects = 0
        val opener = HttpUrlConnectionOpener()
        while (true) {
            current = when (val verdict = WebUrls.validate(current)) {
                is UrlVerdict.Rejected -> return Result.Failed(verdict.reason)
                is UrlVerdict.Accepted -> verdict.url
            }
            val response = try {
                opener.open(current, CancellationSignal.None)
            } catch (e: IOException) {
                return Result.Failed("Could not reach ${hostOf(current)}: ${e.message ?: "network error"}")
            }
            try {
                if (isRedirectStatus(response.status)) {
                    if (redirects >= MAX_REDIRECTS) return Result.Failed("Too many redirects.")
                    when (val hop = WebUrls.resolveRedirect(current, response.location, redirects)) {
                        is RedirectVerdict.Rejected -> return Result.Failed(hop.reason)
                        is RedirectVerdict.Follow -> {
                            current = hop.url
                            redirects = hop.redirects
                        }
                    }
                    continue
                }
                if (response.status !in 200..299) {
                    return Result.Failed("The server answered HTTP ${response.status}. Check the link.")
                }
                val body = BodyReader.readCapped(response.body, MAX_BYTES, CancellationSignal.None)
                if (body.truncated) return Result.Failed("The file is larger than 64 KB. A SKILL.md is much smaller.")
                if (body.interrupted) return Result.Failed("The download stopped part way. Try again.")
                return Result.Ok(String(body.bytes, Charsets.UTF_8))
            } finally {
                response.close()
            }
        }
    }

    private fun hostOf(url: String): String = runCatching { java.net.URI(url).host }.getOrNull() ?: url
}
