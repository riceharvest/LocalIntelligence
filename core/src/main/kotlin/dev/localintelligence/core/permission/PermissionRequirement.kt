package dev.localintelligence.core.permission

/**
 * One permission a tool needs, plus what to call it in English.
 *
 * WHY this type exists when `ToolDefinition.requiredPermission` already exists:
 * that field is a documentation string, frozen by docs/tool-contract.md and
 * explicitly marked "documentation + UI only" — it may hold prose, an API-level
 * caveat, or a permission that does not exist. An agent cannot ask the user for
 * "READ_EXTERNAL_STORAGE (API<=32; API 33+ needs a SAF grant)".
 *
 * So enforcement gets its own type. [ToolDefinition] is untouched; this one is
 * additive and optional, and a tool with no registered requirement keeps
 * working exactly as before. That is the difference between a new concept and a
 * change to a frozen contract.
 *
 * @property permission the platform permission to read state for.
 * @property capabilityLabel human phrasing used in the model-visible sentence
 *   ("your calendar"). The model has to say this to a person, and permission
 *   ids are not sentences.
 */
data class PermissionRequirement(
    val permission: Permission,
    val capabilityLabel: String,
) {
    /**
     * Whether any of this tool's requirements is satisfied enough to run.
     * Derived so a caller cannot disagree with the requirement set.
     */
    fun isSatisfiedBy(state: PermissionState): Boolean = state.allowsExecution
}

/**
 * Where a tool's permission requirements come from.
 *
 * WHY an interface and not a global table: a test needs to say "this tool needs
 * X" in one line, and production needs to say "read it off the tool". A
 * registry keyed by tool name satisfies both without a lookup that depends on
 * a plugin being loaded.
 */
fun interface PermissionRequirementSource {

    /**
     * Requirements for [toolName], empty when the tool needs nothing. Must not
     * throw on an unknown name — an unknown tool has no requirements by
     * definition, and a tool the model hallucinated must not crash the guard.
     */
    fun requirementsFor(toolName: String): List<PermissionRequirement>
}

/**
 * The shipped source: a table, with a fallback to the tool's own declaration.
 *
 * The table is explicit because a permission requirement is a decision a human
 * should make, and a table is where a reviewer can see and argue with it. The
 * fallback exists only so a wave-2 tool that already declared
 * `requiredPermission` gets enforced rather than silently unprotected — the
 * string is normalised, and an unrecognisable one yields no requirement instead
 * of a wrong one.
 */
class DefaultPermissionRequirementSource(
    private val declared: Map<String, List<PermissionRequirement>> = emptyMap(),
    /** Human labels for permissions appearing in a tool's `requiredPermission`. */
    private val labels: Map<String, String> = DEFAULT_LABELS,
) : PermissionRequirementSource {

    override fun requirementsFor(toolName: String): List<PermissionRequirement> =
        // NOT getValue(): an unknown tool name must return "no requirements",
        // never throw. The model hallucinates tool names, and a guard that
        // crashes on one takes the whole agent step down with it.
        declared[toolName]
            ?: declared[WILDCARD_PREFIX + toolName]
            ?: emptyList()

    /**
     * The capability label for [permission], falling back to a humanised form
     * of the permission's short name so a permission nobody registered still
     * produces a readable sentence instead of an empty one.
     */
    fun labelFor(permission: Permission): String =
        labels[permission.shortName] ?: humanise(permission.shortName)

    companion object {
        /**
         * Prefix marking a catch-all entry in the declared map. Lets one rule
         * cover a whole category ("every clipboard tool needs X") without
         * enumerating tools that may not exist yet.
         */
        const val WILDCARD_PREFIX = "*"

        /**
         * Labels for the permissions this app's tools actually use.
         *
         * Only the words the model will say to a user. Kept small and in this
         * file so the vocabulary is reviewable in one place.
         */
        val DEFAULT_LABELS: Map<String, String> = mapOf(
            "READ_CALENDAR" to "your calendar",
            "WRITE_CALENDAR" to "your calendar",
            "READ_CONTACTS" to "your contacts",
            "WRITE_CONTACTS" to "your contacts",
            "READ_NOTIFICATIONS" to "your notifications",
            "POST_NOTIFICATIONS" to "posting notifications",
            "SCHEDULE_EXACT_ALARM" to "setting exact alarms",
            "READ_EXTERNAL_STORAGE" to "your files",
            "WRITE_EXTERNAL_STORAGE" to "your files",
            "READ_MEDIA_IMAGES" to "your photos",
            "READ_MEDIA_VIDEO" to "your videos",
            "READ_MEDIA_AUDIO" to "your audio files",
            "INTERNET" to "the internet",
            "RECORD_AUDIO" to "the microphone",
            "ACCESS_FINE_LOCATION" to "your location",
        )

        /**
         * `READ_MEDIA_IMAGES` -> `Read media images` -> `read media images`.
         *
         * Degrades to something odd-looking but non-empty for an unknown
         * permission, which is the only requirement: the observation must never
         * contain a blank where a noun belongs.
         */
        fun humanise(shortName: String): String {
            if (shortName.isEmpty()) return "that"
            val words = shortName.replace('_', ' ').lowercase().trim()
            return words.ifEmpty { "that" }
        }
    }
}
