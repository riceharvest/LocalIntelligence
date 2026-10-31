package dev.localintelligence.android.tools.web

import dev.localintelligence.core.settings.SettingDef
import dev.localintelligence.core.settings.SettingSection
import dev.localintelligence.core.settings.SettingsReader

// ===========================================================================
// Which search provider web.search talks to, and where its credential comes from.
//
// WHY ONE CHOICE ROW: web.search keeps its name, arguments and output shape, so the
// only thing a provider changes is the backend. The choice is a SettingDef, not a
// flag in the tool, so Settings shows it like every other option.
//
// WHY THE KEYED ONES ARE TECHNICAL-ONLY (simple = false): a key or an instance URL
// is setup. A Simple user always reads the default, DuckDuckGo, which needs nothing.
// ===========================================================================

/**
 * The providers web.search can use. DuckDuckGo is the default and needs no key.
 * Brave, Tavily and Exa need the user's own key. SearXNG needs the address of an
 * instance the user runs or trusts.
 */
enum class SearchProvider(val id: String, val label: String) {
    DUCKDUCKGO("duckduckgo", "DuckDuckGo"),
    BRAVE("brave", "Brave Search"),
    TAVILY("tavily", "Tavily"),
    EXA("exa", "Exa"),
    SEARXNG("searxng", "SearXNG");

    companion object {
        /** An unknown id reads as DuckDuckGo, the same fallback SettingDef.Choice applies. */
        fun fromId(id: String): SearchProvider = entries.firstOrNull { it.id == id } ?: DUCKDUCKGO
    }
}

/** The rows web.search adds under the Web plugin. Keys are prefixed `web.search.`. */
object WebSearchSettings {
    /**
     * Where the query goes. The help text says the one thing a user must know: a
     * failing provider fails the search, it is not replaced by another one.
     */
    val PROVIDER = SettingDef.Choice(
        "web.search.provider", "Search provider",
        "Where web.search sends your query. DuckDuckGo needs no key. Brave, Tavily and Exa " +
            "need your own key; SearXNG needs your instance address. If the chosen provider " +
            "fails, the search fails. It never switches to another provider.",
        SettingSection.PLUGINS,
        default = SearchProvider.DUCKDUCKGO.id,
        options = SearchProvider.entries.map { SettingDef.Choice.Option(it.id, it.label) },
    )

    val BRAVE_KEY = SettingDef.Secret(
        "web.search.brave_key", "Brave Search API key",
        "Your own key from brave.com/search/api. Stored encrypted and never shown back. " +
            "Used only when Brave Search is the provider.",
        SettingSection.PLUGINS,
    )

    val TAVILY_KEY = SettingDef.Secret(
        "web.search.tavily_key", "Tavily API key",
        "Your own key from tavily.com. Stored encrypted and never shown back. " +
            "Used only when Tavily is the provider.",
        SettingSection.PLUGINS,
    )

    val EXA_KEY = SettingDef.Secret(
        "web.search.exa_key", "Exa API key",
        "Your own key from exa.ai. Stored encrypted and never shown back. " +
            "Used only when Exa is the provider.",
        SettingSection.PLUGINS,
    )

    val SEARXNG_URL = SettingDef.Text(
        "web.search.searxng_url", "SearXNG instance address",
        "Full address of a SearXNG instance with the JSON format turned on, for example " +
            "https://searx.example.org. The query is sent there. Used only when SearXNG is the provider.",
        SettingSection.PLUGINS,
        default = "",
        maxLength = 200,
    )

    val all: List<SettingDef<*>> = listOf(PROVIDER, BRAVE_KEY, TAVILY_KEY, EXA_KEY, SEARXNG_URL)
}

/**
 * Where a keyed provider's credential comes from. Today that is the user's own key,
 * read from Settings. A managed key for Simple users would be another reader of the
 * same shape. The search code asks here and never reads a setting itself, so the
 * managed option can plug in without touching the providers.
 */
internal class CredentialSource(private val settings: SettingsReader) {

    /** The key for [provider], or null when none is set. DuckDuckGo and SearXNG take no key. */
    fun keyFor(provider: SearchProvider): String? {
        val raw = when (provider) {
            SearchProvider.BRAVE -> settings[WebSearchSettings.BRAVE_KEY]
            SearchProvider.TAVILY -> settings[WebSearchSettings.TAVILY_KEY]
            SearchProvider.EXA -> settings[WebSearchSettings.EXA_KEY]
            SearchProvider.DUCKDUCKGO, SearchProvider.SEARXNG -> ""
        }
        return raw.trim().takeIf { it.isNotEmpty() }
    }
}
