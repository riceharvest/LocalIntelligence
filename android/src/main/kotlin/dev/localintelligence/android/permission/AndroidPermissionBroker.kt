package dev.localintelligence.android.permission

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import dev.localintelligence.core.permission.Permission
import dev.localintelligence.core.permission.PermissionBroker
import dev.localintelligence.core.permission.PermissionState

/**
 * Reads real permission state from the Android framework.
 *
 * WHY the interesting part is not the `checkSelfPermission` call: that one is
 * obvious. The hard part is that a permission being *not granted* is three
 * different situations, and only one of them is recoverable by asking again.
 * This class is the single place that turns the platform's two booleans into
 * the four-state model, so no tool has to guess and no tool can disagree.
 *
 * The mapping, stated once:
 * ```
 * checkSelfPermission == GRANTED                     -> GRANTED
 * not declared in the manifest                        -> NOT_APPLICABLE
 * not granted && shouldShowRationale                  -> DENIED
 * not granted && !rationale && we already prompted   -> DENIED_PERMANENTLY
 * not granted && !rationale && never prompted        -> DENIED
 * ```
 * The last two lines share every platform signal; the only thing separating
 * them is whether this session already showed a prompt, which is why
 * [askPolicy] is a constructor parameter and not a guess.
 *
 * No caching: a user can revoke a permission from system settings while the
 * app is running, and a stale answer here is what makes an agent insist a tool
 * is unavailable when it is not. The reads are a binder call on a handful of
 * permissions per step, which is not worth a cache with an invalidation bug.
 */
class AndroidPermissionBroker(
    private val context: Context,
    private val askPolicy: dev.localintelligence.core.permission.PermissionAskPolicy,
    /**
     * The activity used for `shouldShowRequestPermissionRationale`, which only
     * exists on an Activity. Null when the agent runs without a foreground
     * activity; the broker degrades to the safe reading in that case.
     */
    private val activity: Activity? = null,
) : PermissionBroker {

    override fun stateOf(permission: Permission): PermissionState {
        // Belt and braces: the contract says an unknown permission is
        // NOT_APPLICABLE, and a throw here would abort an agent step over a
        // string the model produced.
        val granted = runCatching {
            context.checkSelfPermission(permission.id) == PackageManager.PERMISSION_GRANTED
        }.getOrElse { return PermissionState.NOT_APPLICABLE }

        return AndroidPermissionStateResolver.resolve(
            granted = granted,
            declaredInManifest = isDeclaredInManifest(permission.id),
            shouldShowRationale = showRationale(permission.id),
            promptsSoFar = askPolicy.asksFor(permission),
        )
    }

    /**
     * Whether the app's own manifest declares [permissionId].
     *
     * WHY this matters: a permission the app never declared can never be
     * granted, so prompting for it does nothing and the correct answer is
     * NOT_APPLICABLE. It also covers the wave-2 tools that document a
     * permission in prose ("needs a SAF grant") — those are not runtime
     * permissions and must not be reported as a denial the user can fix.
     */
    private fun isDeclaredInManifest(permissionId: String): Boolean = runCatching {
        val info = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS,
        )
        val requested = info.requestedPermissions ?: return false
        requested.contains(permissionId)
    }.getOrElse { false }

    override fun statesOf(permissions: List<Permission>): Map<Permission, PermissionState> {
        if (permissions.isEmpty()) return emptyMap()
        // One PackageManager call for the whole batch: the declared set is read
        // once and reused, rather than once per permission.
        val declared: Set<String>? = runCatching {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_PERMISSIONS,
            ).requestedPermissions?.toSet()
        }.getOrNull()

        return permissions.associateWith { permission ->
            val granted = runCatching {
                context.checkSelfPermission(permission.id) == PackageManager.PERMISSION_GRANTED
            }.getOrDefault(false)

            AndroidPermissionStateResolver.resolve(
                granted = granted,
                // A failed read means we cannot confirm the declaration, so we
                // must not claim it was declared.
                declaredInManifest = declared != null && permission.id in declared,
                shouldShowRationale = showRationale(permission.id),
                promptsSoFar = askPolicy.asksFor(permission),
            )
        }
    }

    /**
     * Whether the system would still show a prompt for [permissionId].
     *
     * Needs an Activity. Without one the answer is false, which the resolver
     * reads as "no prompt available" — the safe direction, because it means the
     * agent will not promise the user a dialog that cannot be shown.
     */
    private fun showRationale(permissionId: String): Boolean {
        val act = activity ?: return false
        return runCatching {
            ActivityCompat.shouldShowRequestPermissionRationale(act, permissionId)
        }.getOrDefault(false)
    }
}
