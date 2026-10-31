package dev.localintelligence.android.cloud.browser

import dev.localintelligence.android.tools.requireCatalogueAgreement
import dev.localintelligence.core.plugin.AgentPlugin
import dev.localintelligence.core.settings.SettingDef
import dev.localintelligence.core.settings.SettingSection
import dev.localintelligence.core.settings.SettingsMode
import dev.localintelligence.android.cloud.CredentialSource
import dev.localintelligence.core.settings.SettingsReader
import dev.localintelligence.core.settings.mode
import dev.localintelligence.core.tool.AgentTool

// ===========================================================================
// A browser in the cloud that the agent can hand a web task to: booking, filling in a form, finding
// a price on a site. The task runs on the user's own Browser Use account, not on the phone, so a
// phone-screen rule does not apply to it.
//
// Off by default, and gated three ways before anything is sent:
//   1. Technical mode only. Simple mode never offers these tools.
//   2. The plugin is switched on.
//   3. A Browser Use key is stored.
// Unlike the sandbox tools, these are offered to any chat model, because a small model can still
// delegate a whole task. Each start asks first (EXTERNAL_COMMUNICATION), so the user sees the full
// instruction and start address before anything runs.
//
// The key is read in one place, [browserKeyOf]. A managed key for Simple users would be read there
// too, so no tool or provider needs to change when it arrives.
// ===========================================================================

/** The settings rows this plugin adds. Keys are prefixed with the plugin id. */
object CloudBrowserSettings {
    const val PLUGIN_ID = "cloud.browser"

    /** One service today. A second one adds an option here and a [CloudBrowserProvider]; nothing else changes. */
    val PROVIDER = SettingDef.Choice(
        "cloud.browser.provider", "Browser service",
        "The service that runs the browser tasks. Browser Use Cloud is the only one so far.",
        SettingSection.PLUGINS, default = BROWSER_USE_ID,
        options = listOf(SettingDef.Choice.Option(BROWSER_USE_ID, "Browser Use Cloud")),
    )

    val API_KEY = SettingDef.Secret(
        "cloud.browser.api_key", "Browser Use API key",
        "Your own key from cloud.browser-use.com. Stored encrypted and never shown back. " +
            "Without one, the browser tools are not offered and nothing is sent.",
        SettingSection.PLUGINS,
    )

    val WAIT_MINUTES = SettingDef.IntValue(
        "cloud.browser.wait_minutes", "Wait for a task",
        "Minutes the agent waits for a browser task before it stops waiting. The task keeps running on " +
            "Browser Use, and its task id can be checked later.",
        SettingSection.PLUGINS, default = 5, min = 1, max = 60,
    )

    /**
     * The spend cap per task. Browser Use's V4 API has no step count; its documented bound on a run is
     * a cost cap, so this row takes the place of a step limit.
     */
    val MAX_SPEND_USD = SettingDef.FloatValue(
        "cloud.browser.max_spend", "Spend limit per task (USD)",
        "The most one browser task may cost, in US dollars. A task that reaches the limit stops.",
        SettingSection.PLUGINS, default = 1f, min = 0.1f, max = 20f, step = 0.1f,
    )

    val USE_PROFILE = SettingDef.Toggle(
        "cloud.browser.use_profile", "Allow logins with my saved profile",
        "Browser tasks start with the saved profile below, so sites where you are signed in there stay " +
            "signed in. Off means every task starts with no logins.",
        SettingSection.PLUGINS, default = false,
    )

    val PROFILE_ID = SettingDef.Text(
        "cloud.browser.profile_id", "Saved profile id",
        "The id of a browser profile in Browser Use Cloud. Used only while the option above is on.",
        SettingSection.PLUGINS, default = "", maxLength = 64,
    )

    val all: List<SettingDef<*>> = listOf(PROVIDER, API_KEY, WAIT_MINUTES, MAX_SPEND_USD, USE_PROFILE, PROFILE_ID)
}

/** The one place the browser key is read. Today it is the user's own key from Settings. */
internal fun browserKeyOf(reader: SettingsReader): String = CredentialSource.browserKey(reader)

/** The provider the Provider setting picks. Browser Use is the only option, which the Choice setting enforces. */
internal fun providerFor(reader: SettingsReader): CloudBrowserProvider =
    BrowserUseProvider(browserKeyOf(reader))

/**
 * Off by default. Offers `browser.task` and `browser.status`, but only in Technical mode with a key
 * stored. [reader] is the live store, so a key or setting change applies at the next message.
 */
class CloudBrowserPlugin(private val reader: SettingsReader) : AgentPlugin {
    override val id: String = CloudBrowserSettings.PLUGIN_ID
    override val title: String = "Cloud browser"
    override val summary: String =
        "Hands a web task (booking, filling in a form, finding a price) to a browser in the cloud, on your " +
            "own Browser Use account. Technical mode only. The task text and start address are sent to Browser Use."
    override val enabledByDefault: Boolean = false
    override val settings: List<SettingDef<*>> = CloudBrowserSettings.all

    private val built: List<AgentTool> by lazy {
        requireCatalogueAgreement(
            listOf(
                BrowserTaskTool(reader),
                BrowserStatusTool(reader),
            ),
        )
    }

    override fun tools(): List<AgentTool> =
        if (reader.mode != SettingsMode.TECHNICAL || browserKeyOf(reader).isBlank()) emptyList() else built

    /** Every tool this plugin ships, key or no key, for the startup catalogue check. */
    fun shippedTools(): List<AgentTool> = built
}
