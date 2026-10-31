package dev.localintelligence.android.gateway.telegram

import dev.localintelligence.core.plugin.AgentPlugin
import dev.localintelligence.core.settings.SettingDef
import dev.localintelligence.core.settings.SettingSection
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.Json

// ===========================================================================
// Telegram as a remote keyboard for this phone's agent. Off by default.
//
// The phone stays the runtime: a message from a linked chat becomes a run on
// this phone, through the same path a scheduled task uses, and the reply goes
// back to that chat. Nothing runs for a chat the owner has not linked.
//
// Needs a bot token from @BotFather, so it is a Technical-mode integration.
// What leaves the phone: the text of messages from linked chats, the replies,
// and the token in each request to api.telegram.org. Telegram itself reads
// both, which is the price of using Telegram at all.
// ===========================================================================

/**
 * The settings this plugin keeps. Only [TOKEN] is an option the user edits. The
 * rest is state the app writes through SettingsStore and the Telegram panel shows
 * above the token row; it is not listed as a row of its own.
 */
object TelegramSettings {
    const val PLUGIN_ID = "gateway.telegram"

    /** The bot token from @BotFather. Stored encrypted and never shown back. */
    val TOKEN = SettingDef.Secret(
        "gateway.telegram.token", "Bot token",
        "From @BotFather: send /newbot and paste the token it gives you. " +
            "Stored encrypted and never shown back.",
        SettingSection.PLUGINS,
    )

    /** Linked chats, as a JSON list of {"id","name"}. Only these chats get answers. */
    val LINKED = SettingDef.Text(
        "gateway.telegram.linked", "Linked chats", "", SettingSection.PLUGINS,
        default = "[]", maxLength = 4000,
    )

    /** The chat waiting for the owner's approval, as {"id","name"}, or empty. */
    val PENDING = SettingDef.Text(
        "gateway.telegram.pending", "Chat waiting for approval", "", SettingSection.PLUGINS,
        default = "", maxLength = 400,
    )

    /** Epoch millis until which a /start from a new chat is offered for approval. 0 when closed. */
    val LINK_OPEN_UNTIL = SettingDef.Text(
        "gateway.telegram.link_open_until", "Link window", "", SettingSection.PLUGINS,
        default = "0", maxLength = 20,
    )

    /**
     * The next update id to ask Telegram for. Kept across restarts so a message
     * is never run twice: Telegram redelivers anything not yet confirmed.
     */
    val OFFSET = SettingDef.Text(
        "gateway.telegram.offset", "Update offset", "", SettingSection.PLUGINS,
        default = "0", maxLength = 20,
    )

    /** The last problem the poller hit, shown in settings. Empty when the last call worked. */
    val LAST_ERROR = SettingDef.Text(
        "gateway.telegram.last_error", "Last error", "", SettingSection.PLUGINS,
        default = "", maxLength = 300,
    )
}

/** Off by default. No tools: the gateway starts runs and sends replies, the model never calls it. */
class TelegramPlugin : AgentPlugin {
    override val id: String = TelegramSettings.PLUGIN_ID
    override val title: String = "Telegram"
    override val summary: String =
        "Message this phone's agent from Telegram. Only chats you link here get answers, and each " +
            "message runs on this phone. Telegram carries the text."
    override val enabledByDefault: Boolean = false
    override val settings: List<SettingDef<*>> = listOf(TelegramSettings.TOKEN)
}

/** A chat the owner linked, or one waiting for approval. */
data class TelegramChat(val id: Long, val name: String)

/** The linked-chat list and the pending chat, stored as JSON text in SettingsStore. */
object TelegramChats {
    /** How long a "Link this chat" press keeps the door open for a /start. */
    const val LINK_WINDOW_MILLIS = 5 * 60_000L

    private const val MAX_NAME_CHARS = 64

    fun linked(raw: String): List<TelegramChat> = runCatching {
        Json.parseToJsonElement(raw).let { root ->
            (root as? JsonArray)?.mapNotNull { chatOf(it) }.orEmpty()
        }
    }.getOrDefault(emptyList())

    fun isLinked(raw: String, chatId: Long): Boolean = linked(raw).any { it.id == chatId }

    fun encodeList(chats: List<TelegramChat>): String = JsonArray(chats.map { encodeChat(it) }).toString()

    fun pending(raw: String): TelegramChat? =
        if (raw.isBlank()) null else runCatching { chatOf(Json.parseToJsonElement(raw)) }.getOrNull()

    fun encodeOne(chat: TelegramChat): String = encodeChat(chat).toString()

    /** A display name from Telegram: no control characters, bounded length. */
    fun cleanName(name: String): String =
        name.filterNot { it.isISOControl() }.trim().take(MAX_NAME_CHARS).ifEmpty { "(no name)" }

    private fun encodeChat(chat: TelegramChat): JsonObject = buildJsonObject {
        put("id", chat.id)
        put("name", cleanName(chat.name))
    }

    private fun chatOf(element: JsonElement): TelegramChat? {
        val obj = element as? JsonObject ?: return null
        val id = obj["id"]?.jsonPrimitive?.longOrNull ?: return null
        val name = obj["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
        return TelegramChat(id, cleanName(name))
    }
}
