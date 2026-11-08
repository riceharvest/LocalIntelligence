package dev.localintelligence.android.cloud.sandbox

import android.util.Base64
import dev.localintelligence.android.cloud.ExtrasClient
import dev.localintelligence.core.settings.SettingsReader
import dev.localintelligence.core.tool.ToolError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.IOException

// ===========================================================================
// Managed code sandbox on Cloudflare Containers via Extras (/v1/sandbox/*).
//
// Used in Simple mode (and Technical mode when no own credentials are set) when
// the Extras subscription is active and has granted the "sandbox" service.
//
// Endpoints mirror SandboxProvider:
//   POST /v1/sandbox/create
//   POST /v1/sandbox/info
//   POST /v1/sandbox/start
//   POST /v1/sandbox/stop
//   POST /v1/sandbox/exec
//   POST /v1/sandbox/write
//   POST /v1/sandbox/read
// Files are base64-encoded with a 1 MB size cap. Execution wall-clock time is
// metered in minutes on the backend.
// ===========================================================================

/**
 * The managed implementation of [SandboxProvider] calling the Extras backend.
 */
class ManagedSandboxProvider(
    private val reader: SettingsReader,
) : SandboxProvider {

    override suspend fun create(idleMinutes: Int): SandboxInfo = withContext(Dispatchers.IO) {
        wrapSandboxCall {
            val reply = ExtrasClient.call(
                reader = reader,
                path = "/v1/sandbox/create",
                body = buildJsonObject {
                    put("idleMinutes", idleMinutes)
                },
            )
            val id = reply["id"]?.jsonPrimitive?.contentOrNull ?: "managed-sandbox"
            val state = reply["state"]?.jsonPrimitive?.contentOrNull ?: "started"
            val toolbox = reply["toolbox"]?.jsonPrimitive?.contentOrNull
            SandboxInfo(id = id, state = state, toolbox = toolbox)
        }
    }

    override suspend fun info(id: String): SandboxInfo = withContext(Dispatchers.IO) {
        wrapSandboxCall {
            val reply = ExtrasClient.call(
                reader = reader,
                path = "/v1/sandbox/info",
                body = buildJsonObject {
                    put("id", id)
                },
            )
            val replyId = reply["id"]?.jsonPrimitive?.contentOrNull ?: id
            val state = reply["state"]?.jsonPrimitive?.contentOrNull ?: "started"
            val toolbox = reply["toolbox"]?.jsonPrimitive?.contentOrNull
            SandboxInfo(id = replyId, state = state, toolbox = toolbox)
        }
    }

    override suspend fun start(id: String): Unit = withContext(Dispatchers.IO) {
        wrapSandboxCall {
            ExtrasClient.call(
                reader = reader,
                path = "/v1/sandbox/start",
                body = buildJsonObject {
                    put("id", id)
                },
            )
            Unit
        }
    }

    override suspend fun stop(id: String): Unit = withContext(Dispatchers.IO) {
        wrapSandboxCall {
            ExtrasClient.call(
                reader = reader,
                path = "/v1/sandbox/stop",
                body = buildJsonObject {
                    put("id", id)
                },
            )
            Unit
        }
    }

    override suspend fun exec(info: SandboxInfo, command: String, timeoutSeconds: Int): ExecResult =
        withContext(Dispatchers.IO) {
            wrapSandboxCall {
                val reply = ExtrasClient.call(
                    reader = reader,
                    path = "/v1/sandbox/exec",
                    body = buildJsonObject {
                        put("id", info.id)
                        put("command", command)
                        put("timeoutSeconds", timeoutSeconds)
                    },
                )
                val exitCode = reply["exitCode"]?.jsonPrimitive?.intOrNull ?: -1
                val output = reply["output"]?.jsonPrimitive?.contentOrNull.orEmpty()
                ExecResult(exitCode = exitCode, output = output)
            }
        }

    override suspend fun writeFile(info: SandboxInfo, path: String, bytes: ByteArray): Unit =
        withContext(Dispatchers.IO) {
            if (bytes.size > MAX_FILE_BYTES) {
                throw SandboxFailure(
                    ToolError.InvalidArguments("file too large"),
                    "The file is larger than the 1 MB limit.",
                )
            }
            wrapSandboxCall {
                val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                ExtrasClient.call(
                    reader = reader,
                    path = "/v1/sandbox/write",
                    body = buildJsonObject {
                        put("id", info.id)
                        put("path", path)
                        put("content", base64)
                    },
                )
                Unit
            }
        }

    override suspend fun readFile(info: SandboxInfo, path: String): ByteArray = withContext(Dispatchers.IO) {
        wrapSandboxCall {
            val reply = ExtrasClient.call(
                reader = reader,
                path = "/v1/sandbox/read",
                body = buildJsonObject {
                    put("id", info.id)
                    put("path", path)
                },
            )
            val content = reply["content"]?.jsonPrimitive?.contentOrNull
                ?: throw SandboxFailure(ToolError.NotFound("file missing"), "The file could not be read.")
            val bytes = try {
                Base64.decode(content, Base64.DEFAULT)
            } catch (e: Exception) {
                throw SandboxFailure(ToolError.Unavailable("corrupt file data"), "Could not decode file data from Extras.")
            }
            if (bytes.size > MAX_FILE_BYTES) {
                throw SandboxFailure(
                    ToolError.InvalidArguments("file too large"),
                    "The file is larger than the 1 MB limit.",
                )
            }
            bytes
        }
    }

    private suspend inline fun <T> wrapSandboxCall(crossinline block: suspend () -> T): T {
        try {
            return block()
        } catch (e: SandboxFailure) {
            throw e
        } catch (e: ExtrasClient.ExtrasException) {
            val error = when {
                e.status == 401 || e.status == 402 -> ToolError.PermissionDenied("Extras inactive")
                e.status == 404 || e.code == "not_found" -> ToolError.NotFound("sandbox or file not found")
                e.status == 413 || e.code == "file_too_large" -> ToolError.InvalidArguments("file too large")
                e.status == 429 -> ToolError.Unavailable("over cap")
                else -> ToolError.Unavailable("Extras error ${e.status}")
            }
            val message = e.message?.takeIf { it.isNotBlank() }
                ?: "Extras could not complete this request. Try again later."
            throw SandboxFailure(error, message)
        } catch (e: IOException) {
            throw SandboxFailure(ToolError.Unavailable("network error"), "Could not reach Extras right now. Try again in a moment.")
        } catch (e: Exception) {
            throw SandboxFailure(ToolError.Unavailable("unexpected error"), e.message?.takeIf { it.isNotBlank() } ?: "An unexpected error occurred.")
        }
    }
}
