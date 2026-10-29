package dev.localintelligence.app

import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings

/**
 * Opens the place where the user makes LocalIntelligence the digital assistant.
 *
 * WHY THE USER CHOOSES AND THE APP ONLY ASKS: an app cannot make itself the
 * default assistant. On Android 10+ the role request shows the system's own
 * confirmation; if the role is not requestable here (or the platform is older),
 * the voice-input settings screen, where the user can pick it, is the fallback.
 */
fun openAssistantSettings(context: Context) {
    val request = assistantRoleRequest(context)
    try {
        context.startActivity(request ?: Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
    } catch (_: ActivityNotFoundException) {
        context.startActivity(Intent(Settings.ACTION_SETTINGS))
    }
}

/** The system role request when it can be made right now, else null. Android 10+ only. */
private fun assistantRoleRequest(context: Context): Intent? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
    val roles = context.getSystemService(RoleManager::class.java) ?: return null
    if (!roles.isRoleAvailable(RoleManager.ROLE_ASSISTANT)) return null
    if (roles.isRoleHeld(RoleManager.ROLE_ASSISTANT)) return null
    return roles.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT)
}
