package dev.localintelligence.app

import android.util.Log
import dev.localintelligence.app.settings.SuggestedModel
import dev.localintelligence.app.ui.DownloadedModelRegistrar
import dev.localintelligence.app.ui.chooseProjector
import dev.localintelligence.core.hub.DeviceBudget
import dev.localintelligence.core.hub.DownloadPlan
import dev.localintelligence.core.hub.DownloadProgress
import dev.localintelligence.core.hub.HubError
import dev.localintelligence.core.hub.HubFilePath
import dev.localintelligence.core.hub.HubGgufFile
import dev.localintelligence.core.hub.HubRepoId
import dev.localintelligence.core.hub.HubTokenSource
import dev.localintelligence.core.hub.HuggingFaceClient
import dev.localintelligence.core.hub.formatBytes
import dev.localintelligence.core.model.gguf.GgufHeader
import dev.localintelligence.core.model.gguf.KvCacheType
import dev.localintelligence.android.hub.ModelDownloader
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Where Simple mode's first run stands. The chat's empty state renders this and nothing else.
 *
 * Every transition is made in [StarterSetup]; see its KDoc for which code sets each one.
 */
sealed interface SetupState {

    /**
     * Nothing is running. [keptBytes] is what an earlier attempt left in its `.part` file, which the
     * next attempt resumes. [onDisk] means the whole file is already on the phone and only loading
     * is left (a download that finished before the app died, or one whose load failed).
     */
    data class Idle(val sizeBytes: Long, val keptBytes: Long, val onDisk: Boolean) : SetupState

    /**
     * Bytes are moving, or the listing and plan are still being made. [totalBytes] is always known
     * (from the listing, or the suggestion before it), so the bar is never indeterminate.
     */
    data class Downloading(val bytes: Long, val totalBytes: Long) : SetupState

    /** The file is loading and the one-time prefix build runs. Ends when both are done. */
    data object Preparing : SetupState

    /** The model answers. */
    data object Ready : SetupState

    /** One plain sentence for the user. Try again starts over and resumes any kept bytes. */
    data class Failed(val message: String) : SetupState
}

/**
 * The one-tap first run for Simple mode: download the suggested model, load it, and finish the
 * one-time prefix build, without the user leaving the chat.
 *
 * ## WHY this is not a second download path
 *
 * It uses the Hub's own pieces: the same [HuggingFaceClient] listing and header probe, the same
 * [ModelDownloader] (so resume, the size check, the checksum and the storage gate are the Hub's),
 * the same [HubTransfers] (so the service keeps the process alive), and the same projector rule
 * ([chooseProjector]). The only thing that differs is what the user sees.
 *
 * ## WHY the transfer is one job, listing and all
 *
 * The listing, the plan and the download run inside one [HubTransfers.launch], so the notification
 * covers the whole setup and Cancel works at every step. The prefix build is also awaited inside
 * it: a user who leaves during it still has a process that is alive.
 *
 * ## State transitions (who sets each)
 *
 * - initial, [idleFromDisk] (constructor): `Idle`. `onDisk` when the final file is complete;
 *   `keptBytes` from the `.part` file otherwise.
 * - [claim] (from [start], main thread): `Idle` or `Failed` -> `Downloading(kept, size)`.
 * - [run] step 1, whole file already on disk: -> `Preparing` in [loadDownloaded].
 * - [download] progress events: `Downloading(bytes, total)`.
 * - [stopped] on `Stopped(Cancelled)`, and [attempt]'s `finally` when the job is cancelled with no
 *   event: -> `Idle` from disk. No error text.
 * - [stopped] on any other `Stopped`, [describe] its error; [run] step 2 (listing failed or the
 *   file is missing), step 3 (plan refused), and [attempt]'s catch (network): -> `Failed`.
 * - [download] `Done`: -> `Preparing` in [loadDownloaded]; [adopt] returns null or not
 *   chat-ready -> `Failed(COULD_NOT_START)`.
 * - [loadDownloaded] after [awaitPrefix] returns: -> `Ready`.
 * - the availability observer in `init`: `Ready` when the model becomes ready some other way
 *   (from `Idle` or `Failed` only); `Idle` from disk when the model is removed while `Ready`.
 *
 * ## Cancel, process death and a failed adopt
 *
 * - Cancel: [cancel] cancels this job. The downloader keeps the `.part` file, so the state is
 *   `Idle` with the kept bytes, and the next tap resumes.
 * - Process death mid-download: nothing is persisted, and the process restarts into [idleFromDisk].
 *   The `.part` file gives `Idle(keptBytes)` ("Continue setup"); a finished file gives
 *   `Idle(onDisk)` ("Finish setup").
 * - Failed adopt: the file stays where it is, listed and not loaded, and the state is `Failed`.
 *   Try again, or a relaunch (which reads `onDisk`), adopts it again.
 */
class StarterSetup(
    private val scope: CoroutineScope,
    private val suggestion: SuggestedModel,
    private val client: HuggingFaceClient,
    private val downloader: ModelDownloader,
    private val budget: DeviceBudget,
    private val tokenSource: HubTokenSource,
    private val transfers: HubTransfers,
    private val contextLength: () -> Int,
    /** The n_ctx the load would allocate for a file whose header has been read; see [HubViewModel]. */
    private val contextLengthFor: (GgufHeader?) -> Int = { contextLength() },
    /** The KV cache type the load allocates, which the plan priced from the header must use too. */
    private val kvCacheType: () -> KvCacheType = { KvCacheType.DEFAULT },
    /** The app's model readiness, so a model loaded from Models completes the setup too. */
    private val availability: StateFlow<ModelAvailability>,
    /** [AppContainer.adoptDownloaded]: registers and loads the file. Null, or not chat-ready, is a failure. */
    private val adopt: suspend (File) -> DownloadedModelRegistrar.Registered?,
    /** [AppContainer.awaitPrefixWarm]: returns once the prefix build for the loaded model is done. */
    private val awaitPrefix: suspend () -> Unit,
) {
    /**
     * The suggestion as a Hub file with no listing data. It is used only for its local paths, which
     * come from the same naming rule the downloader uses ([ModelDownloader.partialFileFor]).
     * The suggestion is a constant, so a parse failure is a programming error, not a user error.
     */
    private val local: HubGgufFile = HubGgufFile(
        repo = (HubRepoId.parse(suggestion.repo) as HubRepoId.Result.Valid).repoId,
        path = (HubFilePath.parse(suggestion.file) as HubFilePath.Result.Valid).filePath,
        sizeBytes = suggestion.sizeBytes,
        sha256 = null,
        quant = null,
        shardIndex = null,
        shardCount = null,
    )

    private val _state = MutableStateFlow<SetupState>(idleFromDisk())

    /** What the chat's empty state shows. Only ever written by the transitions listed on the class. */
    val state: StateFlow<SetupState> = _state.asStateFlow()

    /** The running setup job. Only [cancel] reads it, and only while the state is `Downloading`. */
    private var running: Job? = null

    init {
        // The observer runs on the app scope, not the main thread, so every write is an atomic update.
        scope.launch {
            availability.collect { now ->
                when (now) {
                    is ModelAvailability.Ready -> _state.update { cur ->
                        if (cur is SetupState.Idle || cur is SetupState.Failed) SetupState.Ready else cur
                    }
                    ModelAvailability.None -> _state.update { cur ->
                        if (cur is SetupState.Ready) idleFromDisk() else cur
                    }
                    else -> Unit
                }
            }
        }
    }

    /**
     * Starts the setup, or retries it after a failure. A no-op unless the state is `Idle` or `Failed`,
     * so a second tap while it runs does nothing. Called from the main thread.
     */
    fun start() {
        if (!claim()) return
        running = transfers.launch(suggestion.name) { attempt() }
    }

    /** Cancels the running setup, as the Cancel button does. Only while downloading. */
    fun cancel() {
        val job = running ?: return
        if (_state.value !is SetupState.Downloading) return
        running = null
        job.cancel()
    }

    /** Moves `Idle` or `Failed` to `Downloading`, atomically, so two taps cannot both start. */
    private fun claim(): Boolean {
        while (true) {
            val cur = _state.value
            if (cur !is SetupState.Idle && cur !is SetupState.Failed) return false
            val kept = (cur as? SetupState.Idle)?.keptBytes ?: 0L
            if (_state.compareAndSet(cur, SetupState.Downloading(kept, suggestion.sizeBytes))) return true
        }
    }

    /**
     * The whole setup, as one transfer job. Anything unexpected becomes the network message, because
     * the causes that reach here (no network, a dropped socket, a listing that will not parse) are
     * all things the user can fix by trying again.
     */
    private suspend fun attempt() {
        try {
            run()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "first-run setup failed", e)
            _state.value = SetupState.Failed(NETWORK_FAILED)
        } finally {
            // A cancel can end the job before the downloader reports anything. The bytes on disk are the truth.
            _state.update { if (it is SetupState.Downloading) idleFromDisk() else it }
        }
    }

    private suspend fun run() {
        val partial = downloader.partialFileFor(local)
        // Step 1: the whole file is already here, verified when it was renamed into place. No network.
        if (partial.isComplete(suggestion.sizeBytes)) {
            loadDownloaded(partial.finalFile)
            return
        }

        // Step 2: the same listing the Hub makes, so the file carries its real size and checksum.
        val files = withContext(Dispatchers.IO) {
            client.listGgufFiles(local.repo, tokenSource.token())
        }
        val file = files.firstOrNull { it.fileName == suggestion.file }
        if (file == null) {
            _state.value = SetupState.Failed(NOT_FOUND)
            return
        }

        // Step 4 before step 3: a model that cannot fit is refused before any projector is fetched.
        val plan = withContext(Dispatchers.IO) {
            val header = client.probeHeader(file, tokenSource.token())
            client.plan(
                file = file,
                contextLength = contextLengthFor(header),
                budget = budget,
                alreadyOnDiskBytes = downloader.partialFileFor(file).localBytes(),
                header = header,
                kvCacheType = kvCacheType(),
            )
        }
        plan.reject?.let { reject ->
            _state.value = SetupState.Failed(refusal(reject))
            return
        }

        // Step 3: the projector, the same way the Hub fetches it. It is optional, so a projector the
        // plan refuses is skipped, but a projector the user stopped or that failed stops the setup.
        chooseProjector(files)?.let { projector ->
            if (!fetchProjector(projector)) return
        }

        when (val end = download(file, plan)) {
            is DownloadProgress.Done -> loadDownloaded(downloader.localFileFor(file))
            is DownloadProgress.Stopped -> stopped(end)
            else -> _state.value = SetupState.Failed(NETWORK_FAILED)
        }
    }

    /** Downloads [projector] unless it is already here. False when the setup must stop. */
    private suspend fun fetchProjector(projector: HubGgufFile): Boolean {
        val plan = withContext(Dispatchers.IO) {
            if (downloader.isDownloaded(projector)) return@withContext null
            client.plan(
                file = projector,
                contextLength = contextLength(),
                budget = budget,
                alreadyOnDiskBytes = downloader.partialFileFor(projector).localBytes(),
                ramGate = false,
            )
        } ?: return true
        if (!plan.isAllowed) return true
        return when (val end = download(projector, plan)) {
            is DownloadProgress.Done -> true
            is DownloadProgress.Stopped -> {
                stopped(end)
                false
            }
            else -> {
                _state.value = SetupState.Failed(NETWORK_FAILED)
                false
            }
        }
    }

    /**
     * Runs one download, mirroring every event to the transfer and the state. Returns the last event,
     * which is the terminal one (`Done` or `Stopped`) for a download that ended.
     */
    private suspend fun download(file: HubGgufFile, plan: DownloadPlan): DownloadProgress? {
        var last: DownloadProgress? = null
        downloader.download(file, plan).collect { event ->
            transfers.report(event)
            if (event is DownloadProgress.InProgress) {
                val total = if (event.totalBytes > 0) event.totalBytes else file.sizeBytes
                _state.value = SetupState.Downloading(event.bytesDownloaded, total)
            }
            last = event
        }
        return last
    }

    /**
     * Loads a file that is on the phone, then waits for the prefix build, so the first message is
     * answered at once. [adopt] failing is the one way a downloaded file ends in an error.
     */
    private suspend fun loadDownloaded(file: File) {
        _state.value = SetupState.Preparing
        val registered = try {
            adopt(file)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "adopting the downloaded model failed", e)
            null
        }
        if (registered == null || !registered.chatReady) {
            _state.value = SetupState.Failed(COULD_NOT_START)
            return
        }
        awaitPrefix()
        _state.value = SetupState.Ready
    }

    /** A download that ended with [event] and was not a success. A cancel the user asked for is not an error. */
    private fun stopped(event: DownloadProgress.Stopped) {
        _state.value = if (event.error == HubError.Cancelled) idleFromDisk() else SetupState.Failed(describe(event.error))
    }

    /** The one plain sentence for a download that stopped. Never a file name, a code or a size we did not compute. */
    private fun describe(error: HubError): String = when (error) {
        is HubError.InsufficientStorage -> storageSentence(error)
        is HubError.ChecksumMismatch -> "The download was damaged, so it was removed. Try again."
        is HubError.InvalidInput -> COULD_NOT_SAVE
        else -> NETWORK_FAILED
    }

    /** The sentence for a plan that refuses the download before it starts. */
    private fun refusal(reject: HubError): String = when (reject) {
        is HubError.InsufficientStorage -> storageSentence(reject)
        else -> "This phone can't run this model."
    }

    private fun storageSentence(error: HubError.InsufficientStorage): String =
        "Free up ${formatBytes(error.neededBytes - error.availableBytes)} of storage, then try again."

    /** `Idle` as the disk says it is. Reads two file lengths, so it is cheap enough for the main thread. */
    private fun idleFromDisk(): SetupState.Idle {
        val partial = downloader.partialFileFor(local)
        val onDisk = partial.isComplete(suggestion.sizeBytes)
        return SetupState.Idle(
            sizeBytes = suggestion.sizeBytes,
            keptBytes = if (onDisk) 0L else partial.localBytes(),
            onDisk = onDisk,
        )
    }

    private companion object {
        const val TAG = "StarterSetup"
        const val NETWORK_FAILED = "Couldn't download. Check your internet connection and try again."
        const val NOT_FOUND = "Couldn't find the model to download. Try again later."
        const val COULD_NOT_START = "The model downloaded but would not start on this phone."
        const val COULD_NOT_SAVE = "The download could not be saved. Free up some storage and try again."
    }
}
