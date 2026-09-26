package dev.localintelligence.core.tool.eval

import dev.localintelligence.core.model.ToolArgs
import dev.localintelligence.core.tool.AgentTool
import dev.localintelligence.core.tool.ToolContext
import dev.localintelligence.core.tool.ToolDefinition
import dev.localintelligence.core.tool.ToolResult
import dev.localintelligence.core.tool.ToolRisk
import kotlinx.serialization.json.buildJsonObject
import java.io.File

/**
 * A tool with no implementation, so the harness can price retrieval over the
 * tool set `:android` really ships without depending on `:android`.
 *
 * `execute` is unreachable in this harness and says so rather than pretending.
 * A reviewer should never have to ask whether a recall number here came from a
 * tool that could have run.
 */
class SnapshotTool(
    override val definition: ToolDefinition,
) : AgentTool {
    override suspend fun execute(args: ToolArgs, context: ToolContext): ToolResult =
        ToolResult(
            success = false,
            observation = "SnapshotTool has no implementation; retrieval measurement only.",
            error = dev.localintelligence.core.tool.ToolError.Unavailable(
                "snapshot tool is not executable",
            ),
        )
}

/**
 * The 25 tools `:android` ships, as pure data.
 *
 * ## Why this file exists and why it is a SNAPSHOT
 *
 * `LexicalToolSelector` scores the tools the REGISTRY holds, and the prompt
 * renders the same definitions. Both read `:android`, which `:core` cannot
 * import — `:core` is pure JVM by rule and a CI gate enforces it. So the tool
 * set has to be restated here for the selector to be measurable at all.
 *
 * A restatement can drift from the thing it claims to measure, and a drifted
 * snapshot makes every number below a statement about a fiction. Two things
 * guard that, and both are checked on every run rather than trusted:
 *
 *  - [verifyAgainstCatalogue] compares every name, category and risk tier here
 *    against [dev.localintelligence.core.tool.catalogue.V0ToolCatalogue], which
 *    is the reviewed artefact the same tool set is specified in.
 *  - [verifyAgainstAndroidSources] re-parses the `:android` Kotlin sources and
 *    compares names, categories, risk, PERMISSIONS and every TAG. This is the
 *    check that matters, because tags carry 3x the weight of description tokens
 *    in the scorer, so a snapshot that quietly lost or gained a tag would move
 *    the headline number while still agreeing with the catalogue on names.
 *
 * ## The tags are the thing under test, and they were NOT written for this corpus
 *
 * Every tag below is copied verbatim from `:android`. They were authored by
 * whoever wrote those tools, and the single-turn corpus was authored by an agent
 * that read them. The held-out corpus in [HeldOutDataset] was written without
 * reading a single tag, which is the only property that makes its number mean
 * anything. [HeldOutHarness] reports, per case, whether the expected tool is
 * reachable because of a tag — the tautology check — so a reader can see which
 * cases are decided by retrieval fuel and which by the description.
 */
object HeldOutToolSnapshot {

    val tools: List<AgentTool> = listOf(
        snapshot(
            name = "alarm.cancel",
            description = "Cancel exactly one previously set alarm, identified by its id or by its ",
            category = "alarm",
            risk = ToolRisk.REVERSIBLE,
            requiredPermission = "android.permission.SCHEDULE_EXACT_ALARM",
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
            description = "Set a one-time alarm on the phone and return the time and the id it was ",
            category = "alarm",
            risk = ToolRisk.REVERSIBLE,
            requiredPermission = "android.permission.SCHEDULE_EXACT_ALARM",
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
            description = "List the alarms this assistant has set, and state that alarms from the ",
            category = "alarm",
            risk = ToolRisk.READ_ONLY,
            requiredPermission = null,
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
            requiredPermission = null,
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
            requiredPermission = null,
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
            requiredPermission = null,
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
            requiredPermission = "android.permission.WRITE_CALENDAR",
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
            requiredPermission = "android.permission.READ_CALENDAR",
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
            requiredPermission = null,
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
            description = "Copy plain text to the device clipboard and return how many characters ",
            category = "clipboard",
            risk = ToolRisk.REVERSIBLE,
            requiredPermission = null,
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
            requiredPermission = "android.permission.READ_CONTACTS",
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
            requiredPermission = "android.permission.READ_CONTACTS",
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
            description = "Return the current battery percentage, whether the phone is charging, ",
            category = "device",
            risk = ToolRisk.READ_ONLY,
            requiredPermission = null,
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
            description = "Return the phone model, Android version, screen size, total RAM, and ",
            category = "device",
            risk = ToolRisk.READ_ONLY,
            requiredPermission = null,
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
            description = "Open a system settings screen on the phone and return the screen that ",
            category = "device",
            risk = ToolRisk.REVERSIBLE,
            requiredPermission = null,
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
            requiredPermission = "android.permission.VIBRATE",
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
            requiredPermission = null,
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
            requiredPermission = null,
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
            requiredPermission = null,
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
            requiredPermission = null,
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
            requiredPermission = null,
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
            requiredPermission = null,
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
            requiredPermission = null,
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
            requiredPermission = null,
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
            description = "Fetches a web page over http or https and returns its readable text, ",
            category = "web",
            risk = ToolRisk.NETWORK_EGRESS,
            requiredPermission = null,
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

    fun byName(name: String): AgentTool? = tools.firstOrNull { it.definition.name == name }

    /**
     * Names/categories/risks against the reviewed catalogue.
     *
     * This is the WEAK check: it catches a tool added, removed or re-tiered, and
     * says nothing about tags. [verifyAgainstAndroidSources] is the strong one.
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
                problems += "$name category: snapshot=${definition.category} catalogue=${reference.category}"
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

    /**
     * Re-parse `:android` and compare the FULL definition, tags included.
     *
     * ## Why the sources are parsed at run time rather than trusted
     *
     * The obvious failure for a snapshot is silent drift: someone edits a tag in
     * `:android` because retrieval was weak on the corpus they were working
     * from, and the snapshot keeps the old tag. Every number the harness prints
     * then describes a selector facing a tool set that does not exist, and
     * nothing anywhere says so.
     *
     * Parsing the sources closes that. It costs a file read and a regex, it
     * needs no build-file change and no dependency, and it converts "the
     * snapshot is probably right" into "the snapshot is right or the run says
     * so". When the check cannot find the sources at all — running the JAR
     * outside the repo, say — it reports that it was SKIPPED rather than
     * passing silently, because a skipped check read as a pass is exactly the
     * failure mode this exists to prevent.
     */
    fun verifyAgainstAndroidSources(root: java.io.File = defaultRepoRoot()): List<String> {
        val toolsDir = File(root, ANDROID_TOOLS_RELATIVE_PATH)
        if (!toolsDir.isDirectory) {
            return listOf(
                "SKIPPED: :android tool sources not found at ${toolsDir.path}; " +
                    "names/categories/risks were still checked against V0ToolCatalogue, " +
                    "but TAGS and PERMISSIONS are unverified for this run.",
            )
        }
        val parsed = parseProductionDefinitions(toolsDir) ?: return listOf(
            "SKIPPED: :android tool sources present at ${toolsDir.path} but no " +
                "ToolDefinition blocks could be parsed; tags unverified for this run.",
        )
        val mine = tools.associateBy { it.definition.name }
        val problems = ArrayList<String>()

        for ((name, production) in parsed) {
            val tool = mine[name]
            if (tool == null) {
                problems += "$name is in :android but not in the snapshot"
                continue
            }
            val s = tool.definition
            if (s.category != production.category) {
                problems += "$name category: snapshot=${s.category} :android=${production.category}"
            }
            if (s.risk.name != production.risk) {
                problems += "$name risk: snapshot=${s.risk} :android=${production.risk}"
            }
            if (s.requiredPermission != production.requiredPermission) {
                problems += "$name permission: snapshot=${s.requiredPermission} :android=${production.requiredPermission}"
            }
            if (s.tags != production.tags) {
                val missing = production.tags - s.tags
                val extra = s.tags - production.tags
                problems += "$name TAGS: snapshot-only=${extra.sorted()} :android-only=${missing.sorted()}"
            }
        }
        for (name in mine.keys) {
            if (name !in parsed) problems += "$name is in the snapshot but not in :android"
        }
        return problems
    }

    /**
     * Pull every `ToolDefinition(...)` block out of the `:android` tool sources.
     *
     * A targeted regex, not a Kotlin parser: the shapes it needs (a name, a
     * description, a category, a risk, an optional permission, a `setOf` of
     * tags) are all single-line literals in those files, and adding a compiler
     * to read them would be a far larger claim than the check deserves. It
     * returns null when it finds nothing, which the caller reports as SKIPPED
     * rather than as agreement.
     */
    private fun parseProductionDefinitions(toolsDir: File): Map<String, ParsedDefinition>? {
        val out = LinkedHashMap<String, ParsedDefinition>()
        val files = toolsDir.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.toList()
        for (file in files) {
            val text = file.readText()
            var index = 0
            while (true) {
                val at = text.indexOf("ToolDefinition(", index)
                if (at < 0) break
                val open = at + "ToolDefinition(".length - 1
                var depth = 0
                var close = -1
                var i = open
                while (i < text.length) {
                    when (text[i]) {
                        '(' -> depth++
                        ')' -> {
                            depth--
                            if (depth == 0) { close = i; break }
                        }
                    }
                    i++
                }
                if (close < 0) { index = at + 1; continue }
                val block = text.substring(open + 1, close)
                val name = stringField(block, "name") ?: run { index = close; continue }
                out[name] = ParsedDefinition(
                    name = name,
                    category = stringField(block, "category") ?: "",
                    risk = RISK.find(block)?.groupValues?.get(1) ?: "READ_ONLY",
                    requiredPermission = stringField(block, "requiredPermission"),
                    tags = tagBlock(block)?.let { tags -> STRING_LITERALS.findAll(tags).map { it.groupValues[1] }.toSet() }
                        ?: emptySet(),
                )
                index = close
            }
        }
        return if (out.isEmpty()) null else out
    }

    private data class ParsedDefinition(
        val name: String,
        val category: String,
        val risk: String,
        val requiredPermission: String?,
        val tags: Set<String>,
    )

    private val STRING_LITERALS = Regex("\"([^\"]*)\"")

    /**
     * Matches a bare `field = "value"` assignment, not a substring of a longer word.
     *
     * NOTE the `\\b`: in a Kotlin string literal `"\b"` is a BACKSPACE character,
     * not a regex word boundary. Written the obvious way this silently matches
     * nothing, every block fails to parse, and the caller reports SKIPPED — which
     * is precisely the "check quietly disabled" failure this function exists to
     * prevent. It shipped that way on the first run of this harness, which is the
     * argument for reporting SKIPPED loudly instead of returning "no problems".
     */
    private fun stringField(block: String, field: String): String? =
        Regex("\\b" + field + "\\s*=\\s*\"([^\"]*)\"").find(block)?.groupValues?.get(1)

    /**
     * The `setOf(...)` assigned to `tags`, or null when tags are absent.
     *
     * `DOT_MATCHES_ALL` is REQUIRED, not decoration: the tag lists are written
     * one tag per line, and without it the lazy `(.*?)` cannot reach the closing
     * paren, so the block yields null and the tool parses with an EMPTY tag set.
     * That failure is quiet and it is the dangerous direction — an empty tag set
     * makes the scorer see a tool with no retrieval fuel at all, which would
     * understate recall and look like a corpus problem rather than a parser bug.
     * The first run of this harness did exactly that, for 9 tools.
     */
    private fun tagBlock(block: String): String? =
        Regex("\\btags\\s*=\\s*setOf\\((.*?)\\)\\s*,", RegexOption.DOT_MATCHES_ALL)
            .find(block)?.groupValues?.get(1)
            ?: Regex("\\btags\\s*=\\s*setOf\\((.*)\\)\\s*$", RegexOption.DOT_MATCHES_ALL)
                .find(block)?.groupValues?.get(1)

    private val RISK = Regex("\\brisk\\s*=\\s*ToolRisk\\.(\\w+)")

    private fun snapshot(
        name: String,
        description: String,
        category: String,
        risk: ToolRisk,
        requiredPermission: String?,
        tags: Set<String>,
    ): AgentTool = SnapshotTool(
        ToolDefinition(
            name = name,
            description = description,
            category = category,
            schema = buildJsonObject { },
            risk = risk,
            tags = tags,
            requiredPermission = requiredPermission,
        ),
    )

    /**
     * Walks up from the working directory looking for the repo root.
     *
     * A harness run from `:core`, from the repo root, or from a sibling module
     * all have to find the sources, and hardcoding one layout is how a check
     * ends up quietly skipped. Falls back to the current directory so the
     * SKIPPED branch of the check is reachable and testable by hand.
     */
    private fun defaultRepoRoot(): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            if (File(dir, ANDROID_TOOLS_RELATIVE_PATH).isDirectory) return dir
            dir = dir.parentFile
        }
        return File(".").absoluteFile
    }

    private const val ANDROID_TOOLS_RELATIVE_PATH =
        "android/src/main/kotlin/dev/localintelligence/android/tools"
}