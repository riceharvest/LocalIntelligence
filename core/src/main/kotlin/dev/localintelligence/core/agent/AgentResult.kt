package dev.localintelligence.core.agent

import dev.localintelligence.core.model.ToolArgs
import dev.localintelligence.core.tool.AgentTool
import dev.localintelligence.core.tool.ToolCallValidator
import dev.localintelligence.core.tool.ToolRegistry

/**
 * The entire observable surface of the loop (`docs/wave1-contract.md`).
 *
 * Every exit from `AgentController.run()` is one of these. Nothing throws out
 * of the loop, so a caller can always exhaustively handle a run with a `when`.
 *
 * Note: [StepLimitReached] and [Cancelled] carry no trace. That is the pinned
 * wave-1 signature; it also means the two most-diagnosable failures lose their
 * history. Raised in the PR rather than changed here.
 */
sealed interface AgentResult {
    /** The model produced `Respond`. The task is done — there is no "and then". */
    data class Success(val text: String, val trace: List<StepTrace>) : AgentResult

    /**
     * A risky tool was about to run and the runtime, not the model, decided a
     * human must approve it. Nothing has executed yet. Resume with
     * `AgentController.confirmAndResume`.
     */
    data class AwaitingConfirmation(
        val toolName: String,
        val tool: AgentTool,
        val pendingArgs: ToolArgs,
        val trace: List<StepTrace>,
    ) : AgentResult

    /** A terminal failure with a human-readable reason. The loop gave up. */
    data class Stop(val reason: String, val trace: List<StepTrace>) : AgentResult

    /** `maxSteps` was exhausted without a `Respond`. */
    data object StepLimitReached : AgentResult

    /** The run was cancelled — by `cancel()`, or by the backend reporting it. */
    data object Cancelled : AgentResult
}

/**
 * One line of the run history, for the trace UI and the database.
 *
 * The loop appends in this order within a step:
 * `GENERATION`, then (`TOOL_CALL`, `OBSERVATION`) or `MALFORMED`, then
 * `COMPACTION` if the window needed folding.
 */
data class StepTrace(
    val step: Int,
    val kind: Kind,
    val detail: String,
    val durationMs: Long = 0,
    val success: Boolean = true,
) {
    enum class Kind { GENERATION, TOOL_CALL, OBSERVATION, MALFORMED, COMPACTION }
}

/**
 * The loop's validation seam.
 *
 * It exists so the loop does not depend on the shape of the `ToolCallValidator`
 * object, and so a test can substitute a policy without a registry. Validation
 * runs *before* the loop detector: a bad argument is an argument error, not a
 * loop, and the model must be told which one it is.
 */
fun interface ToolCallValidatorGate {
    fun validate(
        name: String,
        args: ToolArgs,
        visible: List<AgentTool>,
    ): ValidationOutcome

    companion object {
        /**
         * Adapts the frozen [ToolCallValidator] to this gate by capturing the
         * registry, which the gate deliberately does not carry — the loop only
         * ever knows about the *visible* tools.
         */
        fun forRegistry(registry: ToolRegistry): ToolCallValidatorGate =
            ToolCallValidatorGate { name, args, visible ->
                when (val result = ToolCallValidator.validate(name, args, visible, registry)) {
                    is ToolCallValidator.Result.Valid -> ValidationOutcome.Ok(result.tool)
                    is ToolCallValidator.Result.Rejected -> ValidationOutcome.Rejected(result.observation)
                }
            }
    }
}

sealed interface ValidationOutcome {
    data class Ok(val tool: AgentTool) : ValidationOutcome
    data class Rejected(val observation: String) : ValidationOutcome
}
