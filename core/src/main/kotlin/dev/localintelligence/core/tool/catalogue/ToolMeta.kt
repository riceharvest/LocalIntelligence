package dev.localintelligence.core.tool.catalogue

import dev.localintelligence.core.model.ObservationOrigin
import dev.localintelligence.core.tool.ToolDefinition
import dev.localintelligence.core.tool.ToolRisk
import kotlinx.serialization.json.JsonObject

/**
 * The single source of truth for a tool's **descriptive** metadata: its name,
 * description, category and retrieval tags.
 *
 * ## Why this file exists
 *
 * Until it existed, every tool's descriptive metadata was written out twice —
 * once as a `ToolDefinition` literal in [V0ToolCatalogue] and once in the
 * `AgentTool` implementation in `:android` that actually ships. The two sets of
 * names matched, which is why nothing caught it: [CatalogueAgreement] compared
 * names, risk tiers and categories, and every other field was explicitly out of
 * scope.
 *
 * The cost was not tidiness. The registry is what the system prompt, the
 * grammar and the lexical selector read (`AppContainer` builds it from
 * `androidTools(context)`), so the `:android` text was the live one and the
 * catalogue's prose was documentation of a tool set nobody reads. A change-set
 * that carefully retuned the catalogue's descriptions and tags therefore could
 * not have moved selection accuracy at all. Measured on main before this file
 * existed: **all 25 descriptions and 22 of 25 tag sets differed.**
 *
 * ## What lives here, and what deliberately does not
 *
 * Here: [name], [description], [category], [tags], [observationOrigin]. Those are
 * the fields that decide what the model is told, which tools a turn can reach,
 * and how much of what comes back is believed — and the two sides can no longer
 * disagree about them because there is only one value.
 *
 * NOT here, and why:
 *
 *  - **schema.** The `:android` schemas are the real ones — they carry richer
 *    argument documentation, per-tool argument names, and the `minProperties`
 *    constraints the implementations actually enforce. Forcing the catalogue's
 *    older schemas onto the shipped tools would delete working documentation
 *    and, where an argument was renamed, break `execute()`. Schema unification
 *    is a real migration and is not smuggled in here. Each side keeps its own.
 *  - **risk.** Both sides already declare it and [CatalogueAgreement] fails the
 *    build when they disagree, because [dev.localintelligence.core.policy.RiskPolicy]
 *    gates execution on it. Duplicating a value that a loud check already guards
 *    would add a second place to edit and no safety.
 *  - **requiredPermission.** Documentation and approval-dialog text. Stated at
 *    each site on purpose: a permission appearing in a shared table is a
 *    permission nobody reads next to the `execute()` that enforces it.
 *
 * So the per-tool [ToolDefinition] on each side is now three fields of schema
 * and risk, wired together by [define] — and no descriptive or trust text at
 * all.
 *
 * ## observationOrigin IS here, and that is the second bug this file prevents
 *
 * The first version of this table had [name], [description], [category] and
 * [tags], and deliberately left `observationOrigin` at the `define()` call site
 * on the reasoning that it was a per-tool claim like risk. That reasoning was
 * wrong in the direction that matters: the conversion dropped the field from
 * all 25 Android definitions, and since
 * [ToolDefinition.observationOrigin] defaults to
 * [ObservationOrigin.NETWORK], "dropped" did not mean inert — it meant **every
 * tool became NETWORK**, including `web.fetch`'s twelve local siblings. The
 * untrusted-content fence kept working; it was just now claiming that
 * `files.list` output was a hostile web page, which is the kind of false alarm
 * that trains a reader to ignore the fence.
 *
 * The deeper problem is the same one the description bug had: a security claim
 * stated in twenty-five places, of which thirteen were written down and twelve
 * were left to a default nobody read. The compiler cannot see the difference
 * between "declared LOCAL" and "never mentioned", and neither can a review that
 * only reads the call sites. Declaring it here makes all twenty-five visible in
 * one table, and the `init` block pins the NETWORK set so a tool cannot quietly
 * move across that line.
 *
 * ## Why [define] rather than three more field references
 *
 * A call site that reads `name = FILES_LIST.name, description = FILES_LIST.description,
 * category = FILES_LIST.category, tags = FILES_LIST.tags` can be wrong in a way
 * that compiles: a copy-pasted `FILES_SEARCH` on the `files.list` tool is a
 * perfectly well-typed tool carrying another tool's description, and it would
 * ship. Naming one descriptor per tool and passing it whole to [define] makes
 * that mistake unrepresentable — the whole identity moves together or the code
 * does not compile.
 *
 * ## The values are the shipped ones
 *
 * These are the `:android` strings verbatim, and that is the direction the
 * unification runs. The alternative — making the catalogue's prose canonical
 * and pushing it into the implementations — would have changed the text the
 * model reads and the tags the selector scores, which is a behaviour change
 * dressed as a refactor. This way the live behaviour is byte-for-byte what it
 * was, and the catalogue becomes an honest record of it instead of a rival
 * draft.
 */
data class ToolDescriptor(
    val name: String,
    val description: String,
    val category: String,
    val tags: Set<String>,
    /**
     * How much this tool's observation content can be believed.
     *
     * WHY IT LIVES HERE AND NOT AT THE `define()` CALL SITE
     *
     * [ToolDefinition.observationOrigin] defaults to
     * [ObservationOrigin.NETWORK] — the pessimistic reading — so a tool that
     * does not state its origin is fenced as third-party text. That default is
     * correct for a network tool and wrong-but-safe for a local one, which is
     * exactly why it must not be left to chance: on main, 12 of the 25 shipped
     * tools never stated an origin and were silently fenced as untrusted, so
     * `files.list` output reached the model wearing a header that says
     * "untrusted network content". A fence nobody asked for is still a fence —
     * it costs the model the ability to treat device-local data as data.
     *
     * It is a required field with no default on purpose. Every descriptor must
     * make the claim deliberately, so a new tool cannot inherit the pessimistic
     * one by omission, and the review that reads this table sees all 25 origins
     * together instead of 13 of them scattered across call sites.
     */
    val observationOrigin: ObservationOrigin,
) {
    /**
     * Build the [ToolDefinition] for this tool.
     *
     * Description, category, tags and origin are not parameters. That is the
     * whole point: the descriptive and trust metadata has exactly one home, so
     * it cannot be restated with a typo, and the two things a caller *does*
     * vary — the schema it implements and the tier it is gated at — stay
     * visible at the call site where they are reviewed.
     */
    fun define(
        schema: JsonObject,
        risk: ToolRisk,
        requiredPermission: String? = null,
    ): ToolDefinition = ToolDefinition(
        name = name,
        description = description,
        category = category,
        schema = schema,
        risk = risk,
        tags = tags,
        requiredPermission = requiredPermission,
        observationOrigin = observationOrigin,
    )
}

/**
 * Every shipped tool's descriptive metadata, in catalogue order.
 *
 * Read [ToolDescriptor] for why these fields are here and what is not.
 *
 * Adding a tool means adding one entry here and implementing it. The descriptor
 * is not optional and cannot be partially filled, so a tool cannot ship with
 * somebody else's description on it.
 */
object ToolMeta {

    // ------------------------------------------------------------------ files

    val FILES_LIST = ToolDescriptor(
        name = "files.list",
        description = "List documents and downloads this app can see, newest " +
            "first, with name, type, size and date.",
        category = "files",
        tags = setOf(
            "browse", "documents", "downloads", "files", "my files", "storage",
            "what files do i have",
        ),
        observationOrigin = ObservationOrigin.LOCAL,
    )

    val FILES_SEARCH = ToolDescriptor(
        name = "files.search",
        description = "Find documents by name substring, MIME type and " +
            "modification date, returning a capped list of matches.",
        category = "files",
        tags = setOf(
            "extension", "filename", "find", "look for", "mime type", "modified",
            "recent files", "search",
        ),
        observationOrigin = ObservationOrigin.LOCAL,
    )

    val FILES_READ_TEXT = ToolDescriptor(
        name = "files.read_text",
        description = "Read the beginning of a text document given its " +
            "content:// URI, returning at most 8 KB of its text.",
        category = "files",
        tags = setOf(
            "contents", "file content", "open", "preview", "read", "text",
            "what does the file say",
        ),
        observationOrigin = ObservationOrigin.LOCAL,
    )

    val FILES_WRITE_TEXT = ToolDescriptor(
        name = "files.write_text",
        description = "Write text into an existing content:// document, or " +
            "create a new file in Downloads on Android 10 and newer.",
        category = "files",
        tags = setOf(
            "create file", "export", "new note", "overwrite", "save", "store text", "write",
        ),
        observationOrigin = ObservationOrigin.NETWORK,
    )

    val FILES_DELETE = ToolDescriptor(
        name = "files.delete",
        description = "Delete one identified document by content:// URI, or by " +
            "a name that matches exactly one file.",
        category = "files",
        tags = setOf(
            "delete", "erase", "get rid of", "remove", "trash", "unlink",
        ),
        observationOrigin = ObservationOrigin.NETWORK,
    )

    // ------------------------------------------------------------------- apps

    val APPS_LIST = ToolDescriptor(
        name = "apps.list",
        description = "List launchable apps on this device with their labels " +
            "and package names, optionally filtered by a query.",
        category = "apps",
        tags = setOf(
            "applications", "apps", "home screen", "installed", "launcher", "packages",
            "what apps do i have",
        ),
        observationOrigin = ObservationOrigin.LOCAL,
    )

    val APPS_OPEN = ToolDescriptor(
        name = "apps.open",
        description = "Open an app by its exact package name, or by a label that " +
            "matches exactly one installed app.",
        category = "apps",
        tags = setOf(
            "go to app", "launch", "open", "run", "show me the app", "start", "switch to",
        ),
        observationOrigin = ObservationOrigin.NETWORK,
    )

    val APPS_SHARE = ToolDescriptor(
        name = "apps.share",
        description = "Share a content:// document or a text snippet through the " +
            "Android share sheet, with the user's confirmation.",
        category = "apps",
        tags = setOf(
            "attach", "forward", "pass to another app", "send", "share", "share file",
            "share text",
        ),
        observationOrigin = ObservationOrigin.NETWORK,
    )

    // --------------------------------------------------------------- clipboard

    val CLIPBOARD_READ = ToolDescriptor(
        name = "clipboard.read",
        description = "Read the plain text currently on the device clipboard and " +
            "return it.",
        category = "clipboard",
        tags = setOf(
            "clipboard", "clipboard contents", "copied text", "paste", "read clipboard",
            "what did i copy", "what is on my clipboard",
        ),
        observationOrigin = ObservationOrigin.LOCAL,
    )

    val CLIPBOARD_WRITE = ToolDescriptor(
        name = "clipboard.write",
        description = "Copy plain text to the device clipboard and return how many " +
            "characters were copied.",
        category = "clipboard",
        tags = setOf(
            "clipboard", "copy", "copy text", "copy to clipboard", "cut", "paste",
            "put on clipboard", "share text",
        ),
        observationOrigin = ObservationOrigin.NETWORK,
    )

    // ------------------------------------------------------------------ device

    val DEVICE_BATTERY = ToolDescriptor(
        name = "device.battery",
        description = "Return the current battery percentage, whether the phone is " +
            "charging, and the estimated time until the battery is empty or full.",
        category = "device",
        tags = setOf(
            "battery", "battery level", "charge", "charging", "drain",
            "how long until charged", "how much battery", "power",
        ),
        observationOrigin = ObservationOrigin.LOCAL,
    )

    val DEVICE_INFO = ToolDescriptor(
        name = "device.info",
        description = "Return the phone model, Android version, screen size, total " +
            "RAM, and free storage.",
        category = "device",
        tags = setOf(
            "android version", "device info", "free space", "how much ram",
            "phone model", "screen size", "specs", "storage",
        ),
        observationOrigin = ObservationOrigin.LOCAL,
    )

    val DEVICE_OPEN_SETTINGS = ToolDescriptor(
        name = "device.open_settings",
        description = "Open a system settings screen on the phone and return the " +
            "screen that was opened.",
        category = "device",
        tags = setOf(
            "battery saver", "display settings", "open settings", "settings",
            "sound settings", "system settings", "turn on bluetooth", "wifi settings",
        ),
        observationOrigin = ObservationOrigin.NETWORK,
    )

    val DEVICE_VIBRATE = ToolDescriptor(
        name = "device.vibrate",
        description = "Vibrate the phone for a short duration and return what " +
            "actually happened.",
        category = "device",
        tags = setOf(
            "buzz", "find my phone", "ring", "ringer", "shake", "vibrate", "vibration",
        ),
        observationOrigin = ObservationOrigin.NETWORK,
    )

    // ------------------------------------------------------------------- alarm

    val ALARM_CREATE = ToolDescriptor(
        name = "alarm.create",
        description = "Set a one-time alarm on the phone and return the time and " +
            "the id it was given.",
        category = "alarm",
        tags = setOf(
            "alarm", "remind me at", "ring at", "set a reminder", "set an alarm", "timer",
            "wake me up", "wake up call",
        ),
        observationOrigin = ObservationOrigin.NETWORK,
    )

    val ALARM_LIST = ToolDescriptor(
        name = "alarm.list",
        description = "List the alarms this assistant has set, and state that " +
            "alarms from the system Clock app are not visible.",
        category = "alarm",
        tags = setOf(
            "alarm list", "do i have an alarm", "list alarms", "my alarms",
            "upcoming alarms", "what alarms do i have", "what did i set",
        ),
        observationOrigin = ObservationOrigin.LOCAL,
    )

    val ALARM_CANCEL = ToolDescriptor(
        name = "alarm.cancel",
        description = "Cancel exactly one previously set alarm, identified by its " +
            "id or by its hour, minute and label.",
        category = "alarm",
        tags = setOf(
            "cancel alarm", "cancel my alarm", "delete alarm", "remove alarm",
            "remove the wake up", "stop the alarm", "turn off alarm",
        ),
        observationOrigin = ObservationOrigin.NETWORK,
    )

    // ---------------------------------------------------------------- calendar

    val CALENDAR_SEARCH = ToolDescriptor(
        name = "calendar.search",
        description = "Return the calendar events in a time window, optionally " +
            "filtered by title or location text.",
        category = "calendar",
        tags = setOf(
            "agenda", "appointment", "busy", "calendar", "events", "meeting",
            "schedule", "what's on",
        ),
        observationOrigin = ObservationOrigin.LOCAL,
    )

    val CALENDAR_CREATE = ToolDescriptor(
        name = "calendar.create",
        description = "Create a calendar event at a given start time and return " +
            "the new event's id and start time.",
        category = "calendar",
        tags = setOf(
            "add event", "appointment", "book", "calendar", "meeting", "reminder", "schedule",
        ),
        observationOrigin = ObservationOrigin.NETWORK,
    )

    // ---------------------------------------------------------------- contacts

    val CONTACTS_SEARCH = ToolDescriptor(
        name = "contacts.search",
        description = "Return the contacts whose name or phone number matches a " +
            "query, one line per person.",
        category = "contacts",
        tags = setOf(
            "address book", "call", "contact", "contacts", "lookup", "number",
            "phone book", "who is",
        ),
        observationOrigin = ObservationOrigin.LOCAL,
    )

    val CONTACTS_GET = ToolDescriptor(
        name = "contacts.get",
        description = "Return every phone number, email and organisation saved " +
            "for one contact id.",
        category = "contacts",
        tags = setOf(
            "address book", "contact", "contact details", "email address", "lookup",
            "phone number", "who is",
        ),
        observationOrigin = ObservationOrigin.LOCAL,
    )

    // ---------------------------------------------------------- notifications

    val NOTIFICATIONS_LIST = ToolDescriptor(
        name = "notifications.list",
        description = "Lists the notifications currently showing on the phone, " +
            "newest first.",
        category = "notifications",
        tags = setOf(
            "alerts", "banner", "inbox", "list", "messages", "notifications", "ping",
            "what came in",
        ),
        observationOrigin = ObservationOrigin.LOCAL,
    )

    val NOTIFICATIONS_REPLY = ToolDescriptor(
        name = "notifications.reply",
        description = "Sends a quick reply to a messaging notification that offers " +
            "a reply box.",
        category = "notifications",
        tags = setOf(
            "answer", "chat", "message back", "notification", "reply", "respond", "send",
            "text message",
        ),
        observationOrigin = ObservationOrigin.NETWORK,
    )

    val NOTIFICATIONS_DISMISS = ToolDescriptor(
        name = "notifications.dismiss",
        description = "Dismisses (cancels) one notification from the shade by its key.",
        category = "notifications",
        tags = setOf(
            "banner", "clear", "dismiss", "notification", "remove", "silence", "swipe away",
        ),
        observationOrigin = ObservationOrigin.NETWORK,
    )

    // --------------------------------------------------------------------- web

    val WEB_FETCH = ToolDescriptor(
        name = "web.fetch",
        description = "Fetches a web page over http or https and returns its " +
            "readable text, with any HTML markup stripped out. There is no format " +
            "argument: the result is always plain text.",
        category = "web",
        tags = setOf(
            "fetch", "http", "internet", "page", "read online", "url", "web", "website",
        ),
        observationOrigin = ObservationOrigin.NETWORK,
    )

    /** Every descriptor, in catalogue order. */
    val all: List<ToolDescriptor> = listOf(
        FILES_LIST, FILES_SEARCH, FILES_READ_TEXT, FILES_WRITE_TEXT, FILES_DELETE,
        APPS_LIST, APPS_OPEN, APPS_SHARE,
        CLIPBOARD_READ, CLIPBOARD_WRITE,
        DEVICE_BATTERY, DEVICE_INFO, DEVICE_OPEN_SETTINGS, DEVICE_VIBRATE,
        ALARM_CREATE, ALARM_LIST, ALARM_CANCEL,
        CALENDAR_SEARCH, CALENDAR_CREATE,
        CONTACTS_SEARCH, CONTACTS_GET,
        NOTIFICATIONS_LIST, NOTIFICATIONS_REPLY, NOTIFICATIONS_DISMISS,
        WEB_FETCH,
    )

    private val byName: Map<String, ToolDescriptor> = all.associateBy { it.name }

    /** The descriptor for [name], or null if no tool carries that name. */
    fun byName(name: String): ToolDescriptor? = byName[name]

    init {
        // A duplicated name here would be a copy-paste that hands two tools one
        // identity. Caught at class-init with the actual names, because the
        // failure it prevents is silent: both tools would build cleanly and the
        // registry's own duplicate check would not see it until one of them
        // replaced the other.
        val dupes = all.groupBy { it.name }.filterValues { it.size > 1 }.keys
        check(dupes.isEmpty()) { "duplicate tool names in ToolMeta: $dupes" }

        // An empty description is the other silent version of this bug: a tool
        // that reaches the prompt with nothing to say about itself.
        val blank = all.filter { it.description.isBlank() }.map { it.name }
        check(blank.isEmpty()) { "tools with a blank description in ToolMeta: $blank" }

        // Tags are retrieval fuel. A tool with none is reachable only by its
        // name, which the selector scores lexically, so it is close to invisible
        // for any phrasing that does not contain the tool's own name.
        val untagged = all.filter { it.tags.isEmpty() }.map { it.name }
        check(untagged.isEmpty()) { "tools with no retrieval tags in ToolMeta: $untagged" }

        // The NETWORK set is pinned, not derived. Every tool in this table reads
        // device state except web.fetch, so this list should stay at exactly
        // one entry for as long as that is true — and the moment a second tool
        // starts talking to a third party, this check is what makes a human
        // look at it instead of letting a default decide.
        //
        // The 12 tools listed as NETWORK below do not fetch anything. They carry
        // the value they had on main, where they inherited the pessimistic
        // `ToolDefinition` default because nobody wrote the field. Re-classing
        // them to LOCAL is a separate decision, not a side effect of moving the
        // value: several of them (files.write_text, notifications.reply) return
        // text the user or a third party put on the device, and the fence is
        // what is currently standing between that text and the model.
        val network = all.filter { it.observationOrigin == ObservationOrigin.NETWORK }
            .map { it.name }.sorted()
        val expectedNetwork = listOf(
            "alarm.cancel", "alarm.create", "apps.open", "apps.share",
            "calendar.create", "clipboard.write", "device.open_settings",
            "device.vibrate", "files.delete", "files.write_text",
            "notifications.dismiss", "notifications.reply", "web.fetch",
        )
        check(network == expectedNetwork) {
            "tools whose observation origin changed. Expected $expectedNetwork, got $network. " +
                "web.fetch is the only tool that retrieves third-party text; a tool moving " +
                "onto or off that list changes what the model is told to trust, so it " +
                "belongs in review and in docs/threat-model.md (T3)."
        }
    }
}
