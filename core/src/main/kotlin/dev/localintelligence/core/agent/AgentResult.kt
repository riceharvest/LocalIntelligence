package dev.localintelligence.core.agent

import dev.localintelligence.core.model.ToolArgs
import dev.localintelligence.core.model.prefillRateOf
import dev.localintelligence.core.model.prefilledTokensOf
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
        /** The policy's sentence for why a human must decide, such as the destination host. */
        val justification: String = "",
    ) : AgentResult

    /**
     * A terminal failure with a human-readable reason. The loop gave up.
     *
     * [kind] is the app's failure kind as a machine key, set by a caller that knows it. [localized]
     * says the reason was already translated where it was built, so the app must not match its
     * words to a kind. A core reason is English and is matched by the app when it arrives.
     */
    data class Stop(
        val reason: String,
        val trace: List<StepTrace>,
        val kind: String? = null,
        val localized: Boolean = false,
    ) : AgentResult

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
    // WHY THESE EXIST: `detail` for a TOOL_CALL was `name + compactArgs(args)`,
    // which is a display string with no separator contract. A UI that wants the
    // tool name and its arguments has to substring it, and a tool whose name
    // contains a brace, or an argument value that does, breaks that. The UI is
    // exactly where this matters: it renders the call and the phone's answer.
    // Null on every other kind, which is what a reader checks.
    val toolName: String? = null,
    val toolArgs: String? = null,
    /**
     * The model's thought before this step, on a GENERATION row, when it thought. Shown
     * folded under the answer; never part of [detail] and never sent back to the model.
     * Null on every other kind and when the model did not think.
     */
    val thought: String? = null,
    /**
     * The speed of this generation, on a GENERATION row, from the backend's own counts and timers.
     * Shown folded under the reply in Technical mode. Null on every other kind.
     */
    val speed: ReplySpeed? = null,

    /**
     * The memory-block change an OBSERVATION made, from the tool's result data (`change_id`), or null.
     * The chat row uses it to offer Undo on exactly that change. Null on every other kind and for
     * every tool that did not write the block.
     */
    val changeId: Long? = null,

    /**
     * On a TOOL_CALL row for `task.delegate`: the subtask's own steps, folded under that row. Shown to
     * the user only. The parent's model never sees them; it gets the subtask's capped answer.
     */
    val children: List<StepTrace> = emptyList(),

    /**
     * The call's arguments as JSON, exactly as the model sent them, on a TOOL_CALL row. Null
     * elsewhere. The eval runner (scripts/run-evals.py) checks arguments against this; the
     * display string in [toolArgs] is lossy (40-character values, six keys) and cannot be checked.
     */
    val toolArgsJson: String? = null,
) {
    enum class Kind { GENERATION, TOOL_CALL, OBSERVATION, MALFORMED, COMPACTION }
}

/**
 * One generation's token counts and timers, as the backend reported them. Rates are derived,
 * never stored, so a zero timer shows as "no rate", not as a number invented from nothing.
 *
 * [prefillTokensPerSecond] is over the tokens actually processed (the prompt minus [reusedTokens]),
 * and [firstTokenMs] is the time to the first token, which is what the reply shows as "first token".
 * Both are null when the backend did not report them.
 */
data class ReplySpeed(
    val promptTokens: Int,
    val completionTokens: Int,
    val prefillMs: Long,
    val decodeMs: Long,
    val reusedTokens: Int? = null,
    val firstTokenMs: Long? = null,
) {
    /** Prompt tokens processed per second, or null when the count or the prefill timer is missing. */
    val prefillTokensPerSecond: Double?
        get() = prefillRateOf(prefilledTokensOf(promptTokens, reusedTokens), prefillMs)

    /** Generated tokens per second, or null when the decode timer is zero. */
    val decodeTokensPerSecond: Double? get() = if (decodeMs > 0 && completionTokens > 0) completionTokens * 1000.0 / decodeMs else null
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

/**
 * Why a [AgentResult.Stop] happened, as a machine key. The app maps each key to a Simple sentence
 * with a next step (`SimpleWording.Kind`), so the names here are the enum names there. Technical
 * mode still shows the English reason, which is unchanged.
 *
 * WHY STRINGS AND NOT AN ENUM IN :core: the app's kinds carry string resources, and :core has no
 * Android types. A key the app does not know maps to the generic sentence, never to a crash.
 */
object StopKind {
    const val LOOP_DETECTED = "LOOP_DETECTED"
    const val NO_PROGRESS = "NO_PROGRESS"
    const val NO_VALID_ACTION = "NO_VALID_ACTION"
    const val REPEATED = "REPEATED"
    const val EMPTY_REPLY = "EMPTY_REPLY"
    const val MODEL_FAILED = "MODEL_FAILED"
    const val TOO_LONG = "TOO_LONG"
    const val INTERNAL = "INTERNAL"
    const val NOTHING_PENDING = "NOTHING_PENDING"
    const val CANT_SEE = "CANT_SEE"
    const val CANT_HEAR = "CANT_HEAR"
    const val INTERRUPTED = "INTERRUPTED"

    /**
     * The stops that get one last answer-only generation when a tool already succeeded in the run:
     * the model was still working, and its work so far is worth a sentence. Every other stop ends
     * as it is, because asking the same failing model again would only repeat it.
     */
    val ANSWER_FIRST: Set<String> = setOf(LOOP_DETECTED, NO_PROGRESS, NO_VALID_ACTION, REPEATED)
}
