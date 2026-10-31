package dev.localintelligence.android.cloud

import dev.localintelligence.core.settings.SettingDef
import dev.localintelligence.core.settings.SettingSection
import dev.localintelligence.core.settings.SettingsReader
import java.net.URI
import java.net.URISyntaxException

// ===========================================================================
// The remote chat providers. Most model hosts, and every local server, speak the
// OpenAI chat-completions API, so one "base URL + key + model" covers them all.
//
// Preset base URLs were taken from each provider's own docs (sources in the table
// below). "Lists models" means GET {base}/models was confirmed in those docs. Where
// it was not confirmed, the list is off and the model is typed.
//
// | Id         | Name             | Base URL                                              | Lists models | Suggested model | Source                                              |
// |------------|------------------|-------------------------------------------------------|--------------|-----------------|-----------------------------------------------------|
// | openrouter | OpenRouter       | https://openrouter.ai/api/v1                          | yes          | google/gemini-2.5-flash | https://openrouter.ai/docs/quickstart        |
// | groq       | Groq             | https://api.groq.com/openai/v1                        | yes          | none            | https://console.groq.com/docs/openai                |
// | cerebras   | Cerebras         | https://api.cerebras.ai/v1                            | yes          | none            | https://inference-docs.cerebras.ai/resources/openai |
// | deepseek   | DeepSeek         | https://api.deepseek.com                              | not verified | none            | https://api-docs.deepseek.com/                      |
// | together   | Together AI      | https://api.together.ai/v1                            | not verified | none            | https://docs.together.ai/docs/openai-api-compatibility |
// | mistral    | Mistral          | https://api.mistral.ai/v1                             | yes          | none            | https://docs.mistral.ai/api                         |
// | gemini     | Google Gemini    | https://generativelanguage.googleapis.com/v1beta/openai | not verified | gemini-2.5-flash | https://ai.google.dev/gemini-api/docs/openai     |
// | custom     | Custom server    | typed by the user (http only on your own network)     | tried, then typed | none       | any OpenAI-compatible server, e.g. Ollama, LM Studio, llama-server, vLLM |
//
// "none" means no model is suggested: the user lists or types one.
// ===========================================================================

/** One chat provider. [id] is what the settings store; [label] is what the user reads. */
enum class RemoteProvider(
    val id: String,
    val label: String,
    /** Fixed base URL, or null for [CUSTOM], which takes the user's own address. */
    val fixedBaseUrl: String?,
    /** Model used when the model field is blank, or null when none is suggested. */
    val defaultModel: String?,
    /** GET {base}/models is confirmed for this provider, so List models is offered. */
    val listsModels: Boolean,
    /**
     * OpenRouter's own body fields (top_k, min_p, repetition_penalty, seed) are sent only
     * here. Other providers reject unknown fields, so they are left out for them.
     */
    val openRouterExtras: Boolean,
    /** input_audio is sent only where it is supported. */
    val audioInput: Boolean,
) {
    OPENROUTER("openrouter", "OpenRouter", "https://openrouter.ai/api/v1",
        "google/gemini-2.5-flash", listsModels = true, openRouterExtras = true, audioInput = true),
    GROQ("groq", "Groq", "https://api.groq.com/openai/v1",
        null, listsModels = true, openRouterExtras = false, audioInput = false),
    CEREBRAS("cerebras", "Cerebras", "https://api.cerebras.ai/v1",
        null, listsModels = true, openRouterExtras = false, audioInput = false),
    DEEPSEEK("deepseek", "DeepSeek", "https://api.deepseek.com",
        null, listsModels = false, openRouterExtras = false, audioInput = false),
    TOGETHER("together", "Together AI", "https://api.together.ai/v1",
        null, listsModels = false, openRouterExtras = false, audioInput = false),
    MISTRAL("mistral", "Mistral", "https://api.mistral.ai/v1",
        null, listsModels = true, openRouterExtras = false, audioInput = false),
    GEMINI("gemini", "Google Gemini", "https://generativelanguage.googleapis.com/v1beta/openai",
        "gemini-2.5-flash", listsModels = false, openRouterExtras = false, audioInput = false),
    CUSTOM("custom", "Custom server", null,
        null, listsModels = true, openRouterExtras = false, audioInput = false),
}

/**
 * The settings rows for the chat provider. The OpenRouter key and chat model rows stay in
 * [OpenRouterSettings] so an existing OpenRouter setup keeps its stored values.
 *
 * All rows are Technical only: each needs a key, an address or a choice of model.
 * Simple mode reads the provider row as its default, OpenRouter, so Simple users see
 * the same behaviour as before.
 */
object RemoteProviderSettings {
    val PROVIDER = SettingDef.Choice(
        "cloud.chat.provider", "Chat provider",
        "The remote server that answers when you pick it on the Models page. Each provider " +
            "needs its own key. Switching provider: check the chat model, because a model id " +
            "from one provider is not valid on another.",
        SettingSection.PLUGINS, default = RemoteProvider.OPENROUTER.id,
        options = RemoteProvider.entries.map { SettingDef.Choice.Option(it.id, it.label) },
    )
    val GROQ_KEY = secret("cloud.groq.api_key", "Groq API key", "groq.com")
    val CEREBRAS_KEY = secret("cloud.cerebras.api_key", "Cerebras API key", "cerebras.ai")
    val DEEPSEEK_KEY = secret("cloud.deepseek.api_key", "DeepSeek API key", "platform.deepseek.com")
    val TOGETHER_KEY = secret("cloud.together.api_key", "Together AI API key", "together.ai")
    val MISTRAL_KEY = secret("cloud.mistral.api_key", "Mistral API key", "console.mistral.ai")
    val GEMINI_KEY = secret("cloud.gemini.api_key", "Google Gemini API key", "aistudio.google.com")
    val CUSTOM_KEY = SettingDef.Secret(
        "cloud.custom.api_key", "Custom server key (optional)",
        "Only if your server asks for one. Local servers usually do not. Stored encrypted and never shown back.",
        SettingSection.PLUGINS,
    )

    val CUSTOM_BASE_URL = SettingDef.Text(
        "cloud.custom.base_url", "Custom server address",
        "Base URL of an OpenAI-compatible server, for example http://192.168.1.20:11434/v1 for " +
            "Ollama or http://192.168.1.20:8080/v1 for llama-server. http:// only for addresses on " +
            "your own network or this phone. Messages go to this address.",
        SettingSection.PLUGINS, default = "", maxLength = 300, blankMeansDefault = true,
    )

    val all: List<SettingDef<*>> = listOf(
        PROVIDER, GROQ_KEY, CEREBRAS_KEY, DEEPSEEK_KEY, TOGETHER_KEY, MISTRAL_KEY, GEMINI_KEY,
        CUSTOM_KEY, CUSTOM_BASE_URL,
    )

    private fun secret(key: String, title: String, site: String) = SettingDef.Secret(
        key, title,
        "Your own key from $site. Stored encrypted and never shown back. " +
            "Used only while this provider is the chat model.",
        SettingSection.PLUGINS,
    )
}

/** Everything one request needs: where it goes, with which key, and which model. */
class RemoteEndpoint(
    val provider: RemoteProvider,
    /** Base URL with no trailing slash. Every call is built from it. */
    val base: String,
    /** Empty only for a custom server with no key. */
    val key: String,
    /** Blank when no model is chosen yet. The caller decides what that means. */
    val model: String,
) {
    /** The host the messages go to, for the Models page. */
    val destination: String get() = runCatching { URI(base).host }.getOrNull() ?: base
}

/** What the Models page shows for the remote chat entry. */
data class RemoteChatOffer(
    val providerLabel: String,
    val destination: String,
    val model: String,
    val canListModels: Boolean,
)

object RemoteProviders {
    /** The provider the user picked. An unknown id reads as OpenRouter, the old behaviour. */
    fun selected(reader: SettingsReader): RemoteProvider =
        RemoteProvider.entries.firstOrNull { it.id == reader[RemoteProviderSettings.PROVIDER] }
            ?: RemoteProvider.OPENROUTER

    /** The model for [provider]: the model field, or the provider's suggested one when blank. */
    fun modelFor(reader: SettingsReader, provider: RemoteProvider): String {
        // With the Extras key answering, the Extras model is the one; the user's model field is theirs.
        if (provider == RemoteProvider.OPENROUTER && CredentialSource.chatUsesExtras(reader)) {
            return reader[ExtrasSettings.OPENROUTER_MODEL].ifBlank { provider.defaultModel.orEmpty() }
        }
        return reader[OpenRouterSettings.CHAT_MODEL].ifBlank { provider.defaultModel.orEmpty() }
    }

    /**
     * The endpoint for the chosen provider, or null when it cannot be called yet: no key
     * (every provider but a custom server), or a custom address that is not allowed. The
     * model may still be blank; that is for the caller to report.
     */
    fun endpoint(reader: SettingsReader): RemoteEndpoint? {
        val provider = selected(reader)
        val base = when (provider.fixedBaseUrl) {
            null -> normalizedCustomBase(reader[RemoteProviderSettings.CUSTOM_BASE_URL]) ?: return null
            else -> provider.fixedBaseUrl
        }
        val key = CredentialSource.keyFor(reader, provider)
        if (key.isBlank() && provider != RemoteProvider.CUSTOM) return null
        return RemoteEndpoint(provider, base, key, modelFor(reader, provider))
    }
}

/**
 * The custom address as a base URL with no trailing slash, or null when it is not one this
 * app will call. https is allowed anywhere. http is allowed only for addresses on the
 * phone's own network (see [isOwnNetworkHost]), so a key or a message is never sent in
 * clear text across the internet. Credentials in the URL, a query or a fragment are refused.
 */
internal fun normalizedCustomBase(raw: String): String? {
    val text = raw.trim().trimEnd('/')
    if (text.isEmpty()) return null
    val uri = try {
        URI(text)
    } catch (e: URISyntaxException) {
        return null
    }
    val host = uri.host?.lowercase() ?: return null
    if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) return null
    return when (uri.scheme?.lowercase()) {
        "https" -> text
        "http" -> if (isOwnNetworkHost(host)) text else null
        else -> null
    }
}

/**
 * True for loopback, the `.local` names and private IPv4 ranges (10/8, 172.16/12,
 * 192.168/16, 127/8). Only the literal text is checked: the name is never resolved, so a
 * public name cannot be made to point at a private address after the check.
 */
internal fun isOwnNetworkHost(host: String): Boolean {
    if (host == "localhost" || host.endsWith(".local") || host == "[::1]") return true
    val match = IPV4.matchEntire(host) ?: return false
    val octets = match.groupValues.drop(1).map { it.toIntOrNull() ?: return false }
    if (octets.any { it !in 0..255 }) return false
    val (a, b) = octets
    return a == 10 || a == 127 || (a == 172 && b in 16..31) || (a == 192 && b == 168)
}

private val IPV4 = Regex("(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})")
