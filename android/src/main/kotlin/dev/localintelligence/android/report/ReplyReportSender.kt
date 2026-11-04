package dev.localintelligence.android.report

import dev.localintelligence.android.tools.web.exchangeJson
import dev.localintelligence.core.tool.CancellationSignal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.URLEncoder

// ===========================================================================
// Sends one reply report to the developer's Google Form.
//
// WHY A FORM AND NOT A SERVER: the app has no backend for reports, and Play's AI
// content policy needs the report to leave from inside the app. A Google Form the
// owner owns takes a plain HTTPS POST with no account and no SDK, so the only
// thing sent is the one field the form asks for.
//
// WHY 15 s: a form post over a weak mobile link can take several seconds to
// complete the TLS handshake and the reply; the web tools' 8 s / 10 s would give up
// on a report that was going to arrive.
// ===========================================================================

class ReplyReportSender(
    /** The form's `formResponse` URL. Empty in a build that did not set it. */
    private val formUrl: String,
    /** The form field's id, `entry.<digits>`. Empty in a build that did not set it. */
    private val entry: String,
) {
    /** What happened to one report. */
    sealed interface Outcome {
        data object Sent : Outcome

        /** This build has no form configured, so nothing was sent. */
        data object NotSetUp : Outcome

        /** The form did not take it: no connection, a timeout, or a non-200 answer. */
        data object Failed : Outcome
    }

    /** Posts [text] as the form's one answer. Suspends on IO; never throws. */
    suspend fun send(text: String): Outcome = withContext(Dispatchers.IO) {
        if (formUrl.isBlank() || entry.isBlank() || !formUrl.startsWith("https://")) {
            return@withContext Outcome.NotSetUp
        }
        val body = "${encode(entry)}=${encode(text)}"
        try {
            val reply = exchangeJson(
                method = "POST",
                url = formUrl,
                headers = emptyMap(),
                body = body,
                userAgent = USER_AGENT,
                signal = CancellationSignal.None,
                contentType = "application/x-www-form-urlencoded",
                timeoutMs = TIMEOUT_MS to TIMEOUT_MS,
            )
            if (reply.status == 200) Outcome.Sent else Outcome.Failed
        } catch (_: IOException) {
            Outcome.Failed
        } catch (_: IllegalArgumentException) {
            // A malformed URL from the build config reads as a failed send, not a crash.
            Outcome.Failed
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private companion object {
        const val TIMEOUT_MS = 15_000
        const val USER_AGENT = "LocalIntelligence-report"
    }
}
