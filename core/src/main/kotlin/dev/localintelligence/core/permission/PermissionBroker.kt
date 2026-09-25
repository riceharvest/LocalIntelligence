package dev.localintelligence.core.permission

/**
 * A platform permission, identified by its canonical string.
 *
 * WHY a wrapper type instead of a raw [String]: permission ids are compared,
 * stored in maps, and rendered into model-visible text. Wrapping them means a
 * blank or runaway id is rejected at construction instead of producing an
 * observation that tells the model about an empty permission.
 *
 * The id is the platform's own string ("android.permission.READ_CALENDAR").
 * Keeping the platform vocabulary in the data — rather than an enum of
 * permissions — is what lets one core policy work across phones, and keeps
 * :core free of any knowledge of which permissions exist.
 */
data class Permission(val id: String) {

    init {
        require(id.isNotBlank()) { "permission id must not be blank" }
        // Bounded so a corrupt or generated id cannot grow an observation or a
        // map key without limit on a device that is short of RAM.
        require(id.length <= MAX_ID_CHARS) {
            "permission id longer than $MAX_ID_CHARS chars: ${id.length}"
        }
    }

    /**
     * The bare name without the package prefix ("READ_CALENDAR").
     *
     * Used for grouping and for stable display. Never the full observation —
     * the model needs a human label, which is a property of the tool, not of
     * the platform.
     */
    val shortName: String
        get() = id.substringAfterLast('.').ifEmpty { id }

    companion object {
        /** Upper bound on an id's length. See the init block. */
        const val MAX_ID_CHARS = 128
    }
}

/**
 * Reads the real permission state of the device.
 *
 * WHY an interface owned by :core: the whole point of the module split is that
 * the decision ("can this tool run, and what do we tell the model?") is
 * testable on a JVM with no device, while the read that feeds it needs
 * `PackageManager`. :core owns the question, :android answers it.
 *
 * Implementations must not throw. An unknown or malformed permission is a
 * [PermissionState.NOT_APPLICABLE] answer, not an exception: the agent asks
 * about permissions the code does not know about, and a crash over one of them
 * would be worse than the denial it is reporting.
 */
interface PermissionBroker {

    /**
     * The current state of [permission], or [PermissionState.NOT_APPLICABLE] if
     * this app/device has no such permission. Never throws.
     */
    fun stateOf(permission: Permission): PermissionState

    /**
     * Batch read for tools that need several permissions.
     *
     * WHY this exists: a tool guarded by three permissions should be one call
     * into the platform, and the caller's decision logic reads better as "here
     * are all the answers" than as a fold. Defaults to a per-item read, so a
     * minimal implementation stays minimal.
     */
    fun statesOf(permissions: List<Permission>): Map<Permission, PermissionState> =
        permissions.associateWith { stateOf(it) }
}
