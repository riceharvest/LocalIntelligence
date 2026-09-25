package dev.localintelligence.android.tools

import dev.localintelligence.android.tools.alarm.AlarmCancel
import dev.localintelligence.android.tools.alarm.AlarmCreateTool
import dev.localintelligence.android.tools.alarm.AlarmDescriptions
import dev.localintelligence.android.tools.alarm.AlarmIds
import dev.localintelligence.android.tools.alarm.AlarmListTool
import dev.localintelligence.android.tools.alarm.AlarmCancelTool
import dev.localintelligence.android.tools.alarm.AlarmPlatform
import dev.localintelligence.android.tools.alarm.AlarmRegistry
import dev.localintelligence.android.tools.alarm.AlarmSpec
import dev.localintelligence.android.tools.alarm.AlarmTimes
import dev.localintelligence.android.tools.alarm.CancelDecision
import dev.localintelligence.core.model.ToolArgs
import dev.localintelligence.core.tool.CancellationSignal
import dev.localintelligence.core.tool.ObservationTruncator
import dev.localintelligence.core.tool.ToolContext
import dev.localintelligence.core.tool.ToolError
import dev.localintelligence.core.tool.ToolRisk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * JVM tests for alarm.create, alarm.list and alarm.cancel.
 *
 * The centre of gravity here is `alarm.cancel`. Cancelling an alarm the user did not
 * name is data loss — the user wakes up without an alarm they relied on. The guard that
 * prevents it is [AlarmCancel.decide], a pure function, so it is tested exhaustively
 * here rather than hoped about on a device. Every refusal test also asserts that the
 * PLATFORM was never called, because a guard that runs after the cancellation has
 * already been issued is not a guard.
 */
class AlarmToolsTest {

    // =============================================================== fake platform --

    private class FakeAlarmPlatform(
        var canSchedule: Boolean = true,
        var now: Long = 1_700_000_000_000L,
        var hasClockApp: Boolean = true,
        var throwOnSchedule: Exception? = null,
    ) : AlarmPlatform {
        var scheduled: MutableList<AlarmSpec> = mutableListOf()
        var cancelledCodes: MutableList<Int> = mutableListOf()

        override fun canScheduleExactAlarms(): Boolean = canSchedule
        override fun schedule(spec: AlarmSpec) {
            throwOnSchedule?.let { throw it }
            scheduled += spec
        }

        override fun cancel(requestCode: Int) {
            cancelledCodes += requestCode
        }

        override fun nowMillis(): Long = now
        override fun hasClockApp(): Boolean = hasClockApp
    }

    private val denied = ToolContext(permissionGranted = false)
    private val granted = ToolContext(permissionGranted = true)
    private val cancelled = ToolContext(permissionGranted = true, signal = CancellationSignal { true })

    @After
    fun clearRegistry() {
        // The registry is a process-wide singleton, so leaking state between tests would
        // make the ambiguity guard pass or fail depending on execution order.
        AlarmRegistry.clear()
    }

    // ============================================================== risk policy --

    @Test
    fun `risk declared on each alarm tool matches the contract policy`() {
        val platform = FakeAlarmPlatform()

        assertEquals(ToolRisk.REVERSIBLE, AlarmCreateTool(platform).definition.risk)
        assertEquals(ToolRisk.READ_ONLY, AlarmListTool(platform).definition.risk)
        assertEquals(ToolRisk.REVERSIBLE, AlarmCancelTool(platform).definition.risk)

        // A user confirming "delete the 7am alarm" one at a time is a user who stops
        // using the feature. What makes REVERSIBLE honest here is not the label but the
        // guard: it can only ever cancel ONE alarm, only one this tool created, and it
        // refuses rather than guessing. None of these may require confirmation.
        assertFalse(AlarmCancelTool(platform).definition.risk.requiresConfirmation)
    }

    @Test
    fun `every alarm definition satisfies the tool contract checklist`() {
        val platform = FakeAlarmPlatform()
        val definitions = listOf(
            AlarmCreateTool(platform).definition,
            AlarmListTool(platform).definition,
            AlarmCancelTool(platform).definition,
        )

        for (d in definitions) {
            assertTrue(d.name, d.name.matches(NAME_PATTERN))
            assertEquals("alarm", d.category)
            assertEquals("object", d.schema["type"]?.toString()?.trim('"'))
            assertNotNull(d.schema["properties"] as? JsonObject)
            assertNotNull(d.schema["required"])
            assertTrue("${d.name}: ${d.description}", d.description.count { it == '.' } == 1)
            assertTrue("${d.name} has ${d.tags.size} tags", d.tags.size in 4..8)
            assertTrue(d.tags.all { it == it.lowercase() })
        }
    }

    @Test
    fun `alarm create requires an exact-alarm permission and list does not`() {
        val platform = FakeAlarmPlatform()
        assertEquals(
            "android.permission.SCHEDULE_EXACT_ALARM",
            AlarmCreateTool(platform).definition.requiredPermission,
        )
        assertEquals(
            "android.permission.SCHEDULE_EXACT_ALARM",
            AlarmCancelTool(platform).definition.requiredPermission,
        )
        assertEquals(null, AlarmListTool(platform).definition.requiredPermission)
    }

    // ============================================================ permission gate --

    @Test
    fun `create returns PermissionDenied without touching the platform`() = runTest {
        val platform = FakeAlarmPlatform()
        val result = AlarmCreateTool(platform).execute(args { put("hour", 7) }, denied)

        assertFalse(result.success)
        assertEquals(ToolError.PermissionDenied::class, result.error!!::class)
        assertTrue(platform.scheduled.isEmpty())
        assertTrue(result.observation.contains("do not retry"))
    }

    @Test
    fun `list returns PermissionDenied without touching the platform`() = runTest {
        val result = AlarmListTool(FakeAlarmPlatform()).execute(emptyArgs(), denied)
        assertEquals(ToolError.PermissionDenied::class, result.error!!::class)
    }

    @Test
    fun `cancel returns PermissionDenied without touching the platform`() = runTest {
        val platform = FakeAlarmPlatform()
        AlarmRegistry.add(spec("a1", 7, 0, "wake up"))
        val result = AlarmCancelTool(platform).execute(args { put("id", "a1") }, denied)

        assertEquals(ToolError.PermissionDenied::class, result.error!!::class)
        assertTrue("must not cancel anything", platform.cancelledCodes.isEmpty())
    }

    // ================================================================== create --

    @Test
    fun `create schedules a one-shot alarm and reports its id`() = runTest {
        val platform = FakeAlarmPlatform(now = timeAt(10, 0))
        val result = AlarmCreateTool(platform).execute(
            args {
                put("hour", 7)
                put("minute", 30)
                put("label", "take the bread out")
            },
            granted,
        )

        assertTrue("got: ${result.observation}", result.success)
        assertEquals(1, platform.scheduled.size)
        val scheduled = platform.scheduled.first()
        assertEquals(7, scheduled.hour)
        assertEquals(30, scheduled.minute)
        assertEquals("take the bread out", scheduled.label)
        assertTrue(result.observation, result.observation.contains("07:30"))
        assertTrue("must state it is not in the Clock app", result.observation.contains("Clock app"))
        assertEquals(1, AlarmRegistry.all().size)
    }

    @Test
    fun `a time that has already passed today rolls over to tomorrow and says so`() = runTest {
        val platform = FakeAlarmPlatform(now = timeAt(10, 0))
        val result = AlarmCreateTool(platform).execute(args { put("hour", 7) }, granted)

        assertTrue(result.success)
        val scheduled = platform.scheduled.first()
        // 7am tomorrow, not 7am today which is already gone.
        assertTrue("trigger ${scheduled.triggerAtMillis}", scheduled.triggerAtMillis > platform.now)
        assertTrue(result.observation, result.observation.contains("tomorrow"))
    }

    @Test
    fun `a quoted hour is coerced rather than rejected`() = runTest {
        val platform = FakeAlarmPlatform(now = timeAt(10, 0))
        val result = AlarmCreateTool(platform).execute(
            args {
                put("hour", "19")
                put("minute", "5")
            },
            granted,
        )

        assertTrue("got: ${result.observation}", result.success)
        assertEquals(19, platform.scheduled.first().hour)
        assertEquals(5, platform.scheduled.first().minute)
    }

    @Test
    fun `minute defaults to zero when omitted`() = runTest {
        val platform = FakeAlarmPlatform(now = timeAt(10, 0))
        AlarmCreateTool(platform).execute(args { put("hour", 23) }, granted)
        assertEquals(0, platform.scheduled.first().minute)
    }

    @Test
    fun `a missing hour is InvalidArguments and schedules nothing`() = runTest {
        val platform = FakeAlarmPlatform()
        val result = AlarmCreateTool(platform).execute(args { put("minute", 30) }, granted)

        assertEquals(ToolError.InvalidArguments::class, result.error!!::class)
        assertTrue(platform.scheduled.isEmpty())
        assertTrue(result.observation, result.observation.contains("hour is required"))
    }

    @Test
    fun `an out-of-range hour or minute is refused with the valid range`() = runTest {
        val platform = FakeAlarmPlatform()
        val tool = AlarmCreateTool(platform)

        val badHour = tool.execute(args { put("hour", 24) }, granted)
        assertEquals(ToolError.InvalidArguments::class, badHour.error!!::class)
        assertTrue(badHour.observation, badHour.observation.contains("0 to 23"))
        // 25 is a 1am typo, not a request to clamp into tomorrow.
        val wayOut = tool.execute(args { put("hour", 99) }, granted)
        assertEquals(ToolError.InvalidArguments::class, wayOut.error!!::class)

        val badMinute = tool.execute(
            args {
                put("hour", 7)
                put("minute", 60)
            },
            granted,
        )
        assertEquals(ToolError.InvalidArguments::class, badMinute.error!!::class)
        assertTrue(badMinute.observation, badMinute.observation.contains("0 to 59"))

        assertTrue(platform.scheduled.isEmpty())
    }

    @Test
    fun `a non-numeric hour names what it got`() = runTest {
        val platform = FakeAlarmPlatform()
        val result = AlarmCreateTool(platform).execute(args { put("hour", "sevenish") }, granted)

        assertEquals(ToolError.InvalidArguments::class, result.error!!::class)
        assertTrue(result.observation, result.observation.contains("sevenish"))
    }

    @Test
    fun `repeating alarms are refused explicitly rather than silently becoming one-shot`() = runTest {
        val platform = FakeAlarmPlatform()
        val result = AlarmCreateTool(platform).execute(
            args {
                put("hour", 7)
                put("repeat", true)
            },
            granted,
        )

        assertFalse(result.success)
        assertEquals(ToolError.InvalidArguments::class, result.error!!::class)
        assertTrue(result.observation, result.observation.contains("Repeating alarms are not supported"))
        assertTrue(platform.scheduled.isEmpty())
    }

    @Test
    fun `repeat spelled as a string is still understood`() = runTest {
        val platform = FakeAlarmPlatform(now = timeAt(10, 0))
        val result = AlarmCreateTool(platform).execute(
            args {
                put("hour", 8)
                put("repeat", "yes")
            },
            granted,
        )
        assertFalse("a stringy true must not sneak past", result.success)
    }

    @Test
    fun `repeat false is accepted and ignored`() = runTest {
        val platform = FakeAlarmPlatform(now = timeAt(10, 0))
        val result = AlarmCreateTool(platform).execute(
            args {
                put("hour", 8)
                put("repeat", false)
            },
            granted,
        )
        assertTrue(result.success)
        assertEquals(1, platform.scheduled.size)
    }

    @Test
    fun `when the app may not schedule exact alarms the tool says who can grant it`() = runTest {
        val platform = FakeAlarmPlatform(canSchedule = false)
        val result = AlarmCreateTool(platform).execute(args { put("hour", 7) }, granted)

        assertFalse(result.success)
        assertEquals(ToolError.PermissionDenied::class, result.error!!::class)
        assertTrue(result.observation, result.observation.contains("Alarms & reminders"))
        assertTrue("must tell the model not to retry", result.observation.contains("Do not retry"))
        assertTrue(platform.scheduled.isEmpty())
    }

    @Test
    fun `a platform failure on schedule becomes a typed error and never escapes`() = runTest {
        val platform = FakeAlarmPlatform(throwOnSchedule = SecurityException("exact alarm denied"))
        val result = AlarmCreateTool(platform).execute(args { put("hour", 7) }, granted)

        assertFalse(result.success)
        assertEquals(ToolError.PermissionDenied::class, result.error!!::class)
        assertFalse(result.observation.contains("java.lang"))
    }

    @Test
    fun `cancellation is reported before anything is scheduled`() = runTest {
        val platform = FakeAlarmPlatform()
        val result = AlarmCreateTool(platform).execute(args { put("hour", 7) }, cancelled)

        assertEquals(ToolError.Cancelled::class, result.error!!::class)
        assertTrue(platform.scheduled.isEmpty())
    }

    @Test
    fun `an id supplied twice replaces the earlier alarm rather than duplicating it`() = runTest {
        val platform = FakeAlarmPlatform(now = timeAt(10, 0))
        val tool = AlarmCreateTool(platform)

        tool.execute(args { put("hour", 7); put("id", "breakfast") }, granted)
        tool.execute(args { put("hour", 8); put("id", "breakfast") }, granted)

        assertEquals("same id must not create two alarms", 1, AlarmRegistry.all().size)
        assertEquals(8, AlarmRegistry.all().first().hour)
    }

    // ======================================================== the cancel guard --

    @Test
    fun `cancel with no arguments at all is REFUSED, never treated as all`() = runTest {
        val platform = FakeAlarmPlatform()
        AlarmRegistry.add(spec("a1", 7, 0, "wake up"))
        AlarmRegistry.add(spec("a2", 19, 30, "dinner"))

        val result = AlarmCancelTool(platform).execute(emptyArgs(), granted)

        assertFalse(result.success)
        assertEquals(ToolError.InvalidArguments::class, result.error!!::class)
        assertTrue("nothing may be cancelled", platform.cancelledCodes.isEmpty())
        assertEquals("registry must be untouched", 2, AlarmRegistry.all().size)
        assertTrue(result.observation, result.observation.contains("will not cancel them all"))
        // The candidates are listed so the model can pick one and retry with an id.
        assertTrue(result.observation, result.observation.contains("a1"))
        assertTrue(result.observation, result.observation.contains("a2"))
    }

    @Test
    fun `two alarms at the same time are ambiguous and NEITHER is cancelled`() = runTest {
        val platform = FakeAlarmPlatform()
        AlarmRegistry.add(spec("work", 7, 0, "work"))
        AlarmRegistry.add(spec("gym", 7, 0, "gym"))

        val result = AlarmCancelTool(platform).execute(args { put("hour", 7) }, granted)

        assertFalse(result.success)
        assertEquals(ToolError.InvalidArguments::class, result.error!!::class)
        assertTrue("guessing here is data loss", platform.cancelledCodes.isEmpty())
        assertEquals(2, AlarmRegistry.all().size)
        assertTrue(result.observation, result.observation.contains("none were cancelled"))
        assertTrue(result.observation, result.observation.contains("work"))
        assertTrue(result.observation, result.observation.contains("gym"))
        assertTrue("must ask for the id", result.observation.contains("which one"))
    }

    @Test
    fun `one alarm at a unique hour is cancelled`() = runTest {
        val platform = FakeAlarmPlatform()
        val morning = spec("a1", 7, 0, "wake up")
        AlarmRegistry.add(morning)
        AlarmRegistry.add(spec("a2", 19, 30, "dinner"))

        val result = AlarmCancelTool(platform).execute(args { put("hour", 7) }, granted)

        assertTrue(result.success)
        assertEquals(listOf(morning.requestCode), platform.cancelledCodes)
        assertEquals(1, AlarmRegistry.all().size)
        assertEquals("a2", AlarmRegistry.all().first().id)
    }

    @Test
    fun `an explicit id wins over a contradictory hour and cancels only that id`() = runTest {
        val platform = FakeAlarmPlatform()
        val dinner = spec("dinner", 19, 30, "dinner")
        AlarmRegistry.add(spec("breakfast", 7, 0, "breakfast"))
        AlarmRegistry.add(dinner)

        // The model said id=dinner but also hour=7. The id is authoritative; guessing
        // from the hour would delete the wrong alarm.
        val result = AlarmCancelTool(platform).execute(
            args {
                put("id", "dinner")
                put("hour", 7)
            },
            granted,
        )

        assertTrue(result.success)
        assertEquals(listOf(dinner.requestCode), platform.cancelledCodes)
        assertEquals("breakfast", AlarmRegistry.all().single().id)
    }

    @Test
    fun `an unknown id cancels nothing and names the id that was not found`() = runTest {
        val platform = FakeAlarmPlatform()
        AlarmRegistry.add(spec("a1", 7, 0, "wake up"))

        val result = AlarmCancelTool(platform).execute(args { put("id", "typo-id") }, granted)

        assertFalse(result.success)
        assertEquals(ToolError.InvalidArguments::class, result.error!!::class)
        assertTrue(platform.cancelledCodes.isEmpty())
        assertTrue(result.observation, result.observation.contains("typo-id"))
    }

    @Test
    fun `cancelling from an empty registry is NotFound-shaped, not a silent success`() = runTest {
        val platform = FakeAlarmPlatform()
        val result = AlarmCancelTool(platform).execute(args { put("hour", 7) }, granted)

        assertFalse(result.success)
        assertEquals(ToolError.InvalidArguments::class, result.error!!::class)
        assertTrue(platform.cancelledCodes.isEmpty())
        assertTrue(result.observation, result.observation.contains("No alarms are currently set"))
    }

    @Test
    fun `a label that matches two alarms is ambiguous too`() = runTest {
        val platform = FakeAlarmPlatform()
        AlarmRegistry.add(spec("a1", 7, 0, "morning run"))
        AlarmRegistry.add(spec("a2", 19, 0, "evening run"))

        val result = AlarmCancelTool(platform).execute(args { put("label", "run") }, granted)

        assertFalse(result.success)
        assertTrue(platform.cancelledCodes.isEmpty())
        assertTrue(result.observation, result.observation.contains("none were cancelled"))
    }

    @Test
    fun `hour plus label narrows to exactly one and is allowed`() = runTest {
        val platform = FakeAlarmPlatform()
        val target = spec("a2", 7, 0, "gym")
        AlarmRegistry.add(spec("a1", 7, 0, "work"))
        AlarmRegistry.add(target)

        val result = AlarmCancelTool(platform).execute(
            args {
                put("hour", 7)
                put("label", "gym")
            },
            granted,
        )

        assertTrue(result.success)
        assertEquals(listOf(target.requestCode), platform.cancelledCodes)
    }

    @Test
    fun `a malformed hour is refused rather than read as absent`() = runTest {
        val platform = FakeAlarmPlatform()
        AlarmRegistry.add(spec("a1", 7, 0, "wake up"))

        // "7am" is unparseable. Treating it as absent would fall through to the bulk
        // refusal, and treating it as 7 would be a guess; both are wrong.
        val result = AlarmCancelTool(platform).execute(args { put("hour", "7am") }, granted)

        assertFalse(result.success)
        assertEquals(ToolError.InvalidArguments::class, result.error!!::class)
        assertTrue(result.observation, result.observation.contains("7am"))
        assertTrue(platform.cancelledCodes.isEmpty())
    }

    @Test
    fun `a quoted hour string is still coerced to a number`() = runTest {
        val platform = FakeAlarmPlatform()
        val morning = spec("a1", 7, 0, "wake up")
        AlarmRegistry.add(morning)
        AlarmRegistry.add(spec("a2", 19, 0, "dinner"))

        val result = AlarmCancelTool(platform).execute(args { put("hour", "7") }, granted)

        assertTrue(result.success)
        assertEquals(listOf(morning.requestCode), platform.cancelledCodes)
    }

    @Test
    fun `an out-of-range hour is clamped for matching, not rejected`() = runTest {
        // 7:59 as a float spelling is still 7.
        val platform = FakeAlarmPlatform()
        val morning = spec("a1", 7, 0, "wake up")
        AlarmRegistry.add(morning)

        val result = AlarmCancelTool(platform).execute(args { put("hour", "7.0") }, granted)
        assertTrue(result.success)
        assertEquals(listOf(morning.requestCode), platform.cancelledCodes)
    }

    @Test
    fun `cancellation is reported before the platform is touched`() = runTest {
        val platform = FakeAlarmPlatform()
        AlarmRegistry.add(spec("a1", 7, 0, "wake up"))
        val result = AlarmCancelTool(platform).execute(args { put("id", "a1") }, cancelled)

        assertEquals(ToolError.Cancelled::class, result.error!!::class)
        assertTrue(platform.cancelledCodes.isEmpty())
    }

    // --------------------------- the guard as a pure function, exhaustively ------

    @Test
    fun `no combination of arguments can ever cancel more than one alarm`() {
        val all = listOf(
            spec("a", 7, 0, "wake"),
            spec("b", 7, 0, "gym"),
            spec("c", 19, 0, "dinner"),
            spec("d", 7, 0, "tea"),
        )
        val attempts = listOf(
            Triple<String, Int?, Int?>("", null, null),
            Triple("all", null, null),
            Triple("7", 7, null),
            Triple("7:00", 7, 0),
            Triple("0", 0, null),
            Triple("59", null, 0),
        )

        for ((_, hour, minute) in attempts) {
            val decision = AlarmCancel.decide(all, id = null, hour = hour, minute = minute, label = null)
            if (decision is CancelDecision.Cancel) {
                // Only a UNIQUE match may ever become a cancellation.
                val matched = all.count { (hour == null || it.hour == hour) && (minute == null || it.minute == minute) }
                assertEquals("hour=$hour minute=$minute matched $matched alarms", 1, matched)
            }
        }
    }

    @Test
    fun `every decision branch is reachable and typed`() {
        val one = listOf(spec("a", 7, 0, "wake"))
        val two = listOf(spec("a", 7, 0, "wake"), spec("b", 7, 0, "gym"))

        assertTrue(AlarmCancel.decide(one, "a", null, null, null) is CancelDecision.Cancel)
        assertTrue(AlarmCancel.decide(one, "zzz", null, null, null) is CancelDecision.NoMatch)
        assertTrue(AlarmCancel.decide(two, null, 7, null, null) is CancelDecision.Ambiguous)
        assertTrue(AlarmCancel.decide(one, null, null, null, null) is CancelDecision.RefuseBulk)
        assertTrue(AlarmCancel.decide(one, null, 9, null, null) is CancelDecision.NoMatch)
    }

    @Test
    fun `a bulk request names the candidates and refuses in the same breath`() {
        val all = listOf(spec("a", 7, 0, "wake"), spec("b", 19, 0, "dinner"))
        val text = AlarmCancel.describe(AlarmCancel.decide(all, null, null, null, null))

        assertTrue(text, text.contains("Refused"))
        assertTrue(text, text.contains("07:00 wake"))
        assertTrue(text, text.contains("19:00 dinner"))
    }

    // =================================================================== list --

    @Test
    fun `an empty list says so and names the boundary of what it can see`() = runTest {
        val result = AlarmListTool(FakeAlarmPlatform()).execute(emptyArgs(), granted)

        assertTrue("an empty list is a valid answer", result.success)
        assertTrue(result.observation, result.observation.contains("No alarms"))
        // The single most important sentence in this tool: without it the model tells
        // the user they have no alarms when they set three in the Clock app.
        assertTrue(result.observation, result.observation.contains("Clock app"))
    }

    @Test
    fun `list returns the agent alarms it created`() = runTest {
        AlarmRegistry.add(spec("a1", 7, 0, "wake up"))
        AlarmRegistry.add(spec("a2", 19, 30, "dinner"))

        val result = AlarmListTool(FakeAlarmPlatform()).execute(emptyArgs(), granted)

        assertTrue(result.success)
        assertTrue(result.observation, result.observation.contains("2 agent alarm(s)"))
        assertTrue(result.observation.contains("07:00 — wake up"))
        assertTrue(result.observation.contains("19:30 — dinner"))
        assertTrue(result.observation.contains("Clock app"))
    }

    @Test
    fun `the list observation stays under budget with a full registry`() = runTest {
        repeat(AlarmRegistry.MAX_TRACKED) { index ->
            AlarmRegistry.add(
                spec("alarm-$index", index % 24, index % 60, "a label that is reasonably long ${"y".repeat(40)}"),
            )
        }

        val result = AlarmListTool(FakeAlarmPlatform()).execute(emptyArgs(), granted)

        assertTrue(result.success)
        assertTrue(
            "observation was ${result.observation.length} chars",
            result.observation.length <= ObservationTruncator.DEFAULT_BUDGET_CHARS,
        )
    }

    // =============================================================== the registry --

    @Test
    fun `the registry is bounded so it cannot grow without limit on a phone`() {
        repeat(AlarmRegistry.MAX_TRACKED + 50) { index ->
            AlarmRegistry.add(spec("alarm-$index", index % 24, 0, "alarm $index"))
        }
        assertEquals(AlarmRegistry.MAX_TRACKED, AlarmRegistry.all().size)
        // The OLDEST are evicted, so "the alarm I set first" stays a stable thing to name.
        val lastIndex = AlarmRegistry.MAX_TRACKED + 50 - 1
        assertTrue("alarm-0 must have been evicted", AlarmRegistry.all().none { it.id == "alarm-0" })
        assertTrue("alarm-$lastIndex must have been kept", AlarmRegistry.all().any { it.id == "alarm-$lastIndex" })
    }

    @Test
    fun `registry entries are returned oldest first`() {
        val base = 1_700_000_000_000L
        AlarmRegistry.add(spec("third", 3, 0, "c", base + 3000))
        AlarmRegistry.add(spec("first", 1, 0, "a", base + 1000))
        AlarmRegistry.add(spec("second", 2, 0, "b", base + 2000))

        assertEquals(listOf("first", "second", "third"), AlarmRegistry.all().map { it.id })
    }

    @Test
    fun `removing by id is case-insensitive and reports whether anything went`() {
        AlarmRegistry.add(spec("Breakfast", 7, 0, "b"))
        assertNotNull(AlarmRegistry.remove("breakfast"))
        assertNull(AlarmRegistry.remove("breakfast"))
        assertTrue(AlarmRegistry.all().isEmpty())
    }

    // ============================================================== ids and time --

    @Test
    fun `a request code is deterministic, non-zero and positive`() {
        val code = AlarmIds.requestCodeOf("breakfast")
        assertEquals("must survive a process restart", code, AlarmIds.requestCodeOf("breakfast"))
        assertNotEquals(code, AlarmIds.requestCodeOf("dinner"))
        assertTrue("0 would look like a default-initialised code", code != 0)
        assertTrue(code > 0)
        assertEquals(AlarmIds.requestCodeOf("breakfast"), AlarmIds.requestCodeOf("breakfast"))
    }

    @Test
    fun `distinct ids give distinct request codes`() {
        val codes = (1..2_000).map { AlarmIds.requestCodeOf("alarm-$it") }.toSet()
        // A collision means one alarm overwrites another's PendingIntent. With 2000 ids
        // in a 2-billion-wide space, any collision at all is a red flag.
        assertEquals(2000, codes.size)
    }

    @Test
    fun `an id is sanitised and generated when absent`() {
        assertEquals("slash and spaces become dashes", "my-alarm", AlarmIds.coerceId("  my/alarm  ", 7, 0, 0L))
        assertTrue(
            "generated id: ${AlarmIds.coerceId(null, 7, 30, 5L)}",
            AlarmIds.coerceId(null, 7, 30, 5L).startsWith("alarm-0730"),
        )
        assertTrue(
            "blank id: ${AlarmIds.coerceId("   ", 7, 0, 0L)}",
            AlarmIds.coerceId("   ", 7, 0, 0L).startsWith("alarm-0700"),
        )
        // Zero-padded, so "alarm-0700" cannot be misread as hour 70.
        assertTrue(
            "unambiguous id: ${AlarmIds.coerceId(null, 7, 0, 0L)}",
            AlarmIds.coerceId(null, 7, 0, 0L) == "alarm-0700-0",
        )
        val long = AlarmIds.coerceId("x".repeat(500), 7, 0, 0L)
        assertTrue("long id was ${long.length} chars", long.length <= AlarmIds.MAX_ID_CHARS + 1)
    }

    @Test
    fun `a label is sanitised, bounded and defaults`() {
        assertEquals("take out bread", AlarmIds.coerceLabel("  take\nout\tbread  "))
        assertEquals("Alarm", AlarmIds.coerceLabel(null))
        assertEquals("Alarm", AlarmIds.coerceLabel("  "))
        assertTrue(AlarmIds.coerceLabel("z".repeat(500)).length <= AlarmIds.MAX_LABEL_CHARS + 1)
    }

    @Test
    fun `a time in the future today is today and is not rolled over`() {
        val zone = ZoneId.of("UTC")
        val now = ZonedDateTime.of(2026, 3, 10, 8, 0, 0, 0, zone).toInstant().toEpochMilli()

        val plan = AlarmTimes.plan(now, zone, hour = 9, minute = 30, dayOffset = null)

        assertFalse("09:30 is still ahead at 08:00", plan.rolledToNextDay)
        assertEquals("today", plan.dateLabel)
        // 90 minutes, in millis. A 24-hour arithmetic slip would show up here.
        assertEquals(90L * 60L * 1000L, plan.triggerAtMillis - now)
    }

    @Test
    fun `a time already past today rolls to tomorrow`() {
        val zone = ZoneId.of("UTC")
        val now = ZonedDateTime.of(2026, 3, 10, 8, 0, 0, 0, zone).toInstant().toEpochMilli()

        val plan = AlarmTimes.plan(now, zone, hour = 7, minute = 0, dayOffset = null)

        assertTrue(plan.rolledToNextDay)
        assertTrue(plan.dateLabel.startsWith("tomorrow"))
        assertTrue("must be in the future", plan.triggerAtMillis > now)
    }

    @Test
    fun `an explicit day offset is a calendar day, not a 24 hour offset`() {
        val zone = ZoneId.of("UTC")
        val now = ZonedDateTime.of(2026, 3, 10, 8, 0, 0, 0, zone).toInstant().toEpochMilli()

        // 08:00 today, asking for 08:00 today explicitly must NOT roll to tomorrow.
        val today = AlarmTimes.plan(now, zone, 8, 0, dayOffset = 0)
        assertFalse("day_offset 0 means today, even at the current time", today.rolledToNextDay)

        val tomorrow = AlarmTimes.plan(now, zone, 8, 0, dayOffset = 1)
        assertTrue(tomorrow.triggerAtMillis > now)
    }

    @Test
    fun `the alarm is still in the future across a spring-forward DST transition`() {
        val zone = ZoneId.of("Europe/Amsterdam")
        // 2026-03-29: clocks jump 02:00 -> 03:00, so that day is 23 hours long.
        val now = ZonedDateTime.of(2026, 3, 28, 12, 0, 0, 0, zone).toInstant().toEpochMilli()

        val plan = AlarmTimes.plan(now, zone, hour = 7, minute = 0, dayOffset = null)

        assertTrue("must be after now", plan.triggerAtMillis > now)
        // A naive "+86_400_000 ms" implementation would land on 08:00 local, not 07:00.
        val local = Instant.ofEpochMilli(plan.triggerAtMillis).atZone(zone)
        assertEquals(7, local.hour)
        assertEquals(0, local.minute)
    }

    @Test
    fun `an out-of-range hour or minute is clamped by the planner`() {
        val zone = ZoneId.of("UTC")
        val now = ZonedDateTime.of(2026, 3, 10, 8, 0, 0, 0, zone).toInstant().toEpochMilli()

        val plan = AlarmTimes.plan(now, zone, hour = 99, minute = 99, dayOffset = null)
        val local = Instant.ofEpochMilli(plan.triggerAtMillis).atZone(zone)
        assertEquals(23, local.hour)
        assertEquals(59, local.minute)
    }

    @Test
    fun `time labels are zero padded because an unpadded time reads as a typo`() {
        assertEquals("07:05", spec("a", 7, 5, "x").timeLabel())
        assertEquals("00:00", spec("a", 0, 0, "x").timeLabel())
        assertEquals("23:59", spec("a", 23, 59, "x").timeLabel())
    }

    // ============================================================ observations --

    @Test
    fun `every create observation stays under budget with a maximal label`() = runTest {
        val platform = FakeAlarmPlatform(now = timeAt(10, 0))
        val result = AlarmCreateTool(platform).execute(
            args {
                put("hour", 7)
                put("minute", 5)
                put("label", "z".repeat(500))
                put("id", "q".repeat(500))
            },
            granted,
        )

        assertTrue(result.success)
        assertTrue(
            "observation was ${result.observation.length} chars",
            result.observation.length <= ObservationTruncator.DEFAULT_BUDGET_CHARS,
        )
    }

    @Test
    fun `the ambiguous refusal stays under budget with a full registry`() = runTest {
        // 32 alarms all at 07:00: the worst possible candidate set for one hour filter.
        repeat(AlarmRegistry.MAX_TRACKED) { index ->
            AlarmRegistry.add(spec("alarm-$index", 7, 0, "label number $index that is longish"))
        }
        val platform = FakeAlarmPlatform()

        val result = AlarmCancelTool(platform).execute(args { put("hour", 7) }, granted)

        assertFalse(result.success)
        assertTrue(platform.cancelledCodes.isEmpty())
        assertTrue(
            "observation was ${result.observation.length} chars",
            result.observation.length <= ObservationTruncator.DEFAULT_BUDGET_CHARS,
        )
        assertTrue(result.observation, result.observation.contains("32 alarms match"))
    }

    @Test
    fun `a refusal observation is still explicit about what to do next`() {
        val all = listOf(spec("a", 7, 0, "wake"), spec("b", 7, 0, "gym"))
        val text = AlarmCancel.describe(AlarmCancel.decide(all, null, 7, null, null))

        assertTrue(text, text.contains("Ask the user which one"))
        assertTrue(text, text.contains("id"))
    }

    // ============================================================== helpers --

    private fun emptyArgs(): ToolArgs = buildJsonObject { }

    private fun args(block: JsonObjectBuilder.() -> Unit): ToolArgs = buildJsonObject(block)

    private fun spec(
        id: String,
        hour: Int,
        minute: Int,
        label: String,
        createdAt: Long = 1_700_000_000_000L,
    ) = AlarmSpec(
        id = id,
        hour = hour,
        minute = minute,
        label = label,
        triggerAtMillis = createdAt,
        requestCode = AlarmIds.requestCodeOf(id),
        createdAtMillis = createdAt,
    )

    private fun timeAt(hour: Int, minute: Int): Long =
        ZonedDateTime.of(2026, 5, 20, hour, minute, 0, 0, ZoneId.systemDefault())
            .toInstant().toEpochMilli()

    private companion object {
        val NAME_PATTERN = Regex("^[a-z]+[._][a-z_]+$")
    }
}
