package dev.localintelligence.android.cloud

import dev.localintelligence.core.settings.SettingDef
import dev.localintelligence.core.settings.SettingSection
import dev.localintelligence.core.settings.SettingsReader
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
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
     * Whether Extras' remote model answers in chat. Off gives the chat back to the phone's own
     * model and keeps it there across restarts; Extras search stays on either way.
     */
    val CLOUD_CHAT = SettingDef.Toggle(
        "extras.cloud_chat", "Use the remote model for chat",
        "On, Extras' remote model answers. Off, the phone's own model answers; Extras search stays on.",
        SettingSection.PLUGINS, default = true, simple = true,
    )

    /**
     * Whether Extras' cloud voice answers for dictation and speech.
     */
    val BETTER_VOICE = SettingDef.Toggle(
        "extras.better_voice", "Better voice (Extras)",
        "On, Extras provides fast cloud speech recognition and natural voice replies. Off, the phone's own voice engine is used.",
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

    /** The access token the backend issued for proxied Extras services. Encrypted, never shown back. */
    val ACCESS_TOKEN = SettingDef.Secret(
        "extras.access_token", "Extras access token",
        "Issued for this phone while Extras is on. Stored encrypted and never shown back.",
        SettingSection.PLUGINS, simple = true,
    )

    /** Comma-separated names of the proxied Extras services enabled on the backend. */
    val SERVICES = SettingDef.Text(
        "extras.services", "Extras services",
        "The managed services the Extras backend has enabled.",
        SettingSection.PLUGINS, default = "", maxLength = 200, simple = true,
    )

    /** The Extras backend address from the app build, kept so proxied services in :android can read it. */
    val BACKEND_URL = SettingDef.Text(
        "extras.backend_url", "Extras backend address",
        "The Extras backend address.",
        SettingSection.PLUGINS, default = "", maxLength = 300, simple = true,
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

    /** True when the last grant reported [name] as enabled on the backend. */
    fun serviceOn(reader: SettingsReader, name: String): Boolean =
        name.isNotBlank() && reader[SERVICES].split(',').any { it.trim() == name }
}

/**
 * What the backend grants on a successful activate or refresh. Keys are never logged.
 * [exaKey] is null when the backend grants no search key: search then keeps the user's
 * chosen or free provider, with no error. [services] holds the names of the proxied
 * services that are enabled on the backend (empty when an older backend omits `services`).
 */
class ExtrasGrant(
    val expiresAt: String,
    val openRouterKey: String,
    val openRouterModel: String,
    val exaKey: String?,
    val extrasToken: String = "",
    val services: Set<String> = emptySet(),
)

/** The outcome of one backend call. [Failed] carries one plain sentence for the user. */
sealed interface ExtrasReply {
    class Granted(val grant: ExtrasGrant) : ExtrasReply
    /** The backend says the subscription is not active (HTTP 402). */
    data object NotActive : ExtrasReply
    class Failed(val message: String) : ExtrasReply
}

internal class ExtrasHttpReply(val status: Int, val text: String)

internal const val EXTRAS_MAX_REPLY_CHARS = 64 * 1024
internal const val EXTRAS_INSECURE_URL = "The Extras address is not secure, so nothing was sent."
internal const val EXTRAS_BAD_REPLY = "Extras sent an answer this app cannot read. Try again later."

/**
 * One POST to the Extras backend. Only https is called, redirects are not followed, and the
 * response is read with a size cap. Nothing from the request or reply is written to a log.
 */
internal fun postExtrasJson(
    baseUrl: String,
    path: String,
    body: JsonObject,
    timeoutMs: Int,
    bearerToken: String? = null,
    maxReplyChars: Int = EXTRAS_MAX_REPLY_CHARS,
): ExtrasHttpReply {
    val root = baseUrl.trim().trimEnd('/')
    val connection = (URL("$root$path").openConnection() as HttpURLConnection)
    return try {
        connection.instanceFollowRedirects = false
        connection.requestMethod = "POST"
        connection.connectTimeout = timeoutMs
        connection.readTimeout = timeoutMs
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("Accept", "application/json")
        if (!bearerToken.isNullOrBlank()) {
            connection.setRequestProperty("Authorization", "Bearer $bearerToken")
        }
        connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val status = connection.responseCode
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText().take(maxReplyChars) }.orEmpty()
        ExtrasHttpReply(status, text)
    } finally {
        connection.disconnect()
    }
}

/**
 * The calls to the Extras backend: `POST /v1/activate` and `POST /v1/refresh`.
 *
 * Only https is called, redirects are not followed, and the response is read with a size cap.
 * Nothing from a reply is written to a log: the keys are secrets. Blocking; call it on IO.
 */
object ExtrasBackend {
    private const val TIMEOUT_MS = 15_000
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
        ExtrasClient.backendUrl = baseUrl
        val root = baseUrl.trim().trimEnd('/')
        if (!root.startsWith("https://")) return ExtrasReply.Failed(EXTRAS_INSECURE_URL)
        return try {
            val reply = postExtrasJson(root, path, body, TIMEOUT_MS)
            when (reply.status) {
                in 200..299 -> parseGrant(reply.text)
                402 -> ExtrasReply.NotActive
                429 -> ExtrasReply.Failed("Extras is busy right now. Try again in a few minutes.")
                else -> ExtrasReply.Failed("Extras could not set up this phone (HTTP ${reply.status}). Try again later.")
            }
        } catch (e: IOException) {
            ExtrasReply.Failed(UNREACHABLE)
        }
    }

    /** The grant from a 2xx body. A body without the expected fields is a failure, not a grant. */
    internal fun parseGrant(text: String): ExtrasReply = try {
        val root = Json.parseToJsonElement(text).jsonObject
        val openRouter = root.getValue("openrouter").jsonObject
        val exa = root["exa"]?.let { if (it is JsonNull) null else it.jsonObject.getValue("key").jsonPrimitive.content }
        val extrasToken = (root["extrasToken"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val services = (root["services"] as? JsonObject)?.entries
            ?.mapNotNull { (k, v) -> k.takeIf { (v as? JsonPrimitive)?.booleanOrNull == true } }
            ?.toSet()
            .orEmpty()
        ExtrasReply.Granted(
            ExtrasGrant(
                expiresAt = root.getValue("expiresAt").jsonPrimitive.content,
                openRouterKey = openRouter.getValue("key").jsonPrimitive.content,
                openRouterModel = openRouter.getValue("model").jsonPrimitive.content,
                exaKey = exa,
                extrasToken = extrasToken,
                services = services,
            ),
        )
    } catch (e: Exception) {
        ExtrasReply.Failed(EXTRAS_BAD_REPLY)
    }
}
