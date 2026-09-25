package dev.localintelligence.core.permission

import dev.localintelligence.core.tool.ToolError
import dev.localintelligence.core.tool.ToolResult

/**
 * The structured form of "a tool cannot run because a permission is missing".
 *
 * WHY not just a `ToolResult`: the loop needs more than the model-visible
 * sentence. It needs to know *which* permission blocked it (to stop offering
 * the tool again), *which* state applied (to decide whether asking is possible),
 * and whether a prompt is still owed. All three are invisible once the string
 * has been flattened into an observation.
 *
 * [ToolResult] is still what the loop feeds the model — this is the richer
 * carrier that produces it.
 */
data class PermissionDenial(
    val toolName: String,
    val permission: Permission,
    val state: PermissionState,
    val decision: PermissionAskPolicy.Decision,
    /** The model-visible sentence. Never contains a class name or a code. */
    val observation: String,
) {
    /**
     * Whether the tool may run next time without any state changing.
     *
     * The loop reads this to stop re-offering a tool: if this is true, asking
     * again cannot help and the model must be told to move on.
     */
    val isTerminal: Boolean
        get() = !state.isRetryable || decision is PermissionAskPolicy.Decision.Refuse

    /** The result to hand back to the agent loop. See docs/tool-contract.md. */
    fun toToolResult(): ToolResult = ToolResult(
        success = false,
        observation = observation,
        error = ToolError.PermissionDenied(denialCode(state, decision)),
    )

    companion object {
        /**
         * A short, stable code describing why the tool was blocked.
         *
         * WHY this is the `ToolError.PermissionDenied` *message* and not its
         * `code`: `ToolError.PermissionDenied` is frozen with
         * `code = "permission_denied"` for every instance, and changing that
         * would break every workstream that matches on it. The finer
         * distinction lives in the message, which is what the trace UI and the
         * database read.
         *
         * It must stay a short token, never a platform code: it is written by
         * the same no-leak rule as the observation.
         */
        fun denialCode(
            state: PermissionState,
            decision: PermissionAskPolicy.Decision,
        ): String = when {
            state.allowsExecution -> "permission_granted"
            state == PermissionState.NOT_APPLICABLE -> "permission_not_applicable"
            // The ask budget is checked BEFORE the permanent-denial state, and
            // the order is not cosmetic. A state of DENIED_PERMANENTLY is
            // normally reached because the system stopped prompting, but it is
            // also reached when *we* declined to prompt again. Those are
            // different recoveries — one needs Settings, the other just needs a
            // new session — so they must not share a code.
            //
            // The two are still unambiguous: a genuinely permanent state always
            // refuses with PERMANENTLY_DENIED (the state is checked first by
            // the policy), never with ASK_BUDGET_SPENT.
            decision is PermissionAskPolicy.Decision.Refuse &&
                decision.reason == PermissionAskPolicy.RefusalReason.ASK_BUDGET_SPENT ->
                "permission_ask_budget_spent"
            state == PermissionState.DENIED_PERMANENTLY -> "permission_denied_permanently"
            else -> "permission_denied"
        }
    }
}

/**
 * Checks permissions *before* a tool runs.
 *
 * WHY pre-execution rather than catching a failure afterwards: an Android
 * permission that is not granted either throws a `SecurityException` from an
 * arbitrary point inside the framework call — sometimes after a partial write,
 * which for a `WRITE_` permission means the tool did something before failing
 * — or returns an empty result set, which looks like "the user has no contacts"
 * and is worse than a refusal. A model that reads an empty contact list tells
 * the user they have no contacts. Blocking first removes both failures.
 */
class PermissionGuard(
    private val broker: PermissionBroker,
    private val askPolicy: PermissionAskPolicy,
    private val requirements: PermissionRequirementSource,
) {

    /** What happened when a tool asked for clearance. */
    sealed interface Verdict {
        /** No requirement, or all satisfied. Run the tool. */
        data object Proceed : Verdict

        /** Blocked before execution, with a model-readable reason. */
        data class Denied(val denial: PermissionDenial) : Verdict
    }

    /**
     * Check [toolName] against the live state of every permission it declares.
     *
     * Returns the *first* blocking requirement rather than a list, because the
     * model needs one sentence: naming two missing permissions and asking for
     * both is a worse prompt than naming the one that matters, and the order of
     * the requirement list is the order the tool author wrote them in.
     *
     * Pure with respect to the ask counts — it calls [PermissionAskPolicy.decide],
     * not the recording method. A caller that goes on to prompt must call
     * [PermissionAskPolicy.nextStateAfterAsk] exactly once.
     */
    fun check(toolName: String): Verdict {
        for (requirement in requirements.requirementsFor(toolName)) {
            val state = stateOf(requirement.permission)
            if (state.allowsExecution) continue

            val decision = askPolicy.decide(requirement.permission, state)
            return Verdict.Denied(
                PermissionDenial(
                    toolName = toolName,
                    permission = requirement.permission,
                    state = state,
                    decision = decision,
                    observation = observationFor(toolName, requirement, state, decision),
                ),
            )
        }
        return Verdict.Proceed
    }

    /**
     * True when [toolName] is currently blocked, for callers that only need the
     * boolean (tool selection hiding an impossible tool).
     */
    fun isBlocked(toolName: String): Boolean = check(toolName) is Verdict.Denied

    /**
     * Reads the broker defensively.
     *
     * A broker that throws must not take the agent step down with it: the
     * contract says the answer for an unknown permission is NOT_APPLICABLE, and
     * the correct response to a broker that breaks that contract is to treat
     * its answer as not-applicable — blocking the tool, never crashing.
     */
    private fun stateOf(permission: Permission): PermissionState =
        runCatching { broker.stateOf(permission) }.getOrElse {
            PermissionState.NOT_APPLICABLE
        }

    private fun observationFor(
        toolName: String,
        requirement: PermissionRequirement,
        state: PermissionState,
        decision: PermissionAskPolicy.Decision,
    ): String {
        val label = requirement.capabilityLabel
        val base = PermissionObservation.forState(state, label)
        // The ask-budget refusal is the case the hint generator cannot see: the
        // state still says "ask again" but we are not going to. The model has to
        // be told to stop, or it will spend the rest of the conversation asking.
        val extra = when (decision) {
            is PermissionAskPolicy.Decision.Refuse ->
                when (decision.reason) {
                    PermissionAskPolicy.RefusalReason.ASK_BUDGET_SPENT ->
                        "You already asked twice this session, so do not ask again; " +
                            "tell the user what you needed and stop."
                    PermissionAskPolicy.RefusalReason.PERMANENTLY_DENIED,
                    PermissionAskPolicy.RefusalReason.NOT_APPLICABLE -> ""
                }
            else -> ""
        }
        val text = if (extra.isEmpty()) base else "$base $extra"
        // Last line of defence: the budget and the no-leak rule are enforced
        // here too, so a future edit to a hint cannot quietly break either.
        return PermissionObservation.clamp(PermissionObservation.sanitize(text))
            .take(PermissionObservation.BUDGET_CHARS)
    }
}
