package dev.localintelligence.android.cloud

import dev.localintelligence.android.cloud.browser.CloudBrowserSettings
import dev.localintelligence.android.cloud.sandbox.SandboxSettings
import dev.localintelligence.android.tools.email.EmailSettings
import dev.localintelligence.android.tools.home.HomeAssistantSettings
import dev.localintelligence.android.tools.web.SearchProvider
import dev.localintelligence.android.tools.web.WebSearchSettings
import dev.localintelligence.core.settings.SettingsReader
import java.time.Instant

// ===========================================================================
// The one place every keyed integration reads its credential.
//
// Precedence, for the two integrations Extras covers: the user's own key (Settings,
// Technical) > Extras (the managed key, while Extras is active) > none.
//
// Only OpenRouter chat and Exa search take an Extras key. Image, video, sandbox, cloud
// browser, Home Assistant and email read the user's own credential only: Extras does not
// pay for them, so they must never fall through to the Extras key.
//
// Callers ask here and never read a credential row themselves, so a change of source
// (a new managed key, say) is made in this file and nowhere else.
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
}
