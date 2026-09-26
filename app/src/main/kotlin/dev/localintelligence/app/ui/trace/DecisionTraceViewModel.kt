package dev.localintelligence.app.ui.trace

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.localintelligence.app.AppContainer
import dev.localintelligence.app.LocalIntelligenceApp
import dev.localintelligence.core.trace.DecisionLine
import dev.localintelligence.core.trace.TracePolicy
import dev.localintelligence.core.trace.TraceStats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Holds the decision trace for the debug screen.
 *
 * ## WHY IT READS THE CONTAINER'S SINGLE TRACE RATHER THAN ITS OWN
 *
 * A [dev.localintelligence.core.trace.DecisionTrace] is written by the agent
 * loop, which runs in [dev.localintelligence.app.ExecutionService] and finishes
 * before anyone thinks to open a screen. A view model that started its own
 * recorder would hold an empty buffer forever. The container's instance is the
 * one the run actually wrote to, which is the whole reason it is process-wide.
 *
 * ## WHY [error] EXISTS
 *
 * Exporting writes a file and hands out a URI grant, and either can fail. A
 * screen that swallowed that would show a share button that silently does
 * nothing — the exact "looks correct, is not" failure this whole feature exists
 * to make impossible. The failure is shown as itself.
 */
class DecisionTraceViewModel(
    application: Application,
) : AndroidViewModel(application) {

    private val container: AppContainer =
        (application as LocalIntelligenceApp).container

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /**
     * The active capture policy, mirrored so the switch re-renders on change.
     *
     * Mirrored rather than read live because the recorder re-publishes its
     * RECORD list through a StateFlow, which conflates an unchanged list away —
     * so a toggle that changes only the policy emits nothing and the control
     * would sit showing the state it just left.
     */
    private val _policy = MutableStateFlow(container.decisionTrace.policy)

    /**
     * The trace itself, straight off the recorder.
     *
     * The recorder owns the StateFlow, so this is a re-exposed reference rather
     * than a copy. Re-exposing rather than mirroring is what keeps the screen
     * live during a run instead of showing the previous one until it is
     * reopened.
     */
    val records: StateFlow<List<DecisionLine>>
        get() = container.decisionTrace.records

    /** Read on demand, not collected: it changes on every emission. */
    val stats: TraceStats get() = container.decisionTrace.stats

    /** Clears the buffer. Explicit, and never automatic. */
    fun clear() {
        container.decisionTrace.clear()
        _error.value = null
    }

    /**
     * Writes the trace and returns an intent for the share sheet.
     *
     * Null means the export did not happen, and [error] says why. A share
     * button that silently fails is the one thing a debugging aid must not do.
     */
    suspend fun buildShareIntent(): android.content.Intent? {
        val lines = records.value
        if (lines.isEmpty()) {
            _error.value = "There is no trace to share yet. Run a task first."
            return null
        }
        val intent = DecisionTraceExporter.shareIntent(getApplication(), lines)
        _error.value = if (intent == null) {
            "The trace could not be written to this device's storage, so there " +
                "is nothing to share."
        } else {
            null
        }
        return intent
    }

    fun dismissError() {
        _error.value = null
    }

    /**
     * Turns text capture on or off, for the next run.
     *
     * Off is the shipped default and the ON value is modest on purpose. 2048
     * characters per field is enough to see the whole of a tool argument or a
     * short observation, and deliberately not enough to turn the trace into a
     * second copy of the conversation history. The total cap remains the real
     * ceiling; this only bounds one field.
     *
     * Applies from the next emission forward. Records already buffered keep the
     * length they were captured with, because widening them retroactively is
     * how a debugging aid becomes the memory incident.
     */
    fun setBodyCapture(enabled: Boolean) {
        // Published through a StateFlow rather than left to be re-read from
        // `stats`.
        //
        // `stats` is a plain getter, and applying the policy re-publishes the
        // record list through a StateFlow — which CONFLATES an unchanged list
        // away. So after a toggle the flow emits nothing, nothing recomposes,
        // and the switch is left showing the state it just left. The user taps
        // a control, the label does not change, and the control reads as broken.
        //
        // The policy is the state here, so it gets its own flow.
        val applied = container.decisionTrace.policy.copy(
            maxBodyChars = if (enabled) BODY_CAPTURE_CHARS else 0,
        )
        container.decisionTrace.policy = applied
        _policy.value = applied
    }

    /** The active capture policy, so the switch reflects what was applied. */
    val policy: StateFlow<TracePolicy> = _policy.asStateFlow()

    private companion object {
        const val BODY_CAPTURE_CHARS = 2048
    }
}
