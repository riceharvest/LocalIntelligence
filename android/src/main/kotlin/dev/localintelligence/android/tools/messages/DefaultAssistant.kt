package dev.localintelligence.android.tools.messages

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import dev.localintelligence.android.tools.phone.startIntent
import dev.localintelligence.core.tool.ToolError
import dev.localintelligence.core.tool.ToolResult

/**
 * The default-assistant gate for texts and the call log.
 *
 * ## The rule, and why it is one function
 *
 * Google Play's policy for the SMS and call-log permissions (checked 2026-10-31,
 * support.google.com/googleplay/android-developer/answer/10208820) allows them only for
 * an app that is the default assistant handler, and says the app "must immediately stop
 * using the permission when they're no longer the default handler". So every tool that
 * reads or sends texts, or reads the call log, asks [isHeld] on EVERY call, before it
 * touches the permission. Nothing is cached, because a person can change the default at
 * any moment in Settings, and a cached "yes" would keep reading after they had stopped it.
 *
 * The same check drives the Permissions screen: the SMS and call-log dialogs are withheld
 * while the role is not held, so they are never shown before it is.
 *
 * ## Why this mirrors `openAssistantSettings` instead of calling it
 *
 * `openAssistantSettings` lives in `:app`, and `:android` cannot depend on `:app`. The
 * request below is the same intent with the same fallback, so the two stay in step by
 * reading the same platform API.
 */
object DefaultAssistant {

    /** True while Android names LocalIntelligence the default assistant. False below Android 10, which has no such role. */
    fun isHeld(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val roles = context.getSystemService(RoleManager::class.java) ?: return false
        return roles.isRoleHeld(RoleManager.ROLE_ASSISTANT)
    }

    /**
     * Null when the role is held, so the caller carries on. Otherwise the refusal to return
     * to the model, and the role request is opened so the person can make the change.
     *
     * @param what the action the person asked for, in the words the refusal uses, such as
     *   "Reading and sending texts".
     */
    fun refusalUnlessHeld(context: Context, what: String): ToolResult? {
        if (isHeld(context)) return null
        val opened = startIntent(context, requestIntent(context))
        val tail = if (opened) " I opened the screen to set it." else ""
        return ToolResult(
            success = false,
            observation = "$what needs LocalIntelligence as the phone's default assistant app.$tail " +
                "Nothing was read or sent.",
            error = ToolError.PermissionDenied("LocalIntelligence is not the default assistant app"),
        )
    }

    /** The system's own role request when the phone offers it, else the voice input settings. */
    private fun requestIntent(context: Context): Intent {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roles = context.getSystemService(RoleManager::class.java)
            if (roles != null && roles.isRoleAvailable(RoleManager.ROLE_ASSISTANT)) {
                return roles.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT)
            }
        }
        return Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)
    }
}
