package dev.localintelligence.app.execution

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.localintelligence.app.ExecutionService
import dev.localintelligence.app.data.ScheduledTask
import dev.localintelligence.app.data.ScheduledTaskStore
import dev.localintelligence.app.data.TaskCadence
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Fires a scheduled task, and re-arms the schedule after a reboot.
 *
 * ## This is the piece the audit found missing
 *
 * `AlarmFireReceiver` and `AlarmBootReceiver` in `:android` were both declared
 * and both reachable, and `AlarmBootReceiver` even re-armed through a real
 * `AndroidAlarmPlatform.schedule`. What did not exist was any code that could
 * *create* a schedule a user asked for. The only writer into `AlarmManager` was
 * `AlarmCreateTool`, which runs when the model emits an `alarm.create` tool call
 * — so the capability existed but was unreachable except by conversation, and
 * nothing in `:app` called `AlarmManager` at all. This receiver is the
 * reachable half.
 *
 * ## Why one receiver carries both jobs
 *
 * A scheduled task must survive a reboot, and a reboot clears every
 * `AlarmManager` PendingIntent the app registered. The alternative to
 * re-registering is a schedule that silently stops existing the moment the phone
 * restarts — and because the store is on disk, the UI would go on listing it as
 * scheduled. That is the specific failure this project cares about most.
 *
 * `:android`'s `AlarmBootReceiver` already re-arms *clock* alarms from
 * `AlarmRegistry`, and it cannot re-arm these: the registries are separate
 * stores in separate modules, and `AlarmSpec` has no field for a task prompt.
 * Rather than add a second component for the same broadcast, this one listens
 * for `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED` too, so `:app` adds exactly
 * one receiver to the manifest. Collapsing the two re-arm paths into one is a
 * one-line follow-up for whoever owns `:android`; it is called out in the PR
 * description rather than smuggled in here.
 *
 * ## Why the next occurrence is armed BEFORE the run starts
 *
 * If this order were reversed, a process death between starting the service and
 * re-arming would leave a recurring task with no future alarm and no way to
 * notice, which is the "looks scheduled, is not scheduled" bug in its purest
 * form. Arming first means the worst case is a missed run with a live schedule,
 * which the user can see. A recurring schedule is also implemented by
 * re-arming rather than `setRepeating`, because `setRepeating` is inexact and
 * inexact is a lie at "every 15 minutes".
 *
 * ## goAsync is bounded
 *
 * Everything here is a SharedPreferences read, a few binder calls into
 * `AlarmManager`, and a `startForegroundService`. No model is loaded and no
 * inference happens on this thread — the model load happens inside
 * [ExecutionService], which is where it belongs. Loading 2 GB from a broadcast
 * receiver is how you get a broadcast ANR.
 */
class ScheduledTaskFireReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> rearmAll(context)
            // Explicit PendingIntent deliveries always carry the action, but the
            // task id is the real discriminator, so branch on that rather than
            // trusting a string a future edit could get wrong.
            else -> {
                val id = intent.getStringExtra(EXTRA_TASK_ID) ?: return
                fire(context, id)
            }
        }
    }

    /**
     * Runs one task and schedules its successor.
     *
     * A one-shot disarms itself here rather than relying on a cancel that may
     * never come: `TaskCadence.nextAfter` returns null for it, which is the
     * signal that this was the last fire.
     */
    private fun fire(context: Context, taskId: String) {
        val appContext = context.applicationContext
        val store = ScheduledTaskStore(appContext)

        // Read before goAsync. A SharedPreferences read with the file already
        // mapped is microseconds; going async for it would be theatre, and
        // `goAsync` costs a 10-second budget the binder then has to hold.
        val task = store.byId(taskId)
        if (task == null || !task.enabled) {
            // Deleted or paused between arming and firing. Disarm rather than
            // run: the PendingIntent outlives the row only if the two were
            // written at different moments, and a task the user removed must
            // not execute.
            TaskAlarmScheduler.disarm(appContext, taskId)
            return
        }

        val pending = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try {
                advanceSchedule(appContext, store, task)
                startRun(appContext, store, task)
            } catch (e: CancellationException) {
                // Structured cancellation, not a failure. Rethrown so the
                // cancellation stays a cancellation all the way out; swallowing
                // it here would report a scope that neither ran nor rescheduled
                // as if it had done one of those.
                throw e
            } finally {
                // `finish()` must run on every exit or the broadcast is
                // reported as never handled, and the system logs an ANR for an
                // app that did nothing wrong.
                runCatching { pending.finish() }
            }
        }
    }

    /**
     * Records the run and arms the next one.
     *
     * Split out from [fire] so the order — record, arm, then run — reads as the
     * three statements it is, rather than as one call whose internals a reader
     * has to hold in their head to see the sequence.
     */
    private fun advanceSchedule(
        context: Context,
        store: ScheduledTaskStore,
        task: ScheduledTask,
    ) {
        val now = System.currentTimeMillis()
        val next = TaskCadence.nextAfter(task.cadence, task.nextRunAtMillis)

        val advanced = if (next == null) {
            // One-shot: it has now fired, so it is not "enabled and waiting".
            // Kept in the store rather than deleted, so the UI can still show
            // what ran and when instead of the list silently losing a row.
            //
            // `lastResult` is the *pending* marker in both branches, not a
            // claim about what happened. It used to read "Ran. This was a
            // one-time task, so it is no longer scheduled." here — written
            // before the run had done anything, about a run that might not
            // happen at all. `ScheduledRunReporter` overwrites it with the real
            // outcome on every terminal path, and the UI shows the "already
            // ran, not scheduled" fact from `enabled` + `cadence` rather than
            // from this string.
            task.copy(
                enabled = false,
                lastRunAtMillis = now,
                lastResult = ScheduledRunReporter.PENDING,
            )
        } else {
            task.copy(
                nextRunAtMillis = next,
                lastRunAtMillis = now,
                lastResult = ScheduledRunReporter.PENDING,
            )
        }
        store.upsert(advanced)

        if (next == null) {
            TaskAlarmScheduler.disarm(context, task.id)
            return
        }

        try {
            TaskAlarmScheduler.arm(context, advanced)
        } catch (e: SecurityException) {
            // Exact-alarm access revoked between arming and firing. The task row
            // survives with a real next-run time, and the UI reads
            // `canScheduleExactAlarms` to say so rather than the schedule
            // quietly going quiet.
            //
            // The `SCHEDULE_WARNING_PREFIX` is load-bearing: this fact is still
            // true when the run finishes, and the run's own result is about to
            // overwrite this field. `ScheduledRunReporter` recognises the
            // prefix and carries the warning forward onto the result, so an
            // answer never hides the fact that the schedule is now broken.
            store.upsert(
                advanced.copy(
                    lastResult = ScheduledRunReporter.SCHEDULE_WARNING_PREFIX +
                        "exact-alarm access is off, so this task has no next run.",
                ),
            )
        } catch (e: IllegalStateException) {
            // No AlarmManager on this device. Same honesty, different reason,
            // and the same carry-forward.
            store.upsert(
                advanced.copy(
                    lastResult = ScheduledRunReporter.SCHEDULE_WARNING_PREFIX +
                        "this device has no alarm service.",
                ),
            )
        }
    }

    /**
     * Hands the prompt to the existing execution path.
     *
     * One line, and that is the point. This does not build a controller, does
     * not load a model and does not run the loop: [ExecutionService] already does
     * `ensureModelReady()` → `newController()` → `run`, and a second copy of
     * that path would be a second model load discipline on a RAM-constrained
     * device. A scheduled task is a *trigger*, not a runner.
     *
     * [store] is passed rather than constructed here so the failure path below
     * re-reads the *current* row instead of writing the copy this function was
     * called with. Between the fire and this call the user may have paused the
     * task, and writing the captured copy back would re-enable it. A row that
     * has been deleted is left deleted.
     */
    private fun startRun(context: Context, store: ScheduledTaskStore, task: ScheduledTask) {
        try {
            ExecutionService.start(context, task.prompt, task.id)
        } catch (e: CancellationException) {
            // First, and before the broad catch below. A `catch (e: Exception)`
            // in a coroutine body swallows structured cancellation, and this
            // function is called from one. Rethrown so the cancellation
            // propagates instead of being recorded as a failed run — which
            // would write "the system refused to launch a background run" into
            // the task's history, a sentence that is both wrong and alarming.
            throw e
        } catch (e: Exception) {
            // `startForegroundService` from the background throws on API 31+
            // unless an exemption applies. An exact alarm *is* a documented
            // exemption, so this should not happen — but "should not" is not
            // "cannot", and a crash here would take the process down with it.
            // The schedule survives; the run is reported as missed.
            //
            // This is a real terminal outcome rather than a placeholder: no
            // service started, so nothing will ever overwrite it, and
            // `ScheduledRunReporter` will not see this run at all.
            store.upsert(
                store.byId(task.id)?.copy(
                    lastResult = ScheduledRunReporter.DID_NOT_START +
                        "the system refused to start a background run " +
                        "(${e::class.java.simpleName}).",
                ) ?: return,
            )
        }
    }

    /**
     * Re-arms every enabled task after a reboot or an app update.
     *
     * Tasks whose next-run time has passed are rolled forward rather than
     * dropped, and the count of what was restored is returned so the caller —
     * or a future log line — can see it. A schedule that vanishes on reboot
     * while still being listed in the UI is the specific lie this project
     * treats as the worst kind of bug.
     */
    private fun rearmAll(context: Context) {
        val appContext = context.applicationContext
        val store = ScheduledTaskStore(appContext)

        if (!TaskAlarmScheduler.canScheduleExactAlarms(appContext)) {
            // The rows stay. The UI reads the same permission and says "your
            // phone will not let this app schedule exact alarms", which is a
            // thing the user can fix, rather than a schedule that quietly
            // stopped existing.
            return
        }

        val now = System.currentTimeMillis()
        for (task in store.all()) {
            if (!task.enabled) continue
            val rolled = if (task.nextRunAtMillis <= now) {
                val next = TaskCadence.nextAfter(task.cadence, now) ?: continue
                task.copy(nextRunAtMillis = next)
            } else {
                task
            }
            try {
                TaskAlarmScheduler.arm(appContext, rolled)
                if (rolled.nextRunAtMillis != task.nextRunAtMillis) store.upsert(rolled)
            } catch (e: SecurityException) {
                // Refused for this one task; the others still get armed.
            } catch (e: IllegalStateException) {
                // No AlarmManager.
            }
        }
    }

    companion object {
        const val ACTION_TASK_FIRED = "dev.localintelligence.app.execution.TASK_FIRED"
        const val EXTRA_TASK_ID = "dev.localintelligence.app.extra.TASK_ID"

        /** The PendingIntent target for disarming. */
        fun intent(context: Context): Intent =
            Intent(context, ScheduledTaskFireReceiver::class.java)
                .setAction(ACTION_TASK_FIRED)

        /**
         * The full intent for one task.
         *
         * Only the id rides along. The prompt is deliberately *not* an extra:
         * a task row is the single source of truth for what runs, and copying it
         * into a PendingIntent would let a stale alarm carry an old prompt
         * after the user has edited the schedule.
         */
        fun intentFor(context: Context, task: ScheduledTask): Intent =
            intent(context).putExtra(EXTRA_TASK_ID, task.id)
    }
}
