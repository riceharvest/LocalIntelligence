package dev.localintelligence.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.localintelligence.app.RunState
import dev.localintelligence.core.tool.ToolRisk

/**
 * The approval gate. Fires on `AgentResult.AwaitingConfirmation`, which the
 * runtime — not the model, not the tool — raises because
 * [dev.localintelligence.core.tool.ToolRisk.requiresConfirmation] is true.
 *
 * Four things on screen, and the order is the argument:
 *
 *  1. **What** — the tool name, in monospace, because it is a machine name and
 *     the user is being asked to authorise a machine action.
 *  2. **Why it stopped** — the human-readable risk, derived from the enum.
 *     [ToolRisk.DESTRUCTIVE], [ToolRisk.EXTERNAL_COMMUNICATION] and
 *     [ToolRisk.NETWORK_EGRESS] are the only three that reach this dialog, and
 *     they are genuinely different: one cannot be undone, one hands text to a
 *     third party, and one receives text FROM a third party. Collapsing them
 *     into "are you sure?" would hide the difference that decides the answer —
 *     and in the fetch case would send the user looking at the wrong field.
 *  3. **Exactly what** — the arguments as formatted JSON, verbatim, never
 *     summarised. "Delete the file" and "delete *these eleven* files" are the
 *     same tool call and completely different decisions.
 *  4. **The buttons** — Approve and Cancel, with Cancel the negative action.
 *
 * There is no "always allow". Every approval is one call, because a sticky grant
 * is a policy decision and policy lives in the runtime.
 */
@Composable
fun ConfirmationDialog(
    pending: RunState.AwaitingApproval,
    onApprove: () -> Unit,
    onDecline: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDecline,
        // No dismiss-on-tap-away ambiguity: tapping outside means no.
        title = { Text("Allow this action?") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = pending.toolName,
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    text = pending.description,
                    style = MaterialTheme.typography.bodyMedium,
                )
                RiskBanner(pending.risk)
                ArgumentsBlock(pending.arguments)
            }
        },
        confirmButton = {
            TextButton(onClick = onApprove) { Text("Approve") }
        },
        dismissButton = {
            TextButton(onClick = onDecline) { Text("Cancel") }
        },
    )
}

/**
 * The derived risk, in words.
 *
 * `requiresConfirmation` is computed from the enum in `:core`, so a tool cannot
 * lie about needing permission and the UI cannot invent a different threshold.
 * Anything that is not one of the two gated values is a bug upstream, and says so
 * rather than rendering a reassuring green banner.
 */
@Composable
private fun RiskBanner(risk: ToolRisk) {
    val (label, explanation) = when (risk) {
        ToolRisk.DESTRUCTIVE ->
            "Destructive — this cannot be undone." to
                "Whatever this changes, it is gone afterwards. There is no undo."

        ToolRisk.EXTERNAL_COMMUNICATION ->
            "Sends to someone else — a person or a service will see this." to
                "This leaves your device. Check the recipient and the content."

        // Distinct wording from EXTERNAL_COMMUNICATION because the user's risk
        // is different, and both halves of it belong on screen. For a message
        // the danger is what gets SENT. For a fetch nothing is composed: the
        // request reveals where the user is asking about, and the reply is
        // somebody else's text that the model will read. A banner written for
        // sending would make the user check the wrong thing.
        ToolRisk.NETWORK_EGRESS ->
            "Fetches a web page — this contacts a server and reads what it says." to
                "The server sees this request, and whatever it sends back is " +
                    "someone else's text, not instructions to you. Check the " +
                    "address above: only approve a site you recognise."

        else ->
            "Needs approval" to
                "This tool is not one of the gated risk classes, which should not happen."
    }
    val isExpected = risk.requiresConfirmation

    Surface(
        color = if (isExpected) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = explanation,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** The arguments, verbatim, horizontally scrollable so nothing is truncated. */
@Composable
private fun ArgumentsBlock(arguments: String) {
    Column {
        Text(
            text = "Arguments",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
            shape = MaterialTheme.shapes.extraSmall,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                Text(
                    text = arguments,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(10.dp),
                )
            }
        }
    }
}
