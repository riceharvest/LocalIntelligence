package dev.localintelligence.android.cloud

import dev.localintelligence.core.plugin.AgentPlugin
import dev.localintelligence.core.settings.SettingDef
import dev.localintelligence.core.settings.SettingSection
import dev.localintelligence.core.settings.SettingsReader
import java.util.UUID

// ===========================================================================
// ChatGPT as the chat model, through "Sign in with ChatGPT" (OpenAI's plan-usage
// flow for open-source apps). No API key and no client secret: the user signs in
// with their own ChatGPT account and allows this app to use their plan.
//
// What leaves the phone while this is the resident model: the whole conversation
// (system prompt as `instructions`, user turns, tool observations as fenced text)
// and any attached image, in the request body, plus the access token in the
// Bearer header. Nothing else. The model slug is the user's Technical setting, or
// the first model the account lists.
//
// Off until the user signs in. With no account there is no socket at all.
// ===========================================================================

/** The settings this plugin owns. Account rows are kept out of [ChatGptPlugin.settings]. */
object ChatGptSettings {
    const val PLUGIN_ID = "cloud.chatgpt"

    /** Where users review and limit their plan use. Linked from the UI, never fetched. */
    const val USAGE_URL = "https://chatgpt.com/settings/usage"

    /**
     * The model slug. Technical only: Simple mode reads the default, which means the
     * first model the account lists. Blank also means that.
     */
    val CHAT_MODEL = SettingDef.Text(
        "cloud.chatgpt.model", "ChatGPT model",
        "The ChatGPT model slug used for chat. Empty means the first model your ChatGPT " +
            "account lists. Typed as is; the Models page lists the choices.",
        SettingSection.PLUGINS, default = "", maxLength = 120, blankMeansDefault = true,
    )

    // ---- account state -----------------------------------------------------
    // Stored through SettingsStore like every other row, so the tokens are encrypted
    // with the same Keystore key as the OpenRouter key. They are not in the plugin's
    // settings list, so no screen shows them. They are simple=true so a read is the
    // stored value in both modes (a non-simple row reads as its default in Simple).

    /** The per-install host id, `urn:uuid:<uuid4>`. Created once and never changed. */
    val HOST_ID = SettingDef.Text(
        "cloud.chatgpt.host_id", "Host id", "", SettingSection.PLUGINS,
        default = "", maxLength = 80, simple = true,
    )

    /** The client id the account was registered with. Kept after sign-out so a later sign-in reuses it. */
    val CLIENT_ID = SettingDef.Text(
        "cloud.chatgpt.client_id", "Issued client id", "", SettingSection.PLUGINS,
        default = "", maxLength = 120, simple = true,
    )

    /** The verified `sub` of the ID token. The account's identity. */
    val SUBJECT = SettingDef.Text(
        "cloud.chatgpt.subject", "Account subject", "", SettingSection.PLUGINS,
        default = "", maxLength = 255, simple = true,
    )

    val EMAIL = SettingDef.Text(
        "cloud.chatgpt.email", "Account email", "", SettingSection.PLUGINS,
        default = "", maxLength = 320, simple = true,
    )

    /** Granted scopes, space separated. Plan use is allowed only with `chatgpt.tokens.use.direct`. */
    val SCOPES = SettingDef.Text(
        "cloud.chatgpt.scopes", "Granted scopes", "", SettingSection.PLUGINS,
        default = "", maxLength = 400, simple = true,
    )

    /** Access-token expiry, epoch milliseconds as text. Text because a Long does not fit an IntValue. */
    val EXPIRES_AT = SettingDef.Text(
        "cloud.chatgpt.expires_at", "Access token expiry", "", SettingSection.PLUGINS,
        default = "", maxLength = 20, simple = true,
    )

    val ACCESS_TOKEN = SettingDef.Secret(
        "cloud.chatgpt.access_token", "ChatGPT access token", "",
        SettingSection.PLUGINS, simple = true,
    )

    val REFRESH_TOKEN = SettingDef.Secret(
        "cloud.chatgpt.refresh_token", "ChatGPT refresh token", "",
        SettingSection.PLUGINS, simple = true,
    )

    /** Kept only to send as `id_token_hint` on a later sign-in. Cleared on sign-out. */
    val ID_TOKEN = SettingDef.Secret(
        "cloud.chatgpt.id_token", "ChatGPT ID token", "",
        SettingSection.PLUGINS, simple = true,
    )

    /** Set after the one-time "You're using your ChatGPT plan" notice has been dismissed. */
    val WELCOME_SEEN = SettingDef.Toggle(
        "cloud.chatgpt.welcome_seen", "Plan-use notice seen", "", SettingSection.PLUGINS,
        default = false, simple = true,
    )
}

/**
 * The writes this plugin makes. [dev.localintelligence.app.settings.SettingsStore]
 * implements it; the plugin cannot see that class, so the interface lives here.
 */
interface ChatGptSettingsWriter {
    fun <T : Any> set(def: SettingDef<T>, value: T)
    fun setSecret(def: SettingDef.Secret, value: String): Boolean
    fun clearSecret(def: SettingDef.Secret): Boolean
    fun setPluginEnabled(plugin: AgentPlugin, enabled: Boolean)
}

/** A signed-in account: everything the backend needs to send a request. */
class ChatGptAccount(
    val clientId: String,
    val subject: String,
    val email: String,
    val accessToken: String,
    val refreshToken: String,
    val expiresAtMs: Long,
    val scopes: Set<String>,
) {
    // Tokens must never reach a log or a debugger string.
    override fun toString(): String = "ChatGptAccount(redacted)"
}

/** What a new or returning sign-in needs from the saved account, if there is one. */
class ChatGptRegistration(
    val clientId: String,
    val subject: String,
    val email: String,
    val idToken: String,
)

/**
 * Off until the user signs in. Owns the account rows and the model choice; the sign-in
 * itself is [ChatGptSignIn], and the requests are [ChatGptChatBackend].
 *
 * [reader] is the live settings store and [writer] the same store, so a sign-in is
 * visible to the next message without a restart.
 */
class ChatGptPlugin(
    private val reader: SettingsReader,
    private val writer: ChatGptSettingsWriter,
) : AgentPlugin {
    override val id: String = ChatGptSettings.PLUGIN_ID
    override val title: String = "Chat with ChatGPT (your ChatGPT plan)"
    override val summary: String =
        "Sign in with ChatGPT and use your plan for chat. Your messages are sent to OpenAI while it is on."
    override val enabledByDefault: Boolean = false
    override val settings: List<SettingDef<*>> = listOf(ChatGptSettings.CHAT_MODEL)

    /** The signed-in account, or null. An account needs a client id and a refresh token. */
    fun account(): ChatGptAccount? {
        val clientId = reader[ChatGptSettings.CLIENT_ID]
        val refresh = reader[ChatGptSettings.REFRESH_TOKEN]
        if (clientId.isBlank() || refresh.isBlank()) return null
        return ChatGptAccount(
            clientId = clientId,
            subject = reader[ChatGptSettings.SUBJECT],
            email = reader[ChatGptSettings.EMAIL],
            accessToken = reader[ChatGptSettings.ACCESS_TOKEN],
            refreshToken = refresh,
            expiresAtMs = reader[ChatGptSettings.EXPIRES_AT].toLongOrNull() ?: 0L,
            scopes = reader[ChatGptSettings.SCOPES].split(' ').filter { it.isNotBlank() }.toSet(),
        )
    }

    /** The saved registration for a returning sign-in. Null when none was ever saved. */
    fun registration(): ChatGptRegistration? {
        val clientId = reader[ChatGptSettings.CLIENT_ID]
        if (clientId.isBlank()) return null
        return ChatGptRegistration(
            clientId = clientId,
            subject = reader[ChatGptSettings.SUBJECT],
            email = reader[ChatGptSettings.EMAIL],
            idToken = reader[ChatGptSettings.ID_TOKEN],
        )
    }

    /**
     * The per-install host id. Made on first use and kept, so the same install always
     * presents the same `ext_agent_host_id`. It is an opaque id, not a credential.
     */
    fun hostId(): String {
        val existing = reader[ChatGptSettings.HOST_ID]
        if (existing.startsWith(HOST_PREFIX)) return existing
        val fresh = HOST_PREFIX + UUID.randomUUID().toString()
        writer.set(ChatGptSettings.HOST_ID, fresh)
        return fresh
    }

    /** The model slug the user picked in Technical mode; blank means the account's first model. */
    fun chosenModel(): String = reader[ChatGptSettings.CHAT_MODEL]

    fun setChatModel(slug: String) = writer.set(ChatGptSettings.CHAT_MODEL, slug)

    fun welcomeSeen(): Boolean = reader[ChatGptSettings.WELCOME_SEEN]

    fun markWelcomeSeen() = writer.set(ChatGptSettings.WELCOME_SEEN, true)

    /** Saves a sign-in that has passed every check, and switches the plugin on. */
    fun saveAccount(account: ChatGptAccount, idToken: String) {
        writer.set(ChatGptSettings.CLIENT_ID, account.clientId)
        writer.set(ChatGptSettings.SUBJECT, account.subject)
        writer.set(ChatGptSettings.EMAIL, account.email.take(320))
        writer.setSecret(ChatGptSettings.ID_TOKEN, idToken)
        saveTokens(account.accessToken, account.refreshToken, account.expiresAtMs, account.scopes)
        writer.setPluginEnabled(this, true)
    }

    /**
     * Replaces the access token, its expiry, the granted scopes and the rotating refresh
     * token together. Called only after a successful refresh or sign-in.
     */
    fun saveTokens(access: String, refresh: String, expiresAtMs: Long, scopes: Set<String>) {
        writer.setSecret(ChatGptSettings.ACCESS_TOKEN, access)
        writer.setSecret(ChatGptSettings.REFRESH_TOKEN, refresh)
        writer.set(ChatGptSettings.EXPIRES_AT, expiresAtMs.toString())
        writer.set(ChatGptSettings.SCOPES, scopes.joinToString(" "))
    }

    /**
     * Forgets the tokens and keeps the client id and subject, so a later sign-in is a
     * returning one. The ID token goes too: docs say to omit `id_token_hint` after sign-out.
     */
    fun clearTokens() {
        writer.clearSecret(ChatGptSettings.ACCESS_TOKEN)
        writer.clearSecret(ChatGptSettings.REFRESH_TOKEN)
        writer.clearSecret(ChatGptSettings.ID_TOKEN)
        writer.set(ChatGptSettings.EXPIRES_AT, "")
        writer.set(ChatGptSettings.SCOPES, "")
    }

    /** Signed out: tokens cleared and the plugin off. The client id stays for the next sign-in. */
    fun signedOut() {
        clearTokens()
        writer.setPluginEnabled(this, false)
    }

    private companion object {
        const val HOST_PREFIX = "urn:uuid:"
    }
}
