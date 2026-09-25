package dev.localintelligence.core.eval

import dev.localintelligence.core.model.ToolArgs
import dev.localintelligence.core.tool.AgentTool
import dev.localintelligence.core.tool.ToolContext
import dev.localintelligence.core.tool.ToolDefinition
import dev.localintelligence.core.tool.ToolError
import dev.localintelligence.core.tool.ToolResult
import dev.localintelligence.core.tool.ToolRisk
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

// ===========================================================================
// AndroidToolStubs.kt — the real Android tool set, as JVM stubs.
//
// WHY STUBS AND NOT THE REAL CLASSES
// ==================================
//
// The real tools live in `:android` at
// `android/src/main/kotlin/dev/localintelligence/android/tools/<pkg>/<X>.kt`.
// `:core` is a pure JVM module with zero Android dependencies, and it must stay
// that way — CI greps this source tree for the platform import prefix and fails
// the build on a hit, so even mentioning it here in a comment would break the
// build. It is also a compile-time violation to reference `:android` from
// `:core`.
//
// So this file is the contract mirror: for every tool the Android layer is
// building, a stub carrying the EXACT name, category, risk, description and
// tags the real class carries, plus a scripted observation so the loop can
// actually execute it.
//
// The value is not that these stubs run. The value is that they make the real
// tool set ASSERTABLE before the real tool set exists. Every coherence rule in
// docs/tool-contract.md and every retrieval question in docs/evals.md can be
// asked of this file today, in CI, in seconds, with no emulator — so the tool
// authors get their tag and description defects back as a failing test with a
// line number, instead of as a confused 3B model at 2am.
//
// KEEP IN SYNC. When a real tool's name, description, tags, risk or schema
// changes, this file must change in the same commit. That coupling is the
// entire point; a stub that drifts is worse than no stub, because the benchmark
// then measures a tool set that does not exist.
// ===========================================================================

/**
 * The full declaration of one Android tool: everything the coherence benchmark
 * asserts on, in one value.
 *
 * [mirrors] is the real file this stands in for. It is a value rather than only
 * a comment so the report can print the mapping — a tool author reading the
 * report can find their file without grepping.
 */
data class AndroidToolSpec(
    val name: String,
    val category: String,
    /** One sentence, imperative, verb-first. */
    val description: String,
    val risk: ToolRisk,
    /** 4-8 lowercase retrieval keywords. */
    val tags: Set<String>,
    /** Argument name -> JSON type. */
    val args: Map<String, String> = emptyMap(),
    /** Subset of [args] that is mandatory. */
    val required: List<String> = emptyList(),
    val requiredPermission: String? = null,
    /** The canned observation. This is the only thing a model would ever see. */
    val observation: String,
    /** Real source file, relative to `android/src/main/kotlin/`. */
    val mirrors: String,
)

/**
 * A scripted [AgentTool] over one [AndroidToolSpec].
 *
 * It behaves like a well-behaved real tool: it never throws, it returns
 * `permission_denied` when the permission is absent rather than attempting the
 * operation, and its observation is already inside the budget so the loop's
 * truncation path is never what is under test here.
 */
class AndroidStubTool(
    override val definition: ToolDefinition,
    val spec: AndroidToolSpec,
) : AgentTool {

    /** Every call this stub received, in order. Lets a test assert execution. */
    val calls: MutableList<ToolArgs> = mutableListOf()

    override suspend fun execute(args: ToolArgs, context: ToolContext): ToolResult {
        calls += args
        if (!context.permissionGranted) {
            return ToolResult(
                success = false,
                observation = "Permission denied: ${spec.name} needs " +
                    "${spec.requiredPermission ?: "a permission"} that is not granted. " +
                    "Grant it in Settings to let me do that.",
                error = ToolError.PermissionDenied(spec.requiredPermission ?: "not granted"),
            )
        }
        return ToolResult(success = true, observation = spec.observation)
    }
}

/** The 25 tools the Android layer is building, in registry order. */
object AndroidToolStubs {

    // -- device -------------------------------------------------------------
    // mirrors android/.../tools/device/BatteryTool.kt — keep in sync
    private val BATTERY = AndroidToolSpec(
        name = "device.battery",
        category = "device",
        description = "Reads the current battery level as a percentage.",
        risk = ToolRisk.READ_ONLY,
        tags = setOf("battery", "charge", "power", "level", "phone", "dying", "low", "percent"),
        observation = "Battery is at 43% and charging.",
        mirrors = "dev/localintelligence/android/tools/device/BatteryTool.kt",
    )

    // mirrors android/.../tools/device/InfoTool.kt — keep in sync
    private val INFO = AndroidToolSpec(
        name = "device.info",
        category = "device",
        description = "Reads the device model, Android version and free storage.",
        risk = ToolRisk.READ_ONLY,
        tags = setOf("device", "model", "android", "version", "storage", "room", "space", "specifications"),
        observation = "Pixel 7a, Android 15, 74 GB free of 128 GB.",
        mirrors = "dev/localintelligence/android/tools/device/InfoTool.kt",
    )

    // mirrors android/.../tools/device/VibrateTool.kt — keep in sync
    private val VIBRATE = AndroidToolSpec(
        name = "device.vibrate",
        category = "device",
        description = "Vibrates the device for a given number of milliseconds.",
        risk = ToolRisk.REVERSIBLE,
        tags = setOf("vibrate", "buzz", "haptic", "shake", "silent", "alert"),
        args = mapOf("duration_ms" to "integer"),
        required = listOf("duration_ms"),
        observation = "Vibrated for 500 ms.",
        mirrors = "dev/localintelligence/android/tools/device/VibrateTool.kt",
    )

    // mirrors android/.../tools/device/OpenSettingsTool.kt — keep in sync
    private val OPEN_SETTINGS = AndroidToolSpec(
        name = "device.open_settings",
        category = "device",
        description = "Opens a named Android settings screen on this device.",
        risk = ToolRisk.REVERSIBLE,
        tags = setOf("settings", "open", "preferences", "configure", "system", "screen", "toggle", "turn on"),
        args = mapOf("screen" to "string"),
        required = listOf("screen"),
        observation = "Opened the Bluetooth settings screen.",
        mirrors = "dev/localintelligence/android/tools/device/OpenSettingsTool.kt",
    )

    // -- clipboard ----------------------------------------------------------
    // mirrors android/.../tools/clipboard/WriteClipboardTool.kt — keep in sync
    private val CLIPBOARD_WRITE = AndroidToolSpec(
        name = "clipboard.write",
        category = "clipboard",
        description = "Writes text to the system clipboard so the user can paste it.",
        risk = ToolRisk.REVERSIBLE,
        tags = setOf("clipboard", "copy", "paste", "cut", "text"),
        args = mapOf("text" to "string"),
        required = listOf("text"),
        observation = "Copied 18 characters to the clipboard.",
        mirrors = "dev/localintelligence/android/tools/clipboard/WriteClipboardTool.kt",
    )

    // mirrors android/.../tools/clipboard/ReadClipboardTool.kt — keep in sync
    private val CLIPBOARD_READ = AndroidToolSpec(
        name = "clipboard.read",
        category = "clipboard",
        description = "Reads the text currently held on the system clipboard.",
        risk = ToolRisk.READ_ONLY,
        tags = setOf("clipboard", "read", "copy", "copied", "paste", "pasteboard", "content"),
        observation = "Clipboard holds: +31612345678",
        mirrors = "dev/localintelligence/android/tools/clipboard/ReadClipboardTool.kt",
    )

    // -- alarm --------------------------------------------------------------
    // mirrors android/.../tools/alarm/CreateAlarmTool.kt — keep in sync
    private val ALARM_CREATE = AndroidToolSpec(
        name = "alarm.create",
        category = "alarm",
        description = "Creates a new alarm at a given time, optionally repeating daily.",
        risk = ToolRisk.REVERSIBLE,
        tags = setOf("alarm", "set", "wake", "morning", "reminder", "wakeup"),
        args = mapOf("time" to "string", "label" to "string", "repeat" to "string"),
        required = listOf("time"),
        observation = "Alarm set for 08:30, weekdays only.",
        mirrors = "dev/localintelligence/android/tools/alarm/CreateAlarmTool.kt",
    )

    // mirrors android/.../tools/alarm/ListAlarmsTool.kt — keep in sync
    private val ALARM_LIST = AndroidToolSpec(
        name = "alarm.list",
        category = "alarm",
        description = "Lists every alarm currently set, with its time and repeat days.",
        risk = ToolRisk.READ_ONLY,
        tags = setOf("alarm", "list", "show", "which", "active", "alarms"),
        observation = "3 alarms: 06:30 weekdays, 07:00 Friday, 22:00 daily.",
        mirrors = "dev/localintelligence/android/tools/alarm/ListAlarmsTool.kt",
    )

    // mirrors android/.../tools/alarm/CancelAlarmTool.kt — keep in sync
    private val ALARM_CANCEL = AndroidToolSpec(
        name = "alarm.cancel",
        category = "alarm",
        description = "Cancels an existing alarm; it is removed and will not ring again.",
        risk = ToolRisk.DESTRUCTIVE,
        tags = setOf("alarm", "cancel", "delete", "remove", "kill", "silence", "late"),
        args = mapOf("id" to "string"),
        required = listOf("id"),
        observation = "Alarm a-3 (22:00) cancelled.",
        mirrors = "dev/localintelligence/android/tools/alarm/CancelAlarmTool.kt",
    )

    // -- calendar -----------------------------------------------------------
    // mirrors android/.../tools/calendar/SearchCalendarTool.kt — keep in sync
    private val CALENDAR_SEARCH = AndroidToolSpec(
        name = "calendar.search",
        category = "calendar",
        description = "Searches calendar events in a date range or by matching text.",
        risk = ToolRisk.READ_ONLY,
        tags = setOf("calendar", "event", "appointment", "meeting", "scheduled", "seeing", "week", "free"),
        args = mapOf("from" to "string", "to" to "string", "query" to "string"),
        requiredPermission = "android.permission.READ_CALENDAR",
        observation = "2 events: Lunch with Alice 13:00, Standup 09:15.",
        mirrors = "dev/localintelligence/android/tools/calendar/SearchCalendarTool.kt",
    )

    // mirrors android/.../tools/calendar/CreateEventTool.kt — keep in sync
    private val CALENDAR_CREATE = AndroidToolSpec(
        name = "calendar.create",
        category = "calendar",
        description = "Creates a new calendar event at a given start time.",
        risk = ToolRisk.REVERSIBLE,
        tags = setOf("calendar", "event", "appointment", "meeting", "book", "invite", "slot"),
        args = mapOf("title" to "string", "starts_at" to "string", "duration_minutes" to "integer"),
        required = listOf("title", "starts_at"),
        requiredPermission = "android.permission.WRITE_CALENDAR",
        observation = "Created 'Dentist' on 2026-10-02 at 10:00.",
        mirrors = "dev/localintelligence/android/tools/calendar/CreateEventTool.kt",
    )

    // -- contacts -----------------------------------------------------------
    // mirrors android/.../tools/contacts/SearchContactsTool.kt — keep in sync
    private val CONTACTS_SEARCH = AndroidToolSpec(
        name = "contacts.search",
        category = "contacts",
        description = "Searches contacts by name and returns matching phone numbers.",
        risk = ToolRisk.READ_ONLY,
        tags = setOf("contact", "person", "people", "phone", "number", "name"),
        args = mapOf("query" to "string", "limit" to "integer"),
        required = listOf("query"),
        requiredPermission = "android.permission.READ_CONTACTS",
        observation = "1 match: Dario Jansen, +31612345678.",
        mirrors = "dev/localintelligence/android/tools/contacts/SearchContactsTool.kt",
    )

    // mirrors android/.../tools/contacts/GetContactTool.kt — keep in sync
    private val CONTACTS_GET = AndroidToolSpec(
        name = "contacts.get",
        category = "contacts",
        description = "Reads the full record of one contact, including email and phone.",
        risk = ToolRisk.READ_ONLY,
        tags = setOf("contact", "record", "details", "profile", "card", "vcard"),
        args = mapOf("id" to "string"),
        required = listOf("id"),
        requiredPermission = "android.permission.READ_CONTACTS",
        observation = "Dario Jansen, +31612345678, dario@example.com.",
        mirrors = "dev/localintelligence/android/tools/contacts/GetContactTool.kt",
    )

    // -- files --------------------------------------------------------------
    // mirrors android/.../tools/files/ListFilesTool.kt — keep in sync
    private val FILES_LIST = AndroidToolSpec(
        name = "files.list",
        category = "files",
        description = "Lists the files and folders in a directory on device storage.",
        risk = ToolRisk.READ_ONLY,
        tags = setOf("file", "folder", "directory", "browse", "documents", "storage"),
        args = mapOf("path" to "string"),
        requiredPermission = "android.permission.READ_EXTERNAL_STORAGE",
        observation = "/Documents: invoice.pdf, notes.txt, photos/ (3 items).",
        mirrors = "dev/localintelligence/android/tools/files/ListFilesTool.kt",
    )

    // mirrors android/.../tools/files/SearchFilesTool.kt — keep in sync
    private val FILES_SEARCH = AndroidToolSpec(
        name = "files.search",
        category = "files",
        description = "Searches files by name or extension and returns matching paths.",
        risk = ToolRisk.READ_ONLY,
        tags = setOf("file", "search", "find", "name", "extension", "pdf", "document", "download"),
        args = mapOf("query" to "string", "path" to "string"),
        required = listOf("query"),
        requiredPermission = "android.permission.READ_EXTERNAL_STORAGE",
        observation = "1 match: /Documents/invoice.pdf, 2.1 MB, modified yesterday.",
        mirrors = "dev/localintelligence/android/tools/files/SearchFilesTool.kt",
    )

    // mirrors android/.../tools/files/ReadTextTool.kt — keep in sync
    private val FILES_READ_TEXT = AndroidToolSpec(
        name = "files.read_text",
        category = "files",
        description = "Reads the plain-text contents of a file at a given path.",
        risk = ToolRisk.READ_ONLY,
        tags = setOf("file", "read", "text", "content", "contents", "open"),
        args = mapOf("path" to "string"),
        required = listOf("path"),
        requiredPermission = "android.permission.READ_EXTERNAL_STORAGE",
        observation = "invoice.pdf: Invoice 2291, total EUR 1.240,00, due 2026-10-30.",
        mirrors = "dev/localintelligence/android/tools/files/ReadTextTool.kt",
    )

    // mirrors android/.../tools/files/WriteTextTool.kt — keep in sync
    private val FILES_WRITE_TEXT = AndroidToolSpec(
        name = "files.write_text",
        category = "files",
        description = "Writes plain text to a file, creating it or overwriting it.",
        risk = ToolRisk.REVERSIBLE,
        tags = setOf("file", "write", "save", "create", "note", "text"),
        args = mapOf("path" to "string", "text" to "string"),
        required = listOf("path", "text"),
        requiredPermission = "android.permission.WRITE_EXTERNAL_STORAGE",
        observation = "Wrote 96 characters to /Documents/weather.txt.",
        mirrors = "dev/localintelligence/android/tools/files/WriteTextTool.kt",
    )

    // mirrors android/.../tools/files/DeleteFileTool.kt — keep in sync
    private val FILES_DELETE = AndroidToolSpec(
        name = "files.delete",
        category = "files",
        description = "Deletes a file permanently; it is not moved to a trash folder.",
        risk = ToolRisk.DESTRUCTIVE,
        tags = setOf("file", "delete", "remove", "trash", "get rid", "bin", "away"),
        args = mapOf("path" to "string"),
        required = listOf("path"),
        requiredPermission = "android.permission.WRITE_EXTERNAL_STORAGE",
        observation = "Deleted /Documents/old-draft.pdf.",
        mirrors = "dev/localintelligence/android/tools/files/DeleteFileTool.kt",
    )

    // -- apps ---------------------------------------------------------------
    // mirrors android/.../tools/apps/ListAppsTool.kt — keep in sync
    private val APPS_LIST = AndroidToolSpec(
        name = "apps.list",
        category = "apps",
        description = "Lists the apps installed on this device, filtered by an optional query.",
        risk = ToolRisk.READ_ONLY,
        tags = setOf("app", "installed", "application", "program", "software", "list"),
        args = mapOf("query" to "string"),
        observation = "3 matching apps: Spotify, Signal, Settings.",
        mirrors = "dev/localintelligence/android/tools/apps/ListAppsTool.kt",
    )

    // mirrors android/.../tools/apps/OpenAppTool.kt — keep in sync
    private val APPS_OPEN = AndroidToolSpec(
        name = "apps.open",
        category = "apps",
        description = "Launches an installed app by its package or display name.",
        risk = ToolRisk.REVERSIBLE,
        tags = setOf("app", "open", "launch", "start", "run", "get", "up"),
        args = mapOf("name" to "string"),
        required = listOf("name"),
        observation = "Opened Spotify.",
        mirrors = "dev/localintelligence/android/tools/apps/OpenAppTool.kt",
    )

    // mirrors android/.../tools/apps/ShareTool.kt — keep in sync
    private val APPS_SHARE = AndroidToolSpec(
        name = "apps.share",
        category = "apps",
        description = "Shares a file or text with another app, which can then send it on.",
        risk = ToolRisk.EXTERNAL_COMMUNICATION,
        tags = setOf("share", "send", "attach", "forward", "export"),
        args = mapOf("path" to "string", "text" to "string", "app" to "string"),
        requiredPermission = null,
        observation = "Shared /Documents/invoice.pdf with the share sheet.",
        mirrors = "dev/localintelligence/android/tools/apps/ShareTool.kt",
    )

    // -- notifications ------------------------------------------------------
    // mirrors android/.../tools/notifications/ListNotificationsTool.kt — keep in sync
    private val NOTIFICATIONS_LIST = AndroidToolSpec(
        name = "notifications.list",
        category = "notifications",
        description = "Lists the notifications currently posted, with their app and text.",
        risk = ToolRisk.READ_ONLY,
        tags = setOf("notification", "alert", "shade", "pending", "heads"),
        observation = "2 notifications: Signal from Bram, Gmail shipping update.",
        mirrors = "dev/localintelligence/android/tools/notifications/ListNotificationsTool.kt",
    )

    // mirrors android/.../tools/notifications/ReplyNotificationTool.kt — keep in sync
    private val NOTIFICATIONS_REPLY = AndroidToolSpec(
        name = "notifications.reply",
        category = "notifications",
        description = "Sends a reply to a notification thread; the recipient receives it.",
        risk = ToolRisk.EXTERNAL_COMMUNICATION,
        tags = setOf("reply", "respond", "answer", "message", "ping", "back"),
        args = mapOf("id" to "string", "text" to "string"),
        required = listOf("id", "text"),
        observation = "Replied to notification n-1.",
        mirrors = "dev/localintelligence/android/tools/notifications/ReplyNotificationTool.kt",
    )

    // mirrors android/.../tools/notifications/DismissNotificationTool.kt — keep in sync
    private val NOTIFICATIONS_DISMISS = AndroidToolSpec(
        name = "notifications.dismiss",
        category = "notifications",
        description = "Clears a notification from the shade; it will not be shown again.",
        risk = ToolRisk.REVERSIBLE,
        tags = setOf("notification", "dismiss", "clear", "hide", "swipe"),
        args = mapOf("id" to "string"),
        required = listOf("id"),
        observation = "Dismissed notification n-2.",
        mirrors = "dev/localintelligence/android/tools/notifications/DismissNotificationTool.kt",
    )

    // -- web ----------------------------------------------------------------
    // mirrors android/.../tools/web/FetchTool.kt — keep in sync
    private val WEB_FETCH = AndroidToolSpec(
        name = "web.fetch",
        category = "web",
        description = "Fetches a URL and returns the readable text of the page.",
        risk = ToolRisk.READ_ONLY,
        tags = setOf("web", "url", "internet", "page", "browse", "online", "news", "latest"),
        args = mapOf("url" to "string"),
        required = listOf("url"),
        observation = "knmi.nl: Amsterdam 12 C, light rain until 16:00.",
        mirrors = "dev/localintelligence/android/tools/web/FetchTool.kt",
    )

    /** Registry order: category by category, alphabetical inside each. */
    val specs: List<AndroidToolSpec> = listOf(
        BATTERY, INFO, VIBRATE, OPEN_SETTINGS,
        CLIPBOARD_WRITE, CLIPBOARD_READ,
        ALARM_CREATE, ALARM_LIST, ALARM_CANCEL,
        CALENDAR_SEARCH, CALENDAR_CREATE,
        CONTACTS_SEARCH, CONTACTS_GET,
        FILES_LIST, FILES_SEARCH, FILES_READ_TEXT, FILES_WRITE_TEXT, FILES_DELETE,
        APPS_LIST, APPS_OPEN, APPS_SHARE,
        NOTIFICATIONS_LIST, NOTIFICATIONS_REPLY, NOTIFICATIONS_DISMISS,
        WEB_FETCH,
    )

    /** The nine categories the Android tool layer owns. */
    val categories: Set<String> = setOf(
        "device", "clipboard", "alarm", "calendar", "contacts",
        "files", "apps", "notifications", "web",
    )

    fun specFor(name: String): AndroidToolSpec =
        specs.firstOrNull { it.name == name }
            ?: error("AndroidToolStubs has no tool named \"$name\".")

    /** A [ToolDefinition] built from [spec], with a real object JSON Schema. */
    fun definitionOf(spec: AndroidToolSpec): ToolDefinition = ToolDefinition(
        name = spec.name,
        description = spec.description,
        category = spec.category,
        schema = schemaOf(spec),
        risk = spec.risk,
        tags = spec.tags,
        requiredPermission = spec.requiredPermission,
    )

    /**
     * Builds the schema the real tool must build.
     *
     * The shape is fixed by `docs/tool-contract.md` and by
     * `ToolCallValidator`, which reads `schema["properties"]` to decide which
     * arguments are legal. A schema without `properties` rejects every
     * argument of every call, so this emits `type`, `properties` and `required`
     * unconditionally — including for a zero-argument tool, where `properties`
     * is an empty object and `required` an empty array.
     */
    fun schemaOf(spec: AndroidToolSpec): JsonObject = buildJsonObject {
        put("type", JsonPrimitive("object"))
        put("properties", buildJsonObject {
            spec.args.forEach { (name, type) ->
                put(name, buildJsonObject { put("type", JsonPrimitive(type)) })
            }
        })
        put("required", buildJsonArray {
            spec.required.forEach { add(JsonPrimitive(it)) }
        })
    }

    /** One stub per spec, in registry order. */
    fun tools(): List<AndroidStubTool> = specs.map { spec ->
        AndroidStubTool(definition = definitionOf(spec), spec = spec)
    }
}
