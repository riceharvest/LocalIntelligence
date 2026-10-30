package dev.localintelligence.app

import dev.localintelligence.core.hub.DownloadProgress
import dev.localintelligence.core.hub.HubError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Runs model downloads in an app-level scope, so a transfer is not tied to the
 * lifetime of the Hub screen's ViewModel.
 *
 * WHY: a transfer in `viewModelScope` ends when that ViewModel is cleared, and
 * a 2 GB download that ends that way gives no notice. Here the transfer lives as
 * long as the process, and [DownloadService] is what keeps the process running
 * while the app is in the background. [start] and [stop] are the service hooks:
 * the service is started when the first transfer begins and stopped when the
 * last one ends, so it runs exactly as long as there is something to show.
 *
 * WHY the transfer's progress is published here and not only in the ViewModel:
 * the notification needs it, the Cancel button on the notification needs
 * [cancel], and a ViewModel created while a transfer runs can follow it through
 * [transfer] instead of showing an idle screen.
 */
class HubTransfers(
    private val start: () -> Unit,
    private val stop: (result: String?) -> Unit,
) {
    /** One transfer's state. [running] is false once it has ended, and [progress] then holds how it ended. */
    data class Transfer(val fileName: String, val progress: DownloadProgress?, val running: Boolean)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Guards [running], [current] and the service calls, which must pair up. */
    private val lock = Any()
    private var running = 0
    private var current: Job? = null

    private val _transfer = MutableStateFlow<Transfer?>(null)

    /** The latest transfer, or null before any has started. Kept after it ends so its outcome can be read. */
    val transfer: StateFlow<Transfer?> = _transfer.asStateFlow()

    /**
     * Runs [block] as a transfer named [fileName]. Returns its job, which the
     * ViewModel keeps so the screen's own Stop still cancels it.
     */
    fun launch(fileName: String, block: suspend () -> Unit): Job = synchronized(lock) {
        if (running++ == 0) {
            // A refused start (the platform blocks a foreground start from the background) must not
            // stop the download: the transfer still runs, just without the service keeping it alive.
            runCatching { start() }
        }
        _transfer.value = Transfer(fileName, progress = null, running = true)
        val job = scope.launch { block() }
        current = job
        // Runs on cancel too, and runs even when the job is cancelled before it starts,
        // which a `finally` inside the block would miss.
        job.invokeOnCompletion { finished() }
        job
    }

    /** Publishes one progress event of the running transfer. */
    fun report(event: DownloadProgress) {
        _transfer.update { it?.copy(progress = event) }
    }

    /** Cancels the running transfer, as the notification's Cancel button does. */
    fun cancel() {
        synchronized(lock) { current?.cancel() }
    }

    private fun finished() {
        synchronized(lock) {
            if (--running > 0) return
            val last = _transfer.value ?: return
            // A cancel lands between two progress events, so the last event can still be an in-flight one.
            // It is recorded as stopped, with the bytes the .part file holds, so a screen that looks at it
            // later does not show a download still in progress.
            val ended = when (val p = last.progress) {
                is DownloadProgress.InProgress -> DownloadProgress.Stopped(HubError.Cancelled, p.bytesDownloaded)
                else -> p
            }
            _transfer.value = last.copy(progress = ended, running = false)
            stop(resultText(last.fileName, ended))
        }
    }

    /** The line for the result notification. A cancel the user asked for needs no notice. */
    private fun resultText(fileName: String, ended: DownloadProgress?): String? = when (ended) {
        is DownloadProgress.Done -> "$fileName is downloaded. Open the app to use it."
        is DownloadProgress.Stopped ->
            if (ended.error == HubError.Cancelled) null else "$fileName stopped. Open the app to resume it."
        else -> null
    }
}
