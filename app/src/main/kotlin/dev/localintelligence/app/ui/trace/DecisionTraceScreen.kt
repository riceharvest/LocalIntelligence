package dev.localintelligence.app.ui.trace

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.localintelligence.core.trace.BudgetVerdict
import dev.localintelligence.core.trace.CallCapture
import dev.localintelligence.core.trace.ContextCapture
import dev.localintelligence.core.trace.DecisionLine
import dev.localintelligence.core.trace.EventType
import dev.localintelligence.core.trace.GenerationCapture
import dev.localintelligence.core.trace.ParseCapture
import dev.localintelligence.core.trace.SelectionReport
import dev.localintelligence.core.trace.TracedText
import kotlinx.coroutines.launch

/**
 * Per-step decisions, in the order the loop made them.
 *
 * ## WHY THIS IS A SEPARATE SCREEN AND NOT A TAB ON [dev.localintelligence.app.ui.TraceView]
 *
 * The existing trace is a display list: prose strings built for a screen, one
 * row per event, capped at 512 characters. This is structured records with
 * fields, including everything that list cannot show - the tools the selector
 * CUT and their scores, the budget gate's per-bucket arithmetic, which drop legs
 * actually freed space, and the model's raw output next to the parse verdict.
 * Folding that into the existing list would mean re-parsing prose to recover
 * fields, which is how a debug view starts disagreeing with the run it is
 * describing.
 *
 * It is one tap away from that screen and reuses the same navigation, the same
 * styling, and the same redaction path, so it reads as part of the existing
 * debug surface rather than a parallel system.
 *
 * ## THE HONESTY RULE
 *
 * Every number here is the one the runtime used. Where the runtime did NOT
 * decide - a gate that did not run, a selector that reported no scores, text the
 * retention policy withheld - the screen says so rather than showing a blank or
 * a zero that reads like a measurement. A debug view that invents a value is
 * worse than no debug view.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DecisionTraceScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DecisionTraceViewModel = viewModel(),
) {
    val records by viewModel.records.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Decisions") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            viewModel.clear()
                        },
                        enabled = records.isNotEmpty(),
                    ) {
                        Icon(Icons.Filled.Delete, contentDescription = "Clear the trace")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            RetentionBanner(viewModel, records, context)
            error?.let { message ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ) {
                    Row(
                        Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(message, style = MaterialTheme.typography.bodyMedium)
                        IconButton(onClick = viewModel::dismissError) {
                            Icon(Icons.Filled.Delete, contentDescription = "Dismiss")
                        }
                    }
                }
            }
            HorizontalDivider()
            if (records.isEmpty()) {
                Text(
                    text = "Nothing recorded yet. Every run records its decisions " +
                        "as it goes — send a task and come back.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
                return@Column
            }
            // A Column, not a LazyColumn inside a LazyColumn. See TraceScreen.
            LazyColumn(Modifier.fillMaxSize()) {
                items(records.asReversed(), key = { it.seq }) { line ->
                    DecisionRow(line)
                    HorizontalDivider()
                }
            }
        }
    }
}

/**
 * The retention policy, stated, plus the export.
 *
 * The policy sentence is printed rather than implied because "how much of this
 * is actually being kept" is the question a user has about a feature that holds
 * their agent's decisions, and the answer differs depending on [Stats] - the
 * default keeps no text at all, and somebody reading an exported file deserves to
 * know that before it leaves the phone.
 */
@Composable
private fun RetentionBanner(
    viewModel: DecisionTraceViewModel,
    records: List<DecisionLine>,
    context: android.content.Context,
) {
    val stats = viewModel.stats
    // Observed, not read once: the capture switch has to re-render when it
    // changes the policy, and a plain getter read would leave the control
    // showing the state it just left.
    val policy by viewModel.policy.collectAsStateWithLifecycle()
    // Remembered HERE, at the top of the composable, rather than inside the
    // onClick lambda: a scope created inside a click handler is a fresh object
    // per tap, so the launched coroutine would have no stable job to cancel when
    // the screen goes away, and a share write could outlive the composition.
    val scope = rememberCoroutineScope()
    Column(
        Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stats.describe(), style = MaterialTheme.typography.bodySmall)
        Text(
            text = "Secrets are filtered before anything is written, using the same " +
                "filter as tool results. Export sends this file to an app you pick.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // The capture toggle, on the screen where the gap is visible.
        //
        // It is here rather than in a Settings screen because the default -
        // lengths only - is deliberately quiet, and the moment someone notices
        // that a prompt shows "not kept" instead of its text is the moment they
        // need this switch. Burying it in Settings would mean the commonest
        // reason to want it is also the hardest to reach.
        //
        // Turning it ON widens capture from the next emission forward and never
        // retroactively: see `DecisionTrace.policy`.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Switch(
                checked = policy.maxBodyChars > 0,
                onCheckedChange = { on -> viewModel.setBodyCapture(on) },
            )
            Text(
                text = if (policy.maxBodyChars > 0) {
                    "Keeping text, up to ${policy.maxBodyChars} chars per field"
                } else {
                    "Keeping lengths only — no message or page text is retained"
                },
                style = MaterialTheme.typography.labelMedium,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    // The write is IO and the share sheet is a UI act, so the two
                    // are sequenced in the coroutine rather than done inline.
                    scope.launch {
                        viewModel.buildShareIntent()?.let { intent ->
                            context.startActivity(
                                Intent.createChooser(intent, "Share decision trace"),
                            )
                        }
                    }
                },
                enabled = records.isNotEmpty(),
            ) {
                Icon(Icons.Filled.Share, contentDescription = null)
                Text("  Export", style = MaterialTheme.typography.labelLarge)
            }
            OutlinedButton(
                onClick = viewModel::clear,
                enabled = records.isNotEmpty(),
            ) {
                Text("Clear", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun DecisionRow(line: DecisionLine) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = line.type.name,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                color = tintFor(line),
                modifier = Modifier
                    .padding(end = 8.dp),
            )
            if (line.step > 0) {
                Text(
                    text = "step ${line.step}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        when (line.type) {
            EventType.RUN -> line.run?.let { RunBody(it.phase, it.task, it.modelId) }
            // A composable cannot be passed as a plain function reference, so
            // each arm is an explicit lambda rather than `::SelectionBody`.
            EventType.SELECTION -> line.selection?.let { SelectionBody(it) }
            EventType.BUDGET -> line.budget?.let { BudgetBody(it) }
            EventType.PROMPT -> line.prompt?.let { prompt ->
                Text(
                    "${prompt.messages.size} messages, ${prompt.totalChars} chars, " +
                        "~${prompt.estimatedTokens} tokens, grammar ${prompt.grammarChars} chars",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
                prompt.messages.forEach { message ->
                    Text(
                        "  ${message.role}: ${message.text.chars} chars" +
                            bodySuffix(message.text),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            EventType.GENERATION -> line.generation?.let { GenerationBody(it) }
            EventType.PARSE -> line.parse?.let { ParseBody(it) }
            EventType.CALL -> line.call?.let { CallBody(it) }
            EventType.CONTEXT -> line.context?.let { ContextBody(it) }
        }
    }
}

@Composable
private fun RunBody(phase: String, task: String, modelId: String) {
    Text("run $phase", style = MaterialTheme.typography.bodySmall)
    if (modelId.isNotEmpty()) {
        Text(
            "model $modelId",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (task.isNotEmpty()) {
        Text(
            "task: $task",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
        )
    }
}

/**
 * What the selector chose, and — the point of this row — what it did not.
 *
 * The unselected list is rendered as prominently as the selected one because a
 * tool that is not in this list cannot be spoken by the grammar at all. A reader
 * scanning only the chosen names would conclude the model declined the others.
 */
@Composable
private fun SelectionBody(report: SelectionReport) {
    Text(
        "${report.strategy}: ${report.selected.size} of ${report.availableCount} offered " +
            "(max ${report.maxTools})",
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
    )
    if (report.fellBack) {
        Text(
            "selector did not rank: ${report.fallbackReason}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
    if (report.selected.isNotEmpty()) {
        Text(
            "offered: ${report.selected.joinToString(", ")}",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
        )
    }
    // Named by the selector, absent from the registry, therefore never in the
    // grammar. Shown in the error colour because it means the selector and the
    // registry disagree, which is a defect and not a run state.
    if (report.unmatched.isNotEmpty()) {
        Text(
            "named by the selector but not registered, so never offered: " +
                report.unmatched.joinToString(", "),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.error,
        )
    }
    report.unselected.take(MAX_LISTED_UNSELECTED).forEach { tool ->
        Text(
            "not offered: ${tool.name} — ${tool.reason}",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    val hidden = report.unselected.size - MAX_LISTED_UNSELECTED
    if (hidden > 0) {
        Text(
            "and $hidden more not offered",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The gate's arithmetic, including the legs it could not perform.
 *
 * A leg with `applied = false` is the interesting one: it is a drop the gate
 * asked for and did not get, and a verdict that relied on it is a verdict the
 * trace now makes you look at.
 */
@Composable
private fun BudgetBody(verdict: BudgetVerdict) {
    Text(
        "${verdict.verdict} (${verdict.phase}) — ${verdict.currentTokens} now, " +
            "+${verdict.addedTokens} step, ${verdict.projectedTokens}/${verdict.limit} " +
            "projected, ${verdict.headroom} headroom",
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        color = if (verdict.verdict == "FITS") {
            MaterialTheme.colorScheme.onSurface
        } else {
            MaterialTheme.colorScheme.error
        },
    )
    if (verdict.enforcement != "active") {
        Text(
            verdict.enforcement,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
    if (verdict.buckets.isNotEmpty()) {
        Text(
            verdict.buckets.entries.joinToString("  ") { "${it.key.lowercase()} ${it.value}" },
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    verdict.dropLegs.forEach { leg ->
        Text(
            if (leg.applied) {
                "dropped ${leg.component.lowercase()} (${leg.requestedTokens} tok)"
            } else {
                "COULD NOT drop ${leg.component.lowercase()} " +
                    "(claimed ${leg.requestedTokens} tok) — ${leg.note}"
            },
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = if (leg.applied) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.error
            },
        )
    }
    if (verdict.unfixable) {
        Text(
            "nothing droppable can help; the step alone is over the window",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun GenerationBody(capture: GenerationCapture) {
    Text(
        "${capture.stopReason} — ${capture.completionTokens} out, " +
            "${capture.promptTokens} in, prefill ${capture.prefillMs}ms, " +
            "decode ${capture.decodeMs}ms",
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
    )
    bodySuffix(capture.raw, "raw output")
}

@Composable
private fun ParseBody(capture: ParseCapture) {
    Text(
        if (capture.ok) capture.action else "${capture.action}: ${capture.reason}",
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        color = if (capture.ok) {
            MaterialTheme.colorScheme.onSurface
        } else {
            MaterialTheme.colorScheme.error
        },
    )
    if (capture.toolName.isNotEmpty()) {
        Text(
            "  → ${capture.toolName}${bodySuffix(capture.args, " args")}",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
        )
    }
    bodySuffix(capture.respondText, "answer")
}

@Composable
private fun CallBody(capture: CallCapture) {
    Text(
        buildString {
            append(if (capture.dispatched) "ran" else "not run")
            append(" ${capture.name}")
            if (capture.policy.isNotEmpty()) append(" [${capture.policy}]")
            if (capture.risk.isNotEmpty()) append(" risk=${capture.risk}")
            if (capture.durationMs > 0) append(" ${capture.durationMs}ms")
        },
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        color = if (capture.success) {
            MaterialTheme.colorScheme.onSurface
        } else {
            MaterialTheme.colorScheme.error
        },
    )
    if (capture.refusal.isNotEmpty()) {
        Text(
            capture.refusal,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
    bodySuffix(capture.args, "args")
    bodySuffix(capture.observation, "result")
}

@Composable
private fun ContextBody(capture: ContextCapture) {
    Text(
        "${capture.kind} — ${capture.activeTokens}/${capture.limit} tokens, " +
            "window ${capture.windowMessagesBefore} → ${capture.windowMessagesAfter}, " +
            "dropped ${capture.dropped}",
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
    )
    if (capture.note.isNotEmpty()) {
        Text(
            capture.note,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * A captured string, with its length whether or not the body is kept.
 *
 * The length is ALWAYS shown, because that is the part that survives the
 * retention policy and it is enough to answer "was the prompt 200 characters or
 * 20,000". When the body is withheld the row says so, rather than showing an
 * empty string that reads as an empty prompt.
 */
@Composable
private fun bodySuffix(text: TracedText, label: String = "text") {
    if (text.chars == 0) return
    val kept = if (text.text.isNotEmpty()) " (${text.text.length} kept)" else " " +
        "(not kept — switch on text capture above to see it)"
    // Two distinct claims, because they are two distinct facts: `filtered` is
    // what THIS capture pass removed, `upstream` is what an earlier stage had
    // already removed before the trace ever saw the text. Printing only the
    // first would read as "clean" for a body that plainly says `[redacted]`.
    val filtered = when {
        text.redactions > 0 -> ", ${text.redactions} filtered here"
        text.redactedUpstream -> ", filtered before this trace"
        else -> ""
    }
    Text(
        "  $label: ${text.chars} chars$kept$filtered",
        style = MaterialTheme.typography.labelSmall,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun tintFor(line: DecisionLine): Color = when {
    line.budget?.verdict == "FITS" -> MaterialTheme.colorScheme.primary
    line.budget != null -> MaterialTheme.colorScheme.error
    line.call?.success == false -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

/**
 * How many unselected tools a row lists.
 *
 * Bounded because a 25-tool registry would otherwise put 19 identical lines on
 * screen and push the rest of the trace off it. The count of the remainder is
 * shown, so the truncation is never mistaken for a complete list — the exported
 * JSON always carries all of them.
 */
private const val MAX_LISTED_UNSELECTED = 8
