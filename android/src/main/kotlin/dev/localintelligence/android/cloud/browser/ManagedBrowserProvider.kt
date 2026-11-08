package dev.localintelligence.android.cloud.browser

import dev.localintelligence.android.cloud.ExtrasClient
import dev.localintelligence.core.settings.SettingsReader
import dev.localintelligence.core.tool.ToolError
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

// ===========================================================================
// Managed cloud browser provider over the Extras Worker (`/v1/browser/{start,status,result}`).
//
// Used when [CredentialSource.managed] is true for `browser` (Extras is active, the backend
// enabled `browser` in the grant, and the user has not set their own Browser Use API key).
// Each task runs in a Durable Object on Cloudflare Browser Rendering with Stagehand and is
// billed against the customer's monthly Extras browser allowance and OpenRouter chat key.
// ===========================================================================

private const val BAD_REPLY = "Extras sent a browser reply this app cannot read. Try again."

/** The Extras backend implementation of [CloudBrowserProvider]. */
internal class ManagedBrowserProvider(private val reader: SettingsReader) : CloudBrowserProvider {

    override suspend fun start(instruction: String, options: BrowserStartOptions): String {
        val json = call(
            path = "/v1/browser/start",
            body = buildJsonObject {
                put("instruction", instruction)
            },
        )
        return (json["id"] as? JsonPrimitive)?.contentOrNull?.takeIf { BROWSER_TASK_ID.matches(it) }
            ?: throw BrowserFailure(ToolError.Unavailable("no task id"), BAD_REPLY)
    }

    override suspend fun status(taskId: String): BrowserTaskStatus {
        val json = call(
            path = "/v1/browser/status",
            body = buildJsonObject {
                put("id", taskId)
            },
        )
        val state = (json["status"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        return BrowserTaskStatus(taskId, state)
    }

    override suspend fun result(taskId: String): BrowserTaskResult {
        val json = call(
            path = "/v1/browser/result",
            body = buildJsonObject {
                put("id", taskId)
            },
        )
        return BrowserTaskResult(
            state = (json["status"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            text = (json["result"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() },
            error = (json["error"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() },
        )
    }

    override suspend fun liveUrl(taskId: String): String? = null

    private suspend fun call(path: String, body: JsonObject): JsonObject = try {
        ExtrasClient.call(reader, path, body)
    } catch (e: ExtrasClient.ExtrasException) {
        throw when (e.status) {
            400 -> BrowserFailure(
                ToolError.InvalidArguments(e.code ?: "invalid_request"),
                "Extras could not start the browser task because the instruction was not valid.",
            )
            401, 402 -> BrowserFailure(
                ToolError.PermissionDenied(e.code ?: "not_active"),
                e.message ?: "Extras is not active on this phone.",
            )
            404 -> BrowserFailure(
                ToolError.NotFound(e.code ?: "not_found"),
                "Extras has no such browser task.",
            )
            409 -> BrowserFailure(
                ToolError.Unavailable(e.code ?: "task_running"),
                "A browser task is already running. Wait for it to finish before starting another.",
            )
            else -> BrowserFailure(
                ToolError.Unavailable(e.code ?: "HTTP ${e.status}"),
                e.message ?: BAD_REPLY,
            )
        }
    }
}
