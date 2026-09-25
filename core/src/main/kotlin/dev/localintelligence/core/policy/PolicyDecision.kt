package dev.localintelligence.core.policy

import dev.localintelligence.core.model.ToolArgs
import dev.localintelligence.core.tool.ToolDefinition
import dev.localintelligence.core.tool.ToolRisk

/**
 * The four answers the runtime can give about a proposed tool call.
 *
 * WHY an enum and not a boolean: `needsConfirmation = true` is a claim a UI
 * cannot render. "Send 1 SMS to a stranger" and "delete 400 files" both produce
 * `true`, and the user is shown identical dialogs. A [Decision] carries the
 * rule that fired and a sentence explaining it, so the confirmation screen can
 * say *why* and the debug trace (P0-064) can replay the reasoning later.
 *
 * Every case is a denial except [Execute]. That asymmetry is the point: a new
 * case added to this enum defaults to "do not run the tool".
 */
enum class PolicyOutcome {
    /** Safe to run unattended. */
    EXECUTE,

    /** Park the call and ask a human. See `ConfirmationSession`. */
    REQUIRE_CONFIRMATION,

    /** Refuse. The model is told why; nothing runs, ever. */
    BLOCK,

    /**
     * The tool needs an OS permission that has not been granted. Distinct from
     * [BLOCK] because the fix is a system dialog, not a refusal.
     */
    REQUIRE_PERMISSION,
}

/**
 * Why the engine reached its verdict. Carried on every [Decision] so a trace
 * is self-explaining: the rule id is the audit key, the justification is the
 * sentence a human reads.
 */
enum class PolicyRule {
    /** Risk tier maps directly to an outcome. The common case. */
    RISK_TIER,

    /** Arguments look malformed or the schema does not describe them. */
    MALFORMED_ARGUMENTS,

    /** A required argument is absent. */
    MISSING_ARGUMENTS,

    /** A path argument resolves outside the app's own storage. */
    PATH_ESCAPES_APP_STORAGE,

    /** The call is one of many: a count or recipient list exceeds the cap. */
    BULK_OPERATION,

    /** The action is externally visible and the target is not a known contact. */
    EXTERNAL_TO_UNKNOWN_TARGET,

    /** A message body is a bare link — the classic drive-by-phishing shape. */
    MESSAGE_IS_BARE_LINK,

    /** The blast radius or rate limit for this task is already spent. */
    LIMIT_EXCEEDED,

    /** PRIVILEGED tool. Hard-disabled, and no config can re-enable it. */
    PRIVILEGED_DISABLED,

    /** No rule matched and the engine could not justify running it. */
    UNCLASSIFIED,

    /** A caller-supplied rule from [PolicyConfig.rules] decided. */
    CUSTOM,

    /** The tool's schema declares a permission that is not granted. */
    PERMISSION_NOT_GRANTED,
}

/**
 * One decision about one proposed call, with its full reasoning attached.
 *
 * Immutable by construction: the only way to build one is through
 * [RiskPolicy.evaluate], so a [Decision] can be parked in a session, stored,
 * logged, or rendered without any risk of it being edited in between.
 */
data class Decision(
    val outcome: PolicyOutcome,
    val toolName: String,
    val risk: ToolRisk,
    val rule: PolicyRule,
    /**
     * One plain sentence a non-engineer can read: "This sends a message to
     * +316****0000, which is not in your contacts." Never empty — [RiskPolicy]
     * guarantees a justification on every path, including its error paths.
     */
    val justification: String,
    /**
     * The arguments the decision was made about. Held so a confirmation
     * session can prove the human approved *these* arguments, not some later
     * mutation of them.
     */
    val arguments: ToolArgs,
    /**
     * The tool definition this was decided against. Held for the same reason:
     * a session must re-verify the tool it parks has not changed underneath it.
     */
    val definition: ToolDefinition,
) {
    /** True only when the tool may run with no human in the loop. */
    val allowsExecution: Boolean
        get() = outcome == PolicyOutcome.EXECUTE

    /** True when the call must be parked in a [ConfirmationSession] first. */
    val requiresConfirmation: Boolean
        get() = outcome == PolicyOutcome.REQUIRE_CONFIRMATION

    init {
        // Fail fast on an unjustified decision. An empty justification means a
        // branch in the engine was written without thinking about the user
        // reading it, and that is exactly the branch that ships unreviewed.
        require(justification.isNotBlank()) {
            "every Decision needs a justification; rule=$rule tool=$toolName"
        }
    }

    companion object {
        /** Canonical ordering, strongest restriction first. Used by the UI. */
        fun restrictiveness(outcome: PolicyOutcome): Int = when (outcome) {
            PolicyOutcome.EXECUTE -> 0
            PolicyOutcome.REQUIRE_PERMISSION -> 1
            PolicyOutcome.REQUIRE_CONFIRMATION -> 2
            PolicyOutcome.BLOCK -> 3
        }
    }
}
