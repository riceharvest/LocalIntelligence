package dev.localintelligence.app.ui

import androidx.compose.ui.res.stringResource
import dev.localintelligence.app.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.localintelligence.app.crash.CrashReports
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Settings row for the newest crash report. It exists only while a report is on the phone, so a
 * phone that never crashed shows nothing here.
 *
 * Nothing is sent from this row by itself. Share opens a preview first, and the share sheet gets the
 * file only after the person taps Share in that preview.
 */
@Composable
fun CrashReportRow() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var previewing by remember { mutableStateOf(false) }
    var asking by remember { mutableStateOf(false) }
    // Read off the main thread: the newest report is a file on disk.
    val report by produceState<File?>(initialValue = null, refresh) {
        value = withContext(Dispatchers.IO) { CrashReports.newest(context) }
    }
    val file = report ?: return

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.share_last_crash_report), style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(R.string.a_crash_or_freeze_is_saved_on_this),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { previewing = true }) { Text(stringResource(R.string.preview_and_share)) }
                OutlinedButton(onClick = { asking = true }) { Text(stringResource(R.string.delete)) }
            }
        }
    }

    if (previewing) {
        val body by produceState<String?>(initialValue = null, file) {
            value = withContext(Dispatchers.IO) { CrashReports.preview(file) }
        }
        AlertDialog(
            onDismissRequest = { previewing = false },
            title = { Text(stringResource(R.string.crash_report)) },
            text = {
                Text(
                    body ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    previewing = false
                    CrashReports.share(context, file)
                }) { Text(stringResource(R.string.share)) }
            },
            dismissButton = { TextButton(onClick = { previewing = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    if (asking) {
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text(stringResource(R.string.delete_crash_reports)) },
            text = { Text(stringResource(R.string.every_saved_crash_report_is_removed)) },
            confirmButton = {
                TextButton(onClick = {
                    asking = false
                    scope.launch {
                        withContext(Dispatchers.IO) { CrashReports.deleteAll(context) }
                        refresh++
                    }
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { asking = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
