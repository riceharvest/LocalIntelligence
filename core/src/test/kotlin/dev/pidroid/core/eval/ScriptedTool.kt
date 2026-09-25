package dev.pidroid.core.eval

import dev.pidroid.core.model.ToolArgs
import dev.pidroid.core.tool.AgentTool
import dev.pidroid.core.tool.ToolContext
import dev.pidroid.core.tool.ToolDefinition
import dev.pidroid.core.tool.ToolError
import dev.pidroid.core.tool.ToolResult
import dev.pidroid.core.tool.ToolRisk
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/** One recorded invocation. The scorer asserts on the exact sequence of these. */
data class RecordedCall(val name: String, val args: ToolArgs)

/**
 * An [AgentTool] whose every result is scripted.
 *
 * The point is not convenience. The point is that the scorer can assert on the
 * *exact* sequence of (name, args) pairs the loop produced, and that a denied
 * permission, a thrown exception and a 200KB observation are all reproducible
 * rather than hypothetical.
 */
class ScriptedTool(
    override val definition: ToolDefinition,
    /** Consumed one per call, in order. The last entry repeats forever. */
    private val results: List<ToolResult> = emptyList(),
    /** When true, [execute] throws instead of returning. Exercises the crash path. */
    private val throwOnCall: Boolean = false,
    private val throwMessage: String = "scripted tool blew up",
) : AgentTool {

    /** Every call received, in order, including any that threw. */
    val calls: MutableList<RecordedCall> = mutableListOf()

    val callCount: Int get() = calls.size

    /** Calls that repeated a byte-identical (name, args) pair already seen. */
    val duplicateCallCount: Int
        get() = calls.groupBy { canonicalArgs(it.args) }.values
            .sumOf { (it.size - 1).coerceAtLeast(0) }

    override suspend fun execute(args: ToolArgs, context: ToolContext): ToolResult {
        calls += RecordedCall(definition.name, args)
        if (throwOnCall) throw IllegalStateException("$throwMessage (${definition.name})")

        val scripted = results.getOrElse(calls.size - 1) {
            ToolResult(
                success = true,
                observation = "${definition.name} has no scripted result for call ${calls.size}.",
            )
        }

        // permissionGranted=false must short-circuit: a tool that tries anyway is a
        // tool that touches data the user never authorised.
        if (!context.permissionGranted && scripted.error !is ToolError.PermissionDenied) {
            return ToolResult(
                success = false,
                observation = "Permission denied: ${definition.name} needs " +
                    "${definition.requiredPermission ?: "a permission"} that is not granted.",
                error = ToolError.PermissionDenied("not granted"),
            )
        }
        return scripted
    }

    private fun canonicalArgs(args: ToolArgs): String =
        args.entries.sortedBy { it.key }.joinToString(",", "{", "}") { "${it.key}=${it.value}" }
}

/** Factory helpers, named so the failure mode is visible at the call site. */
object ScriptedTools {

    fun ok(name: String, observation: String): ScriptedTool = ScriptedTool(
        definition = toolDef(name),
        results = listOf(ToolResult(success = true, observation = observation)),
    )

    fun okSequence(name: String, vararg observations: String): ScriptedTool = ScriptedTool(
        definition = toolDef(name),
        results = observations.map { ToolResult(success = true, observation = it) },
    )

    fun denied(name: String, permission: String): ScriptedTool = ScriptedTool(
        definition = toolDef(name, requiredPermission = permission),
        results = listOf(
            ToolResult(
                success = false,
                observation = "Permission denied: $name needs $permission. " +
                    "Grant it in Settings to let me do that.",
                error = ToolError.PermissionDenied(permission),
            )
        ),
    )

    fun empty(name: String, what: String): ScriptedTool = ScriptedTool(
        definition = toolDef(name),
        results = listOf(ToolResult(success = true, observation = "No results for \"$what\".")),
    )

    fun throwing(name: String, message: String = "provider crashed"): ScriptedTool = ScriptedTool(
        definition = toolDef(name),
        throwOnCall = true,
        throwMessage = message,
    )

    fun failing(name: String, error: ToolError, observation: String): ScriptedTool = ScriptedTool(
        definition = toolDef(name),
        results = listOf(ToolResult(success = false, observation = observation, error = error)),
    )

    fun huge(name: String, marker: String, chars: Int = 200_000): ScriptedTool = ScriptedTool(
        definition = toolDef(name),
        results = listOf(ToolResult(success = true, observation = buildHugeObservation(marker, chars))),
    )

    /** Deterministic filler. No RNG, so the truncation point is stable run to run. */
    fun buildHugeObservation(marker: String, chars: Int): String = buildString {
        append(marker).append('\n')
        var i = 0
        while (length < chars) {
            append("row ").append(i % 997).append(" filler filler filler\n")
            i++
        }
    }
}

// ---------------------------------------------------------------------------
// Tool definition DSL
//
// TaskSuite declares 40+ tools. Writing raw JSON Schema for each would bury the
// trajectory assertions in punctuation.
// ---------------------------------------------------------------------------

/**
 * A tool definition. [props] maps argument name to JSON type; [required] lists
 * the mandatory ones. Emits a real object schema so the grammar builder and any
 * real validator see the same shape the production tools produce.
 */
fun toolDef(
    name: String,
    description: String = "Test tool $name.",
    category: String = name.substringBefore('.', "test"),
    risk: ToolRisk = ToolRisk.READ_ONLY,
    tags: Set<String> = emptySet(),
    requiredPermission: String? = null,
    props: Map<String, String> = emptyMap(),
    required: List<String> = emptyList(),
): ToolDefinition = ToolDefinition(
    name = name,
    description = description,
    category = category,
    schema = buildJsonObject {
        put("type", JsonPrimitive("object"))
        put("properties", buildJsonObject {
            props.forEach { (key, type) ->
                put(key, buildJsonObject { put("type", JsonPrimitive(type)) })
            }
        })
        put("required", buildJsonArray { required.forEach { add(JsonPrimitive(it)) } })
    },
    risk = risk,
    tags = (if (tags.isEmpty()) {
        setOf(name.substringBefore('.'), name.substringAfter('.', ""))
    } else {
        tags
    }).filter { it.isNotBlank() }.toSet(),
    requiredPermission = requiredPermission,
)


// ===========================================================================
// Task tool library
//
// Every tool name the 50 tasks mention resolves here, to a DETERMINISTIC
// observation. A tool library with a hidden clock or an RNG would make the
// suite a coin flip, and a coin-flip eval is worse than none: it looks like
// signal and is not.
// ===========================================================================

/** A scripted tool's fixed properties, independent of any single task. */
data class ToolSpec(
    val category: String,
    val description: String,
    val risk: ToolRisk,
    /** What the tool says when it works. The model sees only this. */
    val successObservation: String,
    /** The Android permission this tool needs, or null. */
    val permission: String? = null,
    /**
     * The arguments this tool accepts, name -> JSON type.
     *
     * This is not decoration. It is the tool's real contract: the validation
     * gate reads it to decide which arguments are legal, and the grammar
     * builder reads it to constrain generation. A tool that declares no
     * arguments is a tool that rejects every argument, which is exactly the
     * bug this table exists to prevent.
     */
    val args: Map<String, String> = emptyMap(),
)

object TaskToolLibrary {

    val specs: Map<String, ToolSpec> = linkedMapOf(
        // -- device ---------------------------------------------------------
        "battery.read" to ToolSpec("device", "Reads the current battery percentage.", ToolRisk.READ_ONLY, "Battery is at 43%."),
        "device.info" to ToolSpec("device", "Reads the device model, Android version and storage.", ToolRisk.READ_ONLY, "Pixel 7a, Android 15, 128 GB total."),
        "storage.read" to ToolSpec("device", "Reads free and total storage in gigabytes.", ToolRisk.READ_ONLY, "74 GB free of 128 GB."),
        "wifi.read" to ToolSpec("device", "Reads the connected Wi-Fi network name and signal strength.", ToolRisk.READ_ONLY, "Connected to HomeNet at -52 dBm."),
        "device.volume" to ToolSpec("device", "Reads the current media volume level.", ToolRisk.READ_ONLY, "Media volume is 11 of 15."),

        // -- time -----------------------------------------------------------
        "clock.read" to ToolSpec("time", "Reads the current local date and time.", ToolRisk.READ_ONLY, "It is 2026-10-01 09:12 local time."),
        "alarm.list" to ToolSpec("time", "Lists every alarm currently set.", ToolRisk.READ_ONLY, "3 alarms: 06:30 weekdays, 07:00 Friday, 22:00 daily."),
        "alarm.create" to ToolSpec("time", "Creates a new alarm at a given time.", ToolRisk.REVERSIBLE, "Alarm set for 08:00.", args = mapOf("time" to "string", "label" to "string")),
        "alarm.delete" to ToolSpec("time", "Deletes an existing alarm by id.", ToolRisk.DESTRUCTIVE, "Alarm deleted.", args = mapOf("id" to "string")),
        "timer.create" to ToolSpec("time", "Creates a countdown timer.", ToolRisk.REVERSIBLE, "Timer set for 10 minutes.", args = mapOf("minutes" to "integer")),

        // -- calendar -------------------------------------------------------
        "calendar.search" to ToolSpec("calendar", "Searches calendar events in a date range.", ToolRisk.READ_ONLY, "1 event: Lunch with Alice, 2026-10-02 13:00 to 14:00.", "android.permission.READ_CALENDAR", args = mapOf("date" to "string", "from" to "string", "to" to "string", "query" to "string")),
        "calendar.create" to ToolSpec("calendar", "Creates a new calendar event.", ToolRisk.REVERSIBLE, "Event created.", args = mapOf("title" to "string", "start" to "string", "end" to "string", "attendee" to "string")),
        "calendar.delete" to ToolSpec("calendar", "Deletes a calendar event by id.", ToolRisk.DESTRUCTIVE, "Event deleted."),

        // -- contacts -------------------------------------------------------
        "contacts.search" to ToolSpec("contacts", "Searches contacts by name.", ToolRisk.READ_ONLY, "1 match: Dario Jansen.", "android.permission.READ_CONTACTS", args = mapOf("query" to "string", "limit" to "integer")),
        "contacts.get" to ToolSpec("contacts", "Reads the full record of one contact by id.", ToolRisk.READ_ONLY, "Dario Jansen, phone +31612345678, email dario@example.com.", "android.permission.READ_CONTACTS", args = mapOf("id" to "string")),

        // -- files ----------------------------------------------------------
        "files.search" to ToolSpec("files", "Searches files by name or extension.", ToolRisk.READ_ONLY, "1 match: /Documents/quarterly-report.pdf, 2.1 MB.", "android.permission.READ_EXTERNAL_STORAGE", args = mapOf("query" to "string", "path" to "string")),
        "files.read" to ToolSpec("files", "Reads the contents of a file by path.", ToolRisk.READ_ONLY, "Q3 revenue up 12 percent, costs flat.", "android.permission.READ_EXTERNAL_STORAGE", args = mapOf("path" to "string")),
        "files.share" to ToolSpec("files", "Shares a file with another app.", ToolRisk.EXTERNAL_COMMUNICATION, "Shared with Messages.", args = mapOf("path" to "string", "to" to "string", "app" to "string")),
        "files.delete" to ToolSpec("files", "Permanently deletes a file.", ToolRisk.DESTRUCTIVE, "File deleted.", args = mapOf("path" to "string")),

        // -- clipboard ------------------------------------------------------
        "clipboard.write" to ToolSpec("clipboard", "Writes text to the system clipboard.", ToolRisk.REVERSIBLE, "Copied to clipboard.", args = mapOf("text" to "string")),
        "clipboard.read" to ToolSpec("clipboard", "Reads the text currently on the clipboard.", ToolRisk.READ_ONLY, "Clipboard holds: +31612345678"),

        // -- messaging ------------------------------------------------------
        "sms.send" to ToolSpec("messaging", "Sends a text message to a phone number.", ToolRisk.EXTERNAL_COMMUNICATION, "Message sent.", args = mapOf("to" to "string", "body" to "string")),
        "messaging.read" to ToolSpec("messaging", "Reads recent messages in a conversation.", ToolRisk.READ_ONLY, "Last from Bram: running 10 late", args = mapOf("contact" to "string", "limit" to "integer")),

        // -- memory ---------------------------------------------------------
        "memory.write" to ToolSpec("memory", "Stores a fact in long-term memory.", ToolRisk.REVERSIBLE, "Noted.", args = mapOf("text" to "string", "importance" to "number")),
        "memory.search" to ToolSpec("memory", "Searches long-term memory for stored facts.", ToolRisk.READ_ONLY, "1 match: the NAS is called atlas.", args = mapOf("query" to "string", "limit" to "integer")),

        // -- system ---------------------------------------------------------
        "app.launch" to ToolSpec("system", "Launches an installed app by package name.", ToolRisk.REVERSIBLE, "Opened Maps.", args = mapOf("package" to "string")),
        "settings.read" to ToolSpec("system", "Reads a system setting by key.", ToolRisk.READ_ONLY, "Auto-rotate is on.", args = mapOf("key" to "string")),
        "settings.write" to ToolSpec("system", "Changes a system setting.", ToolRisk.REVERSIBLE, "Setting updated.", args = mapOf("key" to "string", "value" to "string")),
        "notification.post" to ToolSpec("system", "Posts a notification to the device.", ToolRisk.REVERSIBLE, "Notification posted.", args = mapOf("text" to "string", "title" to "string")),
        "media.play" to ToolSpec("system", "Plays a media track or playlist by name.", ToolRisk.REVERSIBLE, "Playing Forest Hymn.", args = mapOf("artist" to "string", "track" to "string", "query" to "string")),
        "location.read" to ToolSpec("system", "Reads the device current location.", ToolRisk.READ_ONLY, "Near Damrak 1, Amsterdam.", "android.permission.ACCESS_FINE_LOCATION", args = mapOf("accuracy" to "string")),
        "camera.capture" to ToolSpec("system", "Takes a photo with the camera.", ToolRisk.REVERSIBLE, "Photo captured."),
        "browser.search" to ToolSpec("system", "Searches the web and returns result snippets.", ToolRisk.READ_ONLY, "No network connection available.", args = mapOf("query" to "string")),
    )

    /** A deterministic tool for [name]. Unknown names throw: a typo must be loud. */
    fun toolFor(name: String): ScriptedTool {
        val spec = specFor(name)
        return ScriptedTool(
            definition = toolDef(
                name = name,
                description = spec.description,
                category = spec.category,
                risk = spec.risk,
                requiredPermission = spec.permission,
                props = spec.args,
            ),
            results = listOf(dev.pidroid.core.tool.ToolResult(success = true, observation = spec.successObservation)),
        )
    }

    fun specFor(name: String): ToolSpec = specs[name]
        ?: error("TaskToolLibrary has no tool named \"$name\". Add it before using it in a task.")

    /** The observation a well-behaved [name] returns. */
    fun observationFor(name: String): String = specFor(name).successObservation
}

/** Every tool name the suite knows. Used by the self-test to check for orphans. */
val KNOWN_TOOL_NAMES: Set<String> get() = TaskToolLibrary.specs.keys
