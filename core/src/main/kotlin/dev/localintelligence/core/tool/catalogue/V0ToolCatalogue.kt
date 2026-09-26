package dev.localintelligence.core.tool.catalogue

import dev.localintelligence.core.model.ObservationOrigin
import dev.localintelligence.core.tool.ToolDefinition
import dev.localintelligence.core.tool.ToolRisk
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
 * ## What this file holds, and where the descriptions went
 *
 * A tool's **name, description, category and tags live in [ToolMeta]**, one
 * entry per tool, and the `AgentTool` implementations in `:android` build their
 * definitions from those same entries via `ToolMeta.<TOOL>.define(...)`. This
 * file passes the two things that legitimately differ per side — the schema and
 * the risk tier — and nothing else.
 *
 * That split is the point. These fields used to be written out twice, and the
 * two copies were not equal: measured on main before the split, all 25
 * descriptions and 22 of 25 tag sets differed. The `:android` copy was the live
 * one, because `AppContainer` builds the registry from `androidTools(context)`
 * and `AgentController.buildRequest` builds both the system prompt and the
 * grammar from the *registry's* definitions. So a change-set that retuned the
 * text in this file could not have moved selection accuracy at all — the model
 * never saw it. There is no second copy to retune now.
 *
 * The two fields that stay per-side, and why:
 *
 *  - **schema.** The `:android` schemas are the ones the model is given and the
 *    ones `execute()` validates against. They carry richer argument
 *    documentation, per-tool argument names, and `minProperties` constraints
 *    the implementations enforce. The versions below are a shorter restatement
 *    kept as the catalogue's record of each tool's arguments. Collapsing them
 *    onto one side is a real migration with real breakage risk, and it is not
 *    this change's job.
 *  - **risk.** Declared on both sides and checked by [CatalogueAgreement] in
 *    both directions, because [dev.localintelligence.core.policy.RiskPolicy]
 *    gates unattended execution on it.
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

 * ## Budget
 *
 * [DESCRIPTION_TOKEN_BUDGET] is 800 tokens of description text for the whole
 * catalogue. The measured cost is **484** across the 25 tools under
 * `HeuristicTokenCounter` — the same estimator the context builder's step gate
 * uses, and a calibrated one (`HeuristicTokenCounter`'s own KDoc records it
 * landing within ~1% of a real Qwen2.5 vocabulary on its golden set). It is
 * about 19 tokens, two short clauses, per tool.
 *
 * [TAG_BUDGET] is 384 against a measured 320 across the 25 tools, so the tags fit
 * with room, which is right: tags are the one part of a definition that exists
 * to be over-broad.
 *
 * Both budgets are measured against [ToolMeta]'s text, which is the text the
 * model is actually given. **Neither budget is enforced.** No build step and no
 * test asserts them. The constants are review targets and are honest about being
 * unenforced — which is a different thing from a number that looks enforced and
 * is not.
 *
 * The catalogue/registry agreement is *structural* rather than a test:
 * [CatalogueAgreement] runs in `SimpleToolRegistry`'s constructor, in both
 * directions, so a catalogue entry with no implementation behind it cannot be
 * committed, and a shipped tool the catalogue has never heard of cannot be
 * built. That check once had a hole worth naming: `calendar.delete` was
 * documented, schema'd, tiered DESTRUCTIVE and tagged here, and no class
 * implemented it. Nothing noticed, because the check only ran one way.
 */
object V0ToolCatalogue {

    /**
     * Total description tokens allowed across the catalogue.
     *
     * 800 against a measured 484 at 25 tools. Sizing this is a judgement call
     * about what a 1-3B model can hold in its head at once, not a round number
     * -- but it is a number, and it is NOT enforced by any build step.
     */
    const val DESCRIPTION_TOKEN_BUDGET: Int = 800

    /**
     * Total tags allowed across the catalogue, against a measured 320 at 25 tools.
     *
     * Not enforced, and set with room because tags are retrieval fuel: they are
     * meant to be over-broad.
     */
    const val TAG_BUDGET: Int = 384

    /** Categories, in the order a reader should expect to meet them. */
    val categories: List<String> = listOf(
        "files", "apps", "clipboard", "device", "alarm", "calendar",
        "contacts", "notifications", "web",
    )

    // `SETTINGS_SCREENS` used to live here: a sixteen-entry list of settings
    // screens, declared "for `device.open_settings`'s `screen` argument". It is
    // gone, and it was wrong. The tool resolves model input against
    // [SettingsScreen], which has five entries, so eleven of the sixteen
    // (`accessibility`, `date_time`, `developer`, ...) named a screen the tool
    // would refuse to open. It was invisible because the schema below never
    // read it — the `objSchema` literal hardcoded its own enum — so the list
    // was a third declaration of an idea the code already owned twice.
    //
    // The order note that used to sit here still applies to anything else added
    // to this object: properties initialise in declaration order, so a
    // definition reading a `val` declared below it sees null and throws an
    // ExceptionInInitializerError the first time anything touches the catalogue.

    val definitions: List<ToolDefinition> = listOf(
        // ---------------------------------------------------------------- files
        ToolMeta.FILES_LIST.define(
                schema = ToolSchemas.filesList,
            risk = ToolRisk.READ_ONLY,
        ),

        ToolMeta.FILES_SEARCH.define(
                schema = ToolSchemas.filesSearch,
            risk = ToolRisk.READ_ONLY,
        ),

        ToolMeta.FILES_READ_TEXT.define(
                schema = ToolSchemas.filesReadText,
            risk = ToolRisk.READ_ONLY,
        ),

        ToolMeta.FILES_WRITE_TEXT.define(
                schema = ToolSchemas.filesWriteText,
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
        ),

        ToolMeta.FILES_DELETE.define(
                schema = ToolSchemas.filesDelete,
            risk = ToolRisk.DESTRUCTIVE,
        ),

        // ----------------------------------------------------------------- apps
        ToolMeta.APPS_LIST.define(
                schema = ToolSchemas.appsList,
            risk = ToolRisk.READ_ONLY,
        ),

        ToolMeta.APPS_OPEN.define(
                schema = ToolSchemas.appsOpen,
            risk = ToolRisk.REVERSIBLE,
        ),

        ToolMeta.APPS_SHARE.define(
                schema = ToolSchemas.appsShare,
            risk = ToolRisk.EXTERNAL_COMMUNICATION,
            // No Android permission. The system share sheet owns the destination
            // choice, so there is nothing for this app to hold. The runtime still
            // confirms the call, because the risk tier, not the permission, gates it.
            requiredPermission = null,
        ),

        // ------------------------------------------------------------- clipboard
        ToolMeta.CLIPBOARD_READ.define(
                schema = ToolSchemas.clipboardRead,
            risk = ToolRisk.READ_ONLY,
        ),

        ToolMeta.CLIPBOARD_WRITE.define(
                schema = ToolSchemas.clipboardWrite,
            risk = ToolRisk.REVERSIBLE,
        ),

        // ---------------------------------------------------------------- device
        ToolMeta.DEVICE_BATTERY.define(
                schema = ToolSchemas.deviceBattery,
            risk = ToolRisk.READ_ONLY,
        ),

        ToolMeta.DEVICE_INFO.define(
                schema = ToolSchemas.deviceInfo,
            risk = ToolRisk.READ_ONLY,
        ),

        ToolMeta.DEVICE_OPEN_SETTINGS.define(
                schema = ToolSchemas.deviceOpenSettings,
            risk = ToolRisk.REVERSIBLE,
        ),

        ToolMeta.DEVICE_VIBRATE.define(
                schema = ToolSchemas.deviceVibrate,
            risk = ToolRisk.REVERSIBLE,
            // A normal (install-time) permission. Named here as documentation
            // and rendered in the approval dialog; it is not a runtime grant.
            requiredPermission = "android.permission.VIBRATE",
        ),

        // ----------------------------------------------------------------- alarm
        ToolMeta.ALARM_CREATE.define(
                schema = ToolSchemas.alarmCreate,
            risk = ToolRisk.REVERSIBLE,
            requiredPermission = "android.permission.SCHEDULE_EXACT_ALARM",
        ),

        ToolMeta.ALARM_LIST.define(
                schema = ToolSchemas.alarmList,
            risk = ToolRisk.READ_ONLY,
        ),

        ToolMeta.ALARM_CANCEL.define(
                schema = ToolSchemas.alarmCancel,
            // REVERSIBLE, not DESTRUCTIVE: it can only ever cancel one alarm the
            // assistant itself created, and it refuses rather than guessing. A user
            // asked to confirm every single-alarm cancel stops using the feature.
            risk = ToolRisk.REVERSIBLE,
            requiredPermission = "android.permission.SCHEDULE_EXACT_ALARM",
        ),

        // -------------------------------------------------------------- calendar
        ToolMeta.CALENDAR_SEARCH.define(
                schema = ToolSchemas.calendarSearch,
            risk = ToolRisk.READ_ONLY,
            requiredPermission = "android.permission.READ_CALENDAR",
        ),

        ToolMeta.CALENDAR_CREATE.define(
                schema = ToolSchemas.calendarCreate,
            risk = ToolRisk.REVERSIBLE,
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
        // To add it back, do it as: add a `CALENDAR_DELETE` entry to [ToolMeta],
        // implement `CalendarDeleteTool` in `:android` with
        // `ToolMeta.CALENDAR_DELETE.define(...)`, add the definition here, and
        // the bidirectional check in `SimpleToolRegistry` will hold the two
        // sides together on its own.

        ToolMeta.CONTACTS_SEARCH.define(
                schema = ToolSchemas.contactsSearch,
            risk = ToolRisk.READ_ONLY,
            requiredPermission = "android.permission.READ_CONTACTS",
        ),

        ToolMeta.CONTACTS_GET.define(
                schema = ToolSchemas.contactsGet,
            risk = ToolRisk.READ_ONLY,
            requiredPermission = "android.permission.READ_CONTACTS",
        ),

        // --------------------------------------------------------- notifications
        ToolMeta.NOTIFICATIONS_LIST.define(
                schema = ToolSchemas.notificationsList,
            risk = ToolRisk.READ_ONLY,
        ),

        ToolMeta.NOTIFICATIONS_REPLY.define(
                schema = ToolSchemas.notificationsReply,
            risk = ToolRisk.EXTERNAL_COMMUNICATION,
        ),

        ToolMeta.NOTIFICATIONS_DISMISS.define(
                schema = ToolSchemas.notificationsDismiss,
            risk = ToolRisk.REVERSIBLE,
        ),

        // ------------------------------------------------------------------- web
        ToolMeta.WEB_FETCH.define(
            // MUST match `WebFetchTool.definition`. `CatalogueAgreement.require`
            // is a hard `check()` at composition, so a mismatch here crashes the
            // app rather than degrading.
            risk = ToolRisk.NETWORK_EGRESS,
                schema = ToolSchemas.webFetch,
        ),
    )

    init {
        // The registry already rejects duplicate names at construction time. This is
        // the earlier, cheaper check: a duplicate here would be a merge accident, and
        // the catalogue is a single file, so fail at class-init with the actual names.
        val dupes = definitions.groupBy { it.name }.filterValues { it.size > 1 }.keys
        check(dupes.isEmpty()) { "duplicate tool names in V0ToolCatalogue: $dupes" }

        // Every entry must come from a descriptor. Without this, a future edit
        // could add a hand-written ToolDefinition here with a description that
        // the shipped tool does not share, and the drift this file was refactored
        // to remove would come straight back through the one door left open.
        val undescribed = definitions.map { it.name }.filter { ToolMeta.byName(it) == null }
        check(undescribed.isEmpty()) {
            "catalogue entries with no ToolMeta descriptor ($undescribed): every entry " +
                "must be built as ToolMeta.<TOOL>.define(...) so the descriptive " +
                "metadata has exactly one home."
        }

        // The untrusted-content fence and the confirmation dialog are two answers
        // to two different questions, and this is the check that keeps them from
        // being answered about the same tool by accident.
        //
        //   observationOrigin == NETWORK  ->  the RETURNED TEXT was written by a
        //                                       third party, so the model must be
        //                                       told to distrust it.
        //   risk == NETWORK_EGRESS        ->  the CALL ITSELF reaches a third
        //                                       party, so it cannot run unattended.
        //
        // Today both name exactly `web.fetch`, and that coincidence is the whole
        // reason the classification is reviewable: a new tool that opens a socket
        // is now FORCED to declare it in two independent fields, and the risk tier
        // is the one RiskPolicy already gates on, so getting the origin wrong
        // means contradicting the policy tier rather than editing one string in a
        // table. Conversely, a tool cannot be quietly marked DESTRUCTIVE and
        // NETWORK without tripping this.
        //
        // WHY THIS IS NOT THE SAME AS THE ToolMeta NETWORK PIN. That one pins a
        // name list, so it fires when someone re-classifies a KNOWN tool. This one
        // fires when the two fields stop agreeing, which catches the case a name
        // list cannot see: a new NETWORK_EGRESS tool added with a stale LOCAL
        // origin. Two mechanisms, two different failure modes.
        val egress = definitions.filter { it.risk == ToolRisk.NETWORK_EGRESS }
            .map { it.name }.sorted()
        val networkOrigin = definitions
            .filter { it.observationOrigin == ObservationOrigin.NETWORK }
            .map { it.name }.sorted()
        check(egress == networkOrigin) {
            "observation origin and risk tier disagree about which tools reach a " +
                "third party. NETWORK_EGRESS (cannot execute unattended) = $egress, " +
                "but observationOrigin=NETWORK (return value is third-party text) = " +
                "$networkOrigin. A tool that fetches is BOTH; a tool that only reads " +
                "the device is NEITHER. If a tool genuinely retrieves remote text, it " +
                "is NETWORK_EGRESS and NETWORK together — see " +
                "dev.localintelligence.core.model.ObservationOrigin."
        }
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
