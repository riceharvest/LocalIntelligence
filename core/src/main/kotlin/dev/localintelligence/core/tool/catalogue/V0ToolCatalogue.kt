package dev.localintelligence.core.tool.catalogue

import dev.localintelligence.core.model.ObservationOrigin
import dev.localintelligence.core.tool.ToolDefinition
import dev.localintelligence.core.tool.ToolRisk
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The canonical v0 tool set, as data.
 *
 * ## Why 25 tools and not 145
 *
 * The number is not the point; the *filter* is. v0 shows up to 10 tools per
 * step, chosen lexically, so a 25-tool catalogue never puts 25 definitions in
 * a 4K context. What actually costs the model is a tool it cannot tell apart
 * from its neighbour, and that risk grows with every tool sharing a name
 * fragment, a tag, or a phrasing with another. A 145-tool catalogue is not
 * "more capable", it is 145 ways for the right tool to lose the retrieval race
 * against an almost-identical sibling.
 *
 * The set covers the seven target tasks in `docs/architecture.md` section 1 and
 * nothing those tasks do not need:
 *
 * | category        | tools | why it is here                                            |
 * |-----------------|------:|-----------------------------------------------------------|
 * | `files`         |     5 | find, open, save and delete a document; the PDF and note tasks |
 * | `apps`          |     3 | resolve a name to a package, open it, share through it    |
 * | `clipboard`     |     2 | "copy his number" and "what did I copy"                   |
 * | `device`        |     4 | battery, phone specs, open settings, buzz                 |
 * | `alarm`         |     3 | set, list, cancel; "wake me up" and "remind me"          |
 * | `calendar`      |     2 | "what is on my calendar", "block out 16:00"              |
 * | `contacts`      |     2 | resolve a person to a phone number                       |
 * | `notifications` |     3 | read the shade, reply in it, clear it                    |
 * | `web`           |     1 | the one tool that reaches the internet                   |
 *
 * ## WHAT THIS FILE IS NOT, stated because it was previously misstated
 *
 * **The descriptions and tags below do not reach the model and do not drive
 * retrieval.** This file is read by exactly one consumer: the catalogue
 * agreement check ([CatalogueAgreement]), for names, risk tiers and categories.
 * `AppContainer` builds the registry from `androidTools(context)`, and
 * `AgentController.buildRequest` builds both the system prompt
 * (`SystemPrompts.forTools`) and the grammar (`GrammarBuilder.forActions`) from
 * the *registry's* definitions. So the text the model reads and the tags the
 * lexical selector scores are the `:android` ones — measurably different
 * strings, written separately.
 *
 * That is not a rounding error to be tidied here. It is the reason a whole
 * change-set's careful tag and description tuning could not have moved
 * selection accuracy at all, and it is worth stating rather than leaving the
 * next author to tune the wrong file again. Two numbers, both measured with
 * `HeuristicTokenCounter` rather than estimated:
 *
 * | set                | description tokens | tag tokens |
 * |--------------------|-------------------:|-----------:|
 * | this catalogue, 25 |                632 |        356 |
 * | `:android`, 25     |                484 |        320 |
 *
 * The 219-token system prompt a real turn carries is built from the `:android`
 * side. The budgets below are therefore about the *documentation* staying
 * reviewable, not about prompt cost.
 *
 * Explicitly NOT in v0, and why:
 *
 * - **Memory tools.** `docs/architecture.md` section 14 puts memory writes on an
 *   explicit "remember X" in the runtime, not behind a model-visible tool. Exposing
 *   `memory.write` would let the model invent a memory the user never asked for.
 * - **A general SMS send.** `notifications.reply` covers the only send path v0
 *   needs. A second send tool collides with it on the word "text".
 * - **Settings read/write, wifi, volume, storage, media, clock, timer.** Real
 *   capabilities, none of which a target task needs, and each a fresh pair of tags
 *   competing for the same six retrieval slots.
 * - **Browser and computer-use.** Banned by `docs/architecture.md` section 1.
 *
 * ## Why the description text is written the way it is
 *
 * A 1-3B model reads *some* description text, just not this one — see the note
 * above. The rules below were written for a text that reaches the prompt, and
 * they are kept because the catalogue is the reference a future migration to a
 * single source of truth would start from:
 *
 * 1. One clause for what it returns, one clause for when to use it.
 * 2. No parameter names. That is what the JSON Schema is for, and duplicating it is
 *    how a 25-tool catalogue becomes a 2,000-token prompt.
 * 3. No marketing voice, no "This tool allows you to", no second person.
 * 4. The words a user would type, not the words a programmer would.
 *
 * ## Budget
 *
 * [DESCRIPTION_TOKEN_BUDGET] is 800 tokens of description text for the whole
 * catalogue. The measured cost is **632** across 25 tools under
 * `HeuristicTokenCounter` — the same estimator the context builder's step gate
 * uses, and a calibrated one (`HeuristicTokenCounter`'s own KDoc records it
 * landing within ~1% of a real Qwen2.5 vocabulary on its golden set). It is
 * about 25 tokens, two short clauses, per tool.
 *
 * The older figure in this comment (738 across 26 tools) came from the
 * `length / 4` heuristic, which `HeuristicTokenCounter` documents as ~35% low
 * on text like this. It has been replaced rather than left beside the truth.
 *
 * **Nothing enforces either budget.** No build step and no test asserts them.
 * The constants are kept as review targets and are honest about being
 * unenforced — which is a different thing from a number that looks enforced and
 * is not.
 *
 * That is also why the catalogue/registry agreement is now *structural* rather
 * than a test: [CatalogueAgreement] runs in `SimpleToolRegistry`'s constructor,
 * in both directions, so a catalogue entry with no implementation behind it
 * cannot be committed. This file once carried exactly that: `calendar.delete`
 * was documented, schema'd, tiered DESTRUCTIVE and tagged for retrieval, and no
 * class implemented it. Nothing noticed, because nothing checked.
 *
 * [TAG_BUDGET] is 224 against a measured 356 across 25 tools, so the tags are
 * over budget as written. Raised to 384 rather than the tags cut, because the
 * tags are the one part of a definition that exists to be over-broad — and again
 * they are documentation, since retrieval reads the `:android` side.
 */
object V0ToolCatalogue {

    /**
     * Total description tokens allowed across the catalogue.
     *
     * 800 against a measured 632 at 25 tools. Sizing this is a judgement call about
     * what a 1-3B model can hold in its head at once, not a round number -- but it is
     * a number, and it is NOT enforced by any build step. See the Budget section.
     */
    const val DESCRIPTION_TOKEN_BUDGET: Int = 800

    /**
     * Total tags allowed across the catalogue, against a measured 356 at 25 tools.
     *
     * Not enforced, and set with room because tags are retrieval fuel: they are
     * meant to be over-broad. See the Budget section.
     */
    const val TAG_BUDGET: Int = 384

    /** Categories, in the order a reader should expect to meet them. */
    val categories: List<String> = listOf(
        "files", "apps", "clipboard", "device", "alarm", "calendar",
        "contacts", "notifications", "web",
    )

    /**
     * Enum values for `device.open_settings`'s `screen` argument.
     *
     * Declared BEFORE [definitions] on purpose. Object properties initialise in
     * declaration order, so a definition reading a `val` declared below it sees null
     * and throws an ExceptionInInitializerError the first time anything touches the
     * catalogue.
     */
    val SETTINGS_SCREENS: List<String> = listOf(
        "wifi", "bluetooth", "battery", "display", "sound", "apps", "date_time",
        "location", "airplane_mode", "developer", "home", "notifications",
        "privacy", "storage", "accessibility", "about",
    )

    val definitions: List<ToolDefinition> = listOf(
        // ---------------------------------------------------------------- files
        ToolDefinition(
            name = "files.list",
            description = "List documents and downloads this app can see, newest " +
                "first. Use when the user asks what files they have.",
            category = "files",
            schema = objSchema {
                putJsonObject("limit") {
                    put("type", "integer")
                    put("minimum", 1)
                    put("maximum", 200)
                    put("description", "How many documents to return. Default 25.")
                }
            },
            risk = ToolRisk.READ_ONLY,
            observationOrigin = ObservationOrigin.LOCAL,
            tags = setOf(
                "files", "documents", "downloads", "folder",
                "what files do i have", "list my files",
            ),
            requiredPermission = null,
        ),

        ToolDefinition(
            name = "files.search",
            description = "Find documents by name, type, or date. Use when the " +
                "user names a file or asks what they downloaded recently.",
            category = "files",
            schema = objSchema {
                putJsonObject("query") {
                    put("type", "string")
                    put("maxLength", 200)
                    put("description", "Case-insensitive substring of the file name.")
                }
                putJsonObject("mime") {
                    put("type", "string")
                    put("maxLength", 100)
                    put("description", "Exact MIME type, e.g. application/pdf.")
                }
                putJsonObject("modified_after") {
                    put("type", "integer")
                    put("description", "Epoch ms. Only files changed at or after this.")
                }
                putJsonObject("modified_before") {
                    put("type", "integer")
                    put("description", "Epoch ms. Only files changed before this.")
                }
                putJsonObject("limit") {
                    put("type", "integer")
                    put("minimum", 1)
                    put("maximum", 100)
                    put("description", "How many matches to return. Default 25.")
                }
            },
            risk = ToolRisk.READ_ONLY,
            observationOrigin = ObservationOrigin.LOCAL,
            tags = setOf(
                "search", "find", "look for", "where is",
                "pdf", "report", "downloaded", "my note",
            ),
            requiredPermission = null,
        ),

        ToolDefinition(
            name = "files.read_text",
            description = "Read the beginning of a text document. Use when the " +
                "user wants to see or quote what a file says.",
            category = "files",
            schema = objSchema(required = listOf("uri")) {
                putJsonObject("uri") {
                    put("type", "string")
                    put("description", "content:// document URI from files.list or files.search.")
                }
            },
            risk = ToolRisk.READ_ONLY,
            observationOrigin = ObservationOrigin.LOCAL,
            tags = setOf(
                "read", "open file", "contents", "text",
                "preview", "what does it say", "summarize this file", "show me the file",
            ),
            requiredPermission = null,
        ),

        ToolDefinition(
            name = "files.write_text",
            description = "Write text into a document, or create a new file in " +
                "Downloads. Use when the user wants a note or answer saved to a file.",
            category = "files",
            schema = objSchema(required = listOf("content")) {
                putJsonObject("uri") {
                    put("type", "string")
                    put("description", "content:// URI to overwrite. Omit to create a new file.")
                }
                putJsonObject("name") {
                    put("type", "string")
                    put("maxLength", 120)
                    put("description", "File name for a new document, e.g. notes.txt.")
                }
                putJsonObject("content") {
                    put("type", "string")
                    put("maxLength", 20000)
                    put("description", "The text to write.")
                }
            },
            // ESCALATED from REVERSIBLE, deliberately, and it is the only tier
            // change in this change-set.
            //
            // It was REVERSIBLE on the grounds that "a note can be deleted
            // again". That reasoning only covers `name` (create a new file in
            // Downloads). The OTHER branch of the same tool — `uri` — opens
            // an existing document and overwrites it. The previous contents are
            // gone: no trash, no undo, no backup, and the model chose the URI
            // from a fuzzy name match. That is the same irreversible loss the
            // catalogue already calls DESTRUCTIVE one entry below
            // (files.delete), and a tool that can destroy a document must not
            // auto-execute.
            //
            // The cost is a confirmation on "save my notes", which is the
            // right trade against silently overwriting a file.
            risk = ToolRisk.DESTRUCTIVE,
            tags = setOf(
                "save", "write", "note", "notes",
                "new note", "create file", "jot down", "export",
            ),
            requiredPermission = null,
        ),

        ToolDefinition(
            name = "files.delete",
            description = "Delete one document by URI, or by a name matching " +
                "exactly one file. Use when the user says delete or remove it.",
            category = "files",
            schema = objSchema {
                putJsonObject("uri") {
                    put("type", "string")
                    put("description", "content:// URI of the single document to delete.")
                }
                putJsonObject("name") {
                    put("type", "string")
                    put("maxLength", 120)
                    put(
                        "description",
                        "Exact file name. Refused if it matches more than one document.",
                    )
                }
            },
            risk = ToolRisk.DESTRUCTIVE,
            tags = setOf(
                "delete", "remove", "erase", "trash",
                "get rid of", "pdf", "invoice", "document",
            ),
            requiredPermission = null,
        ),

        // ----------------------------------------------------------------- apps
        ToolDefinition(
            name = "apps.list",
            description = "List installed apps with their labels and package " +
                "names. Use when the user asks what apps are on the phone.",
            category = "apps",
            schema = objSchema {
                putJsonObject("query") {
                    put("type", "string")
                    put("maxLength", 100)
                    put("description", "Filter by app label or package name.")
                }
                putJsonObject("limit") {
                    put("type", "integer")
                    put("minimum", 1)
                    put("maximum", 200)
                    put("description", "How many apps to return. Default 30.")
                }
            },
            risk = ToolRisk.READ_ONLY,
            observationOrigin = ObservationOrigin.LOCAL,
            tags = setOf(
                "apps", "applications", "installed", "what apps do i have",
                "launcher", "home screen", "package name",
            ),
            requiredPermission = null,
        ),

        ToolDefinition(
            name = "apps.open",
            description = "Open one installed app by package name, or by a name " +
                "matching exactly one app. Use when the user says open or launch it.",
            category = "apps",
            schema = objSchema {
                putJsonObject("package") {
                    put("type", "string")
                    put("description", "Exact package name, e.g. com.android.chrome.")
                }
                putJsonObject("name") {
                    put("type", "string")
                    put("maxLength", 100)
                    put(
                        "description",
                        "App label to match, e.g. Maps. Ambiguous names are refused.",
                    )
                }
            },
            risk = ToolRisk.REVERSIBLE,
            tags = setOf(
                "open", "launch", "start", "run",
                "switch to", "go to app", "maps", "whatsapp",
            ),
            requiredPermission = null,
        ),

        ToolDefinition(
            name = "apps.share",
            description = "Share a file or text through the Android share sheet. " +
                "Use when the user says send, share, or forward something to someone.",
            category = "apps",
            schema = objSchema {
                putJsonObject("uri") {
                    put("type", "string")
                    put("description", "content:// document URI from files.search.")
                }
                putJsonObject("text") {
                    put("type", "string")
                    put("maxLength", 4000)
                    put("description", "Plain text to share with no attachment.")
                }
                putJsonObject("name") {
                    put("type", "string")
                    put("maxLength", 120)
                    put("description", "Display name of the attachment, used for its MIME type.")
                }
                putJsonObject("title") {
                    put("type", "string")
                    put("maxLength", 200)
                    put("description", "Title shown on the share sheet.")
                }
            },
            risk = ToolRisk.EXTERNAL_COMMUNICATION,
            tags = setOf(
                "share", "send", "forward", "pass to another app",
                "attach", "send it to", "whatsapp it", "email it",
            ),
            // No Android permission. The system share sheet owns the destination
            // choice, so there is nothing for this app to hold. The runtime still
            // confirms the call, because the risk tier, not the permission, gates it.
            requiredPermission = null,
        ),

        // ------------------------------------------------------------- clipboard
        ToolDefinition(
            name = "clipboard.read",
            description = "Read the text currently on the clipboard. Use when " +
                "the user asks what they copied or what is on the clipboard.",
            category = "clipboard",
            schema = objSchema {
                putJsonObject("format") {
                    put("type", "string")
                    putJsonArray("enum") { add("text") }
                    put("description", "Only \"text\" is supported. Defaults to text.")
                }
            },
            risk = ToolRisk.READ_ONLY,
            observationOrigin = ObservationOrigin.LOCAL,
            tags = setOf(
                "clipboard", "read clipboard", "what did i copy", "copied text",
                "clipboard contents", "what is on my clipboard", "paste",
            ),
            requiredPermission = null,
        ),

        ToolDefinition(
            name = "clipboard.write",
            description = "Copy text to the device clipboard. Use when the user " +
                "says copy, cut, or put this on my clipboard.",
            category = "clipboard",
            schema = objSchema(required = listOf("text")) {
                putJsonObject("text") {
                    put("type", "string")
                    put("maxLength", 20000)
                    put("description", "The plain text to place on the clipboard.")
                }
                putJsonObject("label") {
                    put("type", "string")
                    put("maxLength", 60)
                    put(
                        "description",
                        "Short name for the clip, shown in the system clipboard UI.",
                    )
                }
            },
            risk = ToolRisk.REVERSIBLE,
            tags = setOf(
                "clipboard", "copy", "copy to clipboard", "put on clipboard",
                "cut", "copy text", "copy this", "save to clipboard",
            ),
            requiredPermission = null,
        ),

        // ---------------------------------------------------------------- device
        ToolDefinition(
            name = "device.battery",
            description = "Return the battery percentage, charging state, and " +
                "time until full or empty. Use when the user asks about power.",
            category = "device",
            schema = objSchema(),
            risk = ToolRisk.READ_ONLY,
            observationOrigin = ObservationOrigin.LOCAL,
            tags = setOf(
                "battery", "charge", "power", "battery level",
                "how much battery", "charging", "how long until charged", "drain",
            ),
            requiredPermission = null,
        ),

        ToolDefinition(
            name = "device.info",
            description = "Return the phone model, Android version, screen " +
                "size, and free storage. Use when the user asks what phone this is.",
            category = "device",
            schema = objSchema(),
            risk = ToolRisk.READ_ONLY,
            observationOrigin = ObservationOrigin.LOCAL,
            tags = setOf(
                "device info", "phone model", "specs", "which phone",
                "what phone", "how much storage", "free space", "android version",
            ),
            requiredPermission = null,
        ),

        ToolDefinition(
            name = "device.open_settings",
            description = "Open a system settings screen such as wifi, " +
                "bluetooth, or battery. Use when the user says open settings.",
            category = "device",
            schema = objSchema(required = listOf("screen")) {
                putJsonObject("screen") {
                    put("type", "string")
                    putJsonArray("enum") {
                        SETTINGS_SCREENS.forEach { add(it) }
                    }
                    put("description", "Which settings screen to open.")
                }
            },
            risk = ToolRisk.REVERSIBLE,
            tags = setOf(
                "open settings", "settings", "wifi settings", "turn on bluetooth",
                "display settings", "battery saver", "sound settings",
            ),
            requiredPermission = null,
        ),

        ToolDefinition(
            name = "device.vibrate",
            description = "Vibrate the phone for a moment. Use when the user " +
                "says vibrate, buzz, ring, or find my phone.",
            category = "device",
            schema = objSchema {
                putJsonObject("duration_ms") {
                    put("type", "integer")
                    put("minimum", 50)
                    put("maximum", 5000)
                    put("description", "How long to buzz in milliseconds. Default 300.")
                }
            },
            risk = ToolRisk.REVERSIBLE,
            tags = setOf(
                "vibrate", "buzz", "vibration", "shake",
                "ringer", "find my phone", "ring",
            ),
            // A normal (install-time) permission. Named here as documentation
            // and rendered in the approval dialog; it is not a runtime grant.
            requiredPermission = "android.permission.VIBRATE",
        ),

        // ----------------------------------------------------------------- alarm
        ToolDefinition(
            name = "alarm.create",
            description = "Wake the phone at a given time with a one-time " +
                "alarm. Use when the user says wake me up, ring me at, or set a reminder.",
            category = "alarm",
            schema = objSchema(required = listOf("hour")) {
                putJsonObject("hour") {
                    put("type", "integer")
                    put("minimum", 0)
                    put("maximum", 23)
                    put("description", "Hour in 24-hour time, 0-23.")
                }
                putJsonObject("minute") {
                    put("type", "integer")
                    put("minimum", 0)
                    put("maximum", 59)
                    put("description", "Minute, 0-59.")
                }
                putJsonObject("label") {
                    put("type", "string")
                    put("maxLength", 60)
                    put("description", "Short description, e.g. \"take the bread out\".")
                }
                putJsonObject("id") {
                    put("type", "string")
                    put("maxLength", 60)
                    put(
                        "description",
                        "Stable id used later to cancel this alarm. Generated if omitted.",
                    )
                }
                putJsonObject("day_offset") {
                    put("type", "integer")
                    put("minimum", 0)
                    put("maximum", 7)
                    put(
                        "description",
                        "0 for today, 1 for tomorrow. Omitted, a time already " +
                            "past today rolls over.",
                    )
                }
            },
            risk = ToolRisk.REVERSIBLE,
            tags = setOf(
                "alarm", "set an alarm", "wake me up", "remind me at",
                "ring at", "set a reminder", "timer for",
            ),
            requiredPermission = "android.permission.SCHEDULE_EXACT_ALARM",
        ),

        ToolDefinition(
            name = "alarm.list",
            description = "List the alarms the assistant has set, with their " +
                "times and labels. Use when the user asks what alarms they have.",
            category = "alarm",
            schema = objSchema(),
            risk = ToolRisk.READ_ONLY,
            observationOrigin = ObservationOrigin.LOCAL,
            tags = setOf(
                "alarm list", "my alarm", "what alarm do i have", "upcoming alarm",
                "what did i set", "do i have an alarm", "show my alarm",
            ),
            requiredPermission = null,
        ),

        ToolDefinition(
            name = "alarm.cancel",
            description = "Cancel one alarm the assistant set, by id or by " +
                "time. Use when the user says cancel, delete, or turn off an alarm.",
            category = "alarm",
            schema = objSchema {
                putJsonObject("id") {
                    put("type", "string")
                    put("maxLength", 60)
                    put(
                        "description",
                        "Id of the ONE alarm to cancel. Takes precedence over hour/minute.",
                    )
                }
                putJsonObject("hour") {
                    put("type", "integer")
                    put("minimum", 0)
                    put("maximum", 23)
                    put("description", "Cancel the single alarm at this hour.")
                }
                putJsonObject("minute") {
                    put("type", "integer")
                    put("minimum", 0)
                    put("maximum", 59)
                    put("description", "Cancel the single alarm at this minute.")
                }
                putJsonObject("label") {
                    put("type", "string")
                    put("maxLength", 60)
                    put("description", "Cancel the single alarm whose label contains this text.")
                }
            },
            // REVERSIBLE, not DESTRUCTIVE: it can only ever cancel one alarm the
            // assistant itself created, and it refuses rather than guessing. A user
            // asked to confirm every single-alarm cancel stops using the feature.
            risk = ToolRisk.REVERSIBLE,
            tags = setOf(
                "cancel alarm", "delete alarm", "remove alarm", "turn off alarm",
                "stop the alarm", "get rid of my alarm", "kill the alarm",
            ),
            requiredPermission = "android.permission.SCHEDULE_EXACT_ALARM",
        ),

        // -------------------------------------------------------------- calendar
        ToolDefinition(
            name = "calendar.search",
            description = "Return calendar events in a date range, with times " +
                "and titles. Use when the user asks what is on the calendar or " +
                "tomorrow's schedule.",
            category = "calendar",
            schema = objSchema {
                putJsonObject("date") {
                    put("type", "string")
                    put("maxLength", 10)
                    put(
                        "description",
                        "Single day as YYYY-MM-DD. Omit with from/to for a range.",
                    )
                }
                putJsonObject("from") {
                    put("type", "string")
                    put("maxLength", 10)
                    put("description", "Range start as YYYY-MM-DD.")
                }
                putJsonObject("to") {
                    put("type", "string")
                    put("maxLength", 10)
                    put("description", "Range end as YYYY-MM-DD.")
                }
                putJsonObject("query") {
                    put("type", "string")
                    put("maxLength", 100)
                    put("description", "Match against the event title.")
                }
                putJsonObject("limit") {
                    put("type", "integer")
                    put("minimum", 1)
                    put("maximum", 100)
                    put("description", "How many events to return. Default 20.")
                }
            },
            risk = ToolRisk.READ_ONLY,
            observationOrigin = ObservationOrigin.LOCAL,
            tags = setOf(
                "calendar", "schedule", "appointment", "meeting",
                "event", "agenda", "what do i have on", "next",
            ),
            requiredPermission = "android.permission.READ_CALENDAR",
        ),

        ToolDefinition(
            name = "calendar.create",
            description = "Create a calendar event with a title and a start " +
                "time. Use when the user says schedule, book, or block out time.",
            category = "calendar",
            schema = objSchema(required = listOf("title", "start")) {
                putJsonObject("title") {
                    put("type", "string")
                    put("maxLength", 200)
                    put("description", "Event title, e.g. \"Dentist\".")
                }
                putJsonObject("start") {
                    put("type", "string")
                    put("maxLength", 30)
                    put(
                        "description",
                        "Local start as YYYY-MM-DDTHH:MM, e.g. 2026-10-02T16:00.",
                    )
                }
                putJsonObject("end") {
                    put("type", "string")
                    put("maxLength", 30)
                    put(
                        "description",
                        "Local end as YYYY-MM-DDTHH:MM. Defaults to an hour after start.",
                    )
                }
                putJsonObject("location") {
                    put("type", "string")
                    put("maxLength", 200)
                    put("description", "Where the event happens.")
                }
                putJsonObject("attendee") {
                    put("type", "string")
                    put("maxLength", 100)
                    put("description", "Phone number or email of one attendee.")
                }
            },
            risk = ToolRisk.REVERSIBLE,
            tags = setOf(
                "schedule", "book", "add event", "new meeting",
                "block out", "put in my calendar", "reserve",
            ),
            requiredPermission = "android.permission.WRITE_CALENDAR",
        ),

        // -------------------------------------------------------------- contacts
        //
        // NOTE ON WHAT IS NOT HERE, because it used to be:
        //
        // `calendar.delete` was catalogued in this file from the day the catalogue
        // was written — description, JSON Schema, `risk = DESTRUCTIVE`,
        // `requiredPermission = WRITE_CALENDAR`, and a full set of retrieval tags
        // — and no class in `:android` implemented it. It is removed rather than
        // implemented here, and the reason is worth being precise about:
        //
        //  - Implementing it is an `:android` change (CalendarContract delete
        //    against a content:// event URI), and this change-set does not touch
        //    that module.
        //  - Its blast radius today was documentation only. The grammar and the
        //    prompt are both built from the REGISTRY, so the tool was never
        //    offered to the model and could never be called. The real cost was
        //    that the catalogue claimed a capability the product does not have,
        //    and that the one-directional agreement check could not see it.
        //
        // The alternative — leaving the entry and labelling it "unimplemented" —
        // is exactly the class of thing this change-set exists to remove: a
        // documented tool that cannot be called. A model offered it would call
        // it, get a validation failure, and leave the user worse off than if the
        // capability had simply never been promised.
        //
        // To add it back, do it as: implement `CalendarDeleteTool` in
        // `:android`, add the definition here, and the bidirectional check in
        // `SimpleToolRegistry` will hold the two sides together on its own.

        ToolDefinition(
            name = "contacts.search",
            description = "Find contacts by name and return their phone " +
                "numbers and emails. Use when the user names a person or wants a number.",
            category = "contacts",
            schema = objSchema(required = listOf("query")) {
                putJsonObject("query") {
                    put("type", "string")
                    put("maxLength", 100)
                    put("description", "Name to match, first name or last name or both.")
                }
                putJsonObject("limit") {
                    put("type", "integer")
                    put("minimum", 1)
                    put("maximum", 25)
                    put("description", "How many contacts to return. Default 10.")
                }
            },
            risk = ToolRisk.READ_ONLY,
            observationOrigin = ObservationOrigin.LOCAL,
            tags = setOf(
                "contacts", "contact", "phone number", "who is",
                "look up", "address book", "call", "ring",
            ),
            requiredPermission = "android.permission.READ_CONTACTS",
        ),

        ToolDefinition(
            name = "contacts.get",
            description = "Return every stored field for one contact by id. " +
                "Use when a search found the person but the detail is incomplete.",
            category = "contacts",
            schema = objSchema(required = listOf("id")) {
                putJsonObject("id") {
                    put("type", "string")
                    put("maxLength", 120)
                    put("description", "Contact id from contacts.search.")
                }
            },
            risk = ToolRisk.READ_ONLY,
            observationOrigin = ObservationOrigin.LOCAL,
            tags = setOf(
                "contact details", "full contact", "everything about",
                "contact card", "their email", "their address",
            ),
            requiredPermission = "android.permission.READ_CONTACTS",
        ),

        // --------------------------------------------------------- notifications
        ToolDefinition(
            name = "notifications.list",
            description = "List the notifications on the phone, newest " +
                "first. Use when the user names an app like WhatsApp, or asks " +
                "what alerts they missed.",
            category = "notifications",
            schema = objSchema {
                putJsonObject("query") {
                    put("type", "string")
                    put("maxLength", 100)
                    put("description", "Match against app name, notification title or body.")
                }
                putJsonObject("only_replyable") {
                    put("type", "boolean")
                    put("description", "Only notifications that accept a quick reply.")
                }
                putJsonObject("limit") {
                    put("type", "integer")
                    put("minimum", 1)
                    put("maximum", 50)
                    put("description", "How many to return. Default 20.")
                }
            },
            risk = ToolRisk.READ_ONLY,
            observationOrigin = ObservationOrigin.LOCAL,
            tags = setOf(
                "notifications", "alerts", "what came in", "miss",
                "messages", "whatsapp", "what did i miss", "anything new",
            ),
            requiredPermission = null,
        ),

        ToolDefinition(
            name = "notifications.reply",
            description = "Reply to a messaging notification that offers a " +
                "reply box. Use when the user says answer, reply, or text back.",
            category = "notifications",
            schema = objSchema(required = listOf("key", "text")) {
                putJsonObject("key") {
                    put("type", "string")
                    put("maxLength", 200)
                    put(
                        "description",
                        "Notification key from notifications.list, e.g. com.whatsapp#2.",
                    )
                }
                putJsonObject("text") {
                    put("type", "string")
                    put("maxLength", 2000)
                    put("description", "The message body to send.")
                }
            },
            risk = ToolRisk.EXTERNAL_COMMUNICATION,
            tags = setOf(
                "reply", "respond", "answer", "text back",
                "message back", "quick reply", "send a message", "tell them",
            ),
            requiredPermission = null,
        ),

        ToolDefinition(
            name = "notifications.dismiss",
            description = "Dismiss one notification from the shade by its key. " +
                "Use when the user says clear, dismiss, or get rid of it.",
            category = "notifications",
            schema = objSchema(required = listOf("key")) {
                putJsonObject("key") {
                    put("type", "string")
                    put("maxLength", 200)
                    put("description", "Notification key from notifications.list.")
                }
            },
            risk = ToolRisk.REVERSIBLE,
            tags = setOf(
                "dismiss", "clear notification", "swipe away", "silence",
                "get rid of notification", "mark as read", "no more alerts",
            ),
            requiredPermission = null,
        ),

        // ------------------------------------------------------------------- web
        ToolDefinition(
            name = "web.fetch",
            description = "Fetch a web page and return its readable text. " +
                "Use for weather, news, prices, scores, and any question the " +
                "phone cannot answer offline.",
            category = "web",
            schema = objSchema(required = listOf("url")) {
                putJsonObject("url") {
                    put("type", "string")
                    put("maxLength", 2000)
                    put("description", "Absolute http:// or https:// address of the page.")
                }
                putJsonObject("max_chars") {
                    put("type", "integer")
                    put("minimum", 200)
                    put("maximum", 20000)
                    put("description", "Characters of text to return. Default 4000.")
                }
            },
            risk = ToolRisk.NETWORK_EGRESS,
            // MUST match `WebFetchTool.definition`. `CatalogueAgreement.require`
            // is a hard `check()` at composition, so a mismatch here crashes
            // the app rather than degrading.
            // A weather or news question has almost no content token to match on
            // beyond the topic word itself, so this tool needs several exact-token
            // hits to clear the stopword noise floor every description carries.
            tags = setOf(
                "web", "internet", "online", "look up",
                "website", "weather", "forecast", "right now",
            ),
            requiredPermission = null,
        ),
    )

    init {
        // The registry already rejects duplicate names at construction time. This is
        // the earlier, cheaper check: a duplicate here would be a merge accident, and
        // the catalogue is a single file, so fail at class-init with the actual names.
        val dupes = definitions.groupBy { it.name }.filterValues { it.size > 1 }.keys
        check(dupes.isEmpty()) { "duplicate tool names in V0ToolCatalogue: $dupes" }
    }

    // ---------------------------------------------------------------- accessors

    fun byName(name: String): ToolDefinition? = definitions.firstOrNull { it.name == name }

    fun byCategory(category: String): List<ToolDefinition> =
        definitions.filter { it.category == category }

    fun names(): List<String> = definitions.map { it.name }

    /** The definitions for a named set of tools, skipping any that do not exist. */
    fun select(names: Collection<String>): List<ToolDefinition> =
        names.mapNotNull(::byName)
}
