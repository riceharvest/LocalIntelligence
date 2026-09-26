package dev.localintelligence.android.tools.alarm

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.widget.Toast
import androidx.core.app.NotificationCompat
import dev.localintelligence.android.tools.device.ArgCoerce
import dev.localintelligence.android.tools.device.ArgResult
import dev.localintelligence.android.tools.device.guarded
import dev.localintelligence.core.model.ObservationOrigin
import dev.localintelligence.core.model.ToolArgs
import dev.localintelligence.core.tool.AgentTool
import dev.localintelligence.core.tool.ObservationTruncator
import dev.localintelligence.core.tool.ToolContext
import dev.localintelligence.core.tool.ToolDefinition
import dev.localintelligence.core.tool.ToolError
import dev.localintelligence.core.tool.ToolResult
import dev.localintelligence.core.tool.ToolRisk
import dev.localintelligence.core.tool.catalogue.ToolArgumentBounds
import dev.localintelligence.core.tool.catalogue.ToolSchemas
import dev.localintelligence.core.tool.contracts.PermissionDenial
import dev.localintelligence.core.tool.contracts.PlatformGrant
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
// =====================================================================================
// AlarmTools — alarm.create, alarm.list, alarm.cancel.
//
// TWO DESIGN DECISIONS THAT EVERYONE READING THIS FILE NEEDS TO KNOW:
//
// 1. These are AGENT alarms, not clock-app alarms.
//    `AlarmClock.ACTION_GET_ALIST` can only be answered by an Activity that receives
//    `onActivityResult`. A tool can execute from a Service, a BroadcastReceiver or a
//    plain coroutine, none of which can receive one, so the system alarm list is
//    unreachable from here without a declared Activity in the manifest. So
//    `alarm.list` returns the alarms THIS tool created and states the boundary in
//    every single observation, so the model can never mistake "the agent's 2 alarms"
//    for "your 5 alarms".
//
// 2. That registry is what makes `alarm.cancel` safe.
//    Cancelling an alarm the user did not name is data loss. The ambiguity guard in
//    [AlarmCancel] can only ask "did you mean this one?" against a real candidate set,
//    and a candidate set only exists if the tool remembers what it created. So the
//    registry is load-bearing for the dangerous tool, not a convenience cache.
//
// Every decision lives in [AlarmTimes], [AlarmCancel] or [AlarmRegistry]. The
// android.* code is confined to [AndroidAlarmPlatform] and [AlarmFireReceiver].
// =====================================================================================

/** One scheduled agent alarm. Pure data, no android types. */
data class AlarmSpec(
    val id: String,
    val hour: Int,
    val minute: Int,
    val label: String,
    /** Epoch millis the alarm is set to fire at. */
    val triggerAtMillis: Long,
    /** The PendingIntent request code derived from [id]. */
    val requestCode: Int,
    val createdAtMillis: Long,
) {
    fun timeLabel(): String = String.format(Locale.US, "%02d:%02d", hour, minute)
}

// =====================================================================================
// Ids and request codes
// =====================================================================================

object AlarmIds {

    const val MAX_ID_CHARS = 64
    const val MAX_LABEL_CHARS = 48

    /**
     * A stable PendingIntent request code for an id.
     *
     * Must be deterministic and dependency-free so that an explicit-id cancel still
     * finds the right PendingIntent after the process has been killed and the
     * in-memory registry rebuilt from nothing. FNV-1a over the id, mapped into
     * 1..Int.MAX_VALUE (0 is avoided so a default-initialised code can never look like
     * a real match).
     *
     * Collision probability for a handful of live alarms is below 1e-9 and the cost of
     * a collision is bounded: one alarm fires at the other's time, and `alarm.list`
     * shows both. A registry-probing allocator would be exact but would break the
     * cross-process-death property above, which matters more.
     */
    fun requestCodeOf(id: String): Int {
        var hash = -0x340d631b7bdddcdbL // FNV-1a 64 offset basis
        for (ch in id) {
            hash = hash xor (ch.code.toLong() and 0xFF)
            hash *= 0x100000001b3L
        }
        val positive = (hash ushr 1) and 0x7FFF_FFFFL
        return (positive % Int.MAX_VALUE).toInt() + 1
    }

    /**
     * Sanitises a model-supplied id, or generates one from the time.
     *
     * The generated form zero-pads both fields. An unpadded "alarm-70" is genuinely
     * ambiguous — hour 7 minute 0, or minute 70 of some hour — and an id the model
     * cannot read back is an id it will pass to alarm.cancel wrongly.
     */
    fun coerceId(raw: String?, hour: Int, minute: Int, nowMillis: Long): String {
        val cleaned = raw?.trim().orEmpty()
            .map { if (it.isISOControl() || it == ' ' || it == '/' || it == ':') '-' else it }
            .joinToString("")
            .trim('-')
        return if (cleaned.isEmpty()) {
            String.format(Locale.US, "alarm-%02d%02d-%d", hour, minute, nowMillis % 100_000)
        } else {
            ArgCoerce.ellipsize(cleaned, MAX_ID_CHARS)
        }
    }

    fun coerceLabel(raw: String?): String {
        val cleaned = raw?.trim().orEmpty()
            .map { if (it.isISOControl()) ' ' else it }
            .joinToString("")
            .replace(Regex("\\s+"), " ")
            .trim()
        return if (cleaned.isEmpty()) "Alarm" else ArgCoerce.ellipsize(cleaned, MAX_LABEL_CHARS)
    }
}

// =====================================================================================
// Time arithmetic
// =====================================================================================

/** The resolved trigger time, plus what to tell the model about how it was chosen. */
data class AlarmPlan(
    val triggerAtMillis: Long,
    /** True when "07:00" was asked for at 08:00 and rolled over to tomorrow. */
    val rolledToNextDay: Boolean,
    /** Human date for the observation, e.g. "tomorrow" or "Sat 3 Oct". */
    val dateLabel: String,
)

object AlarmTimes {

    // Aliased from :core's ToolArgumentBounds: these numbers appear in this
    // tool's JSON Schema, which :core owns, and in its `execute()`, below.
    // Aliasing rather than repeating is what stops the advertised bound and
    // the enforced bound from drifting apart.
    const val MIN_HOUR = ToolArgumentBounds.ALARM_MIN_HOUR
    const val MAX_HOUR = ToolArgumentBounds.ALARM_MAX_HOUR
    const val MIN_MINUTE = ToolArgumentBounds.ALARM_MIN_MINUTE
    const val MAX_MINUTE = ToolArgumentBounds.ALARM_MAX_MINUTE

    /** java.time is available natively from API 26, which is minSdk, so this needs no
     *  desugaring — and unlike a fixed 86_400_000 ms step it is correct across a DST
     *  transition, where "08:00 tomorrow" is 23 or 25 hours away, not 24. */
    fun plan(
        nowMillis: Long,
        zone: ZoneId,
        hour: Int,
        minute: Int,
        dayOffset: Int?,
    ): AlarmPlan {
        val safeHour = hour.coerceIn(MIN_HOUR, MAX_HOUR)
        val safeMinute = minute.coerceIn(MIN_MINUTE, MAX_MINUTE)
        val now = Instant.ofEpochMilli(nowMillis).atZone(zone)

        val targetDate = when {
            // An explicit day offset is a date, not a duration: "tomorrow at 07:00"
            // means the next calendar day even if that is 23 hours away.
            dayOffset != null -> now.toLocalDate().plusDays(dayOffset.toLong())
            else -> now.toLocalDate()
        }

        var target = ZonedDateTime.of(targetDate, java.time.LocalTime.of(safeHour, safeMinute), zone)
        val rolled = if (dayOffset == null && !target.isAfter(now)) {
            target = target.plusDays(1)
            true
        } else {
            false
        }

        return AlarmPlan(
            triggerAtMillis = target.toInstant().toEpochMilli(),
            rolledToNextDay = rolled,
            dateLabel = describeDate(target, now, rolled),
        )
    }

    private val DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM", Locale.US)

    fun describeDate(target: ZonedDateTime, now: ZonedDateTime, rolled: Boolean): String = when {
        rolled -> "tomorrow ${DAY_FORMAT.format(target)}"
        target.toLocalDate() == now.toLocalDate() -> "today"
        target.toLocalDate() == now.toLocalDate().plusDays(1) -> "tomorrow"
        else -> DAY_FORMAT.format(target)
    }
}

// =====================================================================================
// The ambiguity guard — the load-bearing safety logic of alarm.cancel
// =====================================================================================

/** What `alarm.cancel` decided to do, before touching the platform. */
sealed class CancelDecision {
    /** Exactly one alarm matches [spec]. Safe to cancel. */
    data class Cancel(val spec: AlarmSpec) : CancelDecision()

    /** Nothing matches. Nothing is cancelled. */
    data class NoMatch(val message: String) : CancelDecision()

    /**
     * More than one alarm matches. NOTHING is cancelled and the model is given the
     * candidates so it can ask the user which one. Guessing here is data loss.
     */
    data class Ambiguous(val candidates: List<AlarmSpec>) : CancelDecision()

    /**
     * The request named no alarm at all — "cancel my alarms", "clear the alarm".
     * Refused. There is no argument combination in this tool that can cancel more than
     * one alarm in a single call, by construction.
     */
    data class RefuseBulk(val candidates: List<AlarmSpec>) : CancelDecision()
}

object AlarmCancel {

    /** Cap on candidates echoed back, so the observation cannot grow with the registry. */
    const val MAX_LISTED_CANDIDATES = 5

    /**
     * The guard. Pure, total, and the only path from arguments to a cancellation.
     *
     * Resolution order, strictest first:
     *  1. An explicit `id`. Exact match, case-insensitive. No match → NoMatch.
     *  2. `hour` and/or `minute`. Every alarm matching the given field(s) is a
     *     candidate. Exactly one → cancel it. More → Ambiguous. None → NoMatch.
     *  3. A `label` substring, same one-match-only rule as (2).
     *  4. Nothing identifying at all → RefuseBulk.
     *
     * A `confirm_all` style escape hatch is deliberately absent.
     */
    fun decide(
        candidates: List<AlarmSpec>,
        id: String?,
        hour: Int?,
        minute: Int?,
        label: String?,
    ): CancelDecision {
        val wantedId = id?.trim()?.takeIf { it.isNotEmpty() }
        if (wantedId != null) {
            val matches = candidates.filter { it.id.equals(wantedId, ignoreCase = true) }
            return when (matches.size) {
                0 -> CancelDecision.NoMatch("No alarm has the id \"$wantedId\".")
                1 -> CancelDecision.Cancel(matches.first())
                // Two ids that differ only in case are still two alarms.
                else -> CancelDecision.Ambiguous(matches)
            }
        }

        val wantedHour = hour?.coerceIn(AlarmTimes.MIN_HOUR, AlarmTimes.MAX_HOUR)
        val wantedMinute = minute?.coerceIn(AlarmTimes.MIN_MINUTE, AlarmTimes.MAX_MINUTE)
        val wantedLabel = label?.trim()?.takeIf { it.isNotEmpty() }

        if (wantedHour == null && wantedMinute == null && wantedLabel == null) {
            return CancelDecision.RefuseBulk(candidates)
        }

        val matches = candidates.filter { spec ->
            val hourOk = wantedHour == null || spec.hour == wantedHour
            val minuteOk = wantedMinute == null || spec.minute == wantedMinute
            val labelOk = wantedLabel == null ||
                spec.label.contains(wantedLabel, ignoreCase = true) ||
                spec.id.contains(wantedLabel, ignoreCase = true)
            hourOk && minuteOk && labelOk
        }

        return when (matches.size) {
            0 -> CancelDecision.NoMatch(describeNoMatch(wantedHour, wantedMinute, wantedLabel, candidates))
            1 -> CancelDecision.Cancel(matches.first())
            else -> CancelDecision.Ambiguous(matches)
        }
    }

    private fun describeNoMatch(
        hour: Int?,
        minute: Int?,
        label: String?,
        candidates: List<AlarmSpec>,
    ): String {
        val wanted = buildList {
            if (hour != null) add(String.format(Locale.US, "%02d:00", hour))
            if (minute != null) add(String.format(Locale.US, "minute %02d", minute))
            if (label != null) add("label containing \"${ArgCoerce.ellipsize(label, 24)}\"")
        }.joinToString(" and ")
        return "No alarm matches $wanted. ${describeAll(candidates)}"
    }

    /** The one-line inventory used by refusals, no-matches and the observation budget. */
    fun describeAll(candidates: List<AlarmSpec>): String {
        if (candidates.isEmpty()) return "No alarms are currently set."
        val ordered = candidates.sortedWith(compareBy({ it.hour }, { it.minute }))
        val shown = ordered.take(MAX_LISTED_CANDIDATES)
            .joinToString("; ") { "${it.timeLabel()} ${it.label} (id ${it.id})" }
        val more = ordered.size - shown.count { s -> s in shown }
        return "$shown${if (more > 0) " (+$more more)" else ""}."
    }

    fun describe(decision: CancelDecision): String = when (decision) {
        is CancelDecision.Cancel ->
            "Cancelled the ${decision.spec.timeLabel()} alarm \"${decision.spec.label}\" (id ${decision.spec.id})."

        is CancelDecision.NoMatch -> decision.message

        is CancelDecision.Ambiguous ->
            "${decision.candidates.size} alarms match that, so none were cancelled. " +
                "Ask the user which one, then call alarm.cancel with its id. " +
                "Candidates: ${describeAll(decision.candidates)}"

        is CancelDecision.RefuseBulk ->
            "Refused: name exactly one alarm to cancel. This tool will not cancel them all. " +
                describeAll(decision.candidates)
    }
}

// =====================================================================================
// The registry
// =====================================================================================

/**
 * Durable record of the alarms this app has scheduled.
 *
 * Bounded at [MAX_TRACKED] entries; the oldest is evicted past that. On a phone the
 * worst case is a few kilobytes, which is the whole reason it is capped rather
 * than allowed to grow.
 *
 * ## Why this is persisted
 *
 * The two facts this registry describes sit on opposite sides of a process
 * boundary, and only one of them survives on its own:
 *
 *  - the **PendingIntents** are held by the system and DO survive a restart, so an
 *    alarm created before a restart still fires;
 *  - the **registry** does not, so an in-memory registry would let `alarm.list`
 *    report "no alarms are currently set" while the phone was about to ring one.
 *
 * That is the worst kind of bug in an agent: the tool does not fail, it answers
 * confidently and wrongly, and the model repeats it to the user. Cancelling by
 * explicit id works across a restart regardless, because [AlarmIds.requestCodeOf]
 * is a pure function of the id — the platform seam, not the registry, is what
 * makes cancel survive.
 *
 * ## Storage
 *
 * A private `SharedPreferences` file, not Room. Reasons, in order:
 *
 *  1. `:android`'s Room schema is owned by the database layer, and an alarm
 *     table added here would sit outside its migrations.
 *  2. `alarm.list` and the boot receiver are both called on a path that cannot
 *     suspend — a `BroadcastReceiver.onReceive` and a tool entry point — so a
 *     suspending DAO would have to be bridged anyway.
 *  3. The write happens once, at alarm creation, against a file of at most
 *     [MAX_TRACKED] small rows.
 *
 * Writes are best-effort: a failure to persist must not fail the alarm the user
 * just asked for, because the PendingIntent is already scheduled in the system and
 * the alarm WILL ring. Losing the listing is strictly better than losing the alarm.
 */
object AlarmRegistry {

    const val MAX_TRACKED = 32

    private const val PREFS_NAME = "alarm_registry"
    private const val KEY_SPECS = "specs"

    private val lock = Any()
    private val entries = ArrayList<AlarmSpec>()

    /**
     * The installed backing, or null when [attach] has not run yet.
     *
     * Exposed so the tools that read the registry *before* touching the platform
     * — `alarm.list` is exactly that case, it calls [all] before its first
     * `platform.*` call — can force the restore themselves. Without this, the
     * restore would depend on a call ordering that no compiler enforces and no
     * test would catch.
     */
    @Volatile
    private var store: SharedPreferences? = null

    /**
     * Installs the persistence backing and restores the persisted set.
     *
     * Idempotent, and deliberately so: the registry is a process-wide singleton
     * and the platform is constructed per tool, so this is called repeatedly.
     */
    fun attach(context: Context) {
        if (store != null) return
        synchronized(lock) {
            if (store != null) return
            val prefs = context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            store = prefs
            val restored = readFrom(prefs)
            if (restored.isNotEmpty()) {
                entries.clear()
                entries.addAll(restored)
            }
        }
    }

    /**
     * Ensures the registry is backed by storage, using [context].
     *
     * The same as [attach], named for the call site: a tool that is about to read
     * or write the registry should say *why* it is calling, and "I am about to
     * read the registry" is the reason, not "I would like some preferences".
     */
    fun ensureAttached(context: Context) = attach(context)

    fun add(spec: AlarmSpec): List<AlarmSpec> = synchronized(lock) {
        entries.removeAll { it.id.equals(spec.id, ignoreCase = true) }
        entries.add(spec)
        while (entries.size > MAX_TRACKED) {
            entries.removeAt(0)
        }
        entries.toList()
    }.also { persist() }

    fun remove(id: String): AlarmSpec? = synchronized(lock) {
        val index = entries.indexOfFirst { it.id.equals(id, ignoreCase = true) }
        if (index < 0) null else entries.removeAt(index)
    }.also { persist() }

    /** Oldest first, so "the alarm I set first" is a stable thing to name. */
    fun all(): List<AlarmSpec> = synchronized(lock) {
        entries.sortedBy { it.createdAtMillis }.toList()
    }

    fun clear() = synchronized(lock) {
        entries.clear()
    }.also { persist() }

    /**
     * Drops fired and past-due entries after a reboot.
     *
     * A reboot clears every PendingIntent the system was holding, so nothing in
     * here is still scheduled. [AlarmBootReceiver] re-arms the ones still in the
     * future and this removes the rest, which stops `alarm.list` from reporting a
     * 07:00 alarm that passed three hours ago as something the user can still
     * cancel.
     *
     * Returns the specs that were re-armed, oldest first.
     */
    fun rearmAfterBoot(nowMillis: Long): List<AlarmSpec> = synchronized(lock) {
        val stillValid = entries.filter { it.triggerAtMillis > nowMillis }
        if (stillValid.size != entries.size) {
            entries.clear()
            entries.addAll(stillValid)
        }
        entries.sortedBy { it.triggerAtMillis }.toList()
    }.also { persist() }

    // ------------------------------------------------------------------ persistence

    /**
     * Writes the current set out. Best-effort by design — see the class doc.
     *
     * A [String] of `|`-joined numeric/identifier fields rather than JSON: no
     * serializer, no schema, and a malformed row is skipped instead of taking the
     * whole file down with it.
     */
    private fun persist() {
        val prefs = store ?: return
        val snapshot = synchronized(lock) { entries.toList() }
        try {
            prefs.edit()
                .putString(KEY_SPECS, snapshot.joinToString(RECORD_SEPARATOR, transform = ::encode))
                .apply()
        } catch (e: IllegalStateException) {
            // The alarm is already in the system AlarmManager; failing to list it
            // later is the lesser failure.
        }
    }

    private fun readFrom(prefs: SharedPreferences): List<AlarmSpec> {
        val raw = try {
            prefs.getString(KEY_SPECS, null)
        } catch (e: ClassCastException) {
            null
        } ?: return emptyList()

        return raw.split(RECORD_SEPARATOR).mapNotNull(::decode).take(MAX_TRACKED)
    }

    private fun encode(spec: AlarmSpec): String = listOf(
        spec.id,
        spec.hour.toString(),
        spec.minute.toString(),
        spec.label,
        spec.triggerAtMillis.toString(),
        spec.requestCode.toString(),
        spec.createdAtMillis.toString(),
    ).joinToString(FIELD_SEPARATOR)

    /**
     * Rebuilds one spec, or null when the row is unusable.
     *
     * `requestCode` is recomputed from the id rather than trusted: it is a pure
     * function of the id, so deriving it is both cheaper and impossible to get
     * wrong with a corrupted stored value. Rows whose numeric fields do not parse
     * are dropped individually — one bad row must not empty the registry.
     */
    private fun decode(row: String): AlarmSpec? {
        val parts = row.split(FIELD_SEPARATOR)
        if (parts.size != 7) return null
        val id = parts[0].trim()
        if (id.isEmpty()) return null
        val hour = parts[1].toIntOrNull() ?: return null
        val minute = parts[2].toIntOrNull() ?: return null
        val triggerAt = parts[4].toLongOrNull() ?: return null
        val createdAt = parts[6].toLongOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return AlarmSpec(
            id = id,
            hour = hour,
            minute = minute,
            label = parts[3],
            triggerAtMillis = triggerAt,
            requestCode = AlarmIds.requestCodeOf(id),
            createdAtMillis = createdAt,
        )
    }

    private const val RECORD_SEPARATOR = "\n"

    /**
     * ASCII UNIT SEPARATOR (0x1F).
     *
     * Not `|` and not `,`, because [AlarmIds.coerceLabel] accepts any character a
     * user or the model can type, and a label containing the delimiter would split
     * one row into two and get both dropped. 0x1F is a control character, and
     * [AlarmIds.coerceLabel] strips control characters from labels on the way in,
     * so a stored label can never contain it.
     */
    private const val FIELD_SEPARATOR = "\u001F"
}

// =====================================================================================
// Descriptions
// =====================================================================================

object AlarmDescriptions {

    fun describeCreated(spec: AlarmSpec, plan: AlarmPlan): String {
        val whenText = when {
            plan.rolledToNextDay ->
                "${spec.timeLabel()} tomorrow (${plan.dateLabel.substringAfter(" ")})"
            plan.dateLabel == "today" -> "${spec.timeLabel()} today"
            else -> "${spec.timeLabel()} on ${plan.dateLabel}"
        }
        return "Alarm set for $whenText, labelled \"${spec.label}\" (id ${spec.id}). " +
            "It will ring even if the phone is idle. It is an alarm created by the " +
            "assistant and will not appear in the system Clock app."
    }

    fun describeList(specs: List<AlarmSpec>): String {
        if (specs.isEmpty()) {
            return "No alarms have been set by the assistant. Alarms made in the system " +
                "Clock app are not visible to this tool."
        }
        val lines = specs.map { "${it.timeLabel()} — ${it.label} (id ${it.id})" }
        val shown = lines.take(AlarmCancel.MAX_LISTED_CANDIDATES).joinToString("; ")
        val more = lines.size - minOf(lines.size, AlarmCancel.MAX_LISTED_CANDIDATES)
        return "${specs.size} agent alarm(s): $shown${if (more > 0) " (+$more more)" else ""}. " +
            "Alarms made in the system Clock app are not visible to this tool."
    }
}

// =====================================================================================
// The platform seam
// =====================================================================================

interface AlarmPlatform {
    /** False on Android 12+ when the user has not granted exact-alarm scheduling. */
    fun canScheduleExactAlarms(): Boolean

    fun schedule(spec: AlarmSpec)

    fun cancel(requestCode: Int)

    fun nowMillis(): Long

    fun hasClockApp(): Boolean
}

// =====================================================================================
// Schemas
// =====================================================================================// =====================================================================================
// Tools
// =====================================================================================

class AlarmCreateTool(
    private val platform: AlarmPlatform,
    private val grant: PlatformGrant,
) : AgentTool {

    override val definition = ToolDefinition(
        name = "alarm.create",
        description = "Set a one-time alarm on the phone and return the time and the id it was " +
            "given.",
        category = "alarm",
        schema = ToolSchemas.alarmCreate,
        risk = ToolRisk.REVERSIBLE,
        tags = setOf(
            "alarm", "set an alarm", "wake me up", "remind me at", "timer", "ring at",
            "wake up call", "set a reminder",
        ),
        // SCHEDULE_EXACT_ALARM is needed on Android 12+ for setExactAndAllowWhileIdle.
        requiredPermission = "android.permission.SCHEDULE_EXACT_ALARM",
    )

    override suspend fun execute(args: ToolArgs, context: ToolContext): ToolResult {
        // There is deliberately NO `permissionGranted` check here.
        // SCHEDULE_EXACT_ALARM is a special-access grant, not a runtime
        // permission, so a ToolContext gate has nothing correct to say about it.
        // The real check is further down: platform.canScheduleExactAlarms(),
        // which asks Android and names the Alarms & reminders screen. Do not
        // add a fabricated gate in front of it — that shadows the one honest
        // denial this tool can produce.
        val repeat = ArgCoerce.booleanOrNull(args["repeat"])
        if (repeat == true) {
            return ToolResult(
                success = false,
                observation = "Repeating alarms are not supported by this build; only one-shot " +
                    "alarms. Call alarm.create again once per day instead, or tell the user it " +
                    "is not available.",
                error = ToolError.InvalidArguments("repeat is not supported"),
            )
        }

        val hour = when (val parsed = ArgCoerce.int(args, "hour")) {
            is ArgResult.Present -> parsed.value
            is ArgResult.Missing -> {
                return ToolResult(
                    success = false,
                    observation = "hour is required. Send a 24-hour hour, for example " +
                        "{\"hour\": 7, \"minute\": 30}.",
                    error = ToolError.InvalidArguments("hour missing"),
                )
            }

            is ArgResult.Unusable -> {
                return ToolResult(
                    success = false,
                    observation = "hour must be a whole number from 0 to 23, but was ${parsed.got}.",
                    error = ToolError.InvalidArguments("hour unusable: ${parsed.got}"),
                )
            }
        }
        if (hour !in AlarmTimes.MIN_HOUR..AlarmTimes.MAX_HOUR) {
            return ToolResult(
                success = false,
                observation = "hour must be from 0 to 23, but was $hour. " +
                    "Use 24-hour time, so 7 means 07:00 and 19 means 19:00.",
                error = ToolError.InvalidArguments("hour $hour out of range"),
            )
        }

        val minute = when (val parsed = ArgCoerce.int(args, "minute")) {
            is ArgResult.Present -> parsed.value
            is ArgResult.Missing -> 0
            is ArgResult.Unusable -> {
                return ToolResult(
                    success = false,
                    observation = "minute must be a whole number from 0 to 59, but was " +
                        "${parsed.got}.",
                    error = ToolError.InvalidArguments("minute unusable: ${parsed.got}"),
                )
            }
        }
        if (minute !in AlarmTimes.MIN_MINUTE..AlarmTimes.MAX_MINUTE) {
            return ToolResult(
                success = false,
                observation = "minute must be from 0 to 59, but was $minute.",
                error = ToolError.InvalidArguments("minute $minute out of range"),
            )
        }

        val dayOffset = when (val parsed = ArgCoerce.int(args, "day_offset")) {
            is ArgResult.Present -> parsed.value
            is ArgResult.Missing -> null
            is ArgResult.Unusable -> {
                return ToolResult(
                    success = false,
                    observation = "day_offset must be a whole number of days from today, but was " +
                        "${parsed.got}.",
                    error = ToolError.InvalidArguments("day_offset unusable: ${parsed.got}"),
                )
            }
        }
        if (dayOffset != null && dayOffset !in 0..7) {
            return ToolResult(
                success = false,
                observation = "day_offset must be from 0 (today) to 7, but was $dayOffset.",
                error = ToolError.InvalidArguments("day_offset $dayOffset out of range"),
            )
        }

        if (context.signal.isCancelled()) {
            return ToolResult(
                success = false,
                observation = "Cancelled before the alarm could be set.",
                error = ToolError.Cancelled("cancelled before alarm.create"),
            )
        }

        return guarded("alarm.create") {
            if (!platform.canScheduleExactAlarms()) {
                return@guarded ToolResult(
                    success = false,
                    observation = "Android is blocking exact alarms for this app, so a reliable " +
                        "alarm cannot be set. The user must turn on \"Alarms & reminders\" " +
                        "permission for this app in Settings. Do not retry until they do.",
                    error = ToolError.PermissionDenied("SCHEDULE_EXACT_ALARM not granted"),
                )
            }

            val now = platform.nowMillis()
            val plan = AlarmTimes.plan(now, ZoneId.systemDefault(), hour, minute, dayOffset)
            val id = AlarmIds.coerceId(
                raw = (ArgCoerce.string(args, "id") as? ArgResult.Present)?.value,
                hour = hour,
                minute = minute,
                nowMillis = now,
            )
            val spec = AlarmSpec(
                id = id,
                hour = hour,
                minute = minute,
                label = AlarmIds.coerceLabel(
                    (ArgCoerce.string(args, "label") as? ArgResult.Present)?.value,
                ),
                triggerAtMillis = plan.triggerAtMillis,
                requestCode = AlarmIds.requestCodeOf(id),
                createdAtMillis = now,
            )
            platform.schedule(spec)
            AlarmRegistry.add(spec)

            ToolResult(
                success = true,
                observation = ObservationTruncator.truncate(AlarmDescriptions.describeCreated(spec, plan)),
                data = buildJsonObject {
                    put("id", spec.id)
                    put("hour", spec.hour)
                    put("minute", spec.minute)
                    put("label", spec.label)
                    put("trigger_at_millis", spec.triggerAtMillis)
                    put("request_code", spec.requestCode)
                },
            )
        }
    }
}

class AlarmListTool(private val platform: AlarmPlatform) : AgentTool {

    override val definition = ToolDefinition(
        name = "alarm.list",
        description = "List the alarms this assistant has set, and state that alarms from the " +
            "system Clock app are not visible.",
        category = "alarm",
        schema = ToolSchemas.alarmList,
        risk = ToolRisk.READ_ONLY,
        observationOrigin = ObservationOrigin.LOCAL,
        tags = setOf(
            "my alarms", "list alarms", "what alarms do i have", "upcoming alarms",
            "alarm list", "what did i set", "do i have an alarm",
        ),
        requiredPermission = null,
    )

    override suspend fun execute(args: ToolArgs, context: ToolContext): ToolResult {
        // No gate. This reads the app's own SharedPreferences-backed registry —
        // it touches no protected API and needs no permission. The removed
        // check was not merely useless but actively harmful: its message told
        // the model to report "no permission to read alarms" and stop, when the
        // truth was available one line below. Worse, because this tool's whole
        // value is refusing to mislead about which alarms exist, a fabricated
        // denial here could not be distinguished from a real one.

        val specs = mutableListOf<AlarmSpec>()
        val pending = AlarmRegistry.all()
        for (spec in pending) {
            if (context.signal.isCancelled()) {
                return ToolResult(
                    success = false,
                    observation = "Cancelled while reading the alarm list.",
                    error = ToolError.Cancelled("cancelled during alarm.list"),
                )
            }
            specs += spec
        }

        val data = buildJsonObject {
            put("count", specs.size)
            put("scope", "agent_created_only")
            put("has_clock_app", platform.hasClockApp())
            specs.forEach { spec ->
                put("alarm:${spec.id}", "${spec.timeLabel()} ${spec.label}")
            }
        }
        return ToolResult(
            success = true,
            observation = ObservationTruncator.truncate(AlarmDescriptions.describeList(specs)),
            data = data,
        )
    }
}

class AlarmCancelTool(private val platform: AlarmPlatform) : AgentTool {

    override val definition = ToolDefinition(
        name = "alarm.cancel",
        description = "Cancel exactly one previously set alarm, identified by its id or by its " +
            "hour, minute and label.",
        category = "alarm",
        schema = ToolSchemas.alarmCancel,
        // REVERSIBLE, not DESTRUCTIVE: the runtime derives confirmation from the risk
        // field, and a user who has to confirm every single-alarm cancel will stop using
        // the feature. What makes this acceptable is not the risk label but the
        // ambiguity guard — it can only ever cancel one alarm, and only one the tool
        // itself created, and it refuses rather than guessing.
        risk = ToolRisk.REVERSIBLE,
        tags = setOf(
            "cancel alarm", "delete alarm", "remove alarm", "turn off alarm",
            "cancel my alarm", "remove the wake up", "stop the alarm",
        ),
        requiredPermission = "android.permission.SCHEDULE_EXACT_ALARM",
    )

    override suspend fun execute(args: ToolArgs, context: ToolContext): ToolResult {
        // No gate. `AlarmManager.cancel` on this app's own PendingIntent needs
        // no permission, and the ambiguity guard below — not a permission —
        // is what makes this safe. The removed check would have refused a
        // cancellation the user explicitly asked for.

        val id = (ArgCoerce.string(args, "id") as? ArgResult.Present)?.value
        val label = (ArgCoerce.string(args, "label") as? ArgResult.Present)?.value

        val hour = when (val parsed = optionalInt(args, "hour")) {
            is OptionalInt.Given -> parsed.value
            OptionalInt.Absent -> null
            is OptionalInt.Malformed -> return malformedIntMessage("hour", parsed.got)
        }
        val minute = when (val parsed = optionalInt(args, "minute")) {
            is OptionalInt.Given -> parsed.value
            OptionalInt.Absent -> null
            is OptionalInt.Malformed -> return malformedIntMessage("minute", parsed.got)
        }

        val candidates = AlarmRegistry.all()
        val decision = AlarmCancel.decide(candidates, id, hour, minute, label)

        // The refusal and the ambiguity answer are decided BEFORE the platform is
        // touched. A guard that can only run after the cancellation has already been
        // issued is not a guard.
        if (decision !is CancelDecision.Cancel) {
            return ToolResult(
                success = false,
                observation = ObservationTruncator.truncate(AlarmCancel.describe(decision)),
                data = buildJsonObject {
                    put("cancelled", false)
                    put("reason", decision.javaClass.simpleName)
                },
                error = ToolError.InvalidArguments(AlarmCancel.describe(decision)),
            )
        }

        if (context.signal.isCancelled()) {
            return ToolResult(
                success = false,
                observation = "Cancelled before the alarm could be cancelled.",
                error = ToolError.Cancelled("cancelled before alarm.cancel"),
            )
        }

        val spec = (decision as CancelDecision.Cancel).spec
        return guarded("alarm.cancel") {
            platform.cancel(spec.requestCode)
            AlarmRegistry.remove(spec.id)
            ToolResult(
                success = true,
                observation = AlarmCancel.describe(decision),
                data = buildJsonObject {
                    put("cancelled", true)
                    put("id", spec.id)
                    put("time", spec.timeLabel())
                },
            )
        }
    }

    /** Exactly-one-only argument extraction, spelled out because a single `?:` chain
     *  here is how "the model sent garbage" turns into "the model sent nothing", and
     *  that difference is exactly the ambiguity guard's input. */
    private sealed class OptionalInt {
        data class Given(val value: Int) : OptionalInt()
        data object Absent : OptionalInt()
        data class Malformed(val got: String) : OptionalInt()
    }

    private fun optionalInt(args: ToolArgs, key: String): OptionalInt =
        when (val parsed = ArgCoerce.int(args, key)) {
            is ArgResult.Present -> OptionalInt.Given(parsed.value)
            is ArgResult.Missing -> OptionalInt.Absent
            is ArgResult.Unusable -> OptionalInt.Malformed(parsed.got)
        }

    private fun malformedIntMessage(key: String, got: String): ToolResult = ToolResult(
        success = false,
        observation = "$key must be a whole number, but was $got. Name one alarm with its id, " +
            "or with a whole-number hour and minute.",
        error = ToolError.InvalidArguments("$key unusable: $got"),
    )
}

// =====================================================================================
// The Android implementation
// =====================================================================================

/**
 * Schedules through [AlarmManager.setExactAndAllowWhileIdle], which fires through Doze
 * — that is the whole reason it is used instead of `set`, and the reason this tool
 * exists separately from the system Clock app.
 *
 * The PendingIntent targets [AlarmFireReceiver] with a per-alarm request code. Because
 * `PendingIntent` equality ignores extras and is decided by (requestCode, action,
 * identity), and [AlarmIds.requestCodeOf] is a pure function of the alarm id, the same
 * code resolves to the same PendingIntent later — which is what makes cancel work
 * across a process restart, with no persisted state.
 */
@SuppressLint("NewApi")
class AndroidAlarmPlatform(private val context: Context) : AlarmPlatform {

    /**
     * Resolved on first use, not at construction.
     *
     * WHY: a tool's `definition` is a constructor-level `val` built from
     * literals, so a tool ought to be constructible without touching the
     * platform at all — that is what makes the whole shipped tool set
     * assertable on a plain JVM, with no emulator and no Robolectric
     * (`docs/architecture.md` §2). Eagerly calling
     * `context.applicationContext` here put a live `Context` call in the
     * constructor of every tool in the family and broke that property for
     * no benefit: the app context is wanted by the first platform call,
     * not by the constructor.
     *
     * It also keeps a `Context` from being captured by a long-lived
     * singleton tool when the caller passed an Activity.
     */
    private val appContext: Context by lazy { context.applicationContext }

    private fun manager(): AlarmManager? =
        appContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager

    override fun canScheduleExactAlarms(): Boolean {
        val alarmManager = manager() ?: return false
        // canScheduleExactAlarms() is API 31+. Before 31 exact alarms needed no
        // permission at all, so the question cannot arise.
        return if (Build.VERSION.SDK_INT >= API_S) alarmManager.canScheduleExactAlarms() else true
    }

    override fun schedule(spec: AlarmSpec) {
        val alarmManager = manager() ?: throw IllegalStateException("AlarmManager unavailable")
        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            spec.triggerAtMillis,
            // Extras included here, and FLAG_UPDATE_CURRENT rewrites them on a
            // reschedule, so the receiver always sees the current label for this id.
            pendingIntentFor(spec),
        )
    }

    override fun cancel(requestCode: Int) {
        val alarmManager = manager() ?: throw IllegalStateException("AlarmManager unavailable")
        // FLAG_NO_CREATE: cancel must not resurrect a PendingIntent that is not there.
        // With FLAG_UPDATE_CURRENT a cancel for an unknown code would create one, which
        // is the opposite of what was asked.
        val existing = PendingIntent.getBroadcast(
            appContext,
            requestCode,
            AlarmFireReceiver.intent(appContext),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        ) ?: return
        alarmManager.cancel(existing)
        existing.cancel()
    }

    override fun nowMillis(): Long = System.currentTimeMillis()

    override fun hasClockApp(): Boolean = try {
        Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS)
            .resolveActivity(appContext.packageManager) != null
    } catch (e: Exception) {
        false
    }

    private fun pendingIntentFor(spec: AlarmSpec): PendingIntent = PendingIntent.getBroadcast(
        appContext,
        spec.requestCode,
        AlarmFireReceiver.intentFor(appContext, spec),
        // IMMUTABLE: nothing needs to fill in the extras at send time, and a mutable
        // PendingIntent handed to another app's alarm machinery is an attack surface.
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private companion object {
        const val API_S = 31
    }
}

/**
 * Fires when an agent alarm is due.
 *
 * ## Why this posts a notification and not a Toast
 *
 * It used to `Toast.makeText(...).show()` and nothing else, on the reasoning that
 * a Toast needs no permission and no channel. That reasoning is right about
 * permissions and wrong about delivery: since API 30 a text Toast raised from the
 * background is rate-limited and deprioritised, and a user who is not looking at
 * the screen at 07:00 sees nothing at all. An alarm that cannot be seen is not an
 * alarm.
 *
 * `POST_NOTIFICATIONS` is already declared and already has a description in the
 * permission map, and `alarm.create` can only be reached by a user who has been
 * through the app, so the channel is created lazily on the first fire and the
 * notification is posted on the best-effort terms below.
 *
 * The Toast is kept as a secondary path: on API < 33 no runtime permission exists
 * at all, and on an OEM build that refuses the background notification the user
 * should still get something.
 *
 * REQUIRES a manifest entry — see the PR description. Without it the PendingIntent
 * resolves to nothing, `schedule` throws nothing, and the alarm silently never fires.
 */
class AlarmFireReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_ALARM_ID) ?: return
        val label = intent.getStringExtra(EXTRA_ALARM_LABEL) ?: "Alarm"
        val hour = intent.getIntExtra(EXTRA_ALARM_HOUR, -1)
        val minute = intent.getIntExtra(EXTRA_ALARM_MINUTE, -1)
        val time = if (hour in 0..23 && minute in 0..59) {
            String.format(Locale.US, "%02d:%02d", hour, minute)
        } else {
            null
        }

        // Remove first: a fired alarm is no longer cancellable, and leaving it in
        // the registry means `alarm.list` reports an alarm that already happened.
        AlarmRegistry.remove(id)

        val posted = postNotification(context, id, label, time)
        if (!posted) showToast(context, "$label — $time".trim())
    }

    /**
     * Posts the alarm notification. Returns false when the platform refused it, so
     * the caller can fall back rather than assume.
     *
     * Every failure is swallowed deliberately: a broadcast receiver that throws
     * takes down the process, and the one thing worse than a missed notification
     * is the app being killed on the alarm thread.
     */
    private fun postNotification(
        context: Context,
        id: String,
        label: String,
        time: String?,
    ): Boolean = try {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE)
            as? NotificationManager ?: return false
        ensureChannel(context, manager)
        manager.notify(id.hashCode(), build(context, id, label, time))
        true
    } catch (e: SecurityException) {
        // POST_NOTIFICATIONS revoked on API 33+. Nothing to do but fall back.
        false
    } catch (e: IllegalArgumentException) {
        false
    }

    private fun build(
        context: Context,
        id: String,
        label: String,
        time: String?,
    ): Notification {
        val text = if (time != null) "$label — $time" else label
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(text)
            .setContentText("Alarm set by LocalIntelligence")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(launchIntent(context, id))
            .build()
    }

    /**
     * Tapping the notification opens the app.
     *
     * `getActivity` rather than `getBroadcast`: the user is foreground when they
     * tap, and there is no work to do in the background — the alarm has already
     * fired and been removed from the registry.
     *
     * Nullable on purpose. `setContentIntent` accepts null, which makes the
     * notification non-tappable, and that is strictly better than throwing on the
     * alarm thread — a `BroadcastReceiver` that throws takes the process down with
     * it, and the user would lose the alarm *and* the app.
     *
     * `requestCode` is the alarm id's hash so two alarms do not share a
     * PendingIntent; a shared one would deliver the first alarm's extras when the
     * second was tapped.
     */
    private fun launchIntent(context: Context, id: String): PendingIntent? = try {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
        if (launch != null) {
            PendingIntent.getActivity(
                context,
                id.hashCode(),
                launch.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        } else {
            // No launcher activity on some trimmed/OEM builds.
            null
        }
    } catch (e: IllegalArgumentException) {
        null
    }

    private fun ensureChannel(context: Context, manager: NotificationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                // HIGH: an alarm is the one notification a user is entitled to
                // interrupt them for. It is a single, deliberate, dated event.
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                setShowBadge(true)
                enableVibration(true)
            },
        )
    }

    private fun showToast(context: Context, text: String) {
        try {
            Toast.makeText(context, text, Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            // A Toast from a background receiver can be refused on some OEM builds.
            // Losing it must not crash the app.
        }
    }

    companion object {
        const val ACTION_ALARM_FIRED = "dev.localintelligence.android.tools.alarm.FIRED"
        const val EXTRA_ALARM_ID = "dev.localintelligence.extra.ALARM_ID"
        const val EXTRA_ALARM_LABEL = "dev.localintelligence.extra.ALARM_LABEL"
        const val EXTRA_ALARM_HOUR = "dev.localintelligence.extra.ALARM_HOUR"
        const val EXTRA_ALARM_MINUTE = "dev.localintelligence.extra.ALARM_MINUTE"

        private const val CHANNEL_ID = "alarms"
        private const val CHANNEL_NAME = "Alarms"

        /**
         * The PendingIntent target.
         *
         * Extras are attached by the caller before [PendingIntent.getBroadcast] runs;
         * note that FLAG_UPDATE_CURRENT rewrites the extras of an existing PendingIntent,
         * which is what keeps a rescheduled alarm's label current.
         */
        fun intent(context: Context): Intent = Intent(context, AlarmFireReceiver::class.java)
            .setAction(ACTION_ALARM_FIRED)

        /**
         * The full intent for a given alarm, extras included.
         *
         * The hour and minute ride along so the notification can show "07:00"
         * without having to parse the id, which is model-supplied and therefore
         * not a reliable source of the time.
         */
        fun intentFor(context: Context, spec: AlarmSpec): Intent =
            intent(context)
                .putExtra(EXTRA_ALARM_ID, spec.id)
                .putExtra(EXTRA_ALARM_LABEL, spec.label)
                .putExtra(EXTRA_ALARM_HOUR, spec.hour)
                .putExtra(EXTRA_ALARM_MINUTE, spec.minute)
    }
}

/**
 * Re-arms agent alarms after a reboot.
 *
 * ## Why this had to exist
 *
 * The manifest declared `RECEIVE_BOOT_COMPLETED` and nothing listened for it.
 * Every `AlarmManager` PendingIntent the app registered is cleared by a reboot, so
 * a user who set a 07:00 alarm and restarted their phone overnight got silence,
 * and `alarm.list` — reading a registry that a reboot also emptied — agreed with
 * the lie.
 *
 * That combination is the specific failure this project cares about most: a
 * component that looks finished, is not started by anything, and reports success.
 *
 * ## What it does
 *
 * Reads the persisted [AlarmRegistry], drops everything already past, and
 * re-schedules the rest through the same [AndroidAlarmPlatform.schedule] the
 * create tool uses — so there is one scheduling path, not two, and a re-armed
 * alarm is indistinguishable from a freshly created one.
 *
 * ## Boundaries
 *
 * `goAsync()` is bounded rather than open-ended: the work is a SharedPreferences
 * read and at most [AlarmRegistry.MAX_TRACKED] `setExactAndAllowWhileIdle` calls,
 * all in-process and all in the low tens of milliseconds. There is no model load
 * and no inference on this path — loading 2 GB from a boot receiver is how you
 * get a boot-time ANR, and it is not needed to put a PendingIntent in a system
 * service.
 */
class AlarmBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }

        val pending = goAsync()
        try {
            rearm(context.applicationContext)
        } catch (e: SecurityException) {
            // Exact-alarm permission revoked between reboot and this broadcast.
            // The registry is still correct; only the re-arming is skipped.
        } finally {
            pending.finish()
        }
    }

    /**
     * Re-arms every future alarm. Returns how many were restored.
     *
     * Split out from [onReceive] so the whole of it is one expression a reader can
     * check, and so a failure on one alarm cannot abandon the rest.
     */
    private fun rearm(context: Context): Int {
        val platform = AndroidAlarmPlatform(context)
        // FIRST, before anything reads or rewrites the registry. This is a cold
        // process after a reboot, so nothing has resolved the lazy context yet,
        // and `rearmAfterBoot` both filters and re-persists: running it against an
        // unrestored registry would see zero entries, decide every alarm is gone,
        // and persist that — turning a recoverable reboot into permanent data loss.
        AlarmRegistry.ensureAttached(context)

        val now = platform.nowMillis()
        val survivors = AlarmRegistry.rearmAfterBoot(now)
        if (survivors.isEmpty()) return 0

        // canScheduleExactAlarms() is checked once, not per alarm: it is a property
        // of the app, not of an alarm, and asking N times is N system calls for one
        // boolean. On failure every alarm is skipped identically, so the early
        // return is also the cheaper path.
        if (!platform.canScheduleExactAlarms()) return 0

        var armed = 0
        for (spec in survivors) {
            try {
                platform.schedule(spec)
                armed++
            } catch (e: SecurityException) {
                // One refused alarm must not stop the rest from being restored.
            } catch (e: IllegalStateException) {
                // AlarmManager unavailable on this device.
            }
        }
        return armed
    }
}

/**
 * Wires the three alarm tools to a real [Context].
 *
 * [AlarmRegistry.attach] is called here, once, and this is the only place it
 * needs to be. The tools themselves are constructed without a `Context` — that
 * is deliberate, and it is what keeps the shipped tool set assertable on a plain
 * JVM with no emulator (`docs/architecture.md` §2) — so the registry cannot be
 * attached from inside a tool without either breaking that property or making the
 * restore depend on a call ordering no compiler enforces.
 *
 * Doing it at construction removes the ordering question entirely: by the time
 * any tool method can run, the persisted set is already restored. That matters
 * because both `alarm.list` and `alarm.cancel` read the registry before their
 * first `platform.*` call, and a cancel that matched against a half-restored
 * registry would refuse an alarm the user can plainly see.
 *
 * The boot receiver does not come through here — it constructs a platform
 * directly — so it attaches for itself.
 */
fun alarmTools(context: Context, grant: PlatformGrant): List<AgentTool> {
    AlarmRegistry.attach(context.applicationContext)
    return listOf(
        AlarmCreateTool(AndroidAlarmPlatform(context), grant),
        AlarmListTool(AndroidAlarmPlatform(context)),
        AlarmCancelTool(AndroidAlarmPlatform(context)),
    )
}
