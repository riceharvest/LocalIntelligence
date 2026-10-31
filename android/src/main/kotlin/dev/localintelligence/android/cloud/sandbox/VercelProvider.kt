package dev.localintelligence.android.cloud.sandbox

import dev.localintelligence.core.tool.ToolError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.util.zip.GZIPOutputStream

// ===========================================================================
// Vercel Sandbox, a cloud sandbox on the user's own Vercel account. It uses the REST API at
// api.vercel.com (documented in the OpenAPI spec at openapi.vercel.sh, and at
// vercel.com/docs/rest-api/sandboxes), with an access token from the user's account.
//
// The sandbox is a named sandbox, so its name is the id this app keeps. Each run of it is a
// "session", and a command needs the current session's id:
//   POST   /v4/sandboxes                                 create (name, projectId, timeout, persistent)
//   GET    /v2/sandboxes/{name}?projectId=&resume=true   read it; resume starts a stopped one
//   DELETE is not used: stop keeps the files (persistent), so a later start resumes them
//   GET    /v2/sandboxes/sessions/{sid}                  the session's status
//   POST   /v2/sandboxes/sessions/{sid}/stop             stop the session (files kept in a snapshot)
//   POST   /v2/sandboxes/sessions/{sid}/cmd              start a command: {command, args, timeout (ms)}
//   GET    /v2/sandboxes/sessions/{sid}/cmd/{cmdId}?wait=true   block until it finishes; exitCode
//   GET    /v2/sandboxes/sessions/{sid}/cmd/{cmdId}/logs        output, one ND-JSON line per chunk
//   POST   /v2/sandboxes/sessions/{sid}/fs/mkdir        {path} creates a folder, parents too
//   POST   /v2/sandboxes/sessions/{sid}/fs/write        a gzipped tar, extracted into x-cwd
//   POST   /v2/sandboxes/sessions/{sid}/fs/read         {path} returns the raw bytes
//
// The token goes only to https://api.vercel.com, and redirects are not followed, so the Bearer
// header cannot follow a reply to another host. The token is removed from any error text.
// ===========================================================================

/** The id the Choice setting stores for this provider. */
internal const val VERCEL_ID: String = "vercel"

private const val VERCEL_API = "https://api.vercel.com"
private const val NAME_PREFIX = "li-"
private const val CONNECT_TIMEOUT_MS = 20_000
private const val READ_TIMEOUT_MS = 30_000
private const val JSON_TYPE = "application/json"
private const val TAR_BLOCK = 512
private const val TAR_NAME_CHARS = 100
private const val ERROR_CHARS = 160

/** Names and ids from Vercel are short URL-safe strings. Anything else is refused before it goes in a path. */
private val SAFE_ID = Regex("[A-Za-z0-9_-]{1,128}")

/** Words a refusal uses when the account is out of credit. Unverified against Vercel's exact wording. */
private val BILLING_WORDS = Regex("credit|balance|billing|payment|quota", RegexOption.IGNORE_CASE)

private const val VERCEL_BAD_REPLY = "Vercel sent a reply this app cannot read. Try again."

/**
 * The Vercel implementation of [SandboxProvider]. Built per call from the live settings, so a
 * token, project or team changed in Settings applies to the next tool call.
 */
internal class VercelProvider(
    private val token: String,
    private val projectId: String,
    private val teamId: String,
) : SandboxProvider {

    override suspend fun create(idleMinutes: Int): SandboxInfo = withContext(Dispatchers.IO) {
        val name = NAME_PREFIX + randomHex()
        val body = buildJsonObject {
            put("name", name)
            put("projectId", projectId)
            // Vercel's timeout is the most a session may run before it stops, not an idle timer.
            put("timeout", idleMinutes * 60_000L)
            put("persistent", true)
        }.toString()
        val reply = objectOf(checked(exchange("POST", url("/v4/sandboxes"), body.toByteArray(Charsets.UTF_8), JSON_TYPE)))
        val status = reply["session"]?.jsonObject?.get("status")?.jsonPrimitive?.contentOrNull
        SandboxInfo(id = name, state = stateOf(status), toolbox = null)
    }

    override suspend fun info(id: String): SandboxInfo = withContext(Dispatchers.IO) {
        val sessionId = sessionIdOf(id)
        val state = if (sessionId == null) "stopped" else stateOf(statusOf(sessionId))
        SandboxInfo(id = id, state = state, toolbox = null)
    }

    override suspend fun start(id: String): Unit = withContext(Dispatchers.IO) {
        checked(exchange("GET", url("/v2/sandboxes/${safe(id)}", "projectId" to projectId, "resume" to "true"), null, null))
        Unit
    }

    override suspend fun stop(id: String): Unit = withContext(Dispatchers.IO) {
        val sessionId = sessionIdOf(id) ?: return@withContext
        checked(exchange("POST", url("/v2/sandboxes/sessions/${safe(sessionId)}/stop"), ByteArray(0), null))
        Unit
    }

    override suspend fun exec(info: SandboxInfo, command: String, timeoutSeconds: Int): ExecResult =
        withContext(Dispatchers.IO) {
            val sessionId = currentSession(info)
            val body = buildJsonObject {
                put("command", "sh")
                putJsonArray("args") {
                    add(JsonPrimitive("-c"))
                    add(JsonPrimitive(command))
                }
                put("timeout", timeoutSeconds * 1000L)
            }.toString()
            val started = objectOf(
                checked(exchange("POST", url("/v2/sandboxes/sessions/$sessionId/cmd"), body.toByteArray(Charsets.UTF_8), JSON_TYPE)),
            )
            val cmdId = started["command"]?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull
                ?.takeIf { SAFE_ID.matches(it) }
                ?: throw SandboxFailure(ToolError.Unavailable("no command id"), VERCEL_BAD_REPLY)
            val waitMs = (timeoutSeconds + 30) * 1000
            val finished = objectOf(
                checked(
                    exchange(
                        "GET", url("/v2/sandboxes/sessions/$sessionId/cmd/$cmdId", "wait" to "true"), null, null,
                        readTimeoutMs = waitMs,
                    ),
                ),
            )
            val exitCode = finished["command"]?.jsonObject?.get("exitCode")?.jsonPrimitive?.intOrNull ?: -1
            val logs = checked(
                exchange("GET", url("/v2/sandboxes/sessions/$sessionId/cmd/$cmdId/logs"), null, null, readTimeoutMs = waitMs),
            )
            ExecResult(exitCode = exitCode, output = outputOf(logs))
        }

    override suspend fun writeFile(info: SandboxInfo, path: String, bytes: ByteArray): Unit =
        withContext(Dispatchers.IO) {
            val sessionId = currentSession(info)
            val slash = path.lastIndexOf('/')
            val dir = when {
                slash < 0 -> ""
                slash == 0 -> "/"
                else -> path.substring(0, slash)
            }
            val name = path.substring(slash + 1)
            if (name.isEmpty() || name.length > TAR_NAME_CHARS || name == "." || name == "..") {
                throw SandboxFailure(
                    ToolError.InvalidArguments("path"),
                    "Vercel Sandbox needs a file name of 1 to $TAR_NAME_CHARS characters at the end of the path.",
                )
            }
            val headers = mutableMapOf<String, String>()
            if (dir.isNotEmpty()) {
                if (dir != "/") {
                    val body = buildJsonObject { put("path", dir) }.toString()
                    checked(exchange("POST", url("/v2/sandboxes/sessions/$sessionId/fs/mkdir"), body.toByteArray(Charsets.UTF_8), JSON_TYPE))
                }
                headers["x-cwd"] = dir
            }
            checked(
                exchange(
                    "POST", url("/v2/sandboxes/sessions/$sessionId/fs/write"), tarGzOf(name, bytes),
                    "application/gzip", headers = headers,
                ),
            )
            Unit
        }

    override suspend fun readFile(info: SandboxInfo, path: String): ByteArray = withContext(Dispatchers.IO) {
        val sessionId = currentSession(info)
        val body = buildJsonObject { put("path", path) }.toString()
        checked(
            exchange(
                "POST", url("/v2/sandboxes/sessions/$sessionId/fs/read"), body.toByteArray(Charsets.UTF_8), JSON_TYPE,
                maxBytes = MAX_FILE_BYTES,
            ),
        )
    }

    /** The current session of a named sandbox. A stopped one has none, and that is said plainly. */
    private suspend fun currentSession(info: SandboxInfo): String =
        sessionIdOf(info.id)?.let { safe(it) }
            ?: throw SandboxFailure(
                ToolError.Unavailable("sandbox stopped"),
                "The Vercel sandbox is stopped. Try again; it will start.",
            )

    /** The session a named sandbox points at, or null when it has none (stopped). */
    private fun sessionIdOf(name: String): String? {
        val reply = objectOf(checked(exchange("GET", url("/v2/sandboxes/${safe(name)}", "projectId" to projectId), null, null)))
        return reply["sandbox"]?.jsonObject?.get("currentSessionId")?.jsonPrimitive?.contentOrNull
            ?.takeIf { SAFE_ID.matches(it) }
    }

    private fun statusOf(sessionId: String): String? {
        val reply = objectOf(checked(exchange("GET", url("/v2/sandboxes/sessions/${safe(sessionId)}"), null, null)))
        return reply["session"]?.jsonObject?.get("status")?.jsonPrimitive?.contentOrNull
    }

    /** Vercel's session status as the words [SandboxSession] waits on. Transitional states keep the loop polling. */
    private fun stateOf(status: String?): String = when (status) {
        "running" -> "started"
        "stopped", "aborted", null -> "stopped"
        "failed" -> "error"
        "pending" -> "pending"
        else -> "stopping"
    }

    /** The command's output: every stdout and stderr chunk in order. An error line from the stream fails the call. */
    private fun outputOf(logs: ByteArray): String {
        val out = StringBuilder()
        for (line in String(logs, Charsets.UTF_8).lineSequence()) {
            if (line.isBlank()) continue
            val entry = runCatching { Json.parseToJsonElement(line).jsonObject }
                .getOrElse { throw SandboxFailure(ToolError.Unavailable("unreadable log"), VERCEL_BAD_REPLY) }
            val data = entry["data"]
            when {
                data is JsonPrimitive -> out.append(data.content)
                else -> throw SandboxFailure(
                    ToolError.Unavailable("log stream closed"),
                    "The Vercel sandbox closed the output stream before the command finished.",
                )
            }
        }
        return out.toString()
    }

    private fun safe(id: String): String {
        if (!SAFE_ID.matches(id)) throw SandboxFailure(ToolError.Unavailable("bad id"), VERCEL_BAD_REPLY)
        return id
    }

    private fun url(path: String, vararg query: Pair<String, String>): String {
        val pairs = buildList {
            addAll(query)
            if (teamId.isNotBlank()) add("teamId" to teamId)
        }
        val qs = pairs.joinToString("&") { (k, v) -> "${encode(k)}=${encode(v)}" }
        return VERCEL_API + path + if (qs.isEmpty()) "" else "?$qs"
    }

    /** The body of a 2xx reply, or the failure sentence for anything else. */
    private fun checked(reply: Reply): ByteArray {
        if (reply.ok) return reply.body
        throw failure(reply.status, reply.body)
    }

    private fun failure(status: Int, body: ByteArray): SandboxFailure {
        val raw = String(body, Charsets.UTF_8)
        return when {
            status == 401 || status == 403 -> SandboxFailure(
                ToolError.PermissionDenied("Vercel refused the token"),
                "Vercel refused the token or the project. Check the Vercel access token and project in Settings.",
            )
            status == 402 || BILLING_WORDS.containsMatchIn(raw) && status != 404 -> SandboxFailure(
                ToolError.Unavailable("out of credit"),
                "Vercel refused: out of credit. Check the payment method on your Vercel account.",
            )
            status == 429 -> SandboxFailure(
                ToolError.Unavailable("rate limit"),
                "Vercel refused: the concurrency or rate limit is reached. Wait a minute and try again.",
            )
            status == 404 || status == 410 -> SandboxFailure(
                ToolError.NotFound("no such sandbox or file"),
                "Vercel has no such sandbox, command or file.",
            )
            else -> {
                val detail = scrubbed(body)
                SandboxFailure(
                    ToolError.Unavailable("HTTP $status"),
                    "Vercel returned HTTP $status. Try again later." + if (detail.isBlank()) "" else " Vercel said: $detail",
                )
            }
        }
    }

    /** Vercel's error message, or the start of its body, with the token removed and whitespace collapsed. */
    private fun scrubbed(body: ByteArray): String {
        val raw = String(body, Charsets.UTF_8)
        val message = runCatching {
            Json.parseToJsonElement(raw).jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
        }.getOrNull() ?: raw
        val cleaned = if (token.isBlank()) message else message.replace(token, "[token]")
        return cleaned.replace(Regex("\\s+"), " ").trim().take(ERROR_CHARS)
    }

    /**
     * One HTTPS exchange. Redirects are not followed, so the Bearer header never reaches another host.
     * The token is added here and nowhere else.
     */
    private fun exchange(
        method: String,
        url: String,
        body: ByteArray?,
        contentType: String?,
        headers: Map<String, String> = emptyMap(),
        readTimeoutMs: Int = READ_TIMEOUT_MS,
        maxBytes: Int = MAX_JSON_BYTES,
    ): Reply {
        if (!url.startsWith("https://")) {
            throw SandboxFailure(ToolError.Unavailable("not https"), "The Vercel address must start with https://.")
        }
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = readTimeoutMs
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Accept", JSON_TYPE)
            headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }
            if (body != null) {
                connection.doOutput = true
                if (contentType != null) connection.setRequestProperty("Content-Type", contentType)
                connection.outputStream.use { it.write(body) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            return Reply(status, stream?.use { readBounded(it, maxBytes) } ?: ByteArray(0))
        } finally {
            connection.disconnect()
        }
    }

    private fun objectOf(body: ByteArray): JsonObject = runCatching {
        Json.parseToJsonElement(String(body, Charsets.UTF_8)).jsonObject
    }.getOrElse { throw SandboxFailure(ToolError.Unavailable("unreadable reply"), VERCEL_BAD_REPLY) }
}

private fun randomHex(): String {
    val bytes = ByteArray(6)
    SecureRandom().nextBytes(bytes)
    return bytes.joinToString("") { "%02x".format(it) }
}

/**
 * A gzipped tar holding one file, for fs/write. The JDK has no tar writer, so the ustar header is
 * built by hand: name, mode, size, checksum, then the data padded to 512 bytes and two zero blocks.
 * The name must fit the 100-byte field, which the caller checks.
 */
internal fun tarGzOf(name: String, content: ByteArray): ByteArray {
    val header = ByteArray(TAR_BLOCK)
    fun put(offset: Int, text: String, width: Int) {
        val bytes = text.toByteArray(Charsets.US_ASCII)
        System.arraycopy(bytes, 0, header, offset, minOf(bytes.size, width))
    }
    fun octal(value: Long, digits: Int): String = value.toString(8).padStart(digits, '0')
    put(0, name, TAR_NAME_CHARS)
    put(100, "0000644\u0000", 8)
    put(108, "0000000\u0000", 8)
    put(116, "0000000\u0000", 8)
    put(124, octal(content.size.toLong(), 11) + "\u0000", 12)
    put(136, octal(0, 11) + "\u0000", 12)
    put(148, "        ", 8)
    header[156] = '0'.code.toByte()
    put(257, "ustar\u0000", 6)
    put(263, "00", 2)
    var sum = 0L
    for (b in header) sum += b.toInt() and 0xff
    put(148, octal(sum, 6) + "\u0000 ", 8)

    val tar = ByteArrayOutputStream()
    tar.write(header)
    tar.write(content)
    val padding = (TAR_BLOCK - content.size % TAR_BLOCK) % TAR_BLOCK
    tar.write(ByteArray(padding + 2 * TAR_BLOCK))
    val gz = ByteArrayOutputStream()
    GZIPOutputStream(gz).use { it.write(tar.toByteArray()) }
    return gz.toByteArray()
}
