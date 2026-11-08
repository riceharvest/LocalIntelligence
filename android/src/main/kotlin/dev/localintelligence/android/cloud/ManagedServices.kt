package dev.localintelligence.android.cloud

import dev.localintelligence.android.cloud.browser.CloudBrowserSettings
import dev.localintelligence.android.cloud.sandbox.SandboxSettings
import dev.localintelligence.android.gateway.telegram.TelegramSettings
import dev.localintelligence.android.mcp.McpSettings
import dev.localintelligence.android.tools.email.EmailSettings
import dev.localintelligence.core.plugin.AgentPlugin
import dev.localintelligence.core.settings.SettingsMode
import dev.localintelligence.core.settings.SettingsReader
import dev.localintelligence.core.settings.mode

/**
 * The one place that maps a plugin id to the Extras service name that unlocks it in Simple
 * mode. Service jobs add to [BY_PLUGIN_ID] without touching shared call sites.
 */
object ManagedServices {
    const val SANDBOX = "sandbox"
    const val BROWSER = "browser"
    const val COMPOSIO = "composio"
    const val VOICE = "voice"
    const val MAIL = "mail"
    const val TELEGRAM = "telegram"

    /** Plugin id -> Extras service name for plugins that Extras turns on in Simple mode. */
    val BY_PLUGIN_ID: Map<String, String> = mapOf(
        SandboxSettings.PLUGIN_ID to SANDBOX,
        CloudBrowserSettings.PLUGIN_ID to BROWSER,
        EmailSettings.PLUGIN_ID to MAIL,
        TelegramSettings.PLUGIN_ID to TELEGRAM,
        McpSettings.PLUGIN_ID to COMPOSIO,
    )

    /** The Extras service name for [plugin], or null when [plugin] is not an Extras-managed plugin. */
    fun serviceFor(plugin: AgentPlugin): String? = BY_PLUGIN_ID[plugin.id]

    /**
     * True when [plugin] runs in Simple mode through an active Extras service and the user has
     * no own credential for that integration.
     */
    fun isManagedEnabled(reader: SettingsReader, plugin: AgentPlugin): Boolean {
        if (reader.mode != SettingsMode.SIMPLE) return false
        val service = serviceFor(plugin) ?: return false
        return CredentialSource.managed(reader, service)
    }
}
