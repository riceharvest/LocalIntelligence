package dev.localintelligence.android.tools.device

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import dev.localintelligence.core.model.ToolArgs
import dev.localintelligence.core.tool.AgentTool
import dev.localintelligence.core.tool.ObservationTruncator
import dev.localintelligence.core.tool.ToolContext
import dev.localintelligence.core.tool.ToolDefinition
import dev.localintelligence.core.tool.ToolError
import dev.localintelligence.core.tool.ToolResult
import dev.localintelligence.core.tool.ToolRisk
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

// =====================================================================================
// ArgCoerce — the shared defensive-coercion kit for every tool in this package.
//
// It lives here because DeviceTools.kt is the first of the three tool files and all
// three need identical coercion of the malformed arguments a small model emits. It is
// PURE KOTLIN: no android.* import anywhere below, which is what makes it unit
// testable on the JVM (see the RoomStoreTest rationale — there is no device or
// Robolectric in this environment, so anything that inlines a platform call cannot be
// executed at all in CI).
//
// Models emit "8" for an Int, 8 for a String, null for a required argument, and 1 for a
// boolean. Every accessor below returns a three-state result so the caller can tell
// "absent" (use the default) from "present but unusable" (InvalidArguments). Collapsing
// those two into a single null is how a tool ends up silently doing the wrong thing.
// =====================================================================================

/** Outcome of reading one model-supplied argument. */
sealed class ArgResult<out T> {
    /** The argument was present and could be understood. */
    data class Present<T>(val value: T) : ArgResult<T>()

    /** The key was absent (or JSON null) — the caller applies its default. */
    data class Missing(val key: String) : ArgResult<Nothing>()

    /** The key was present but could not be read as the requested type. */
    data class Unusable(val key: String, val got: String) : ArgResult<Nothing>()
}

object ArgCoerce {

    /** Hard ceiling on any model-supplied integer. Beyond this, clamping is a lie. */
    const val MAX_ABS_INT = 1_000_000_000

    /**
     * Reads [key] as an Int.
     *
     * Accepts a JSON number, and — because models quote numbers constantly — a string
     * holding a number, including a float spelling like "8.0" which `toIntOrNull` alone
     * would reject. `Double.toInt` saturates rather than overflowing, and [clamp]
     * bounds what survives, so there is no path to a wrapped value.
     */
    fun int(args: ToolArgs, key: String): ArgResult<Int> {
        val raw = args[key] ?: return ArgResult.Missing(key)
        val primitive = raw as? JsonPrimitive
            ?: return ArgResult.Unusable(key, describeElement(raw))
        if (primitive is JsonNull) return ArgResult.Missing(key)
        val text = primitive.content.trim()
        if (text.isEmpty()) return ArgResult.Unusable(key, "an empty string")
        val parsed = if (primitive.isString) {
            text.toIntOrNull() ?: text.toDoubleOrNull()?.takeIf { it.isFinite() }?.toInt()
        } else {
            primitive.intOrNull
                ?: primitive.doubleOrNull?.takeIf { it.isFinite() }?.toInt()
        }
        return if (parsed == null) {
            ArgResult.Unusable(key, "the string \"${ellipsize(text, 24)}\"")
        } else {
            ArgResult.Present(clamp(parsed, -MAX_ABS_INT, MAX_ABS_INT))
        }
    }

    /** Reads [key] as an Int in [min]..[max], clamping rather than rejecting. */
    fun intIn(args: ToolArgs, key: String, min: Int, max: Int): ArgResult<Int> =
        when (val r = int(args, key)) {
            is ArgResult.Present -> ArgResult.Present(r.value.coerceIn(min, max))
            else -> r
        }

    /**
     * Reads [key] as a String.
     *
     * A JSON number is accepted and its literal text returned, because "the model sent
     * 42 for the label" should not lose the user's intent. Arrays and objects are not
     * strings under any reading and are rejected.
     */
    fun string(args: ToolArgs, key: String): ArgResult<String> {
        val raw = args[key] ?: return ArgResult.Missing(key)
        if (raw is JsonNull) return ArgResult.Missing(key)
        val primitive = raw as? JsonPrimitive
            ?: return ArgResult.Unusable(key, describeElement(raw))
        return ArgResult.Present(primitive.content)
    }

    /**
     * Reads [key] as a Boolean, accepting the many things a model calls a boolean:
     * `true`, `"true"`, `"yes"`, `1`, and — the common failure — the number 0 meaning
     * "no". Returns null when the value is genuinely undecidable, so a caller can fall
     * back to a default instead of guessing.
     */
    fun booleanOrNull(raw: JsonElement?): Boolean? {
        val primitive = raw as? JsonPrimitive ?: return null
        if (primitive is JsonNull) return null
        if (primitive.isString) {
            return when (primitive.content.trim().lowercase()) {
                "true", "yes", "1", "on" -> true
                "false", "no", "0", "off" -> false
                else -> null
            }
        }
        primitive.booleanOrNull?.let { return it }
        return primitive.intOrNull?.let { it != 0 }
    }

    fun clamp(value: Int, min: Int, max: Int): Int = value.coerceIn(min, max)

    /** Shortens [text] to [max] chars for echoing back into an observation. */
    fun ellipsize(text: String, max: Int): String =
        if (text.length <= max) text else text.take(max.coerceAtLeast(1)) + "…"

    /**
     * Names the *kind* of a JSON element rather than dumping it.
     *
     * A model that sends an 8 KB array for `text` must not get 8 KB of its own output
     * reflected back into the prompt, and an observation must never be a JSON dump.
     */
    fun describeElement(element: JsonElement): String = when (element) {
        is JsonNull -> "null"
        is JsonPrimitive -> if (element.isString) {
            val text = element.content
            if (text.length > 24) "a ${text.length}-character string" else "the string \"$text\""
        } else {
            "the number ${ellipsize(element.content, 16)}"
        }

        is JsonArray -> "an array of ${element.size} items"
        is JsonObject -> "an object"
    }
}

/** Wraps a tool body so no expected failure can ever escape `execute`. */
internal inline fun guarded(toolName: String, block: () -> ToolResult): ToolResult =
    try {
        block()
    } catch (e: SecurityException) {
        // A platform permission the manifest declares but the user revoked at runtime.
        ToolResult(
            success = false,
            observation = "Denied: Android refused this $toolName call (${e.javaClass.simpleName}). " +
                "The permission for it has probably been revoked; tell the user to re-grant it in " +
                "Settings, and do not retry.",
            error = ToolError.PermissionDenied("permission revoked for $toolName"),
        )
    } catch (e: IllegalStateException) {
        ToolResult(
            success = false,
            observation = "Unavailable: the system was not in a state to run $toolName. " +
                "Report this to the user rather than retrying.",
            error = ToolError.Unavailable("illegal state during $toolName"),
        )
    } catch (e: Exception) {
        // Never an exception toString(): the model must not see a stack trace. A short
        // reference is enough for a bug report and costs the context nothing.
        ToolResult(
            success = false,
            observation = "Internal error in $toolName (${e.javaClass.simpleName}). " +
                "This is a bug in the app, not something retrying will fix.",
            error = ToolError.Internal("$toolName failed: ${e.javaClass.simpleName}"),
        )
    }

// =====================================================================================
// Battery
// =====================================================================================

/** Charge state, resolved from the raw int constants so this layer stays android-free. */
enum class ChargeState {
    CHARGING,
    FULL,
    ON_BATTERY,
    NOT_CHARGING,
    UNPLUGGED,
    UNKNOWN,
}

/**
 * The integers are copies of `android.os.BatteryManager` BATTERY_STATUS_* and
 * BATTERY_PLUGGED_*. They have been stable public API since level 5 and are duplicated
 * here so the decision logic below can be executed by a JVM test. `BatteryFactsTest`
 * pins every mapping, so a drift on either side shows up as a failing test rather than
 * as a wrong sentence to the user.
 */
object BatteryConstants {
    const val STATUS_UNKNOWN = 1
    const val STATUS_CHARGING = 2
    const val STATUS_DISCHARGING = 3
    const val STATUS_NOT_CHARGING = 4
    const val STATUS_FULL = 5

    const val PLUGGED_NONE = 0
    const val PLUGGED_AC = 1
    const val PLUGGED_USB = 2
    const val PLUGGED_WIRELESS = 4

    const val HEALTH_UNKNOWN = 1
    const val HEALTH_GOOD = 2
    const val HEALTH_OVERHEAT = 3
    const val HEALTH_DEAD = 4
    const val HEALTH_OVER_VOLTAGE = 5
    const val HEALTH_UNSPECIFIED_FAILURE = 6
    const val HEALTH_COLD = 7
}

/**
 * One reading of the battery.
 *
 * A field of -1 means "the system did not report this", which is different from zero
 * and must never be rendered as a number: "Battery 0%, on battery, 0m remaining" is a
 * lie the model will act on.
 */
data class BatterySnapshot(
    val levelPercent: Int = -1,
    val status: Int = BatteryConstants.STATUS_UNKNOWN,
    val plugged: Int = BatteryConstants.PLUGGED_NONE,
    val timeToFullSeconds: Long = -1L,
    val temperatureCelsius: Float = -1f,
    val health: Int = BatteryConstants.HEALTH_UNKNOWN,
    val screenOn: Boolean? = null,
)

/** Everything the battery tool can say, decided in pure Kotlin. */
object BatteryFacts {

    fun chargeStateOf(status: Int, plugged: Int): ChargeState = when (status) {
        BatteryConstants.STATUS_CHARGING -> ChargeState.CHARGING
        BatteryConstants.STATUS_FULL -> ChargeState.FULL
        BatteryConstants.STATUS_DISCHARGING -> ChargeState.ON_BATTERY
        BatteryConstants.STATUS_NOT_CHARGING ->
            if (plugged == BatteryConstants.PLUGGED_NONE) ChargeState.NOT_CHARGING else ChargeState.CHARGING

        BatteryConstants.STATUS_UNKNOWN ->
            if (plugged == BatteryConstants.PLUGGED_NONE) ChargeState.UNPLUGGED else ChargeState.CHARGING

        else -> ChargeState.UNKNOWN
    }

    /** "USB", "wall charger", "wireless", or "" — never an int. */
    fun plugDescriptionOf(plugged: Int): String = when (plugged) {
        BatteryConstants.PLUGGED_AC -> "wall charger"
        BatteryConstants.PLUGGED_USB -> "USB"
        BatteryConstants.PLUGGED_WIRELESS -> "wireless charger"
        BatteryConstants.PLUGGED_NONE -> ""
        else -> "unknown charger"
    }

    /**
     * Renders the observation the model sees.
     *
     * Shape: percentage, then charge state, then time remaining or time to full. A
     * missing estimate is stated as missing rather than omitted, because a model that
     * asks "how much battery do I have" and gets a bare percentage tends to invent a
     * duration. Worst case this is well under 200 characters.
     */
    fun describe(snapshot: BatterySnapshot): String {
        val level = if (snapshot.levelPercent in 0..100) {
            "Battery ${snapshot.levelPercent}%."
        } else {
            "Battery level not reported by the system."
        }

        val state = when (chargeStateOf(snapshot.status, snapshot.plugged)) {
            ChargeState.CHARGING -> "Charging" + plugDescriptionOf(snapshot.plugged).let { " on the $it." }
            ChargeState.FULL -> "Fully charged and plugged in."
            ChargeState.ON_BATTERY -> "Running on battery."
            ChargeState.NOT_CHARGING -> "Plugged in but not charging."
            ChargeState.UNPLUGGED -> "Not charging, no charger connected."
            ChargeState.UNKNOWN -> "Charge state not reported by the system."
        }

        val chargeState = chargeStateOf(snapshot.status, snapshot.plugged)
        // Time-to-full is a real platform reading (API 28+, EXTRA_TIME_TO_FULL).
        // Time-to-EMPTY is not: Android has never exposed one. Deriving one from the
        // percentage would be a guess, and a model quotes a guess back to the user as
        // fact ("about 4 hours left"). So the absence is stated instead of filled in.
        val timing = when {
            snapshot.timeToFullSeconds in 1..MAX_ESTIMATE_SECONDS &&
                (chargeState == ChargeState.CHARGING || chargeState == ChargeState.FULL) ->
                " About ${formatDuration(snapshot.timeToFullSeconds)} until full."

            chargeState == ChargeState.ON_BATTERY && snapshot.levelPercent in 0..100 ->
                " Android does not report a time-to-empty estimate."

            else -> ""
        }

        val temp = if (snapshot.temperatureCelsius > 0f) {
            " Battery temperature ${snapshot.temperatureCelsius.toInt()}°C."
        } else {
            ""
        }

        val health = if (snapshot.health != BatteryConstants.HEALTH_GOOD &&
            snapshot.health != BatteryConstants.HEALTH_UNKNOWN
        ) {
            " Battery health: ${healthDescriptionOf(snapshot.health)}."
        } else {
            ""
        }

        return listOf(level, state, timing, temp, health)
            .filter { it.isNotBlank() }
            .joinToString(" ")
    }

    fun healthDescriptionOf(health: Int): String = when (health) {
        BatteryConstants.HEALTH_OVERHEAT -> "overheating"
        BatteryConstants.HEALTH_DEAD -> "dead, may need replacement"
        BatteryConstants.HEALTH_OVER_VOLTAGE -> "over voltage"
        BatteryConstants.HEALTH_COLD -> "too cold"
        BatteryConstants.HEALTH_UNSPECIFIED_FAILURE -> "failed"
        else -> "unreported"
    }

    /** "3h 12m", "45m", "under a minute" — the three shapes a human would use. */
    fun formatDuration(seconds: Long): String = when {
        seconds < 0 -> "an unknown amount of time"
        seconds < 60 -> "under a minute"
        seconds < 3600 -> "${seconds / 60}m"
        else -> {
            val hours = seconds / 3600
            val minutes = (seconds % 3600) / 60
            if (minutes == 0L) "${hours}h" else "${hours}h ${minutes}m"
        }
    }

    fun data(snapshot: BatterySnapshot): ToolArgs = buildJsonObject {
        if (snapshot.levelPercent >= 0) put("level_percent", snapshot.levelPercent)
        put("charge_state", chargeStateOf(snapshot.status, snapshot.plugged).name.lowercase())
        if (snapshot.plugged > 0) put("plugged", plugDescriptionOf(snapshot.plugged))
        if (snapshot.timeToFullSeconds >= 0) put("time_to_full_seconds", snapshot.timeToFullSeconds)
        snapshot.screenOn?.let { put("screen_on", it) }
    }

    /** No sticky ACTION_BATTERY_CHANGED broadcast at all — a device with no battery. */
    fun describeAbsent(): String =
        "No battery information is available on this device."

    const val MAX_ESTIMATE_SECONDS = 24L * 3600L
}

// =====================================================================================
// Device info
// =====================================================================================

/** Raw device facts, flattened so [DeviceInfoFacts] can render them without Android. */
data class DeviceFacts(
    val manufacturer: String? = null,
    val model: String? = null,
    val androidRelease: String? = null,
    val sdkInt: Int = 0,
    val widthPixels: Int = 0,
    val heightPixels: Int = 0,
    val densityDpi: Int = 0,
    val totalRamBytes: Long = -1L,
    val availableRamBytes: Long = -1L,
    val totalStorageBytes: Long = -1L,
    val availableStorageBytes: Long = -1L,
)

object DeviceInfoFacts {

    /**
     * A device is free to report a model string of any length and content, and it is
     * attacker-controlled in the sense that the value reaches the model verbatim. Every
     * string is therefore sanitised to a bounded, single-line, control-char-free form
     * before it is rendered. This is what keeps the worst-case observation bounded.
     */
    const val MAX_FIELD_CHARS = 48

    fun sanitizeField(value: String?, max: Int = MAX_FIELD_CHARS): String? {
        if (value == null) return null
        val cleaned = value
            .map { ch -> if (ch.isISOControl() || ch == '\uFEFF') ' ' else ch }
            .joinToString("")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (cleaned.isEmpty()) return null
        return if (cleaned.length <= max) cleaned else cleaned.take(max) + "…"
    }

    /** Binary units, because that is what the storage settings screen shows. */
    fun formatBytes(bytes: Long): String? {
        if (bytes < 0) return null
        val gib = bytes / (1024.0 * 1024.0 * 1024.0)
        val mib = bytes / (1024.0 * 1024.0)
        return when {
            gib >= 1.0 -> String.format(java.util.Locale.US, "%.1f GB", gib)
            mib >= 1.0 -> String.format(java.util.Locale.US, "%.0f MB", mib)
            else -> String.format(java.util.Locale.US, "%.0f KB", bytes / 1024.0)
        }
    }

    fun deviceName(facts: DeviceFacts): String {
        val model = sanitizeField(facts.model)
        val manufacturer = sanitizeField(facts.manufacturer)
        return when {
            model == null && manufacturer == null -> "Unknown device"
            manufacturer == null || model == null -> model ?: manufacturer!!
            // "Google Pixel 8 Pro" — do not repeat the vendor when the model says it.
            model.startsWith(manufacturer, ignoreCase = true) -> model
            else -> "$manufacturer $model"
        }
    }

    fun screenDescription(facts: DeviceFacts): String? {
        if (facts.widthPixels <= 0 || facts.heightPixels <= 0) return null
        val longEdge = maxOf(facts.widthPixels, facts.heightPixels)
        val shortEdge = minOf(facts.widthPixels, facts.heightPixels)
        val ratio = longEdge.toDouble() / shortEdge
        val shape = when {
            ratio > 2.15 -> "$ratio:1 tall"
            ratio < 1.85 -> "$ratio:1 wide"
            else -> "$ratio:1"
        }
        val density = if (facts.densityDpi > 0) " at ${facts.densityDpi}dpi" else ""
        return "${facts.widthPixels}x${facts.heightPixels}px, $shape$density"
    }

    fun osDescription(facts: DeviceFacts): String? {
        val release = sanitizeField(facts.androidRelease, 24)
        return when {
            release != null && facts.sdkInt > 0 -> "Android $release (API ${facts.sdkInt})"
            release != null -> "Android $release"
            facts.sdkInt > 0 -> "Android API ${facts.sdkInt}"
            else -> null
        }
    }

    fun ramDescription(facts: DeviceFacts): String? {
        val total = formatBytes(facts.totalRamBytes) ?: return null
        val available = formatBytes(facts.availableRamBytes)
        return if (available == null) "$total RAM" else "$total RAM, $available available"
    }

    fun storageDescription(facts: DeviceFacts): String? {
        val available = formatBytes(facts.availableStorageBytes) ?: return null
        val total = formatBytes(facts.totalStorageBytes)
        return if (total == null) "$available storage free" else "$available free of $total storage"
    }

    fun describe(facts: DeviceFacts): String {
        val parts = buildList {
            add(deviceName(facts))
            osDescription(facts)?.let { add(it) }
            screenDescription(facts)?.let { add("Screen ${it}.") }
            ramDescription(facts)?.let { add(it) }
            storageDescription(facts)?.let { add(it) }
        }
        return parts.joinToString(". ") + "."
    }

    fun data(facts: DeviceFacts): ToolArgs = buildJsonObject {
        put("manufacturer", sanitizeField(facts.manufacturer) ?: "unknown")
        put("model", sanitizeField(facts.model) ?: "unknown")
        put("android_release", sanitizeField(facts.androidRelease, 24) ?: "unknown")
        put("sdk_int", facts.sdkInt)
        put("width_px", facts.widthPixels)
        put("height_px", facts.heightPixels)
        put("density_dpi", facts.densityDpi)
        if (facts.totalRamBytes >= 0) put("total_ram_bytes", facts.totalRamBytes)
        if (facts.availableRamBytes >= 0) put("available_ram_bytes", facts.availableRamBytes)
        if (facts.totalStorageBytes >= 0) put("total_storage_bytes", facts.totalStorageBytes)
        if (facts.availableStorageBytes >= 0) {
            put("available_storage_bytes", facts.availableStorageBytes)
        }
    }
}

// =====================================================================================
// Vibration
// =====================================================================================

/** The decision a vibrate call makes, independent of whether a motor exists. */
sealed class VibrationPlan {
    /** Go ahead for [durationMs]. */
    data class Vibrate(val durationMs: Int) : VibrationPlan()

    /** The device has no vibration motor. */
    data object NoMotor : VibrationPlan()

    /**
     * Do Not Disturb is on. Reported as a *success* with an explicit "nothing happened",
     * because returning a failure here would make the agent loop retry a request the
     * system has already answered correctly.
     */
    data object SuppressedByDoNotDisturb : VibrationPlan()
}

object VibrationLogic {
    const val MIN_DURATION_MS = 10
    const val MAX_DURATION_MS = 5_000
    const val DEFAULT_DURATION_MS = 300

    /** Clamps a model-supplied duration into the range the API is safe with. */
    fun coerceDurationMs(raw: Int?): Int = when {
        raw == null -> DEFAULT_DURATION_MS
        raw < MIN_DURATION_MS -> MIN_DURATION_MS
        raw > MAX_DURATION_MS -> MAX_DURATION_MS
        else -> raw
    }

    fun plan(hasVibrator: Boolean, doNotDisturbActive: Boolean, durationMs: Int): VibrationPlan = when {
        !hasVibrator -> VibrationPlan.NoMotor
        doNotDisturbActive -> VibrationPlan.SuppressedByDoNotDisturb
        else -> VibrationPlan.Vibrate(durationMs)
    }

    fun describe(plan: VibrationPlan): String = when (plan) {
        is VibrationPlan.Vibrate -> "Vibrating for ${plan.durationMs} ms."
        VibrationPlan.NoMotor -> "This device has no vibration motor, so nothing happened."
        VibrationPlan.SuppressedByDoNotDisturb ->
            "Nothing happened: Do Not Disturb is on, so vibration is suppressed. " +
                "Tell the user to turn off Do Not Disturb if they need the buzz."
    }
}

// =====================================================================================
// Settings screens
// =====================================================================================

/**
 * The settings sub-screens the agent may open.
 *
 * The Intent action is deliberately NOT stored here: it is supplied by the Android
 * layer from the real `Settings.ACTION_*` constant, so this enum stays pure and the
 * one-per-screen mapping lives next to the platform call that uses it.
 */
enum class SettingsScreen(val argName: String, val displayName: String) {
    WIFI("wifi", "Wi-Fi"),
    BLUETOOTH("bluetooth", "Bluetooth"),
    DISPLAY("display", "Display"),
    SOUND("sound", "Sound & vibration"),
    BATTERY_SAVER("battery_saver", "Battery saver"),
    ;

    companion object {
        val ARG_NAMES: List<String> get() = entries.map { it.argName }
    }
}

object SettingsScreenResolver {
    /**
     * Maps a model-supplied string to a screen.
     *
     * Tolerant on purpose: users say "wifi", "wi-fi", "internet" and "the network
     * settings" and the model will echo all of them. A miss returns null and the caller
     * answers with [unknownMessage] naming the valid options, because silently opening
     * the wrong settings screen is worse than not opening one.
     */
    fun resolve(raw: String?): SettingsScreen? {
        val key = normalizeKey(raw)
        if (key.isEmpty()) return null
        return when (key) {
            "wifi", "wi_fi", "wireless", "internet", "network", "internet_connection" -> SettingsScreen.WIFI
            "bluetooth", "bt", "bluetooth_settings", "paired_devices" -> SettingsScreen.BLUETOOTH
            "display", "screen", "brightness", "screen_settings" -> SettingsScreen.DISPLAY
            "sound", "volume", "audio", "sound_and_vibration", "ringer" -> SettingsScreen.SOUND
            "battery", "battery_saver", "power", "power_saving" -> SettingsScreen.BATTERY_SAVER
            else -> null
        }
    }

    /**
     * Folds the ways a person writes the same word together.
     *
     * "wi-fi", "wi fi", "Wi Fi" and "WIFI" are one word; so are "power saving" and
     * "power_saving". Without this a user typing the space-separated form gets "there
     * is no settings screen called \"power saving\"" for a screen that exists.
     */
    private fun normalizeKey(raw: String?): String = raw
        ?.trim()
        ?.lowercase()
        ?.map { if (it == '-' || it == ' ' || it == '_') '_' else it }
        ?.joinToString("")
        .orEmpty()

    fun unknownMessage(raw: String?): String {
        val shown = ArgCoerce.ellipsize(raw?.trim().orEmpty(), 32)
        val got = if (shown.isEmpty()) "nothing" else "\"$shown\""
        return "There is no settings screen called $got. " +
            "Valid screens: ${SettingsScreen.ARG_NAMES.joinToString(", ")}."
    }

    fun describe(screen: SettingsScreen): String = "Opened the ${screen.displayName} settings screen."
}

// =====================================================================================
// The platform seam
// =====================================================================================

/**
 * Everything the four device tools need from Android, as data in and data out.
 *
 * Constructor injection is the DI system in this project (see tool-contract.md), so this
 * seam is public and the tools take it as a constructor parameter. A JVM test can then
 * drive `execute()` end to end with a fake, which is the only way to get the real
 * permission/cancellation/exception paths covered without a device. The only android.*
 * code in this file is [AndroidDevicePlatform].
 */
interface DevicePlatform {
    /** null when the device reports no battery at all. */
    fun batterySnapshot(): BatterySnapshot?

    fun deviceFacts(): DeviceFacts

    fun hasVibrator(): Boolean

    fun isDoNotDisturbActive(): Boolean

    fun vibrate(durationMs: Int)

    /** False when no installed activity can handle the screen. */
    fun openSettingsScreen(screen: SettingsScreen): Boolean
}

// =====================================================================================
// Tools
// =====================================================================================

private val BATTERY_SCHEMA: ToolArgs = buildJsonObject {
    put("type", "object")
    put("description", "No arguments.")
    putJsonObject("properties") { }
    putJsonArray("required") { }
}

private val VIBRATE_SCHEMA: ToolArgs = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") {
        putJsonObject("duration_ms") {
            put("type", "integer")
            put("minimum", VibrationLogic.MIN_DURATION_MS)
            put("maximum", VibrationLogic.MAX_DURATION_MS)
            put(
                "description",
                "How long to buzz in milliseconds. Defaults to ${VibrationLogic.DEFAULT_DURATION_MS}.",
            )
        }
    }
    putJsonArray("required") { }
}

private val OPEN_SETTINGS_SCHEMA: ToolArgs = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") {
        putJsonObject("screen") {
            put("type", "string")
            putJsonArray("enum") { SettingsScreen.ARG_NAMES.forEach { add(it) } }
            put("description", "Which settings screen to open. One of: ${SettingsScreen.ARG_NAMES.joinToString(", ")}.")
        }
    }
    putJsonArray("required") { add("screen") }
}

class DeviceBatteryTool(private val platform: DevicePlatform) : AgentTool {

    override val definition = ToolDefinition(
        name = "device.battery",
        description = "Return the current battery percentage, whether the phone is charging, " +
            "and the estimated time until the battery is empty or full.",
        category = "device",
        schema = BATTERY_SCHEMA,
        risk = ToolRisk.READ_ONLY,
        tags = setOf(
            "battery", "charge", "power", "how much battery", "battery level",
            "charging", "how long until charged", "drain",
        ),
        requiredPermission = null,
    )

    override suspend fun execute(args: ToolArgs, context: ToolContext): ToolResult {
        if (!context.permissionGranted) {
            return ToolResult(
                success = false,
                observation = "No permission to read the battery. Tell the user it is unavailable " +
                    "in this session; do not retry.",
                error = ToolError.PermissionDenied("battery read denied"),
            )
        }
        if (context.signal.isCancelled()) {
            return ToolResult(
                success = false,
                observation = "Cancelled before the battery could be read.",
                error = ToolError.Cancelled("cancelled before device.battery"),
            )
        }
        return guarded("device.battery") {
            val snapshot = platform.batterySnapshot()
            if (snapshot == null) {
                ToolResult(
                    success = false,
                    observation = BatteryFacts.describeAbsent() +
                        " This looks like a device with no battery; there is nothing to report.",
                    data = buildJsonObject { put("battery_present", false) },
                    error = ToolError.Unavailable("no battery on this device"),
                )
            } else {
                ToolResult(
                    success = true,
                    observation = ObservationTruncator.truncate(BatteryFacts.describe(snapshot)),
                    data = BatteryFacts.data(snapshot),
                )
            }
        }
    }
}

class DeviceInfoTool(private val platform: DevicePlatform) : AgentTool {

    override val definition = ToolDefinition(
        name = "device.info",
        description = "Return the phone model, Android version, screen size, total RAM, and " +
            "free storage.",
        category = "device",
        schema = BATTERY_SCHEMA,
        risk = ToolRisk.READ_ONLY,
        tags = setOf(
            "device info", "phone model", "specs", "how much ram", "storage",
            "free space", "android version", "screen size",
        ),
        requiredPermission = null,
    )

    override suspend fun execute(args: ToolArgs, context: ToolContext): ToolResult {
        if (!context.permissionGranted) {
            return ToolResult(
                success = false,
                observation = "No permission to read device information. Tell the user it is " +
                    "unavailable in this session; do not retry.",
                error = ToolError.PermissionDenied("device info denied"),
            )
        }
        if (context.signal.isCancelled()) {
            return ToolResult(
                success = false,
                observation = "Cancelled before the device information could be read.",
                error = ToolError.Cancelled("cancelled before device.info"),
            )
        }
        return guarded("device.info") {
            val facts = platform.deviceFacts()
            ToolResult(
                success = true,
                observation = ObservationTruncator.truncate(DeviceInfoFacts.describe(facts)),
                data = DeviceInfoFacts.data(facts),
            )
        }
    }
}

class DeviceVibrateTool(private val platform: DevicePlatform) : AgentTool {

    override val definition = ToolDefinition(
        name = "device.vibrate",
        description = "Vibrate the phone for a short duration and return what actually happened.",
        category = "device",
        schema = VIBRATE_SCHEMA,
        risk = ToolRisk.REVERSIBLE,
        tags = setOf(
            "vibrate", "buzz", "vibration", "shake", "ringer", "find my phone", "ring",
        ),
        requiredPermission = "android.permission.VIBRATE",
    )

    override suspend fun execute(args: ToolArgs, context: ToolContext): ToolResult {
        if (!context.permissionGranted) {
            return ToolResult(
                success = false,
                observation = "No permission to vibrate the phone. Tell the user it is unavailable " +
                    "in this session; do not retry.",
                error = ToolError.PermissionDenied("vibrate denied"),
            )
        }
        if (context.signal.isCancelled()) {
            return ToolResult(
                success = false,
                observation = "Cancelled before the phone could vibrate.",
                error = ToolError.Cancelled("cancelled before device.vibrate"),
            )
        }

        val durationMs = when (val parsed = ArgCoerce.intIn(args, "duration_ms", -MAX_RAW_DURATION, MAX_RAW_DURATION)) {
            is ArgResult.Present -> VibrationLogic.coerceDurationMs(parsed.value)
            is ArgResult.Unusable -> {
                return ToolResult(
                    success = false,
                    observation = "duration_ms must be a whole number of milliseconds, but was " +
                        "${parsed.got}. Send something like {\"duration_ms\": 300}.",
                    error = ToolError.InvalidArguments("duration_ms unusable: ${parsed.got}"),
                )
            }

            is ArgResult.Missing -> VibrationLogic.DEFAULT_DURATION_MS
        }

        return guarded("device.vibrate") {
            val plan = VibrationLogic.plan(
                hasVibrator = platform.hasVibrator(),
                doNotDisturbActive = platform.isDoNotDisturbActive(),
                durationMs = durationMs,
            )
            when (plan) {
                is VibrationPlan.Vibrate -> {
                    platform.vibrate(plan.durationMs)
                    ToolResult(
                        success = true,
                        observation = VibrationLogic.describe(plan),
                        data = buildJsonObject {
                            put("vibrated", true)
                            put("duration_ms", plan.durationMs)
                        },
                    )
                }

                // DND is a successful answer, not a failure: returning false here would
                // make the agent loop retry a request the system has already handled.
                VibrationPlan.SuppressedByDoNotDisturb -> ToolResult(
                    success = true,
                    observation = VibrationLogic.describe(plan),
                    data = buildJsonObject {
                        put("vibrated", false)
                        put("suppressed_by", "do_not_disturb")
                        put("duration_ms", durationMs)
                    },
                )

                VibrationPlan.NoMotor -> ToolResult(
                    success = false,
                    observation = VibrationLogic.describe(plan),
                    error = ToolError.Unavailable("device has no vibration motor"),
                )

            }
        }
    }

    private companion object {
        /** A wider read window than the clamp, so a typo reads as a typo not as a clamp. */
        const val MAX_RAW_DURATION = 1_000_000
    }
}

class DeviceOpenSettingsTool(private val platform: DevicePlatform) : AgentTool {

    override val definition = ToolDefinition(
        name = "device.open_settings",
        description = "Open a system settings screen on the phone and return the screen that " +
            "was opened.",
        category = "device",
        schema = OPEN_SETTINGS_SCHEMA,
        risk = ToolRisk.REVERSIBLE,
        tags = setOf(
            "open settings", "settings", "wifi settings", "turn on bluetooth", "display settings",
            "battery saver", "sound settings", "system settings",
        ),
        // No permission: this launches an ordinary settings activity. It is classified
        // REVERSIBLE rather than READ_ONLY because it takes the user out of the app and
        // changes what they are looking at.
        requiredPermission = null,
    )

    override suspend fun execute(args: ToolArgs, context: ToolContext): ToolResult {
        if (!context.permissionGranted) {
            return ToolResult(
                success = false,
                observation = "No permission to open settings. Tell the user it is unavailable " +
                    "in this session; do not retry.",
                error = ToolError.PermissionDenied("open settings denied"),
            )
        }

        val requested = when (val parsed = ArgCoerce.string(args, "screen")) {
            is ArgResult.Present -> parsed.value
            is ArgResult.Missing -> {
                return ToolResult(
                    success = false,
                    observation = "screen is required. Valid screens: " +
                        "${SettingsScreen.ARG_NAMES.joinToString(", ")}.",
                    error = ToolError.InvalidArguments("screen missing"),
                )
            }

            is ArgResult.Unusable -> {
                return ToolResult(
                    success = false,
                    observation = "screen must be a single word, but was ${parsed.got}. " +
                        "Valid screens: ${SettingsScreen.ARG_NAMES.joinToString(", ")}.",
                    error = ToolError.InvalidArguments("screen unusable: ${parsed.got}"),
                )
            }
        }

        val screen = SettingsScreenResolver.resolve(requested)
            ?: return ToolResult(
                success = false,
                observation = SettingsScreenResolver.unknownMessage(requested),
                error = ToolError.InvalidArguments("unknown screen"),
            )

        if (context.signal.isCancelled()) {
            return ToolResult(
                success = false,
                observation = "Cancelled before the settings screen could be opened.",
                error = ToolError.Cancelled("cancelled before device.open_settings"),
            )
        }

        return guarded("device.open_settings") {
            val opened = platform.openSettingsScreen(screen)
            if (opened) {
                ToolResult(
                    success = true,
                    observation = SettingsScreenResolver.describe(screen),
                    data = buildJsonObject { put("screen", screen.argName) },
                )
            } else {
                ToolResult(
                    success = false,
                    observation = "Nothing on this device handles the ${screen.displayName} " +
                        "settings screen, so nothing was opened. The user can reach it from " +
                        "Settings directly.",
                    error = ToolError.Unavailable("no handler for ${screen.argName} settings"),
                )
            }
        }
    }
}

// =====================================================================================
// The Android implementation — the only android.* code in this file
// =====================================================================================

/**
 * Reads device state through the public platform APIs. No reflection, no hidden APIs.
 *
 * Three platform details are handled here rather than in the pure logic above, and each
 * one is a real trap:
 *
 *  - `Intent.ACTION_BATTERY_CHANGED` is a STICKY broadcast. `registerReceiver(null, …)`
 *    is the documented way to read one without registering, so there is no receiver to
 *    leak and no unregister to forget.
 *  - time-to-full arrives as a named extra, not a `BatteryManager` property constant,
 *    and only exists on API 28+. Absent on 26/27 it defaults to -1, which the pure
 *    layer renders as "not reported" instead of a bogus 0 seconds.
 *  - Do Not Disturb has to be read from a sticky notification broadcast, falling back
 *    to the `zen_mode` global setting. Reading it needs no permission; *changing* it
 *    would need ACCESS_NOTIFICATION_POLICY, which this tool does not do.
 */
@SuppressLint("NewApi")
class AndroidDevicePlatform(private val context: Context) : DevicePlatform {

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

    override fun batterySnapshot(): BatterySnapshot? {
        val intent = appContext.registerReceiver(
            null,
            android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED),
        ) ?: return null

        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        // Integer arithmetic on purpose: a phone that reports 3900/4000 must not be
        // told 97% because of a rounding surprise, and level*100 can overflow on a
        // pathological scale, hence the Long.
        val rawPercent = if (level >= 0 && scale > 0) (level * 100L) / scale else -1L
        val timeToFull = intent.getLongExtra(EXTRA_TIME_TO_FULL, -1L)

        return BatterySnapshot(
            levelPercent = rawPercent.coerceIn(-1L, 100L).toInt(),
            status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryConstants.STATUS_UNKNOWN),
            plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, BatteryConstants.PLUGGED_NONE),
            timeToFullSeconds = timeToFull,
            temperatureCelsius = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) / 10f,
            health = intent.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryConstants.HEALTH_UNKNOWN),
            screenOn = intent.getBooleanExtra(BatteryManager.EXTRA_PRESENT, true),
        )
    }

    override fun deviceFacts(): DeviceFacts {
        val metrics = appContext.resources.displayMetrics
        val memory = ActivityManager.MemoryInfo()
        (appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)
            ?.getMemoryInfo(memory)

        val stat = android.os.StatFs(appContext.filesDir.path)

        return DeviceFacts(
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            androidRelease = Build.VERSION.RELEASE,
            sdkInt = Build.VERSION.SDK_INT,
            widthPixels = metrics.widthPixels,
            heightPixels = metrics.heightPixels,
            densityDpi = metrics.densityDpi,
            totalRamBytes = memory.totalMem,
            availableRamBytes = memory.availMem,
            totalStorageBytes = stat.totalBytes,
            availableStorageBytes = stat.availableBytes,
        )
    }

    private fun vibrator(): Vibrator? =
        if (Build.VERSION.SDK_INT >= API_S) {
            appContext.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    override fun hasVibrator(): Boolean = vibrator()?.hasVibrator() == true

    override fun isDoNotDisturbActive(): Boolean = try {
        val filter = appContext.registerReceiver(
            null,
            android.content.IntentFilter(android.app.NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED),
        )?.getIntExtra(EXTRA_INTERRUPTION_FILTER, -1)
        if (filter != null && filter >= 0) {
            filter != android.app.NotificationManager.INTERRUPTION_FILTER_ALL
        } else {
            // Fall back to the global zen_mode setting: 0 is off, everything else is some
            // form of interruption. A missing key reads as 0, i.e. "not in DND".
            Settings.Global.getInt(appContext.contentResolver, KEY_ZEN_MODE, 0) != 0
        }
    } catch (e: Exception) {
        // An OEM build that refuses the read must not silently swallow a buzz the user
        // explicitly asked for. Failing open (vibrate) is the lesser evil here, and the
        // user is told nothing happened only when we actually know DND is on.
        false
    }

    override fun vibrate(durationMs: Int) {
        val vibrator = vibrator() ?: return
        // USAGE_ALARM makes the system apply the user's alarm-vibration setting, which is
        // the right bucket for a deliberate, user-requested buzz. VibrationEffect and
        // createOneShot are both API 26, so no branch is needed for those.
        val effect = VibrationEffect.createOneShot(
            durationMs.toLong(),
            VibrationEffect.DEFAULT_AMPLITUDE,
        )
        if (Build.VERSION.SDK_INT >= API_TIRAMISU) {
            // API 33+ replaced the AudioAttributes overload, which is deprecated there.
            // The guard is the whole point of targeting minSdk 26.
            vibrator.vibrate(
                effect,
                android.os.VibrationAttributes.Builder()
                    .setUsage(android.os.VibrationAttributes.USAGE_ALARM)
                    .build(),
            )
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(
                effect,
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                    .build(),
            )
        }
    }

    override fun openSettingsScreen(screen: SettingsScreen): Boolean {
        val intent = settingsIntentFor(screen)
        if (startSettings(intent)) return true
        // A handful of OEM builds ship without a Bluetooth or battery-saver screen;
        // the top-level settings screen is a far better answer than a failure.
        if (screen == SettingsScreen.BLUETOOTH || screen == SettingsScreen.BATTERY_SAVER) {
            return startSettings(
                Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
        return false
    }

    private fun settingsIntentFor(screen: SettingsScreen): Intent = Intent(
        when (screen) {
            // NB: there is no Settings.ACTION_WIFI. The real constant is
            // ACTION_WIFI_SETTINGS; using the wrong one is a compile error, not a
            // silent no-op, which is exactly why it is written out here.
            SettingsScreen.WIFI -> Settings.ACTION_WIFI_SETTINGS
            SettingsScreen.BLUETOOTH -> Settings.ACTION_BLUETOOTH_SETTINGS
            SettingsScreen.DISPLAY -> Settings.ACTION_DISPLAY_SETTINGS
            SettingsScreen.SOUND -> Settings.ACTION_SOUND_SETTINGS
            SettingsScreen.BATTERY_SAVER -> Settings.ACTION_BATTERY_SAVER_SETTINGS
        },
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    private fun startSettings(intent: Intent): Boolean = try {
        appContext.startActivity(intent)
        true
    } catch (e: android.content.ActivityNotFoundException) {
        // No installed activity handles this screen on this device. An explicit
        // "nothing was opened" is better than a generic internal error.
        false
    }

    private companion object {
        /** `BatteryManager.EXTRA_TIME_TO_FULL`, API 28+, seconds remaining. */
        const val EXTRA_TIME_TO_FULL = "android.os.extra.TIME_TO_FULL"

        /** `NotificationManager.EXTRA_INTERRUPTION_FILTER`, API 23+. */
        const val EXTRA_INTERRUPTION_FILTER = "android.app.extra.INTERRUPTION_FILTER"

        /** `Settings.Global.ZEN_MODE`, the DND switch itself: 0 is off. */
        const val KEY_ZEN_MODE = "zen_mode"

        const val API_S = 31
        const val API_TIRAMISU = 33
    }
}

/** All four device tools, wired to a real [Context]. */
fun deviceTools(context: Context): List<AgentTool> = listOf(
    DeviceBatteryTool(AndroidDevicePlatform(context)),
    DeviceInfoTool(AndroidDevicePlatform(context)),
    DeviceVibrateTool(AndroidDevicePlatform(context)),
    DeviceOpenSettingsTool(AndroidDevicePlatform(context)),
)
