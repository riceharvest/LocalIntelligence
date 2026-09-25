package dev.localintelligence.app.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID

/**
 * One thing the user asked the app to do on a schedule.
 *
 * ## Why this type is not an `AlarmSpec`
 *
 * `dev.localintelligence.android.tools.alarm.AlarmSpec` is a clock alarm: an
 * hour, a minute and a label, which [dev.localintelligence.android.tools.alarm.AlarmFireReceiver]
 * turns into a notification. It has nowhere to put a prompt, so it cannot
 * express "run this task at 07:00", and it has no cadence field, so it cannot
 * express "every weekday". A scheduled *task* is a different thing wearing the
 * same platform mechanism, and pretending otherwise by overloading the alarm
 * shape would have meant a null field in a data class that other code reads.
 *
 * ## What is deliberately not here
 *
 * No queue, no priority, no dependencies between tasks, no trigger other than
 * time. A scheduling engine that supports those is a project; one real schedule
 * that works is a feature, and this is the feature.
 */
@Serializable
data class ScheduledTask(
    /** Stable, unique, opaque. Also seeds the PendingIntent request code. */
    val id: String,
    /** The prompt handed verbatim to [dev.localintelligence.app.ExecutionService]. */
    val prompt: String,
    val cadence: TaskCadence,
    /** Epoch millis of the next fire. Authoritative: the UI never recomputes it. */
    val nextRunAtMillis: Long,
    val createdAtMillis: Long,
    /** False stops the schedule; the task and its history stay for the UI. */
    val enabled: Boolean = true,
    /** Epoch millis of the last fire, or null if it has never run. */
    val lastRunAtMillis: Long? = null,
    /**
     * One line describing what the last run did, written by the fire path.
     *
     * This is the honesty requirement made durable: a task that fired at 03:00
     * while the phone was locked and found no model must still say so when the
     * user opens the screen at 09:00, rather than showing a cheerful "scheduled"
     * next to a run that never happened.
     */
    val lastResult: String? = null,
) {
    fun timeLabel(zone: ZoneId = ZoneId.systemDefault()): String =
        ZonedDateTime.ofInstant(Instant.ofEpochMilli(nextRunAtMillis), zone)
            .format(DAY_FORMAT)
}

private val DAY_FORMAT: java.time.format.DateTimeFormatter =
    java.time.format.DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", java.util.Locale.getDefault())

/**
 * How often a task repeats.
 *
 * Three shapes, chosen because they are the three a person actually means:
 * once, every N minutes, or at a wall-clock time each day. Anything more is a
 * cron parser, and a wrong cron parser is worse than no cron parser.
 */
@Serializable
sealed interface TaskCadence {

    /** Runs once, then disables itself. [nextRunAtMillis] is the whole story. */
    data object Once : TaskCadence

    /**
     * Every [minutes], counted from the previous fire.
     *
     * A fixed step rather than a daily time because "every 30 minutes" is a
     * different request from "at :00 and :30", and only one of them survives a
     * DST transition unchanged.
     */
    data class EveryMinutes(val minutes: Int) : TaskCadence

    /** At [hour]:[minute] local time, every day. */
    data class DailyAt(val hour: Int, val minute: Int) : TaskCadence

    companion object {
        const val MIN_EVERY_MINUTES = 15
        const val MAX_EVERY_MINUTES = 24 * 60

        /** The exact next fire strictly after [afterMillis], or null when finished. */
        fun nextAfter(
            cadence: TaskCadence,
            afterMillis: Long,
            zone: ZoneId = ZoneId.systemDefault(),
        ): Long? = when (cadence) {
            // A one-shot has exactly one next fire, ever. Returning null is what
            // lets the fire path disable it instead of scheduling it into a loop.
            TaskCadence.Once -> null

            is TaskCadence.EveryMinutes -> {
                val step = cadence.minutes.coerceIn(MIN_EVERY_MINUTES, MAX_EVERY_MINUTES) * 60_000L
                // One step is the common case. The loop is here for the case
                // where the phone was off for three days: it skips the missed
                // slots instead of firing nine times the instant it wakes up.
                var candidate = afterMillis + step
                while (candidate <= afterMillis) candidate += step
                candidate
            }

            is TaskCadence.DailyAt -> {
                val time = LocalTime.of(
                    cadence.hour.coerceIn(0, 23),
                    cadence.minute.coerceIn(0, 59),
                )
                var day = Instant.ofEpochMilli(afterMillis).atZone(zone).toLocalDate()
                var target = ZonedDateTime.of(day, time, zone)
                if (!target.toInstant().toEpochMilli().let { it > afterMillis }) {
                    day = day.plusDays(1)
                    target = ZonedDateTime.of(day, time, zone)
                }
                // ZonedDateTime resolves a local time that does not exist (the
                // spring-forward hour) forward and a repeated one back, which is
                // the platform's documented behaviour and the only answer that
                // cannot throw at 02:30 on the wrong Sunday.
                target.toInstant().toEpochMilli()
            }
        }

        /** One line for the UI. */
        fun describe(cadence: TaskCadence): String = when (cadence) {
            TaskCadence.Once -> "Once"
            is TaskCadence.EveryMinutes -> when (cadence.minutes) {
                60 -> "Every hour"
                1440 -> "Every day"
                in 61..1439 -> "Every ${cadence.minutes / 60}h ${cadence.minutes % 60}m"
                else -> "Every ${cadence.minutes} min"
            }

            is TaskCadence.DailyAt -> String.format(
                java.util.Locale.getDefault(),
                "Daily at %02d:%02d",
                cadence.hour.coerceIn(0, 23),
                cadence.minute.coerceIn(0, 59),
            )
        }
    }
}

/** Builds a task with a fresh id and its first fire time. */
fun newScheduledTask(
    prompt: String,
    cadence: TaskCadence,
    nowMillis: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): ScheduledTask = ScheduledTask(
    id = "task-" + UUID.randomUUID().toString().take(8),
    prompt = prompt,
    cadence = cadence,
    // The first fire is always in the future, whatever the cadence: a task the
    // user just created must not have already been "missed" by the phone being
    // slow to save it.
    nextRunAtMillis = firstFireFor(cadence, nowMillis, zone),
    createdAtMillis = nowMillis,
)

/**
 * The first fire of a brand new task.
 *
 * [TaskCadence.Once] honours [nowMillis] itself — a user picking "in 5 minutes"
 * means 5 minutes from now. Everything repeating starts a full interval out, so
 * "every hour" created at 10:59 fires at 11:00 rather than 10:59.
 */
fun firstFireFor(
    cadence: TaskCadence,
    nowMillis: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): Long = when (cadence) {
    TaskCadence.Once -> nowMillis
    is TaskCadence.EveryMinutes ->
        nowMillis + cadence.minutes.coerceIn(
            TaskCadence.MIN_EVERY_MINUTES,
            TaskCadence.MAX_EVERY_MINUTES,
        ) * 60_000L

    is TaskCadence.DailyAt -> TaskCadence.nextAfter(cadence, nowMillis, zone) ?: nowMillis
}

/**
 * Persistence for scheduled tasks.
 *
 * ## Why SharedPreferences and not Room
 *
 * `:android` declares Room as `implementation`, so from `:app` the
 * `RoomDatabase` supertype of `LocalIntelligenceDatabase` does not resolve and
 * any direct database call here would not compile — the same constraint that
 * forced the [dev.localintelligence.android.data.resilientMemoryStore] factory.
 * The data is a handful of short strings, the read happens on a boot broadcast
 * where a synchronous read is the point, and this module is Compose-only by
 * policy. Room would be the wrong tool as well as the unavailable one.
 *
 * ## Why a JSON array and not the pipe-delimited rows `AlarmRegistry` uses
 *
 * A scheduled task carries a free-text prompt that the user typed. A delimiter
 * scheme for that is a data-loss bug waiting for a prompt containing the
 * delimiter, and `AlarmRegistry` is careful about exactly that for its labels
 * (`FIELD_SEPARATOR = 0x1F`, and `coerceLabel` strips control characters to
 * guarantee it cannot appear). Sanitising user prose to make a storage format
 * safe is worse than using a real encoder. kotlinx-serialization is already a
 * declared dependency of `:core` with `api` scope, so this adds nothing.
 *
 * ## Why java.time is never serialised
 *
 * Only epoch millis and two `Int`s are stored. The calendar arithmetic happens
 * in [TaskCadence.nextAfter] against the live zone. An `Instant` in a
 * preferences file pins a schedule to the offset that was current when it was
 * written, which is a bug a user in a different timezone would find months
 * later.
 */
class ScheduledTaskStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Oldest first, so "the task I added first" is a stable thing to name. */
    fun all(): List<ScheduledTask> {
        val raw = try {
            prefs.getString(KEY_TASKS, null)
        } catch (e: ClassCastException) {
            // A prefs file written by a different version with a different type.
            // Returning empty is the honest reading: this app cannot parse it.
            null
        } ?: return emptyList()

        val array = try {
            JSON.parseToJsonElement(raw) as? JsonArray ?: return emptyList()
        } catch (e: Exception) {
            // Corrupt payload. Every task is lost, but the app starts — which is
            // strictly better than throwing inside a BroadcastReceiver.
            return emptyList()
        }

        return array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            try {
                JSON.decodeFromJsonElement(ScheduledTask.serializer(), obj)
            } catch (e: Exception) {
                // One malformed row must not empty the list. A schema change
                // that adds a field with no default lands here, drops that row
                // and keeps the rest.
                null
            }
        }.sortedBy { it.createdAtMillis }.take(MAX_TASKS)
    }

    fun byId(id: String): ScheduledTask? = all().firstOrNull { it.id == id }

    /** Inserts or replaces by id, then persists. Returns the stored task. */
    fun upsert(task: ScheduledTask): ScheduledTask {
        val current = all().filterNot { it.id == task.id } + task
        persist(current.sortedBy { it.createdAtMillis })
        return task
    }

    /** Removes by id. Returns true when something was actually removed. */
    fun remove(id: String): Boolean {
        val current = all()
        if (current.none { it.id == id }) return false
        persist(current.filterNot { it.id == id })
        return true
    }

    /**
     * Writes the current set out. Best-effort by design: the caller has usually
     * just armed or disarmed a real AlarmManager PendingIntent, and losing the
     * listing is strictly better than losing the schedule the user can see.
     */
    private fun persist(tasks: List<ScheduledTask>) {
        try {
            // The serializer is named explicitly rather than relying on the
            // reified `encodeToJsonElement` extension: inside a `map` lambda the
            // receiver type is inferred from the lambda parameter alone, and the
            // compiler cannot see that it is a `ScheduledTask`. Naming the
            // serializer is also the form that keeps working if this class ever
            // holds a second type.
            val array = JsonArray(
                tasks.take(MAX_TASKS).map { JSON.encodeToJsonElement(ScheduledTask.serializer(), it) },
            )
            prefs.edit().putString(KEY_TASKS, array.toString()).apply()
        } catch (e: IllegalStateException) {
            // apply() on a store torn down mid-write. The alarm is still armed.
        }
    }

    companion object {
        const val PREFS_NAME = "scheduled_tasks"
        const val KEY_TASKS = "tasks_v1"

        /**
         * Cap on stored tasks.
         *
         * Bounds the work a boot broadcast has to do — it re-arms every enabled
         * task, and an unbounded list turns a 30-millisecond SharedPreferences
         * read into an unbounded number of system calls on a cold process.
         */
        const val MAX_TASKS = 16

        private val JSON = Json { ignoreUnknownKeys = true }
    }
}
