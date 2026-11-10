package dev.localintelligence.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
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
import dev.localintelligence.core.hub.Sha256
import dev.localintelligence.core.hub.formatBytes
import dev.localintelligence.core.settings.SettingDef
import dev.localintelligence.core.settings.SettingSection
import dev.localintelligence.core.model.gguf.GgufHeader
import dev.localintelligence.core.model.gguf.KvCacheType
import dev.localintelligence.android.hub.ModelDownloader
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
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
    data class Failed(val message: String, val kind: SimpleWording.Kind? = null) : SetupState
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
 *   chat-ready -> `Failed(context.getString(R.string.setup_starter_would_not_start))`.
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
    private val context: Context,
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
    private val kvCacheType: (GgufHeader?) -> KvCacheType = { KvCacheType.DEFAULT },
    /** The app's model readiness, so a model loaded from Models completes the setup too. */
    private val availability: StateFlow<ModelAvailability>,
    /** [AppContainer.adoptDownloaded]: registers and loads the file. Null, or not chat-ready, is a failure. */
    private val adopt: suspend (File) -> DownloadedModelRegistrar.Registered?,
    /** [AppContainer.awaitPrefixWarm]: returns once the prefix build for the loaded model is done. */
    private val awaitPrefix: suspend () -> Unit,
    /**
     * The smallest starter, offered when the phone cannot run [suggestion]. Null: no fallback.
     * A phone that refuses the bigger starter is set up with this one instead, and [remember] keeps
     * that choice for the next launch.
     */
    private val smallest: SuggestedModel? = null,
    private val remember: (SuggestedModel) -> Unit = {},
) {
    /** The starter being set up. Changes once, from [suggestion] to [smallest], when the phone refuses [suggestion]. */
    private var active: SuggestedModel = suggestion

    /**
     * The active suggestion as a Hub file with no listing data. It is used only for its local paths, which
     * come from the same naming rule the downloader uses ([ModelDownloader.partialFileFor]).
     * The suggestion is a constant, so a parse failure is a programming error, not a user error.
     */
    private var local: HubGgufFile = localFor(active)

    private fun localFor(model: SuggestedModel): HubGgufFile = HubGgufFile(
        repo = (HubRepoId.parse(model.repo) as HubRepoId.Result.Valid).repoId,
        path = (HubFilePath.parse(model.file) as HubFilePath.Result.Valid).filePath,
        sizeBytes = model.sizeBytes,
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

    /** Set once the person was told the download would use mobile data; their next tap is the consent. */
    private var awaitingMobileConsent = false

    /** The system callback that starts the setup again when the phone is back online. Set only while waiting. */
    private var onlineWatch: ConnectivityManager.NetworkCallback? = null

    init {
        // The observer runs on the app scope, not the main thread, so every write is an atomic update.
        scope.launch {
            availability.collect { now ->
                when (now) {
                    is ModelAvailability.Ready -> {
                        stopWatchingNetwork()
                        _state.update { cur ->
                            if (cur is SetupState.Idle || cur is SetupState.Failed) SetupState.Ready else cur
                        }
                    }
                    ModelAvailability.None -> _state.update { cur ->
                        if (cur is SetupState.Ready) idleFromDisk() else cur
                    }
                    // A model that was ready and then failed to load: Try again must be able to start the
                    // setup again, and it can only start from Idle or Failed.
                    is ModelAvailability.Failed -> _state.update { cur ->
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
    fun start(automatic: Boolean = false) {
        val cur = _state.value
        if (cur !is SetupState.Idle && cur !is SetupState.Failed) return
        // Mobile data is the person's to spend: the first tap says how big the download is, the second
        // (Try again on that sentence) is the consent.
        // A network coming back is not the person's consent to spend mobile data: only their own tap is.
        val consent = awaitingMobileConsent && !automatic
        if (!automatic) awaitingMobileConsent = false
        // With no network at all the system calls the (absent) connection metered, so this must come first:
        // an offline phone is told it is offline and starts by itself on reconnect, not asked about mobile data.
        if (!downloader.partialFileFor(local).isComplete(active.sizeBytes) && !online()) {
            waitForNetwork()
            return
        }
        if (!consent && onMeteredNetwork()) {
            awaitingMobileConsent = true
            // Wi-Fi may arrive a moment after mobile data (airplane mode turning off, a router restarting): the watch
            // stays, and an unmetered network starts the download by itself. Mobile data still waits for the tap.
            _state.value = SetupState.Failed(context.getString(R.string.setup_mobile_data, formatBytes(active.sizeBytes)), SimpleWording.Kind.NO_NETWORK)
            watchNetwork()
            return
        }
        stopWatchingNetwork()
        awaitingMobileConsent = false
        if (!claim()) return
        // The notification and its result line are shown in Simple mode, so they carry no model name.
        running = transfers.launch(context.getString(R.string.assistant_download_name)) { attempt() }
    }

    /** Cancels the running setup, as the Cancel button does. Only while downloading. */
    fun cancel() {
        stopWatchingNetwork()
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
            if (_state.compareAndSet(cur, SetupState.Downloading(kept, active.sizeBytes))) return true
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
        } catch (e: HubError) {
            // The site answered (a 5xx, a 404, a rate limit): say what it said, in plain words, not "check your internet".
            Log.w(TAG, "first-run setup refused by the hub", e)
            if (e is HubError.NoNetwork || e is HubError.ConnectionLost) {
                if (!online()) waitForNetwork() else _state.value = SetupState.Failed(context.getString(R.string.setup_network_failed), SimpleWording.Kind.NO_NETWORK)
            } else {
                _state.value = SetupState.Failed(describe(e), kindOf(e))
            }
        } catch (e: Exception) {
            Log.w(TAG, "first-run setup failed", e)
            if (!online()) waitForNetwork() else _state.value = SetupState.Failed(context.getString(R.string.setup_network_failed), SimpleWording.Kind.NO_NETWORK)
        } finally {
            // A cancel can end the job before the downloader reports anything. The bytes on disk are the truth.
            _state.update { if (it is SetupState.Downloading) idleFromDisk() else it }
        }
    }

    private suspend fun run() {
        val partial = downloader.partialFileFor(local)
        // Step 1: the whole file is already here, verified when it was renamed into place. No network.
        if (partial.isComplete(active.sizeBytes)) {
            loadDownloaded(partial.finalFile)
            return
        }

        // Step 1b: the room the rest of the file needs, checked before any network call, so the answer is the
        // same offline. The plan below checks it again against the listed size.
        val needed = active.sizeBytes - partial.localBytes()
        val free = context.filesDir.usableSpace
        if (needed > free) {
            _state.value = SetupState.Failed(context.getString(R.string.setup_free_storage, formatBytes(needed - free)), SimpleWording.Kind.STORAGE_FULL)
            return
        }

        // Step 2: the same listing the Hub makes, so the file carries its real size and checksum.
        val files = withContext(Dispatchers.IO) {
            client.listGgufFiles(local.repo, tokenSource.token())
        }
        val file = files.firstOrNull { it.fileName == active.file }
        if (file == null) {
            _state.value = SetupState.Failed(context.getString(R.string.setup_not_found), SimpleWording.Kind.DOWNLOAD_FAILED)
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
                kvCacheType = kvCacheType(header),
            )
        }
        plan.reject?.let { reject ->
            // A phone that cannot run the bigger starter is set up with the smallest one, once, and the
            // choice is remembered. A full phone is not helped by a smaller model: that one stays a refusal.
            val fallback = smallest?.takeIf { reject !is HubError.InsufficientStorage && it.file != active.file }
            if (fallback != null) {
                Log.w(TAG, "the phone cannot run ${active.name}; setting up ${fallback.name}")
                active = fallback
                local = localFor(fallback)
                remember(fallback)
                return run()
            }
            _state.value = SetupState.Failed(refusal(reject), refusalKind(reject))
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
            else -> _state.value = SetupState.Failed(context.getString(R.string.setup_network_failed), SimpleWording.Kind.NO_NETWORK)
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
                _state.value = SetupState.Failed(context.getString(R.string.setup_network_failed), SimpleWording.Kind.NO_NETWORK)
                false
            }
        }
    }

    /**
     * Runs a download, retrying a dropped connection with growing waits. Each retry resumes from the bytes
     * already on disk (the downloader's `.part` file). Offline is not retried here: [waitForNetwork] takes it.
     */
    private suspend fun download(file: HubGgufFile, plan: DownloadPlan): DownloadProgress? {
        var retry = 0
        while (true) {
            val last = downloadOnce(file, plan)
            val dropped = last is DownloadProgress.Stopped &&
                (last.error is HubError.ConnectionLost || last.error is HubError.NoNetwork)
            if (!dropped || retry >= RETRY_WAITS_MS.size || !online()) return last
            delay(RETRY_WAITS_MS[retry++])
        }
    }

    /**
     * Runs one download, mirroring every event to the transfer and the state. Returns the last event,
     * which is the terminal one (`Done` or `Stopped`) for a download that ended.
     */
    private suspend fun downloadOnce(file: HubGgufFile, plan: DownloadPlan): DownloadProgress? {
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
            // A file that will not load may be damaged in a way its size does not show. When its checksum is
            // known and does not match, it is removed, so the next tap downloads it again instead of failing forever.
            if (discardIfDamaged(file)) {
                _state.value = SetupState.Failed(context.getString(R.string.setup_damaged_again), SimpleWording.Kind.DOWNLOAD_FAILED)
                return
            }
            // The file is sound but this phone cannot start it (the load ladder has run out): set up the
            // smallest starter instead, once, and remember that. The bigger file stays where it is.
            val fallback = smallest?.takeIf { it.file != active.file }
            if (fallback != null) {
                Log.w(TAG, "the phone cannot start ${active.name}; setting up ${fallback.name}")
                active = fallback
                local = localFor(fallback)
                remember(fallback)
                return run()
            }
            _state.value = SetupState.Failed(context.getString(R.string.setup_starter_would_not_start), SimpleWording.Kind.LOAD_FAILED)
            return
        }
        awaitPrefix()
        _state.value = SetupState.Ready
    }

    /** A download that ended with [event] and was not a success. A cancel the user asked for is not an error. */
    private fun stopped(event: DownloadProgress.Stopped) {
        val offline = event.error is HubError.ConnectionLost || event.error is HubError.NoNetwork
        _state.value = when {
            event.error == HubError.Cancelled -> idleFromDisk()
            offline && !online() -> return waitForNetwork()
            else -> SetupState.Failed(describe(event.error), kindOf(event.error))
        }
    }

    /** True when the phone has a connection that can reach the internet. */
    private fun online(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return true
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) } ?: return false
        return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun onMeteredNetwork(): Boolean {
        if (downloader.partialFileFor(local).isComplete(active.sizeBytes)) return false
        return context.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered == true
    }

    /**
     * No connection: say so in one sentence, and start again by itself when the system reports a network.
     * A callback, not a timer: nothing polls while the phone is offline.
     */
    private fun waitForNetwork() {
        _state.value = SetupState.Failed(context.getString(R.string.setup_waiting_network), SimpleWording.Kind.NO_NETWORK)
        watchNetwork()
    }

    /** Starts the setup again, without a tap, whenever the system reports a network. See [start]. */
    private fun watchNetwork() {
        if (onlineWatch != null) return
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        val watch = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                scope.launch(Dispatchers.Main) {
                    if (_state.value is SetupState.Failed) start(automatic = true)
                }
            }
        }
        runCatching { cm.registerDefaultNetworkCallback(watch) }
            .onSuccess { onlineWatch = watch }
    }

    private fun stopWatchingNetwork() {
        val watch = onlineWatch ?: return
        onlineWatch = null
        context.getSystemService(ConnectivityManager::class.java)?.let { cm ->
            runCatching { cm.unregisterNetworkCallback(watch) }
        }
    }

    /**
     * True, and the file removed, when a checksum is known and the file on disk does not match it. False
     * when it matches, or when it cannot be checked (no listing, offline, no checksum): the file is kept then.
     */
    private suspend fun discardIfDamaged(file: File): Boolean = withContext(Dispatchers.IO) {
        val expected = runCatching { client.listGgufFiles(local.repo, tokenSource.token()) }.getOrNull()
            ?.firstOrNull { it.fileName == active.file }?.sha256
            ?: return@withContext false
        if (Sha256.matches(expected, Sha256.ofFile(file))) return@withContext false
        file.delete()
        true
    }

    /** The one plain sentence for a download that stopped. Never a file name, a code or a size we did not compute. */
    /** The Simple kind of a failed download, set where the failure is born (the sentence is localized). */
    private fun kindOf(error: HubError): SimpleWording.Kind = when (error) {
        is HubError.InsufficientStorage -> SimpleWording.Kind.STORAGE_FULL
        is HubError.ChecksumMismatch, is HubError.InvalidInput -> SimpleWording.Kind.DOWNLOAD_FAILED
        is HubError.ServerUnavailable, is HubError.RateLimited, is HubError.RepoNotFound, is HubError.FileNotFound -> SimpleWording.Kind.DOWNLOAD_FAILED
        else -> SimpleWording.Kind.NO_NETWORK
    }

    private fun refusalKind(reject: HubError): SimpleWording.Kind = when (reject) {
        is HubError.InsufficientStorage -> SimpleWording.Kind.STORAGE_FULL
        else -> SimpleWording.Kind.CANT_RUN
    }

    private fun describe(error: HubError): String = when (error) {
        is HubError.InsufficientStorage -> storageSentence(error)
        is HubError.ChecksumMismatch -> context.getString(R.string.setup_checksum_damaged)
        is HubError.InvalidInput -> context.getString(R.string.setup_could_not_save)
        // The download site answered, so the phone is online: say the site is the problem, not the connection.
        is HubError.ServerUnavailable -> context.getString(R.string.setup_site_down)
        is HubError.RateLimited -> context.getString(R.string.setup_site_busy)
        is HubError.RepoNotFound, is HubError.FileNotFound -> context.getString(R.string.setup_not_found)
        else -> context.getString(R.string.setup_network_failed)
    }

    /** The sentence for a plan that refuses the download before it starts. */
    private fun refusal(reject: HubError): String = when (reject) {
        is HubError.InsufficientStorage -> storageSentence(reject)
        else -> context.getString(R.string.setup_starter_cannot_run)
    }

    private fun storageSentence(error: HubError.InsufficientStorage): String =
        context.getString(R.string.setup_free_storage, formatBytes(error.neededBytes - error.availableBytes))

    /** `Idle` as the disk says it is. Reads two file lengths, so it is cheap enough for the main thread. */
    private fun idleFromDisk(): SetupState.Idle {
        val partial = downloader.partialFileFor(local)
        val onDisk = partial.isComplete(active.sizeBytes)
        return SetupState.Idle(
            sizeBytes = active.sizeBytes,
            keptBytes = if (onDisk) 0L else partial.localBytes(),
            onDisk = onDisk,
        )
    }

    internal companion object {
        const val TAG = "StarterSetup"

        /** The waits before each retry of a dropped connection. The download resumes from the bytes it kept. */
        val RETRY_WAITS_MS = longArrayOf(2_000L, 5_000L, 15_000L, 30_000L)

        /** The file name of the smallest starter once the phone has been moved down to it. Empty: the table's pick. */
        val SMALLER_STARTER = SettingDef.Text(
            "setup.smaller_starter", "Smaller starter", "The starter this phone was moved to because it could not run the first pick.",
            SettingSection.MODEL, default = "", maxLength = 200,
        )
    }
}
