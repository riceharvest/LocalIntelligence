package dev.localintelligence.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.localintelligence.android.inference.ImportedModel
import dev.localintelligence.android.inference.ModelImporter
import dev.localintelligence.android.inference.RamEstimate
import dev.localintelligence.app.describeLoadFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Model management. A real feature, not a placeholder: the whole point of an
 * on-device agent is that the model lives on the phone, and the first-run
 * experience is "here is a 2 GB file, here is your RAM, here is whether it
 * fits".
 *
 * ## Why RAM is shown before anything is loaded
 *
 * `RamEstimate` in `:android` does the arithmetic and this screen only renders
 * it, because getting it wrong costs the user an OOM kill mid-conversation. The
 * two numbers that matter:
 *
 *  - **weights**, constant, ~= the file size;
 *  - **KV cache**, which scales *linearly* with context. At 3B, every doubling
 *    of context adds ~448 MiB (`RamEstimate` KDoc). Past ~16K the cache is the
 *    majority of the footprint, so "pick a context length" is a RAM decision
 *    dressed as a UI decision, and the screen says so.
 *
 * The model is loaded into **native** memory, not the JVM heap, so this does not
 * come out of `maxMemory()`. `RamEstimate.usableDeviceBytes()` deliberately
 * reads physical RAM and caps it at 6x the heap ceiling, because the number the
 * low-memory killer watches is the process footprint.
 *
 * ## Why SAF and not a copy
 *
 * `ACTION_OPEN_DOCUMENT` hands back a document the app can persist access to
 * without duplicating the file. A 3B Q4_K_M is ~1.9 GB; copying it on import
 * would double the device's storage cost, take minutes over USB, and leave two
 * copies to clean up. See `ModelImporter` for the descriptor lifetime rules.
 *
 * ## Why [onImport] suspends
 *
 * It is a `suspend` function so the screen can hold `busy` across the *real*
 * duration of the read. As a plain `(Uri) -> Unit` it returned instantly, the
 * spinner cleared before the header had even been opened, and a failed import
 * had nowhere to report to — which is how a user ends up tapping import on a
 * JPEG and getting silence.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelManagerScreen(
    models: List<ImportedModel>,
    onImport: suspend (Uri) -> Unit,
    onDelete: (ImportedModel) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Opens the HuggingFace browser.
     *
     * WHY a defaulted parameter rather than a new required one: this screen is
     * shared with the import-by-hand flow, and the two entry points are
     * additive. Making the callback required would break every other caller for
     * no reason; defaulting it keeps the existing flow compiling unchanged and
     * lets the button render only where a destination exists.
     */
    onOpenHub: (() -> Unit)? = null,
    /**
     * Imports every GGUF already sitting in the app's own storage.
     *
     * WHY THIS EXISTS: the picker and the downloader are both fine, and both
     * were useless during development because a model had been pushed onto the
     * device by hand — the app had no way to notice a file it already owned.
     * Scanning for a model the app can already see is the shortest honest path
     * from "the file is on the phone" to "the app is using it", and it is what
     * makes a pushed model usable without re-downloading 668 MB.
     */
    onScanLocal: (suspend () -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<ImportedModel?>(null) }

    // The reason the last import failed, or null.
    //
    // Previously the import ran under `runCatching { ... }.onSuccess { ... }`
    // with no `onFailure`, so picking a 40 MB JPEG produced *no* feedback at
    // all: the spinner stopped, no row appeared, no message. A user cannot
    // distinguish "that file is not a model" from "the app is broken", and
    // the second is what they will assume. Surfacing the reason is the
    // difference between a bug report and a retry.
    var importError by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        // Persist read access so the model is still there after a reboot. Without
        // this the URI stops resolving and the entry silently becomes unloadable.
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        importError = null
        busy = true
        scope.launch {
            // `onImport` performs the header read and reports its own failure
            // through this screen's error state, so a throw here is still
            // caught: an uncaught exception in this scope would cancel the
            // whole composition scope and leave `busy` stuck on forever.
            runCatching { onImport(uri) }
                .onFailure { importError = describeLoadFailure(it) }
            busy = false
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Models") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // WHY a text button and not another FAB: the FAB is
                    // "import a file you already have", and this is "go get one
                    // from the internet". Two floating buttons would collide on
                    // a phone.
                    onOpenHub?.let { open ->
                        TextButton(onClick = open) { Text("Download") }
                    }
                    // WHY next to Download rather than in the FAB: both of these
                    // are "get a model" actions, so they belong together. The FAB
                    // stays "import a file you already have somewhere else".
                    onScanLocal?.let { scan ->
                        TextButton(
                            onClick = {
                                busy = true
                                scope.launch {
                                    runCatching { scan() }
                                        .onFailure { importError = describeLoadFailure(it) }
                                    busy = false
                                }
                            },
                            enabled = !busy,
                        ) { Text("Scan storage") }
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    // "application/octet-stream" plus a wildcard: SAF does not
                    // reliably tag .gguf, and a filter that is too narrow makes
                    // the model look unimportable.
                    picker.launch(arrayOf("application/octet-stream", "*/*"))
                },
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Import a GGUF model")
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (busy) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(Modifier.padding(2.dp), strokeWidth = 2.dp)
                    Text("Reading the model header…")
                }
            }

            if (importError != null) {
                ImportErrorBanner(
                    message = importError!!,
                    onDismiss = { importError = null },
                )
            }

            if (models.isEmpty() && !busy && importError == null) {
                Text(
                    text = "No models yet. Import a GGUF file to run the agent " +
                        "on-device — nothing is sent anywhere.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(models, key = { it.uri.toString() }) { model ->
                    ModelRow(
                        model = model,
                        onDelete = { pendingDelete = model },
                    )
                }
            }
        }
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Remove ${target.displayName}?") },
            text = {
                Text(
                    "This forgets the model. The file itself is untouched — if you " +
                        "picked it from cloud storage it stays there, and you will " +
                        "have to grant access again to use it again.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(target)
                    pendingDelete = null
                }) {
                    Text("Remove")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            },
        )
    }
}

/**
 * A failed import, stated plainly and dismissible.
 *
 * Dismissible because the failure is about one file, not the screen: the user
 * should be able to try a different one without navigating away and back. A live
 * region so it is announced — otherwise the only signal is a visual change that
 * a screen-reader user has no way to detect.
 */
@Composable
private fun ImportErrorBanner(message: String, onDismiss: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Filled.Close, contentDescription = "Dismiss this error")
            }
        }
    }
}

@Composable
private fun ModelRow(model: ImportedModel, onDelete: () -> Unit) {
    val context = LocalContext.current
    val chosenContext = remember(model.uri) {
        model.trainedContextLength ?: ModelImporter.DEFAULT_CONTEXT_LENGTH
    }
    val totalBytes = model.estimate.totalBytes(chosenContext)
    val fits = model.fitsOnDevice(chosenContext)
    val maxContext = model.estimate.maxAffordableContext(RamEstimate.usableDeviceBytes())

    Card(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = model.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = listOfNotNull(
                            model.quantType,
                            model.architecture,
                            model.parameterCount?.let { "${formatParams(it)} params" },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "Remove ${model.displayName}")
                }
            }

            FactLine("On disk", formatBytes(model.fileSizeBytes))
            FactLine("Weights in RAM", formatBytes(model.estimate.weightBytes))
            FactLine("Context", "$chosenContext tokens")
            FactLine(
                "RAM at this context",
                "${formatBytes(totalBytes)}  (KV cache ${formatBytes(model.estimate.kvBytes(chosenContext))})",
            )
            if (!model.hasChatTemplate) {
                FactLine("Chat template", "missing — tool calls will be unreliable")
            }

            if (!fits) {
                Card(
                    colors = androidx.compose.material3.CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = buildString {
                            append("This model does not fit in this device's usable RAM ")
                            append("(${formatBytes(RamEstimate.usableDeviceBytes())}). ")
                            if (maxContext > 0) {
                                append("Lower the context to ${formatContextCeiling(maxContext)} or below. ")
                            }
                            append("Loading it anyway risks the system killing the app mid-task.")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            } else if (maxContext in 1 until chosenContext) {
                FactLine(
                    "Headroom",
                    "context could go to ${formatContextCeiling(maxContext)} on this device",
                )
            }

            // The display name from a content provider is often useless (a hash),
            // so offer the real one from the provider when it disagrees.
            val resolved = remember(model.uri) { displayNameOf(context, model.uri) }
            if (resolved != null && resolved != model.displayName) {
                FactLine("File", resolved)
            }
        }
    }
}

@Composable
private fun FactLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.4f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(0.6f),
        )
    }
}

/** Binary units, because that is how model sizes are quoted. */
internal fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "unknown"
    val units = listOf("B", "KiB", "MiB", "GiB", "TiB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return if (unit == 0) "${bytes} B" else "%.1f %s".format(value, units[unit])
}

private fun formatParams(count: Long): String = when {
    count >= 1_000_000_000 -> "%.1fB".format(count / 1_000_000_000.0)
    count >= 1_000_000 -> "%.0fM".format(count / 1_000_000.0)
    else -> count.toString()
}

/** Context lengths are chosen from powers of two, so round down to one. */
private fun formatContextCeiling(tokens: Int): String {
    var value = 1024
    while (value * 2 <= tokens) value *= 2
    return value.toString()
}

private fun displayNameOf(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
    }
}.getOrNull()
