package dev.localintelligence.app.ui.trace

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.localintelligence.app.ModelAvailability
import dev.localintelligence.app.data.ScheduledTask
import dev.localintelligence.app.data.ScheduledTaskStore
import dev.localintelligence.app.data.TaskAction
import dev.localintelligence.app.data.TaskCadence
import dev.localintelligence.app.data.TaskReadiness
import dev.localintelligence.app.data.intentForTaskAction
import dev.localintelligence.app.data.readTaskReadiness
import dev.localintelligence.app.execution.ScheduledRunRegistry
import dev.localintelligence.app.execution.ScheduledTaskController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Everything the scheduled-tasks screen renders.
 *
 * One state object rather than six separate flows, because the screen's whole
 * job is showing relationships: a create button that is disabled *because* of a
 * blocker, a task marked "running" because of the registry, a last result that
 * has to be read together with the next run time. Split into independent flows
 * those three can be half-updated against each other and the screen says
 * something false for a frame.
 */
data class ScheduleUiState(
    val tasks: List<ScheduledTask> = emptyList(),
    val readiness: TaskReadiness? = null,
    val runningTaskId: String? = null,
    /** Set after a refused create/delete, cleared on the next edit. */
    val notice: String? = null,
) {
    val isFull: Boolean get() = tasks.size >= ScheduledTaskController.MAX_TASKS

    /** True when pressing Create would actually produce a schedule. */
    val canCreate: Boolean get() = readiness?.canRun == true && !isFull && notice == null

    /**
     * Why Create is disabled, for the cases the blockers list does NOT cover.
     *
     * WHY THIS IS NOT JUST THE BLOCKERS LIST: `canCreate` has three terms and
     * the blockers list only explains the first. A user with 16 saved tasks has
     * no blockers at all, so the button was greyed out with nothing on screen
     * saying why — and the "There are already N tasks" notice is only ever
     * written *by a create attempt*, which the disabled button makes impossible.
     * A refusal the user can never trigger is not a refusal, it is a dead end.
     *
     * The `notice != null` term deliberately returns null: that case is already
     * explained by the notice itself, which is on screen, so saying it twice
     * would be noise rather than honesty.
     */
    val disabledReason: String?
        get() = when {
            readiness?.canRun != true -> null      // the blockers list says this
            isFull -> "There are already ${ScheduledTaskController.MAX_TASKS} " +
                "scheduled tasks. Delete one to add another."
            else -> null
        }
}

/**
 * The screen's state holder.
 *
 * A real `ViewModel` — unlike `AgentViewModel`, which is deliberately not one —
 * because this state is purely screen-scoped: it exists while the screen is on
 * the back stack and is worth nothing after the process dies. The schedule
 * itself lives on disk in [ScheduledTaskStore] and does not depend on this
 * object surviving.
 */
class ScheduleViewModel(
    private val context: Context,
    private val modelAvailability: () -> ModelAvailability,
    private val hasSelectedModel: () -> Boolean,
) : ViewModel() {

    private val store = ScheduledTaskStore(context)

    private val _state = MutableStateFlow(ScheduleUiState())
    val state: StateFlow<ScheduleUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    /**
     * Re-reads everything.
     *
     * `IO` because [ScheduledTaskStore] reads a SharedPreferences file, which
     * is a disk read the first time in a process even when the bytes are
     * already in the page cache. Called on init, after every mutation, and on
     * resume — the last of which matters because notification access, battery
     * saver and the model can all change in Settings while this screen is in
     * the back stack, and a readiness reading captured at composition is a lie
     * the moment the user comes back.
     */
    fun refresh() {
        viewModelScope.launch {
            val tasks = withContext(Dispatchers.IO) { store.all() }
            val readiness = readTaskReadiness(
                context = context,
                modelAvailability = modelAvailability(),
                hasSelectedModel = hasSelectedModel(),
            )
            _state.value = _state.value.copy(
                tasks = tasks,
                readiness = readiness,
                runningTaskId = ScheduledRunRegistry.runningTaskId,
                // A notice describes one refused action. It does not survive a
                // refresh, because the condition that produced it has just been
                // re-read and the user is expected to act on it now.
                notice = null,
            )
        }
    }

    /**
     * Creates a task and arms it.
     *
     * Two phases because the refusal has to be *seen*: `create` may decline
     * (16 tasks, or Android refusing exact alarms), and a decline the screen
     * never hears is the same silent failure this whole feature is about. So
     * the mutation runs on IO, the refusal is written into the notice, and only
     * then is the list re-read.
     */
    fun create(prompt: String, cadence: TaskCadence) {
        if (prompt.isBlank()) {
            _state.value = _state.value.copy(notice = "Type what the task should do first.")
            return
        }
        viewModelScope.launch {
            val created = withContext(Dispatchers.IO) {
                ScheduledTaskController.create(context, prompt.trim(), cadence)
            }
            val isFull = _state.value.isFull
            val notice = when {
                created != null -> null
                isFull -> "There are already ${ScheduledTaskController.MAX_TASKS} " +
                    "scheduled tasks. Delete one to add another."
                // The two refusal causes are already visible in the blockers
                // list above the form, so the notice names the cause rather
                // than shrugging with a generic failure.
                else -> "Android would not schedule this task. Check the " +
                    "blockers above — exact alarms have to be allowed for this app."
            }
            val tasks = withContext(Dispatchers.IO) { store.all() }
            _state.value = _state.value.copy(
                tasks = tasks,
                readiness = readTaskReadiness(
                    context = context,
                    modelAvailability = modelAvailability(),
                    hasSelectedModel = hasSelectedModel(),
                ),
                runningTaskId = ScheduledRunRegistry.runningTaskId,
                notice = notice,
            )
        }
    }

    /**
     * Deletes a task, stopping it if it is mid-run.
     *
     * The stop is [ScheduledTaskController.delete]'s job and it is the first
     * thing that function does — see its KDoc for why the order matters. This
     * method is the button, not the mechanism.
     */
    fun delete(taskId: String) = mutate { ScheduledTaskController.delete(context, taskId) }

    fun pause(taskId: String) = mutate { ScheduledTaskController.pause(context, taskId) }

    /**
     * Resumes a paused task, reporting a refusal.
     *
     * Its own function rather than [mutate] because the failure is worth saying
     * out loud: a silent no-op on Resume leaves a task visibly paused with the
     * user having pressed the button that should have fixed it.
     */
    fun resume(taskId: String) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                ScheduledTaskController.resume(context, taskId)
            }
            if (!ok) {
                _state.value = _state.value.copy(
                    notice = "Could not resume: Android is not allowing this app to " +
                        "schedule exact alarms. See the blockers above.",
                )
            }
            refreshKeepingNotice()
        }
    }

    /**
     * The Intent that resolves a blocker, or null when the system has no such
     * screen.
     *
     * Null is a real case — an OEM build can lack the exact-alarm settings
     * activity entirely — and the screen says so instead of pressing a button
     * that does nothing.
     */
    fun intentFor(action: TaskAction) = intentForTaskAction(context, action)

    /**
     * Re-reads without discarding the notice just set.
     *
     * [refresh] clears notices on purpose, so the two refusal paths that need
     * to *show* one cannot go through it. A private variant rather than a flag
     * on the public function, because "refresh but keep the error" is a
     * different operation and the public name should not grow a parameter that
     * only two callers pass.
     */
    private fun refreshKeepingNotice() {
        val notice = _state.value.notice
        refresh()
        viewModelScope.launch {
            _state.value = _state.value.copy(notice = notice)
        }
    }

    /**
     * One mutating action, followed by a re-read.
     *
     * Delete and pause are the same shape — a call, then a re-read — and writing
     * that twice would be the only place in this class where a future change
     * could be applied to one and forgotten in the other. The result is
     * deliberately ignored: both return a Boolean meaning "a row changed", and
     * a false there is a no-op the user does not need to be told about. The
     * *refusal* cases that do need saying out loud are handled in [create] and
     * [resume], where the boolean means something went wrong rather than nothing
     * needed doing.
     */
    private fun mutate(block: () -> Unit) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { block() }
            refresh()
        }
    }

    class Factory(
        private val context: Context,
        private val modelAvailability: () -> ModelAvailability,
        private val hasSelectedModel: () -> Boolean,
    ) : ViewModelProvider.Factory {

        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ScheduleViewModel(
            context = context.applicationContext,
            modelAvailability = modelAvailability,
            hasSelectedModel = hasSelectedModel,
        ) as T
    }
}
