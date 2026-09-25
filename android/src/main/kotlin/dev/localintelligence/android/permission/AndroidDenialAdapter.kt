package dev.localintelligence.android.permission

import dev.localintelligence.core.permission.Permission
import dev.localintelligence.core.permission.PermissionAskPolicy
import dev.localintelligence.core.permission.PermissionDenial
import dev.localintelligence.core.permission.PermissionObservation
import dev.localintelligence.core.permission.PermissionState
import dev.localintelligence.core.permission.PlatformDenial
import dev.localintelligence.core.tool.ToolError
import dev.localintelligence.core.tool.ToolResult

/**
 * Turns a `SecurityException` thrown from inside a framework call into a
 * structured, model-readable denial.
 *
 * WHY this exists when the guard already blocks before execution: the guard
 * removes the *predictable* case, and the predictable case is most of it. What
 * remains are the calls where the framework enforces something the app cannot
 * pre-check — a provider behind a content URI, a scoped-storage boundary, a
 * per-account restriction. Those throw mid-call, sometimes after a partial
 * write, and every wave-2 tool currently handles them by catching and
 * re-formatting the exception message by hand.
 *
 * Centralising that here means the message is sanitised once, by the one object
 * that is tested against leaking, instead of eight times by hand.
 */
object AndroidDenialAdapter {

    /**
     * Builds the result for a caught [throwable] on [toolName].
     *
     * [capabilityLabel] is what the model will say to the user ("your calendar"),
     * so it belongs here rather than being derived from the permission id.
     *
     * The returned result is a failure with `ToolError.PermissionDenied` when
     * the throwable is a permission denial, and a failure with
     * `ToolError.Internal` otherwise. It never rethrows: an exception escaping
     * a tool takes down an agent step that should have recovered.
     */
    fun fromThrowable(
        toolName: String,
        permission: Permission,
        capabilityLabel: String,
        throwable: Throwable,
        askPolicy: PermissionAskPolicy,
    ): ToolResult {
        val classified = PlatformDenial.classify(
            exceptionSimpleName = throwable.javaClass.simpleName,
            // The policy knows whether this session already prompted, which is
            // the fact that separates a soft denial from a permanent one.
            askedAlready = askPolicy.asksFor(permission) > 0,
        )

        if (!classified.isPermissionDenial) {
            // Not a permission problem. The message is sanitised anyway, because
            // an arbitrary exception message has no business being read by a
            // model verbatim.
            return ToolResult(
                success = false,
                observation = PermissionObservation.sanitize(
                    "The system refused the request for $capabilityLabel. " +
                        "Tell the user it could not be completed.",
                ),
                error = ToolError.Internal("non_permission_failure_for_$toolName"),
            )
        }

        val state: PermissionState = classified.state
        val decision = askPolicy.decide(permission, state)
        val denial = PermissionDenial(
            toolName = toolName,
            permission = permission,
            state = state,
            decision = decision,
            observation = observationFor(state, capabilityLabel, decision),
        )
        return denial.toToolResult()
    }

    private fun observationFor(
        state: PermissionState,
        capabilityLabel: String,
        decision: PermissionAskPolicy.Decision,
    ): String {
        val base = PermissionObservation.forState(state, capabilityLabel)
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
        return PermissionObservation.clamp(PermissionObservation.sanitize(text))
            .take(PermissionObservation.BUDGET_CHARS)
    }
}
