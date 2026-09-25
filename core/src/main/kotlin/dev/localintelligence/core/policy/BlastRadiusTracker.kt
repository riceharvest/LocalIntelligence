package dev.localintelligence.core.policy

import dev.localintelligence.core.tool.ToolRisk

/**
 * Per-task running totals used to cap blast radius.
 *
 * WHY this is a separate object from [PolicyConfig]: the config is the rule,
 * this is the *state* the rule is applied to, and the state must reset between
 * tasks. Keeping them apart means a new task cannot inherit the previous task's
 * headroom, which is the bug where "20 deletes per task" quietly becomes "20
 * deletes per app session".
 *
 * Not thread-safe by design — one task, one thread, the agent loop. Callers
 * that need more should hold one tracker per task.
 */
class BlastRadiusTracker(
    private val config: PolicyConfig = PolicyConfig.default,
) {
    /** How many executed tool calls this task has made. */
    var actionCount: Int = 0
        private set

    /** How many *destructive* actions this task has executed. */
    var destructiveCount: Int = 0
        private set

    /** How many confirmable actions this task has had approved. */
    var confirmedCount: Int = 0
        private set

    /**
     * Records that a call actually ran.
     *
     * Called only after execution is authorised — never at decision time. If it
     * were called at decision time, a denied call would burn budget and the
     * model could starve itself by attempting things it is not allowed to do.
     */
    fun recordExecuted(risk: ToolRisk) {
        actionCount += 1
        if (risk == ToolRisk.DESTRUCTIVE) destructiveCount += 1
    }

    /** Records that a human approved a gated call. */
    fun recordConfirmed(risk: ToolRisk) {
        confirmedCount += 1
        if (risk == ToolRisk.DESTRUCTIVE) destructiveCount += 1
    }

    /** Clears all counters. Call at the start of every task. */
    fun reset() {
        actionCount = 0
        destructiveCount = 0
        confirmedCount = 0
    }

    /**
     * Whether another call of this risk may proceed at all.
     *
     * WHY destructive is capped separately from total actions: a task that
     * makes two hundred harmless reads and then deletes something is still a
     * task that deleted something, and the user approved "read my files", not
     * "read my files then delete them".
     */
    fun permits(risk: ToolRisk): Boolean = when {
        actionCount >= config.maxActionsPerTask -> false
        risk == ToolRisk.DESTRUCTIVE && destructiveCount >= config.maxDestructivePerTask -> false
        else -> true
    }

    /** Human-readable reason for a denial, or null when permitted. */
    fun denialReason(risk: ToolRisk): String? = when {
        actionCount >= config.maxActionsPerTask ->
            "This task has already run $actionCount actions, the limit is " +
                "${config.maxActionsPerTask}. Splitting it into a new task is safer than continuing."

        risk == ToolRisk.DESTRUCTIVE && destructiveCount >= config.maxDestructivePerTask ->
            "This task has already performed $destructiveCount destructive actions, the limit is " +
                "${config.maxDestructivePerTask}."

        else -> null
    }
}
