package dev.localintelligence.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.localintelligence.android.report.ReplyReportSender
import dev.localintelligence.core.report.ReplyReportReason
import kotlinx.coroutines.launch

// ===========================================================================
// The "Report this reply" sheet. Reporting is in-app because Play's AI-generated
// content policy requires it: no mailto, no browser, no share sheet.
//
// WHY THE REASON IS REQUIRED AND THE REPLY IS TICKED BY DEFAULT: a report with no reason
// tells the developer nothing, so Send waits for one. The reply text is what the report is
// about, so it is sent unless the user unticks the box, and the box shows exactly what
// leaves the phone.
//
// WHY THE SHEET HOLDS ITS OWN OUTCOME: a failed send keeps the sheet open with the reason
// and a Retry, so the user does not retype a note they already wrote.
// ===========================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReplyReportSheet(
    viewModel: ChatViewModel,
    reply: String,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var reason by remember { mutableStateOf<ReplyReportReason?>(null) }
    var note by remember { mutableStateOf("") }
    var includeReply by remember { mutableStateOf(true) }
    var sending by remember { mutableStateOf(false) }
    var outcome by remember { mutableStateOf<ReplyReportSender.Outcome?>(null) }

    fun send(chosen: ReplyReportReason) {
        sending = true
        scope.launch {
            val report = viewModel.replyReport(reply, chosen, note, includeReply)
            outcome = viewModel.sendReport(report)
            sending = false
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Report this reply", style = MaterialTheme.typography.titleLarge)

            ReasonChips(selected = reason, enabled = !sending && outcome != ReplyReportSender.Outcome.Sent) {
                reason = it
            }

            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("Note (optional)") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                enabled = !sending && outcome != ReplyReportSender.Outcome.Sent,
            )

            Row(
                Modifier.fillMaxWidth().clickable(enabled = !sending) { includeReply = !includeReply },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = includeReply,
                    onCheckedChange = { includeReply = it },
                    enabled = !sending,
                )
                Text("Include the reply text", style = MaterialTheme.typography.bodyLarge)
            }

            if (includeReply) {
                // The box is what leaves the phone, so it is shown whole, not cut to a preview.
                SelectionContainer {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 180.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                            .verticalScroll(rememberScrollState())
                            .padding(12.dp),
                    ) {
                        Text(reply, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            Text(
                "Sent to the developer through Google Forms. Nothing else from your chats is sent.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            when (outcome) {
                ReplyReportSender.Outcome.Sent -> {
                    Text("Thanks. Report sent.", style = MaterialTheme.typography.bodyLarge)
                    Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Done") }
                }
                ReplyReportSender.Outcome.NotSetUp -> {
                    Text("Reporting isn't set up in this build.", style = MaterialTheme.typography.bodyLarge)
                    OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Close") }
                }
                ReplyReportSender.Outcome.Failed -> {
                    Text(
                        "Couldn't send. Check your connection.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error,
                    )
                    SendButton(label = "Retry", enabled = reason != null && !sending) { reason?.let { send(it) } }
                }
                null -> SendButton(
                    label = if (sending) "Sending..." else "Send",
                    enabled = reason != null && !sending,
                ) { reason?.let { send(it) } }
            }
        }
    }
}

@Composable
private fun SendButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text(label) }
}

/** One required choice among the four reasons, as chips that wrap on a narrow screen. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReasonChips(
    selected: ReplyReportReason?,
    enabled: Boolean,
    onPick: (ReplyReportReason) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Why are you reporting it?", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ReplyReportReason.entries.forEach { reason ->
                FilterChip(
                    selected = selected == reason,
                    onClick = { onPick(reason) },
                    label = { Text(reason.label) },
                    enabled = enabled,
                )
            }
        }
    }
}
