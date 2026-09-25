package dev.localintelligence.app.execution

import android.content.Context
import dev.localintelligence.app.AgentViewModel
import dev.localintelligence.app.ModelAvailability
import dev.localintelligence.app.RunOutcome
import dev.localintelligence.app.RunState
import dev.localintelligence.app.blockingReason
import dev.localintelligence.app.data.ScheduledTaskStore
import dev.localintelligence.core.agent.StepTrace

/**
 * Writes what a scheduled run actually did into [dev.localintelligence.app.data.ScheduledTask.lastResult].
 *
 * ## The bug this exists to close
 *
 * A scheduled task fired, the agent ran, the model produced an answer — and
 * the schedule screen said `Started.` forever, because the fire path wrote
 * that string and nothing ever overwrote it. The answer was computed and
 * thrown away. `ScheduledTask.lastResult` existed, was persisted, and had
 * exactly two writers, both of which fired *before* the run did anything:
 *
 *  - `ScheduledTaskFireReceiver.advanceSchedule` wrote `Started.`
 *  - and, for a one-shot, `Ran. This was a one-time task…`
 *
 * Neither of those is a result. Both are a prediction, written by the thing
 * that starts the run about the run it has not seen yet.
 *
 * ## Why this reads `RunState` instead of `AgentResult`
 *
 * `AgentViewModel.publish` has already flattened the sealed `AgentResult` onto
 * [RunState] by the time anything here runs, and [RunState.Finished] carries
 * the outcome. Going back up to `AgentResult` would mean re-deriving what the
 * UI already knows, and the two derivations would eventually disagree.
 *
 * ## Why a refusal is read out of the trace
 *
 * `AgentController.refuse` (`:core`, the file this project does not own)
 * returns `null` for a `PolicyOutcome.BLOCK`. A refused tool call does **not**
 * end the run: the justification is handed to the model as an observation and
 * the loop continues, so the model may still go on to answer. There is
 * therefore no terminal "refused" state to read — [RunOutcome] has no such
 * case, and inventing one would mean editing `:core`.
 *
 * What *is* observable is the trace entry `refuse` writes:
 *
 * ```
 * StepTrace(step, TOOL_CALL, "refused <tool>: <rule> — <justification>", success = false, toolName = <tool>)
 * ```
 *
 * So a refusal is reported when a run finished **without an answer** and its
 * trace carries one. That ordering is the honest one in both directions: if the
 * model was refused and then answered anyway, the answer is the result and the
 * user should see the agent's own words explaining what happened — not a
 * bureaucratic note that a tool was once blocked on the way there.
 *
 * ## The five outcomes
 *
 * Distinguished because a user who scheduled a task and cannot tell success
 * from failure has no way to trust the feature:
 *
 * | Outcome  | Terminal signal                          | Field written           |
 * |----------|------------------------------------------|-------------------------|
 * | answer   | `RunOutcome.Answer`                      | `Answered: …`           |
 * | refused  | no answer + a refusal in the trace       | `Refused: …`            |
 * | cancelled| `RunOutcome.Cancelled` / CANCELLED_REASON| `Cancelled: …`          |
 * | failed   | `RunOutcome.Failed` / `StepLimitReached`  | `Failed: …`             |
 * | no run   | readiness recorded and not runnable       | `Did not run: …`        |
 *
 * ## Why the row is re-read instead of cached
 *
 * Deleting or pausing a task while it runs must not be undone by its own
 * result landing. Both operations happen *after* the run captured the task, so
 * writing a captured copy back would resurrect a deleted task and re-enable a
 * paused one. The store is the only current truth, so the row is re-read and
 * only [dev.localintelligence.app.data.ScheduledTask.lastResult] is replaced.
 * A row that is gone is a task the user deleted, and it is left gone: a
 * deleted task is not brought back by the report of the run it was deleted
 * during.
 */
internal object ScheduledRunReporter {

    /**
     * What the fire path writes before a run has produced anything.
     *
     * Deliberately *not* phrased as a result. The service overwrites it on
     * every terminal path, so it is only ever visible if the process died
     * mid-run — and the screen renders that case honestly rather than showing
     * a confident sentence about a run nobody saw finish.
     */
    const val PENDING = "Started, waiting for the result."

    /**
     * Prefix for a schedule fault that must survive the run's own result.
     *
     * One field carries both facts. When the next occurrence cannot be armed
     * (exact-alarm access revoked, no alarm service) that is still true after
     * the run finishes, so the reporter carries it forward onto the result
     * rather than letting the result overwrite the only warning the user has.
     */
    const val SCHEDULE_WARNING_PREFIX = "Next run not scheduled: "

    /**
     * Prefix for a run that never started because the platform refused to
     * launch the service at all.
     *
     * Distinct from [PENDING]: this is written when `startForegroundService`
     * throws in `ScheduledTaskFireReceiver`, so no service ever exists and no
     * result will arrive to overwrite it. It is a terminal outcome, told apart
     * from a "did not run because there was no model" by the sentence that
     * follows the prefix.
     */
    const val DID_NOT_START = "Did not run: "

    /** Kept short enough that 16 rows cannot turn into a megabyte of prefs. */
    const val MAX_RESULT_CHARS = 400

    /**
     * Records the outcome of a scheduled run, if [scheduledTaskId] names one.
     *
     * Null id is a run the user started by hand from the chat screen, which has
     * no row to report into and is already visible in the transcript.
     */
    fun settle(
        context: Context,
        scheduledTaskId: String?,
        state: RunState,
        trace: List<StepTrace>,
        readiness: ModelAvailability?,
    ) {
        val id = scheduledTaskId ?: return
        val outcome = state as? RunState.Finished ?: return
        val store = ScheduledTaskStore(context.applicationContext)

        // Re-read, and write nothing when the row is gone. See the KDoc: a
        // task deleted mid-run stays deleted, and this is the only writer that
        // could otherwise put it back.
        val current = store.byId(id) ?: return
        val result = describe(outcome.outcome, trace, readiness)

        store.upsert(
            current.copy(
                lastResult = carryScheduleWarning(current.lastResult, result),
            ),
        )
    }

    /**
     * The one place a run's terminal state becomes a sentence.
     *
     * Exhaustive over [RunOutcome] on purpose: a fifth outcome added to `:core`
     * should break this at compile time rather than quietly fall through to
     * the generic failure sentence.
     */
    fun describe(
        outcome: RunOutcome,
        trace: List<StepTrace>,
        readiness: ModelAvailability?,
    ): String = when (outcome) {
        is RunOutcome.Answer -> ANSWER_PREFIX + oneLine(outcome.text)

        RunOutcome.Cancelled -> CANCELLED_USER

        is RunOutcome.Failed ->
            when {
                // The scope was torn down mid-run rather than the user asking.
                // `AgentViewModel` publishes this as a `Stop` carrying a
                // constant, so this is an identity check against a named
                // constant, not a guess at a message.
                outcome.reason == AgentViewModel.CANCELLED_REASON -> CANCELLED_INTERRUPTED

                // Blocked before the loop ever started: no model chosen, or
                // the RAM gate refused the load. `readiness` is recorded by the
                // The service wrapping the single `ensureModelReady` call
                // records this, so it is a fact rather than a string match.
                // `blockingReason()` is nullable only for `Ready`, which the
                // guard above has already excluded — the `?:` is for the
                // compiler, not for a case that can happen.
                readiness != null && !readiness.canRun ->
                    NOT_RUN + oneLine(readiness.blockingReason() ?: NO_REASON)

                // No answer, and something was refused along the way. That is
                // the real reason the user has nothing to read.
                else -> refusalOrNull(trace)?.let { REFUSED_PREFIX + oneLine(it) }
                    ?: FAILED_PREFIX + oneLine(outcome.reason)
            }

        RunOutcome.StepLimitReached ->
            refusalOrNull(trace)?.let { REFUSED_PREFIX + oneLine(it) }
                ?: FAILED_PREFIX + STEP_LIMIT_REASON
    }

    /**
     * The policy's own justification, from the trace entry `refuse` wrote.
     *
     * Null when nothing was refused. The detail format is
     * `"refused <tool>: <rule> — <justification>"`; `PolicyRule` is an enum, so
     * the first em dash always separates the rule from the sentence and a
     * justification containing its own em dash is not mis-split. Falls back to
     * naming the tool rather than to an empty string, so a format change in
     * `:core` degrades to a vaguer but still-true sentence instead of to
     * `Refused:`.
     */
    private fun refusalOrNull(trace: List<StepTrace>): String? =
        trace.lastOrNull { isRefusal(it) }
            ?.let { entry ->
                val rest = entry.detail.removePrefix(REFUSED_DETAIL_PREFIX)
                rest.substringAfter(EM_DASH_SEPARATOR, missingDelimiterValue = "")
                    .ifBlank { entry.toolName?.let { "the runtime refused $it." } }
                    ?: entry.toolName?.let { "the runtime refused $it." }
            }

    private fun isRefusal(entry: StepTrace): Boolean =
        entry.kind == StepTrace.Kind.TOOL_CALL &&
            !entry.success &&
            entry.detail.startsWith(REFUSED_DETAIL_PREFIX)

    /**
     * Appends a schedule fault that is still true onto the run's result.
     *
     * A no-op when the fire path recorded no fault. The separator is a space
     * and the trailing full stop is dropped from the warning rather than added
     * to the result, because the result is not always punctuated — an agent
     * answer can end mid-sentence, and `Answered: All clear.. exact-alarm…` is
     * the sort of thing a user reads as a rendering fault rather than as two
     * facts.
     */
    private fun carryScheduleWarning(previous: String?, result: String): String {
        val warning = previous?.takeIf { it.startsWith(SCHEDULE_WARNING_PREFIX) }
            ?: return result
        val body = warning.removePrefix(SCHEDULE_WARNING_PREFIX).trim().trimEnd('.')
        return "$result $body."
    }

    /**
     * One line, at most [MAX_RESULT_CHARS], cut on a word boundary.
     *
     * A model answer is a paragraph and a list row is not, so the stored field
     * is bounded rather than the UI merely hiding the overflow: these rows live
     * in one JSON blob in SharedPreferences that every boot broadcast re-parses,
     * and 16 unbounded answers is a real cost for no benefit. The ellipsis is
     * always present when anything was dropped, so a truncated answer cannot be
     * mistaken for a complete one.
     */
    fun oneLine(text: String): String {
        val flat = text.replace(NEWLINES, " ").replace(MULTISPACE, " ").trim()
        if (flat.length <= MAX_RESULT_CHARS) return flat
        val cut = flat.take(MAX_RESULT_CHARS)
        val boundary = cut.lastIndexOf(' ')
        val body = if (boundary > MAX_RESULT_CHARS / 2) cut.take(boundary) else cut
        return body.trimEnd() + ELLIPSIS
    }

    // ---- wording -----------------------------------------------------------
    //
    // Five sentences, each naming a different fact. Plain text, no formatting
    // and no colour: the schedule list is read by people looking for the one
    // word that tells them whether to worry, and a prefix that says which of
    // the five happened is that word.
    //
    // The prefixes are public because `ScheduleScreen` keys its colour off
    // them. A UI that re-typed "Refused: " as a literal would drift from the
    // writer the first time either side was reworded, and the symptom would be
    // a refusal rendered in the neutral colour — the exact case where the
    // colour is the only thing telling the user to look twice.

    const val ANSWER_PREFIX = "Answered: "
    const val REFUSED_PREFIX = "Refused: "
    const val FAILED_PREFIX = "Failed: "
    const val NOT_RUN = "Did not run: "

    /**
     * Unreachable in practice — [blockingReason] is non-null for every
     * availability that is not [dev.localintelligence.app.ModelAvailability.Ready].
     * It exists so that a future case added to the sealed type degrades to an
     * honest sentence instead of to `Did not run: null`.
     */
    private const val NO_REASON = "the model was not ready."

    private const val CANCELLED_USER =
        "Cancelled: stopped before it finished."
    private const val CANCELLED_INTERRUPTED =
        "Cancelled: the run was interrupted before it finished."

    /**
     * `RunOutcome.notice` already owns this sentence for the transcript. It is
     * restated rather than imported because that property is UI wording on a
     * type the reporter reads as data, and the field is persisted — it has to
     * mean the same thing in six months, with or without the UI.
     */
    private const val STEP_LIMIT_REASON = "the agent used all of its steps."

    private const val REFUSED_DETAIL_PREFIX = "refused "
    private const val EM_DASH_SEPARATOR = " — "
    private const val ELLIPSIS = "…"
    private val NEWLINES = Regex("\\s*\\R+\\s*")
    private val MULTISPACE = Regex(" {2,}")
}
