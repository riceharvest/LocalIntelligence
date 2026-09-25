package dev.localintelligence.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.localintelligence.core.agent.StepTrace

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
) {
    if (trace.isEmpty()) {
        Text(
            text = "No trace yet. Send a task to see what the loop did.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier.padding(16.dp),
        )
        return
    }

    val steps = remember(trace) { trace.groupBy { it.step }.toSortedMap() }
    var expanded by remember(trace) { mutableStateOf(emptySet<Int>()) }

    Column(modifier.fillMaxWidth()) {
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
 * App bar and back affordance around [TraceView].
 *
 * Split out so the trace rendering is a pure function of `List<StepTrace>` and
 * can be reasoned about without a navigation graph in the way.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TraceScreen(
    trace: List<StepTrace>,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
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
            TraceView(trace)
        }
    }
}

/** Counts and timings across the whole run. Cheap, and answers "was it slow?". */
@Composable
private fun TraceSummary(trace: List<StepTrace>) {
    val okCalls = trace.count { it.kind == StepTrace.Kind.TOOL_CALL && it.success }
    val failures = trace.count { !it.success }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SummaryCell("steps", trace.maxOf { it.step }.toString())
        SummaryCell("tools", "$okCalls ok / $failures failed")
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 10.dp),
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
            )
        }
        Icon(
            imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            contentDescription = if (expanded) "Collapse step $step" else "Expand step $step",
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
