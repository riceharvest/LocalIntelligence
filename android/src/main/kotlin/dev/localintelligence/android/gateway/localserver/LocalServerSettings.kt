package dev.localintelligence.android.gateway.localserver

import dev.localintelligence.core.plugin.AgentPlugin
import dev.localintelligence.core.settings.SettingDef
import dev.localintelligence.core.settings.SettingSection

/**
 * The settings the local OpenAI-style server keeps. Only [TOKEN] is an option the user sees as a
 * row; the rest is state the server writes and the panel reads.
 *
 * The port is fixed rather than a setting. A client has to be told the address, and a fixed
 * address is one line in each client's config; a changeable one would need a second place to
 * show it.
 */
object LocalServerSettings {
    const val PLUGIN_ID = "gateway.local_server"

    /** Loopback only. Never 0.0.0.0: the server is for apps on this phone, not the network. */
    const val HOST = "127.0.0.1"
    const val PORT = 8080

    /**
     * The bearer token every request must send. 32 random bytes, generated when the server is
     * switched on. Stored encrypted and never shown back; the panel shows it once, from memory.
     */
    val TOKEN = SettingDef.Secret(
        "gateway.local_server.token", "Access token",
        "The token other apps send as a Bearer header. Regenerate replaces it and cuts off the old one.",
        SettingSection.PLUGINS,
    )

    /** The last problem the server hit (for example a busy port). Empty when it is running cleanly. */
    val LAST_ERROR = SettingDef.Text(
        "gateway.local_server.last_error", "Last error", "", SettingSection.PLUGINS,
        default = "", maxLength = 300,
    )
}

/**
 * The token generated in this process, kept in memory so the panel can show it once with a Copy
 * button. It is cleared when the Settings screen closes, because the stored value is never read
 * back into the UI.
 */
object LocalServerReveal {
    @Volatile
    var token: String? = null
}

/** Off by default. No tools: the server answers requests with the model, and nothing else. */
class LocalServerPlugin : AgentPlugin {
    override val id: String = LocalServerSettings.PLUGIN_ID
    override val title: String = "Local server"
    override val summary: String =
        "Let other apps on this phone use the loaded model through a local OpenAI-style address. " +
            "Each request needs the access token. They get the model only: no tools, memory or chats."
    override val enabledByDefault: Boolean = false
    override val settings: List<SettingDef<*>> = listOf(LocalServerSettings.TOKEN)
}
