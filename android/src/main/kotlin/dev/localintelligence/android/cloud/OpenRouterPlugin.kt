package dev.localintelligence.android.cloud

import android.content.Context
import android.util.Base64
import dev.localintelligence.android.tools.requireCatalogueAgreement
import dev.localintelligence.core.model.ToolArgs
import dev.localintelligence.core.plugin.AgentPlugin
import dev.localintelligence.core.settings.SettingDef
import dev.localintelligence.core.settings.SettingSection
import dev.localintelligence.core.settings.SettingsReader
import dev.localintelligence.core.tool.AgentTool
import dev.localintelligence.core.tool.CancellationSignal
import dev.localintelligence.core.tool.ToolContext
import dev.localintelligence.core.tool.ToolDefinition
import dev.localintelligence.core.tool.ToolError
import dev.localintelligence.core.tool.ToolResult
import dev.localintelligence.core.tool.catalogue.V0ToolCatalogue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

// ===========================================================================
// Cloud image and video generation through the user's own OpenRouter key.
//
// Off by default, and doubly gated: the plugin must be switched on, and the
// tools are offered only while a key is stored. With neither, this file makes
// no network call at all.
//
// What leaves the phone: the prompt the model wrote, and the key in the
// Authorization header. Nothing else. The model cannot choose the model id or the
// wait limit; those are the user's settings, so a small model cannot pick an
// expensive one.
//
// The key goes only to https://openrouter.ai/api/v1. Every URL is built here
// from that base, and redirects are not followed, so the Bearer header cannot
// reach another host even if a response points there.
// ===========================================================================

/** The settings rows this plugin adds. Keys are prefixed with the plugin id. */
object OpenRouterSettings {
    const val PLUGIN_ID = "cloud.openrouter"

    /** Simple, so Simple mode can still reach it: without a key nothing is offered. */
    val API_KEY = SettingDef.Secret(
        "cloud.openrouter.api_key", "OpenRouter API key",
        "Your own key from openrouter.ai. Stored encrypted and never shown back. " +
            "Without one, the image and video tools are not offered and nothing is sent.",
        SettingSection.PLUGINS, simple = true,
    )
    val IMAGE_MODEL = SettingDef.Text(
        "cloud.openrouter.image_model", "Image model",
        "The OpenRouter model id used for images. Typed as is; check the Models page for ids " +
            "with image output.",
        SettingSection.PLUGINS, default = "google/gemini-2.5-flash-image", maxLength = 120,
        blankMeansDefault = true,
    )
    val VIDEO_MODEL = SettingDef.Text(
        "cloud.openrouter.video_model", "Video model",
        "The OpenRouter model id used for videos. The default is the example id in OpenRouter's " +
            "video docs, not checked against the live model list.",
        SettingSection.PLUGINS, default = "google/veo-3.1", maxLength = 120,
        blankMeansDefault = true,
    )
    val VIDEO_WAIT_SECONDS = SettingDef.IntValue(
        "cloud.openrouter.video_wait", "Video wait limit",
        "Seconds to wait for a video before the tool stops waiting. The job keeps running on OpenRouter.",
        SettingSection.PLUGINS, default = 300, min = 30, max = 900, step = 30,
    )

    val all: List<SettingDef<*>> = listOf(API_KEY, IMAGE_MODEL, VIDEO_MODEL, VIDEO_WAIT_SECONDS)
}

/**
 * Off by default. Offers `image.generate` and `video.generate` while a key is set.
 *
 * [reader] is the live settings store, not a snapshot: the key is read when a run
 * builds its tool list and again when a tool runs, so a key saved mid-session
 * takes effect at the next message.
 */
class OpenRouterPlugin(
    context: Context,
    private val reader: SettingsReader,
) : AgentPlugin {
    private val generatedDir = File(context.applicationContext.filesDir, GENERATED_DIR)

    override val id: String = OpenRouterSettings.PLUGIN_ID
    override val title: String = "Cloud images and video (OpenRouter)"
    override val summary: String =
        "Make images and short videos with your own OpenRouter key. The prompt is sent to openrouter.ai."
    override val enabledByDefault: Boolean = false
    override val settings: List<SettingDef<*>> = OpenRouterSettings.all

    private val built: List<AgentTool> by lazy {
        requireCatalogueAgreement(
            listOf(
                ImageGenerateTool(reader, generatedDir),
                VideoGenerateTool(reader, generatedDir),
            ),
        )
    }

    override fun tools(): List<AgentTool> =
        if (reader[OpenRouterSettings.API_KEY].isBlank()) emptyList() else built

    /**
     * Every tool this plugin ships, key or no key. For the catalogue check at
     * startup, which must see the whole shipped set and not the offered subset.
     */
    fun shippedTools(): List<AgentTool> = built
}

private const val GENERATED_DIR = "generated"

// ---------------------------------------------------------------------------
// image.generate
// ---------------------------------------------------------------------------

/**
 * `POST /api/v1/images` with `{model, prompt}`. The reply carries base64 images in
 * `data[].b64_json` with a `media_type`. The first one is saved.
 */
private class ImageGenerateTool(
    private val reader: SettingsReader,
    private val dir: File,
) : AgentTool {
    override val definition: ToolDefinition =
        checkNotNull(V0ToolCatalogue.byName("image.generate")) { "image.generate missing from catalogue" }

    override suspend fun execute(args: ToolArgs, context: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            try {
                val key = reader[OpenRouterSettings.API_KEY]
                if (key.isBlank()) return@withContext noKey("image.generate")
                val prompt = promptOf(args) ?: return@withContext badPrompt("image.generate")
                if (context.signal.isCancelled()) return@withContext cancelled("image.generate")

                val body = buildJsonObject {
                    put("model", reader[OpenRouterSettings.IMAGE_MODEL])
                    put("prompt", prompt)
                }.toString()
                val reply = exchange("POST", "$API/images", key, body)
                if (!reply.ok) return@withContext httpFailure("image.generate", reply, key)

                val image = firstImage(reply.body)
                    ?: return@withContext ToolResult(
                        success = false,
                        observation = "image.generate: OpenRouter returned no image for this prompt. " +
                            "Try different wording.",
                        error = ToolError.Unavailable("no image in the response"),
                    )
                val file = saveBytes(dir, image.bytes, image.extension)
                ToolResult(
                    success = true,
                    observation = "Image saved to ${file.absolutePath}.",
                    data = buildJsonObject { put("path", file.absolutePath) },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                unreachable("image.generate", e)
            } catch (e: Exception) {
                internal("image.generate", e)
            }
        }
}

private class DecodedImage(val bytes: ByteArray, val extension: String)

private fun firstImage(body: ByteArray): DecodedImage? {
    val first = Json.parseToJsonElement(String(body, Charsets.UTF_8))
        .jsonObject["data"]?.jsonArray?.firstOrNull()?.jsonObject ?: return null
    val b64 = first["b64_json"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
    val mediaType = first["media_type"]?.jsonPrimitive?.contentOrNull.orEmpty().lowercase()
    val extension = when (mediaType) {
        "image/jpeg", "image/jpg" -> "jpg"
        "image/webp" -> "webp"
        "image/svg+xml" -> "svg"
        else -> "png"
    }
    return DecodedImage(Base64.decode(b64, Base64.DEFAULT), extension)
}

// ---------------------------------------------------------------------------
// video.generate
// ---------------------------------------------------------------------------

/**
 * `POST /api/v1/videos` returns a job id. The job is polled every 5 s until it is
 * `completed` or `failed`, or until the user's wait limit passes. A finished video
 * is downloaded from `GET /api/v1/videos/{id}/content?index=0`.
 *
 * There is no status tool. A job that outlives the wait limit is reported by id,
 * and the app cannot fetch it later.
 */
private class VideoGenerateTool(
    private val reader: SettingsReader,
    private val dir: File,
) : AgentTool {
    override val definition: ToolDefinition =
        checkNotNull(V0ToolCatalogue.byName("video.generate")) { "video.generate missing from catalogue" }

    override suspend fun execute(args: ToolArgs, context: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            try {
                val key = reader[OpenRouterSettings.API_KEY]
                if (key.isBlank()) return@withContext noKey("video.generate")
                val prompt = promptOf(args) ?: return@withContext badPrompt("video.generate")
                if (context.signal.isCancelled()) return@withContext cancelled("video.generate")

                val body = buildJsonObject {
                    put("model", reader[OpenRouterSettings.VIDEO_MODEL])
                    put("prompt", prompt)
                }.toString()
                val submit = exchange("POST", "$API/videos", key, body)
                if (!submit.ok) return@withContext httpFailure("video.generate", submit, key)
                val jobId = Json.parseToJsonElement(String(submit.body, Charsets.UTF_8))
                    .jsonObject["id"]?.jsonPrimitive?.contentOrNull
                    ?.takeIf { JOB_ID.matches(it) }
                    ?: return@withContext ToolResult(
                        success = false,
                        observation = "video.generate: OpenRouter accepted the request but gave no job id.",
                        error = ToolError.Unavailable("no job id"),
                    )

                val limit = reader[OpenRouterSettings.VIDEO_WAIT_SECONDS]
                val startedAt = System.currentTimeMillis()
                while (true) {
                    if (!pause(POLL_SECONDS, context.signal)) {
                        return@withContext cancelled("video.generate", jobId)
                    }
                    val poll = exchange("GET", "$API/videos/$jobId", key, null)
                    if (!poll.ok) return@withContext httpFailure("video.generate", poll, key, jobId)
                    val job = Json.parseToJsonElement(String(poll.body, Charsets.UTF_8)).jsonObject
                    when (job["status"]?.jsonPrimitive?.contentOrNull) {
                        "completed" -> break
                        "failed" -> {
                            val reason = job["error"]?.jsonPrimitive?.contentOrNull.orEmpty()
                                .replace(key, "[key]").take(160)
                            return@withContext ToolResult(
                                success = false,
                                observation = "video.generate failed on OpenRouter" +
                                    if (reason.isNotBlank()) ": $reason" else ".",
                                error = ToolError.Unavailable("job $jobId failed"),
                            )
                        }
                    }
                    val waited = (System.currentTimeMillis() - startedAt) / 1000
                    if (waited >= limit) {
                        return@withContext ToolResult(
                            success = false,
                            observation = "Still generating after ${waited}s. OpenRouter job $jobId keeps " +
                                "running, but the video is not on the phone.",
                            error = ToolError.Timeout("job $jobId still running"),
                        )
                    }
                }

                val content = exchange("GET", "$API/videos/$jobId/content?index=0", key, null)
                if (!content.ok) return@withContext httpFailure("video.generate", content, key, jobId)
                val file = saveBytes(dir, content.body, "mp4")
                val waited = (System.currentTimeMillis() - startedAt) / 1000
                ToolResult(
                    success = true,
                    observation = "Video saved to ${file.absolutePath} after ${waited}s.",
                    data = buildJsonObject { put("path", file.absolutePath) },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                unreachable("video.generate", e)
            } catch (e: Exception) {
                internal("video.generate", e)
            }
        }

    /** Sleeps [seconds] in one-second steps. False as soon as the call is cancelled. */
    private suspend fun pause(seconds: Int, signal: CancellationSignal): Boolean {
        repeat(seconds) {
            if (signal.isCancelled()) return false
            delay(1_000)
        }
        return !signal.isCancelled()
    }

    private companion object {
        const val POLL_SECONDS = 5
        val JOB_ID = Regex("[A-Za-z0-9_-]{1,128}")
    }
}

// ---------------------------------------------------------------------------
// Shared helpers
// ---------------------------------------------------------------------------

private const val API = "https://openrouter.ai/api/v1"
private const val MAX_RESPONSE_BYTES = 64 * 1024 * 1024

private fun promptOf(args: ToolArgs): String? {
    val raw = (args["prompt"] as? JsonPrimitive)?.contentOrNull
    return raw?.trim()?.takeIf { it.isNotEmpty() && it.length <= 2000 }
}

private fun noKey(tool: String) = ToolResult(
    success = false,
    observation = "$tool: no OpenRouter key is set. Add one in Settings, under Plugins.",
    error = ToolError.PermissionDenied("no OpenRouter key"),
)

private fun badPrompt(tool: String) = ToolResult(
    success = false,
    observation = "$tool needs a prompt of 1 to 2000 characters.",
    error = ToolError.InvalidArguments("prompt"),
)

private fun cancelled(tool: String, jobId: String? = null) = ToolResult(
    success = false,
    observation = if (jobId == null) "$tool was cancelled."
    else "$tool stopped waiting. OpenRouter job $jobId may still finish there.",
    error = ToolError.Cancelled("$tool cancelled"),
)

private fun unreachable(tool: String, e: IOException) = ToolResult(
    success = false,
    observation = "$tool could not reach OpenRouter (${e.javaClass.simpleName}). " +
        "Check the connection before retrying.",
    error = ToolError.Unavailable("network: ${e.javaClass.simpleName}"),
)

private fun internal(tool: String, e: Exception) = ToolResult(
    success = false,
    observation = "$tool could not run (${e.javaClass.simpleName}). Do not retry the same prompt.",
    error = ToolError.Internal("${e.javaClass.simpleName} in $tool"),
)

/**
 * A failed OpenRouter reply, as one short line. The key is removed from the
 * detail before it is shown, in case a reply ever echoes it.
 */
private fun httpFailure(tool: String, reply: Reply, key: String, jobId: String? = null): ToolResult {
    val detail = String(reply.body, Charsets.UTF_8)
        .replace(key, "[key]").replace(Regex("\\s+"), " ").take(160)
    val job = if (jobId == null) "" else " (job $jobId)"
    val kind = when (reply.status) {
        401, 403 -> ToolError.PermissionDenied("OpenRouter refused the key")
        404 -> ToolError.NotFound("OpenRouter has no such job or model")
        else -> ToolError.Unavailable("HTTP ${reply.status}")
    }
    val hint = when (reply.status) {
        401, 403 -> "Check the key in Settings."
        404 -> "Check the model id in Settings."
        else -> "Try again later."
    }
    return ToolResult(
        success = false,
        observation = "$tool failed$job with HTTP ${reply.status}. $hint" +
            if (detail.isNotBlank()) " OpenRouter said: $detail" else "",
        error = kind,
    )
}

private fun saveBytes(dir: File, bytes: ByteArray, extension: String): File {
    dir.mkdirs()
    return File(dir, "${System.currentTimeMillis()}.$extension").apply { writeBytes(bytes) }
}

private class Reply(val status: Int, val body: ByteArray) {
    val ok: Boolean get() = status in 200..299
}

/**
 * One HTTPS exchange. Redirects are not followed: a 3xx is returned as a failure,
 * because following it would hand the Bearer header to whatever host it names.
 */
private fun exchange(method: String, url: String, key: String, json: String?): Reply {
    check(url.startsWith("$API/")) { "not an OpenRouter address" }
    val connection = URL(url).openConnection() as HttpURLConnection
    try {
        connection.requestMethod = method
        connection.connectTimeout = 20_000
        connection.readTimeout = 120_000
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("Authorization", "Bearer $key")
        connection.setRequestProperty("Accept", "application/json")
        if (json != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
        }
        val status = connection.responseCode
        val stream: InputStream? = if (status in 200..299) connection.inputStream else connection.errorStream
        return Reply(status, stream?.use(::readCapped) ?: ByteArray(0))
    } finally {
        connection.disconnect()
    }
}

private fun readCapped(input: InputStream): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(16 * 1024)
    while (true) {
        val n = input.read(buffer)
        if (n < 0) break
        if (out.size() + n > MAX_RESPONSE_BYTES) {
            throw IOException("response over ${MAX_RESPONSE_BYTES shr 20} MB")
        }
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}
