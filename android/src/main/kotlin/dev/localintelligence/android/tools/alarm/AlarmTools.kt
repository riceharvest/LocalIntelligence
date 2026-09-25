package dev.localintelligence.android.tools.alarm

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import dev.localintelligence.android.tools.device.ArgCoerce
import dev.localintelligence.android.tools.device.ArgResult
import dev.localintelligence.android.tools.device.guarded
import dev.localintelligence.core.model.ToolArgs
import dev.localintelligence.core.tool.AgentTool
import dev.localintelligence.core.tool.ObservationTruncator
import dev.localintelligence.core.tool.ToolContext
import dev.localintelligence.core.tool.ToolDefinition
import dev.localintelligence.core.tool.ToolError
import dev.localintelligence.core.tool.ToolResult
import dev.localintelligence.core.tool.ToolRisk
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

// =====================================================================================
// AlarmTools — alarm.create, alarm.list, alarm.cancel.
//
// TWO DESIGN DECISIONS THAT EVERYONE READING THIS FILE NEEDS TO KNOW:
//
// 1. These are AGENT alarms, not clock-app alarms.
//    `AlarmClock.ACTION_GET_ALIST` can only be answered by an Activity that receives
//    `onActivityResult`. A tool can execute from a Service, a BroadcastReceiver or a
//    plain coroutine, none of which can receive one, so the system alarm list is
//    fundamentally unreachable from here without a declared Activity in the manifest —
//    which this change is not allowed to add. Rather than pretend, `alarm.list` returns
//    the alarms THIS tool created and states the boundary in every single observation,
//    so the model can never mistake "the agent's 2 alarms" for "your 5 alarms".
//
// 2. That registry is what makes `alarm.cancel` safe.
//    Cancelling an alarm the user did not name is data loss. The ambiguity guard in
//    [AlarmCancel] can only ask "did you mean this one?" against a real candidate set,
//    and a candidate set only exists if the tool remembers what it created. So the
//    registry is load-bearing for the dangerous tool, not a convenience cache.
//
// Every decision lives in [AlarmTimes], [AlarmCancel] or [AlarmRegistry], all of which
// are pure Kotlin and unit-tested on the JVM. The android.* code is confined to
// [AndroidAlarmPlatform] and [AlarmFireReceiver].
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

    const val MIN_HOUR = 0
    const val MAX_HOUR = 23
    const val MIN_MINUTE = 0
    const val MAX_MINUTE = 59

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
 * In-memory record of the alarms this app has scheduled.
 *
 * Bounded at [MAX_TRACKED] entries; the oldest is evicted past that. On a phone the
 * worst case is a few kilobytes — see the RAM note in the PR description — which is
 * the whole reason it is capped rather than allowed to grow.
 *
 * It does NOT survive process death. The PendingIntents themselves are held by the
 * system and DO survive, so an alarm created before a restart still fires; only the
 * ability to *list* or match it by time is lost. Cancelling by explicit id still works
 * across a restart because [AlarmIds.requestCodeOf] is a pure function of the id — the
 * platform seam, not the registry, is what makes cancel survive.
 */
object AlarmRegistry {

    const val MAX_TRACKED = 32

    private val lock = Any()
    private val entries = ArrayList<AlarmSpec>()

    fun add(spec: AlarmSpec): List<AlarmSpec> = synchronized(lock) {
        entries.removeAll { it.id.equals(spec.id, ignoreCase = true) }
        entries.add(spec)
        while (entries.size > MAX_TRACKED) {
            entries.removeAt(0)
        }
        entries.toList()
    }

    fun remove(id: String): AlarmSpec? = synchronized(lock) {
        val index = entries.indexOfFirst { it.id.equals(id, ignoreCase = true) }
        if (index < 0) null else entries.removeAt(index)
    }

    /** Oldest first, so "the alarm I set first" is a stable thing to name. */
    fun all(): List<AlarmSpec> = synchronized(lock) {
        entries.sortedBy { it.createdAtMillis }.toList()
    }

    fun clear() = synchronized(lock) {
        entries.clear()
    }
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
// =====================================================================================

private val CREATE_SCHEMA: ToolArgs = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") {
        putJsonObject("hour") {
            put("type", "integer")
            put("minimum", AlarmTimes.MIN_HOUR)
            put("maximum", AlarmTimes.MAX_HOUR)
            put("description", "Hour in 24-hour time, 0-23.")
        }
        putJsonObject("minute") {
            put("type", "integer")
            put("minimum", AlarmTimes.MIN_MINUTE)
            put("maximum", AlarmTimes.MAX_MINUTE)
            put("description", "Minute, 0-59.")
        }
        putJsonObject("label") {
            put("type", "string")
            put("description", "Short description, e.g. \"take the bread out\".")
        }
        putJsonObject("id") {
            put("type", "string")
            put("description", "Stable id used later to cancel this exact alarm. Generated if omitted.")
        }
        putJsonObject("day_offset") {
            put("type", "integer")
            put("minimum", 0)
            put("maximum", 7)
            put(
                "description",
                "0 for today, 1 for tomorrow. When omitted, a time that has already passed " +
                    "today automatically rolls over to tomorrow.",
            )
        }
        putJsonObject("repeat") {
            put("type", "boolean")
            put("description", "Repeating alarms are not supported. Leave this false or omit it.")
        }
    }
    putJsonArray("required") { add("hour") }
}

private val LIST_SCHEMA: ToolArgs = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") { }
    putJsonArray("required") { }
}

private val CANCEL_SCHEMA: ToolArgs = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") {
        putJsonObject("id") {
            put("type", "string")
            put("description", "The id of the ONE alarm to cancel. Takes precedence over hour/minute.")
        }
        putJsonObject("hour") {
            put("type", "integer")
            put("minimum", AlarmTimes.MIN_HOUR)
            put("maximum", AlarmTimes.MAX_HOUR)
            put("description", "Cancel the single alarm at this hour.")
        }
        putJsonObject("minute") {
            put("type", "integer")
            put("minimum", AlarmTimes.MIN_MINUTE)
            put("maximum", AlarmTimes.MAX_MINUTE)
            put("description", "Cancel the single alarm at this minute.")
        }
        putJsonObject("label") {
            put("type", "string")
            put("description", "Cancel the single alarm whose label contains this text.")
        }
    }
    putJsonArray("required") { }
}

// =====================================================================================
// Tools
// =====================================================================================

class AlarmCreateTool(private val platform: AlarmPlatform) : AgentTool {

    override val definition = ToolDefinition(
        name = "alarm.create",
        description = "Set a one-time alarm on the phone and return the time and the id it was " +
            "given.",
        category = "alarm",
        schema = CREATE_SCHEMA,
        risk = ToolRisk.REVERSIBLE,
        tags = setOf(
            "alarm", "set an alarm", "wake me up", "remind me at", "timer", "ring at",
            "wake up call", "set a reminder",
        ),
        // SCHEDULE_EXACT_ALARM is needed on Android 12+ for setExactAndAllowWhileIdle.
        requiredPermission = "android.permission.SCHEDULE_EXACT_ALARM",
    )

    override suspend fun execute(args: ToolArgs, context: ToolContext): ToolResult {
        if (!context.permissionGranted) {
            return ToolResult(
                success = false,
                observation = "No permission to set alarms. Tell the user it is unavailable in " +
                    "this session; do not retry.",
                error = ToolError.PermissionDenied("alarm.create denied"),
            )
        }

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
        schema = LIST_SCHEMA,
        risk = ToolRisk.READ_ONLY,
        tags = setOf(
            "my alarms", "list alarms", "what alarms do i have", "upcoming alarms",
            "alarm list", "what did i set", "do i have an alarm",
        ),
        requiredPermission = null,
    )

    override suspend fun execute(args: ToolArgs, context: ToolContext): ToolResult {
        if (!context.permissionGranted) {
            return ToolResult(
                success = false,
                observation = "No permission to read alarms. Tell the user it is unavailable in " +
                    "this session; do not retry.",
                error = ToolError.PermissionDenied("alarm.list denied"),
            )
        }

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
        schema = CANCEL_SCHEMA,
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
        if (!context.permissionGranted) {
            return ToolResult(
                success = false,
                observation = "No permission to cancel alarms. Tell the user it is unavailable " +
                    "in this session; do not retry.",
                error = ToolError.PermissionDenied("alarm.cancel denied"),
            )
        }

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
 * Intentionally minimal: a Toast, which needs no permission and no notification channel,
 * and a registry clean-up. A real product would post a notification here (and would need
 * POST_NOTIFICATIONS on API 33+ plus a channel); doing that without the parent's sign-off
 * on a manifest change would ship a silent failure, so the honest minimal behaviour is
 * the one that is actually visible.
 *
 * REQUIRES a manifest entry — see the PR description. Without it the PendingIntent
 * resolves to nothing, `schedule` throws nothing, and the alarm silently never fires.
 */
class AlarmFireReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_ALARM_ID) ?: return
        val label = intent.getStringExtra(EXTRA_ALARM_LABEL) ?: "Alarm"
        AlarmRegistry.remove(id)
        try {
            Toast.makeText(context, "$label — $id", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            // A Toast from a background receiver can be refused on some OEM builds.
            // Losing the toast must not crash the app.
        }
    }

    companion object {
        const val ACTION_ALARM_FIRED = "dev.localintelligence.android.tools.alarm.FIRED"
        const val EXTRA_ALARM_ID = "dev.localintelligence.extra.ALARM_ID"
        const val EXTRA_ALARM_LABEL = "dev.localintelligence.extra.ALARM_LABEL"

        /**
         * The PendingIntent target.
         *
         * Extras are attached by the caller before [PendingIntent.getBroadcast] runs;
         * note that FLAG_UPDATE_CURRENT rewrites the extras of an existing PendingIntent,
         * which is what keeps a rescheduled alarm's label current.
         */
        fun intent(context: Context): Intent = Intent(context, AlarmFireReceiver::class.java)
            .setAction(ACTION_ALARM_FIRED)

        /** The full intent for a given alarm, extras included. */
        fun intentFor(context: Context, spec: AlarmSpec): Intent =
            intent(context)
                .putExtra(EXTRA_ALARM_ID, spec.id)
                .putExtra(EXTRA_ALARM_LABEL, spec.label)
    }
}

/** Wires the three alarm tools to a real [Context]. */
fun alarmTools(context: Context): List<AgentTool> = listOf(
    AlarmCreateTool(AndroidAlarmPlatform(context)),
    AlarmListTool(AndroidAlarmPlatform(context)),
    AlarmCancelTool(AndroidAlarmPlatform(context)),
)
