package dev.localintelligence.app.execution

import android.content.Context
import dev.localintelligence.app.data.ScheduledTask
import dev.localintelligence.app.data.ScheduledTaskStore
import dev.localintelligence.app.data.TaskCadence
import dev.localintelligence.app.data.newScheduledTask

/**
 * Create, pause, resume and delete a scheduled task.
 *
 * ## Why this is not a class with an interface
 *
 * The obvious shape is a `TaskService` behind an interface so it could be
 * faked. That would be a queue abstraction, which is the thing this change was
 * told not to build: one real schedule does not need to be swappable, and an
 * interface with one implementation is a promise about a second one that does
 * not exist. The operations that matter are the ones with a failure mode —
 * [create], [delete] — and they are written out rather than delegated.
 *
 * ## What every mutating call does, in order
 *
 * Write the store, then touch `AlarmManager`, then report. That order is chosen
 * so a crash between the two leaves a visible row that the UI can reconcile
 * against [TaskAlarmScheduler.canScheduleExactAlarms] on the next open, rather
 * than an armed alarm for a task that does not exist and fires into a receiver
 * that disarms it again.
 */
object ScheduledTaskController {

    /** The cap the store enforces, surfaced so the UI can say "N of 16". */
    const val MAX_TASKS = ScheduledTaskStore.MAX_TASKS

    /**
     * Creates a task and arms it.
     *
     * Returns the stored task, or null when the store is full or the alarm
     * could not be armed. A null is a refusal with a reason already visible in
     * the UI, not an exception: pressing "Create" must not be able to crash the
     * app, and the failure modes here — 16 tasks, a revoked alarm permission —
     * are all things a person can see and act on.
     */
    fun create(
        context: Context,
        prompt: String,
        cadence: TaskCadence,
        nowMillis: Long = System.currentTimeMillis(),
    ): ScheduledTask? {
        val store = ScheduledTaskStore(context)
        if (store.all().size >= MAX_TASKS) return null
        if (!TaskAlarmScheduler.canScheduleExactAlarms(context)) return null

        val task = newScheduledTask(prompt, cadence, nowMillis)
        return try {
            TaskAlarmScheduler.arm(context, task)
            store.upsert(task)
        } catch (e: SecurityException) {
            // The permission was revoked between the check above and this call.
            // A real race: the user can open Settings and switch it off while
            // this screen is open.
            null
        } catch (e: IllegalStateException) {
            // No AlarmManager on this device.
            null
        }
    }

    /**
     * Stops a task from firing again and removes it.
     *
     * This is where "delete means stop" is actually enforced, and the order is
     * the whole point:
     *
     *  1. **Cancel a run in flight.** Before disarming and before removing the
     *     row, so there is no window in which the alarm fires into a task the
     *     user has already stopped. `ScheduledRunRegistry.cancelIfRunning` is
     *     conditional on the id, so deleting a paused task cannot kill an
     *     unrelated chat.
     *  2. **Disarm.** `FLAG_NO_CREATE` means cancelling a task that was never
     *     armed cannot arm it.
     *  3. **Remove the row last.** If the process dies between 1 and 3 the task
     *     survives with no alarm, and the user still sees it in the list —
     *     recoverable, rather than a task that vanished having possibly run.
     *
     * Returns true when a row was actually removed.
     */
    fun delete(context: Context, taskId: String): Boolean {
        ScheduledRunRegistry.cancelIfRunning(context, taskId)
        TaskAlarmScheduler.disarm(context, taskId)
        return ScheduledTaskStore(context).remove(taskId)
    }

    /**
     * Pauses a task without losing it.
     *
     * Disarms only. The row stays with its cadence and its history, so resuming
     * is a tap rather than a retype, and so the user can see that a task exists
     * and is deliberately not running — which is the difference between "paused"
     * and "gone".
     *
     * ## A run already in flight is left to finish
     *
     * `pause` does not cancel. The user asked to stop the *schedule*, not to
     * interrupt work already done on their behalf, and killing a half-finished
     * agent run to satisfy a pause would be a surprising thing for a button
     * labelled "Pause" to do. The run keeps going, reaches its own terminal
     * state, and `ScheduledRunReporter` writes the outcome onto this row —
     * re-reading it, which is why the pause survives: the reporter replaces
     * only `lastResult`, so a task paused mid-run comes back saying
     * "Paused. Not scheduled." *and* what the run it just finished actually
     * said. Both are true and the user needs both.
     */
    fun pause(context: Context, taskId: String): Boolean {
        val store = ScheduledTaskStore(context)
        val task = store.byId(taskId) ?: return false
        if (!task.enabled) return true
        TaskAlarmScheduler.disarm(context, taskId)
        store.upsert(task.copy(enabled = false))
        return true
    }

    /**
     * Resumes a paused task, arming it for the next occurrence from *now*.
     *
     * Recomputed rather than restoring the stored `nextRunAtMillis`: resuming a
     * daily task that was paused for a week at 07:00 must not fire immediately
     * because 07:00 today has already passed while it was off. The skipped
     * occurrences are genuinely skipped, and the UI shows the new time before
     * the user taps anything.
     */
    fun resume(context: Context, taskId: String, nowMillis: Long = System.currentTimeMillis()): Boolean {
        val store = ScheduledTaskStore(context)
        val task = store.byId(taskId) ?: return false
        if (task.enabled) return true
        if (!TaskAlarmScheduler.canScheduleExactAlarms(context)) return false

        val next = TaskCadence.nextAfter(task.cadence, nowMillis) ?: return false
        val resumed = task.copy(enabled = true, nextRunAtMillis = next)
        return try {
            TaskAlarmScheduler.arm(context, resumed)
            // `upsert` returns the task, not a Boolean. Without the explicit
            // `true` the try-block's value type is inferred as the common
            // supertype of `ScheduledTask` and `Boolean` and the declared return
            // type does not check — which is how "armed but not saved" would
            // ever get written as a return value.
            store.upsert(resumed)
            true
        } catch (e: SecurityException) {
            false
        } catch (e: IllegalStateException) {
            false
        }
    }
}
