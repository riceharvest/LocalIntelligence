package dev.localintelligence.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.localintelligence.app.ui.ChatMessage

/**
 * One tool call and the phone's answer to it.
 *
 * ## WHY IT IS NOT A CHAT BUBBLE
 *
 * A bubble is something the user said or the model said. This is the app
 * reporting on its own work, and dressing it as dialogue would put a fabricated
 * "assistant" turn in the transcript. It is deliberately quiet: monospace,
 * smaller, and it shows the raw observation because a paraphrase here would let
 * the app claim the phone said something it did not.
 *
 * ## WHY IT IS COLLAPSIBLE, AND WHAT COLLAPSING MAY NOT HIDE
 *
 * An observation is whatever the tool returned, clipped by the runtime to the
 * budget the model was shown — which for a `files.read` or a `web.fetch` is
 * paragraphs. A run with a dozen calls therefore produces a transcript that is
 * almost entirely raw tool output, and the user's own messages and the model's
 * answers are buried in it. That is the readability problem this fixes.
 *
 * So the body collapses. What does NOT collapse is the header, and the header
 * carries every fact needed to know that the call happened and how it went:
 *
 *  - the tool name, always;
 *  - an explicit outcome word — `ok`, `failed`, `refused` — never an icon alone;
 *  - the duration, which is the only answer to "was it slow?".
 *
 * A collapsed row is therefore a complete sentence about a call that was made.
 * It is not a placeholder, and there is no state in which a row is present but
 * says nothing about what occurred.
 *
 * ## WHY FAILURES DEFAULT TO EXPANDED
 *
 * Collapsing is for the *verbose* case. A refusal and a failure are short, and
 * they are the rows a user actually needs to read, so they open by default. Only
 * a successful call with a long observation starts folded.
 */
@Composable
fun ToolStepRow(
    step: ChatMessage.ToolStep,
    modifier: Modifier = Modifier,
) {
    val refused = step.observation == null
    val outcome = when {
        refused -> "refused"
        step.success -> "ok"
        else -> "failed"
    }
    val body = when {
        refused -> step.detail
        step.success -> step.observation
        else -> "failed: ${step.observation}"
    }
    val isProblem = refused || !step.success

    // Keyed on the call's identity rather than its position, so the fold state
    // follows the call when the transcript drops rows off the front. Keyed on
    // anything that includes `expanded` itself and the tap would collapse the
    // row it just expanded.
    var expanded by rememberSaveable(step.toolName, step.args) {
        mutableStateOf(isProblem)
    }

    val duration = if (step.durationMs > 0) "${step.durationMs}ms" else null
    val header = buildString {
        append("▸ ")
        if (step.toolName.isNotBlank()) {
            append(step.toolName)
            if (step.args.isNotBlank()) append(' ').append(step.args)
        } else {
            // A refusal can arrive without a tool name; the two approval paths
            // that fail to claim a call do exactly that.
            append("tool call")
        }
        append(" · ").append(outcome)
        duration?.let { append(" · ").append(it) }
    }

    val accent = if (isProblem) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.primary
    }

    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // Fold/unfold is a control, so it is a whole-row target and
                // carries a touch target taller than the text it wraps.
                .clickable { expanded = !expanded }
                .padding(vertical = 6.dp)
                .semantics(mergeDescendants = true) {
                    contentDescription = buildString {
                        append(header)
                        append(if (expanded) ". Showing the raw result." else ". Folded.")
                        append(" Double tap to ")
                        append(if (expanded) "fold" else "expand")
                        append(".")
                    }
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = header,
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = accent,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // NOT AnimatedVisibility on a size-changing element. The body appears
        // and disappears, which re-measures every row below it, and in a
        // transcript with dozens of tool rows that re-measure is what makes a
        // long answer feel like it is thrashing under the reader's thumb.
        // Appearing in place is worth more than the animation.
        if (expanded && body.isNotBlank()) {
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = if (step.success && !refused) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                },
                modifier = Modifier.padding(start = 14.dp),
            )
        }
    }
}

/**
 * The user's answer to an approval prompt, kept in the transcript.
 *
 * Without this the decline vanished: the dialog closed, `ChatViewModel` recorded
 * it in a list no screen read, and the transcript resumed as if nothing had
 * been asked. For a destructive action that is the worst outcome available — the
 * user cannot afterwards tell whether the agent acted, and the app cannot show
 * them.
 *
 * The wording states the consequence because it is the real one: on a decline
 * `AgentController.confirmAndResume` puts "The user declined <tool>. Do not call
 * it again." into the session, so the model is told and told not to retry. On an
 * approval the staged call runs exactly as it was shown.
 */
@Composable
fun ApprovalRow(
    approval: ChatMessage.Approval,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = if (approval.approved) {
                "▸ you approved ${approval.toolName}"
            } else {
                "▸ you declined ${approval.toolName}"
            },
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
            color = if (approval.approved) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.error
            },
        )
        Text(
            text = if (approval.approved) {
                "The agent was told, and it may now run this call."
            } else {
                "The agent was told, and was told not to try it again."
            },
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 14.dp),
        )
    }
}
