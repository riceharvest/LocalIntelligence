package dev.localintelligence.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import dev.localintelligence.android.cloud.RemoteChatOffer
import dev.localintelligence.android.inference.ImportedModel
import dev.localintelligence.android.inference.ModelImporter
import dev.localintelligence.android.inference.RamEstimate
import dev.localintelligence.app.ModelAvailability
import dev.localintelligence.app.describeLoadFailure
import dev.localintelligence.app.speedtest.SpeedTestButton
import dev.localintelligence.app.speedtest.SpeedTestResultBlock
import dev.localintelligence.app.speedtest.SpeedTestRunner
import dev.localintelligence.core.hub.formatBytes
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
    /**
     * Copies a picked LiteRT-LM document into the app's models directory and returns
     * the copy. LiteRT-LM opens models by path, so a document-picker descriptor cannot
     * be used directly. Arguments: the document, its display name, and a progress
     * callback `(copiedBytes, totalBytes)`.
     */
    copyLiteRtLm: suspend (Uri, String, (Long, Long) -> Unit) -> java.io.File,
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
    /** The n_ctx a model will be loaded (and priced) at. */
    contextLengthFor: (ImportedModel) -> Int = { ModelImporter.DEFAULT_CONTEXT_LENGTH },
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
    /** The in-app speed test. Null hides the button. */
    speedTest: SpeedTestRunner? = null,
    /** URI of the model that is resident, so its row gets the speed test button. */
    loadedModelUri: String? = null,
    /** The remote chat provider and model, when that option is on (plugin on and a key set). Null hides the entry. */
    remoteChatOffer: RemoteChatOffer? = null,
    /** True while the remote chat model is the one answering. */
    remoteChatActive: Boolean = false,
    /** Makes the remote chat model the resident one. */
    onUseRemoteChat: (suspend () -> Unit)? = null,
    /** Lists the model ids the remote provider offers. Null hides the list button. */
    onListRemoteModels: (suspend () -> List<String>)? = null,
    /** Saves the picked model id for the remote provider. */
    onPickRemoteModel: ((String) -> Unit)? = null,
    /** The ChatGPT entry (sign in, model picker, use). Null hides it. */
    chatGptCard: (@Composable () -> Unit)? = null,
    /**
     * Makes a local model the resident one, the way import does. Returns the outcome so a
     * model that does not fit is reported here rather than shown as a silent no-op.
     */
    onUseModel: (suspend (ImportedModel) -> ModelAvailability)? = null,
    /**
     * True in Technical mode. Simple mode shows one line per model and hides the speed
     * test; Technical keeps every figure.
     */
    technical: Boolean = false,
    /** True when the model's file lives in the app's own storage, so removing it frees the space. */
    isOwnFile: (ImportedModel) -> Boolean = { false },
    /**
     * Lists the model files in the app's own storage. Run once when the screen opens so a
     * Simple user sees every earlier download, not only the saved model.
     */
    onAutoScan: (suspend () -> Unit)? = null,
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

    // A LiteRT-LM copy in progress: its file name and (copied, total) bytes, or null.
    // The total is -1 when the provider does not report a size.
    var copying by remember { mutableStateOf<Pair<String, Pair<Long, Long>>?>(null) }

    // What the spinner row says: "Checking file…" for an import or scan, "Loading model…" for Use.
    var busyLabel by remember { mutableStateOf("Checking file…") }
    var menuOpen by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        runCatching { onAutoScan?.invoke() }
    }

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
        busyLabel = "Checking file…"
        busy = true
        scope.launch {
            // The container is sniffed before `onImport`, because `onImport` reads a
            // GGUF header and a LiteRT-LM FlatBuffer would fail that read with a
            // message that points at a re-download. The picker accepts `*/*`, so
            // both formats arrive here.
            when (val container = withContext(Dispatchers.IO) { sniffContainer(context, uri) }) {
                // A LiteRT-LM bundle is copied into the app's own models directory
                // and then imported from there, the same way a downloaded model is.
                // The copy is announced with its size, because these files are GBs.
                ModelContainer.LITERTLM -> {
                    // The name query goes to the document provider, which a cloud-backed one can answer
                    // slowly, so it runs off the main thread like the sniff above.
                    val name = withContext(Dispatchers.IO) { displayNameOf(context, uri) }
                        ?: uri.lastPathSegment ?: "model.litertlm"
                    val copied = try {
                        copyLiteRtLm(uri, name) { done, total ->
                            // Hop to the composition's dispatcher: this runs on the copy thread.
                            scope.launch { copying = name to (done to total) }
                        }
                    } catch (e: java.io.IOException) {
                        Log.w("ModelManager", "Copying $name failed", e)
                        importError = "Couldn't add \"$name\". Check there is enough free space and try again."
                        null
                    } finally {
                        copying = null
                    }
                    if (copied != null) {
                        runCatching { onImport(Uri.fromFile(copied)) }
                            .onFailure { importError = describeLoadFailure(it) }
                    }
                }
                // `onImport` performs the header read and reports its own
                // failure through this screen's error state, so a throw here
                // is still caught: an uncaught exception in this scope would
                // cancel the whole composition scope and leave `busy` stuck
                // on forever.
                ModelContainer.GGUF, ModelContainer.UNKNOWN ->
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
                    // WHY an overflow menu: the main way to get a model is the
                    // "Get a model" button below. Importing a file you already
                    // have is the rarer path, and scanning for files the app can
                    // already see is a developer path, so it is Technical only.
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "More options")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Add from a file") },
                            enabled = !busy,
                            onClick = {
                                menuOpen = false
                                // Guarded as well as disabled: a second picker on top of a
                                // running header read gives two imports racing over the list.
                                if (busy) return@DropdownMenuItem
                                // "application/octet-stream" plus a wildcard: SAF does not
                                // reliably tag .gguf, and a filter that is too narrow makes
                                // the model look unimportable.
                                picker.launch(arrayOf("application/octet-stream", "*/*"))
                            },
                        )
                        if (technical) {
                            onScanLocal?.let { scan ->
                                DropdownMenuItem(
                                    text = { Text("Find models on this phone") },
                                    enabled = !busy,
                                    onClick = {
                                        menuOpen = false
                                        busyLabel = "Checking file…"
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
                                )
                            }
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            // The empty state already has a "Download a model" button; two at once is noise.
            val emptyStateShowing = models.isEmpty() && !busy && importError == null
            onOpenHub?.takeIf { !emptyStateShowing }?.let { open ->
                ExtendedFloatingActionButton(
                    onClick = open,
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("Download a model") },
                )
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (busy && copying == null) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(Modifier.padding(2.dp), strokeWidth = 2.dp)
                    Text(busyLabel)
                }
            }

            copying?.let { (_, progress) ->
                val (done, total) = progress
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = if (total > 0) {
                            "Adding model… ${formatBytes(done)} of ${formatBytes(total)}"
                        } else {
                            "Adding model… ${formatBytes(done)}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (total > 0) {
                        LinearProgressIndicator(
                            progress = { (done.toFloat() / total).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
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
                // No top padding of its own below: the list under it adds none
                // while it is empty, so the gap is 16 dp once and not twice.
                Column(
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = if (technical) {
                            "No models yet. Import a GGUF file to run the agent " +
                                "on-device — nothing is sent anywhere."
                        } else {
                            "No models yet. Download one to chat privately on your phone."
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (onOpenHub != null) {
                        Button(onClick = onOpenHub) { Text("Download a model") }
                    }
                    if (technical) {
                        // A `.litertlm` is the second format this app has a backend for.
                        // The picker copies it into the app's storage first, so it takes
                        // as much free space as the file is large.
                        Text(
                            text = "Have a .litertlm (LiteRT-LM) model? Import it from the " +
                                "menu like a GGUF. The app copies it into its own storage " +
                                "first, which takes its size in free space and a while " +
                                "for a multi-GB file. Download lists GGUFs only.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        // The hand-pushed case, named: a model can reach a phone over
                        // USB or out of a backup without going through the picker.
                        if (onScanLocal != null) {
                            Text(
                                text = "Copied a .gguf onto this phone with a cable, or " +
                                    "downloaded one already? Find models on this phone in " +
                                    "the menu finds models the app can already see.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = if (models.isEmpty() && remoteChatOffer == null) 0.dp else 16.dp,
                    bottom = 88.dp, // clears the extended button
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (chatGptCard != null) {
                    item(key = "chatgpt") { chatGptCard() }
                }
                if (remoteChatOffer != null && onUseRemoteChat != null) {
                    item(key = "remote-chat") {
                        RemoteChatCard(
                            offer = remoteChatOffer,
                            active = remoteChatActive,
                            enabled = !busy,
                            onListModels = onListRemoteModels,
                            onPickModel = onPickRemoteModel,
                            onUse = {
                                busyLabel = "Loading model…"
                                busy = true
                                importError = null
                                scanNotice = null
                                scope.launch {
                                    runCatching { onUseRemoteChat() }
                                        .onFailure { importError = describeLoadFailure(it) }
                                    busy = false
                                }
                            },
                        )
                    }
                }
                // The model in use is listed first; sortedBy is stable, so the rest keep their order.
                items(
                    models.sortedByDescending { it.uri.toString() == loadedModelUri },
                    key = { it.uri.toString() },
                ) { model ->
                    ModelRow(
                        technical = technical,
                        model = model,
                        shownName = if (technical) model.displayName else listedModelName(model, models),
                        loadContext = contextLengthFor(model),
                        onDelete = { pendingDelete = model },
                        speedTest = speedTest,
                        isLoaded = model.uri.toString() == loadedModelUri,
                        onUse = onUseModel?.let { use ->
                            {
                                busyLabel = "Loading model…"
                                busy = true
                                importError = null
                                scanNotice = null
                                scope.launch {
                                    val outcome = runCatching { use(model) }
                                        .getOrElse { ModelAvailability.Failed(describeLoadFailure(it)) }
                                    if (outcome is ModelAvailability.Failed) importError = outcome.reason
                                    busy = false
                                }
                            }
                        },
                    )
                }
            }
        }
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = {
                val name = if (technical) target.displayName else listedModelName(target, models)
                Text(
                    if (isOwnFile(target)) "Delete $name?" else "Remove $name from the list?",
                )
            },
            text = {
                Text(
                    if (isOwnFile(target)) {
                        "This frees ${formatBytes(target.fileSizeBytes)} on your phone."
                    } else {
                        "The file stays where it is."
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(target)
                    pendingDelete = null
                }) {
                    Text(if (isOwnFile(target)) "Delete" else "Remove")
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
private fun ModelRow(
    technical: Boolean,
    model: ImportedModel,
    /** The name the row shows and its remove button names (see [listedModelName]). */
    shownName: String,
    loadContext: Int,
    onDelete: () -> Unit,
    speedTest: SpeedTestRunner?,
    isLoaded: Boolean,
    /** Makes this model the resident one. Null hides the button. */
    onUse: (() -> Unit)? = null,
) {
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
    val totalBytes = model.estimate.totalBytes(loadContext)
    val fits = model.fitsOnDevice(loadContext)

    // The model in use gets its own container colour, so the row that answers the chat
    // is the one that stands out.
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = if (isLoaded) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = shownName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    val subtitle = if (technical) {
                        listOfNotNull(
                            model.quantType,
                            model.architecture,
                            model.parameterCount?.let { "${formatParams(it)} params" },
                        ).joinToString(" · ")
                    } else {
                        model.parameterCount?.let { formatParams(it) }.orEmpty()
                    }
                    if (subtitle.isNotEmpty()) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isLoaded) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
                if (isLoaded) {
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = "In use",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(start = 4.dp, end = 4.dp),
                    )
                }
                if (!isLoaded && onUse != null) {
                    TextButton(onClick = onUse) { Text("Use") }
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "Remove $shownName")
                }
            }

            if (!technical) {
                // One line: the size and whether the phone can run it. A model that does
                // not fit says so in the card below instead.
                Text(
                    text = if (fits) {
                        "${formatBytes(model.fileSizeBytes)} · Fits this phone"
                    } else {
                        formatBytes(model.fileSizeBytes)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                // "On disk" is the only number on this card that was measured: it
                // is `File.length()` on the file itself. Everything below it is
                // arithmetic on that number plus the header, and the card says so
                // once, here, instead of leaving four confident figures looking
                // equally solid.
                FactLine("On disk (measured)", formatBytes(model.fileSizeBytes))
                FactLine("Weights, estimated", formatBytes(model.estimate.weightBytes))
                FactLine("RAM at load (estimated)", formatBytes(totalBytes))
                // A LiteRT-LM file does not expose its KV geometry, so the line is
                // omitted rather than shown as 0 B, which would read as a claim.
                model.estimate.kvBytes(loadContext)?.let { kv ->
                    FactLine("  of which KV cache", formatBytes(kv))
                }
                FactLine("Context at load", "$loadContext tokens")
                model.trainedContextLength?.let { trained ->
                    // The trained length is a property of the file, and it is NOT
                    // what this app will use. Shown because it is genuinely useful
                    // to know the model is capable of more, labelled so that it is
                    // never read as the size of the cache about to be allocated.
                    FactLine("Model's own max context", "$trained tokens (not used)")
                }
                // The disclaimer the numbers above need: nothing has been profiled
                // on a phone, so every RAM number is arithmetic on the file size and
                // the header, and the load gate uses the same arithmetic.
                Text(
                    text = "RAM figures are calculated from the file size and the model's " +
                        "header. They are not measured on this phone.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (technical && !model.hasChatTemplate) {
                FactLine("Chat template", "missing — tool calls will be unreliable")
            }

            if (!fits) {
                Card(
                    colors = androidx.compose.material3.CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        if (!technical) {
                            Text(
                                text = "Too big for this phone. Try a smaller model.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        } else {
                            Text(
                                text = "This model will not load on this phone. It needs " +
                                    "about ${formatBytes(totalBytes)} and this device has " +
                                    "${formatBytes(RamEstimate.usableDeviceBytes())} usable " +
                                    "for the app. The load gate refuses it rather than " +
                                    "letting the system kill the app part-way through a task.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            // There is no context control anywhere in this app: every
                            // load uses the fixed 4096 above. The one real lever is a
                            // smaller model, so that is what is named.
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
            }

            // The display name from a content provider is often useless (a hash),
            // so offer the real one from the provider when it disagrees.
            val resolved = remember(model.uri) { displayNameOf(context, model.uri) }
            if (technical && resolved != null && resolved != model.displayName) {
                FactLine("File", resolved)
            }

            // The speed test sits under the resident model only: it measures the
            // loaded backend, so the button on any other row would describe a model
            // that is not in memory. The last result stays under its model after it
            // is unloaded, labelled with the date, so it is not lost.
            if (technical && speedTest != null) {
                SpeedTestSection(model = model, runner = speedTest, isLoaded = isLoaded)
            }
        }
    }
}

/** How many listed model ids are shown at once. The rest are reachable by typing the id in Settings. */
private const val LISTED_MODELS_SHOWN = 50

/**
 * The remote chat entry. It is a card and not a model row because it is not a file on
 * this phone: there is no size, RAM or context figure to show, and inventing them would be
 * wrong. The sentence about where messages go comes before the button on purpose.
 *
 * "Choose from list" asks the provider for its model ids, only on tap, and offers them as
 * buttons. Providers that do not list models do not get the button; the model is typed in
 * Settings.
 */
@Composable
private fun RemoteChatCard(
    offer: RemoteChatOffer,
    active: Boolean,
    enabled: Boolean,
    onListModels: (suspend () -> List<String>)?,
    onPickModel: ((String) -> Unit)?,
    onUse: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var listing by remember { mutableStateOf(false) }
    var listed by remember { mutableStateOf<List<String>?>(null) }
    var listError by remember { mutableStateOf<String?>(null) }
    Card(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "${offer.providerLabel}: ${offer.model.ifBlank { "no model chosen" }}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Runs on ${offer.destination} — your messages are sent there.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (active) {
                Text(
                    text = "In use. Use a local model below to stop sending messages.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                TextButton(onClick = onUse, enabled = enabled && offer.model.isNotBlank()) { Text("Use") }
            }
            if (offer.canListModels && onListModels != null && onPickModel != null) {
                TextButton(
                    enabled = !listing,
                    onClick = {
                        listing = true
                        listError = null
                        scope.launch {
                            runCatching { onListModels() }
                                .onSuccess { listed = it }
                                .onFailure {
                                    listed = null
                                    listError = "Could not list models. ${it.message.orEmpty().take(120)}"
                                }
                            listing = false
                        }
                    },
                ) { Text(if (listing) "Listing…" else "Choose from list") }
                listError?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                listed?.let { ids ->
                    if (ids.isEmpty()) {
                        Text(
                            text = "The provider listed no models. Type the id in Settings.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    ids.take(LISTED_MODELS_SHOWN).forEach { id ->
                        TextButton(onClick = {
                            onPickModel(id)
                            listed = null
                        }) { Text(id) }
                    }
                    if (ids.size > LISTED_MODELS_SHOWN) {
                        Text(
                            text = "Showing the first $LISTED_MODELS_SHOWN of ${ids.size}. Type any other id in Settings.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SpeedTestSection(model: ImportedModel, runner: SpeedTestRunner, isLoaded: Boolean) {
    val last by runner.last.collectAsState()
    val mine = last?.takeIf { it.modelUri == model.uri.toString() }
    if (isLoaded) SpeedTestButton(runner)
    mine?.let { SpeedTestResultBlock(it) }
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

/** The model file extensions the app imports. Only these are stripped from a name. */
private val MODEL_EXTENSIONS = listOf(".gguf", ".litertlm")

/**
 * A readable name for Simple mode: the file name without its model extension, quant suffix
 * and separators. Both "LFM3-1.7B-QAD-Q4_0.gguf" (a file name) and "LFM3 1.7B" (a GGUF's own
 * `general.name`, which has a dot in it) become "LFM3 1.7B".
 *
 * WHY only known extensions: the displayName is usually the GGUF's `general.name`, and cutting
 * at the last dot turned "LFM3 1.7B" into "LFM3 1".
 */
internal fun friendlyModelName(fileName: String): String {
    // A downloaded file is saved as `owner__repo__file` (GgufQuant.localFileName); the file part names the model.
    val leaf = fileName.substringAfterLast("__").ifEmpty { fileName }
    val base = MODEL_EXTENSIONS
        .firstOrNull { leaf.endsWith(it, ignoreCase = true) }
        ?.let { leaf.dropLast(it.length) }
        ?: leaf
    val stem = base.ifEmpty { fileName }
    // Everything from the first quant token on is dropped ("-Q4_K_M", "-QAD-Q4_0", "-IQ3_XS").
    val cut = Regex("[-_.](i?q\\d|qad|bf16|f16|f32)", RegexOption.IGNORE_CASE).find(stem)?.range?.first
    val name = (if (cut != null && cut > 0) stem.substring(0, cut) else stem)
        .replace('-', ' ').replace('_', ' ').trim()
    return name.ifBlank { fileName }
}

/**
 * The name a model is listed under in Simple mode. Two installed files of one model (LFM3 1.7B
 * Q4_0 and Q4_K_M) share a friendly name, so the list would show two identical rows; the quant
 * is appended to tell them apart. Left alone when nothing else has the name, or the quant is unknown.
 */
internal fun listedModelName(model: ImportedModel, installed: List<ImportedModel>): String {
    val name = friendlyModelName(model.displayName)
    val clash = installed.any { it.uri != model.uri && friendlyModelName(it.displayName) == name }
    val quant = model.quantType
    return if (clash && quant != null) "$name · $quant" else name
}

private fun formatParams(count: Long): String = when {
    count >= 1_000_000_000 -> "%.1fB".format(count / 1_000_000_000.0)
    count >= 1_000_000 -> "%.0fM".format(count / 1_000_000.0)
    else -> count.toString()
}

internal fun displayNameOf(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
    }
}.getOrNull()
