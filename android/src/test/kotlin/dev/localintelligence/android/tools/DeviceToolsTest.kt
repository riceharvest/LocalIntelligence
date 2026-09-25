package dev.localintelligence.android.tools

import dev.localintelligence.android.tools.device.ArgCoerce
import dev.localintelligence.android.tools.device.ArgResult
import dev.localintelligence.android.tools.device.BatteryConstants
import dev.localintelligence.android.tools.device.BatteryFacts
import dev.localintelligence.android.tools.device.BatterySnapshot
import dev.localintelligence.android.tools.device.ChargeState
import dev.localintelligence.android.tools.device.DeviceBatteryTool
import dev.localintelligence.android.tools.device.DeviceFacts
import dev.localintelligence.android.tools.device.DeviceInfoFacts
import dev.localintelligence.android.tools.device.DeviceInfoTool
import dev.localintelligence.android.tools.device.DeviceOpenSettingsTool
import dev.localintelligence.android.tools.device.DevicePlatform
import dev.localintelligence.android.tools.device.DeviceVibrateTool
import dev.localintelligence.android.tools.device.SettingsScreen
import dev.localintelligence.android.tools.device.SettingsScreenResolver
import dev.localintelligence.android.tools.device.VibrationLogic
import dev.localintelligence.android.tools.device.VibrationPlan
import dev.localintelligence.core.model.ToolArgs
import dev.localintelligence.core.tool.CancellationSignal
import dev.localintelligence.core.tool.ObservationTruncator
import dev.localintelligence.core.tool.ToolContext
import dev.localintelligence.core.tool.ToolError
import dev.localintelligence.core.tool.ToolRisk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * Lowercase, one dot, two segments. tool-contract.md says "verb.noun"; the noun may
 * itself be two words (device.open_settings), so an underscore is allowed there.
 */
private val NAME_PATTERN = Regex("^[a-z]+[._][a-z_]+$")

/**
 * JVM tests for the four device tools.
 *
 * There is no emulator here and Robolectric is not a dependency, so `android.*` is
 * stubbed and a method call on it throws. The tools are therefore built on a
 * [DevicePlatform] seam, and these tests drive `execute()` itself against a fake
 * platform — that is the only way to cover the permission, cancellation, coercion and
 * failure-mapping paths that actually matter, rather than just the string builders.
 *
 * The fake records every call, so "permission denied does not touch the platform" is an
 * assertion, not a claim.
 */
class DeviceToolsTest {

    // =============================================================== fake platform --

    private class FakeDevicePlatform(
        var battery: BatterySnapshot? = null,
        var facts: DeviceFacts = DeviceFacts(),
        var hasVibrator: Boolean = true,
        var dndActive: Boolean = false,
        var settingsScreenOpens: Boolean = true,
        var throwOnVibrate: Exception? = null,
    ) : DevicePlatform {
        var calls: MutableList<String> = mutableListOf()

        override fun batterySnapshot(): BatterySnapshot? {
            calls += "batterySnapshot"
            return battery
        }

        override fun deviceFacts(): DeviceFacts {
            calls += "deviceFacts"
            return facts
        }

        override fun hasVibrator(): Boolean {
            calls += "hasVibrator"
            return hasVibrator
        }

        override fun isDoNotDisturbActive(): Boolean {
            calls += "isDoNotDisturbActive"
            return dndActive
        }

        override fun vibrate(durationMs: Int) {
            calls += "vibrate:$durationMs"
            throwOnVibrate?.let { throw it }
        }

        override fun openSettingsScreen(screen: SettingsScreen): Boolean {
            calls += "openSettings:$screen"
            return settingsScreenOpens
        }
    }

    private val denied = ToolContext(permissionGranted = false)
    private val granted = ToolContext(permissionGranted = true)
    private val cancelled = ToolContext(permissionGranted = true, signal = CancellationSignal { true })

    // ============================================================== risk policy --

    @Test
    fun `risk declared on every device tool matches the contract policy`() {
        val platform = FakeDevicePlatform()
        val risks = mapOf(
            "device.battery" to DeviceBatteryTool(platform).definition.risk,
            "device.info" to DeviceInfoTool(platform).definition.risk,
            "device.vibrate" to DeviceVibrateTool(platform).definition.risk,
            "device.open_settings" to DeviceOpenSettingsTool(platform).definition.risk,
        )

        // Both readers are READ_ONLY. Both writers are REVERSIBLE: vibrate is undone by
        // the buzz ending, opening a settings screen is undone by pressing back. Neither
        // deletes anything, so neither is DESTRUCTIVE.
        assertEquals(ToolRisk.READ_ONLY, risks["device.battery"])
        assertEquals(ToolRisk.READ_ONLY, risks["device.info"])
        assertEquals(ToolRisk.REVERSIBLE, risks["device.vibrate"])
        assertEquals(ToolRisk.REVERSIBLE, risks["device.open_settings"])

        // A derived property, not a declared one: a REVERSIBLE tool must never prompt.
        for ((name, risk) in risks) {
            assertFalse("$name must not require confirmation", risk.requiresConfirmation)
        }
    }

    @Test
    fun `every device definition satisfies the tool contract checklist`() {
        val platform = FakeDevicePlatform()
        val definitions = listOf(
            DeviceBatteryTool(platform).definition,
            DeviceInfoTool(platform).definition,
            DeviceVibrateTool(platform).definition,
            DeviceOpenSettingsTool(platform).definition,
        )

        for (d in definitions) {
            assertTrue("${d.name} must be dotted verb.noun", d.name.matches(NAME_PATTERN))
            assertTrue("${d.name} must use exactly one dot", d.name.count { it == '.' } == 1)
            assertEquals("category must match the package", "device", d.category)
            assertEquals("schema must be an object schema", "object", d.schema["type"]?.toString()?.trim('"'))
            assertNotNull("${d.name} needs properties", d.schema["properties"] as? JsonObject)
            assertNotNull("${d.name} needs required", d.schema["required"])
            assertTrue("${d.name} description must be one sentence", d.description.count { it == '.' } == 1)
            assertTrue(
                "${d.name} needs 4-8 retrieval tags, had ${d.tags.size}",
                d.tags.size in 4..8,
            )
            assertTrue("${d.name} tags must be lowercase", d.tags.all { it == it.lowercase() })
        }
    }

    // ============================================================ permission gate --

    @Test
    fun `battery returns PermissionDenied without touching the platform`() = runTest {
        val platform = FakeDevicePlatform(battery = BatterySnapshot(levelPercent = 50))
        val result = DeviceBatteryTool(platform).execute(emptyArgs(), denied)

        assertFalse(result.success)
        assertEquals(ToolError.PermissionDenied::class, result.error!!::class)
        assertTrue("platform must not be touched", platform.calls.isEmpty())
        assertTrue(result.observation.contains("No permission"))
        // The observation must tell the model to stop, not to retry.
        assertTrue(result.observation.contains("do not retry"))
    }

    @Test
    fun `info returns PermissionDenied without touching the platform`() = runTest {
        val platform = FakeDevicePlatform()
        val result = DeviceInfoTool(platform).execute(emptyArgs(), denied)

        assertEquals(ToolError.PermissionDenied::class, result.error!!::class)
        assertTrue(platform.calls.isEmpty())
    }

    @Test
    fun `vibrate returns PermissionDenied without touching the platform`() = runTest {
        val platform = FakeDevicePlatform()
        val result = DeviceVibrateTool(platform).execute(args { put("duration_ms", 200) }, denied)

        assertEquals(ToolError.PermissionDenied::class, result.error!!::class)
        assertTrue(platform.calls.isEmpty())
    }

    @Test
    fun `open_settings returns PermissionDenied without touching the platform`() = runTest {
        val platform = FakeDevicePlatform()
        val result = DeviceOpenSettingsTool(platform).execute(args { put("screen", "wifi") }, denied)

        assertEquals(ToolError.PermissionDenied::class, result.error!!::class)
        assertTrue(platform.calls.isEmpty())
    }

    // ================================================================== battery --

    @Test
    fun `battery renders a discharging phone as one plain sentence`() = runTest {
        val platform = FakeDevicePlatform(
            battery = BatterySnapshot(
                levelPercent = 43,
                status = BatteryConstants.STATUS_DISCHARGING,
                timeToFullSeconds = -1,
            ),
        )

        val result = DeviceBatteryTool(platform).execute(emptyArgs(), granted)

        assertTrue(result.success)
        assertTrue("got: ${result.observation}", result.observation.startsWith("Battery 43%"))
        assertTrue(result.observation.contains("Running on battery"))
        // No fabricated time-to-empty: the platform does not provide one.
        assertTrue(result.observation, result.observation.contains("does not report a time-to-empty"))
        assertFalse("no JSON in an observation", result.observation.contains("{"))
    }

    @Test
    fun `battery reports charging with a time to full instead of a time to empty`() = runTest {
        val snapshot = BatterySnapshot(
            levelPercent = 60,
            status = BatteryConstants.STATUS_CHARGING,
            plugged = BatteryConstants.PLUGGED_USB,
            timeToFullSeconds = 5_400,
        )
        val result = DeviceBatteryTool(FakeDevicePlatform(battery = snapshot)).execute(emptyArgs(), granted)

        assertTrue(result.success)
        assertTrue(result.observation.contains("Charging on the USB"))
        assertTrue(result.observation.contains("1h 30m until full"))
    }

    @Test
    fun `an unknown battery level is stated as unknown and never rendered as zero`() {
        val snapshot = BatterySnapshot(
            levelPercent = -1,
            status = BatteryConstants.STATUS_UNKNOWN,
            plugged = BatteryConstants.PLUGGED_NONE,
        )
        val text = BatteryFacts.describe(snapshot)

        assertTrue(text, text.contains("Battery level not reported"))
        assertFalse("never claim 0% for an absent reading", text.contains("Battery 0%"))
        assertFalse(text.contains("0m"))
    }

    @Test
    fun `a device with no battery at all is Unavailable with an explanation`() = runTest {
        val result = DeviceBatteryTool(FakeDevicePlatform(battery = null)).execute(emptyArgs(), granted)

        assertFalse(result.success)
        assertEquals(ToolError.Unavailable::class, result.error!!::class)
        assertTrue(result.observation.contains("no battery"))
    }

    @Test
    fun `a platform exception becomes a typed error and never escapes execute`() = runTest {
        val platform = FakeDevicePlatform(throwOnVibrate = SecurityException("VIBRATE revoked"))
        val result = DeviceVibrateTool(platform).execute(args { put("duration_ms", 100) }, granted)

        assertFalse(result.success)
        assertEquals(ToolError.PermissionDenied::class, result.error!!::class)
        // The observation must not be an exception toString().
        assertFalse(result.observation.contains("java.lang.SecurityException"))
        assertTrue(result.observation.contains("SecurityException"))
    }

    @Test
    fun `an unexpected platform exception is Internal and carries no stack trace`() = runTest {
        val platform = FakeDevicePlatform(throwOnVibrate = IllegalArgumentException("boom at Frame.kt:12"))
        val result = DeviceVibrateTool(platform).execute(args { put("duration_ms", 100) }, granted)

        assertEquals(ToolError.Internal::class, result.error!!::class)
        assertFalse(result.observation.contains("Frame.kt"))
        assertFalse(result.observation.contains("at dev."))
    }

    @Test
    fun `cancellation is reported as Cancelled before the platform is called`() = runTest {
        val platform = FakeDevicePlatform(battery = BatterySnapshot(levelPercent = 10))
        val result = DeviceBatteryTool(platform).execute(emptyArgs(), cancelled)

        assertFalse(result.success)
        assertEquals(ToolError.Cancelled::class, result.error!!::class)
        assertTrue(platform.calls.isEmpty())
    }

    @Test
    fun `every battery charge-state mapping is pinned to the platform constant it mirrors`() {
        assertEquals(ChargeState.CHARGING, BatteryFacts.chargeStateOf(BatteryConstants.STATUS_CHARGING, 0))
        assertEquals(ChargeState.FULL, BatteryFacts.chargeStateOf(BatteryConstants.STATUS_FULL, 4))
        assertEquals(ChargeState.ON_BATTERY, BatteryFacts.chargeStateOf(BatteryConstants.STATUS_DISCHARGING, 0))
        assertEquals(ChargeState.NOT_CHARGING, BatteryFacts.chargeStateOf(BatteryConstants.STATUS_NOT_CHARGING, 0))
        // A phone that reports NOT_CHARGING while plugged in IS charging the battery,
        // it just is not moving charge right now. Treating it as unplugged would tell
        // the user to plug in a charger they already have.
        assertEquals(ChargeState.CHARGING, BatteryFacts.chargeStateOf(BatteryConstants.STATUS_NOT_CHARGING, 1))
        assertEquals(ChargeState.UNPLUGGED, BatteryFacts.chargeStateOf(BatteryConstants.STATUS_UNKNOWN, 0))
        assertEquals(ChargeState.CHARGING, BatteryFacts.chargeStateOf(BatteryConstants.STATUS_UNKNOWN, 2))
        // An out-of-contract status from an OEM must not crash or claim anything.
        assertEquals(ChargeState.UNKNOWN, BatteryFacts.chargeStateOf(99, 0))
    }

    @Test
    fun `an unhealthy battery is surfaced instead of being silently dropped`() {
        val snapshot = BatterySnapshot(
            levelPercent = 30,
            status = BatteryConstants.STATUS_DISCHARGING,
            health = BatteryConstants.HEALTH_DEAD,
            temperatureCelsius = 41f,
        )
        val text = BatteryFacts.describe(snapshot)

        assertTrue(text, text.contains("Battery health: dead"))
        assertTrue(text, text.contains("41°C"))
    }

    @Test
    fun `duration formatting uses the three shapes a human would use`() {
        assertEquals("under a minute", BatteryFacts.formatDuration(30))
        assertEquals("45m", BatteryFacts.formatDuration(45 * 60))
        assertEquals("3h 12m", BatteryFacts.formatDuration(3 * 3600 + 12 * 60))
        assertEquals("2h", BatteryFacts.formatDuration(2 * 3600))
    }

    // =================================================================== device --

    @Test
    fun `info reports model, OS, screen, RAM and storage`() = runTest {
        val facts = DeviceFacts(
            manufacturer = "Google",
            model = "Pixel 8 Pro",
            androidRelease = "15",
            sdkInt = 35,
            widthPixels = 1344,
            heightPixels = 2992,
            densityDpi = 560,
            totalRamBytes = 8L * 1024 * 1024 * 1024,
            availableRamBytes = 3L * 1024 * 1024 * 1024,
            totalStorageBytes = 128L * 1024 * 1024 * 1024,
            availableStorageBytes = 92L * 1024 * 1024 * 1024,
        )

        val result = DeviceInfoTool(FakeDevicePlatform(facts = facts)).execute(emptyArgs(), granted)

        assertTrue(result.success)
        val text = result.observation
        assertTrue(text, text.contains("Google Pixel 8 Pro"))
        assertTrue(text, text.contains("Android 15 (API 35)"))
        assertTrue(text, text.contains("1344x2992px"))
        assertTrue(text, text.contains("8.0 GB RAM"))
        assertTrue(text, text.contains("92.0 GB free of 128.0 GB storage"))
    }

    @Test
    fun `a vendor already in the model name is not repeated`() {
        val facts = DeviceFacts(manufacturer = "samsung", model = "SM-S918B")
        // "samsung" + "SM-S918B" is fine, but "Google Pixel 8" + "Google" is not.
        assertEquals("samsung SM-S918B", DeviceInfoFacts.deviceName(facts))
        assertEquals(
            "Google Pixel 8 Pro",
            DeviceInfoFacts.deviceName(DeviceFacts(manufacturer = "Google", model = "Google Pixel 8 Pro")),
        )
    }

    @Test
    fun `missing facts are omitted rather than rendered as zero`() {
        val text = DeviceInfoFacts.describe(DeviceFacts(model = "Mystery Phone"))

        assertTrue(text, text.contains("Mystery Phone"))
        assertFalse("no screen line without dimensions", text.contains("0x0"))
        assertFalse(text.contains("-1"))
        assertFalse(text.contains("GB RAM"))
    }

    @Test
    fun `a hostile model string cannot blow up the observation`() = runTest {
        // Build.MODEL is device-controlled and reaches the model verbatim. 40 000 chars
        // of it, plus control characters and a fake schema, must still produce a short
        // plain-text observation.
        val hostile = "x".repeat(40_000) + "\n\u0000\u0007{\"type\":\"object\"}"
        val facts = DeviceFacts(
            manufacturer = hostile,
            model = hostile,
            androidRelease = hostile,
            sdkInt = 34,
            widthPixels = 1080,
            heightPixels = 2400,
            densityDpi = 400,
            totalRamBytes = 4L * 1024 * 1024 * 1024,
            availableRamBytes = 1024L * 1024 * 1024,
        )

        val result = DeviceInfoTool(FakeDevicePlatform(facts = facts)).execute(emptyArgs(), granted)

        assertTrue(result.success)
        assertTrue(
            "observation was ${result.observation.length} chars",
            result.observation.length <= ObservationTruncator.DEFAULT_BUDGET_CHARS,
        )
        assertTrue("must not echo a JSON blob", !result.observation.contains("\"type\""))
        assertTrue("must be one line", !result.observation.contains("\n"))
        assertTrue(result.observation.endsWith("."))
    }

    @Test
    fun `byte formatting never reports a negative or nonsense size`() {
        assertNull(DeviceInfoFacts.formatBytes(-1))
        assertEquals("0 KB", DeviceInfoFacts.formatBytes(0))
        assertEquals("512 KB", DeviceInfoFacts.formatBytes(512L * 1024))
        assertEquals("1.0 GB", DeviceInfoFacts.formatBytes(1024L * 1024 * 1024))
    }

    // ================================================================ vibrate --

    @Test
    fun `vibrate defaults to 300 ms when no duration is given`() = runTest {
        val platform = FakeDevicePlatform()
        val result = DeviceVibrateTool(platform).execute(emptyArgs(), granted)

        assertTrue(result.success)
        assertTrue(platform.calls.contains("vibrate:300"))
    }

    @Test
    fun `a quoted duration string is coerced rather than rejected`() = runTest {
        val platform = FakeDevicePlatform()
        // The single most common model bug: 800 arrives as "800".
        val result = DeviceVibrateTool(platform).execute(args { put("duration_ms", "800") }, granted)

        assertTrue("got: ${result.observation}", result.success)
        assertTrue(platform.calls.contains("vibrate:800"))
    }

    @Test
    fun `an out-of-range duration is clamped into the safe band, not obeyed`() = runTest {
        val platform = FakeDevicePlatform()
        val result = DeviceVibrateTool(platform).execute(args { put("duration_ms", 999_999) }, granted)

        assertTrue(result.success)
        assertTrue(platform.calls.contains("vibrate:5000"))
    }

    @Test
    fun `a negative duration is raised to the minimum instead of buzzing forever`() = runTest {
        val platform = FakeDevicePlatform()
        DeviceVibrateTool(platform).execute(args { put("duration_ms", -50) }, granted)

        assertTrue(platform.calls.contains("vibrate:10"))
    }

    @Test
    fun `a non-numeric duration is InvalidArguments and vibrates nothing`() = runTest {
        val platform = FakeDevicePlatform()
        val result = DeviceVibrateTool(platform).execute(args { put("duration_ms", "loud") }, granted)

        assertFalse(result.success)
        assertEquals(ToolError.InvalidArguments::class, result.error!!::class)
        assertTrue("must not vibrate", platform.calls.none { it.startsWith("vibrate") })
    }

    @Test
    fun `Do Not Disturb suppresses the buzz and is reported as a success`() = runTest {
        val platform = FakeDevicePlatform(dndActive = true)
        val result = DeviceVibrateTool(platform).execute(args { put("duration_ms", 200) }, granted)

        // success == true is deliberate: the request was answered correctly. Returning
        // false would make the agent loop retry something the system already refused.
        assertTrue(result.success)
        assertTrue(platform.calls.none { it.startsWith("vibrate") })
        assertTrue(result.observation, result.observation.contains("Do Not Disturb"))
        assertTrue(result.observation.contains("turn off Do Not Disturb"))
    }

    @Test
    fun `a device with no motor is Unavailable`() = runTest {
        val platform = FakeDevicePlatform(hasVibrator = false)
        val result = DeviceVibrateTool(platform).execute(args { put("duration_ms", 200) }, granted)

        assertFalse(result.success)
        assertEquals(ToolError.Unavailable::class, result.error!!::class)
        assertTrue(platform.calls.none { it.startsWith("vibrate") })
    }

    @Test
    fun `the vibrate plan is a pure function of motor, dnd and duration`() {
        assertEquals(VibrationPlan.NoMotor, VibrationLogic.plan(false, false, 300))
        assertEquals(
            VibrationPlan.SuppressedByDoNotDisturb,
            VibrationLogic.plan(true, true, 300),
        )
        assertEquals(VibrationPlan.Vibrate(300), VibrationLogic.plan(true, false, 300))
        // No motor wins over DND: "this phone cannot vibrate" is the more useful fact.
        assertEquals(VibrationPlan.NoMotor, VibrationLogic.plan(false, true, 300))

        assertEquals(300, VibrationLogic.coerceDurationMs(null))
        assertEquals(10, VibrationLogic.coerceDurationMs(0))
        assertEquals(10, VibrationLogic.coerceDurationMs(-9))
        assertEquals(5000, VibrationLogic.coerceDurationMs(Int.MAX_VALUE))
        assertEquals(1234, VibrationLogic.coerceDurationMs(1234))
    }

    // =========================================================== open settings --

    @Test
    fun `open_settings opens a resolved screen`() = runTest {
        val platform = FakeDevicePlatform()
        val result = DeviceOpenSettingsTool(platform).execute(args { put("screen", "wifi") }, granted)

        assertTrue(result.success)
        assertTrue(platform.calls.contains("openSettings:WIFI"))
        assertTrue(result.observation, result.observation.contains("Wi-Fi settings"))
    }

    @Test
    fun `the word a user would actually type resolves to the right screen`() {
        assertEquals(SettingsScreen.WIFI, SettingsScreenResolver.resolve("wi-fi"))
        assertEquals(SettingsScreen.WIFI, SettingsScreenResolver.resolve("internet"))
        assertEquals(SettingsScreen.WIFI, SettingsScreenResolver.resolve("  WIFI  "))
        assertEquals(SettingsScreen.BLUETOOTH, SettingsScreenResolver.resolve("bt"))
        assertEquals(SettingsScreen.DISPLAY, SettingsScreenResolver.resolve("brightness"))
        assertEquals(SettingsScreen.SOUND, SettingsScreenResolver.resolve("volume"))
        assertEquals(SettingsScreen.BATTERY_SAVER, SettingsScreenResolver.resolve("power saving"))
        // Separators fold together: the user types a space or a hyphen, not an underscore.
        assertEquals(SettingsScreen.BATTERY_SAVER, SettingsScreenResolver.resolve("power_saving"))
        assertEquals(SettingsScreen.SOUND, SettingsScreenResolver.resolve("sound and vibration"))
        assertEquals(SettingsScreen.WIFI, SettingsScreenResolver.resolve("Wi Fi"))
        assertNull(SettingsScreenResolver.resolve("nuclear reactor"))
        assertNull(SettingsScreenResolver.resolve(null))
        assertNull(SettingsScreenResolver.resolve(""))
    }

    @Test
    fun `an unknown screen is InvalidArguments and names the valid ones`() = runTest {
        val platform = FakeDevicePlatform()
        val result = DeviceOpenSettingsTool(platform).execute(args { put("screen", "nuclear") }, granted)

        assertFalse(result.success)
        assertEquals(ToolError.InvalidArguments::class, result.error!!::class)
        assertTrue(platform.calls.isEmpty())
        for (name in SettingsScreen.ARG_NAMES) {
            assertTrue("must list $name", result.observation.contains(name))
        }
    }

    @Test
    fun `a missing or wrongly-typed screen is rejected before any platform call`() = runTest {
        val platform = FakeDevicePlatform()
        val tool = DeviceOpenSettingsTool(platform)

        val missing = tool.execute(emptyArgs(), granted)
        assertEquals(ToolError.InvalidArguments::class, missing.error!!::class)
        assertTrue(missing.observation.contains("screen is required"))

        val wrongType = tool.execute(
            args { put("screen", buildJsonArray { }) },
            granted,
        )
        assertEquals(ToolError.InvalidArguments::class, wrongType.error!!::class)
        assertTrue(wrongType.observation, wrongType.observation.contains("must be a single word"))
        assertTrue(platform.calls.isEmpty())
    }

    @Test
    fun `a settings screen nothing can handle is Unavailable, not an internal error`() = runTest {
        val platform = FakeDevicePlatform(settingsScreenOpens = false)
        val result = DeviceOpenSettingsTool(platform).execute(args { put("screen", "bluetooth") }, granted)

        assertFalse(result.success)
        assertEquals(ToolError.Unavailable::class, result.error!!::class)
        assertTrue(result.observation, result.observation.contains("nothing was opened"))
    }

    // ============================================================== coercion --

    @Test
    fun `ArgCoerce reads every shape a model emits for an int`() {
        assertEquals(8, intIn(args { put("v", 8) }))
        assertEquals(8, intIn(args { put("v", "8") }))       // quoted number
        assertEquals(8, intIn(args { put("v", " 8 ") }))     // padded
        assertEquals(8, intIn(args { put("v", "8.0") }))     // float spelling of an int
        assertEquals(-3, intIn(args { put("v", "-3") }))
        assertEquals(0, intIn(args { put("v", 0) }))
    }

    @Test
    fun `ArgCoerce distinguishes absent from unusable, which is the whole point`() {
        assertTrue(ArgCoerce.int(emptyArgs(), "v") is ArgResult.Missing)
        assertTrue(ArgCoerce.int(args { put("v", JsonNull) }, "v") is ArgResult.Missing)

        val unusable = ArgCoerce.int(args { put("v", "eight") }, "v")
        assertTrue("expected Unusable, got $unusable", unusable is ArgResult.Unusable)
        // A malformed value must say what it got, in a form the model can learn from.
        assertTrue((unusable as ArgResult.Unusable).got.contains("eight"))
    }

    @Test
    fun `ArgCoerce never reflects a large payload back into the prompt`() {
        val huge = buildJsonObject { repeat(500) { put("k$it", "v") } }
        val unusable = ArgCoerce.int(args { put("v", huge) }, "v") as ArgResult.Unusable
        assertEquals("an object", unusable.got)

        val bigArray = JsonArray(List(1000) { JsonPrimitive(it) })
        assertEquals("an array of 1000 items", ArgCoerce.describeElement(bigArray))
    }

    @Test
    fun `an absurd int is clamped rather than wrapping around`() {
        val huge = intIn(args { put("v", JsonPrimitive("999999999999999")) })
        assertTrue("was $huge", huge <= ArgCoerce.MAX_ABS_INT)
        assertTrue("was $huge", huge >= -ArgCoerce.MAX_ABS_INT)
    }

    @Test
    fun `intIn clamps instead of rejecting, because a clamp is what the model meant`() {
        assertEquals(0, (ArgCoerce.intIn(args { put("v", -50) }, "v", 0, 100) as ArgResult.Present).value)
        assertEquals(100, (ArgCoerce.intIn(args { put("v", 500) }, "v", 0, 100) as ArgResult.Present).value)
    }

    @Test
    fun `boolean coercion accepts the five ways a model spells true and false`() {
        val truthy = listOf(
            JsonPrimitive(true), JsonPrimitive("true"), JsonPrimitive("TRUE"),
            JsonPrimitive(1), JsonPrimitive("yes"), JsonPrimitive("on"),
        )
        val falsy = listOf(
            JsonPrimitive(false), JsonPrimitive("false"), JsonPrimitive(0),
            JsonPrimitive("no"), JsonPrimitive("off"),
        )
        for (v in truthy) assertEquals("$v must read as true", true, ArgCoerce.booleanOrNull(v))
        for (v in falsy) assertEquals("$v must read as false", false, ArgCoerce.booleanOrNull(v))

        assertNull(
            "an undecidable value must stay null, not silently become false",
            ArgCoerce.booleanOrNull(JsonPrimitive("maybe")),
        )
        assertNull(ArgCoerce.booleanOrNull(JsonNull))
        assertNull(ArgCoerce.booleanOrNull(null))
    }

    @Test
    fun `string coercion accepts a number but rejects a structure`() {
        val asText = ArgCoerce.string(args { put("v", 42) }, "v")
        assertEquals("42", (asText as ArgResult.Present).value)
        assertTrue(ArgCoerce.string(args { put("v", buildJsonArray { }) }, "v") is ArgResult.Unusable)
    }

    @Test
    fun `ellipsize never exceeds its budget`() {
        assertEquals("abc", ArgCoerce.ellipsize("abc", 5))
        assertTrue(ArgCoerce.ellipsize("x".repeat(100), 10).length <= 11)
    }

    // ============================================================== helpers --

    private fun emptyArgs(): ToolArgs = buildJsonObject { }

    /** `args { put("hour", 7) }` — the same call a model would produce. */
    private fun args(block: JsonObjectBuilder.() -> Unit): ToolArgs = buildJsonObject(block)

    private fun intIn(a: ToolArgs): Int {
        val r = ArgCoerce.int(a, "v")
        assertTrue("expected a usable int, got $r", r is ArgResult.Present)
        return (r as ArgResult.Present).value
    }

}
