package dev.localintelligence.app.execution

import android.content.Context

/**
 * Which scheduled task, if any, the foreground service is running right now.
 *
 * ## Why this exists at all
 *
 * "Deleting a task stops it" is a correctness requirement, not a nicety: a user
 * who deletes a task that is mid-run has said stop, and an agent that keeps
 * calling tools after that is doing something the user did not author. The
 * problem is that the run lives in [dev.localintelligence.app.ExecutionService]
 * and the schedule lives in the store, and neither can see the other.
 *
 * ## Why it is a registry and not a field on the service
 *
 * The service is not reachable from a Compose screen — it is not bound, and
 * binding it would add lifecycle state for a single boolean. The run's identity
 * is process-wide (one service, one run) and the thing that needs to read it is
 * a ViewModel, so a process-scoped holder is the shape that actually fits. It
 * mirrors how `RunSinks` is already a process-scoped object rather than
 * something bound.
 *
 * ## Why cancellation is conditional
 *
 * [cancelIfRunning] refuses to cancel unless the ids match. The alternative —
 * cancelling on any delete — would let a user delete a *paused* task and kill
 * the unrelated chat they were in the middle of. That is the worst version of
 * this feature: a button that stops something the user did not ask it to stop.
 */
object ScheduledRunRegistry {

    /**
     * The task id the current run belongs to, or null for a run the user
     * started by hand from the chat screen.
     *
     * Volatile rather than a StateFlow: there is exactly one writer (the
     * service, on the main thread) and one reader (a ViewModel, on the main
     * thread), and a background receiver writes it too — so it needs a memory
     * barrier, not a reactive stream. A flow here would imply observers that
     * nobody needs and re-arm work on a screen that may not be visible.
     */
    @Volatile
    private var currentTaskId: String? = null

    val runningTaskId: String? get() = currentTaskId

    /**
     * Claims the run for [taskId], or clears the claim when [taskId] is null.
     *
     * Called by the service as it starts a run, never by the UI.
     */
    fun begin(taskId: String?) {
        currentTaskId = taskId
    }

    /**
     * Releases the claim.
     *
     * Idempotent, and deliberately not conditional: the run has ended, so
     * whatever it was is no longer running regardless of who ended it.
     */
    fun end() {
        currentTaskId = null
    }

    /**
     * Cancels the run if — and only if — it is [taskId].
     *
     * Returns true when a cancel was sent. A false return is the normal case
     * for deleting a task that is not currently running, and the UI does not
     * treat it as a failure: nothing needed stopping.
     */
    fun cancelIfRunning(context: Context, taskId: String): Boolean {
        if (currentTaskId != taskId) return false
        // `ExecutionService.cancel` is already runCatching-wrapped internally,
        // because a task that finished a millisecond before the delete arrived
        // has no service left to talk to. A crash on a button that worked is
        // never an acceptable trade.
        dev.localintelligence.app.ExecutionService.cancel(context)
        return true
    }
}
