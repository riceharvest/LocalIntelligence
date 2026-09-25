package dev.localintelligence.app.execution

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import dev.localintelligence.app.data.ScheduledTask

/**
 * Arms and disarms the system alarms behind a [ScheduledTask].
 *
 * ## The gap this fills
 *
 * The audit that prompted this file: `AlarmFireReceiver` and `AlarmBootReceiver`
 * existed, were declared in the manifest, and were reachable — but the only code
 * that could put a `PendingIntent` in `AlarmManager` was `AlarmCreateTool`,
 * reachable only when the *model* decided mid-conversation to emit an
 * `alarm.create` tool call. There was no path from a user pressing a button, and
 * no `AlarmManager` call of any kind in `:app`. The plumbing was real; nothing
 * turned the tap.
 *
 * ## One mechanism, not two
 *
 * This uses `AlarmManager.setExactAndAllowWhileIdle` with a
 * `ScheduledTaskFireReceiver` PendingIntent, which is the same mechanism
 * `AndroidAlarmPlatform` uses for clock alarms — deliberately. A second
 * scheduling abstraction (a queue, a cron parser, a retry ladder) would be a
 * framework; this is a second *schedule* on the first mechanism.
 *
 * ## Why the request code is derived, not stored
 *
 * `PendingIntent` equality ignores extras and is decided by (requestCode,
 * action, identity), so a code that is a pure function of the task id resolves
 * to the same PendingIntent after process death, with no persisted request code
 * and no registry to get out of step. Disarming then works with
 * `FLAG_NO_CREATE` — cancel must never resurrect a PendingIntent that is not
 * already armed, which is the exact bug the comment in `AndroidAlarmPlatform`
 * warns about.
 */
object TaskAlarmScheduler {

    /**
     * FNV-1a over `"task:" + id`, mapped into 1..Int.MAX_VALUE.
     *
     * The `"task:"` prefix is the load-bearing part. `AlarmIds.requestCodeOf`
     * hashes a bare alarm id, and both sets live in the same `AlarmManager`
     * namespace; without a distinct prefix a task id that collided with an alarm
     * id would produce the same request code, and the second `PendingIntent`
     * built for it would resolve to the *first* one — a task that silently
     * cancels an alarm, or an alarm that fires a task.
     *
     * A collision within one set is bounded the same way `AlarmIds` documents
     * it: one task fires at another's time and the list shows both.
     */
    fun requestCodeOf(taskId: String): Int {
        var hash = -0x340d631b7bdddcdbL
        for (ch in "task:$taskId") {
            hash = hash xor (ch.code.toLong() and 0xFF)
            hash *= 0x100000001b3L
        }
        val positive = (hash ushr 1) and 0x7FFF_FFFFL
        return (positive % Int.MAX_VALUE).toInt() + 1
    }

    private fun manager(context: Context): AlarmManager? =
        context.applicationContext
            .getSystemService(Context.ALARM_SERVICE) as? AlarmManager

    /**
     * False on Android 12+ when the user has not granted exact-alarm access.
     *
     * The same question `AndroidAlarmPlatform.canScheduleExactAlarms` asks,
     * asked again here because the two schedules are separate objects with
     * separate request codes and one being granted says nothing about the other
     * being armed.
     */
    fun canScheduleExactAlarms(context: Context): Boolean {
        val manager = manager(context) ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            manager.canScheduleExactAlarms()
        } else {
            // Before API 31 an exact alarm needed no permission at all, so the
            // question cannot arise.
            true
        }
    }

    /**
     * Arms [task] for a real exact alarm.
     *
     * Throws [SecurityException] when exact-alarm access has been revoked
     * between the check and the call — a real race the user can win by opening
     * Settings — and [IllegalStateException] when there is no AlarmManager. Both
     * are caught by the caller, which is what turns them into a visible reason
     * rather than a crash on a broadcast.
     */
    fun arm(context: Context, task: ScheduledTask) {
        val manager = manager(context) ?: throw IllegalStateException("AlarmManager unavailable")
        manager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            task.nextRunAtMillis,
            pendingIntent(context, task),
        )
    }

    /**
     * Disarms [taskId]. A no-op when nothing is armed for it.
     *
     * `FLAG_NO_CREATE`, and the reason is in [arm]: a cancel that creates the
     * PendingIntent it is about to cancel arms an alarm the user just removed.
     */
    fun disarm(context: Context, taskId: String) {
        val manager = manager(context) ?: return
        val existing = PendingIntent.getBroadcast(
            context.applicationContext,
            requestCodeOf(taskId),
            ScheduledTaskFireReceiver.intent(context),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        ) ?: return
        manager.cancel(existing)
        existing.cancel()
    }

    private fun pendingIntent(context: Context, task: ScheduledTask): PendingIntent =
        PendingIntent.getBroadcast(
            context.applicationContext,
            requestCodeOf(task.id),
            ScheduledTaskFireReceiver.intentFor(context, task),
            // IMMUTABLE: nothing needs to fill in the extras at send time, and a
            // mutable PendingIntent registered with the system alarm machinery is
            // an attack surface.
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}
