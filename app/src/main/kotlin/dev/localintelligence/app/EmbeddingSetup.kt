package dev.localintelligence.app

import android.content.Context
import android.util.Log
import dev.localintelligence.android.documents.DEFAULT_EMBEDDING_FILE
import dev.localintelligence.android.documents.DEFAULT_EMBEDDING_REPO
import dev.localintelligence.android.documents.DEFAULT_EMBEDDING_SIZE_BYTES
import dev.localintelligence.android.hub.ModelDownloader
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
 * Manages downloading the document search model (EmbeddingGemma 2 Q8_0) with one tap,
 * inline progress, cancellation, and resume across process deaths.
 *
 * ## WHY this reuses StarterSetup's machinery
 *
 * It uses the same [HuggingFaceClient], [ModelDownloader], [HubTransfers], and [DeviceBudget],
 * so partial downloads, chunked resume, and foreground service lifecycle are shared without
 * duplicating any transfer logic.
 */
class EmbeddingSetup(
    private val context: Context,
    private val scope: CoroutineScope,
    private val client: HuggingFaceClient,
    private val downloader: ModelDownloader,
    private val budget: DeviceBudget,
    private val tokenSource: HubTokenSource,
    private val transfers: HubTransfers,
    private val isModelReady: () -> Boolean,
    private val adopt: suspend (File) -> Unit,
    private val onModelReady: (() -> Unit)? = null,
    settingsChanges: StateFlow<Int>? = null,
) {
    private val local: HubGgufFile = HubGgufFile(
        repo = (HubRepoId.parse(DEFAULT_EMBEDDING_REPO) as HubRepoId.Result.Valid).repoId,
        path = (HubFilePath.parse(DEFAULT_EMBEDDING_FILE) as HubFilePath.Result.Valid).filePath,
        sizeBytes = DEFAULT_EMBEDDING_SIZE_BYTES,
        sha256 = null,
        quant = null,
        shardIndex = null,
        shardCount = null,
    )

    private val _state = MutableStateFlow<SetupState>(idleFromDisk())
    val state: StateFlow<SetupState> = _state.asStateFlow()

    private var running: Job? = null

    init {
        settingsChanges?.let { flow ->
            scope.launch {
                flow.collect {
                    checkReady()
                }
            }
        }
    }

    /** Retries or starts the download. A no-op unless the state is Idle or Failed. */
    fun start() {
        if (!claim()) return
        running = transfers.launch(context.getString(R.string.document_search_model)) { attempt() }
    }

    /** Cancels the in-flight download, keeping any .part file for resume. */
    fun cancel() {
        val job = running ?: return
        if (_state.value !is SetupState.Downloading) return
        running = null
        job.cancel()
    }

    /** Updates state if the file was added or removed by the user or another flow. */
    fun checkReady() {
        if (isModelReady()) {
            _state.update { cur ->
                if (cur is SetupState.Idle || cur is SetupState.Failed) SetupState.Ready else cur
            }
        } else {
            _state.update { cur ->
                if (cur is SetupState.Ready) idleFromDisk() else cur
            }
        }
    }

    private fun claim(): Boolean {
        while (true) {
            val cur = _state.value
            if (cur !is SetupState.Idle && cur !is SetupState.Failed) return false
            val kept = (cur as? SetupState.Idle)?.keptBytes ?: 0L
            if (_state.compareAndSet(cur, SetupState.Downloading(kept, DEFAULT_EMBEDDING_SIZE_BYTES))) return true
        }
    }

    private suspend fun attempt() {
        try {
            run()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "embedding model download failed", e)
            _state.value = SetupState.Failed(context.getString(R.string.setup_network_failed))
        } finally {
            _state.update { if (it is SetupState.Downloading) idleFromDisk() else it }
        }
    }

    private suspend fun run() {
        val partial = downloader.partialFileFor(local)
        // Step 1: the file is already complete on disk.
        if (partial.isComplete(DEFAULT_EMBEDDING_SIZE_BYTES) || isModelReady()) {
            loadDownloaded(partial.finalFile)
            return
        }

        // Step 2: list the repo to get HF's exact metadata.
        val files = withContext(Dispatchers.IO) {
            client.listGgufFiles(local.repo, tokenSource.token())
        }
        val file = files.firstOrNull { it.fileName == DEFAULT_EMBEDDING_FILE }
        if (file == null) {
            _state.value = SetupState.Failed(context.getString(R.string.setup_not_found))
            return
        }

        // Step 3: plan storage (ramGate = false as it is not loaded as the chat model).
        val plan = withContext(Dispatchers.IO) {
            val header = client.probeHeader(file, tokenSource.token())
            client.plan(
                file = file,
                contextLength = 0,
                budget = budget,
                alreadyOnDiskBytes = downloader.partialFileFor(file).localBytes(),
                header = header,
                ramGate = false,
            )
        }
        plan.reject?.let { reject ->
            _state.value = SetupState.Failed(refusal(reject))
            return
        }

        // Step 4: download the model.
        when (val end = download(file, plan)) {
            is DownloadProgress.Done -> loadDownloaded(downloader.localFileFor(file))
            is DownloadProgress.Stopped -> stopped(end)
            else -> _state.value = SetupState.Failed(context.getString(R.string.setup_network_failed))
        }
    }

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

    private suspend fun loadDownloaded(file: File) {
        _state.value = SetupState.Preparing
        try {
            adopt(file)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "adopting downloaded search model failed", e)
        }
        if (isModelReady()) {
            onModelReady?.invoke()
            _state.value = SetupState.Ready
        } else {
            _state.value = SetupState.Failed(context.getString(R.string.setup_embedding_not_loaded))
        }
    }

    private fun stopped(event: DownloadProgress.Stopped) {
        _state.value = if (event.error == HubError.Cancelled) idleFromDisk() else SetupState.Failed(describe(event.error))
    }

    private fun describe(error: HubError): String = when (error) {
        is HubError.InsufficientStorage -> storageSentence(error)
        is HubError.ChecksumMismatch -> context.getString(R.string.setup_checksum_damaged)
        is HubError.InvalidInput -> context.getString(R.string.setup_could_not_save)
        else -> context.getString(R.string.setup_network_failed)
    }

    private fun refusal(reject: HubError): String = when (reject) {
        is HubError.InsufficientStorage -> storageSentence(reject)
        else -> context.getString(R.string.setup_embedding_cannot_download)
    }

    private fun storageSentence(error: HubError.InsufficientStorage): String =
        context.getString(R.string.setup_free_storage, formatBytes(error.neededBytes - error.availableBytes))

    private fun idleFromDisk(): SetupState {
        if (isModelReady()) return SetupState.Ready
        val partial = downloader.partialFileFor(local)
        val onDisk = partial.isComplete(DEFAULT_EMBEDDING_SIZE_BYTES)
        return SetupState.Idle(
            sizeBytes = DEFAULT_EMBEDDING_SIZE_BYTES,
            keptBytes = if (onDisk) 0L else partial.localBytes(),
            onDisk = onDisk,
        )
    }

    private companion object {
        const val TAG = "EmbeddingSetup"
    }
}
