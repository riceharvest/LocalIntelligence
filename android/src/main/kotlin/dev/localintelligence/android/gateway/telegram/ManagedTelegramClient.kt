package dev.localintelligence.android.gateway.telegram

import dev.localintelligence.android.cloud.ExtrasClient
import dev.localintelligence.core.settings.SettingsReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.time.Instant

// ===========================================================================
// Managed Telegram transport via the Extras backend (/v1/telegram/*).
//
// Used when CredentialSource.managed(reader, "telegram") is true:
// - createLink: calls POST /v1/telegram/link to mint a temporary deep-link URL.
// - getUpdates: polls POST /v1/telegram/updates to fetch new messages and acknowledge delivery.
// - sendMessage: calls POST /v1/telegram/send to deliver replies to linked chats.
// ===========================================================================

/**
 * Communicates with the Extras backend for managed Telegram bridging.
 */
object ManagedTelegramClient {

    /**
     * Mints a 15-minute deep link for connecting a Telegram chat to this customer.
     * Returns the full `https://t.me/<bot>?start=<token>` URL.
     */
    suspend fun createLink(reader: SettingsReader): String = withContext(Dispatchers.IO) {
        val reply = ExtrasClient.call(
            reader = reader,
            path = "/v1/telegram/link",
            body = buildJsonObject {},
        )
        reply["url"]?.jsonPrimitive?.contentOrNull
            ?: throw ExtrasClient.ExtrasException(200, "bad_reply", "Extras sent an answer this app could not read")
    }

    /**
     * Polls pending inbound messages after [afterId] from the Worker inbox and deletes delivered rows.
     */
    suspend fun getUpdates(reader: SettingsReader, afterId: Long): List<TelegramApi.Incoming> = withContext(Dispatchers.IO) {
        val reply = ExtrasClient.call(
            reader = reader,
            path = "/v1/telegram/updates",
            body = buildJsonObject {
                put("afterId", afterId)
            },
        )
        val updatesArray = reply["updates"] as? JsonArray ?: return@withContext emptyList()
        updatesArray.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val id = obj["id"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
            val chatId = obj["chatId"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
            val text = obj["text"]?.jsonPrimitive?.contentOrNull
            val receivedAt = obj["receivedAt"]?.jsonPrimitive?.contentOrNull
            val dateSeconds = receivedAt?.let {
                runCatching { Instant.parse(it).epochSecond }.getOrNull()
            } ?: (System.currentTimeMillis() / 1000)

            TelegramApi.Incoming(
                updateId = id,
                chatId = chatId,
                chatType = "private",
                chatName = chatId.toString(),
                date = dateSeconds,
                text = text,
            )
        }
    }

    /**
     * Sends [text] to [chatId] through the Extras backend.
     */
    suspend fun sendMessage(reader: SettingsReader, chatId: Long, text: String): Unit = withContext(Dispatchers.IO) {
        ExtrasClient.call(
            reader = reader,
            path = "/v1/telegram/send",
            body = buildJsonObject {
                put("chatId", chatId)
                put("text", text)
            },
        )
    }
}
