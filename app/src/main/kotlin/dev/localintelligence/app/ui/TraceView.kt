package dev.localintelligence.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.localintelligence.app.RunOutcome
import dev.localintelligence.app.RunState
import dev.localintelligence.core.agent.StepTrace
import dev.localintelligence.core.tool.ToolRisk

/**
 * The debug screen, and the single most valuable screen in the app for
 * development. When a 1B model does the wrong thing, this is where the answer is.
 *
 * ## What it shows and why
 *
 * The loop appends to the trace in a fixed order within a step
 * (`docs/architecture.md` §6, and `AgentResult.kt`): `GENERATION`, then
 * (`TOOL_CALL`, `OBSERVATION`) or `MALFORMED`, then `COMPACTION` if the window
 * folded. This renders that order rather than re-sorting it, because the order
 * *is* the diagnostic: a `TOOL_CALL` followed by a `MALFORMED` means something
 * different from the reverse, and a trace sorted by timestamp would hide it.
 *
 * Rows are grouped by step number, because "what happened on step 3" is the
 * question that actually gets asked, and a flat list of 40 unlabelled rows
 * answers it badly.
 *
 * ## What is deliberately not here
 *
 * **No chain-of-thought panel.** `AgentAction` is `Respond | CallTool` (§5); the
 * model has no thought channel to display, so a UI that showed one would be
 * fabricating. What this screen shows instead is the real `GENERATION` text — the
 * literal bytes the model emitted, including the ones that failed to parse. That
 * is the actual reasoning artefact, and unlike a "thinking" spinner it is
 * verifiable.
 *
 * ## The two results that lose their trace
 *
 * `StepLimitReached` and `Cancelled` carry no trace in the wave-1 contract, so an
 * empty trace here is ambiguous between "nothing has run yet" and "the run ended
 * without recording one". The empty state says which.
 */
@Composable
fun TraceView(
    trace: List<StepTrace>,
    modifier: Modifier = Modifier,
    /**
     * The current run phase, so the empty state can be honest about *why* it is
     * empty.
     *
     * `StepLimitReached` and `Cancelled` carry no trace in the wave-1 contract,
     * so "nothing here" is ambiguous between "you have not run anything yet" and
     * "the run you just did ended without recording one". Without this the
     * screen says "send a task" to a user who just sent one, which is the exact
     * confusion this screen is supposed to prevent.
     */
    runState: RunState? = null,
) {
    if (trace.isEmpty()) {
        TraceEmptyState(runState = runState, modifier = modifier)
        return
    }

    val steps = remember(trace) { trace.groupBy { it.step }.toSortedMap() }
    var expanded by remember(trace) { mutableStateOf(emptySet<Int>()) }

    Column(modifier.fillMaxWidth()) {
        TraceRunCaption(runState)
        // WHY THE VERDICT IS RENDERED ABOVE A NON-EMPTY TRACE: a failed run
        // usually has steps, so the screen used to show rows and no outcome at
        // all — the user was left reading a trace of a run that had failed,
        // with nothing saying so. Worse, `ChatViewModel.transcriptLine` tells
        // the user "the runtime's own error is on the Trace screen", and on
        // exactly the runs that matter it was not: the error lives in the run
        // state, not in a trace row, so the transcript was pointing at a screen
        // that did not have it. One line, from the same state, closes both.
        traceVerdict(runState)?.let { verdict ->
            Text(
                text = verdict,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
        TraceSummary(trace)
        HorizontalDivider()
        LazyColumn(Modifier.fillMaxWidth()) {
            items(steps.entries.toList(), key = { it.key }) { (step, rows) ->
                val isOpen = step in expanded
                StepHeader(
                    step = step,
                    rows = rows,
                    expanded = isOpen,
                    onToggle = { expanded = if (isOpen) expanded - step else expanded + step },
                )
                if (isOpen) {
                    rows.forEach { TraceRow(it) }
                }
                HorizontalDivider()
            }
        }
    }
}

/**
 * Says whose run this is.
 *
 * `RunSinks.reset()` is never called — the sinks are created once in
 * `AppContainer` and shared by every `AgentViewModel` the service builds — so
 * the trace on screen is whatever the *last reporting run* left there. Opening
 * this screen during a second run therefore shows the first run's steps with
 * nothing to say so, and the natural reading is that the run in progress has
 * already done those things. It has not: the controller is per-run and its trace
 * list starts empty, so the list only ever belongs to one run.
 */
@Composable
private fun TraceRunCaption(runState: RunState?) {
    val caption = when (runState) {
        // A run in flight cannot be showing its own steps, because the runtime
        // has not reported back yet. Saying so is the whole point.
        null, RunState.Idle -> "The most recent run that reported its steps."
        RunState.LoadingModel, RunState.Running ->
            "A run is in progress. These are the steps from the previous one — " +
                "this run's steps arrive when it reports back."
        is RunState.AwaitingApproval ->
            "Steps from the run now waiting on your approval."
        is RunState.Finished -> "The most recent run."
    }
    Text(
        text = caption,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

/**
 * The prefix `AgentController` puts on a failed generation.
 *
 * `AgentController` returns `Stop("model failed: ${generation.text.take(120)}")`
 * when the backend reports `StopReason.ERROR`. Everything after the prefix is
 * the backend's own error — an llama.cpp string, a JNI message, sometimes a
 * path. `ChatViewModel.transcriptLine` already refuses to print it; this file
 * did not, and the empty state interpolated `${outcome.reason}` straight onto
 * the screen. One screen sanitising it and the other not is exactly the drift
 * the shared `describeLoadFailure` in `:app` was written to prevent.
 *
 * WHY THE REPLACEMENT SAYS WHAT IT KNOWS AND NO MORE: the trace's last row is
 * the literal bytes the model emitted, and those bytes are still shown verbatim
 * below — they are the artefact. The native error is the one string that is
 * both unreadable and un-derivable from anything on this screen.
 */
private const val RAW_MODEL_FAILURE = "model failed: "

/** The reason a run failed, with the backend's raw error string removed. */
internal fun safeFailureReason(reason: String): String =
    if (reason.startsWith(RAW_MODEL_FAILURE)) {
        "The model stopped while it was generating, and produced no answer. " +
            "This build does not show the runtime's raw error text on screen."
    } else {
        reason
    }

/**
 * The one line that says how the run on screen ended, or null when it did not
 * fail.
 *
 * Only failures. A run that answered or was stopped normally needs no verdict
 * here — the transcript already shows the answer — and adding a line for them
 * would be noise on a debug screen.
 */
internal fun traceVerdict(runState: RunState?): String? {
    val outcome = (runState as? RunState.Finished)?.outcome ?: return null
    if (outcome !is RunOutcome.Failed) return null
    return "That run failed: ${safeFailureReason(outcome.reason)}"
}

/**
 * Why the trace is empty, in the user's terms.
 *
 * A function rather than three inline `Text` calls so the mapping is one
 * exhaustive `when` over the run state: adding a sixth `RunState` breaks the
 * build here instead of silently falling back to the "send a task" message,
 * which would be wrong for every new state.
 *
 * ## Why the in-flight messages do not promise a live list
 *
 * `AgentViewModel` writes the trace into its sink only inside `publish()`, which
 * runs at an approval or a terminal outcome — never while the loop is between
 * steps. So an empty trace during a run does not mean "nothing is happening"; it
 * means the loop has not reported back yet, and the list will be populated in
 * one go when it does. The previous wording for `LoadingModel` said "steps
 * appear as soon as it does", which describes a live tail this build does not
 * have, and sent the user back to a screen that stayed empty.
 */
internal fun traceEmptyMessage(runState: RunState?): String = when (runState) {
    null, RunState.Idle -> "No trace yet. Send a task to see what the loop did."

    RunState.LoadingModel ->
        "No trace yet. The model is still loading, and the loop has not " +
            "recorded anything. The run reports its steps when it finishes."

    RunState.Running ->
        "No trace yet. The run is still going; it reports its steps when it " +
            "reaches an answer, an approval prompt, or a stop."

    is RunState.AwaitingApproval ->
        "The tool is waiting for your approval. The steps from before the " +
            "prompt are here; the rest appear when the run reports back."

    is RunState.Finished -> when (val outcome = runState.outcome) {
        is RunOutcome.Answer ->
            "That run finished without recording any steps."

        is RunOutcome.Cancelled ->
            "You stopped that run before it recorded a step."

        RunOutcome.StepLimitReached ->
            "That run used all of its steps without recording a trace."

        is RunOutcome.Failed ->
            // Naming the reason is the entire value of this screen. A generic
            // "no trace" here would send a developer hunting for a bug that the
            // transcript has already explained.
            "That run failed before recording a step: " +
                safeFailureReason(outcome.reason)
    }
}

@Composable
private fun TraceEmptyState(runState: RunState?, modifier: Modifier) {
    Text(
        text = traceEmptyMessage(runState),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(16.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TraceScreen(
    trace: List<StepTrace>,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** Forwarded to [TraceView] so the empty state can name the run phase. */
    runState: RunState? = null,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Trace") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        // Deliberately a Column, not another LazyColumn: TraceView owns one, and
        // nesting two same-direction scrollables throws at composition time
        // because the inner one receives infinite height constraints.
        Column(Modifier.fillMaxSize().padding(padding)) {
            TraceView(trace, runState = runState)
        }
    }
}

/** Counts and timings across the whole run. Cheap, and answers "was it slow?". */
@Composable
private fun TraceSummary(trace: List<StepTrace>) {
    // Only TOOL_CALL entries are tool calls. The previous version counted every
    // `!success` row, and a MALFORMED generation is recorded with
    // `success = false` — so a run whose model failed to emit a parseable
    // action four times reported "0 ok / 4 failed" *tools* when it had made no
    // tool call at all. A count that misattributes a generation failure to the
    // phone is worse than no count.
    val okCalls = trace.count { it.kind == StepTrace.Kind.TOOL_CALL && it.success }
    val failedCalls = trace.count { it.kind == StepTrace.Kind.TOOL_CALL && !it.success }
    val malformed = trace.count { it.kind == StepTrace.Kind.MALFORMED }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SummaryCell("steps", trace.maxOf { it.step }.toString())
        SummaryCell("tools", "$okCalls ok / $failedCalls failed")
        if (malformed > 0) {
            // Broken out rather than folded into the tool count, because it is a
            // different failure with a different fix: the model emitted something
            // the parser would not accept, which is a prompt or grammar problem,
            // not a phone problem.
            SummaryCell("unparseable", malformed.toString())
        }
        SummaryCell("total", formatMs(trace.sumOf { it.durationMs }))
    }
}

@Composable
private fun SummaryCell(label: String, value: String) {
    Column {
        Text(
            text = value,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StepHeader(
    step: Int,
    rows: List<StepTrace>,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val failed = rows.any { !it.success }
    // One spoken sentence for the whole header. Without this, TalkBack reads the
    // three Text children and the chevron's contentDescription separately, so
    // the user hears "Step 3, 4 events, FAILED, Collapse step 3" with no pause
    // and no indication that the row is the thing you tap.
    val description = buildString {
        append("Step $step, ${rows.size} events")
        if (failed) append(", contains a failure")
        append(if (expanded) ", expanded" else ", collapsed")
        append(". Double tap to ")
        append(if (expanded) "collapse" else "expand")
        append(".")
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 48dp: the platform minimum touch target. The row is only ~40dp of
            // text and padding, so without this it is a tappable target a
            // motor-impaired user reliably misses.
            .heightIn(min = 48.dp)
            .clickable(onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .semantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Step $step",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "  ${rows.size} events",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (failed) {
            Text(
                text = "FAILED",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.Bold,
                // Colour alone is not an accessible signal; "FAILED" is, and
                // spelling it out here also puts it in the header description.
                modifier = Modifier.semantics { stateDescription = "Contains a failure" },
            )
        }
        Icon(
            imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            // Null: the row's own contentDescription already says "expanded" or
            // "collapsed". Two overlapping descriptions make TalkBack stutter.
            contentDescription = null,
        )
    }
}

/** One trace line: kind badge, detail, duration, success. */
@Composable
private fun TraceRow(row: StepTrace) {
    val tint = if (row.success) kindTint(row.kind) else MaterialTheme.colorScheme.error
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = row.kind.name,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = tint,
                modifier = Modifier
                    .background(tint.copy(alpha = 0.12f), MaterialTheme.shapes.extraSmall)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
            if (row.durationMs > 0) {
                Text(
                    text = formatMs(row.durationMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
        Text(
            text = row.detail,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun kindTint(kind: StepTrace.Kind): Color = when (kind) {
    StepTrace.Kind.GENERATION -> MaterialTheme.colorScheme.primary
    StepTrace.Kind.TOOL_CALL -> MaterialTheme.colorScheme.tertiary
    StepTrace.Kind.OBSERVATION -> MaterialTheme.colorScheme.secondary
    StepTrace.Kind.MALFORMED -> MaterialTheme.colorScheme.error
    StepTrace.Kind.COMPACTION -> MaterialTheme.colorScheme.outline
}

/** "—" for zero, "340ms", "1.2s". Trace rows get read at a glance. */
private fun formatMs(ms: Long): String = when {
    ms <= 0 -> "—"
    ms < 1000 -> "${ms}ms"
    else -> "%.1fs".format(ms / 1000.0)
}
