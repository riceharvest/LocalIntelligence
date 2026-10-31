package dev.localintelligence.android.cloud.browser

import dev.localintelligence.core.tool.ToolError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

// ===========================================================================
// Browser Use Cloud, API v4. The one cloud browser provider so far.
//
// Requests, all under https://api.browser-use.com/api/v4 (docs:
// https://docs.browser-use.com/cloud/api-v4-overview):
//   POST {api}/runs                  create a task. Body: task, maxCostUsd, and browserSettings.profileId
//                                    only when a saved profile is used. The model is not sent, so the
//                                    service default applies (https://docs.browser-use.com/cloud/api-v4/runs/create-run).
//   GET  {api}/runs/{id}/status      the state only, the cheap poll (https://docs.browser-use.com/cloud/api-v4/runs/get-run-status)
//   GET  {api}/runs/{id}             the full summary, read once the task is finished (https://docs.browser-use.com/cloud/api-v4/runs/get-run)
//   GET  {api}/runs/{id}/events      the ordered event stream. browser.ready and browser.reattached carry
//                                    data.live_view_url (https://docs.browser-use.com/cloud/agent/observability)
//
// Auth is the X-Browser-Use-API-Key header, with no "Bearer" prefix. The key goes only to the one
// https host above, and redirects are not followed, so the header cannot follow a reply elsewhere.
// The address is fixed rather than a setting: a user who sets another host would send the key there.
//
// The V4 API has no field for a start URL or a step cap. The start URL is written into the task
// text by the tool, and the spend cap (maxCostUsd) is the documented bound on a run.
// ===========================================================================

/** The id the Provider setting stores for this service. */
internal const val BROWSER_USE_ID: String = "browser_use"

/** The API root every request is built from. */
private const val BROWSER_USE_API: String = "https://api.browser-use.com/api/v4"

private const val KEY_HEADER = "X-Browser-Use-API-Key"
private const val JSON = "application/json"
private const val CONNECT_TIMEOUT_MS = 20_000
private const val READ_TIMEOUT_MS = 30_000

/** A JSON reply is a few kilobytes. A larger one is a failure, not a cut. */
private const val MAX_JSON_BYTES = 4 * 1024 * 1024

/** Events are read 200 at a time; this many pages is the most one live-view lookup will read. */
private const val EVENT_PAGE_LIMIT = 200
private const val MAX_EVENT_PAGES = 5

/** Task ids are UUIDs as the API issues them. Anything else is rejected before it reaches a path. */
internal val BROWSER_TASK_ID: Regex =
    Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

private const val BAD_REPLY = "Browser Use sent a reply this app cannot read. Try again."

/** The Browser Use implementation of [CloudBrowserProvider]. Built per call from the live key. */
internal class BrowserUseProvider(private val apiKey: String) : CloudBrowserProvider {

    override suspend fun start(instruction: String, options: BrowserStartOptions): String =
        withContext(Dispatchers.IO) {
            val body = buildJsonObject {
                put("task", instruction)
                put("maxCostUsd", options.maxSpendUsd)
                options.profileId?.let { profile ->
                    putJsonObject("browserSettings") { put("profileId", profile) }
                }
            }.toString()
            val json = objectOf(checked(exchange("POST", "$BROWSER_USE_API/runs", body.toByteArray(Charsets.UTF_8))))
            json["id"]?.jsonPrimitive?.contentOrNull?.takeIf { BROWSER_TASK_ID.matches(it) }
                ?: throw BrowserFailure(ToolError.Unavailable("no task id"), BAD_REPLY)
        }

    override suspend fun status(taskId: String): BrowserTaskStatus = withContext(Dispatchers.IO) {
        val json = objectOf(checked(exchange("GET", "$BROWSER_USE_API/runs/$taskId/status", null)))
        BrowserTaskStatus(taskId, json["status"]?.jsonPrimitive?.contentOrNull.orEmpty())
    }

    override suspend fun result(taskId: String): BrowserTaskResult = withContext(Dispatchers.IO) {
        val json = objectOf(checked(exchange("GET", "$BROWSER_USE_API/runs/$taskId", null)))
        // "result" is the agent's text. "output" is set only when the task asked for a structured
        // answer, so its JSON is shown as text when there is no plain result.
        val text = json["result"]?.jsonPrimitive?.contentOrNull
            ?: json["output"]?.takeUnless { it is JsonNull }?.toString()
        BrowserTaskResult(
            state = json["status"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            text = text?.takeIf { it.isNotBlank() },
            error = json["error"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
        )
    }

    override suspend fun liveUrl(taskId: String): String? = withContext(Dispatchers.IO) {
        var after = 0L
        var live: String? = null
        for (page in 0 until MAX_EVENT_PAGES) {
            val json = objectOf(
                checked(exchange("GET", "$BROWSER_USE_API/runs/$taskId/events?after=$after&limit=$EVENT_PAGE_LIMIT", null)),
            )
            json["events"]?.jsonArray?.forEach { event ->
                val fields = event.jsonObject
                val type = fields["type"]?.jsonPrimitive?.contentOrNull
                if (type == "browser.ready" || type == "browser.reattached") {
                    fields["data"]?.jsonObject?.get("live_view_url")?.jsonPrimitive?.contentOrNull
                        ?.takeIf { it.startsWith("https://") }
                        ?.let { live = it }
                }
            }
            val more = json["hasMore"]?.jsonPrimitive?.contentOrNull == "true"
            val next = json["nextAfter"]?.jsonPrimitive?.longOrNull
            if (!more || next == null) break
            after = next
        }
        live
    }

    /** A reply's body as a JSON object, or the one plain sentence about it being unreadable. */
    private fun objectOf(body: ByteArray): JsonObject = runCatching {
        Json.parseToJsonElement(String(body, Charsets.UTF_8)).jsonObject
    }.getOrElse { throw BrowserFailure(ToolError.Unavailable("unreadable reply"), BAD_REPLY) }

    /** The body of a 2xx reply, or the failure for anything else. */
    private fun checked(reply: Reply): ByteArray {
        if (reply.ok) return reply.body
        throw failure(reply.status, reply.body)
    }

    /**
     * One plain sentence per failure. 402, 403, 404, 409, 422 and 429 are the create-run reference's
     * own list. 401 is not in that list; it is mapped as a refused key, as the auth header implies.
     */
    private fun failure(status: Int, body: ByteArray): BrowserFailure = when (status) {
        401 -> BrowserFailure(
            ToolError.PermissionDenied("key refused"),
            "Browser Use refused the key. Check the Browser Use API key in Settings.",
        )
        402 -> BrowserFailure(
            ToolError.Unavailable("no credit or spend cap"),
            "Browser Use refused: no credit is left, or this key's monthly spend cap is reached. " +
                "Check the balance in Browser Use Cloud.",
        )
        403 -> BrowserFailure(
            ToolError.Unavailable("zero data retention"),
            "Browser Use refused: Zero Data Retention is on for this project, and its V4 API is not available.",
        )
        404 -> BrowserFailure(ToolError.NotFound("no such task"), "Browser Use has no such task.")
        409 -> BrowserFailure(
            ToolError.Unavailable("session busy"),
            "Browser Use says this session is busy. Try again in a minute.",
        )
        422 -> BrowserFailure(
            ToolError.InvalidArguments("rejected by Browser Use"),
            "Browser Use rejected the request" + scrubbed(body).let { if (it.isBlank()) "." else ": $it" },
        )
        429 -> BrowserFailure(
            ToolError.Unavailable("rate limit or concurrency"),
            "Browser Use is at its browser limit or is rate limiting. Wait a minute and try again.",
        )
        else -> BrowserFailure(
            ToolError.Unavailable("HTTP $status"),
            "Browser Use returned HTTP $status. Try again later.",
        )
    }

    /** The reply text with the key removed and whitespace collapsed, cut to a short length. */
    private fun scrubbed(body: ByteArray): String {
        val raw = String(body, Charsets.UTF_8)
        val cleaned = if (apiKey.isBlank()) raw else raw.replace(apiKey, "[key]")
        return cleaned.replace(Regex("\\s+"), " ").trim().take(DETAIL_CHARS)
    }

    /**
     * One HTTPS exchange. Redirects are not followed: a 3xx comes back as a failure, because
     * following it would send the key to whatever host it names.
     */
    private fun exchange(method: String, url: String, body: ByteArray?): Reply {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = false
            connection.setRequestProperty(KEY_HEADER, apiKey)
            connection.setRequestProperty("Accept", JSON)
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", JSON)
                connection.outputStream.use { it.write(body) }
            }
            val status = connection.responseCode
            val stream: InputStream? = if (status in 200..299) connection.inputStream else connection.errorStream
            return Reply(status, stream?.use { readBounded(it, MAX_JSON_BYTES) } ?: ByteArray(0))
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val DETAIL_CHARS = 160
    }
}

/** A reply that came back, whatever its status. */
private class Reply(val status: Int, val body: ByteArray) {
    val ok: Boolean get() = status in 200..299
}

/**
 * Reads at most [max] bytes. A larger reply is a failure rather than a cut, because a cut JSON
 * reply would look complete to the parser and fail somewhere less clear.
 */
private fun readBounded(input: InputStream, max: Int): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(16 * 1024)
    while (true) {
        val n = input.read(buffer)
        if (n < 0) break
        if (out.size() + n > max) {
            throw BrowserFailure(
                ToolError.Unavailable("reply too large"),
                "Browser Use sent more than ${max / 1024} KB, which is too large to use here.",
            )
        }
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}
