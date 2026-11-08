package dev.localintelligence.android.cloud

import dev.localintelligence.core.settings.SettingsReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.io.IOException

// ===========================================================================
// HTTPS client for the proxied Extras services (/v1/sandbox/*, /v1/browser/*,
// /v1/voice/*, /v1/mail/*, /v1/telegram/*).
//
// Authenticates with the customer access token (Authorization: Bearer <extrasToken>).
// Only https is called, redirects are not followed, and the response is read with a
// size cap. Request and response bodies are never written to a log.
// ===========================================================================

object ExtrasClient {
    private const val TIMEOUT_MS = 30_000
    private const val UNREACHABLE = "Could not reach Extras right now. Try again in a moment."
    private const val NOT_ACTIVE = "Extras is not active on this phone."
    private const val SERVICE_OFF = "This Extras service is not available yet."

    /**
     * The backend address from `BuildConfig.EXTRAS_BACKEND_URL`, passed in from `:app`
     * the same way [ExtrasBackend] receives it.
     */
    @Volatile
    var backendUrl: String = ""

    /** An error from the Extras backend with one plain sentence for the user. */
    class ExtrasException(
        val status: Int,
        val code: String?,
        message: String,
    ) : IOException(message)

    /** The normalized backend base URL from [backendUrl] or [ExtrasSettings.BACKEND_URL]. */
    fun baseUrl(reader: SettingsReader): String =
        backendUrl.ifBlank { reader[ExtrasSettings.BACKEND_URL] }.trim().trimEnd('/')

    /**
     * Calls `POST <baseUrl><path>` with [body] and the stored customer bearer token, and
     * returns the parsed JSON object from a 2xx reply. Throws [ExtrasException] with a plain
     * sentence on any failure.
     */
    suspend fun call(
        reader: SettingsReader,
        path: String,
        body: JsonObject = buildJsonObject {},
        baseUrl: String = baseUrl(reader),
    ): JsonObject = withContext(Dispatchers.IO) {
        val root = baseUrl.trim().trimEnd('/')
        if (!root.startsWith("https://")) {
            throw ExtrasException(0, "insecure_url", EXTRAS_INSECURE_URL)
        }
        val token = CredentialSource.extrasAccessToken(reader)
        if (token.isBlank()) {
            throw ExtrasException(401, "unauthorized", NOT_ACTIVE)
        }
        val normalizedPath = if (path.startsWith("/")) path else "/$path"
        val reply = try {
            postExtrasJson(
                baseUrl = root,
                path = normalizedPath,
                body = body,
                timeoutMs = TIMEOUT_MS,
                bearerToken = token,
            )
        } catch (e: IOException) {
            throw ExtrasException(0, "unreachable", UNREACHABLE)
        }
        when (reply.status) {
            in 200..299 -> try {
                Json.parseToJsonElement(reply.text).jsonObject
            } catch (e: Exception) {
                throw ExtrasException(reply.status, "bad_reply", EXTRAS_BAD_REPLY)
            }
            401, 402 -> throw ExtrasException(reply.status, parseError(reply.text)?.first, NOT_ACTIVE)
            429 -> {
                val parsed = parseError(reply.text)
                val serverMessage = parsed?.second
                val message = if (parsed?.first == "over_cap" && !serverMessage.isNullOrBlank()) {
                    serverMessage
                } else {
                    "Extras is busy right now. Try again in a few minutes."
                }
                throw ExtrasException(429, parsed?.first, message)
            }
            503 -> throw ExtrasException(503, parseError(reply.text)?.first, SERVICE_OFF)
            else -> throw ExtrasException(
                reply.status,
                parseError(reply.text)?.first,
                "Extras could not complete this request (HTTP ${reply.status}). Try again later.",
            )
        }
    }

    private fun parseError(text: String): Pair<String?, String?>? = runCatching {
        val obj = Json.parseToJsonElement(text).jsonObject
        val code = (obj["error"] as? JsonPrimitive)?.contentOrNull
        val message = (obj["message"] as? JsonPrimitive)?.contentOrNull
        code to message
    }.getOrNull()
}
