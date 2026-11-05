package dev.localintelligence.app.access

import android.content.Intent
import androidx.annotation.StringRes
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import dev.localintelligence.android.tools.access.SpecialAccess
import dev.localintelligence.android.tools.access.ToolAccess
import dev.localintelligence.app.R
import dev.localintelligence.core.tool.contracts.ToolPermissions
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The one dialog a run may show to ask for access, and the bridge that answers the tool waiting on it.
 *
 * ## Why one sentence, then the system page
 *
 * The person is told why in one plain sentence before anything system-owned appears. "Continue" then opens the
 * system permission dialog, or the exact Settings page for a special access. "Not now" answers the tool with a
 * refusal, and the tool takes its fallback. Nothing is asked behind the person's back: a run that starts while no
 * activity is resumed never reaches this class (see [ToolAccess.host]).
 *
 * ## Why the activity owns the launchers
 *
 * `registerForActivityResult` must run while the activity is being built, so [MainActivity] registers the two
 * launchers and hands them in as lambdas. The callbacks come back through [runtimeAnswered] and [specialReturned].
 */
class AccessPrompts : ToolAccess.Host {

    internal enum class Kind { RUNTIME, SPECIAL }

    /** What the dialog is asking. [key] is a permission name or a [SpecialAccess] key. */
    class Request internal constructor(
        internal val kind: Kind,
        internal val key: String,
        internal val intent: Intent?,
        @StringRes val sentence: Int,
    ) {
        /** True for a Settings page, so the button says Open settings. */
        val isSpecial: Boolean get() = kind == Kind.SPECIAL
    }

    /** The request on screen, or null. */
    internal val current = MutableStateFlow<Request?>(null)

    private var waiting: CompletableDeferred<ToolAccess.Answer>? = null

    /** Set once by the activity, before the first run can ask. */
    var launchRuntime: ((String) -> Unit)? = null
    var launchSettings: ((Intent) -> Unit)? = null

    /** True once Continue was pressed and the system page or dialog is up. Leaving the app then does not refuse. */
    private var handedOver = false

    override suspend fun askRuntime(permission: String): ToolAccess.Answer {
        val sentence = sentenceFor(permission) ?: return ToolAccess.Answer.NOT_ASKED
        return present(Request(Kind.RUNTIME, permission, null, sentence))
    }

    override suspend fun openSpecialAccess(key: String, intent: Intent): ToolAccess.Answer {
        val sentence = sentenceFor(key) ?: return ToolAccess.Answer.NOT_ASKED
        return present(Request(Kind.SPECIAL, key, intent, sentence))
    }

    private suspend fun present(request: Request): ToolAccess.Answer {
        // One question at a time: a second run asking while this one waits is told not now, not stacked.
        if (waiting != null) return ToolAccess.Answer.NOT_ASKED
        val answer = CompletableDeferred<ToolAccess.Answer>()
        waiting = answer
        handedOver = false
        current.value = request
        return try {
            answer.await()
        } finally {
            waiting = null
            handedOver = false
            current.value = null
        }
    }

    /** Continue: hand over to the system dialog or Settings page. */
    fun continueTapped() {
        val request = current.value ?: return
        val launched = try {
            when (request.kind) {
                Kind.RUNTIME -> launchRuntime?.let { it(request.key); true } ?: false
                Kind.SPECIAL -> request.intent != null && launchSettings?.let { it(request.intent); true } == true
            }
        } catch (e: Exception) {
            false
        }
        if (launched) handedOver = true else finish(ToolAccess.Answer.REFUSED)
    }

    /** Not now, or the dialog was dismissed. */
    fun notNowTapped() = finish(ToolAccess.Answer.REFUSED)

    /** The system permission dialog answered. */
    fun runtimeAnswered(granted: Boolean) = finish(if (granted) ToolAccess.Answer.GRANTED else ToolAccess.Answer.REFUSED)

    /** The person came back from the Settings page. The tool re-checks the grant itself. */
    fun specialReturned() = finish(ToolAccess.Answer.GRANTED)

    /**
     * The activity went to the background. A sentence nobody answered is not a refusal, so it is not remembered
     * as asked. A system page we handed over is still waited for.
     */
    fun paused() {
        if (!handedOver) finish(ToolAccess.Answer.NOT_ASKED)
    }

    private fun finish(answer: ToolAccess.Answer) {
        val pending = waiting ?: return
        waiting = null
        current.value = null
        handedOver = false
        pending.complete(answer)
    }

    /** The sentence the person reads for [key], or null when there is none, so an unknown access is never asked. */
    @StringRes
    private fun sentenceFor(key: String): Int? = when (key) {
        ToolPermissions.READ_CONTACTS -> R.string.access_contacts
        ToolPermissions.READ_CALENDAR -> R.string.access_calendar_read
        ToolPermissions.WRITE_CALENDAR -> R.string.access_calendar_write
        ToolPermissions.ACCESS_COARSE_LOCATION -> R.string.access_location
        ToolPermissions.READ_SMS -> R.string.access_sms_read
        ToolPermissions.SEND_SMS -> R.string.access_sms_send
        ToolPermissions.READ_CALL_LOG -> R.string.access_call_log
        ToolPermissions.CALL_PHONE -> R.string.access_call
        SpecialAccess.DND -> R.string.access_dnd
        SpecialAccess.WRITE_SETTINGS -> R.string.access_write_settings
        SpecialAccess.USAGE -> R.string.access_usage
        SpecialAccess.NOTIFICATION_LISTENER -> R.string.access_notifications
        SpecialAccess.EXACT_ALARM -> R.string.access_exact_alarm
        else -> null
    }
}

/** Shows the pending access request, if any. Place once, near the root of the activity's content. */
@Composable
fun AccessPromptHost(prompts: AccessPrompts) {
    val request by prompts.current.collectAsState()
    val pending = request ?: return
    AlertDialog(
        onDismissRequest = { prompts.notNowTapped() },
        text = { Text(stringResource(pending.sentence)) },
        confirmButton = {
            TextButton(onClick = { prompts.continueTapped() }) {
                Text(
                    stringResource(
                        if (pending.isSpecial) R.string.access_open_settings else R.string.access_continue,
                    ),
                )
            }
        },
        dismissButton = {
            TextButton(onClick = { prompts.notNowTapped() }) { Text(stringResource(R.string.access_not_now)) }
        },
    )
}
