package dev.localintelligence.android.permission

import dev.localintelligence.core.permission.PermissionAskPolicy
import dev.localintelligence.core.permission.PermissionState

/**
 * The platform-signals-to-state mapping, with no Android types in sight.
 *
 * WHY it is separated from [AndroidPermissionBroker]: the decision is the part
 * worth testing, and testing it through a real `Context` needs an emulator and
 * a device. Taking the three platform answers as plain parameters makes the
 * entire four-state mapping runnable as a JVM unit test, with the framework
 * calls left as a thin shell that is correct by inspection.
 *
 * This is the same split docs/architecture.md §2 asks for, one level down:
 * :core owns the decision, :android owns the read.
 */
object AndroidPermissionStateResolver {

    /**
     * Map the platform's answers onto [PermissionState].
     *
     * @param granted result of `checkSelfPermission`.
     * @param declaredInManifest whether the app asked for this permission at
     *   all. A permission the app never declared can never be granted, so
     *   prompting for it is pointless and the honest answer is NOT_APPLICABLE.
     * @param shouldShowRationale `shouldShowRequestPermissionRationale`. True
     *   means the system will still show a prompt.
     * @param promptsSoFar how many times this session has already prompted.
     *   This is the one fact the platform cannot supply, and it is what
     *   separates a soft denial from a permanent one.
     */
    fun resolve(
        granted: Boolean,
        declaredInManifest: Boolean,
        shouldShowRationale: Boolean,
        promptsSoFar: Int,
    ): PermissionState = when {
        granted -> PermissionState.GRANTED

        !declaredInManifest -> PermissionState.NOT_APPLICABLE

        // A rationale means the user denied once and the system will still
        // show a dialog: asking again works.
        shouldShowRationale -> PermissionState.DENIED

        // No rationale but we already prompted. On Android 11+ the system stops
        // showing the dialog after two denials, and this is the only way to
        // know. Reporting DENIED here is the retry-loop bug in its purest form.
        promptsSoFar > 0 -> PermissionState.DENIED_PERMANENTLY

        // No rationale and no prompt yet: the first ask will still be shown.
        else -> PermissionState.DENIED
    }

    /** Convenience overload for a caller that already holds the policy. */
    fun resolve(
        granted: Boolean,
        declaredInManifest: Boolean,
        shouldShowRationale: Boolean,
        policy: PermissionAskPolicy,
        permission: dev.localintelligence.core.permission.Permission,
    ): PermissionState = resolve(
        granted = granted,
        declaredInManifest = declaredInManifest,
        shouldShowRationale = shouldShowRationale,
        promptsSoFar = policy.asksFor(permission),
    )
}
