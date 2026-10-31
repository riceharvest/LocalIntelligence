package dev.localintelligence.android.cloud.sandbox

import dev.localintelligence.core.tool.ToolError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

// ===========================================================================
// Daytona, the one sandbox provider so far.
//
// Two hosts, and the key goes to both:
//   - The control plane, at the API base (default https://app.daytona.io/api):
//       POST   {api}/sandbox                  create (cpu 1, memory 1 GB, disk 3 GB, autoStopInterval)
//       GET    {api}/sandbox/{id}             read state and the toolbox address
//       POST   {api}/sandbox/{id}/start       start a stopped sandbox
//       POST   {api}/sandbox/{id}/stop        stop it (files kept)
//   - The toolbox, at the address the sandbox reports as toolboxProxyUrl, plus "/{id}":
//       POST   {toolbox}/process/execute      {command, timeout} -> {exitCode, result}
//       POST   {toolbox}/files/upload-v2?path=...   raw body, application/octet-stream
//       GET    {toolbox}/files/download?path=...
//
// Every request sends "Authorization: Bearer <key>". An API key already names the organization,
// so no X-Daytona-Organization-ID header is sent (that header is for JWT logins only).
//
// Rules this file keeps: the key goes only to https addresses, and redirects are not followed,
// so the Bearer header cannot follow a response to another host. The key is removed from any
// error text before it is shown. The toolbox address comes from the service's reply, so it is
// checked to be https before use.
// ===========================================================================

/** The id the Choice setting stores for this provider. */
internal const val DAYTONA_ID: String = "daytona"

/** Daytona's cloud API. Used unless the user sets another address in Settings. */
internal const val DAYTONA_DEFAULT_API: String = "https://app.daytona.io/api"

/** Resources of Daytona's default sandbox (1 vCPU, 1 GB RAM, 3 GiB disk), the smallest the sandbox tools ask for. */
private const val SANDBOX_CPU = 1
private const val SANDBOX_MEMORY_GB = 1
private const val SANDBOX_DISK_GB = 3

private const val CONNECT_TIMEOUT_MS = 20_000
private const val READ_TIMEOUT_MS = 30_000

/** A JSON reply is a few kilobytes; a command's output is the largest thing that comes back in one. */
private const val MAX_JSON_BYTES = 4 * 1024 * 1024

/** The largest file the read tool will download. */
internal const val MAX_FILE_BYTES = 1024 * 1024

/** Sandbox ids are short URL-safe strings. Anything else in a reply is rejected before it is used in a path. */
private val SANDBOX_ID = Regex("[A-Za-z0-9_-]{1,128}")

private const val BAD_REPLY = "Daytona sent a reply this app cannot read. Try again."

/**
 * The Daytona implementation of [SandboxProvider]. Built per call from the live settings, so a
 * key or address changed in Settings applies to the next tool call.
 */
internal class DaytonaProvider(
    apiBase: String,
    private val apiKey: String,
) : SandboxProvider {
    private val apiBase = apiBase.trimEnd('/')

    override suspend fun create(idleMinutes: Int): SandboxInfo = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("cpu", SANDBOX_CPU)
            put("memory", SANDBOX_MEMORY_GB)
            put("disk", SANDBOX_DISK_GB)
            put("autoStopInterval", idleMinutes)
            put("public", false)
        }.toString()
        infoOf(checked(exchange("POST", "$apiBase/sandbox", body.toByteArray(Charsets.UTF_8), JSON)))
    }

    override suspend fun info(id: String): SandboxInfo = withContext(Dispatchers.IO) {
        infoOf(checked(exchange("GET", "$apiBase/sandbox/$id", null, null)))
    }

    override suspend fun start(id: String): Unit = withContext(Dispatchers.IO) {
        checked(exchange("POST", "$apiBase/sandbox/$id/start", ByteArray(0), null))
        Unit
    }

    override suspend fun stop(id: String): Unit = withContext(Dispatchers.IO) {
        checked(exchange("POST", "$apiBase/sandbox/$id/stop", ByteArray(0), null))
        Unit
    }

    override suspend fun exec(info: SandboxInfo, command: String, timeoutSeconds: Int): ExecResult =
        withContext(Dispatchers.IO) {
            val body = buildJsonObject {
                put("command", command)
                put("timeout", timeoutSeconds)
            }.toString()
            // The command's own timeout is the service's; the socket waits a little longer than that.
            val reply = checked(
                exchange(
                    "POST", "${toolboxOf(info)}/process/execute", body.toByteArray(Charsets.UTF_8), JSON,
                    readTimeoutMs = (timeoutSeconds + 30) * 1000,
                ),
            )
            val json = objectOf(reply)
            ExecResult(
                exitCode = json["exitCode"]?.jsonPrimitive?.intOrNull ?: -1,
                output = json["result"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            )
        }

    override suspend fun upload(info: SandboxInfo, path: String, bytes: ByteArray): Unit =
        withContext(Dispatchers.IO) {
            checked(
                exchange(
                    "POST", "${toolboxOf(info)}/files/upload-v2?path=${encode(path)}", bytes,
                    "application/octet-stream",
                ),
            )
            Unit
        }

    override suspend fun download(info: SandboxInfo, path: String): ByteArray = withContext(Dispatchers.IO) {
        checked(
            exchange(
                "GET", "${toolboxOf(info)}/files/download?path=${encode(path)}", null, null,
                maxBytes = MAX_FILE_BYTES,
            ),
        )
    }

    private fun toolboxOf(info: SandboxInfo): String =
        info.toolbox ?: throw SandboxFailure(
            ToolError.Unavailable("no toolbox address"),
            "Daytona gave no address for the sandbox's toolbox. Try again.",
        )

    private fun infoOf(body: ByteArray): SandboxInfo {
        val json = objectOf(body)
        val id = json["id"]?.jsonPrimitive?.contentOrNull?.takeIf { SANDBOX_ID.matches(it) }
            ?: throw SandboxFailure(ToolError.Unavailable("no id"), BAD_REPLY)
        val state = json["state"]?.jsonPrimitive?.contentOrNull.orEmpty()
        // The address is the service's to give, but the key is about to be sent to it, so only https counts.
        val toolbox = json["toolboxProxyUrl"]?.jsonPrimitive?.contentOrNull
            ?.trimEnd('/')
            ?.takeIf { it.startsWith("https://") }
            ?.let { "$it/$id" }
        return SandboxInfo(id = id, state = state, toolbox = toolbox)
    }

    /** A reply's body as a JSON object, or the one plain sentence about it being unreadable. */
    private fun objectOf(body: ByteArray): JsonObject = runCatching {
        Json.parseToJsonElement(String(body, Charsets.UTF_8)).jsonObject
    }.getOrElse { throw SandboxFailure(ToolError.Unavailable("unreadable reply"), BAD_REPLY) }

    /** The body of a 2xx reply, or the failure sentence for anything else. */
    private fun checked(reply: Reply): ByteArray {
        if (reply.ok) return reply.body
        throw failure(reply.status, reply.body)
    }

    /** One short line per HTTP failure. The sentence is fixed; Daytona's own words are appended, with the key removed. */
    private fun failure(status: Int, body: ByteArray): SandboxFailure {
        val mapped: Pair<ToolError, String> = when (status) {
            401, 403 -> ToolError.PermissionDenied("Daytona refused the key") to
                "Daytona refused the key. Check the Daytona API key in Settings."
            402 -> ToolError.Unavailable("out of credit") to
                "Daytona refused: out of credit. Add credit in your Daytona account."
            404 -> ToolError.NotFound("no such sandbox or file") to
                "Daytona has no such sandbox or file."
            429 -> ToolError.Unavailable("rate or quota limit") to
                "Daytona refused: quota or rate limit reached. Try again later."
            else -> ToolError.Unavailable("HTTP $status") to
                "Daytona returned HTTP $status. Try again later."
        }
        val (error, sentence) = mapped
        val detail = scrubbed(body)
        return SandboxFailure(error, if (detail.isBlank()) sentence else "$sentence Daytona said: $detail")
    }

    /** Daytona's message, or the start of its body, with the key removed and whitespace collapsed. */
    private fun scrubbed(body: ByteArray): String {
        val raw = String(body, Charsets.UTF_8)
        val message = runCatching {
            Json.parseToJsonElement(raw).jsonObject["message"]?.jsonPrimitive?.contentOrNull
        }.getOrNull() ?: raw
        val cleaned = if (apiKey.isBlank()) message else message.replace(apiKey, "[key]")
        return cleaned.replace(Regex("\\s+"), " ").trim().take(DETAIL_CHARS)
    }

    /**
     * One HTTPS exchange. Redirects are not followed: a 3xx comes back as a failure, because
     * following it would send the Bearer header to whatever host it names.
     */
    private fun exchange(
        method: String,
        url: String,
        body: ByteArray?,
        contentType: String?,
        readTimeoutMs: Int = READ_TIMEOUT_MS,
        maxBytes: Int = MAX_JSON_BYTES,
    ): Reply {
        if (!url.startsWith("https://")) {
            throw SandboxFailure(
                ToolError.Unavailable("not https"),
                "The Daytona address in Settings must start with https://, so the key is not sent in clear text.",
            )
        }
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = readTimeoutMs
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.setRequestProperty("Accept", JSON)
            if (body != null) {
                connection.doOutput = true
                if (contentType != null) connection.setRequestProperty("Content-Type", contentType)
                connection.outputStream.use { it.write(body) }
            }
            val status = connection.responseCode
            val stream: InputStream? = if (status in 200..299) connection.inputStream else connection.errorStream
            return Reply(status, stream?.use { readBounded(it, maxBytes) } ?: ByteArray(0))
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val JSON = "application/json"
        const val DETAIL_CHARS = 160
    }
}

/** A reply that came back, whatever its status. */
private class Reply(val status: Int, val body: ByteArray) {
    val ok: Boolean get() = status in 200..299
}

/**
 * Reads at most [max] bytes. A larger reply is a failure rather than a cut: a cut file or
 * a cut JSON reply would look complete to the model.
 */
private fun readBounded(input: InputStream, max: Int): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(16 * 1024)
    while (true) {
        val n = input.read(buffer)
        if (n < 0) break
        if (out.size() + n > max) {
            throw SandboxFailure(
                ToolError.Unavailable("reply too large"),
                "Daytona sent more than ${max / 1024} KB, which is too large to use here.",
            )
        }
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}

/**
 * A path in the URL's query string. Spaces become %20, not "+", because a query parser on
 * the service side may read "+" as a space or as a plus sign.
 */
private fun encode(path: String): String =
    URLEncoder.encode(path, Charsets.UTF_8.name()).replace("+", "%20")
