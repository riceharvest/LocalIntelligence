package dev.localintelligence.android.tools.calendar

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.provider.CalendarContract
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
import dev.localintelligence.core.tool.contracts.ToolPermissions
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Pure, android-free calendar logic: argument coercion, instant parsing, window and
 * duration validation, and observation rendering.
 *
 * Everything the model can get wrong is decided HERE, in a plain object with no
 * `android.*` import, so it is unit-testable on the JVM with no device. The
 * `AgentTool` classes below are then a thin, unremarkable shell around a
 * ContentResolver: fetch rows, hand them to this object, close the cursor.
 */

// ---------------------------------------------------------------------------- data --

/** One calendar instance row, already read out of the cursor. */
internal data class CalendarEvent(
    val id: Long,
    val title: String,
    val startMillis: Long,
    val endMillis: Long,
    val allDay: Boolean,
    val location: String?,
)

internal sealed interface ParsedInstant {
    data class At(val epochMillis: Long) : ParsedInstant
    data class Invalid(val message: String) : ParsedInstant
}

internal sealed interface ResolvedWindow {
    data class Ok(
        val fromMillis: Long,
        val toMillis: Long,
        /** True when the model passed from > to and we swapped them. */
        val swapped: Boolean,
    ) : ResolvedWindow

    data class Invalid(val message: String) : ResolvedWindow
}

internal sealed interface ResolvedDuration {
    data class Ok(val minutes: Int, /** True when neither duration nor end was usable. */
        val assumed: Boolean,
    ) : ResolvedDuration

    data class Invalid(val message: String) : ResolvedDuration
}

// ---------------------------------------------------------------------------- args --

internal object CalendarArgs {

    // Aliased from :core's ToolArgumentBounds: these numbers appear in this
    // tool's JSON Schema, which :core owns, and in its `execute()`, below.
    // Aliasing rather than repeating is what stops the advertised bound and
    // the enforced bound from drifting apart.
    const val DEFAULT_LIMIT = ToolArgumentBounds.CALENDAR_DEFAULT_LIMIT
    const val MAX_LIMIT = ToolArgumentBounds.CALENDAR_MAX_LIMIT

    /** Hard ceiling on a search window. Beyond this the provider scan is a battery event. */
    const val MAX_WINDOW_DAYS = 90L

    const val MIN_DURATION_MINUTES = ToolArgumentBounds.CALENDAR_MIN_DURATION_MINUTES
    const val MAX_DURATION_MINUTES = ToolArgumentBounds.CALENDAR_MAX_DURATION_MINUTES
    const val DEFAULT_DURATION_MINUTES = 60

    const val MAX_QUERY_CHARS = 64
    const val MAX_TITLE_CHARS = 200
    const val MAX_LOCATION_CHARS = 200
    const val MAX_DESCRIPTION_CHARS = 1000

    private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM", Locale.US)
    private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.US)
    private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.US)

    internal fun dayFormatter(): DateTimeFormatter = DAY

    internal fun stampFormatter(): DateTimeFormatter = STAMP

    internal fun clockFormatter(): DateTimeFormatter = CLOCK

    private val OFFSET_FORMATS: List<DateTimeFormatter> = listOf(
        DateTimeFormatter.ISO_OFFSET_DATE_TIME,
        DateTimeFormatter.ISO_INSTANT,
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US),
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mmXXX", Locale.US),
        // XXX is the colon form (+02:00); Z is the compact form (+0200). Both occur, and
        // a space before the offset is common enough that it needs its own patterns.
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss XXX", Locale.US),
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm XXX", Locale.US),
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss XXX", Locale.US),
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ssXXX", Locale.US),
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mmXXX", Locale.US),
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss Z", Locale.US),
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm Z", Locale.US),
        DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", Locale.US),
    )

    private val LOCAL_FORMATS: List<DateTimeFormatter> = listOf(
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US),
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss", Locale.US),
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm", Locale.US),
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS", Locale.US),
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US),
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.US),
        DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss", Locale.US),
        DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm", Locale.US),
        DateTimeFormatter.ofPattern("yyyy-MM-dd h:mm:ss a", Locale.US),
        DateTimeFormatter.ofPattern("yyyy-MM-dd h:mm a", Locale.US),
    )

    private val DATE_FORMATS: List<DateTimeFormatter> = listOf(
        DateTimeFormatter.ISO_LOCAL_DATE,
        DateTimeFormatter.ofPattern("yyyy/MM/dd", Locale.US),
        // US convention first: 09/25/2026 can only be month-first, and small models
        // emit slashes far more often than dashes.
        DateTimeFormatter.ofPattern("MM/dd/yyyy", Locale.US),
        DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.US),
        DateTimeFormatter.ofPattern("dd-MM-yyyy", Locale.US),
    )

    /** Longest timestamp we accept, as a sanity bound against a runaway epoch. */
    private const val MAX_PLAUSIBLE_MILLIS = 4_102_444_800_000L // 2100-01-01T00:00:00Z

    /**
     * Parses whatever the model called a date. Accepts epoch millis/seconds, offset and
     * naive ISO date-times in several shapes, plain dates, and the words today /
     * tomorrow / yesterday / now. A zone-less value is interpreted in [zone]; a plain
     * date means the start of that day.
     */
    fun parseInstant(
        raw: String?,
        zone: ZoneId,
        nowMillis: Long,
    ): ParsedInstant {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) {
            return ParsedInstant.Invalid("empty; expected an ISO date or date-time such as 2026-09-25T09:00:00")
        }

        keyword(text, zone, nowMillis)?.let { return ParsedInstant.At(it) }

        val normalised = normalise(text)

        if (normalised.all { it.isDigit() || it == '-' || it == '+' } && normalised.any { it.isDigit() }) {
            // Only treat it as an epoch if it really is one. "2026-09-25" is digits and
            // dashes too, and must fall through to the date formats below rather than be
            // rejected as an unparseable number.
            val numeric = normalised.trimStart('+').toLongOrNull()
            if (numeric != null) {
                // 10 digits is seconds, 13 is millis. Anything else is a mistake, not a date.
                val millis = when {
                    numeric in -99_999_999_999L..99_999_999_999L -> numeric * 1000L
                    else -> numeric
                }
                return if (millis in 0L..MAX_PLAUSIBLE_MILLIS) {
                    ParsedInstant.At(millis)
                } else {
                    ParsedInstant.Invalid("'$text' is outside the year 1970-2100 range a calendar can hold")
                }
            }
        }

        for (format in OFFSET_FORMATS) {
            val parsed = try {
                ZonedDateTime.parse(normalised, format)
            } catch (_: DateTimeParseException) {
                null
            } ?: continue
            return ParsedInstant.At(parsed.toInstant().toEpochMilli())
        }

        for (format in LOCAL_FORMATS) {
            val parsed = try {
                LocalDateTime.parse(normalised, format)
            } catch (_: DateTimeParseException) {
                null
            } ?: continue
            return ParsedInstant.At(parsed.atZone(zone).toInstant().toEpochMilli())
        }

        for (format in DATE_FORMATS) {
            val parsed = try {
                LocalDate.parse(normalised, format)
            } catch (_: DateTimeParseException) {
                null
            } ?: continue
            return ParsedInstant.At(parsed.atStartOfDay(zone).toInstant().toEpochMilli())
        }

        return ParsedInstant.Invalid(
            "cannot read '$text' as a date; use 2026-09-25, 2026-09-25T09:00:00, " +
                "2026-09-25T09:00:00+02:00, or the word today/tomorrow",
        )
    }

    /** Reads a required instant argument out of the raw model arguments. */
    fun requiredInstant(
        args: ToolArgs,
        key: String,
        zone: ZoneId,
        nowMillis: Long,
    ): ParsedInstant {
        val element = primitive(args, key)
        if (element == null) {
            return ParsedInstant.Invalid(
                "'$key' is required; pass an ISO date or date-time such as 2026-09-25T09:00:00",
            )
        }
        val parsed = parseInstant(element.content, zone, nowMillis)
        return if (parsed is ParsedInstant.Invalid) {
            ParsedInstant.Invalid("'$key' is unusable: ${parsed.message}")
        } else {
            parsed
        }
    }

    /**
     * Validates a search window. An inverted window is swapped rather than rejected —
     * the model clearly meant the interval — and an over-wide one is rejected, because a
     * ten-year Instances scan on a phone is a battery event with no useful answer.
     */
    fun resolveWindow(fromMillis: Long, toMillis: Long): ResolvedWindow {
        if (fromMillis == toMillis) {
            return ResolvedWindow.Invalid(
                "the window is empty: 'from' and 'to' are the same instant; widen one of them",
            )
        }
        val (from, to, swapped) = if (fromMillis > toMillis) {
            Triple(toMillis, fromMillis, true)
        } else {
            Triple(fromMillis, toMillis, false)
        }
        val days = (to - from) / MILLIS_PER_DAY
        if ((to - from) > MAX_WINDOW_DAYS * MILLIS_PER_DAY) {
            return ResolvedWindow.Invalid(
                "the window spans $days days; the maximum is $MAX_WINDOW_DAYS. " +
                    "Split the range into windows of at most $MAX_WINDOW_DAYS days",
            )
        }
        return ResolvedWindow.Ok(from, to, swapped)
    }

    /** Duration for a create call: explicit minutes, else end-start, else a sane default. */
    fun resolveDuration(durationMinutes: Int?, endMillis: Long?, startMillis: Long): ResolvedDuration {
        if (durationMinutes != null) {
            if (durationMinutes < MIN_DURATION_MINUTES) {
                return ResolvedDuration.Invalid(
                    "durationMinutes must be at least $MIN_DURATION_MINUTES, got $durationMinutes",
                )
            }
            if (durationMinutes > MAX_DURATION_MINUTES) {
                return ResolvedDuration.Invalid(
                    "durationMinutes must be $MAX_DURATION_MINUTES (24h) or less, got $durationMinutes; " +
                        "create one event per day for anything longer",
                )
            }
            return ResolvedDuration.Ok(durationMinutes, assumed = false)
        }
        if (endMillis != null) {
            val minutes = (endMillis - startMillis) / 60_000L
            if (minutes < MIN_DURATION_MINUTES) {
                return ResolvedDuration.Invalid("'end' is not after 'start'")
            }
            if (minutes > MAX_DURATION_MINUTES) {
                return ResolvedDuration.Invalid(
                    "'end' is $minutes minutes after 'start'; the maximum is $MAX_DURATION_MINUTES (24h)",
                )
            }
            return ResolvedDuration.Ok(minutes.toInt(), assumed = false)
        }
        return ResolvedDuration.Ok(DEFAULT_DURATION_MINUTES, assumed = true)
    }

    /** Models send "8", 8.7, -4 and "banana" for an integer. All of them are survivable. */
    fun coerceLimit(raw: String?): Int {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty() || text.equals("null", ignoreCase = true)) return DEFAULT_LIMIT
        val value = text.toIntOrNull()
            ?: text.toDoubleOrNull()?.takeIf { it.isFinite() }?.toInt()
            ?: return DEFAULT_LIMIT
        return when {
            value < 1 -> DEFAULT_LIMIT
            value > MAX_LIMIT -> MAX_LIMIT
            else -> value
        }
    }

    /**
     * Escapes LIKE metacharacters. The query is always bound as an argument, so this is
     * about correctness rather than injection: without it, a search for "50%" matches
     * every event in the window.
     */
    fun escapeLike(raw: String): String =
        raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    /** Trims, caps and blank-checks a free-text argument. */
    fun clean(raw: String?, max: Int): String? {
        val text = raw?.trim().orEmpty().replace(Regex("\\s+"), " ")
        if (text.isEmpty()) return null
        return if (text.length <= max) text else text.take(max - 1).trimEnd() + "…"
    }

    /** Coerces the several shapes a model uses for a boolean. */
    fun coerceBoolean(raw: String?): Boolean {
        val text = raw?.trim()?.lowercase(Locale.US) ?: return false
        return text == "true" || text == "1" || text == "yes" || text == "y"
    }

    private fun keyword(text: String, zone: ZoneId, nowMillis: Long): Long? {
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        val date = when (text.lowercase(Locale.US)) {
            "now" -> return nowMillis
            "today" -> today
            "tomorrow" -> today.plusDays(1)
            "yesterday" -> today.minusDays(1)
            else -> return null
        }
        return date.atStartOfDay(zone).toInstant().toEpochMilli()
    }

    /**
     * "2026-09-25t09:00" is not ISO to java.time, and "9:00 am" is not what
     * DateTimeFormatter expects, but both are what small models emit.
     */
    private fun normalise(text: String): String {
        var out = text
        if (out.length > 10 && out[10] == 't') out = out.substring(0, 10) + "T" + out.substring(11)
        if (out.length > 10 && out[10] == 'z') out = out.substring(0, 10) + "T" + out.substring(11)
        // The AM/PM pattern letter only matches upper case with a US locale.
        out = AM_PM.replace(out) { match -> match.value.uppercase(Locale.US) }
        return out
    }

    private val AM_PM = Regex("""\b(am|pm|a\.m\.|p\.m\.)\b""", RegexOption.IGNORE_CASE)

    private const val MILLIS_PER_DAY = 24L * 60L * 60L * 1000L
}

// ------------------------------------------------------------------------- rendering --

internal object CalendarText {

    private val DAY: DateTimeFormatter = CalendarArgs.dayFormatter()
    private val STAMP: DateTimeFormatter = CalendarArgs.stampFormatter()
    private val CLOCK: DateTimeFormatter = CalendarArgs.clockFormatter()

    fun day(millis: Long, zone: ZoneId): String = DAY.format(Instant.ofEpochMilli(millis).atZone(zone))

    fun stamp(millis: Long, zone: ZoneId): String = STAMP.format(Instant.ofEpochMilli(millis).atZone(zone))

    private fun clock(millis: Long, zone: ZoneId): String = CLOCK.format(Instant.ofEpochMilli(millis).atZone(zone))

    /** One line per event. Dense on purpose: the model pays for every character. */
    fun eventLine(event: CalendarEvent, zone: ZoneId): String {
        val title = CalendarArgs.clean(event.title, 70) ?: "(no title)"
        val start = Instant.ofEpochMilli(event.startMillis).atZone(zone)
        val end = Instant.ofEpochMilli(event.endMillis).atZone(zone)
        val whenText = when {
            event.allDay -> "${DAY.format(start)} all-day"
            start.toLocalDate() == end.toLocalDate() -> "${DAY.format(start)} ${clock(event.startMillis, zone)}-${clock(event.endMillis, zone)}"
            else -> "${DAY.format(start)} ${clock(event.startMillis, zone)} - ${DAY.format(end)} ${clock(event.endMillis, zone)}"
        }
        val location = CalendarArgs.clean(event.location, 48)?.let { " @ $it" }.orEmpty()
        return "$whenText  $title$location"
    }

    fun search(
        events: List<CalendarEvent>,
        hasMore: Boolean,
        fromMillis: Long,
        toMillis: Long,
        swapped: Boolean,
        query: String?,
        zone: ZoneId,
    ): String {
        if (events.isEmpty()) {
            val filter = query?.let { " matching \"$it\"" }.orEmpty()
            return "No events$filter between ${stamp(fromMillis, zone)} and ${stamp(toMillis, zone)}."
        }
        val header = buildString {
            append(if (hasMore) "Showing ${events.size} events, more exist." else "${events.size} events")
            append(" between ${stamp(fromMillis, zone)} and ${stamp(toMillis, zone)}")
            if (swapped) append(" (from/to were reversed, searched in chronological order)")
            query?.let { append(" matching \"$it\"") }
            append(':')
        }
        val body = events.mapIndexed { index, event -> "${index + 1}. ${eventLine(event, zone)}" }
        return ObservationTruncator.truncate((listOf(header) + body).joinToString("\n"))
    }

    fun created(event: CalendarEvent, zone: ZoneId, durationAssumed: Boolean): String {
        val title = CalendarArgs.clean(event.title, 70) ?: "(no title)"
        val whenText = eventLine(event, zone).substringBefore("  ")
        val location = CalendarArgs.clean(event.location, 48)?.let { " @ $it" }.orEmpty()
        val assumed = if (durationAssumed) " Duration was not given; used ${CalendarArgs.DEFAULT_DURATION_MINUTES} min." else ""
        return "Created \"$title\" $whenText$location (calendar id ${event.id}).$assumed"
    }
}

// -------------------------------------------------------------------------- helpers --

/** The JsonPrimitive behind [key], treating an explicit JSON null as absent. */
internal fun primitive(args: ToolArgs, key: String): JsonPrimitive? {
    val element: JsonElement? = args[key] ?: return null
    val value = element as? JsonPrimitive ?: return null
    return if (value.content.equals("null", ignoreCase = true)) null else value
}

/** Trims an optional string argument, or null when it is absent/blank. */
internal fun optionalString(args: ToolArgs, key: String, max: Int): String? =
    CalendarArgs.clean(primitive(args, key)?.content, max)

// ----------------------------------------------------------------------- the seam --

/**
 * The one thing these tools need from the Android platform.
 *
 * It exists so the REAL `execute()` path — argument parsing, window validation, row
 * mapping, capping, observation rendering, and the close-in-finally contract — can be
 * executed on a plain JVM with a fake provider. Testing only the pure helpers would
 * leave the code that actually touches a cursor unverified, and a leaked cursor on a
 * phone is a real memory leak (docs/architecture.md §16).
 *
 * Production wiring passes [ResolverCalendarProvider]. The interface is internal:
 * :android constructs the tools, nobody else needs to know.
 */
internal interface CalendarProvider {
    /**
     * Returns a cursor the caller MUST close, or null when no calendar provider exists.
     * [pattern] is an already-escaped SQL LIKE pattern (e.g. `%50\%%`) or null for no
     * text filter.
     */
    fun query(
        fromMillis: Long,
        toMillis: Long,
        pattern: String?,
    ): Cursor?

    /** Returns the new event's id, or null when the insert was refused. */
    fun insert(
        title: String,
        startMillis: Long,
        endMillis: Long,
        allDay: Boolean,
        location: String?,
        description: String?,
        zone: ZoneId,
    ): Long?
}

/** The production implementation. The only class in this file that touches ContentResolver. */
internal class ResolverCalendarProvider(private val resolver: ContentResolver) : CalendarProvider {

    override fun query(
        fromMillis: Long,
        toMillis: Long,
        pattern: String?,
    ): Cursor? {
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .apply {
                ContentUris.appendId(this, fromMillis)
                ContentUris.appendId(this, toMillis)
            }
            .build()
        val selection = pattern?.let {
            "(${CalendarContract.Instances.TITLE} LIKE ? ESCAPE '\\' " +
                "OR ${CalendarContract.Instances.EVENT_LOCATION} LIKE ? ESCAPE '\\')"
        }
        val selectionArgs = pattern?.let { arrayOf(it, it) }
        return resolver.query(
            uri,
            PROJECTION,
            selection,
            selectionArgs,
            "${CalendarContract.Instances.BEGIN} ASC",
        )
    }

    override fun insert(
        title: String,
        startMillis: Long,
        endMillis: Long,
        allDay: Boolean,
        location: String?,
        description: String?,
        zone: ZoneId,
    ): Long? {
        val values = ContentValues().apply {
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DTSTART, startMillis)
            put(CalendarContract.Events.DTEND, endMillis)
            put(CalendarContract.Events.ALL_DAY, if (allDay) 1 else 0)
            put(CalendarContract.Events.EVENT_TIMEZONE, zone.id)
            put(CalendarContract.Events.HAS_ALARM, 0)
            put(CalendarContract.Events.AVAILABILITY, CalendarContract.Events.AVAILABILITY_BUSY)
            location?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
            description?.let { put(CalendarContract.Events.DESCRIPTION, it) }
        }
        val uri = resolver.insert(CalendarContract.Events.CONTENT_URI, values)
            ?: return null
        return uri.lastPathSegment?.toLongOrNull() ?: -1L
    }

    private companion object {
        val PROJECTION = arrayOf(
            CalendarContract.Instances._ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.EVENT_LOCATION,
        )
    }
}

// ----------------------------------------------------------------------- the tools --

/**
 * Reads calendar events in a time window.
 *
 * READ_ONLY, so the runtime executes it without asking. The observation is the only
 * thing the model sees, so it is a short numbered list, not a cursor dump.
 */
class CalendarSearchTool internal constructor(
    private val provider: CalendarProvider,
    private val zone: ZoneId,
    private val grant: PlatformGrant,
) : AgentTool {

    /** Production wiring: `CalendarSearchTool(context.contentResolver)`. */
    @JvmOverloads
    constructor(resolver: ContentResolver, zone: ZoneId = ZoneId.systemDefault(), grant: PlatformGrant) :
        this(ResolverCalendarProvider(resolver), zone, grant)

    override val definition: ToolDefinition = ToolDefinition(
        name = "calendar.search",
        description = "Return the calendar events in a time window, optionally filtered by title or location text.",
        category = "calendar",
        schema = ToolSchemas.calendarSearch,
        risk = ToolRisk.READ_ONLY,
        observationOrigin = ObservationOrigin.LOCAL,
        tags = setOf(
            "calendar", "events", "agenda", "schedule", "appointment", "meeting", "busy", "what's on",
        ),
        requiredPermission = "android.permission.READ_CALENDAR",
    )

    override suspend fun execute(args: ToolArgs, context: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            // Asked of the platform, not of `context.permissionGranted` — that
            // flag comes from a set nothing populates and is false on every
            // call. Trusting it fabricates a denial; ignoring it returns
            // "No events ... between ..." for a calendar the app was not
            // allowed to open, and the model tells the user they are free.
            if (!grant.isGranted(ToolPermissions.CALENDAR_READ)) return@withContext denied()
            if (context.signal.isCancelled()) return@withContext cancelled()

            val now = System.currentTimeMillis()
            val from = CalendarArgs.requiredInstant(args, "from", zone, now)
            if (from is ParsedInstant.Invalid) return@withContext invalid(from.message)
            val to = CalendarArgs.requiredInstant(args, "to", zone, now)
            if (to is ParsedInstant.Invalid) return@withContext invalid(to.message)

            val window = CalendarArgs.resolveWindow(
                (from as ParsedInstant.At).epochMillis,
                (to as ParsedInstant.At).epochMillis,
            )
            if (window is ResolvedWindow.Invalid) return@withContext invalid(window.message)
            val resolved = window as ResolvedWindow.Ok

            val limit = CalendarArgs.coerceLimit(primitive(args, "limit")?.content)
            val query = optionalString(args, "query", CalendarArgs.MAX_QUERY_CHARS)
            // Escaped here, in the pure, testable layer; the provider only wraps it in
            // the provider-specific selection and binds it.
            val pattern = query?.let { "%${CalendarArgs.escapeLike(it)}%" }

            val events: MutableList<CalendarEvent> = ArrayList(limit + 1)
            var hasMore = false
            var wasCancelled = false

            try {
                val cursor: Cursor? = provider.query(resolved.fromMillis, resolved.toMillis, pattern)
                if (cursor == null) {
                    return@withContext ToolResult(
                        success = false,
                        observation = "No calendar provider is available on this device, so no events " +
                            "could be read. Tell the user calendar access is unavailable.",
                        error = ToolError.Unavailable("no calendar provider"),
                    )
                }
                try {
                    // limit + 1 rows: the extra row is the honest proof that more exist.
                    while (cursor.moveToNext()) {
                        if (context.signal.isCancelled()) {
                            wasCancelled = true
                            break
                        }
                        if (events.size >= limit) {
                            hasMore = true
                            break
                        }
                        events += cursor.toEvent()
                    }
                } finally {
                    cursor.close()
                }
            } catch (security: SecurityException) {
                return@withContext denied()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                return@withContext ToolResult(
                    success = false,
                    observation = "Reading the calendar failed (${t.javaClass.simpleName}). " +
                        "Do not retry; report that the calendar could not be read.",
                    error = ToolError.Internal("calendar.query failed: ${t.javaClass.simpleName}"),
                )
            }

            if (wasCancelled) return@withContext cancelled()

            val observation = CalendarText.search(
                events = events,
                hasMore = hasMore,
                fromMillis = resolved.fromMillis,
                toMillis = resolved.toMillis,
                swapped = resolved.swapped,
                query = query,
                zone = zone,
            )
            ToolResult(
                success = true,
                observation = observation,
                data = buildJsonObject {
                    put("count", events.size)
                    put("truncated", hasMore)
                    put("from", resolved.fromMillis)
                    put("to", resolved.toMillis)
                    put("swapped", resolved.swapped)
                },
            )
        }

    private fun denied(): ToolResult = ToolResult(
        success = false,
        observation = PermissionDenial.observation("calendar.search", ToolPermissions.CALENDAR_READ),
        error = ToolError.PermissionDenied(PermissionDenial.summary(ToolPermissions.CALENDAR_READ)),
    )

    private fun invalid(message: String): ToolResult = ToolResult(
        success = false,
        observation = "calendar.search was called with unusable dates: $message",
        error = ToolError.InvalidArguments(message),
    )

    private fun cancelled(): ToolResult = ToolResult(
        success = false,
        observation = "The calendar search was cancelled before it finished.",
        error = ToolError.Cancelled("cancelled during calendar.search"),
    )

    /** Reads one row, tolerating a provider that does not expose every column. */
    private fun Cursor.toEvent(): CalendarEvent = CalendarEvent(
        id = longOrZero(CalendarContract.Instances._ID),
        title = string(CalendarContract.Instances.TITLE) ?: "",
        startMillis = longOrZero(CalendarContract.Instances.BEGIN),
        endMillis = longOrZero(CalendarContract.Instances.END),
        allDay = intOrZero(CalendarContract.Instances.ALL_DAY) == 1,
        location = string(CalendarContract.Instances.EVENT_LOCATION),
    )

    private fun Cursor.indexOf(column: String): Int = getColumnIndex(column)

    private fun Cursor.string(column: String): String? {
        val index = indexOf(column)
        return if (index < 0 || isNull(index)) null else getString(index)?.trim()
    }

    private fun Cursor.longOrZero(column: String): Long {
        val index = indexOf(column)
        return if (index < 0 || isNull(index)) 0L else getLong(index)
    }

    private fun Cursor.intOrZero(column: String): Int {
        val index = indexOf(column)
        return if (index < 0 || isNull(index)) 0 else getInt(index)
    }
}

/**
 * Inserts a calendar event.
 *
 * REVERSIBLE: the user can undo it from their calendar app, so the runtime does not
 * block on a confirmation dialog, but the insert is a real mutation and needs
 * WRITE_CALENDAR.
 */
class CalendarCreateTool internal constructor(
    private val provider: CalendarProvider,
    private val zone: ZoneId,
    private val grant: PlatformGrant,
) : AgentTool {

    /** Production wiring: `CalendarCreateTool(context.contentResolver)`. */
    @JvmOverloads
    constructor(resolver: ContentResolver, zone: ZoneId = ZoneId.systemDefault(), grant: PlatformGrant) :
        this(ResolverCalendarProvider(resolver), zone, grant)

    override val definition: ToolDefinition = ToolDefinition(
        name = "calendar.create",
        description = "Create a calendar event at a given start time and return the new event's id and start time.",
        category = "calendar",
        schema = ToolSchemas.calendarCreate,
        risk = ToolRisk.REVERSIBLE,
        tags = setOf("calendar", "add event", "schedule", "reminder", "book", "appointment", "meeting"),
        requiredPermission = "android.permission.WRITE_CALENDAR",
    )

    override suspend fun execute(args: ToolArgs, context: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            // Platform truth, not the unmaintained context flag — see the note
            // on calendar.search. Here it matters twice over, because the
            // create path's failure mode is a provider insert that returns
            // null and is reported as "nothing was created" for a user who
            // simply lacks WRITE_CALENDAR.
            if (!grant.isGranted(ToolPermissions.CALENDAR_WRITE)) return@withContext denied()
            if (context.signal.isCancelled()) return@withContext cancelled()

            val title = optionalString(args, "title", CalendarArgs.MAX_TITLE_CHARS)
            if (title.isNullOrBlank()) {
                return@withContext invalid("'title' is required and must be 1-${CalendarArgs.MAX_TITLE_CHARS} characters")
            }

            val now = System.currentTimeMillis()
            val start = CalendarArgs.requiredInstant(args, "start", zone, now)
            if (start is ParsedInstant.Invalid) return@withContext invalid(start.message)
            val startMillis = (start as ParsedInstant.At).epochMillis

            val allDay = CalendarArgs.coerceBoolean(primitive(args, "allDay")?.content)

            val durationText = primitive(args, "durationMinutes")?.content
            val durationMinutes: Int? = if (durationText == null) {
                null
            } else {
                durationText.trim().toIntOrNull()
                    ?: durationText.trim().toDoubleOrNull()?.takeIf { it.isFinite() }?.toInt()
                    ?: return@withContext invalid("'durationMinutes' must be a whole number of minutes, got \"$durationText\"")
            }

            val endText = primitive(args, "end")?.content
            val endMillis: Long? = if (endText == null) {
                null
            } else {
                when (val parsed = CalendarArgs.parseInstant(endText, zone, now)) {
                    is ParsedInstant.At -> parsed.epochMillis
                    is ParsedInstant.Invalid -> return@withContext invalid("'end' is unusable: ${parsed.message}")
                }
            }

            val duration = if (allDay) {
                ResolvedDuration.Ok(CalendarArgs.MAX_DURATION_MINUTES, assumed = false)
            } else {
                CalendarArgs.resolveDuration(durationMinutes, endMillis, startMillis)
            }
            if (duration is ResolvedDuration.Invalid) return@withContext invalid(duration.message)
            val resolvedDuration = duration as ResolvedDuration.Ok

            val end = if (allDay) startMillis + 24L * 60L * 60L * 1000L
            else startMillis + resolvedDuration.minutes * 60_000L

            val event = CalendarEvent(
                id = 0L,
                title = title,
                startMillis = startMillis,
                endMillis = end,
                allDay = allDay,
                location = optionalString(args, "location", CalendarArgs.MAX_LOCATION_CHARS),
            )
            val description = optionalString(args, "description", CalendarArgs.MAX_DESCRIPTION_CHARS)

            try {
                val id = provider.insert(
                    title = title,
                    startMillis = startMillis,
                    endMillis = end,
                    allDay = allDay,
                    location = event.location,
                    description = description,
                    zone = zone,
                )
                if (id == null) {
                    return@withContext ToolResult(
                        success = false,
                        observation = "The calendar provider accepted the request but returned no event, " +
                            "so nothing was created. Do not retry; tell the user the event was not saved.",
                        error = ToolError.Unavailable("calendar insert returned no uri"),
                    )
                }
                ToolResult(
                    success = true,
                    observation = CalendarText.created(event.copy(id = id), zone, resolvedDuration.assumed),
                    data = buildJsonObject {
                        put("id", id)
                        put("start", startMillis)
                        put("end", end)
                        put("allDay", allDay)
                    },
                )
            } catch (security: SecurityException) {
                denied()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                ToolResult(
                    success = false,
                    observation = "Creating the event failed (${t.javaClass.simpleName}); nothing was saved. " +
                        "Do not retry; report the failure.",
                    error = ToolError.Internal("calendar insert failed: ${t.javaClass.simpleName}"),
                )
            }
        }

    private fun denied(): ToolResult = ToolResult(
        success = false,
        observation = PermissionDenial.observation("calendar.create", ToolPermissions.CALENDAR_WRITE),
        error = ToolError.PermissionDenied(PermissionDenial.summary(ToolPermissions.CALENDAR_WRITE)),
    )

    private fun invalid(message: String): ToolResult = ToolResult(
        success = false,
        observation = "calendar.create was called with unusable arguments: $message",
        error = ToolError.InvalidArguments(message),
    )

    private fun cancelled(): ToolResult = ToolResult(
        success = false,
        observation = "The event creation was cancelled before it finished; nothing was saved.",
        error = ToolError.Cancelled("cancelled during calendar.create"),
    )
}


// =====================================================================================
// The tool set
// =====================================================================================

/**
 * Both calendar tools, wired to a real [Context].
 *
 * WHY a factory and not three call sites: [dev.localintelligence.android.di.AgentGraph]
 * is the only place allowed to know what the shipped tool set is, and a missing
 * entry here is a tool the agent cannot see. This function is what the
 * composition root calls and what `ToolDefinitionAgreementTest` compares against
 * the catalogue, so the two are the same list by construction.
 *
 * One [ContentResolver] shared by both tools. Constructing a second costs a
 * binder round trip and buys nothing: the resolver is a handle, not a pool.
 */
fun calendarTools(context: Context, grant: PlatformGrant): List<AgentTool> {
    val resolver = context.applicationContext.contentResolver
    return listOf(
        CalendarSearchTool(resolver, ZoneId.systemDefault(), grant),
        CalendarCreateTool(resolver, ZoneId.systemDefault(), grant),
    )
}
