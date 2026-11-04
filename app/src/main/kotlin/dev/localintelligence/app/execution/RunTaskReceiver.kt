package dev.localintelligence.app.execution

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.localintelligence.app.ExecutionService
import dev.localintelligence.app.LocalIntelligenceApp
import dev.localintelligence.app.data.ScheduledTaskStore
import dev.localintelligence.core.settings.CoreSettings

/**
 * Lets another app start one of the user's existing scheduled tasks, by id.
 *
 * ## What this receiver is, and what it refuses to be
 *
 * It is a trigger for a task the user already created, not a way to run new work.
 * The inputs are a task id and a token. The prompt comes from the stored row, so a
 * caller cannot supply text the agent will obey. A caller that guesses an id can run
 * only a task the user chose to schedule, and only with the token.
 *
 * ## Why a token and not a permission
 *
 * A custom permission cannot be held by an app that does not declare it, and Tasker
 * and adb do not. The token travels in the broadcast, and the user copies it across.
 *
 * ## Why this is a separate receiver from ScheduledTaskFireReceiver
 *
 * That receiver is internal (`exported="false"`) and its `fire` path advances the
 * schedule: it moves `nextRunAtMillis`, disarms one-shot tasks and re-arms the alarm.
 * An outside trigger must change none of that, so it gets its own class and action.
 *
 * ## Gates, in order
 *
 * 1. The action must match. The manifest filter already narrows this, but an explicit
 *    intent can name the component and skip the filter.
 * 2. The toggle "Let other apps start my scheduled tasks" must be on. It is Technical
 *    only and off by default. [CoreSettings.EXTERNAL_TASK_START] reads the default in
 *    Simple mode, so the receiver is inert there.
 * 3. The `token` extra must match the stored token, compared in constant time. A
 *    missing token or a stored token that cannot be decrypted is a refusal too.
 * 4. The `task_id` extra must name a task that exists and is enabled. A paused task is
 *    refused because pausing means "do not run this", and a one-time task that has
 *    already fired is disabled. Both refusals match what the alarm path does.
 * 5. The run goes through [ExecutionService.start] with the id, which makes it
 *    unattended and lets `RunGate` refuse a second concurrent run.
 *
 * ## What it does not do
 *
 * It does not touch `nextRunAtMillis`, `enabled` or the alarm. A triggered run is an
 * extra run, and the schedule keeps its own timing. A one-time task that has not yet
 * fired will therefore also fire at its scheduled time. The threat model states this.
 *
 * ## Logging
 *
 * One fixed line per refusal. The token and the task id are caller-supplied, so
 * neither is logged, and the prompt is never logged.
 */
class RunTaskReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_RUN_TASK) return

        val app = context.applicationContext as? LocalIntelligenceApp ?: return
        val settings = app.container.settings
        if (!settings[CoreSettings.EXTERNAL_TASK_START]) {
            Log.i(TAG, "RUN_TASK ignored: 'Let other apps start my scheduled tasks' is off.")
            return
        }

        // A sender can attach an extra this process cannot unparcel. The read then throws,
        // and an uncaught exception here would take the process down because the receiver
        // is reachable from outside the app.
        val presented = readExtra(intent, EXTRA_TOKEN)
        val stored = settings[CoreSettings.EXTERNAL_TASK_TOKEN]
        if (!ExternalTaskToken.matches(stored, presented)) {
            Log.i(TAG, "RUN_TASK ignored: token missing or wrong.")
            return
        }

        val taskId = readExtra(intent, EXTRA_TASK_ID)
        if (taskId.isNullOrEmpty()) {
            Log.i(TAG, "RUN_TASK ignored: no task_id extra.")
            return
        }

        val store = ScheduledTaskStore(app)
        val task = store.byId(taskId)
        if (task == null || !task.enabled) {
            Log.i(TAG, "RUN_TASK ignored: no enabled scheduled task has that id.")
            return
        }

        // Only the run's bookkeeping moves. The reporter overwrites lastResult when the run
        // finishes. nextRunAtMillis, enabled and the alarm are left alone. The write happens
        // before the start, as the alarm path does, so the list never shows an old "last ran"
        // time beside a result from this run.
        store.upsert(
            task.copy(
                lastRunAtMillis = System.currentTimeMillis(),
                lastResult = ScheduledRunReporter.PENDING,
            ),
        )

        try {
            ExecutionService.start(app, task.prompt, task.id)
        } catch (e: Exception) {
            // A broadcast from another app is not covered by the exact-alarm exemption, and
            // on Android 12 and newer a background start from it is refused unless this app is
            // exempt from battery optimisation. That is a plausible outcome, not a theoretical
            // one, so it is recorded on the row. The message points at the app's background
            // access screen, where the user can grant the exemption.
            store.byId(task.id)?.let { current ->
                store.upsert(
                    current.copy(
                        lastResult = ScheduledRunReporter.DID_NOT_START +
                            "Android refused a background start. Allow background access " +
                            "for LocalIntelligence in its Background access screen, then " +
                            "trigger it again (${e::class.java.simpleName}).",
                    ),
                )
            }
            Log.w(TAG, "RUN_TASK: could not start the run (${e::class.java.simpleName}).")
        }
    }

    /** Reads one string extra, or null when the sender's bundle cannot be unparcelled. */
    private fun readExtra(intent: Intent, name: String): String? = try {
        intent.getStringExtra(name)
    } catch (e: RuntimeException) {
        null
    }

    companion object {
        private const val TAG = "RunTaskReceiver"

        /** The action another app sends. The receiver is exported and checks the token itself. */
        const val ACTION_RUN_TASK = "dev.localintelligence.action.RUN_TASK"

        /** The extra that names the task. Deliberately no prompt extra exists. */
        const val EXTRA_TASK_ID = "task_id"

        /** The extra that carries the token from the setting. Never logged. */
        const val EXTRA_TOKEN = "token"
    }
}
