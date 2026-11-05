package dev.localintelligence.android.tools.access

import android.content.Context
import android.content.Intent
import dev.localintelligence.core.tool.contracts.PermissionDenial
import dev.localintelligence.core.tool.contracts.PlatformRequirement
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * The keys of the special accesses, passed to [ToolAccess.openSpecialAccess]. The app keeps one sentence per key.
 */
object SpecialAccess {
    const val DND = "dnd"
    const val WRITE_SETTINGS = "write_settings"
    const val USAGE = "usage_access"
    const val NOTIFICATION_LISTENER = "notification_listener"
    const val EXACT_ALARM = "exact_alarm"
}

/**
 * Asks the person for access in the middle of a run, instead of telling the model to tell them to
 * go and switch it on. Simple mode only.
 *
 * ## Why this exists
 *
 * Measured on 11-05, the model answered "I couldn't retrieve your calendar events because the
 * Calendar permission isn't granted" and "To enable Do Not Disturb you need to grant the app".
 * The person then had to find the setting alone, and most did not. A tool that needs a normal
 * permission now asks at that moment with the system dialog, after one plain sentence; a special
 * access opens its exact Settings page and the run continues when the person comes back.
 *
 * ## Why the screen is a [Host] and not a Context
 *
 * A dialog needs a visible activity, and a background run (a scheduled task, a reply that arrives
 * while the app is closed) has none. So the app registers a host only while an activity is resumed.
 * With no host the answer is [Answer.NOT_ASKED] and the tool takes its fallback, which is the
 * right behaviour for a run nobody is watching. The sentences the person reads live in the app's
 * strings, because this module has no resources.
 *
 * ## Why "asked once" lives in memory
 *
 * A preferences file would be a second settings store. So a permission is asked at most once per
 * process. After a restart the sentence may appear once more, and the system dialog itself stops
 * showing after two refusals. The gap is named here on purpose.
 */
object ToolAccess {

    /** How the person answered, or why nobody could be asked. */
    enum class Answer { GRANTED, REFUSED, NOT_ASKED }

    /** The screen side. Implemented by the app while an activity is in the foreground. */
    interface Host {
        /** Shows the sentence for [permission], then the system dialog. */
        suspend fun askRuntime(permission: String): Answer

        /**
         * Shows the sentence for [key] and a button that opens [intent]. Resolves GRANTED once the person
         * is back from that page (the caller re-checks the grant), REFUSED for "not now".
         */
        suspend fun openSpecialAccess(key: String, intent: Intent): Answer
    }

    /** Null while no activity is resumed. Set and cleared by the app. */
    @Volatile
    var host: Host? = null

    /**
     * True in Simple mode. Wired by the app at startup; until then the Technical wording stays, so an
     * unwired build behaves as it did before this file existed.
     */
    @Volatile
    var simpleMode: () -> Boolean = { false }

    /** Permissions and special accesses already asked this process and answered (granted or not now). */
    private val asked = ConcurrentHashMap.newKeySet<String>()

    /**
     * Asks for a normal runtime [permission]. True when it is granted now. False when the person said
     * not now, was already asked, or nobody could be asked. The caller re-checks nothing: the answer
     * is the grant.
     */
    suspend fun askRuntime(permission: String): Boolean {
        if (!simpleMode() || permission in asked) return false
        val screen = host ?: return false
        val answer = withTimeoutOrNull(ASK_TIMEOUT_MS) { screen.askRuntime(permission) } ?: Answer.NOT_ASKED
        if (answer != Answer.NOT_ASKED) asked += permission
        return answer == Answer.GRANTED
    }

    /**
     * Opens the Settings [intent] for a special access named [key] (the [ToolPermissions] constant).
     * True when the person came back from that page, so the caller re-checks the grant. False for
     * "not now", a repeat, or no screen. The caller re-checks the grant either way.
     */
    suspend fun openSpecialAccess(key: String, intent: Intent): Boolean {
        if (!simpleMode() || key in asked) return false
        val screen = host ?: return false
        val answer = withTimeoutOrNull(ASK_TIMEOUT_MS) { screen.openSpecialAccess(key, intent) } ?: Answer.NOT_ASKED
        if (answer != Answer.NOT_ASKED) asked += key
        return answer == Answer.GRANTED
    }

    /**
     * The observation for a refused or missing access. Simple mode gets [simple], a plain sentence
     * that says what to do next. Technical mode keeps the standard denial text, unchanged.
     */
    fun observe(tool: String, requirement: PlatformRequirement, simple: String): String =
        if (simpleMode()) simple else PermissionDenial.observation(tool, requirement)

    /**
     * The application context, set once at startup so a tool without a Context can open a screen.
     * Application-scoped only, so it cannot leak an activity.
     */
    @Volatile
    var appContext: Context? = null

    /**
     * Opens [intent] for the person. False when no screen is attached (a background run cannot start an
     * activity), no app on the phone handles it, or no context is set.
     */
    fun start(intent: Intent): Boolean {
        if (host == null) return false
        val context = appContext ?: return false
        return try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (e: Exception) {
            false
        }
    }

    /** A dialog the person may not answer for a long while; past this the run takes the fallback. */
    private const val ASK_TIMEOUT_MS = 120_000L
}
