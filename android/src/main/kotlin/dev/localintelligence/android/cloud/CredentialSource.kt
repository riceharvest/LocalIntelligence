package dev.localintelligence.android.cloud

import dev.localintelligence.android.cloud.browser.CloudBrowserSettings
import dev.localintelligence.android.cloud.sandbox.SandboxSettings
import dev.localintelligence.android.gateway.telegram.TelegramSettings
import dev.localintelligence.android.tools.email.EmailSettings
import dev.localintelligence.android.tools.home.HomeAssistantSettings
import dev.localintelligence.android.tools.web.SearchProvider
import dev.localintelligence.android.tools.web.WebSearchSettings
import dev.localintelligence.core.settings.SettingsReader
import java.time.Instant

// ===========================================================================
// The one place every keyed integration reads its credential.
//
// Precedence everywhere: the user's own credential or configuration (Settings,
// Technical) > Extras (while Extras is active) > none.
//
// OpenRouter chat and Exa search receive per-phone provider keys issued by the Extras
// backend. Extras ALSO covers sandbox, browser, composio, voice, mail and telegram,
// but through the Extras backend (ExtrasClient + access token), never by handing out
// a provider key. Image, video and Home Assistant read the user's own credential only.
//
// Callers ask here and never read a credential row themselves, so a change of source
// is made in this file and nowhere else.
// ===========================================================================

object CredentialSource {

    // -- Chat (OpenRouter and the other chat providers) ---------------------------------

    /** The user's own key for [provider], from Settings. Blank when none is set. */
    fun ownKeyFor(reader: SettingsReader, provider: RemoteProvider): String {
        val row = when (provider) {
            RemoteProvider.OPENROUTER -> OpenRouterSettings.API_KEY
            RemoteProvider.GROQ -> RemoteProviderSettings.GROQ_KEY
            RemoteProvider.CEREBRAS -> RemoteProviderSettings.CEREBRAS_KEY
            RemoteProvider.DEEPSEEK -> RemoteProviderSettings.DEEPSEEK_KEY
            RemoteProvider.TOGETHER -> RemoteProviderSettings.TOGETHER_KEY
            RemoteProvider.MISTRAL -> RemoteProviderSettings.MISTRAL_KEY
            RemoteProvider.GEMINI -> RemoteProviderSettings.GEMINI_KEY
            RemoteProvider.CUSTOM -> RemoteProviderSettings.CUSTOM_KEY
        }
        return reader[row]
    }

    /** The key the chat provider is called with: own key, else Extras for OpenRouter, else blank. */
    fun keyFor(reader: SettingsReader, provider: RemoteProvider): String {
        val own = ownKeyFor(reader, provider)
        if (own.isNotBlank() || provider != RemoteProvider.OPENROUTER) return own
        return extrasChatKey(reader)
    }

    /**
     * True when the OpenRouter chat answers with the Extras key, because the user has no
     * key of their own. The Extras model is then the one to use, not the user's model field.
     */
    fun chatUsesExtras(reader: SettingsReader): Boolean =
        ownKeyFor(reader, RemoteProvider.OPENROUTER).isBlank() && extrasChatKey(reader).isNotBlank()

    // -- Search (web.search) ------------------------------------------------------------

    /**
     * The key for a keyed search provider: own key first, and for Exa the Extras key when
     * there is no own one. Null when none. DuckDuckGo and SearXNG take no key here.
     */
    fun searchKey(reader: SettingsReader, provider: SearchProvider): String? {
        val own = when (provider) {
            SearchProvider.BRAVE -> reader[WebSearchSettings.BRAVE_KEY]
            SearchProvider.TAVILY -> reader[WebSearchSettings.TAVILY_KEY]
            SearchProvider.EXA -> reader[WebSearchSettings.EXA_KEY]
            SearchProvider.DUCKDUCKGO, SearchProvider.SEARXNG -> ""
        }.trim()
        val key = if (own.isEmpty() && provider == SearchProvider.EXA) extrasSearchKey(reader) else own
        return key.takeIf { it.isNotEmpty() }
    }

    // -- Own keys only: these integrations are not covered by Extras --------------------

    /** The Home Assistant token the user pasted, or null. */
    fun homeToken(reader: SettingsReader): String? =
        reader[HomeAssistantSettings.TOKEN].trim().takeIf { it.isNotEmpty() }

    /** The mailbox app password the user pasted, or null. */
    fun mailPassword(reader: SettingsReader): String? =
        reader[EmailSettings.PASSWORD].trim().takeIf { it.isNotEmpty() }

    /** The Daytona key the user pasted. Blank when none. */
    fun sandboxKey(reader: SettingsReader): String = reader[SandboxSettings.API_KEY]

    /** The Browser Use key the user pasted. Blank when none. */
    fun browserKey(reader: SettingsReader): String = reader[CloudBrowserSettings.API_KEY]

    // -- Extras -------------------------------------------------------------------------

    /** True while Extras is active: a key is stored and its grant has not ended. */
    fun extrasActive(reader: SettingsReader, now: Instant = Instant.now()): Boolean =
        ExtrasSettings.isActive(reader, now)

    private fun extrasChatKey(reader: SettingsReader): String =
        if (extrasActive(reader)) reader[ExtrasSettings.OPENROUTER_KEY] else ""

    private fun extrasSearchKey(reader: SettingsReader): String =
        if (extrasActive(reader)) reader[ExtrasSettings.EXA_KEY] else ""

    /** True when Extras is active and granted a search key, so search can use Exa for Simple users. */
    fun extrasSearchAvailable(reader: SettingsReader): Boolean = extrasSearchKey(reader).isNotBlank()

    /** The customer access token for proxied Extras services, or blank when Extras is not active. */
    fun extrasAccessToken(reader: SettingsReader): String =
        if (extrasActive(reader)) reader[ExtrasSettings.ACCESS_TOKEN] else ""

    /**
     * True when [service] (`sandbox`, `browser`, `composio`, `voice`, `mail`, `telegram`) is
     * served by the Extras backend: Extras is active, the backend enabled [service] in the grant,
     * and the user has no own credential for that integration (own config always wins).
     */
    fun managed(reader: SettingsReader, service: String): Boolean =
        extrasActive(reader) && ExtrasSettings.serviceOn(reader, service) && !hasOwnCredential(reader, service)

    private fun hasOwnCredential(reader: SettingsReader, service: String): Boolean = when (service) {
        ManagedServices.SANDBOX -> sandboxKey(reader).isNotBlank() ||
            reader[SandboxSettings.VERCEL_TOKEN].isNotBlank() ||
            reader[SandboxSettings.SSH_HOST].isNotBlank() ||
            reader[SandboxSettings.PROVIDER] == "termux"
        ManagedServices.BROWSER -> browserKey(reader).isNotBlank()
        ManagedServices.MAIL -> mailPassword(reader) != null
        ManagedServices.TELEGRAM -> reader[TelegramSettings.TOKEN].isNotBlank()
        ManagedServices.COMPOSIO, ManagedServices.VOICE -> false
        else -> false
    }
}
