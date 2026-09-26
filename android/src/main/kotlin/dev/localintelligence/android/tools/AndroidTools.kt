package dev.localintelligence.android.tools

import android.content.Context
import dev.localintelligence.android.tools.alarm.alarmTools
import dev.localintelligence.android.tools.apps.appTools
import dev.localintelligence.android.tools.calendar.calendarTools
import dev.localintelligence.android.tools.clipboard.clipboardTools
import dev.localintelligence.android.tools.contacts.contactsTools
import dev.localintelligence.android.tools.device.deviceTools
import dev.localintelligence.android.tools.files.fileTools
import dev.localintelligence.android.tools.notifications.notificationTools
import dev.localintelligence.android.tools.web.webTools
import dev.localintelligence.core.tool.AgentTool
import dev.localintelligence.core.tool.catalogue.V0ToolCatalogue

/**
 * The complete shipped Android tool set, in catalogue order, checked against the
 * catalogue before it is handed to the loop.
 *
 * THIS IS THE ONE PLACE THAT KNOWS THE SHIPPED SET. It lives in `:android`
 * rather than in `AppContainer` because the tools are `:android`'s and `:app` is
 * supposed to be Compose and nothing else (`docs/architecture.md` §4): a `:app`
 * that lists the tool families is a `:app` that has to be edited every time a
 * tool lands.
 *
 * Adding a tool means editing two files — its own implementation in
 * `android/.../tools/<family>/`, which carries only the schema and the risk
 * tier, and the one [dev.localintelligence.core.tool.catalogue.ToolMeta]
 * descriptor that holds its name, description, category and tags. The entry
 * below cannot drift from any of them: the definitions are built from the same
 * descriptors, so `init` failing loudly means two of those files genuinely
 * disagree rather than that somebody forgot to copy a string across.
 *
 * ## Why it is cheap to call
 *
 * Every entry is a constructor that stores a `Context` or a platform seam and
 * builds a [dev.localintelligence.core.tool.ToolDefinition] from literals. No
 * platform call happens here — the `applicationContext` lookups in the platform
 * adapters are `by lazy` for exactly this reason. So the whole set is a few
 * kilobytes of objects, and `AppContainer` can build it in its own `by lazy`
 * without pulling a system service into a cold start.
 *
 * ## What this is NOT
 *
 * It does not filter, prioritise or rank. Selection is
 * [dev.localintelligence.core.tool.ToolSelector]'s job at run time, and doing it
 * here would mean a second, static selection rule nothing measures.
 */
fun androidTools(context: Context): List<AgentTool> {
    val appContext = context.applicationContext
    // The one platform-truthful answer to "may this app do this right now",
    // built once and shared. See AndroidPlatformGrant for why the runtime's
    // ToolContext.permissionGranted cannot be the source of this fact: it is
    // filled from a permission set nothing populates, so it is false for every
    // permissioned tool on every call.
    val grant = AndroidPlatformGrant(appContext)
    val tools = buildList {
        addAll(deviceTools(appContext, grant))
        addAll(fileTools(appContext, grant))
        addAll(appTools(appContext))
        addAll(clipboardTools(appContext))
        addAll(alarmTools(appContext, grant))
        addAll(calendarTools(appContext, grant))
        addAll(contactsTools(appContext, grant))
        addAll(notificationTools(grant))
        addAll(webTools(grant))
    }
    return requireCatalogueAgreement(tools)
}

/**
 * Fails loudly when a shipped tool and [V0ToolCatalogue] disagree.
 *
 * ## What is checked, and what is deliberately not
 *
 * Checked: that every shipped tool is in the catalogue, and that the risk tier
 * the catalogue declares is the tier the tool declares. Risk is the one field
 * that must never drift, because [dev.localintelligence.core.policy.RiskPolicy]
 * reads `definition.risk` to decide whether a call runs unattended, confirms,
 * or is refused. A tool that quietly declared `REVERSIBLE` while the catalogue
 * said `DESTRUCTIVE` would be auto-executed, and no amount of green tests
 * elsewhere would notice.
 *
 * ## Description and tags: one value, and still checked
 *
 * This function used to carry a paragraph explaining that description, tags,
 * category and schema were deliberately out of scope because the Android
 * schemas carry richer argument documentation and "forcing one side onto the
 * other would be a real piece of work". The schema half of that is still true.
 * The description/tags half is not, and the reason it was ever true is the bug
 * this file's guard used to sit next to without seeing.
 *
 * Descriptions and tags were written out twice, and the two copies differed on
 * all 25 descriptions and 22 of 25 tag sets. Because `AppContainer` builds the
 * registry from `androidTools(context)` and `AgentController.buildRequest` builds
 * both the system prompt and the grammar from the *registry's* definitions, the
 * `:android` text was the only text the model ever saw. The catalogue's prose
 * was documentation of a tool set nobody reads, which is why careful tuning
 * there could not have moved selection accuracy at all.
 *
 * Both sides now build their definitions from the same
 * [dev.localintelligence.core.tool.catalogue.ToolMeta] descriptor, so there is
 * one description and one tag set per tool. The checks below are belt to that
 * braces: they cannot fail through the descriptor, and they fire the moment
 * somebody hand-writes a `ToolDefinition` with its own strings — the one way
 * the duplication can come back, and a way that compiles cleanly.
 *
 * NOT checked: schema. The `:android` schemas are the ones the model is given
 * and the ones `execute()` validates against; they carry richer argument
 * documentation, per-tool argument names, and `minProperties` constraints the
 * implementations enforce. The catalogue's are a shorter restatement kept as
 * documentation. Collapsing them onto one side is a real migration with real
 * breakage risk, and it is recorded as follow-up rather than smuggled in here.
 *
 * ## Why this is an exception and not a warning
 *
 * The failure it catches is silent by construction: a tool that is not in the
 * catalogue cannot be retrieved by the lexical selector's own index, and a
 * mismatched tier is a wrong decision rather than an error. A warning would be
 * logged and shipped. Throwing at composition time means the app does not start
 * with a tool set that can delete something the user was never asked about —
 * which, for a safety invariant, is the correct direction to fail.
 */
private fun requireCatalogueAgreement(tools: List<AgentTool>): List<AgentTool> {
    val unlisted = tools.map { it.definition.name }.filter { V0ToolCatalogue.byName(it) == null }
    check(unlisted.isEmpty()) {
        "these tools ship in the registry but are not in V0ToolCatalogue, so the lexical " +
            "selector's index does not know them and the policy has no catalogue entry to " +
            "agree with: $unlisted. Either add them to the catalogue or stop shipping them."
    }

    val tierMismatch = tools.mapNotNull { tool ->
        val def = tool.definition
        val catalogued = V0ToolCatalogue.byName(def.name) ?: return@mapNotNull null
        if (catalogued.risk != def.risk) "${def.name}: catalogue=${catalogued.risk} tool=${def.risk}"
        else null
    }
    check(tierMismatch.isEmpty()) {
        "risk tier drift between V0ToolCatalogue and the shipped tools. The policy gates on " +
            "the tool's own tier, so a mismatch means the catalogue understates how dangerous " +
            "a call is: $tierMismatch"
    }

    // The one-direction name-and-tier check above is kept separate on purpose:
    // its messages name the failure a reader hits in :android, and it runs
    // before the shared check below so a tool that is not catalogued at all is
    // reported as missing rather than as three simultaneous mismatches.
    val textMismatch = tools.mapNotNull { tool ->
        val def = tool.definition
        val catalogued = V0ToolCatalogue.byName(def.name) ?: return@mapNotNull null
        when {
            catalogued.description != def.description ->
                "${def.name}: description differs from the catalogue entry"
            catalogued.tags != def.tags ->
                "${def.name}: tags differ from the catalogue entry " +
                    "(only in catalogue=${catalogued.tags - def.tags} " +
                    "only in tool=${def.tags - catalogued.tags})"
            else -> null
        }
    }
    check(textMismatch.isEmpty()) {
        "descriptive metadata drift between V0ToolCatalogue and the shipped tools. Both " +
            "sides are supposed to read one string and one tag set from ToolMeta, and the " +
            "shipped tool's copy is the one the model and the selector actually read — so " +
            "this means a ToolDefinition was hand-written instead of built with " +
            "ToolMeta.<TOOL>.define(...). $textMismatch"
    }
    return tools
}
