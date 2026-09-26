package dev.localintelligence.app.ui.trace

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.localintelligence.app.AppContainer
import dev.localintelligence.app.LocalIntelligenceApp
import dev.localintelligence.core.metrics.DiagnosticReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Runs [DeviceSelfCheck] and holds the report the screen renders.
 *
 * ## Why the report is state rather than a parameter
 *
 * A self-check the user has to re-open to re-run is a self-check they will not
 * re-run. Holding the report means returning to the screen after fixing
 * something in Settings shows the new answer, and [refresh] exists so the
 * screen can call it on every resume for the same reason
 * [ScheduleViewModel] does: permissions and memory are facts a user changes in
 * another app.
 *
 * ## Why the work is on [Dispatchers.IO]
 *
 * One check parses a GGUF header off the filesystem and the ABI check lists a
 * directory. On a fast phone that is milliseconds; on a slow one with a large
 * header it is enough to drop a frame, and a diagnostics screen that janks is
 * the first thing a user concludes is broken.
 */
class DiagnosticsViewModel(
    application: Application,
) : AndroidViewModel(application) {

    private val container: AppContainer =
        (application as LocalIntelligenceApp).container

    private val _state = MutableStateFlow(DiagnosticsUiState())
    val state: StateFlow<DiagnosticsUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    /**
     * Re-runs every check.
     *
     * A previous report is kept while the new one runs rather than being
     * cleared, so the screen does not flash empty on every resume. It is
     * replaced only when the new report exists, and a report that throws is
     * surfaced as itself rather than swallowed — a self-check that hides its
     * own failure is the last thing this screen should do.
     */
    fun refresh() {
        _state.value = _state.value.copy(running = true)
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching { DeviceSelfCheck(getApplication(), container).run() }
            }
            _state.value = outcome.fold(
                onSuccess = { report ->
                    DiagnosticsUiState(report = report, running = false, error = null)
                },
                onFailure = { t ->
                    DiagnosticsUiState(
                        report = _state.value.report,
                        running = false,
                        error = "${t.javaClass.simpleName}: ${t.message}",
                    )
                },
            )
        }
    }
}

/**
 * What the screen renders.
 *
 * [error] exists because a self-check run is code like any other and can throw.
 * When it does, the honest thing is to show the exception rather than an empty
 * list, which would read as "everything is fine".
 */
data class DiagnosticsUiState(
    val report: DiagnosticReport? = null,
    val running: Boolean = true,
    val error: String? = null,
)
