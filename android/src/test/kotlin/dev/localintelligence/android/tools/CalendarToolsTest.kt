package dev.localintelligence.android.tools

import android.database.Cursor
import dev.localintelligence.android.tools.calendar.CalendarArgs
import dev.localintelligence.android.tools.calendar.CalendarCreateTool
import dev.localintelligence.android.tools.calendar.CalendarEvent
import dev.localintelligence.android.tools.calendar.CalendarProvider
import dev.localintelligence.android.tools.calendar.CalendarSearchTool
import dev.localintelligence.android.tools.calendar.CalendarText
import dev.localintelligence.android.tools.calendar.ParsedInstant
import dev.localintelligence.android.tools.calendar.ResolvedDuration
import dev.localintelligence.android.tools.calendar.ResolvedWindow
import dev.localintelligence.core.model.ToolArgs
import dev.localintelligence.core.tool.ObservationTruncator
import dev.localintelligence.core.tool.ToolContext
import dev.localintelligence.core.tool.ToolError
import dev.localintelligence.core.tool.ToolResult
import dev.localintelligence.core.tool.ToolRisk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/**
 * JVM-only tests for the calendar tools. There is no device here and Robolectric is not
 * a dependency, so `CalendarContract`, `ContentUris` and `ContentResolver` cannot
 * execute. Two things are proven, at two strengths:
 *
 *  1. EXECUTED — the real `execute()` of both tools, driven through a fake
 *     [CalendarProvider] and [FakeCursor]. Permission, cancellation, argument rejection,
 *     row mapping, capping, the typed error for a dead provider, and — the one that
 *     matters for RAM — that the cursor is closed on every path.
 *  2. EXECUTED — the pure helpers ([CalendarArgs], [CalendarText]) directly, for the
 *     parsing and coercion edge cases that are tedious to reach through `execute`.
 *
 * NOT provable here, needs a device: that the production provider builds the right
 * `Instances` URI, and that a real provider returns the columns the projection asks
 * for. Those are covered by compilation and review only.
 */
class CalendarToolsTest {

    private val utc: ZoneId = ZoneId.of("UTC")
    private val now: Long = Instant.parse("2026-09-25T12:00:00Z").toEpochMilli()

    private fun millis(iso: String): Long = Instant.parse(iso).toEpochMilli()

    // ================================================================ the seam --

    private class FakeCalendarProvider(
        var cursor: Cursor? = null,
        var throwOnQuery: Throwable? = null,
        var insertId: Long? = 4711L,
        var throwOnInsert: Throwable? = null,
    ) : CalendarProvider {
        var queryCount = 0
        var lastFrom: Long? = null
        var lastTo: Long? = null
        var lastPattern: String? = null
        var lastInsert: InsertArgs? = null

        data class InsertArgs(
            val title: String,
            val startMillis: Long,
            val endMillis: Long,
            val allDay: Boolean,
            val location: String?,
            val description: String?,
        )

        override fun query(fromMillis: Long, toMillis: Long, pattern: String?): Cursor? {
            queryCount++
            lastFrom = fromMillis
            lastTo = toMillis
            lastPattern = pattern
            throwOnQuery?.let { throw it }
            return cursor
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
            lastInsert = InsertArgs(title, startMillis, endMillis, allDay, location, description)
            throwOnInsert?.let { throw it }
            return insertId
        }
    }

    /**
     * The column-name constants the tool asks the cursor for. `android.provider` is
     * stubbed in a unit test, so these are spelled out rather than referenced — they are
     * plain Strings, and a typo would surface as a column that reads as absent, which
     * the tool tolerates by design.
     */
    private object Columns {
        const val ID = "_id"
        const val TITLE = "title"
        const val BEGIN = "begin"
        const val END = "end"
        const val ALL_DAY = "allDay"
        const val LOCATION = "eventLocation"
    }

    private fun eventCursor(vararg events: CalendarEvent): FakeCursor = FakeCursor(
        columns = listOf(Columns.ID, Columns.TITLE, Columns.BEGIN, Columns.END, Columns.ALL_DAY, Columns.LOCATION),
        rows = events.map {
            mapOf(
                Columns.ID to it.id,
                Columns.TITLE to it.title,
                Columns.BEGIN to it.startMillis,
                Columns.END to it.endMillis,
                Columns.ALL_DAY to if (it.allDay) 1 else 0,
                Columns.LOCATION to it.location,
            )
        },
    )

    private fun event(
        id: Long,
        title: String,
        startIso: String,
        minutes: Long,
        allDay: Boolean = false,
        location: String? = null,
    ) = CalendarEvent(id, title, millis(startIso), millis(startIso) + minutes * 60_000, allDay, location)

    // ============================================================ 1. permission --

    @Test
    fun `search without permission returns PermissionDenied without touching the provider`() = runTest {
        val provider = FakeCalendarProvider(cursor = eventCursor())

        val result = search(provider, permissionGranted = false)

        assertFalse(result.success)
        assertTrue(result.error is ToolError.PermissionDenied)
        assertEquals("permission_denied", result.error?.code)
        assertTrue(result.observation, result.observation.contains("Calendar permission"))
        assertEquals("the provider must not be called at all", 0, provider.queryCount)
    }

    @Test
    fun `create without permission returns PermissionDenied without inserting`() = runTest {
        val provider = FakeCalendarProvider()

        val result = create(provider, permissionGranted = false) {
            put("title", "Standup"); put("start", "2026-09-25T09:00:00")
        }

        assertTrue(result.error is ToolError.PermissionDenied)
        assertEquals("permission_denied", result.error?.code)
        assertNull("nothing may be inserted", provider.lastInsert)
    }

    // ================================================================ 2. empty --

    @Test
    fun `an empty window produces a sentence, not an empty string`() = runTest {
        val result = search(FakeCalendarProvider(cursor = eventCursor()))

        assertTrue(result.success)
        assertTrue(result.observation.isNotBlank())
        assertTrue(result.observation, result.observation.startsWith("No events"))
        assertTrue(result.observation, result.observation.contains("25 Sep"))
    }

    @Test
    fun `an empty window with a filter names the filter`() = runTest {
        val result = search(FakeCalendarProvider(cursor = eventCursor()), "query" to "dentist")

        assertTrue(result.success)
        assertTrue(result.observation, result.observation.contains("dentist"))
    }

    // ============================================================== 3. success --

    @Test
    fun `a successful search maps cursor rows into one line per event`() = runTest {
        val provider = FakeCalendarProvider(
            cursor = eventCursor(
                event(1, "Team standup", "2026-09-25T09:00:00Z", 30),
                event(2, "1:1 with Sam", "2026-09-25T14:00:00Z", 60, location = "Room 4"),
            ),
        )

        val result = search(provider)

        assertTrue(result.success)
        assertNull(result.error)
        assertTrue(result.observation, result.observation.startsWith("2 events between"))
        assertTrue(result.observation, result.observation.contains("1. Fri 25 Sep 09:00-09:30  Team standup"))
        assertTrue(result.observation, result.observation.contains("2. Fri 25 Sep 14:00-15:00  1:1 with Sam @ Room 4"))
        assertFalse("no JSON reaches the model", result.observation.contains("{"))
    }

    @Test
    fun `a null column value is tolerated instead of throwing`() = runTest {
        // A provider returns null for TITLE and EVENT_LOCATION on an event with neither,
        // which is exactly what a real calendar does.
        val provider = FakeCalendarProvider(
            cursor = eventCursor(
                CalendarEvent(1, "", millis("2026-09-25T09:00:00Z"), millis("2026-09-25T10:00:00Z"), false, null),
            ),
        )

        val result = search(provider)

        assertTrue(result.success)
        assertTrue(result.observation, result.observation.contains("(no title)"))
    }

    @Test
    fun `a row missing every requested column still yields a line`() = runTest {
        // A provider answering with an unexpected projection: getColumnIndex returns -1
        // for everything and the tool must degrade, not explode.
        val provider = FakeCalendarProvider(
            cursor = FakeCursor(listOf("something_else"), listOf(mapOf("something_else" to 1))),
        )

        val result = search(provider)

        assertTrue(result.success)
        assertTrue(result.observation, result.observation.contains("(no title)"))
    }

    @Test
    fun `a created event reports the new id and the time it was created for`() = runTest {
        val provider = FakeCalendarProvider(insertId = 8123L)

        val result = create(provider) {
            put("title", "Dentist")
            put("start", "2026-09-25T09:00:00")
            put("durationMinutes", "45")
            put("location", "Kliniek")
        }

        assertTrue(result.success)
        assertTrue(result.observation, result.observation.contains("Created \"Dentist\""))
        assertTrue(result.observation, result.observation.contains("09:00-09:45"))
        assertTrue(result.observation, result.observation.contains("@ Kliniek"))
        assertTrue(result.observation, result.observation.contains("8123"))
        assertEquals(millis("2026-09-25T09:00:00Z"), provider.lastInsert?.startMillis)
        assertEquals(millis("2026-09-25T09:45:00Z"), provider.lastInsert?.endMillis)
    }

    @Test
    fun `an all day event is stored as a whole day whatever clock time was given`() = runTest {
        val provider = FakeCalendarProvider()

        val result = create(provider) {
            put("title", "Holiday")
            put("start", "2026-09-25T13:37:00")
            put("allDay", "true")
        }

        assertTrue(result.success)
        assertTrue(provider.lastInsert!!.allDay)
        assertEquals(24L * 60 * 60 * 1000, provider.lastInsert!!.endMillis - provider.lastInsert!!.startMillis)
    }

    // ====================================================== 4. invalid arguments --

    @Test
    fun `a missing required field is rejected before the provider is called`() = runTest {
        val provider = FakeCalendarProvider(cursor = eventCursor())

        val result = CalendarSearchTool(provider, utc).execute(
            args("to" to "2026-09-26"),
            ToolContext(permissionGranted = true),
        )

        assertFalse(result.success)
        assertTrue(result.error is ToolError.InvalidArguments)
        assertTrue(result.error!!.message, result.error!!.message.contains("required"))
        assertEquals(0, provider.queryCount)
    }

    @Test
    fun `an unparseable date is rejected and the accepted formats are named`() = runTest {
        val provider = FakeCalendarProvider(cursor = eventCursor())

        val result = search(provider, "from" to "next tuesday-ish")

        assertFalse(result.success)
        assertTrue(result.error is ToolError.InvalidArguments)
        assertTrue(result.observation, result.observation.contains("2026-09-25T09:00:00"))
        assertEquals(0, provider.queryCount)
    }

    @Test
    fun `a window longer than ninety days is rejected with the split instruction`() = runTest {
        val provider = FakeCalendarProvider(cursor = eventCursor())

        val result = search(
            provider,
            "from" to "2026-01-01T00:00:00",
            "to" to "2026-12-31T00:00:00",
        )

        assertFalse(result.success)
        assertTrue(result.error is ToolError.InvalidArguments)
        assertTrue(result.observation, result.observation.contains("maximum is 90"))
        assertTrue(result.observation, result.observation.contains("Split"))
        assertEquals(0, provider.queryCount)
    }

    @Test
    fun `an inverted window is searched chronologically and says so`() = runTest {
        val provider = FakeCalendarProvider(
            cursor = eventCursor(event(1, "Sync", "2026-09-25T10:00:00Z", 30)),
        )

        val result = search(
            provider,
            "from" to "2026-09-26T00:00:00",
            "to" to "2026-09-25T00:00:00",
        )

        assertTrue(result.success)
        assertTrue("the window is normalised", provider.lastFrom!! < provider.lastTo!!)
        assertEquals(millis("2026-09-25T00:00:00Z"), provider.lastFrom)
        assertEquals(millis("2026-09-26T00:00:00Z"), provider.lastTo)
        assertTrue(result.observation, result.observation.contains("reversed"))
    }

    @Test
    fun `an absurd duration is rejected and nothing is inserted`() = runTest {
        val provider = FakeCalendarProvider()

        val result = create(provider) {
            put("title", "Marathon")
            put("start", "2026-09-25T09:00:00")
            put("durationMinutes", "5000")
        }

        assertFalse(result.success)
        assertTrue(result.error is ToolError.InvalidArguments)
        assertTrue(result.observation, result.observation.contains("1440"))
        assertNull(provider.lastInsert)
    }

    @Test
    fun `a create with no title is rejected`() = runTest {
        val provider = FakeCalendarProvider()

        for (bad in listOf(null, "", "   ")) {
            val result = create(provider) {
                put("title", bad); put("start", "2026-09-25T09:00:00")
            }
            assertFalse("title=$bad", result.success)
            assertTrue("title=$bad", result.error is ToolError.InvalidArguments)
        }
        assertNull(provider.lastInsert)
    }

    @Test
    fun `a non numeric duration is rejected rather than silently defaulting`() = runTest {
        val provider = FakeCalendarProvider()

        val result = create(provider) {
            put("title", "X")
            put("start", "2026-09-25T09:00:00")
            put("durationMinutes", "half an hour")
        }

        assertFalse(result.success)
        assertTrue(result.error is ToolError.InvalidArguments)
        assertNull(provider.lastInsert)
    }

    // ============================================================== 5. coercion --

    @Test
    fun `a stringified limit is honoured an absurd one is clamped and junk falls back`() = runTest {
        // 60 rows, so a limit clamped to 50 must genuinely truncate.
        val events = (1..60).map { event(it.toLong(), "Event $it", "2026-09-25T09:00:00Z", 30) }
        fun provider() = FakeCalendarProvider(cursor = eventCursor(*events.toTypedArray()))

        val asString = search(provider(), "limit" to "5")
        val absurd = search(provider(), "limit" to "9999")
        val junk = search(provider(), "limit" to "banana")

        assertTrue(asString.observation, asString.observation.contains("Showing 5 events, more exist"))
        assertTrue(absurd.observation, absurd.observation.contains("Showing 50 events, more exist"))
        assertTrue(junk.observation, junk.observation.contains("Showing 20 events, more exist"))
    }

    @Test
    fun `a like metacharacter in the query is escaped before it reaches the provider`() = runTest {
        val provider = FakeCalendarProvider(cursor = eventCursor())

        search(provider, "query" to "50%")

        assertEquals("%50\\%%", provider.lastPattern)
    }

    @Test
    fun `no filter means no pattern reaches the provider at all`() = runTest {
        val provider = FakeCalendarProvider(cursor = eventCursor())

        search(provider)

        assertNull(provider.lastPattern)
    }

    @Test
    fun `limit coercion covers the shapes a model actually emits`() {
        assertEquals(8, CalendarArgs.coerceLimit("8"))
        assertEquals(8, CalendarArgs.coerceLimit(" 8 "))
        assertEquals(8, CalendarArgs.coerceLimit("8.0"))
        // A fractional limit truncates rather than rounds: never show more than asked.
        assertEquals(7, CalendarArgs.coerceLimit("7.9"))
        assertEquals(20, CalendarArgs.coerceLimit(null))
        assertEquals(20, CalendarArgs.coerceLimit(""))
        assertEquals(20, CalendarArgs.coerceLimit("null"))
        assertEquals(20, CalendarArgs.coerceLimit("banana"))
        assertEquals(20, CalendarArgs.coerceLimit("-4"))
        assertEquals(20, CalendarArgs.coerceLimit("0"))
        assertEquals(50, CalendarArgs.coerceLimit("5000"))
        assertEquals(50, CalendarArgs.coerceLimit(Int.MAX_VALUE.toString()))
    }

    @Test
    fun `iso date variants all parse to the same instant`() {
        val expected = millis("2026-09-25T09:30:00Z")
        for (text in listOf(
            "2026-09-25T09:30:00Z",
            "2026-09-25t09:30:00z",
            "2026-09-25 09:30:00+00:00",
            "2026-09-25 09:30:00 +00:00",
            "2026-09-25T11:30:00+02:00",
            "2026-09-25T09:30:00.000Z",
            "1790328600000",  // epoch millis for the same instant
        )) {
            val parsed = CalendarArgs.parseInstant(text, utc, now)
            assertTrue("'$text' -> $parsed", parsed is ParsedInstant.At)
            assertEquals("'$text'", expected, (parsed as ParsedInstant.At).epochMillis)
        }
    }

    @Test
    fun `a zone-less value uses the device zone and a bare date means midnight`() {
        assertEquals(millis("2026-09-25T09:30:00Z"), at(CalendarArgs.parseInstant("2026-09-25T09:30:00", utc, now)))
        assertEquals(
            millis("2026-09-25T07:30:00Z"),
            at(CalendarArgs.parseInstant("2026-09-25T09:30:00", ZoneId.of("Europe/Berlin"), now)),
        )
        assertEquals(millis("2026-09-25T00:00:00Z"), at(CalendarArgs.parseInstant("2026-09-25", utc, now)))
    }

    @Test
    fun `us style dates and twelve hour times are understood`() {
        assertEquals(millis("2026-09-25T14:00:00Z"), at(CalendarArgs.parseInstant("2026-09-25 2:00 PM", utc, now)))
        assertEquals(millis("2026-09-25T09:00:00Z"), at(CalendarArgs.parseInstant("2026-09-25 9:00 am", utc, now)))
        assertEquals(millis("2026-09-25T00:00:00Z"), at(CalendarArgs.parseInstant("09/25/2026", utc, now)))
    }

    @Test
    fun `today and tomorrow are relative to the supplied now not the machine clock`() {
        assertEquals(millis("2026-09-25T00:00:00Z"), at(CalendarArgs.parseInstant("today", utc, now)))
        assertEquals(millis("2026-09-26T00:00:00Z"), at(CalendarArgs.parseInstant("TOMORROW", utc, now)))
        assertEquals(millis("2026-09-24T00:00:00Z"), at(CalendarArgs.parseInstant("Yesterday", utc, now)))
        assertEquals(now, at(CalendarArgs.parseInstant("now", utc, now)))
    }

    @Test
    fun `an implausible epoch is rejected instead of becoming the year 5138`() {
        assertTrue(CalendarArgs.parseInstant("999999999999999", utc, now) is ParsedInstant.Invalid)
        assertTrue(CalendarArgs.parseInstant("-5000", utc, now) is ParsedInstant.Invalid)
    }

    @Test
    fun `nonsense dates are rejected`() {
        for (junk in listOf("next thursday-ish", "banana", "2026-13-45T99:99:99", "--", "null", "  ")) {
            assertTrue("'$junk'", CalendarArgs.parseInstant(junk, utc, now) is ParsedInstant.Invalid)
        }
        val message = (CalendarArgs.parseInstant("banana", utc, now) as ParsedInstant.Invalid).message
        assertTrue(message, message.contains("2026-09-25T09:00:00"))
        assertTrue(message, message.contains("today/tomorrow"))
    }

    @Test
    fun `booleans arrive in several shapes and all mean the same thing`() {
        for (text in listOf("true", "TRUE", "1", "yes", "Y")) {
            assertTrue("'$text'", CalendarArgs.coerceBoolean(text))
        }
        for (text in listOf("false", "0", "no", "", "banana", null)) {
            assertFalse("'$text'", CalendarArgs.coerceBoolean(text))
        }
    }

    @Test
    fun `like metacharacters are escaped so a percent does not match everything`() {
        assertEquals("50\\%", CalendarArgs.escapeLike("50%"))
        assertEquals("a\\_b", CalendarArgs.escapeLike("a_b"))
        assertEquals("c\\\\d", CalendarArgs.escapeLike("c\\d"))
        // An injection payload is neutralised to harmless literal text.
        assertEquals("'; DROP TABLE events; --", CalendarArgs.escapeLike("'; DROP TABLE events; --"))
    }

    @Test
    fun `free text is collapsed trimmed and capped so one field cannot eat the budget`() {
        assertEquals("a b c", CalendarArgs.clean("  a   b \n c  ", 100))
        assertNull(CalendarArgs.clean("   ", 100))
        assertNull(CalendarArgs.clean(null, 100))
        val capped = CalendarArgs.clean("x".repeat(200), 20)
        assertNotNull(capped)
        assertEquals(20, capped!!.length)
        assertTrue(capped.endsWith("…"))
    }

    @Test
    fun `an explicit json null argument reads as absent not as the string null`() {
        val args = buildJsonObject {
            put("from", "2026-09-25")
            put("to", JsonNull)
            put("limit", JsonNull)
        }
        assertNotNull(args["to"])
        assertTrue(CalendarArgs.requiredInstant(args, "to", utc, now) is ParsedInstant.Invalid)
        assertEquals(20, CalendarArgs.coerceLimit((args["limit"] as JsonPrimitive).content))
    }

    @Test
    fun `window boundary is exact at ninety days`() {
        val ninetyDays = 90L * 24 * 60 * 60 * 1000
        val from = millis("2026-01-01T00:00:00Z")
        assertTrue(CalendarArgs.resolveWindow(from, from + ninetyDays) is ResolvedWindow.Ok)
        assertTrue(CalendarArgs.resolveWindow(from, from + ninetyDays + 1) is ResolvedWindow.Invalid)
    }

    @Test
    fun `an empty window where from equals to is rejected as unusable`() {
        val at = millis("2026-09-25T09:00:00Z")
        val window = CalendarArgs.resolveWindow(at, at)
        assertTrue(window is ResolvedWindow.Invalid)
        assertTrue((window as ResolvedWindow.Invalid).message.contains("empty"))
    }

    @Test
    fun `zero and negative durations and an end before the start are rejected`() {
        assertTrue(CalendarArgs.resolveDuration(0, null, 0) is ResolvedDuration.Invalid)
        assertTrue(CalendarArgs.resolveDuration(-30, null, 0) is ResolvedDuration.Invalid)
        val start = millis("2026-09-25T09:00:00Z")
        assertTrue(CalendarArgs.resolveDuration(null, start, start) is ResolvedDuration.Invalid)
    }

    @Test
    fun `a missing duration falls back to sixty minutes and says it assumed that`() = runTest {
        val provider = FakeCalendarProvider()

        val result = create(provider) {
            put("title", "Standup")
            put("start", "2026-09-25T09:00:00")
        }

        assertTrue(result.success)
        assertEquals(60L * 60_000, provider.lastInsert!!.endMillis - provider.lastInsert!!.startMillis)
        assertTrue(result.observation, result.observation.contains("60 min"))
    }

    @Test
    fun `an end instead of a duration is converted to a duration`() = runTest {
        val provider = FakeCalendarProvider()

        val result = create(provider) {
            put("title", "Call")
            put("start", "2026-09-25T09:00:00")
            put("end", "2026-09-25T09:30:00")
        }

        assertTrue(result.success)
        assertEquals(millis("2026-09-25T09:30:00Z"), provider.lastInsert!!.endMillis)
        assertFalse(result.observation, result.observation.contains("was not given"))
    }

    // =============================================================== 6. capping --

    @Test
    fun `more events than the limit are capped and the observation says so`() = runTest {
        val events = (1..30).map { event(it.toLong(), "Event $it", "2026-09-25T09:00:00Z", 30) }

        val result = search(FakeCalendarProvider(cursor = eventCursor(*events.toTypedArray())), "limit" to "5")

        assertTrue(result.observation, result.observation.contains("Showing 5 events, more exist"))
        assertEquals(5, eventLineCount(result))
    }

    @Test
    fun `exactly the limit of events is not described as truncated`() = runTest {
        val events = (1..5).map { event(it.toLong(), "Event $it", "2026-09-25T09:00:00Z", 30) }

        val result = search(FakeCalendarProvider(cursor = eventCursor(*events.toTypedArray())), "limit" to "5")

        assertFalse(result.observation, result.observation.contains("more exist"))
        assertTrue(result.observation, result.observation.startsWith("5 events"))
    }

    @Test
    fun `a fifty event window still fits the observation budget`() = runTest {
        val events = (1..50).map {
            event(it.toLong(), "Meeting $it with a long name", "2026-09-25T09:00:00Z", 60, location = "Room $it")
        }

        val result = search(
            FakeCalendarProvider(cursor = eventCursor(*events.toTypedArray())),
            "limit" to "50",
            "query" to "meeting",
        )

        assertTrue(result.success)
        assertTrue(
            "observation was ${result.observation.length} chars",
            result.observation.length <= ObservationTruncator.DEFAULT_BUDGET_CHARS,
        )
    }

    // ===================================================== 7. provider failure --

    @Test
    fun `a missing provider is Unavailable and no exception escapes`() = runTest {
        val result = search(FakeCalendarProvider(cursor = null))

        assertFalse(result.success)
        assertTrue(result.error is ToolError.Unavailable)
        assertEquals("unavailable", result.error?.code)
        assertTrue(result.observation, result.observation.contains("No calendar provider"))
    }

    @Test
    fun `a provider that throws is a typed failure with no stack trace in the observation`() = runTest {
        val result = search(
            FakeCalendarProvider(throwOnQuery = IllegalStateException("cursor window died")),
        )

        assertFalse(result.success)
        assertTrue(result.error is ToolError.Internal)
        assertTrue(result.observation, result.observation.contains("IllegalStateException"))
        assertFalse(result.observation, result.observation.contains("at dev.localintelligence"))
        assertFalse("the provider message must not leak verbatim", result.observation.contains("cursor window died"))
    }

    @Test
    fun `a SecurityException from the provider is PermissionDenied`() = runTest {
        val result = search(FakeCalendarProvider(throwOnQuery = SecurityException("READ_CALENDAR needed")))

        assertFalse(result.success)
        assertTrue(result.error is ToolError.PermissionDenied)
    }

    @Test
    fun `a provider that throws on insert does not claim the event was created`() = runTest {
        val provider = FakeCalendarProvider(throwOnInsert = IllegalStateException("disk full"))

        val result = create(provider) {
            put("title", "Standup"); put("start", "2026-09-25T09:00:00")
        }

        assertFalse(result.success)
        assertTrue(result.error is ToolError.Internal)
        assertTrue(result.observation, result.observation.contains("nothing was saved"))
    }

    @Test
    fun `a provider that refuses the insert is Unavailable not a false success`() = runTest {
        val provider = FakeCalendarProvider(insertId = null)

        val result = create(provider) {
            put("title", "Standup"); put("start", "2026-09-25T09:00:00")
        }

        assertFalse(result.success)
        assertTrue(result.error is ToolError.Unavailable)
        assertFalse(result.observation, result.observation.startsWith("Created"))
    }

    // ======================================================== 8. cancellation --

    @Test
    fun `a cancelled signal short-circuits before the provider is touched`() = runTest {
        val provider = FakeCalendarProvider(cursor = eventCursor())

        val result = search(provider, signalCancelled = true)

        assertFalse(result.success)
        assertTrue(result.error is ToolError.Cancelled)
        assertEquals("cancelled", result.error?.code)
        assertEquals(0, provider.queryCount)
    }

    @Test
    fun `a cancellation part way through a scan still closes the cursor`() = runTest {
        // The signal flips on the very first cell read, i.e. after the loop has started
        // but before any event is mapped. The cursor must still be released.
        var reads = 0
        val events = (1..10).map { event(it.toLong(), "Event $it", "2026-09-25T09:00:00Z", 30) }
        val cursor = FakeCursor(
            columns = listOf(Columns.ID, Columns.TITLE, Columns.BEGIN, Columns.END, Columns.ALL_DAY, Columns.LOCATION),
            rows = events.map {
                mapOf(
                    Columns.ID to it.id,
                    Columns.TITLE to it.title,
                    Columns.BEGIN to it.startMillis,
                    Columns.END to it.endMillis,
                    Columns.ALL_DAY to 0,
                    Columns.LOCATION to null,
                )
            },
            onRead = { reads++ },
        )

        val result = CalendarSearchTool(FakeCalendarProvider(cursor = cursor), utc).execute(
            args("from" to "2026-09-25T00:00:00", "to" to "2026-09-26T00:00:00", "limit" to "10"),
            ToolContext(permissionGranted = true, signal = { reads >= 1 }),
        )

        assertTrue("the scan actually started", reads >= 1)
        assertTrue(result.error is ToolError.Cancelled)
        assertEquals("a cancelled scan must still release the cursor", 1, cursor.closeCount)
    }

    // ================================================== 9. cursor is closed --

    @Test
    fun `the cursor is closed exactly once on the success path`() = runTest {
        val cursor = eventCursor(event(1, "Standup", "2026-09-25T09:00:00Z", 30))

        search(FakeCalendarProvider(cursor = cursor))

        assertEquals(1, cursor.closeCount)
    }

    @Test
    fun `the cursor is closed on the empty path too`() = runTest {
        val cursor = eventCursor()

        search(FakeCalendarProvider(cursor = cursor))

        assertEquals(1, cursor.closeCount)
    }

    @Test
    fun `the cursor is closed when the provider throws mid scan`() = runTest {
        val cursor = FakeCursor(
            columns = listOf(Columns.TITLE),
            rows = listOf(mapOf(Columns.TITLE to "Standup")),
            failOnRead = IllegalStateException("cursor window died"),
        )

        val result = search(FakeCalendarProvider(cursor = cursor))

        assertFalse(result.success)
        assertEquals("a cursor must be released even when the read throws", 1, cursor.closeCount)
    }

    @Test
    fun `the cursor is closed exactly once per query, never zero and never twice`() = runTest {
        val cursors = mutableListOf<FakeCursor>()

        repeat(5) {
            val cursor = eventCursor(event(it.toLong(), "Event $it", "2026-09-25T09:00:00Z", 30))
            cursors += cursor
            search(FakeCalendarProvider(cursor = cursor))
        }

        for (cursor in cursors) {
            assertEquals(1, cursor.closeCount)
            assertTrue("the cursor must be closed", cursor.isClosed)
        }
    }

    // ================================================= 10. the shape of a tool --

    @Test
    fun `definitions declare honest risk category and permission`() {
        val search = CalendarSearchTool(FakeCalendarProvider(), utc).definition
        val create = CalendarCreateTool(FakeCalendarProvider(), utc).definition

        assertEquals("calendar.search", search.name)
        assertEquals("calendar", search.category)
        assertEquals(ToolRisk.READ_ONLY, search.risk)
        assertEquals("android.permission.READ_CALENDAR", search.requiredPermission)

        assertEquals("calendar.create", create.name)
        assertEquals("calendar", create.category)
        assertEquals(ToolRisk.REVERSIBLE, create.risk)
        assertEquals("android.permission.WRITE_CALENDAR", create.requiredPermission)

        // REVERSIBLE is derived to NOT require confirmation. Pinned so a future
        // "just make it DESTRUCTIVE" does not silently add a dialog.
        assertFalse(create.risk.requiresConfirmation)
    }

    @Test
    fun `each tool carries between four and eight unique lowercase retrieval tags`() {
        for (definition in listOf(
            CalendarSearchTool(FakeCalendarProvider(), utc).definition,
            CalendarCreateTool(FakeCalendarProvider(), utc).definition,
        )) {
            assertTrue("${definition.name} has ${definition.tags.size}", definition.tags.size in 4..8)
            assertEquals(definition.tags.size, definition.tags.map { it.lowercase() }.toSet().size)
        }
    }

    @Test
    fun `descriptions are one imperative sentence about what is returned`() {
        for (definition in listOf(
            CalendarSearchTool(FakeCalendarProvider(), utc).definition,
            CalendarCreateTool(FakeCalendarProvider(), utc).definition,
        )) {
            assertTrue(definition.description, definition.description.endsWith("."))
            assertEquals(1, definition.description.split(". ").size)
            assertTrue(definition.description.first().isUpperCase())
        }
    }

    @Test
    fun `schemas are object schemas with properties and a non empty required list`() {
        for (definition in listOf(
            CalendarSearchTool(FakeCalendarProvider(), utc).definition,
            CalendarCreateTool(FakeCalendarProvider(), utc).definition,
        )) {
            assertEquals("object", (definition.schema["type"] as JsonPrimitive).content)
            assertNotNull("${definition.name} needs properties", definition.schema["properties"])
            val required = definition.schema["required"] as? JsonArray
            assertNotNull("${definition.name} must declare required", required)
            assertTrue(required!!.isNotEmpty())
        }
    }

    @Test
    fun `search requires from and to, create requires title and start`() {
        val searchRequired = CalendarSearchTool(FakeCalendarProvider(), utc)
            .definition.schema["required"] as JsonArray
        val createRequired = CalendarCreateTool(FakeCalendarProvider(), utc)
            .definition.schema["required"] as JsonArray

        assertEquals(listOf("from", "to"), searchRequired.map { (it as JsonPrimitive).content })
        assertEquals(listOf("title", "start"), createRequired.map { (it as JsonPrimitive).content })
    }

    // ================================================= 11. observation budget --

    @Test
    fun `a worst case event observation stays under the budget`() {
        val events = (1..50).map {
            CalendarEvent(
                id = it.toLong(),
                title = "A".repeat(300),
                startMillis = millis("2026-09-25T09:00:00Z"),
                endMillis = millis("2026-09-26T09:00:00Z"),
                allDay = false,
                location = "B".repeat(300),
            )
        }

        val observation = CalendarText.search(
            events = events,
            hasMore = true,
            fromMillis = millis("2026-09-25T00:00:00Z"),
            toMillis = millis("2026-10-25T00:00:00Z"),
            swapped = false,
            query = "A".repeat(500),
            zone = utc,
        )

        assertTrue(
            "observation was ${observation.length} chars",
            observation.length <= ObservationTruncator.DEFAULT_BUDGET_CHARS,
        )
    }

    @Test
    fun `every observation the tools can produce is bounded by the budget`() {
        val samples = listOf(
            CalendarText.search(emptyList(), false, now, now + 1000, false, null, utc),
            CalendarText.search(
                (1..50).map { event(it.toLong(), "x".repeat(400), "2026-09-25T09:00:00Z", 60, location = "y".repeat(400)) },
                true, now, now + 1000, true, "q".repeat(200), utc,
            ),
            CalendarText.created(
                CalendarEvent(1, "T".repeat(500), now, now + 60_000, false, "L".repeat(500)),
                utc,
                durationAssumed = true,
            ),
        )
        for (sample in samples) {
            assertTrue("sample was ${sample.length} chars", sample.length <= ObservationTruncator.DEFAULT_BUDGET_CHARS)
        }
    }

    // ---------------------------------------------------------------- helpers --

    private suspend fun search(
        provider: FakeCalendarProvider,
        vararg extra: Pair<String, Any?>,
        permissionGranted: Boolean = true,
        signalCancelled: Boolean = false,
    ): ToolResult {
        val all = listOf<Pair<String, Any?>>(
            "from" to "2026-09-25T00:00:00",
            "to" to "2026-09-26T00:00:00",
        ) + extra
        return CalendarSearchTool(provider, utc).execute(
            args(*all.toTypedArray()),
            ToolContext(permissionGranted = permissionGranted, signal = { signalCancelled }),
        )
    }

    private suspend fun create(
        provider: FakeCalendarProvider,
        permissionGranted: Boolean = true,
        build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit,
    ): ToolResult = CalendarCreateTool(provider, utc).execute(
        buildJsonObject(build),
        ToolContext(permissionGranted = permissionGranted),
    )

    private fun args(vararg pairs: Pair<String, Any?>): ToolArgs = buildJsonObject {
        for ((key, value) in pairs) {
            when (value) {
                null -> put(key, JsonNull)
                is Number -> put(key, value)
                is Boolean -> put(key, value)
                else -> put(key, value.toString())
            }
        }
    }

    private fun eventLineCount(result: ToolResult): Int =
        result.observation.lines().count { Regex("""^\d+\. """).containsMatchIn(it) }

    private fun at(parsed: ParsedInstant): Long = (parsed as ParsedInstant.At).epochMillis
}

/**
 * A JVM `android.database.Cursor` fake with real behaviour.
 *
 * `android.database.Cursor` is a plain interface, so it can be implemented off-device.
 * The AGP unit-test runtime substitutes a mockable android.jar whose *Android*
 * implementations throw, but this class is ours, so it runs for real. That is what lets
 * the tests above PROVE the cursor is closed on every path, rather than asserting that a
 * `finally` block was written — which is the whole point of §16 RAM.
 */
internal class FakeCursor(
    private val columns: List<String>,
    private val rows: List<Map<String, Any?>>,
    private val failOnRead: Throwable? = null,
    /** Called on every value read; lets a test flip a cancellation mid-scan. */
    private val onRead: (() -> Unit)? = null,
) : Cursor {

    var closeCount: Int = 0
        private set

    var readCount: Int = 0
        private set

    private var position: Int = -1
    private var closed: Boolean = false

    private fun value(column: String): Any? {
        onRead?.invoke()
        failOnRead?.let { throw it }
        readCount++
        if (position !in rows.indices) return null
        return rows[position][column]
    }

    override fun close() {
        closeCount++
        closed = true
    }

    override fun isClosed(): Boolean = closed

    override fun moveToNext(): Boolean {
        if (closed || position >= rows.size) return false
        position++
        return position < rows.size
    }

    override fun moveToFirst(): Boolean {
        position = 0
        return rows.isNotEmpty()
    }

    override fun moveToLast(): Boolean {
        position = rows.size - 1
        return rows.isNotEmpty()
    }

    override fun moveToPrevious(): Boolean {
        if (position <= 0) return false
        position--
        return true
    }

    override fun moveToPosition(position: Int): Boolean {
        this.position = position
        return position in rows.indices
    }

    override fun getPosition(): Int = position

    override fun getCount(): Int = rows.size

    override fun getColumnCount(): Int = columns.size

    override fun getColumnName(index: Int): String = columns[index]

    override fun getColumnNames(): Array<String> = columns.toTypedArray()

    override fun getColumnIndex(columnName: String): Int = columns.indexOf(columnName)

    override fun getColumnIndexOrThrow(columnName: String): Int =
        columns.indexOf(columnName).also { if (it < 0) throw IllegalArgumentException(columnName) }

    override fun getType(columnIndex: Int): Int = when (value(columns[columnIndex])) {
        null -> Cursor.FIELD_TYPE_NULL
        is String -> Cursor.FIELD_TYPE_STRING
        is Int, is Long, is Short -> Cursor.FIELD_TYPE_INTEGER
        is Float, is Double -> Cursor.FIELD_TYPE_FLOAT
        is ByteArray -> Cursor.FIELD_TYPE_BLOB
        else -> Cursor.FIELD_TYPE_STRING
    }

    override fun isNull(columnIndex: Int): Boolean = value(columns[columnIndex]) == null

    override fun getString(columnIndex: Int): String? = value(columns[columnIndex]) as? String

    override fun getLong(columnIndex: Int): Long = when (val v = value(columns[columnIndex])) {
        is Long -> v
        is Int -> v.toLong()
        is String -> v.toLongOrNull() ?: 0L
        else -> 0L
    }

    override fun getInt(columnIndex: Int): Int = when (val v = value(columns[columnIndex])) {
        is Int -> v
        is Long -> v.toInt()
        is String -> v.toIntOrNull() ?: 0
        else -> 0
    }

    override fun getShort(columnIndex: Int): Short = getInt(columnIndex).toShort()

    override fun getDouble(columnIndex: Int): Double = when (val v = value(columns[columnIndex])) {
        is Double -> v
        is Float -> v.toDouble()
        is Int -> v.toDouble()
        else -> 0.0
    }

    override fun getFloat(columnIndex: Int): Float = getDouble(columnIndex).toFloat()

    override fun getBlob(columnIndex: Int): ByteArray? = value(columns[columnIndex]) as? ByteArray

    override fun copyStringToBuffer(columnIndex: Int, buffer: android.database.CharArrayBuffer) = Unit
    override fun deactivate() = Unit
    override fun getExtras(): android.os.Bundle? = null
    override fun getNotificationUri(): android.net.Uri? = null
    override fun isAfterLast(): Boolean = position >= rows.size
    override fun isBeforeFirst(): Boolean = position < 0
    override fun isFirst(): Boolean = position == 0
    override fun isLast(): Boolean = position == rows.size - 1
    override fun getWantsAllOnMoveCalls(): Boolean = false
    override fun move(count: Int): Boolean = moveToPosition(position + count)
    override fun registerContentObserver(observer: android.database.ContentObserver?) = Unit
    override fun registerDataSetObserver(observer: android.database.DataSetObserver?) = Unit
    override fun requery(): Boolean = false
    override fun respond(bundle: android.os.Bundle?): android.os.Bundle? = null
    override fun setExtras(bundle: android.os.Bundle?) = Unit
    override fun setNotificationUri(resolver: android.content.ContentResolver?, uri: android.net.Uri?) = Unit
    override fun unregisterContentObserver(observer: android.database.ContentObserver?) = Unit
    override fun unregisterDataSetObserver(observer: android.database.DataSetObserver?) = Unit
}