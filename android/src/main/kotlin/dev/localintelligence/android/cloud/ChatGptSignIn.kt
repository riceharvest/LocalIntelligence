package dev.localintelligence.android.cloud

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import dev.localintelligence.android.cloud.ChatGptEndpoints.AUTHORIZE
import dev.localintelligence.android.cloud.ChatGptEndpoints.CALLBACK_PATH
import dev.localintelligence.android.cloud.ChatGptEndpoints.ISSUER
import dev.localintelligence.android.cloud.ChatGptEndpoints.PLAN_SCOPE
import dev.localintelligence.android.cloud.ChatGptEndpoints.RESOURCE
import dev.localintelligence.android.cloud.ChatGptEndpoints.TOKEN
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.security.spec.RSAPublicKeySpec
import java.util.Base64

// ===========================================================================
// "Sign in with ChatGPT" for an open-source app: a public client with no secret.
//
// The flow: PKCE and a fresh state and nonce per attempt, the system browser opens
// OpenAI's authorize page, OpenAI redirects to a listener bound to 127.0.0.1 only,
// the code is exchanged, the ID token is verified (RS256 against OpenAI's JWKS,
// issuer, audience, expiry, nonce), and the account is saved only when the granted
// scopes include chatgpt.tokens.use.direct. Nothing is saved before every check
// has passed, so a failed attempt never replaces a working account.
//
// What leaves the phone: the authorize request (client id, host id, PKCE challenge,
// and the saved ID token as a hint on a returning sign-in), the code exchange,
// refresh, JWKS and discovery fetches, and revocation at sign-out. Tokens are never
// logged and never put in a URL except the ID-token hint on the authorize request.
// ===========================================================================

/** A failure to show the user, in one plain sentence. [needsSignIn] means the saved tokens are gone. */
class ChatGptError(val userMessage: String, val needsSignIn: Boolean = false) : Exception(userMessage)

/** The result of one sign-in attempt. */
sealed interface ChatGptSignInOutcome {
    data class SignedIn(val account: String) : ChatGptSignInOutcome
    data class Failed(val message: String) : ChatGptSignInOutcome
}

/** The fixed addresses of the flow. Every request is built from these, never from a redirect. */
object ChatGptEndpoints {
    const val ISSUER = "https://auth.openai.com"
    const val DISCOVERY = "$ISSUER/.well-known/openid-configuration"
    const val AUTHORIZE = "$ISSUER/api/accounts/authorize"
    const val TOKEN = "$ISSUER/api/accounts/oauth/token"
    const val RESOURCE = "https://api.openai.com/v1"
    const val PLAN_SCOPE = "chatgpt.tokens.use.direct"
    const val SCOPES = "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct"
    const val DYNAMIC_CLIENT = "dynamic_agent_client"
    const val AGENT_NAME = "LocalIntelligence"
    const val CALLBACK_PATH = "/auth/callback"
    const val PREFERRED_PORT = 1455
    const val SIGN_IN_WAIT_MS = 5 * 60 * 1000L
}

/** Refresh this long before the access token expires. */
private const val REFRESH_AHEAD_MS = 5 * 60 * 1000L

/** Lifetime assumed when a token response omits `expires_in`. Docs say 3600. */
private const val DEFAULT_EXPIRES_SECONDS = 3600L

/** Clock skew tolerated on the ID token's `exp`. */
private const val SKEW_SECONDS = 60L

private const val REVOKE_ATTEMPTS = 3

/**
 * One request to OpenAI. Redirects are not followed, and the Bearer header goes only to the
 * resource API, so a token can never reach another host. The caller reads the body and
 * disconnects.
 */
internal fun openChatGptConnection(
    method: String,
    url: String,
    bearer: String? = null,
    form: Map<String, String>? = null,
    json: String? = null,
    accept: String = "application/json",
): HttpURLConnection {
    require(url.startsWith("$ISSUER/") || url.startsWith("$RESOURCE/")) { "not a ChatGPT address" }
    require(bearer == null || url.startsWith("$RESOURCE/")) { "the token goes only to the API" }
    val connection = URL(url).openConnection() as HttpURLConnection
    connection.requestMethod = method
    connection.connectTimeout = 20_000
    connection.readTimeout = 120_000
    connection.instanceFollowRedirects = false
    connection.setRequestProperty("Accept", accept)
    if (bearer != null) connection.setRequestProperty("Authorization", "Bearer $bearer")
    val payload = when {
        form != null -> {
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            form.entries.joinToString("&") { (k, v) -> enc(k) + "=" + enc(v) }
        }
        json != null -> {
            connection.setRequestProperty("Content-Type", "application/json")
            json
        }
        else -> null
    }
    if (payload != null) {
        connection.doOutput = true
        connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
    }
    return connection
}

/** A whole reply, read with a cap, for the JSON calls. */
internal class HttpReply(val status: Int, val body: String) {
    val ok: Boolean get() = status in 200..299
}

/** One JSON or form call on IO. An unreachable network becomes a [ChatGptError], never an IOException. */
internal suspend fun chatGptCall(
    method: String,
    url: String,
    bearer: String? = null,
    form: Map<String, String>? = null,
    json: String? = null,
): HttpReply = withContext(Dispatchers.IO) {
    try {
        val connection = openChatGptConnection(method, url, bearer, form, json)
        try {
            val status = connection.responseCode
            val stream: InputStream? = if (status in 200..299) connection.inputStream else connection.errorStream
            HttpReply(status, stream?.use { readCapped(it, MAX_JSON_BYTES) }.orEmpty())
        } finally {
            connection.disconnect()
        }
    } catch (e: IOException) {
        throw ChatGptError("Could not reach ChatGPT. Check the connection, then try again.")
    }
}

private const val MAX_JSON_BYTES = 2 * 1024 * 1024

internal fun readCapped(input: InputStream, max: Int): String {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(8 * 1024)
    while (out.size() < max) {
        val n = input.read(buffer, 0, minOf(buffer.size, max - out.size()))
        if (n < 0) break
        out.write(buffer, 0, n)
    }
    return out.toString(Charsets.UTF_8.name())
}

internal fun enc(text: String): String = URLEncoder.encode(text, Charsets.UTF_8.name())

/** Random bytes as base64url without padding: 43 characters, enough for a PKCE verifier. */
internal fun randomUrlToken(): String {
    val bytes = ByteArray(32)
    SecureRandom().nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

internal fun pkceChallenge(verifier: String): String =
    Base64.getUrlEncoder().withoutPadding()
        .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

internal fun parseJsonObject(text: String): JsonObject? =
    runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()

internal fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

internal fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

/** The signing keys and the endpoints from OpenAI's discovery document, fetched once per process. */
private object ChatGptKeys {
    @Volatile
    private var endpoints: Endpoints? = null

    @Volatile
    private var keys: Map<String, RSAPublicKey> = emptyMap()

    class Endpoints(val jwksUri: String, val revocationEndpoint: String)

    /** The discovery document. Its addresses must stay on the issuer, or the flow stops. */
    suspend fun endpoints(): Endpoints {
        endpoints?.let { return it }
        val reply = chatGptCall("GET", ChatGptEndpoints.DISCOVERY)
        val json = if (reply.ok) parseJsonObject(reply.body) else null
        val jwks = json?.string("jwks_uri")
        val revoke = json?.string("revocation_endpoint")
        if (json?.string("issuer") != ISSUER || jwks == null || revoke == null ||
            !jwks.startsWith("$ISSUER/") || !revoke.startsWith("$ISSUER/")
        ) {
            throw ChatGptError("ChatGPT's sign-in settings could not be read. Try again later.")
        }
        return Endpoints(jwks, revoke).also { endpoints = it }
    }

    /** The public key for [kid]. An unknown kid refetches the set once, since OpenAI can rotate keys. */
    suspend fun key(kid: String): RSAPublicKey? {
        keys[kid]?.let { return it }
        val reply = chatGptCall("GET", endpoints().jwksUri)
        val set = (if (reply.ok) parseJsonObject(reply.body) else null)?.get("keys") as? JsonArray
            ?: throw ChatGptError("ChatGPT's signing keys could not be read. Try again later.")
        val factory = KeyFactory.getInstance("RSA")
        val fresh = HashMap<String, RSAPublicKey>()
        for (element in set) {
            val jwk = element as? JsonObject ?: continue
            if (jwk.string("kty") != "RSA") continue
            val id = jwk.string("kid") ?: continue
            val n = jwk.string("n") ?: continue
            val e = jwk.string("e") ?: continue
            runCatching {
                val spec = RSAPublicKeySpec(
                    BigInteger(1, Base64.getUrlDecoder().decode(n)),
                    BigInteger(1, Base64.getUrlDecoder().decode(e)),
                )
                fresh[id] = factory.generatePublic(spec) as RSAPublicKey
            }
        }
        keys = fresh
        return fresh[kid]
    }
}

/** The identity a verified ID token proves. */
internal class IdentityClaims(val subject: String, val email: String)

/**
 * Verifies an ID token: RS256 signature against OpenAI's published key, the issuer, that
 * the audience names this client, that it has not expired (with a small skew), and that
 * the nonce is the one sent with this attempt. Anything else is refused.
 */
internal suspend fun verifyIdToken(token: String, clientId: String, nonce: String): IdentityClaims {
    val invalid = ChatGptError("ChatGPT's sign-in could not be verified. Try again.")
    val parts = token.split('.')
    if (parts.size != 3) throw invalid
    val header = decodeSegment(parts[0]) ?: throw invalid
    val claims = decodeSegment(parts[1]) ?: throw invalid
    if (header.string("alg") != "RS256") throw invalid
    val kid = header.string("kid") ?: throw invalid
    val key = ChatGptKeys.key(kid) ?: throw invalid
    val signed = "${parts[0]}.${parts[1]}".toByteArray(Charsets.US_ASCII)
    val signature = runCatching { Base64.getUrlDecoder().decode(parts[2]) }.getOrNull() ?: throw invalid
    val valid = runCatching {
        Signature.getInstance("SHA256withRSA").run {
            initVerify(key)
            update(signed)
            verify(signature)
        }
    }.getOrDefault(false)
    if (!valid) throw invalid
    if (claims.string("iss") != ISSUER) throw invalid
    if (!audienceIncludes(claims, clientId)) throw invalid
    val exp = claims.long("exp") ?: throw invalid
    if (System.currentTimeMillis() / 1000 > exp + SKEW_SECONDS) throw invalid
    if (claims.string("nonce") != nonce) throw invalid
    val subject = claims.string("sub") ?: throw invalid
    return IdentityClaims(subject, claims.string("email").orEmpty())
}

/** `aud` may be one string or a list. Either way it must name this client. */
private fun audienceIncludes(claims: JsonObject, clientId: String): Boolean =
    when (val aud = claims["aud"]) {
        is JsonPrimitive -> aud.isString && aud.content == clientId
        is JsonArray -> aud.any { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content == clientId }
        else -> false
    }

private fun decodeSegment(segment: String): JsonObject? {
    val bytes = runCatching { Base64.getUrlDecoder().decode(segment) }.getOrNull() ?: return null
    return parseJsonObject(String(bytes, Charsets.UTF_8))
}

/**
 * The loopback listener for the browser's redirect. Bound to 127.0.0.1 only, so no other
 * device can reach it. Only the callback path with this attempt's state is accepted;
 * anything else gets a short page and the wait goes on.
 */
private class LoopbackCallback private constructor(
    private val server: ServerSocket,
    val port: Int,
    private val expectedState: String,
) {
    /** Stops the wait and frees the port. Safe to call twice. */
    fun close() {
        runCatching { server.close() }
    }

    /** The callback's query parameters, or null when the wait runs out or the listener closes. */
    suspend fun await(timeoutMs: Long): Map<String, String>? = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + timeoutMs
        var found: Map<String, String>? = null
        while (found == null) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) break
            val client = try {
                server.soTimeout = left.toInt()
                server.accept()
            } catch (e: IOException) {
                // Timeout, or closed by close(): either way the wait is over.
                break
            }
            found = client.use { runCatching { handle(it) }.getOrNull() }
        }
        found
    }

    private fun handle(client: Socket): Map<String, String>? {
        client.soTimeout = 5_000
        val line = readLine(client.getInputStream()) ?: return null
        val parts = line.split(' ')
        if (parts.size < 2 || parts[0] != "GET") {
            respond(client, 405, "Not here.")
            return null
        }
        val target = parts[1]
        if (target.substringBefore('?') != CALLBACK_PATH) {
            respond(client, 404, "Not found.")
            return null
        }
        val params = parseQuery(target.substringAfter('?', ""))
        if (params["state"] != expectedState) {
            respond(client, 400, "This page is not from this sign-in attempt.")
            return null
        }
        respond(client, 200, DONE_PAGE)
        return params
    }

    companion object {
        /** Binds the preferred port, or any free one. Null when neither can be bound. */
        fun open(expectedState: String): LoopbackCallback? {
            for (port in intArrayOf(ChatGptEndpoints.PREFERRED_PORT, 0)) {
                val server = ServerSocket()
                try {
                    server.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port))
                    return LoopbackCallback(server, server.localPort, expectedState)
                } catch (e: IOException) {
                    runCatching { server.close() }
                }
            }
            return null
        }

        private const val DONE_PAGE =
            "<!doctype html><html><head><meta charset=\"utf-8\">" +
                "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
                "<title>LocalIntelligence</title></head>" +
                "<body style=\"font-family:sans-serif;padding:24px\">" +
                "<p>You can go back to LocalIntelligence.</p></body></html>"

        private fun readLine(input: InputStream): String? {
            val out = ByteArrayOutputStream()
            while (out.size() < 4096) {
                val b = input.read()
                if (b < 0 || b == '\n'.code) break
                out.write(b)
            }
            return if (out.size() == 0) null else String(out.toByteArray(), Charsets.US_ASCII).trimEnd('\r')
        }

        private fun parseQuery(query: String): Map<String, String> =
            query.split('&').mapNotNull { pair ->
                val key = pair.substringBefore('=', "")
                if (key.isEmpty()) return@mapNotNull null
                key to runCatching { URLDecoder.decode(pair.substringAfter('=', ""), Charsets.UTF_8.name()) }
                    .getOrDefault("")
            }.toMap()

        private fun respond(client: Socket, status: Int, body: String) {
            val bytes = body.toByteArray(Charsets.UTF_8)
            val reason = when (status) {
                200 -> "OK"
                400 -> "Bad Request"
                404 -> "Not Found"
                else -> "Method Not Allowed"
            }
            val head = "HTTP/1.1 $status $reason\r\nContent-Type: text/html; charset=utf-8\r\n" +
                "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
            client.getOutputStream().apply {
                write(head.toByteArray(Charsets.US_ASCII))
                write(bytes)
                flush()
            }
        }
    }
}

/**
 * One sign-in at a time. A new sign-in is kept apart from the saved account until every check
 * has passed; a returning sign-in must be the same account (same `sub`).
 *
 * [keepAlive] and [releaseKeepAlive] bracket the wait for the browser: the app is in the
 * background then, and a foreground service keeps its process and listener alive.
 */
class ChatGptSignIn(
    private val context: Context,
    private val plugin: ChatGptPlugin,
    private val keepAlive: () -> Unit,
    private val releaseKeepAlive: () -> Unit,
) {
    private val attempt = Mutex()

    /** Runs one sign-in from start to saved account. Never throws, except cancellation. */
    suspend fun signIn(): ChatGptSignInOutcome = attempt.withLock {
        try {
            signInOnce()
        } catch (e: ChatGptError) {
            ChatGptSignInOutcome.Failed(e.userMessage)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ChatGptSignInOutcome.Failed("Sign-in could not finish (${e.javaClass.simpleName}). Try again.")
        }
    }

    /**
     * Ends the account's renewable session at OpenAI, then clears the tokens here. Returns false
     * when OpenAI did not confirm the revocation; the tokens are cleared regardless.
     */
    suspend fun signOut(): Boolean = ChatGptAuth.signOut(plugin)

    private suspend fun signInOnce(): ChatGptSignInOutcome {
        val saved = plugin.registration()
        val host = plugin.hostId()
        val verifier = randomUrlToken()
        val state = randomUrlToken()
        val nonce = randomUrlToken()
        val listener = withContext(Dispatchers.IO) { LoopbackCallback.open(state) }
            ?: return ChatGptSignInOutcome.Failed("This phone could not open the sign-in listener. Try again.")
        val redirect = "http://127.0.0.1:${listener.port}$CALLBACK_PATH"
        // The URL carries the saved ID token as a hint, so it is never logged.
        val url = authorizeUrl(saved, host, redirect, state, nonce, pkceChallenge(verifier))

        val received = try {
            keepAlive()
            if (!openBrowser(url)) {
                return ChatGptSignInOutcome.Failed("No browser opened for sign-in. Install or enable a browser and try again.")
            }
            listener.await(ChatGptEndpoints.SIGN_IN_WAIT_MS)
        } finally {
            listener.close()
            releaseKeepAlive()
        }
        val params = received
            ?: return ChatGptSignInOutcome.Failed("Sign-in timed out. Choose Continue with ChatGPT to try again.")

        params["error"]?.let { error ->
            if (error == "access_denied") {
                return ChatGptSignInOutcome.Failed(
                    "Sign-in was not finished, so ChatGPT plan use is still off. Choose Continue with ChatGPT to allow it.",
                )
            }
            val code = error.filter { it.isLetterOrDigit() || it == '_' }.take(40)
            return ChatGptSignInOutcome.Failed("ChatGPT did not complete sign-in ($code). Try again.")
        }
        val code = params["code"]
            ?: return ChatGptSignInOutcome.Failed("ChatGPT did not return a sign-in code. Try again.")

        val returnedClient = params["client_id"]?.takeIf { it.isNotBlank() }
        val clientId = if (saved != null) {
            if (returnedClient != null && returnedClient != saved.clientId) {
                return ChatGptSignInOutcome.Failed("ChatGPT returned a different registration. Sign in again.")
            }
            saved.clientId
        } else {
            returnedClient ?: return ChatGptSignInOutcome.Failed("ChatGPT did not finish registering this app. Try again.")
        }

        val tokens = exchangeCode(code, clientId, verifier, redirect)
        val idToken = tokens.string("id_token")
            ?: return ChatGptSignInOutcome.Failed("ChatGPT did not return an ID token. Try again.")
        val claims = verifyIdToken(idToken, clientId, nonce)
        if (saved != null && saved.subject.isNotBlank() && saved.subject != claims.subject) {
            return ChatGptSignInOutcome.Failed(
                "That is a different ChatGPT account. Sign in with the account you used before.",
            )
        }
        val granted = (tokens.string("scope") ?: params["scope"].orEmpty())
            .split(' ').filter { it.isNotBlank() }.toSet()
        if (PLAN_SCOPE !in granted) {
            return ChatGptSignInOutcome.Failed(
                "ChatGPT plan use was not allowed, so nothing was saved. Sign in again and allow it.",
            )
        }
        val access = tokens.string("access_token")
            ?: return ChatGptSignInOutcome.Failed("ChatGPT did not return an access token. Try again.")
        val refresh = tokens.string("refresh_token")
            ?: return ChatGptSignInOutcome.Failed("ChatGPT did not return a refresh token. Try again.")
        val expiresAt = System.currentTimeMillis() +
            (tokens.long("expires_in") ?: DEFAULT_EXPIRES_SECONDS) * 1000

        val email = claims.email.ifBlank { saved?.email.orEmpty() }
        plugin.saveAccount(
            ChatGptAccount(clientId, claims.subject, email, access, refresh, expiresAt, granted),
            idToken,
        )
        return ChatGptSignInOutcome.SignedIn(email.ifBlank { "your ChatGPT account" })
    }

    private fun authorizeUrl(
        saved: ChatGptRegistration?,
        host: String,
        redirect: String,
        state: String,
        nonce: String,
        challenge: String,
    ): String {
        val params = linkedMapOf(
            "client_id" to (saved?.clientId ?: ChatGptEndpoints.DYNAMIC_CLIENT),
            "ext_agent_host_id" to host,
            "response_type" to "code",
            "redirect_uri" to redirect,
            "scope" to ChatGptEndpoints.SCOPES,
            "resource" to RESOURCE,
            "state" to state,
            "nonce" to nonce,
            "code_challenge_method" to "S256",
            "code_challenge" to challenge,
        )
        if (saved == null) {
            params["agent_name_hint"] = ChatGptEndpoints.AGENT_NAME
        } else {
            if (saved.idToken.isNotBlank()) params["id_token_hint"] = saved.idToken
            if (saved.email.isNotBlank()) params["login_hint"] = saved.email
        }
        return AUTHORIZE + "?" + params.entries.joinToString("&") { (k, v) -> enc(k) + "=" + enc(v) }
    }

    /** Opens the system browser. False when there is none to open it. */
    private fun openBrowser(url: String): Boolean = try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: ActivityNotFoundException) {
        false
    }

    private suspend fun exchangeCode(code: String, clientId: String, verifier: String, redirect: String): JsonObject {
        val reply = chatGptCall(
            "POST", TOKEN,
            form = mapOf(
                "grant_type" to "authorization_code",
                "client_id" to clientId,
                "code" to code,
                "code_verifier" to verifier,
                "redirect_uri" to redirect,
                "resource" to RESOURCE,
            ),
        )
        val json = if (reply.ok) parseJsonObject(reply.body) else null
        return json ?: throw ChatGptError("ChatGPT did not finish sign-in (HTTP ${reply.status}). Try again.")
    }
}

/**
 * Access tokens for the backend, and sign-out. One lock covers refresh and revocation, because
 * the refresh token rotates: two refreshes racing would leave one of them holding a dead token.
 */
internal object ChatGptAuth {
    private val tokenLock = Mutex()

    /** A usable access token: the saved one, or a fresh one when it is near expiry or [force] is set. */
    suspend fun accessToken(plugin: ChatGptPlugin, force: Boolean = false): String = tokenLock.withLock {
        val account = plugin.account()
            ?: throw ChatGptError("Sign in with ChatGPT first.", needsSignIn = true)
        val fresh = account.accessToken.isNotBlank() &&
            account.expiresAtMs - REFRESH_AHEAD_MS > System.currentTimeMillis()
        if (!force && fresh) account.accessToken else refresh(plugin, account)
    }

    private suspend fun refresh(plugin: ChatGptPlugin, account: ChatGptAccount): String {
        val reply = chatGptCall(
            "POST", TOKEN,
            form = mapOf(
                "grant_type" to "refresh_token",
                "client_id" to account.clientId,
                "refresh_token" to account.refreshToken,
                "resource" to RESOURCE,
            ),
        )
        if (!reply.ok) {
            val code = parseJsonObject(reply.body)?.string("error")
            if (code == "invalid_client") {
                throw ChatGptError("ChatGPT sign-in is set up wrongly on OpenAI's side. Try again later.")
            }
            if (reply.status == 429 || reply.status >= 500) {
                throw ChatGptError("ChatGPT could not refresh your sign-in right now. Try again later.")
            }
            // invalid_grant and the other refresh errors: the refresh token is unusable.
            plugin.clearTokens()
            throw ChatGptError("Sign in with ChatGPT again.", needsSignIn = true)
        }
        val json = parseJsonObject(reply.body)
            ?: throw ChatGptError("ChatGPT sent an unreadable reply. Try again later.")
        val access = json.string("access_token")
            ?: throw ChatGptError("ChatGPT sent no access token. Try again later.")
        // A replacement refresh token replaces the old one; without one the old one stays valid.
        val refresh = json.string("refresh_token") ?: account.refreshToken
        val scopes = json.string("scope")
            ?.split(' ')?.filter { it.isNotBlank() }?.toSet()
            ?: account.scopes
        if (PLAN_SCOPE !in scopes) {
            plugin.clearTokens()
            throw ChatGptError(
                "ChatGPT plan use is no longer allowed for this sign-in. Sign in again and allow it.",
                needsSignIn = true,
            )
        }
        val expiresAt = System.currentTimeMillis() +
            (json.long("expires_in") ?: DEFAULT_EXPIRES_SECONDS) * 1000
        plugin.saveTokens(access, refresh, expiresAt, scopes)
        return access
    }

    /** Revokes the refresh token at OpenAI when it can, then clears the local tokens either way. */
    suspend fun signOut(plugin: ChatGptPlugin): Boolean = tokenLock.withLock {
        val account = plugin.account()
        val confirmed = account == null || revoke(account)
        plugin.signedOut()
        confirmed
    }

    /**
     * A bounded retry loop. A network error or a 5xx retries with backoff; any other answer is
     * final. An empty 200 is the success reply, and OpenAI also answers 200 for a token it has
     * already dropped.
     */
    private suspend fun revoke(account: ChatGptAccount): Boolean {
        repeat(REVOKE_ATTEMPTS) { attempt ->
            val reply = try {
                chatGptCall(
                    "POST", ChatGptKeys.endpoints().revocationEndpoint,
                    form = mapOf(
                        "token" to account.refreshToken,
                        "token_type_hint" to "refresh_token",
                        "client_id" to account.clientId,
                    ),
                )
            } catch (e: ChatGptError) {
                null
            }
            if (reply != null) {
                if (reply.ok) return true
                if (reply.status < 500) return false
            }
            delay(1_000L * (attempt + 1))
        }
        return false
    }
}
