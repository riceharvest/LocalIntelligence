package dev.localintelligence.core.tool.eval

import dev.localintelligence.core.model.ToolArgs
import dev.localintelligence.core.tool.AgentTool
import dev.localintelligence.core.tool.ToolContext
import dev.localintelligence.core.tool.ToolDefinition
import dev.localintelligence.core.tool.ToolResult
import dev.localintelligence.core.tool.ToolRisk
import kotlinx.serialization.json.buildJsonObject

/**
 * A frozen, faithful copy of the tool definitions `:android` actually ships.
 *
 * ## Why a copy and not the real registry
 *
 * [dev.localintelligence.core.tool.LexicalToolSelector] scores whatever
 * definitions the REGISTRY holds, and the shipped registry is
 * `SimpleToolRegistry(androidTools(context))` — which needs an Android
 * `Context`. `:core` is a pure JVM module by construction and CI enforces it,
 * so the harness cannot load the real set, and there is no honest way to make
 * it do so from here. A harness that reached into `:android` would break the
 * moment the module boundary moved, and it would be measuring this file
 * either way.
 *
 * So the copy IS the artifact, and it is held honest by
 * [AndroidToolSnapshot.verifyAgainstCatalogue]: every name, category and risk
 * tier here is checked against
 * [dev.localintelligence.core.tool.catalogue.V0ToolCatalogue], which
 * `CatalogueAgreement` already holds bidirectionally equal to the shipped
 * set. A new tool, a rename or a risk change therefore fails the harness
 * loudly instead of quietly measuring a 24-tool catalogue.
 *
 * ## What is NOT copied, and why that is not cheating
 *
 * **Schema.** The selector never reads it, so copying it would be decoration
 * pretending to be fidelity. The empty object below is a placeholder and is
 * labelled as one at the call site. The two components that DO read the
 * schema — `GrammarBuilder` and `ToolCallValidator` — are not what a
 * retrieval measurement exercises. Where the schema would have mattered for
 * COST, the harness prices the real system prompt through
 * [dev.localintelligence.core.context.SystemPrompts], which is name +
 * description only, exactly as production renders it.
 *
 * **Descriptions and tags are the whole point, and they are verbatim.** These
 * strings are what the scorer tokenises. If they drift from `:android` then
 * every number the harness prints describes a tool set that no longer ships —
 * so [verifyAgainstCatalogue]'s report is printed on every run rather than
 * hidden behind a green tick.
 */
class SnapshotTool(
    override val definition: ToolDefinition,
) : AgentTool {
    /**
     * Never invoked, and it throws rather than returning a plausible
     * observation.
     *
     * A fake-backed harness that returns success for a tool it never ran is
     * the exact failure `docs/evals.md` records as the reason the old suite
     * was deleted: it reported 50/50 for a system that could not answer a
     * single question. This harness measures RETRIEVAL. Executing a tool is a
     * different measurement, and a different measurement does not get to
     * borrow this one's harness.
     */
    override suspend fun execute(args: ToolArgs, context: ToolContext): ToolResult =
        throw UnsupportedOperationException(
            "${definition.name} is a definition-only snapshot: this harness measures " +
                "RETRIEVAL, and executing a tool is a different measurement entirely.",
        )
}

/**
 * The 25 tools `:android` ships, as pure data.
 *
 * Alphabetical by name, so a diff against the real registry is readable and a
 * change in ORDER cannot be mistaken for a change in CONTENT. Input order
 * cannot move a number printed by the harness regardless: the selector sorts
 * by score and then by name, so ordering is decided by the sorter, not here.
 */
object AndroidToolSnapshot {

    val tools: List<AgentTool> = listOf(
        snapshot(
            name = "alarm.cancel",
            description = "Cancel exactly one previously set alarm, identified by its id or by its hour, minute and label.",
            category = "alarm",
            risk = ToolRisk.REVERSIBLE,
            tags = setOf(
                "cancel alarm", "cancel my alarm", "delete alarm", "get rid of my alarm",
                "kill the alarm", "remove alarm", "remove the wake up", "stop the alarm",
                "turn off alarm",
            ),
        ),
        snapshot(
            name = "alarm.create",
            description = "Set a one-time alarm on the phone and return the time and the id it was given.",
            category = "alarm",
            risk = ToolRisk.REVERSIBLE,
            tags = setOf(
                "alarm", "before work", "get me up", "get up", "need to be up", "remind me at",
                "ring at", "set a reminder", "set a timer", "set an alarm", "timer", "timer for",
                "up at", "wake me up", "wake up call",
            ),
        ),
        snapshot(
            name = "alarm.list",
            description = "List the alarms this assistant has set, and state that alarms from the system Clock app are not visible.",
            category = "alarm",
            risk = ToolRisk.READ_ONLY,
            tags = setOf(
                "alarm list", "do i have an alarm", "list alarms", "my alarm", "my alarms",
                "show my alarm", "upcoming alarm", "upcoming alarms", "what alarm do i have",
                "what alarms do i have", "what did i set",
            ),
        ),
        snapshot(
            name = "apps.list",
            description = "List launchable apps on this device with their labels and package names, optionally filtered by a query.",
            category = "apps",
            risk = ToolRisk.READ_ONLY,
            tags = setOf(
                "app", "applications", "apps", "do i have an app", "find an app", "home screen",
                "installed", "is there an app for", "launcher", "package name", "packages",
                "search apps", "what apps do i have",
            ),
        ),
        snapshot(
            name = "apps.open",
            description = "Open an app by its exact package name, or by a label that matches exactly one installed app.",
            category = "apps",
            risk = ToolRisk.REVERSIBLE,
            tags = setOf(
                "go to app", "launch", "listen to", "maps", "open", "open a media app", "play",
                "play a game", "play music", "put on", "run", "show me the app", "start", "switch to",
                "whatsapp",
            ),
        ),
        snapshot(
            name = "apps.share",
            description = "Share a content:// document or a text snippet through the Android share sheet, with the user's confirmation.",
            category = "apps",
            risk = ToolRisk.EXTERNAL_COMMUNICATION,
            tags = setOf(
                "attach", "email it", "forward", "pass to another app", "send", "send it to", "share",
                "share file", "share text", "whatsapp it",
            ),
        ),
        snapshot(
            name = "calendar.create",
            description = "Create a calendar event at a given start time and return the new event's id and start time.",
            category = "calendar",
            risk = ToolRisk.REVERSIBLE,
            tags = setOf(
                "add event", "appointment", "block out", "block time", "book", "calendar", "hold time",
                "meeting", "new meeting", "put in my calendar", "reminder", "reserve", "schedule",
                "this afternoon", "work meeting",
            ),
        ),
        snapshot(
            name = "calendar.search",
            description = "Return the calendar events in a time window, optionally filtered by title or location text.",
            category = "calendar",
            risk = ToolRisk.READ_ONLY,
            tags = setOf(
                "agenda", "am i free", "anything today", "appointment", "busy", "calendar", "event",
                "events", "free", "meeting", "meetings", "next", "schedule", "this afternoon", "today",
                "tomorrow", "what do i have on", "what's on",
            ),
        ),
        snapshot(
            name = "clipboard.read",
            description = "Read the plain text currently on the device clipboard and return it.",
            category = "clipboard",
            risk = ToolRisk.READ_ONLY,
            tags = setOf(
                "clipboard", "clipboard contents", "copied text", "paste", "read clipboard",
                "what did i copy", "what is on my clipboard",
            ),
        ),
        snapshot(
            name = "clipboard.write",
            description = "Copy plain text to the device clipboard and return how many characters were copied.",
            category = "clipboard",
            risk = ToolRisk.REVERSIBLE,
            tags = setOf(
                "clipboard", "copy", "copy text", "copy this", "copy to clipboard", "cut", "paste",
                "put on clipboard", "save to clipboard", "share text",
            ),
        ),
        snapshot(
            name = "contacts.get",
            description = "Return every phone number, email and organisation saved for one contact id.",
            category = "contacts",
            risk = ToolRisk.READ_ONLY,
            tags = setOf(
                "address book", "contact", "contact card", "contact details", "email address",
                "everything about", "full contact", "lookup", "phone number", "their address",
                "their email", "who is",
            ),
        ),
        snapshot(
            name = "contacts.search",
            description = "Return the contacts whose name or phone number matches a query, one line per person.",
            category = "contacts",
            risk = ToolRisk.READ_ONLY,
            tags = setOf(
                "address book", "call", "contact", "contacts", "find someone", "get in touch",
                "how do i contact", "how to reach", "look someone up", "look up", "lookup", "number",
                "phone book", "phone number", "reach", "ring", "who is",
            ),
        ),
        snapshot(
            name = "device.battery",
            description = "Return the current battery percentage, whether the phone is charging, and the estimated time until the battery is empty or full.",
            category = "device",
            risk = ToolRisk.READ_ONLY,
            tags = setOf(
                "battery", "battery level", "charge", "charging", "die", "dies", "drain", "empty",
                "how long until charged", "how long until it dies", "how long will it last",
                "how much battery", "make it home", "power", "run out", "run out of battery",
                "running out", "survive",
            ),
        ),
        snapshot(
            name = "device.info",
            description = "Return the phone model, Android version, screen size, total RAM, and free storage.",
            category = "device",
            risk = ToolRisk.READ_ONLY,
            tags = setOf(
                "android version", "device info", "free space", "how much ram", "how much storage",
                "phone model", "screen size", "specs", "storage", "what phone", "which phone",
            ),
        ),
        snapshot(
            name = "device.open_settings",
            description = "Open a system settings screen on the phone and return the screen that was opened.",
            category = "device",
            risk = ToolRisk.REVERSIBLE,
            tags = setOf(
                "battery saver", "display settings", "open settings", "settings", "sound settings",
                "system settings", "turn on bluetooth", "wifi settings",
            ),
        ),
        snapshot(
            name = "device.vibrate",
            description = "Vibrate the phone for a short duration and return what actually happened.",
            category = "device",
            risk = ToolRisk.REVERSIBLE,
            tags = setOf(
                "buzz", "find my phone", "ring", "ringer", "shake", "vibrate", "vibration",
            ),
        ),
        snapshot(
            name = "files.delete",
            description = "Delete one identified document by content:// URI, or by a name that matches exactly one file.",
            category = "files",
            risk = ToolRisk.DESTRUCTIVE,
            tags = setOf(
                "bin", "delete", "delete a file", "discard", "document", "draft", "erase", "get rid of",
                "invoice", "pdf", "remove", "remove a file", "throw away", "trash", "unlink",
            ),
        ),
        snapshot(
            name = "files.list",
            description = "List documents and downloads this app can see, newest first, with name, type, size and date.",
            category = "files",
            risk = ToolRisk.READ_ONLY,
            tags = setOf(
                "browse", "documents", "downloads", "files", "folder", "list my files", "my files",
                "storage", "what files do i have",
            ),
        ),
        snapshot(
            name = "files.read_text",
            description = "Read the beginning of a text document given its content:// URI, returning at most 8 KB of its text.",
            category = "files",
            risk = ToolRisk.READ_ONLY,
            tags = setOf(
                "contents", "file content", "open", "open file", "preview", "read", "show me the file",
                "summarize this file", "text", "what does it say", "what does the file say",
            ),
        ),
        snapshot(
            name = "files.search",
            description = "Find documents by name substring, MIME type and modification date, returning a capped list of matches.",
            category = "files",
            risk = ToolRisk.READ_ONLY,
            tags = setOf(
                "anywhere", "downloaded", "extension", "filename", "find", "is there a", "look for",
                "mime type", "modified", "my note", "pdf", "recent files", "report", "search",
                "what did i download", "where is",
            ),
        ),
        snapshot(
            name = "files.write_text",
            description = "Write text into an existing content:// document, or create a new file in Downloads on Android 10 and newer.",
            category = "files",
            risk = ToolRisk.DESTRUCTIVE,
            tags = setOf(
                "create file", "export", "jot down", "new note", "note", "notes", "overwrite", "save",
                "store text", "write",
            ),
        ),
        snapshot(
            name = "notifications.dismiss",
            description = "Dismisses (cancels) one notification from the shade by its key.",
            category = "notifications",
            risk = ToolRisk.REVERSIBLE,
            tags = setOf(
                "banner", "clear", "clear notification", "dismiss", "get rid of notification",
                "mark as read", "no more alerts", "notification", "remove", "silence", "swipe away",
            ),
        ),
        snapshot(
            name = "notifications.list",
            description = "Lists the notifications currently showing on the phone, newest first.",
            category = "notifications",
            risk = ToolRisk.READ_ONLY,
            tags = setOf(
                "alerts", "anything new", "banner", "come in", "did anything come in", "inbox", "list",
                "messages", "miss", "notifications", "ping", "what came in", "what did i miss",
                "whatsapp", "who messaged",
            ),
        ),
        snapshot(
            name = "notifications.reply",
            description = "Sends a quick reply to a messaging notification that offers a reply box.",
            category = "notifications",
            risk = ToolRisk.EXTERNAL_COMMUNICATION,
            tags = setOf(
                "answer", "chat", "let them know", "message back", "notification", "quick reply",
                "reply", "reply to", "respond", "send", "send a message", "tell them", "text back",
                "text message",
            ),
        ),
        snapshot(
            name = "web.fetch",
            description = "Fetches a web page over http or https and returns its readable text, with any HTML markup stripped out. There is no format argument: the result is always plain text.",
            category = "web",
            risk = ToolRisk.NETWORK_EGRESS,
            tags = setOf(
                "check what the site says", "fetch", "forecast", "http", "internet", "link", "link url",
                "look up", "online", "open a link", "page", "page link", "read online", "right now",
                "this link", "url", "weather", "web", "website", "website says",
            ),
        ),
    )

    private fun snapshot(
        name: String,
        description: String,
        category: String,
        risk: ToolRisk,
        tags: Set<String>,
    ): AgentTool = SnapshotTool(
        ToolDefinition(
            name = name,
            description = description,
            category = category,
            // Deliberately EMPTY, and the one thing in this file that is not a
            // faithful copy. See the class KDoc: the selector does not read
            // the schema, so a fabricated one would put an invented number
            // into the cost column and call it measured.
            schema = buildJsonObject { },
            risk = risk,
            tags = tags,
        ),
    )

    /**
     * Fails loudly if this snapshot and the shipped catalogue disagree.
     *
     * Names, categories and risk tiers are the three fields
     * [dev.localintelligence.core.tool.catalogue.CatalogueAgreement] already
     * guarantees equal between the catalogue and `:android`, so they are
     * exactly the three this file cannot be allowed to drift on. Descriptions
     * and tags are deliberately NOT checked: they are documented to differ
     * legitimately, and a retrieval harness needs the `:android` side of that
     * difference rather than the catalogue's.
     *
     * @return one line per disagreement, empty when there are none. The
     *   harness prints the list either way — a check whose passing result is
     *   indistinguishable from a check nobody ran is not a check.
     */
    fun verifyAgainstCatalogue(): List<String> {
        val mine = tools.associateBy { it.definition.name }
        val theirs = dev.localintelligence.core.tool.catalogue.V0ToolCatalogue.definitions
            .associateBy { it.name }
        val problems = ArrayList<String>()

        for ((name, tool) in mine) {
            val reference = theirs[name]
            if (reference == null) {
                problems += "$name is in the snapshot but not in V0ToolCatalogue"
                continue
            }
            val definition = tool.definition
            if (definition.category != reference.category) {
                problems += "$name category: snapshot=${definition.category} " +
                    "catalogue=${reference.category}"
            }
            if (definition.risk != reference.risk) {
                problems += "$name risk: snapshot=${definition.risk} catalogue=${reference.risk}"
            }
            // TAGS AND DESCRIPTIONS ARE COMPARED HERE, and the omission of these
            // two checks was itself a defect: the KDoc above calls this function
            // the thing that holds the copy honest, while the two fields the
            // scorer actually tokenises were not among the things it checked. A
            // snapshot whose tags had drifted from the shipped tools would have
            // measured a tool set the app does not ship, and reported it as
            // faithful. Both sides now read the same ToolMeta descriptor, so
            // these cannot fail for a correctly written entry -- exactly like
            // CatalogueAgreement's own checks.
            if (definition.tags != reference.tags) {
                problems += "$name tags: snapshot=${definition.tags.sorted()} " +
                    "catalogue=${reference.tags.sorted()}"
            }
            if (definition.description != reference.description) {
                problems += "$name description: snapshot=${definition.description} " +
                    "catalogue=${reference.description}"
            }
        }
        for (name in theirs.keys) {
            if (name !in mine) problems += "$name is in V0ToolCatalogue but not in the snapshot"
        }
        return problems
    }
}
