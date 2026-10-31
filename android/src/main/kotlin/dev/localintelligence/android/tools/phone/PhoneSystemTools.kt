package dev.localintelligence.android.tools.phone

import android.app.NotificationManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import dev.localintelligence.android.tools.device.ArgCoerce
import dev.localintelligence.android.tools.device.ArgResult
import dev.localintelligence.android.tools.device.guarded
import dev.localintelligence.android.tools.messages.CallerNames
import dev.localintelligence.core.model.ObservationOrigin
import dev.localintelligence.core.model.ToolArgs
import dev.localintelligence.core.tool.AgentTool
import dev.localintelligence.core.tool.ToolContext
import dev.localintelligence.core.tool.ToolDefinition
import dev.localintelligence.core.tool.ToolError
import dev.localintelligence.core.tool.ToolResult
import dev.localintelligence.core.tool.ToolRisk
import dev.localintelligence.core.tool.contracts.PermissionDenial
import dev.localintelligence.core.tool.contracts.PlatformGrant
import dev.localintelligence.core.tool.contracts.PlatformRequirement
import dev.localintelligence.core.tool.contracts.ToolPermissions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.Calendar

// =====================================================================================
// Phone settings and calls: place a call, Do Not Disturb, screen brightness, and screen
// time. Each one that changes the phone asks the person through the policy first, or is
// reversible; each special access is switched on in a Settings screen, which the tool opens.
// =====================================================================================

/** The Do Not Disturb modes the model may name, mapped to the platform's interruption filter. */
internal enum class DndMode(val arg: String, val filter: Int, val label: String) {
    OFF("off", NotificationManager.INTERRUPTION_FILTER_ALL, "off"),
    PRIORITY("priority", NotificationManager.INTERRUPTION_FILTER_PRIORITY, "priority only"),
    ALARMS("alarms", NotificationManager.INTERRUPTION_FILTER_ALARMS, "alarms only"),
    NONE("none", NotificationManager.INTERRUPTION_FILTER_NONE, "on, total silence");

    companion object {
        val ARG_NAMES: List<String> = values().map { it.arg }

        /**
         * "on" is priority, what Android's own Do Not Disturb switch turns on. WHY: "Turn on do
         * not disturb" makes LFM3-1.7B pass mode "on", and its call format does not hold the
         * argument to the enum, so the call failed and cost a second step.
         */
        fun byArg(raw: String): DndMode? {
            val key = raw.trim().lowercase()
            return if (key == "on") PRIORITY else values().firstOrNull { it.arg == key }
        }
    }
}

/** A refusal for a special access the person turns on in a Settings screen, with that screen opened. */
internal fun specialAccessRefusal(
    context: Context,
    tool: String,
    requirement: PlatformRequirement,
    screen: Intent,
): ToolResult {
    val opened = startIntent(context, screen)
    val tail = if (opened) " The settings screen is open now." else ""
    return ToolResult(
        success = false,
        observation = PermissionDenial.observation(tool, requirement) + tail,
        error = ToolError.PermissionDenied(PermissionDenial.summary(requirement)),
    )
}

/** The screen where Do Not Disturb access is turned on for this app. */
internal fun dndAccessScreen(): Intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)

/** "Maria (+31612345678)" when the number is a contact, else the number itself. */
private fun partyLabel(context: Context, number: String): String {
    val name = CallerNames.nameFor(context, number)
    return if (name != null) "$name ($number)" else number
}

// =====================================================================================
// Schemas
// =====================================================================================

private val CALL_SCHEMA: ToolArgs = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") {
        putJsonObject("number") {
            put("type", "string")
            put("maxLength", PhoneNumber.MAX_CHARS)
            put("description", "Phone number to call, e.g. +31612345678. Look a name up with contacts.search first.")
        }
    }
    putJsonArray("required") { add("number") }
}

private val DND_SCHEMA: ToolArgs = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") {
        putJsonObject("mode") {
            put("type", "string")
            putJsonArray("enum") { DndMode.ARG_NAMES.forEach { add(it) } }
            put("description", "off, priority (priority callers only), alarms, or none (total silence).")
        }
    }
    putJsonArray("required") { add("mode") }
}

private val BRIGHTNESS_SCHEMA: ToolArgs = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") {
        putJsonObject("level") {
            put("type", "integer")
            put("minimum", 0)
            put("maximum", 100)
            put("description", "Brightness as a percentage, 0 to 100.")
        }
        putJsonObject("auto") {
            put("type", "boolean")
            put("description", "true to set brightness back to automatic.")
        }
    }
    putJsonArray("required") { }
}

private val SCREEN_TIME_SCHEMA: ToolArgs = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") {
        putJsonObject("period") {
            put("type", "string")
            putJsonArray("enum") { add("today"); add("yesterday"); add("week") }
            put("description", "today, yesterday or week (the last 7 days). Default today.")
        }
    }
    putJsonArray("required") { }
}

// =====================================================================================
// phone.call
// =====================================================================================

/**
 * EXTERNAL_COMMUNICATION. Places the call at once, so the person confirms every time. The
 * confirmation names the contact when one resolves, and the number is always shown.
 */
class PhoneCallTool(private val appContext: Context, private val grant: PlatformGrant) : AgentTool {

    override val definition = ToolDefinition(
        name = "phone.call",
        description = "Place a phone call now to a number, after the user confirms. Use when the " +
            "user asks to call someone now; phone.dial only opens the dialer.",
        category = "phone",
        schema = CALL_SCHEMA,
        risk = ToolRisk.EXTERNAL_COMMUNICATION,
        observationOrigin = ObservationOrigin.LOCAL,
        tags = setOf("call", "phone call", "ring", "place a call", "bel nu"),
        requiredPermission = ToolPermissions.CALL_PHONE,
    )

    /** "Maria (+31612345678)" when the number is a contact, so the confirmation says who is called. */
    override suspend fun describeTarget(args: ToolArgs): String? {
        val raw = (args["number"] as? JsonPrimitive)?.content ?: return null
        val number = PhoneNumber.clean(raw) ?: return null
        val name = withContext(Dispatchers.IO) { CallerNames.nameFor(appContext, number) } ?: return null
        return "$name ($number)"
    }

    override suspend fun execute(args: ToolArgs, context: ToolContext): ToolResult {
        val raw = when (val r = textArg(args, "number", PhoneNumber.MAX_CHARS * 2)) {
            is ArgResult.Present -> r.value
            is ArgResult.Missing -> return invalidArgument("number is required, e.g. +31612345678.")
            is ArgResult.Unusable -> return invalidArgument("number must be one piece of text, but was ${r.got}.")
        }
        val number = PhoneNumber.clean(raw)
            ?: return invalidArgument(
                "\"${ArgCoerce.ellipsize(raw, 24)}\" is not a phone number. Send digits with an " +
                    "optional leading +, e.g. +31612345678. Look up a name with contacts.search first.",
            )
        if (!grant.isGranted(ToolPermissions.PHONE_CALL)) {
            return ToolResult(
                success = false,
                observation = PermissionDenial.observation("phone.call", ToolPermissions.PHONE_CALL),
                error = ToolError.PermissionDenied(PermissionDenial.summary(ToolPermissions.PHONE_CALL)),
            )
        }
        if (context.signal.isCancelled()) return cancelledBefore("phone.call")
        return withContext(Dispatchers.IO) { guarded("phone.call") { placeCall(number) } }
    }

    private fun placeCall(number: String): ToolResult {
        val intent = Intent(Intent.ACTION_CALL, Uri.fromParts("tel", number, null))
        if (!startIntent(appContext, intent)) {
            return ToolResult(
                success = false,
                observation = "Android refused to place the call, so nothing was called.",
                error = ToolError.Unavailable("startActivity refused for phone.call"),
            )
        }
        return ToolResult(
            success = true,
            observation = "Calling ${partyLabel(appContext, number)}.",
        )
    }
}

// =====================================================================================
// phone.dnd
// =====================================================================================

/**
 * REVERSIBLE. Sets the Do Not Disturb filter. Needs Do Not Disturb access, a special access
 * the person turns on in Settings, so a call without it opens that screen and says so.
 */
class PhoneDndTool(private val appContext: Context, private val grant: PlatformGrant) : AgentTool {

    override val definition = ToolDefinition(
        name = "phone.dnd",
        description = "Set Do Not Disturb to off, priority, alarms only, or none. Use when the user " +
            "says do not disturb, silence notifications or turn DND off.",
        category = "phone",
        schema = DND_SCHEMA,
        risk = ToolRisk.REVERSIBLE,
        observationOrigin = ObservationOrigin.LOCAL,
        tags = setOf("do not disturb", "dnd", "silence", "priority", "alarms only", "niet storen"),
        requiredPermission = ToolPermissions.ACCESS_NOTIFICATION_POLICY,
    )

    override suspend fun execute(args: ToolArgs, context: ToolContext): ToolResult {
        val mode = when (val r = ArgCoerce.string(args, "mode")) {
            is ArgResult.Present -> DndMode.byArg(r.value)
                ?: return invalidArgument("mode must be one of ${DndMode.ARG_NAMES.joinToString(", ")}.")
            is ArgResult.Missing -> return invalidArgument(
                "mode is required: one of ${DndMode.ARG_NAMES.joinToString(", ")}.",
            )
            is ArgResult.Unusable -> return invalidArgument(
                "mode must be one word: ${DndMode.ARG_NAMES.joinToString(", ")}.",
            )
        }
        if (!grant.isGranted(ToolPermissions.DO_NOT_DISTURB)) {
            return specialAccessRefusal(appContext, "phone.dnd", ToolPermissions.DO_NOT_DISTURB, dndAccessScreen())
        }
        if (context.signal.isCancelled()) return cancelledBefore("phone.dnd")
        return guarded("phone.dnd") { setFilter(mode) }
    }

    private fun setFilter(mode: DndMode): ToolResult {
        val manager = appContext.getSystemService(NotificationManager::class.java)
            ?: return ToolResult(
                success = false,
                observation = "This phone's notification service is not available, so nothing changed.",
                error = ToolError.Unavailable("no notification service"),
            )
        manager.setInterruptionFilter(mode.filter)
        return ToolResult(
            success = true,
            observation = "Do Not Disturb is now ${mode.label}.",
            data = buildJsonObject { put("mode", mode.arg) },
        )
    }
}

// =====================================================================================
// phone.brightness
// =====================================================================================

/**
 * REVERSIBLE. Sets the screen brightness as a percentage, or back to automatic. Changing
 * system settings needs the "Modify system settings" special access, so without it the tool
 * opens that screen for this app.
 */
class PhoneBrightnessTool(private val appContext: Context, private val grant: PlatformGrant) : AgentTool {

    override val definition = ToolDefinition(
        name = "phone.brightness",
        description = "Set the screen brightness to a percentage, or back to automatic. Use when the " +
            "user asks for the screen brighter, dimmer or automatic.",
        category = "phone",
        schema = BRIGHTNESS_SCHEMA,
        risk = ToolRisk.REVERSIBLE,
        observationOrigin = ObservationOrigin.LOCAL,
        tags = setOf("brightness", "screen brightness", "dimmer", "brighter", "auto brightness", "helderheid"),
        requiredPermission = ToolPermissions.WRITE_SETTINGS,
    )

    override suspend fun execute(args: ToolArgs, context: ToolContext): ToolResult {
        val auto = ArgCoerce.booleanOrNull(args["auto"]) == true
        val level = when (val r = ArgCoerce.intIn(args, "level", 0, 100)) {
            is ArgResult.Present -> r.value
            is ArgResult.Missing -> null
            is ArgResult.Unusable -> return invalidArgument("level must be a number from 0 to 100, but was ${r.got}.")
        }
        if (!auto && level == null) {
            return invalidArgument("say a level from 0 to 100, or auto true to set brightness back to automatic.")
        }
        if (!grant.isGranted(ToolPermissions.WRITE_SETTINGS_ACCESS)) {
            val screen = Intent(
                Settings.ACTION_MANAGE_WRITE_SETTINGS,
                Uri.parse("package:${appContext.packageName}"),
            )
            return specialAccessRefusal(appContext, "phone.brightness", ToolPermissions.WRITE_SETTINGS_ACCESS, screen)
        }
        if (context.signal.isCancelled()) return cancelledBefore("phone.brightness")
        return guarded("phone.brightness") { apply(if (auto) null else level) }
    }

    private fun apply(level: Int?): ToolResult {
        val resolver = appContext.contentResolver
        val written = if (level == null) {
            Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC)
        } else {
            // Manual first: with automatic on, a written brightness is overridden at once.
            val mode = Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            // The platform scale is 0..255 and a value of 0 can leave the screen unreadable, so the floor is 1.
            val raw = ((level * 255) + 50) / 100
            mode && Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, raw.coerceIn(1, 255))
        }
        if (!written) {
            return ToolResult(
                success = false,
                observation = "Android refused the brightness change, so it did not change.",
                error = ToolError.Unavailable("Settings.System write refused"),
            )
        }
        return ToolResult(
            success = true,
            observation = if (level == null) "Screen brightness is automatic now." else "Screen brightness set to $level%.",
        )
    }
}

// =====================================================================================
// device.screen_time
// =====================================================================================

private const val TOP_APPS: Int = 10

/**
 * READ_ONLY. The apps used most in a period, from the platform's usage statistics. The
 * statistics are a special access (Usage access), so without it the tool opens that screen.
 */
class DeviceScreenTimeTool(private val appContext: Context, private val grant: PlatformGrant) : AgentTool {

    override val definition = ToolDefinition(
        name = "device.screen_time",
        description = "List the apps used most in a period, with minutes for each. Use when the " +
            "user asks how long they used their phone or which apps they use most.",
        category = "device",
        schema = SCREEN_TIME_SCHEMA,
        risk = ToolRisk.READ_ONLY,
        observationOrigin = ObservationOrigin.LOCAL,
        tags = setOf("screen time", "usage", "how long", "most used apps", "phone use", "digital wellbeing"),
        requiredPermission = ToolPermissions.PACKAGE_USAGE_STATS,
    )

    override suspend fun execute(args: ToolArgs, context: ToolContext): ToolResult {
        val period = when (val r = ArgCoerce.string(args, "period")) {
            is ArgResult.Present -> r.value.trim().lowercase().ifEmpty { "today" }
            else -> "today"
        }
        if (period !in PERIODS) {
            return invalidArgument("period must be one of ${PERIODS.joinToString(", ")}, but was \"${ArgCoerce.ellipsize(period, 24)}\".")
        }
        if (!grant.isGranted(ToolPermissions.USAGE_ACCESS)) {
            return specialAccessRefusal(
                appContext,
                "device.screen_time",
                ToolPermissions.USAGE_ACCESS,
                Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS),
            )
        }
        if (context.signal.isCancelled()) return cancelledBefore("device.screen_time")
        return guarded("device.screen_time") { report(period) }
    }

    private fun report(period: String): ToolResult {
        val manager = appContext.getSystemService(UsageStatsManager::class.java)
            ?: return ToolResult(
                success = false,
                observation = "This phone's usage statistics are not available, so nothing was read.",
                error = ToolError.Unavailable("no usage stats service"),
            )
        val now = System.currentTimeMillis()
        val (begin, end, label) = when (period) {
            "yesterday" -> Triple(dayStart(now, -1), dayStart(now, 0), "yesterday")
            "week" -> Triple(dayStart(now, -6), now, "the last 7 days")
            else -> Triple(dayStart(now, 0), now, "today")
        }
        val rows = manager.queryAndAggregateUsageStats(begin, end).values
            .filter { it.totalTimeInForeground > 0 }
            .sortedByDescending { it.totalTimeInForeground }
        if (rows.isEmpty()) {
            return ToolResult(success = true, observation = "No app use was recorded for $label.")
        }

        val total = rows.sumOf { it.totalTimeInForeground }
        val pm = appContext.packageManager
        val top = rows.take(TOP_APPS).joinToString("; ") { stat ->
            "${appLabel(pm, stat.packageName)} ${minutesText(stat.totalTimeInForeground)}"
        }
        return ToolResult(
            success = true,
            observation = "Screen time for $label: ${minutesText(total)} in all. Most used: $top.",
            data = buildJsonObject { put("total_minutes", total / 60_000) },
        )
    }

    private fun appLabel(pm: PackageManager, pkg: String): String = try {
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        pkg
    }

    /** "1 h 5 min" or "42 min": the phone's own units, not raw milliseconds. */
    private fun minutesText(millis: Long): String {
        val minutes = millis / 60_000
        return if (minutes >= 60) "${minutes / 60} h ${minutes % 60} min" else "$minutes min"
    }

    /** Local midnight, [offsetDays] from today. Calendar keeps this right across a daylight-saving change. */
    private fun dayStart(now: Long, offsetDays: Int): Long = Calendar.getInstance().run {
        timeInMillis = now
        add(Calendar.DAY_OF_MONTH, offsetDays)
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        timeInMillis
    }

    private companion object {
        val PERIODS: List<String> = listOf("today", "yesterday", "week")
    }
}
