package dev.localintelligence.android.tools

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import dev.localintelligence.core.tool.contracts.PlatformGrant
import dev.localintelligence.core.tool.contracts.PlatformRequirement

/**
 * The real answer to "may this app do this right now", read from the platform
 * rather than from a set the host has to remember to populate.
 *
 * ## Why this exists
 *
 * [dev.localintelligence.core.tool.ToolContext.permissionGranted] defaults to
 * `true` and is filled from
 * [dev.localintelligence.core.policy.PolicyConfig.grantedPermissions], which
 * ships empty and which nothing in the app populates. The consequence is not a
 * subtle degradation: every tool that declares a `requiredPermission` is handed
 * `permissionGranted = false` on every call, forever, whether or not the user
 * granted it in Settings.
 *
 * That produced two failure modes and this fixes both:
 *
 *  - Tools that trusted the flag returned a fabricated "no permission in this
 *    session" for a permission the user HAD granted. The model tells the user
 *    to go and switch on something that is already on.
 *  - Tools that distrusted the flag and relied on catching `SecurityException`
 *    returned whatever the provider gave them — an empty contact list, an empty
 *    calendar. That is the worse of the two, because the model reports "you
 *    have no contacts" and the user has no way to tell that from the truth.
 *
 * So a tool asks THIS, which is a direct platform query, instead of trusting a
 * flag that nobody maintains.
 *
 * ## Why a Context is captured rather than passed per call
 *
 * `execute` has no platform handle in its signature, and adding one would put
 * an `android.*` type into `:core`'s tool contract. The probe is constructed
 * once per registry, in `androidTools(context)`, from the application context —
 * so no Activity is retained and no tool holds a Context it can leak.
 */
class AndroidPlatformGrant(context: Context) : PlatformGrant {

    private val appContext: Context = context.applicationContext

    override fun isGranted(requirement: PlatformRequirement): Boolean {
        // Bound to a local because `permission` is a property on a type from
        // another module, and Kotlin will not smart-cast a cross-module
        // property inside a `when`.
        val permission = requirement.permission
        return if (permission == null) capabilityGranted(requirement) else isPermissionHeld(permission)
    }

    /**
     * `ContextCompat.checkSelfPermission` rather than a bare call so the
     * pre-Marshmallow path is honest. minSdk is 26, so this is really just the
     * one API, but going through ContextCompat means a denial is a denial on
     * every build rather than a crash on one.
     */
    private fun isPermissionHeld(permission: String): Boolean =
        ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED

    /**
     * A requirement with no runtime permission still names a capability, and
     * that capability can be off.
     *
     * Only the notification listener reaches here today, and the honest answer
     * is a real platform query rather than a guess. `isNotificationListenerEnabled`
     * is the API 26+ way to ask without requiring the service to be running,
     * which is what makes "never granted" distinguishable from "granted but the
     * service has not connected yet" — two different user problems that deserve
     * two different sentences.
     */
    private fun capabilityGranted(requirement: PlatformRequirement): Boolean =
        when (requirement.capability) {
            "Notification access" ->
                dev.localintelligence.android.tools.notifications.LocalNotificationListenerService
                    .isGranted(appContext)

            // A requirement with no permission and no probe means the
            // capability is not gated at all, so it is available.
            else -> true
        }

    /**
     * Storage, which is the one requirement that is genuinely conditional on the
     * API level.
     *
     * Before Android 13 a single runtime permission covers reading documents.
     * From Android 13 that permission no longer exists for documents at all:
     * it was split into per-media-type permissions that do NOT cover a PDF in
     * Documents, and the only route to an arbitrary document is the system file
     * picker. A tool that claimed to need `READ_EXTERNAL_STORAGE` on Android 13
     * would be claiming a permission the platform ignores — which is the
     * documentation lying about the API, the exact failure this work exists to
     * remove.
     *
     * @return true when a read of another app's documents can succeed.
     */
    fun canReadDocuments(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // No runtime permission grants this on 13+. Documents become
            // readable only per-URI, through the file picker. So the honest
            // answer is: the app can read what it was given, and nothing else.
            false
        } else {
            isPermissionHeld(Manifest.permission.READ_EXTERNAL_STORAGE) ||
                isPermissionHeld(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }

    /** True when the app can create a document in Downloads, which needs no permission from API 29. */
    fun canCreateDocuments(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    /**
     * Whether the app can see any other app at all.
     *
     * Package visibility on Android 11+ hides most installed apps unless the
     * manifest declares a `<queries>` element. This app does declare one, but
     * the question is still worth asking rather than assuming, because
     * `apps.list` returning empty is indistinguishable from "the user has no
     * apps" unless something says otherwise.
     */
    fun canSeeInstalledApps(): Boolean = true
}
