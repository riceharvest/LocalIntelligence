package dev.localintelligence.android.tools.home

import dev.localintelligence.android.cloud.CredentialSource
import dev.localintelligence.android.tools.requireCatalogueAgreement
import dev.localintelligence.core.plugin.AgentPlugin
import dev.localintelligence.core.settings.SettingDef
import dev.localintelligence.core.settings.SettingSection
import dev.localintelligence.core.settings.SettingsMode
import dev.localintelligence.core.settings.SettingsReader
import dev.localintelligence.core.settings.mode
import dev.localintelligence.core.tool.AgentTool
import java.net.URI
import java.net.URISyntaxException

// ===========================================================================
// Home Assistant, on the user's own server. Off by default.
//
// Off, Simple mode, or no usable address and token: [HomeAssistantPlugin.tools]
// returns nothing and no socket is opened. Needs a URL and a token, so it is a
// Technical-mode integration (see the owner's Simple/Technical split).
//
// What leaves the phone: requests to the user's own Home Assistant address, with
// the access token in the Authorization header, and the entity and action the
// model chose. Redirects are not followed, so the token cannot be carried to a
// host the user did not name.
// ===========================================================================

/** The settings rows this plugin adds. Keys are prefixed with the plugin id. */
object HomeAssistantSettings {
    const val PLUGIN_ID = "home.assistant"

    val ADDRESS = SettingDef.Text(
        "home.assistant.address", "Home Assistant address",
        "Where your Home Assistant runs, for example https://ha.example.com or " +
            "http://homeassistant.local:8123. Plain http works only for an address on your own network. " +
            "Empty means nothing is sent.",
        SettingSection.PLUGINS, default = "", maxLength = 300,
    )

    val TOKEN = SettingDef.Secret(
        "home.assistant.token", "Long-lived access token",
        "In Home Assistant: your profile, Security, Long-lived access tokens. " +
            "Stored encrypted and never shown back.",
        SettingSection.PLUGINS,
    )

    val all: List<SettingDef<*>> = listOf(ADDRESS, TOKEN)
}

/**
 * Which addresses this app will call.
 *
 * https is accepted for any host, because a Home Assistant behind a reverse proxy
 * is public. Plain http is accepted only for the user's own network: a private
 * IPv4 range, a single-label name, or a name under .local or .home.arpa. Loopback,
 * link-local (cloud metadata addresses live there) and unspecified addresses are
 * refused in both schemes, and so is any address carrying a user name, query or
 * fragment, so the token cannot be sent somewhere the user did not write.
 */
internal object HomeAddress {
    private const val MAX_ADDRESS_CHARS = 300

    /** The base address with no trailing slash, or null when this app will not call it. */
    fun accept(raw: String): String? {
        val text = raw.trim().trimEnd('/')
        val lower = text.lowercase()
        val secure = lower.startsWith("https://")
        if (!secure && !lower.startsWith("http://")) return null
        if (text.length > MAX_ADDRESS_CHARS || text.any { it.isWhitespace() }) return null
        val uri = try {
            URI(text)
        } catch (e: URISyntaxException) {
            return null
        }
        if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) return null
        val host = uri.host?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        if (host == "localhost" || host.endsWith(".localhost")) return null

        val v4 = ipv4(host)
        if (v4 != null) {
            val first = v4[0]
            val second = v4[1]
            if (first == 127 || first == 0 || (first == 169 && second == 254)) return null
            if (secure) return text
            val ownNetwork = first == 10 || (first == 172 && second in 16..31) || (first == 192 && second == 168)
            return text.takeIf { ownNetwork }
        }
        // An IPv6 literal is not accepted: the address check above is written for IPv4.
        if (host.contains(':')) return null
        if (secure) return text
        val ownName = !host.contains('.') || host.endsWith(".local") || host.endsWith(".home.arpa")
        return text.takeIf { ownName }
    }

    private fun ipv4(host: String): IntArray? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val numbers = IntArray(4)
        for ((index, part) in parts.withIndex()) {
            numbers[index] = part.toIntOrNull()?.takeIf { it in 0..255 } ?: return null
        }
        return numbers
    }
}

/** A checked address and the token to send to it. */
internal class HomeServer(val base: String, val token: String)

/** The server to call now, read from the live settings, or null when not set up. */
internal fun homeServer(reader: SettingsReader): HomeServer? {
    val base = HomeAddress.accept(reader[HomeAssistantSettings.ADDRESS]) ?: return null
    val token = CredentialSource.homeToken(reader) ?: return null
    return HomeServer(base, token)
}

/**
 * Off by default. Offers `home.devices`, `home.control` and `home.secure` while it is
 * switched on, the app is in Technical mode, and an address and token are set.
 *
 * [reader] is the live settings store, read when a run builds its tool list and again
 * when a tool runs, so a change takes effect at the next message.
 */
class HomeAssistantPlugin(
    private val reader: SettingsReader,
) : AgentPlugin {
    override val id: String = HomeAssistantSettings.PLUGIN_ID
    override val title: String = "Home Assistant"
    override val summary: String =
        "Read and control lights, plugs, heating, covers, media and locks on your own Home Assistant. " +
            "Requests go to that server."
    override val enabledByDefault: Boolean = false
    override val settings: List<SettingDef<*>> = HomeAssistantSettings.all

    private val built: List<AgentTool> by lazy {
        requireCatalogueAgreement(homeTools { homeServer(reader) })
    }

    override fun tools(): List<AgentTool> {
        // Simple mode never offers these: they need an address and a token, which
        // are Technical settings.
        if (reader.mode != SettingsMode.TECHNICAL) return emptyList()
        return if (homeServer(reader) == null) emptyList() else built
    }

    /** Every tool this plugin ships, configured or not, for the catalogue check at startup. */
    fun shippedTools(): List<AgentTool> = built
}
