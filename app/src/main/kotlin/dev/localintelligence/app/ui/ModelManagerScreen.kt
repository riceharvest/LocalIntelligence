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

    // Set after a "Scan storage" tap.
    //
    // WHY THIS EXISTS: `onScanLocal` is a `suspend () -> Unit`, because the
    // loop over the models directory lives in `MainActivity` and that file is
    // not this screen's to change. So this screen genuinely cannot know how
    // many files the scan adopted — which means it must not claim a number.
    // A tap that finds nothing used to produce no signal at all: the spinner
    // stopped and the screen was exactly as it was, so "I scanned and there is
    // nothing here" and "the button did nothing" looked identical. The notice
    // says what the button did and points at the list, which is all that is
    // known, and the empty state underneath it does the rest.
    var scanNotice by remember { mutableStateOf<String?>(null) }

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
        scanNotice = null
        busy = true
        scope.launch {
            // WHY THE CONTAINER IS SNIFFED HERE, BEFORE `onImport`:
            //
            // The picker accepts `*/*` (see the FAB below) because SAF does not
            // reliably tag a `.gguf`, and a too-narrow filter makes a model look
            // unimportable. The same filter therefore lets a `.litertlm` through
            // as well — and `onImport` reads a **GGUF** header, so a LiteRT-LM
            // FlatBuffer cannot be imported by any route this screen has. It
            // used to be handed straight to `onImport`, which threw
            // `GgufParseException(NOT_A_GGUF_FILE)`, and
            // `describeLoadFailure` has no branch for that type, so the user
            // read: "It may be a format this app cannot read, or it may be
            // damaged - try importing it again."
            //
            // That sentence is the specific failure this closes. It is not
            // *wrong* — a LiteRT-LM container is not something `onImport` can
            // read — but it is useless, and it is worse than useless because it
            // points at a re-download that will fail identically every time.
            // The app does have a LiteRT-LM backend. What it does not have is
            // any way to hand this file to it, and the reason is structural:
            // `LiteRtLmModelSource.toFile` refuses every `content://` uri,
            // because LiteRT-LM opens its model by filesystem path and has no
            // descriptor entry point. A document picked from Files is by
            // definition a descriptor, so this picker can never reach that
            // backend no matter which filter is used.
            //
            // So the file is classified and the specific reason is given. GGUF
            // and "neither" both fall through to `onImport` unchanged, so the
            // GGUF path and the genuinely-damaged-file path behave exactly as
            // before.
            when (val container = withContext(Dispatchers.IO) { sniffContainer(context, uri) }) {
                ModelContainer.LITERTLM -> importError = litertlmViaPickerRefusal(context, uri)
                ModelContainer.GGUF, ModelContainer.UNKNOWN ->
                    // `onImport` performs the header read and reports its own
                    // failure through this screen's error state, so a throw here
                    // is still caught: an uncaught exception in this scope would
                    // cancel the whole composition scope and leave `busy` stuck
                    // on forever.
                    runCatching { onImport(uri) }
                        .onFailure { importError = describeLoadFailure(it) }
            }
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
                                importError = null
                                scope.launch {
                                    runCatching { scan() }
                                        .onFailure { importError = describeLoadFailure(it) }
                                        .onSuccess {
                                            scanNotice = "Scanned this app's own storage for " +
                                                "GGUF files. Anything found is now in the " +
                                                "list."
                                        }
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
                    // Guarded rather than disabled: `FloatingActionButton` has
                    // no `enabled` parameter, and a second picker launched on top
                    // of a running header read gives two imports racing over
                    // the same list.
                    if (busy) return@FloatingActionButton
                    // "application/octet-stream" plus a wildcard: SAF does not
                    // reliably tag .gguf, and a filter that is too narrow makes
                    // the model look unimportable.
                    picker.launch(arrayOf("application/octet-stream", "*/*"))
                },
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Import a model file")
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

            scanNotice?.let { notice ->
                Text(
                    text = notice,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            if (models.isEmpty() && !busy && importError == null) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        text = "No models yet. Import a GGUF file to run the agent " +
                            "on-device — nothing is sent anywhere.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // A `.litertlm` is the second format this app has a backend
                    // for, and a user holding one has no way to discover that.
                    // Named here, with the actual reason rather than a promise:
                    // the picker cannot reach that backend (LiteRT-LM needs a
                    // filesystem path, not a document descriptor), and the
                    // bundle has to be in this app's own storage, where the
                    // scan below only looks for `.gguf`. That is a gap in the
                    // app, not something the user's file did wrong, and saying
                    // so is the difference between a bug report and a dead end.
                    Text(
                        text = "Have a .litertlm (LiteRT-LM) model? This app " +
                            "cannot import one from Files — LiteRT-LM needs a " +
                            "real file path, not a document picker. It is also " +
                            "not fetched by Download, which lists GGUFs only. " +
                            "Nothing is wrong with your file: the app has no " +
                            "route for it yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    // The hand-pushed case, named. A model can reach a phone
                    // over USB, out of a backup, or from the Hub download, and
                    // none of those go through the file picker. Without this
                    // the only visible route to a model is a picker the user
                    // may not know they have, and the screen reads as broken
                    // for a phone that already has a perfectly good GGUF on
                    // it. The Scan storage button above is the fix; this line
                    // says so.
                    if (onScanLocal != null) {
                        Text(
                            text = "Copied a .gguf onto this phone with a cable, or " +
                                "downloaded one already? Scan storage finds models " +
                                "the app can already see.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
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
    // WHY THIS IS `DEFAULT_CONTEXT_LENGTH` AND NOT THE MODEL'S TRAINED ONE:
    // it is the context the app will actually allocate. `AppContainer.loadModel`
    // gates on `ModelImporter.DEFAULT_CONTEXT_LENGTH` and the backend creates
    // the KV cache at that size, so that is the only number whose RAM figure
    // describes the load.
    //
    // This used to be `model.trainedContextLength ?: DEFAULT`. A 4B model
    // trained at 32K therefore rendered "Context 32768", a KV cache eight
    // times larger than the real one, and — because `fitsOnDevice` was asked
    // about the same 32K — a flat "This model does not fit in this device's
    // usable RAM. It will not load" for a model the app loads at 4K without
    // complaint. A refusal with a real-looking number attached, and false.
    // Showing the trained length is honest *as a fact about the file*, which
    // is why it is still shown — as its own line, labelled, below.
    val loadContext = ModelImporter.DEFAULT_CONTEXT_LENGTH
    val totalBytes = model.estimate.totalBytes(loadContext)
    val fits = model.fitsOnDevice(loadContext)

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

            // "On disk" is the only number on this card that was measured: it
            // is `File.length()` on the file itself. Everything below it is
            // arithmetic on that number plus the header, and the card says so
            // once, here, instead of leaving four confident figures looking
            // equally solid.
            FactLine("On disk (measured)", formatBytes(model.fileSizeBytes))
            FactLine("Weights, estimated", formatBytes(model.estimate.weightBytes))
            FactLine("RAM at load (estimated)", formatBytes(totalBytes))
            FactLine(
                "  of which KV cache",
                formatBytes(model.estimate.kvBytes(loadContext)),
            )
            FactLine("Context at load", "$loadContext tokens")
            model.trainedContextLength?.let { trained ->
                // The trained length is a property of the file, and it is NOT
                // what this app will use. Shown because it is genuinely useful
                // to know the model is capable of more, labelled so that it is
                // never read as the size of the cache about to be allocated.
                FactLine("Model's own max context", "$trained tokens (not used)")
            }
            // The disclaimer the numbers above need. There is no measured
            // figure anywhere in this app: nothing has been profiled on a
            // phone, so every RAM number here is arithmetic on the file size
            // and the header. Saying so once per row is the difference
            // between a number the user can reason about and one they have to
            // take on faith — and the load gate uses the same arithmetic, so
            // the row and the gate agree.
            Text(
                text = "RAM figures are calculated from the file size and the model's " +
                    "header. They are not measured on this phone.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            text = "This model will not load on this phone. It needs " +
                                "about ${formatBytes(totalBytes)} and this device has " +
                                "${formatBytes(RamEstimate.usableDeviceBytes())} usable " +
                                "for the app. The load gate refuses it rather than " +
                                "letting the system kill the app part-way through a task.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        // The previous version told the user to "Lower the
                        // context to 2048 or below" and offered "context could
                        // go to 16384" as headroom. There is no context control
                        // anywhere in this app: every load uses the fixed
                        // 4096 above. Both sentences named a lever the user
                        // does not have, so a refused model read as a
                        // settings problem instead of the one thing it is —
                        // too big for this phone, and the fix is a smaller
                        // model. The one real lever is named instead.
                        Text(
                            text = "The only way to make this phone run something is a " +
                                "smaller model. A lower quantization of the same " +
                                "family is usually the cheapest way there.",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
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

/**
 * The two container formats this app runs, decided by the file's first bytes.
 *
 * ## Why content, not the extension
 *
 * Because the extension is exactly the thing that is unreliable here. SAF does
 * not register a MIME type for either format, so a picker filter cannot be
 * written that is both correct and complete, and a file that was renamed or
 * sideloaded is precisely the case where the name lies. The bytes do not:
 *
 *  - **GGUF** — the container declares itself with the four ASCII bytes `GGUF`
 *    at offset 0, before the version field. That is why `GgufParser` in
 *    `:core` checks the same position.
 *  - **LiteRT-LM** — a FlatBuffer whose identifier is `LITERTLM`. It is matched
 *    anywhere in the probe window rather than at a hard-coded offset, because
 *    a FlatBuffer header is a 4-byte root offset followed by an optional 4-byte
 *    file identifier, and `liblitertlm_jni.so` reports its own failure as
 *    `Invalid magic number. Expected 'LITERTLM', got '`. Reading a window
 *    instead of one position costs nothing and does not depend on an alignment
 *    detail nobody here has verified.
 *
 * ## Why this duplicates `ModelBackendRouter.sniff`
 *
 * It has to: the router's `sniff` is `private` in `:android` and this is `:app`.
 * If either file changes, both must change together; the constants below are
 * the same ones `ModelBackendRouter` and `LiteRtLmModelSource` use, read from
 * the shipped 0.13.1 `liblitertlm_jni.so` and from the GGUF spec.
 *
 * A false negative here is safe: [ModelContainer.UNKNOWN] falls through to
 * `onImport` exactly as before, and the GGUF parser is the authority. A false
 * *positive* is the one that would hurt, which is why GGUF is tested at offset
 * 0 exactly and LITERTLM only at the two offsets a FlatBuffer header can put
 * it.
 */
internal enum class ModelContainer { GGUF, LITERTLM, UNKNOWN }

/**
 * How many leading bytes to read.
 *
 * ## Why 16, and not 8
 *
 * Because the LiteRT-LM identifier is **eight** ASCII bytes and a FlatBuffer
 * header is a 4-byte root offset followed by an *optional* 4-byte file
 * identifier, so the identifier can start at byte 0 or byte 4 — and reading 8
 * bytes cannot see all 8 of it in the second case. An 8-byte window searched for
 * an 8-byte needle only ever matches at offset 0.
 *
 * 16 bytes covers both offsets and leaves room for the 4-byte alignment padding
 * some FlatBuffer writers emit. `ModelBackendRouter.sniff` in `:android` uses the
 * same 16, so the two agree.
 */
private const val MAGIC_PROBE_BYTES = 16

private val GGUF_MAGIC = "GGUF".toByteArray(Charsets.US_ASCII)
private val LITERTLM_MAGIC = "LITERTLM".toByteArray(Charsets.US_ASCII)

/** Where a FlatBuffer may carry its 8-byte file identifier. */
private val LITERTLM_OFFSETS = intArrayOf(0, 4)

/**
 * Classifies [uri] by its first bytes. Never throws.
 *
 * An unreadable or empty document is [ModelContainer.UNKNOWN], which the caller
 * treats as "hand it to the GGUF importer and let the real parser answer" —
 * this is a routing hint, not a verdict, and it must not become a second
 * authority that can refuse a file the real parser would have accepted.
 */
internal fun sniffContainer(context: Context, uri: Uri): ModelContainer {
    val head = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(MAGIC_PROBE_BYTES)
            // A short read is normal and not an error: `read` returns what it
            // got, and a 3-byte file correctly fails the magic comparison below.
            var read = 0
            while (read < buffer.size) {
                val n = input.read(buffer, read, buffer.size - read)
                if (n <= 0) break
                read += n
            }
            // `copyOf(read)` and not `take(read)`: on a `ByteArray`, `take`
            // resolves to the stdlib's collection operator and returns a
            // `List<Byte>`, which has no byte-array comparison. Copying the
            // prefix is also the honest thing — the array is a private 8-byte
            // buffer and this is its only escape.
            buffer.copyOf(read)
        }
    }.getOrNull() ?: return ModelContainer.UNKNOWN

    return when {
        head.size >= GGUF_MAGIC.size && head.startsWithAt0(GGUF_MAGIC) -> ModelContainer.GGUF
        LITERTLM_OFFSETS.any { head.matchesAt(it, LITERTLM_MAGIC) } -> ModelContainer.LITERTLM
        else -> ModelContainer.UNKNOWN
    }
}

/**
 * Why a `.litertlm` picked from Files cannot be used, in the order the user
 * would hit the walls.
 *
 * Every clause below is a verified fact about this build, not a forecast:
 *
 *  - `LiteRtLmModelSource.toFile` refuses a `content://` uri outright,
 *    because LiteRT-LM opens its model by filesystem path and has no
 *    descriptor entry point in its Kotlin API. So the picker — whose whole
 *    output is a descriptor — cannot reach that backend at all.
 *  - `litertlm-android:0.13.1` ships exactly three native libraries
 *    (`liblitertlm_jni.so`, `libLiteRt.so`, `libLiteRtClGlAccelerator.so`).
 *    **No NPU delegate is among them**, and `Backend$GOOGLE_TENSOR` is not in
 *    the published artifact, so on a stock APK the NPU is unavailable on every
 *    phone. The GPU tier is **OpenCL**, not Vulkan.
 *  - **No `.litertlm` has ever been initialised in this project.** There is no
 *    device, no model file and no measurement, so nothing here promises what a
 *    working one would do.
 *
 * The last clause is the one a user needs: a re-pick will not help, and
 * neither will renaming the file. The route that does work today is putting the
 * bundle in the app's own `filesDir/models`, which is what "Scan storage"
 * looks for — and `MainActivity`'s scan filters on `.gguf`, so that route is
 * itself still a gap. Named as one, because a user who has just been told
 * "no" deserves to know it is the app's gap and not their file's.
 */
private fun litertlmViaPickerRefusal(context: Context, uri: Uri): String {
    val name = displayNameOf(context, uri) ?: uri.lastPathSegment ?: "this file"
    return "\"$name\" is a LiteRT-LM model (.litertlm), not a GGUF. " +
        "This app has a LiteRT-LM backend, but it cannot use a file picked " +
        "this way: LiteRT-LM opens models by filesystem path and has no way to " +
        "read a document from the Files app. Picking it again, or renaming it, " +
        "will not change that. " +
        "It is also worth knowing before you try: litertlm-android 0.13.1 " +
        "ships no NPU library and no GOOGLE_TENSOR backend, and its GPU is " +
        "OpenCL, not Vulkan — so on a stock build this would run on CPU. " +
        "No .litertlm has ever been loaded in this project, so there is no " +
        "measured speed to expect."
}

private fun ByteArray.startsWithAt0(prefix: ByteArray): Boolean =
    matchesAt(0, prefix)

/**
 * Whether [prefix] begins at [offset].
 *
 * Bounds are checked rather than assumed, because the probe deliberately reads
 * a fixed window and a 6-byte JPEG must fall through to [ModelContainer.UNKNOWN]
 * instead of throwing on an index.
 */
private fun ByteArray.matchesAt(offset: Int, prefix: ByteArray): Boolean {
    if (offset < 0 || offset + prefix.size > size) return false
    for (i in prefix.indices) if (this[offset + i] != prefix[i]) return false
    return true
}

private fun formatParams(count: Long): String = when {
    count >= 1_000_000_000 -> "%.1fB".format(count / 1_000_000_000.0)
    count >= 1_000_000 -> "%.0fM".format(count / 1_000_000.0)
    else -> count.toString()
}

private fun displayNameOf(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
    }
}.getOrNull()
