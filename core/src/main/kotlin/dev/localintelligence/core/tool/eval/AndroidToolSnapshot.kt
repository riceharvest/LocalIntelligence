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
                "cancel alarm",
                "delete alarm",
                "remove alarm",
                "turn off alarm",
                "cancel my alarm",
                "remove the wake up",
                "stop the alarm",
            ),
        ),
        snapshot(
            name = "alarm.create",
            description = "Set a one-time alarm on the phone and return the time and the id it was given.",
            category = "alarm",
            risk = ToolRisk.REVERSIBLE,
            tags = setOf(
                "alarm",
                "set an alarm",
                "wake me up",
                "remind me at",
                "timer",
                "ring at",
                "wake up call",
                "set a reminder",
            ),
        ),
        snapshot(
            name = "alarm.list",
            description = "List the alarms this assistant has set, and state that alarms from the system Clock app are not visible.",
            category = "alarm",
            risk = ToolRisk.READ_ONLY,
            tags = setOf(
                "my alarms",
                "list alarms",
                "what alarms do i have",
                "upcoming alarms",
                "alarm list",
                "what did i set",
                "do i have an alarm",
            ),
        ),
        snapshot(
            name = "apps.list",
            description = "List launchable apps on this device with their labels and package names, optionally filtered by a query.",
            category = "apps",
            risk = ToolRisk.READ_ONLY,
            tags = setOf(
                "apps",
                "applications",
                "installed",
                "launcher",
                "home screen",
                "what apps do i have",
                "packages",
            ),
        ),
        snapshot(
            name = "apps.open",
            description = "Open an app by its exact package name, or by a label that matches exactly one installed app.",
            category = "apps",
            risk = ToolRisk.REVERSIBLE,
            tags = setOf(
                "open",
                "launch",
                "start",
                "run",
                "switch to",
                "go to app",
                "show me the app",
            ),
        ),
        snapshot(
            name = "apps.share",
            description = "Share a content:// document or a text snippet through the Android share sheet, with the user's confirmation.",
            category = "apps",
            risk = ToolRisk.EXTERNAL_COMMUNICATION,
            tags = setOf(
                "share",
                "send",
                "attach",
                "share file",
                "share text",
                "pass to another app",
                "forward",
            ),
        ),
        snapshot(
            name = "calendar.create",
            description = "Create a calendar event at a given start time and return the new event's id and start time.",
            category = "calendar",
            risk = ToolRisk.REVERSIBLE,
            tags = setOf(
                "calendar",
                "add event",
                "schedule",
                "reminder",
                "book",
                "appointment",
                "meeting",
            ),
        ),
        snapshot(
            name = "calendar.search",
            description = "Return the calendar events in a time window, optionally filtered by title or location text.",
            category = "calendar",
            risk = ToolRisk.READ_ONLY,
            tags = setOf(
                "calendar",
                "events",
                "agenda",
                "schedule",
                "appointment",
                "meeting",
                "busy",
                "what's on",
            ),
        ),
        snapshot(
            name = "clipboard.read",
            description = "Read the plain text currently on the device clipboard and return it.",
            category = "clipboard",
            risk = ToolRisk.READ_ONLY,
            tags = setOf(
                "clipboard",
                "read clipboard",
                "what did i copy",
                "paste",
                "copied text",
                "clipboard contents",
                "what is on my clipboard",
            ),
        ),
        snapshot(
            name = "clipboard.write",
            description = "Copy plain text to the device clipboard and return how many characters were copied.",
            category = "clipboard",
            risk = ToolRisk.REVERSIBLE,
            tags = setOf(
                "clipboard",
                "copy",
                "copy to clipboard",
                "put on clipboard",
                "cut",
                "copy text",
                "share text",
                "paste",
            ),
        ),
        snapshot(
            name = "contacts.get",
            description = "Return every phone number, email and organisation saved for one contact id.",
            category = "contacts",
            risk = ToolRisk.READ_ONLY,
            tags = setOf(
                "contact",
                "contact details",
                "phone number",
                "email address",
                "address book",
                "who is",
                "lookup",
            ),
        ),
        snapshot(
            name = "contacts.search",
            description = "Return the contacts whose name or phone number matches a query, one line per person.",
            category = "contacts",
            risk = ToolRisk.READ_ONLY,
            tags = setOf(
                "contact",
                "contacts",
                "phone book",
                "address book",
                "call",
                "who is",
                "number",
                "lookup",
            ),
        ),
        snapshot(
            name = "device.battery",
            description = "Return the current battery percentage, whether the phone is charging, and the estimated time until the battery is empty or full.",
            category = "device",
            risk = ToolRisk.READ_ONLY,
            tags = setOf(
                "battery",
                "charge",
                "power",
                "how much battery",
                "battery level",
                "charging",
                "how long until charged",
                "drain",
            ),
        ),
        snapshot(
            name = "device.info",
            description = "Return the phone model, Android version, screen size, total RAM, and free storage.",
            category = "device",
            risk = ToolRisk.READ_ONLY,
            tags = setOf(
                "device info",
                "phone model",
                "specs",
                "how much ram",
                "storage",
                "free space",
                "android version",
                "screen size",
            ),
        ),
        snapshot(
            name = "device.open_settings",
            description = "Open a system settings screen on the phone and return the screen that was opened.",
            category = "device",
            risk = ToolRisk.REVERSIBLE,
            tags = setOf(
                "open settings",
                "settings",
                "wifi settings",
                "turn on bluetooth",
                "display settings",
                "battery saver",
                "sound settings",
                "system settings",
            ),
        ),
        snapshot(
            name = "device.vibrate",
            description = "Vibrate the phone for a short duration and return what actually happened.",
            category = "device",
            risk = ToolRisk.REVERSIBLE,
            tags = setOf(
                "vibrate",
                "buzz",
                "vibration",
                "shake",
                "ringer",
                "find my phone",
                "ring",
            ),
        ),
        snapshot(
            name = "files.delete",
            description = "Delete one identified document by content:// URI, or by a name that matches exactly one file.",
            category = "files",
            risk = ToolRisk.DESTRUCTIVE,
            tags = setOf(
                "delete",
                "remove",
                "erase",
                "trash",
                "get rid of",
                "unlink",
            ),
        ),
        snapshot(
            name = "files.list",
            description = "List documents and downloads this app can see, newest first, with name, type, size and date.",
            category = "files",
            risk = ToolRisk.READ_ONLY,
            tags = setOf(
                "files",
                "documents",
                "downloads",
                "storage",
                "browse",
                "my files",
                "what files do i have",
            ),
        ),
        snapshot(
            name = "files.read_text",
            description = "Read the beginning of a text document given its content:// URI, returning at most 8 KB of its text.",
            category = "files",
            risk = ToolRisk.READ_ONLY,
            tags = setOf(
                "read",
                "open",
                "text",
                "contents",
                "preview",
                "file content",
                "what does the file say",
            ),
        ),
        snapshot(
            name = "files.search",
            description = "Find documents by name substring, MIME type and modification date, returning a capped list of matches.",
            category = "files",
            risk = ToolRisk.READ_ONLY,
            tags = setOf(
                "search",
                "find",
                "look for",
                "filename",
                "extension",
                "mime type",
                "recent files",
                "modified",
            ),
        ),
        snapshot(
            name = "files.write_text",
            description = "Write text into an existing content:// document, or create a new file in Downloads on Android 10 and newer.",
            category = "files",
            risk = ToolRisk.DESTRUCTIVE,
            tags = setOf(
                "write",
                "save",
                "create file",
                "new note",
                "store text",
                "overwrite",
                "export",
            ),
        ),
        snapshot(
            name = "notifications.dismiss",
            description = "Dismisses (cancels) one notification from the shade by its key.",
            category = "notifications",
            risk = ToolRisk.REVERSIBLE,
            tags = setOf(
                "dismiss",
                "clear",
                "notification",
                "remove",
                "silence",
                "swipe away",
                "banner",
            ),
        ),
        snapshot(
            name = "notifications.list",
            description = "Lists the notifications currently showing on the phone, newest first.",
            category = "notifications",
            risk = ToolRisk.READ_ONLY,
            tags = setOf(
                "notifications",
                "alerts",
                "messages",
                "list",
                "banner",
                "inbox",
                "what came in",
                "ping",
            ),
        ),
        snapshot(
            name = "notifications.reply",
            description = "Sends a quick reply to a messaging notification that offers a reply box.",
            category = "notifications",
            risk = ToolRisk.EXTERNAL_COMMUNICATION,
            tags = setOf(
                "reply",
                "respond",
                "answer",
                "message back",
                "notification",
                "chat",
                "text message",
                "send",
            ),
        ),
        snapshot(
            name = "web.fetch",
            description = "Fetches a web page over http or https and returns its readable text, with any HTML markup stripped out. There is no format argument: the result is always plain text.",
            category = "web",
            risk = ToolRisk.READ_ONLY,
            tags = setOf(
                "web",
                "fetch",
                "url",
                "internet",
                "page",
                "website",
                "read online",
                "http",
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
        }
        for (name in theirs.keys) {
            if (name !in mine) problems += "$name is in V0ToolCatalogue but not in the snapshot"
        }
        return problems
    }
}
