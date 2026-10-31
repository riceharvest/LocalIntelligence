package dev.localintelligence.android.gateway.telegram

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

// ===========================================================================
// The two Bot API methods this app uses, over plain HttpURLConnection (no new
// dependency, the same transport the Home Assistant and cloud plugins use):
//
//   getUpdates  long poll for new messages (timeout 50 s, allowed_updates: message)
//   sendMessage reply text to a chat, at most 4096 characters per message
//
// Nothing else is called. The token is part of the URL path, so no exception
// text from this file includes the URL: a failure names the method and Telegram's
// own description only.
// ===========================================================================

/** The Bot API surface this app uses. Pure JVM apart from the socket. */
class TelegramApi(private val base: String = "https://api.telegram.org") {

    /**
     * Telegram refused the call, or the request never completed. [code] is
     * Telegram's error_code (401 for a bad token, 409 when another client is
     * polling), or 0 when no answer arrived.
     */
    class Failure(val code: Int, message: String) : IOException(message)

    /** One incoming message, reduced to what the gateway decides on. */
    data class Incoming(
        val updateId: Long,
        val chatId: Long,
        val chatType: String,
        val chatName: String,
        /** Unix seconds, as Telegram sends it. */
        val date: Long,
        /** Null for a message without text (a photo, a voice note, a sticker). */
        val text: String?,
    ) {
        val isStart: Boolean
            get() = text?.trim().let { it == "/start" || (it != null && it.startsWith("/start ")) }
    }

    /**
     * Long poll for messages after [offset]. Blocks up to [LONG_POLL_SECONDS]
     * when nothing is waiting. Updates other than messages are not requested.
     */
    fun getUpdates(token: String, offset: Long): List<Incoming> {
        val body = buildJsonObject {
            put("offset", offset)
            put("timeout", LONG_POLL_SECONDS)
            put("allowed_updates", buildJsonArray { add(JsonPrimitive("message")) })
        }
        val root = call(token, "getUpdates", body, readTimeoutMs = (LONG_POLL_SECONDS + 15) * 1000)
        val updates = root["result"] as? JsonArray ?: return emptyList()
        return updates.mapNotNull { (it as? JsonObject)?.let { obj -> incomingOf(obj) } }
    }

    /** Sends [text] to [chatId]. The caller splits it to the 4096-character limit. */
    fun sendMessage(token: String, chatId: Long, text: String) {
        val body = buildJsonObject {
            put("chat_id", chatId)
            put("text", text)
        }
        call(token, "sendMessage", body, readTimeoutMs = SEND_TIMEOUT_MS)
    }

    private fun call(token: String, method: String, body: JsonObject, readTimeoutMs: Int): JsonObject {
        val connection = try {
            URL("$base/bot$token/$method").openConnection() as HttpURLConnection
        } catch (e: java.net.MalformedURLException) {
            throw Failure(0, "$method: the bot address is not valid")
        }
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = readTimeoutMs
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val root = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
                ?: throw Failure(code, "$method: Telegram sent an answer this app could not read")
            if (root["ok"]?.jsonPrimitive?.booleanOrNull != true) {
                val telegramCode = root["error_code"]?.jsonPrimitive?.longOrNull?.toInt() ?: code
                val description = root["description"]?.jsonPrimitive?.contentOrNull.orEmpty().take(200)
                throw Failure(telegramCode, "$method: ${description.ifBlank { "Telegram refused the call" }}")
            }
            return root
        } catch (e: SocketTimeoutException) {
            throw Failure(0, "$method: Telegram did not answer in time")
        } finally {
            connection.disconnect()
        }
    }

    private fun incomingOf(update: JsonObject): Incoming? {
        val updateId = update["update_id"]?.jsonPrimitive?.longOrNull ?: return null
        val message = update["message"] as? JsonObject ?: return null
        val chat = message["chat"] as? JsonObject ?: return null
        val chatId = chat["id"]?.jsonPrimitive?.longOrNull ?: return null
        return Incoming(
            updateId = updateId,
            chatId = chatId,
            chatType = chat["type"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            chatName = nameOf(chat, chatId),
            date = message["date"]?.jsonPrimitive?.longOrNull ?: 0L,
            text = message["text"]?.jsonPrimitive?.contentOrNull,
        )
    }

    /** Title for a group, first and last name for a person, @username, else the id. */
    private fun nameOf(chat: JsonObject, chatId: Long): String {
        chat["title"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { return it }
        val person = listOfNotNull(
            chat["first_name"]?.jsonPrimitive?.contentOrNull,
            chat["last_name"]?.jsonPrimitive?.contentOrNull,
        ).joinToString(" ").trim()
        if (person.isNotEmpty()) return person
        chat["username"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { return "@$it" }
        return chatId.toString()
    }

    companion object {
        /** Telegram's long-poll window. Sent to the server as the `timeout` parameter. */
        const val LONG_POLL_SECONDS = 50

        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val SEND_TIMEOUT_MS = 30_000
    }
}

/** Telegram's limit for one message, in characters after entity parsing. */
const val TELEGRAM_MESSAGE_LIMIT = 4096

/**
 * Splits [text] into messages of at most [limit] characters. Breaks at the last
 * newline in the second half of a chunk when there is one, else hard-cuts, and
 * never splits a surrogate pair.
 */
fun splitForTelegram(text: String, limit: Int = TELEGRAM_MESSAGE_LIMIT): List<String> {
    if (text.length <= limit) return listOf(text)
    val parts = ArrayList<String>()
    var rest = text
    while (rest.length > limit) {
        var cut = rest.lastIndexOf('\n', limit)
        if (cut < limit / 2) cut = limit
        if (Character.isHighSurrogate(rest[cut - 1])) cut--
        parts += rest.substring(0, cut).trimEnd()
        rest = rest.substring(cut).trimStart('\n')
    }
    if (rest.isNotEmpty()) parts += rest
    return parts
}
