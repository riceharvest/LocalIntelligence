package dev.localintelligence.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * One tool call, in the transcript. Four facts and nothing else: which tool, did
 * it work, how long, and what it said.
 *
 * ## What is deliberately not here
 *
 * **No chain-of-thought.** The runtime does not produce any — `AgentAction` is
 * `Respond | CallTool` and nothing else (`docs/architecture.md` §5) — so a
 * "thinking…" shimmer here would be a lie the UI invented. What a user sees
 * instead is the `GENERATION` row in [TraceView], which shows the actual model
 * output that produced the call, including the malformed ones.
 *
 * **No arguments by default.** They are one tap away in [TraceView]; a chat
 * transcript full of JSON arguments is unreadable and this app has to be usable
 * without ever opening the debug screen.
 *
 * **No chain-of-tools.** One card per call, in the order the loop made them.
 */
@Composable
fun ToolActivityCard(
    toolName: String,
    status: String,
    success: Boolean,
    durationMs: Long,
    modifier: Modifier = Modifier,
    /** Rendered under the status line when the user expands the card. */
    detail: String? = null,
) {
    var expanded by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(success)
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text(
                        text = toolName,
                        style = MaterialTheme.typography.labelLarge,
                        fontFamily = FontFamily.Monospace,
                    )
                    Text(
                        text = status,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                    )
                }
                DurationChip(durationMs)
                if (detail != null) {
                    IconButton(
                        onClick = { expanded = !expanded },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            contentDescription = if (expanded) "Hide detail" else "Show detail",
                        )
                    }
                }
            }

            AnimatedVisibility(visible = expanded && detail != null) {
                Text(
                    text = detail.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

/**
 * Success/fail as a colour and a shape together.
 *
 * Colour alone is not an accessible signal and a debug tool that hides failures
 * from a colourblind user is worse than no tool, so the failed dot is hollow as
 * well as red.
 */
@Composable
private fun StatusDot(success: Boolean) {
    val color: Color =
        if (success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    val shape = CircleShape
    Icon(
        imageVector = if (success) Icons.Filled.CheckCircle else Icons.Filled.Error,
        contentDescription = if (success) "succeeded" else "failed",
        tint = color,
        modifier = Modifier
            .size(16.dp)
            .background(color.copy(alpha = if (success) 0.15f else 0.25f), shape)
            .padding(2.dp)
            .semantics { contentDescription = if (success) "succeeded" else "failed" },
    )
}

/**
 * Duration, or nothing under 10ms.
 *
 * A local tool call is often sub-10ms, and a wall of "3ms" chips trains the eye
 * to ignore the column — which is exactly when a 4-second `sms.send` needs to be
 * visible. The 1 decimal under a second is deliberate: 380ms and 3840ms are very
 * different events.
 */
@Composable
private fun DurationChip(durationMs: Long) {
    if (durationMs < 10) return
    Text(
        text = if (durationMs < 1000) "${durationMs}ms" else "%.1fs".format(durationMs / 1000.0),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 6.dp),
    )
}

/** Human-readable one-liner for a tool card's status line. */
internal fun toolStatusLine(
    toolName: String,
    observation: String,
    success: Boolean,
): String = when {
    !success -> "Failed — ${observation.lineSequence().firstOrNull().orEmpty().take(120)}"
    observation.isBlank() -> "Done"
    else -> observation.lineSequence().firstOrNull().orEmpty().take(120)
}
