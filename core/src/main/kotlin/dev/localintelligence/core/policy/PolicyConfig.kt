package dev.localintelligence.core.policy

import dev.localintelligence.core.model.ToolArgs
import dev.localintelligence.core.tool.ToolDefinition

/**
 * A caller-supplied rule, evaluated after the built-in safety rules and before
 * the risk-tier fallback.
 *
 * WHY this is a narrow interface and not a general scripting hook: the whole
 * value of this package is that an auditor can read the decision table and know
 * what runs. A rule may only *tighten* or *replace* an outcome, and it can
 * never lift a [dev.localintelligence.core.tool.ToolRisk.PRIVILEGED] block —
 * that check runs before rules are consulted and is not overridable.
 */
fun interface PolicyRuleOverride {
    /**
     * Returns a [Decision], or null to abstain and let the next rule decide.
     * A returned decision is used as-is, so a rule that wants to block should
     * return [PolicyOutcome.BLOCK] rather than mutate someone else's decision.
     */
    fun evaluate(
        definition: ToolDefinition,
        args: ToolArgs,
        current: Decision,
    ): Decision?
}

/**
 * Immutable policy configuration. [default] is what ships.
 *
 * Every knob here fails toward denial when set to a permissive-looking value:
 * an empty [knownTargets] does not mean "trust everyone", it means "nothing is
 * known, so external sends to any target are held for confirmation". That is
 * the whole design rule — a missing piece of configuration is treated as
 * ignorance, and ignorance is not permission.
 */
data class PolicyConfig(
    /**
     * Path prefixes considered "inside the app's own storage". A destructive
     * call whose path escapes these is held for confirmation even if the tool
     * is otherwise safe.
     *
     * Empty means "nothing is inside app storage", so every path is treated as
     * escaping. That is the safe direction: the default cannot be read as
     * blanket permission to delete.
     */
    val appStorageRoots: Set<String> = DEFAULT_APP_STORAGE_ROOTS,

    /**
     * Targets (phone numbers, emails, contact ids) the user has explicitly
     * marked as trusted. Sharing to a trusted target with a non-destructive
     * payload may skip confirmation; anything unrecognised is held.
     */
    val knownTargets: Set<String> = emptySet(),

    /**
     * How many items one call may affect before it counts as bulk. A bulk call
     * is never auto-executed and never silently confirmed.
     */
    val bulkThreshold: Int = DEFAULT_BULK_THRESHOLD,

    /**
     * How many items a *destructive* call may affect before it is blocked
     * outright rather than confirmed. Blocks because the cost of getting it
     * wrong scales with the count.
     */
    val destructiveBulkBlockThreshold: Int = DEFAULT_DESTRUCTIVE_BULK_BLOCK,

    /**
     * Maximum calls a single task may make before the limit engine starts
     * denying further *executed* actions. Guards against a model that
     * legitimately passes each individual check but accumulates a hundred of
     * them.
     */
    val maxActionsPerTask: Int = DEFAULT_MAX_ACTIONS_PER_TASK,

    /**
     * Maximum destructive actions per task. The blast-radius rule: one task
     * cannot quietly delete thirty things one at a time.
     */
    val maxDestructivePerTask: Int = DEFAULT_MAX_DESTRUCTIVE_PER_TASK,

    /**
     * Arguments treated as filesystem paths for escape analysis. Extensible
     * because tool authors name their own arguments.
     */
    val pathArgumentNames: Set<String> = DEFAULT_PATH_ARGS,

    /** Arguments holding a message body, used for the bare-link check. */
    val bodyArgumentNames: Set<String> = DEFAULT_BODY_ARGS,

    /** Arguments holding a recipient/target, used for the trust check. */
    val targetArgumentNames: Set<String> = DEFAULT_TARGET_ARGS,

    /** Arguments holding a count or list that defines the call's blast radius. */
    val countArgumentNames: Set<String> = DEFAULT_COUNT_ARGS,

    /**
     * Argument keys and values that indicate a privileged capability, whatever
     * the tool's declared [dev.localintelligence.core.tool.ToolRisk] says.
     *
     * WHY this exists: the tier comes from the tool author. An author who
     * declares `settings.write` as REVERSIBLE — because "it writes a setting" —
     * has quietly shipped an accessibility-service enabler behind a tier that
     * auto-executes. The tier is a claim by the person who benefits from the
     * claim, so one independent check does not take it at face value. This is
     * defence in depth, not a replacement for honest risk classification.
     *
     * Only applied to tiers that can change the world (see
     * [PRIVILEGED_ARGUMENT_TIERS]); a READ_ONLY tool asking to read a setting is
     * harmless and must not be blocked for it.
     */
    val privilegedArgumentMarkers: Set<String> = DEFAULT_PRIVILEGED_MARKERS,

    /** Caller rules, applied in order after the built-in argument analysis. */
    val rules: List<PolicyRuleOverride> = emptyList(),

    /**
     * Risk tiers that may run with no human present. Defaults to the two tiers
     * that cannot lose data. Anything added here is a deliberate, reviewable
     * widening — which is why it is a set the caller must name explicitly
     * rather than a flag.
     */
    val autoExecuteTiers: Set<dev.localintelligence.core.tool.ToolRisk> = DEFAULT_AUTO_EXECUTE,

    /**
     * Permission strings the host has granted. A tool declaring a
     * [ToolDefinition.requiredPermission] absent here returns
     * [PolicyOutcome.REQUIRE_PERMISSION] instead of executing.
     */
    val grantedPermissions: Set<String> = emptySet()
) {
    companion object {
        /**
         * The paths an app owns on a stock Android device. A device-specific
         * path prefix (external SD, work profile) is NOT here on purpose:
         * unknown storage must be treated as escaping until a caller adds it.
         */
        val DEFAULT_APP_STORAGE_ROOTS: Set<String> = setOf(
            "/data/data/",
            "/data/user/0/",
            "/storage/emulated/0/Android/data/",
            "/storage/emulated/0/Android/obb/",
        )

        const val DEFAULT_BULK_THRESHOLD = 10
        const val DEFAULT_DESTRUCTIVE_BULK_BLOCK = 100
        const val DEFAULT_MAX_ACTIONS_PER_TASK = 200
        const val DEFAULT_MAX_DESTRUCTIVE_PER_TASK = 20

        val DEFAULT_PATH_ARGS: Set<String> = setOf("path", "paths", "file", "files", "uri", "target", "targetPath")
        val DEFAULT_BODY_ARGS: Set<String> = setOf("body", "text", "message", "content", "note")
        val DEFAULT_TARGET_ARGS: Set<String> = setOf("to", "recipient", "contact", "address", "phone", "number", "email")
        val DEFAULT_COUNT_ARGS: Set<String> = setOf("count", "limit", "ids", "items", "paths", "recipients", "numbers")

        /**
         * Substrings that mark a privileged capability in an argument key or
         * value. Lowercase; matched case-insensitively as a substring.
         *
         * Each one is a way to hand another app control of this device. All of
         * them are exactly what [dev.localintelligence.core.tool.ToolRisk.PRIVILEGED]
         * exists to keep out of reach, so a call carrying one is refused even if
         * its declared tier would otherwise auto-execute.
         */
        val DEFAULT_PRIVILEGED_MARKERS: Set<String> = setOf(
            "accessibility",
            "device_admin",
            "deviceadmin",
            "shizuku",
            "root",
            "su ",
            "pm install",
            "install_packages",
            "notification_listener",
            "usage_stats",
            "bind_device_admin",
            "overlay",
        )

        /**
         * Tiers for which [DEFAULT_PRIVILEGED_MARKERS] is enforced. A READ_ONLY
         * call cannot grant itself a permission, so it is exempt — otherwise
         * `settings.read{key: "accessibility_enabled"}`, a perfectly ordinary
         * diagnostic, would be blocked.
         */
        val PRIVILEGED_ARGUMENT_TIERS: Set<dev.localintelligence.core.tool.ToolRisk> =
            setOf(
                dev.localintelligence.core.tool.ToolRisk.REVERSIBLE,
                dev.localintelligence.core.tool.ToolRisk.DESTRUCTIVE,
                dev.localintelligence.core.tool.ToolRisk.EXTERNAL_COMMUNICATION,
            )

        /** READ_ONLY and REVERSIBLE. Everything else is gated by default. */
        val DEFAULT_AUTO_EXECUTE: Set<dev.localintelligence.core.tool.ToolRisk> =
            setOf(
                dev.localintelligence.core.tool.ToolRisk.READ_ONLY,
                dev.localintelligence.core.tool.ToolRisk.REVERSIBLE,
            )

        /**
         * The shipped configuration. Every value here is chosen so that the
         * *absence* of configuration denies rather than permits.
         *
         * WHY `by lazy` and not a plain `val`: the constructor's default
         * arguments read the constants above, so a `val default = PolicyConfig()`
         * declared before them would read nulls during class initialisation and
         * fail the first call with `NoClassDefFoundError` — a crash in the
         * safety component itself, caused by the order of two declarations in
         * the same file.
         */
        val default: PolicyConfig by lazy { PolicyConfig() }
    }
}
