package dev.localintelligence.android.tools.alarm

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import dev.localintelligence.android.tools.device.ArgCoerce
import dev.localintelligence.android.tools.device.ArgResult
import dev.localintelligence.core.model.ObservationOrigin
import dev.localintelligence.core.model.ToolArgs
import dev.localintelligence.core.tool.AgentTool
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

// =====================================================================================
// timer.set: hands a countdown to the phone's clock app via ACTION_SET_TIMER.
//
// Unlike alarm.create this does not go through AlarmManager, so it needs no
// SCHEDULE_EXACT_ALARM grant and nothing is tracked in AlarmRegistry: the clock app
// owns the timer and its UI. EXTRA_SKIP_UI keeps the clock app from opening, so the
// timer starts in the background. Nothing here can confirm the clock app actually
// started it; ActivityNotFoundException is the only failure we can see.
// =====================================================================================

/** Longest timer the clock apps reliably accept: 24 hours. */
private const val MAX_TIMER_SECONDS = 86_400
private const val MAX_TIMER_LABEL_CHARS = 60

private val TIMER_SCHEMA: ToolArgs = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") {
        putJsonObject("seconds") {
            put("type", "integer")
            put("minimum", 1)
            put("maximum", MAX_TIMER_SECONDS)
            put("description", "Length of the timer in seconds, 1 to 86400. Ten minutes is 600.")
        }
        putJsonObject("label") {
            put("type", "string")
            put("description", "Short name for the timer, e.g. \"pasta\".")
        }
    }
    putJsonArray("required") { add("seconds") }
}

class TimerSetTool(private val appContext: Context) : AgentTool {

    override val definition = ToolDefinition(
        name = "timer.set",
        description = "Start a countdown timer in the phone's clock app for a number of seconds. " +
            "Use when the user says set a timer, or a countdown for some minutes.",
        category = "alarm",
        schema = TIMER_SCHEMA,
        risk = ToolRisk.REVERSIBLE,
        observationOrigin = ObservationOrigin.LOCAL,
        tags = setOf(
            "timer", "countdown", "set a timer", "timer for", "minute timer", "time me",
            // Dutch: the scorer reads only the tool's English text, so a Dutch request needs these to count as asked.
            "zet een timer", "minuten", "kookwekker",
        ),
        // ACTION_SET_TIMER needs SET_ALARM, a normal permission the manifest declares
        // (granted at install), so there is nothing to ask the user for.
        requiredPermission = null,
    )

    override suspend fun execute(args: ToolArgs, context: ToolContext): ToolResult {
        val seconds = when (val parsed = ArgCoerce.int(args, "seconds")) {
            is ArgResult.Present -> parsed.value
            is ArgResult.Missing -> return invalid(
                "seconds is required. Send the length in seconds, for example {\"seconds\": 600} for ten minutes.",
            )
            is ArgResult.Unusable -> return invalid("seconds must be a whole number of seconds, but was ${parsed.got}.")
        }
        if (seconds !in 1..MAX_TIMER_SECONDS) {
            return invalid("seconds must be from 1 to $MAX_TIMER_SECONDS, but was $seconds. Send the length in seconds.")
        }

        val label = (ArgCoerce.string(args, "label") as? ArgResult.Present)?.value
            ?.trim()
            ?.replace(Regex("\\s+"), " ")
            ?.take(MAX_TIMER_LABEL_CHARS)
            ?.takeIf { it.isNotEmpty() }

        if (context.signal.isCancelled()) {
            return ToolResult(
                success = false,
                observation = "Cancelled before the timer could be set.",
                error = ToolError.Cancelled("cancelled before timer.set"),
            )
        }

        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (label != null) intent.putExtra(AlarmClock.EXTRA_MESSAGE, label)

        return try {
            appContext.startActivity(intent)
            val named = if (label != null) " \"$label\"" else ""
            ToolResult(
                success = true,
                observation = "Set a${named} timer for ${describeDuration(seconds)} in the clock app. " +
                    "It runs there in the background.",
                data = buildJsonObject {
                    put("seconds", seconds)
                    if (label != null) put("label", label)
                },
            )
        } catch (e: ActivityNotFoundException) {
            noClockApp()
        } catch (e: SecurityException) {
            // Background starts can be refused by the platform; say so instead of claiming success.
            noClockApp()
        }
    }

    private fun noClockApp(): ToolResult = ToolResult(
        success = false,
        observation = "No clock app on this phone accepted the timer, so none was set. " +
            "Tell the user to set the timer in their clock app.",
        error = ToolError.Unavailable("no app handles ACTION_SET_TIMER"),
    )

    private fun invalid(message: String): ToolResult = ToolResult(
        success = false,
        observation = message,
        error = ToolError.InvalidArguments(message),
    )
}

/** "90 seconds", "10 minutes", "1 hour 5 minutes": the words a person would say back. */
internal fun describeDuration(seconds: Int): String {
    val hours = seconds / 3_600
    val minutes = (seconds % 3_600) / 60
    val secs = seconds % 60
    val parts = buildList {
        if (hours > 0) add("$hours hour${if (hours == 1) "" else "s"}")
        if (minutes > 0) add("$minutes minute${if (minutes == 1) "" else "s"}")
        if (secs > 0 || isEmpty()) add("$secs second${if (secs == 1) "" else "s"}")
    }
    return parts.joinToString(" ")
}
