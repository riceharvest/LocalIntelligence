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
 * ## Why this function exists
 *
 * `AppContainer.androidTools()` returned `emptyList()` on the branch this landed
 * on, with a comment saying the tool workstreams had not landed. They had: all
 * nine families were implemented, exported, and reviewed. The consequence was
 * that the agent loop was handed an empty [dev.localintelligence.core.tool.ToolRegistry]
 * and could not read a battery level, open a file, or look up a contact — the
 * repo was green and the app could not perform one task end to end.
 *
 * This is the one place that knows the shipped set. It lives in `:android`
 * rather than in `AppContainer` because the tools are `:android`'s and `:app` is
 * supposed to be Compose and nothing else (`docs/architecture.md` §4): a `:app`
 * that lists nine tool families is a `:app` that has to be edited every time a
 * tool lands.
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
 * NOT checked: description, tags, category and schema properties. Those differ
 * legitimately today — the Android schemas carry richer argument
 * documentation and a few argument names the catalogue does not have — and
 * forcing one side onto the other would either delete working schema
 * documentation or break `execute()`. Making the catalogue authoritative for
 * the whole definition is a real piece of work with a real migration, and it is
 * recorded as follow-up rather than smuggled in here.
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
    return tools
}
