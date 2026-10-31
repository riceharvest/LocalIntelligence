package dev.localintelligence.android.cloud

import dev.localintelligence.core.settings.SettingDef
import dev.localintelligence.core.settings.SettingSection
import dev.localintelligence.core.settings.SettingsReader
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

// ===========================================================================
// Extras: the paid managed option. A monthly Google Play subscription gives this phone
// its own capped OpenRouter key (chat) and Exa key (search), issued by the Extras backend.
//
// WHY THESE ROWS ARE NOT IN ANY PLUGIN'S LIST: Settings draws its rows from the plugins,
// and Extras is not a plugin. Its rows are written by the purchase code only, and the
// Extras screen is the one place they are shown (as "is it on", never the key).
//
// WHY SIMPLE = TRUE: a row that is not simple reads as its Simple default. Extras exists
// for Simple mode, so every Extras row must read its stored value there.
//
// The phone never holds the provisioning keys. The backend only sees the Play purchase
// token and a random install id; chat and search traffic go straight to OpenRouter and Exa.
// ===========================================================================

/** The Extras rows. Written by the purchase code in :app, read through [CredentialSource]. */
object ExtrasSettings {
    /** The one Play product that unlocks Extras. */
    const val PRODUCT_ID = "extras_monthly"

    /** The OpenRouter key the backend issued for this phone. Encrypted, never shown back. */
    val OPENROUTER_KEY = SettingDef.Secret(
        "extras.openrouter_key", "Extras chat key",
        "Issued for this phone while Extras is on. Stored encrypted and never shown back.",
        SettingSection.PLUGINS, simple = true,
    )

    /** The Exa key the backend issued for this phone. Encrypted, never shown back. */
    val EXA_KEY = SettingDef.Secret(
        "extras.exa_key", "Extras search key",
        "Issued for this phone while Extras is on. Stored encrypted and never shown back.",
        SettingSection.PLUGINS, simple = true,
    )

    /** The Play purchase token, kept so the phone can refresh at start. Encrypted. */
    val PURCHASE_TOKEN = SettingDef.Secret(
        "extras.purchase_token", "Extras purchase token",
        "The Google Play purchase this phone activated. Stored encrypted.",
        SettingSection.PLUGINS, simple = true,
    )

    /**
     * Whether Extras' cloud model answers in chat. Off gives the chat back to the phone's own
     * model and keeps it there across restarts; Extras search stays on either way.
     */
    val CLOUD_CHAT = SettingDef.Toggle(
        "extras.cloud_chat", "Use the cloud model for chat",
        "On, Extras' cloud model answers. Off, the phone's own model answers; Extras search stays on.",
        SettingSection.PLUGINS, default = true, simple = true,
    )

    /** The chat model the backend chose for Extras. Blank when Extras is off. */
    val OPENROUTER_MODEL = SettingDef.Text(
        "extras.openrouter_model", "Extras chat model",
        "The model the Extras chat uses.",
        SettingSection.PLUGINS, default = "", maxLength = 120, simple = true,
    )

    /** When the current grant ends, as an ISO-8601 instant. Blank when Extras is off. */
    val EXPIRES_AT = SettingDef.Text(
        "extras.expires_at", "Extras expiry",
        "When the current Extras grant ends.",
        SettingSection.PLUGINS, default = "", maxLength = 40, simple = true,
    )

    /** A random id for this install, sent with each backend call. Made once. */
    val INSTALL_ID = SettingDef.Text(
        "extras.install_id", "Install id",
        "A random id for this install, sent to the Extras backend. Not your identity.",
        SettingSection.PLUGINS, default = "", maxLength = 64, simple = true,
    )

    /** The grant's end, or null when none is stored or it is not a time. */
    fun expiresAt(reader: SettingsReader): Instant? =
        runCatching { Instant.parse(reader[EXPIRES_AT]) }.getOrNull()

    /** True while a key is stored and its grant has not ended. */
    fun isActive(reader: SettingsReader, now: Instant = Instant.now()): Boolean {
        if (reader[OPENROUTER_KEY].isBlank()) return false
        val end = expiresAt(reader) ?: return false
        return end.isAfter(now)
    }
}

/**
 * What the backend grants on a successful activate or refresh. Keys are never logged.
 * [exaKey] is null when the backend grants no search key: search then keeps the user's
 * chosen or free provider, with no error.
 */
class ExtrasGrant(
    val expiresAt: String,
    val openRouterKey: String,
    val openRouterModel: String,
    val exaKey: String?,
)

/** The outcome of one backend call. [Failed] carries one plain sentence for the user. */
sealed interface ExtrasReply {
    class Granted(val grant: ExtrasGrant) : ExtrasReply
    /** The backend says the subscription is not active (HTTP 402). */
    data object NotActive : ExtrasReply
    class Failed(val message: String) : ExtrasReply
}

/**
 * The calls to the Extras backend: `POST /v1/activate` and `POST /v1/refresh`.
 *
 * Only https is called, redirects are not followed, and the response is read with a size cap.
 * Nothing from a reply is written to a log: the keys are secrets. Blocking; call it on IO.
 */
object ExtrasBackend {
    private const val TIMEOUT_MS = 15_000
    private const val MAX_REPLY_CHARS = 64 * 1024
    private val UNREACHABLE = "Could not reach Extras right now. Google Play keeps your purchase, so try again later."

    fun activate(baseUrl: String, packageName: String, purchaseToken: String, installId: String): ExtrasReply =
        post(
            baseUrl, "/v1/activate",
            buildJsonObject {
                put("packageName", packageName)
                put("productId", ExtrasSettings.PRODUCT_ID)
                put("purchaseToken", purchaseToken)
                put("installId", installId)
            },
        )

    fun refresh(baseUrl: String, purchaseToken: String, installId: String): ExtrasReply =
        post(
            baseUrl, "/v1/refresh",
            buildJsonObject {
                put("purchaseToken", purchaseToken)
                put("installId", installId)
            },
        )

    private fun post(baseUrl: String, path: String, body: JsonObject): ExtrasReply {
        val root = baseUrl.trim().trimEnd('/')
        if (!root.startsWith("https://")) return ExtrasReply.Failed("The Extras address is not secure, so nothing was sent.")
        val connection = try {
            (URL("$root$path").openConnection() as HttpURLConnection)
        } catch (e: IOException) {
            return ExtrasReply.Failed(UNREACHABLE)
        }
        return try {
            connection.instanceFollowRedirects = false
            connection.requestMethod = "POST"
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText().take(MAX_REPLY_CHARS) }.orEmpty()
            when (status) {
                in 200..299 -> parseGrant(text)
                402 -> ExtrasReply.NotActive
                429 -> ExtrasReply.Failed("Extras is busy right now. Try again in a few minutes.")
                else -> ExtrasReply.Failed("Extras could not set up this phone (HTTP $status). Try again later.")
            }
        } catch (e: IOException) {
            ExtrasReply.Failed(UNREACHABLE)
        } finally {
            connection.disconnect()
        }
    }

    /** The grant from a 2xx body. A body without the expected fields is a failure, not a grant. */
    private fun parseGrant(text: String): ExtrasReply = try {
        val root = Json.parseToJsonElement(text).jsonObject
        val openRouter = root.getValue("openrouter").jsonObject
        ExtrasReply.Granted(
            ExtrasGrant(
                expiresAt = root.getValue("expiresAt").jsonPrimitive.content,
                openRouterKey = openRouter.getValue("key").jsonPrimitive.content,
                openRouterModel = openRouter.getValue("model").jsonPrimitive.content,
                exaKey = root.getValue("exa").let { if (it is JsonNull) null else it.jsonObject.getValue("key").jsonPrimitive.content },
            ),
        )
    } catch (e: Exception) {
        ExtrasReply.Failed("Extras sent an answer this app cannot read. Try again later.")
    }
}
