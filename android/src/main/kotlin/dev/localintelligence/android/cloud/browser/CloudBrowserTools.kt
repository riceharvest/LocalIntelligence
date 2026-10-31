package dev.localintelligence.android.cloud.browser

import dev.localintelligence.android.tools.web.UrlVerdict
import dev.localintelligence.android.tools.web.WebArgs
import dev.localintelligence.android.tools.web.WebUrls
import dev.localintelligence.core.model.ToolArgs
import dev.localintelligence.core.settings.SettingsReader
import dev.localintelligence.core.tool.AgentTool
import dev.localintelligence.core.tool.CancellationSignal
import dev.localintelligence.core.tool.ObservationTruncator
import dev.localintelligence.core.tool.ToolContext
import dev.localintelligence.core.tool.ToolDefinition
import dev.localintelligence.core.tool.ToolError
import dev.localintelligence.core.tool.ToolResult
import dev.localintelligence.core.tool.catalogue.V0ToolCatalogue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.SocketTimeoutException

// ===========================================================================
// The two browser tools. Both read their definitions from V0ToolCatalogue, so the name, risk tier
// and origin are the catalogue's and cannot drift from it.
//
// browser.task starts a task and waits for it, up to the user's wait limit. A task that outlives the
// limit is reported by its id, and browser.status checks it later. The result text is the task's
// own output from the web, so both tools return NETWORK origin: the loop fences it as untrusted text.
//
// The live view address is a credential: anyone holding it can drive the open browser. It is shown
// only while the task is still running, to the user who asked for the task, and never stored.
// ===========================================================================

/** The most instruction text the model may send. The card shows all of it before anything runs. */
private const val MAX_TASK_CHARS = 4000

/** Characters of a finished task's result shown to the model. The loop keeps a longer one by id. */
private const val MAX_RESULT_CHARS = 8000

/** Seconds between status polls while a task runs. */
private const val POLL_SECONDS = 3

/** While a task runs and has no live view yet, its events are re-read at most this often. */
private const val LIVE_CHECK_EVERY_MS = 15_000L

/** `browser.task`: starts a web task and waits for its result, up to the wait limit. */
internal class BrowserTaskTool(private val reader: SettingsReader) : AgentTool {
    override val definition: ToolDefinition =
        checkNotNull(V0ToolCatalogue.byName("browser.task")) { "browser.task missing from catalogue" }

    override suspend fun execute(args: ToolArgs, context: ToolContext): ToolResult = browserGuarded("browser.task") {
        if (browserKeyOf(reader).isBlank()) return@browserGuarded noKey("browser.task")
        val task = WebArgs.coerceString(args["task"])
            ?.takeIf { it.length <= MAX_TASK_CHARS }
            ?: return@browserGuarded ToolResult(
                success = false,
                observation = "browser.task needs task, what to do on the web, in 1 to $MAX_TASK_CHARS characters.",
                error = ToolError.InvalidArguments("task"),
            )

        // The V4 API has no start-URL field, so the address goes into the instruction. It is checked
        // with the same rules as web.fetch, so only http and https can be named.
        var instruction = task
        WebArgs.coerceString(args["start_url"])?.let { raw ->
            when (val verdict = WebUrls.validate(raw)) {
                is UrlVerdict.Accepted -> instruction = "Start by opening ${verdict.url}.\n\n$task"
                is UrlVerdict.Rejected -> return@browserGuarded ToolResult(
                    success = false,
                    observation = verdict.reason,
                    error = ToolError.InvalidArguments("start_url"),
                )
            }
        }

        val profile = if (reader[CloudBrowserSettings.USE_PROFILE]) {
            reader[CloudBrowserSettings.PROFILE_ID].takeIf { it.isNotBlank() }
                ?: return@browserGuarded ToolResult(
                    success = false,
                    observation = "Allow logins is on, but no saved profile id is set in Settings. " +
                        "Set the id, or switch the option off.",
                    error = ToolError.Unavailable("no profile id"),
                )
        } else {
            null
        }

        if (context.signal.isCancelled()) return@browserGuarded cancelled("browser.task")
        val provider = providerFor(reader)
        val id = provider.start(
            instruction,
            BrowserStartOptions(maxSpendUsd = reader[CloudBrowserSettings.MAX_SPEND_USD].toDouble(), profileId = profile),
        )

        val minutes = reader[CloudBrowserSettings.WAIT_MINUTES]
        val startedAt = System.currentTimeMillis()
        var liveUrl: String? = null
        var nextLiveCheck = 0L
        while (true) {
            if (!pause(POLL_SECONDS, context.signal)) return@browserGuarded cancelled("browser.task", id)
            val status = provider.status(id)
            if (status.isTerminal) break
            val now = System.currentTimeMillis()
            if (liveUrl == null && now >= nextLiveCheck) {
                nextLiveCheck = now + LIVE_CHECK_EVERY_MS
                liveUrl = liveUrlOrNull(provider, id)
            }
            if (now - startedAt >= minutes * 60_000L) {
                return@browserGuarded ToolResult(
                    success = false,
                    observation = "Browser task $id is still ${status.state} after $minutes minutes. It keeps " +
                        "running on Browser Use. Call browser.status with task_id $id to get the result." +
                        liveLine(liveUrl),
                    data = buildJsonObject { put("task_id", id); put("status", status.state) },
                    error = ToolError.Timeout("browser task $id still running"),
                )
            }
        }
        finishedResult(id, provider.result(id))
    }
}

/** `browser.status`: one check on a task by its id. Returns the result once the task has finished. */
internal class BrowserStatusTool(private val reader: SettingsReader) : AgentTool {
    override val definition: ToolDefinition =
        checkNotNull(V0ToolCatalogue.byName("browser.status")) { "browser.status missing from catalogue" }

    override suspend fun execute(args: ToolArgs, context: ToolContext): ToolResult = browserGuarded("browser.status") {
        if (browserKeyOf(reader).isBlank()) return@browserGuarded noKey("browser.status")
        val id = (args["task_id"] as? JsonPrimitive)?.contentOrNull?.trim()
            ?.takeIf { BROWSER_TASK_ID.matches(it) }
            ?: return@browserGuarded ToolResult(
                success = false,
                observation = "browser.status needs task_id, the id that browser.task reported.",
                error = ToolError.InvalidArguments("task_id"),
            )
        if (context.signal.isCancelled()) return@browserGuarded cancelled("browser.status", id)

        val provider = providerFor(reader)
        val status = provider.status(id)
        if (status.isTerminal) return@browserGuarded finishedResult(id, provider.result(id))
        val live = if (status.state == "running") liveUrlOrNull(provider, id) else null
        ToolResult(
            success = true,
            observation = "Browser task $id is ${status.state}. Check again later with browser.status." + liveLine(live),
            data = buildJsonObject { put("task_id", id); put("status", status.state) },
        )
    }
}

/** The observation for a task that has finished: its result, or why it failed or was cancelled. */
private fun finishedResult(id: String, result: BrowserTaskResult): ToolResult = when (result.state) {
    "completed" -> ToolResult(
        success = true,
        observation = "Browser task $id completed.\n" + ObservationTruncator.preview(
            result.text ?: "The task gave no result text.",
            MAX_RESULT_CHARS,
            id = null,
        ),
        data = buildJsonObject { put("task_id", id); put("status", result.state) },
    )
    "failed" -> ToolResult(
        success = false,
        observation = "Browser task $id failed." + (result.error?.let { " Browser Use said: ${it.take(300)}" } ?: ""),
        data = buildJsonObject { put("task_id", id); put("status", result.state) },
        error = ToolError.Unavailable("browser task $id failed"),
    )
    "cancelled" -> ToolResult(
        success = false,
        observation = "Browser task $id was cancelled.",
        data = buildJsonObject { put("task_id", id); put("status", result.state) },
        error = ToolError.Unavailable("browser task $id cancelled"),
    )
    else -> ToolResult(
        success = true,
        observation = "Browser task $id is ${result.state.ifEmpty { "in an unknown state" }}. Check again later.",
        data = buildJsonObject { put("task_id", id); put("status", result.state) },
    )
}

/** The live view line for a running task, or nothing when the browser has no address yet. */
private fun liveLine(live: String?): String =
    if (live == null) "" else "\nLive view, open only while the browser runs: $live"

/** The live view address, or null. A failed lookup is not a failed task, so it is not reported. */
private suspend fun liveUrlOrNull(provider: CloudBrowserProvider, id: String): String? =
    try {
        provider.liveUrl(id)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

/** Sleeps [seconds] in one-second steps. False as soon as the call is cancelled. */
private suspend fun pause(seconds: Int, signal: CancellationSignal): Boolean {
    repeat(seconds) {
        if (signal.isCancelled()) return false
        delay(1_000)
    }
    return !signal.isCancelled()
}

private fun noKey(name: String) = ToolResult(
    success = false,
    observation = "$name needs a Browser Use API key. Add one in Settings, under Cloud browser.",
    error = ToolError.PermissionDenied("no browser key"),
)

private fun cancelled(name: String, id: String? = null) = ToolResult(
    success = false,
    observation = if (id == null) {
        "$name was cancelled before it started."
    } else {
        "$name stopped waiting. The task keeps running on Browser Use; check it with browser.status and task_id $id."
    },
    error = ToolError.Cancelled(name),
)

/**
 * Runs one tool body off the main thread and turns every failure into a [ToolResult]. A
 * [CancellationException] is rethrown, because it is the loop stopping, not a failure.
 */
private suspend fun browserGuarded(name: String, block: suspend () -> ToolResult): ToolResult =
    withContext(Dispatchers.IO) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: BrowserFailure) {
            ToolResult(success = false, observation = e.observation, error = e.error)
        } catch (e: SocketTimeoutException) {
            ToolResult(
                success = false,
                observation = "$name: Browser Use did not answer in time. Try again.",
                error = ToolError.Timeout("$name socket timeout"),
            )
        } catch (e: IOException) {
            ToolResult(
                success = false,
                observation = "$name could not reach Browser Use (${e.javaClass.simpleName}). " +
                    "Check the connection before retrying.",
                error = ToolError.Unavailable("network: ${e.javaClass.simpleName}"),
            )
        } catch (e: Exception) {
            ToolResult(
                success = false,
                observation = "$name could not run (${e.javaClass.simpleName}). Do not retry the same input.",
                error = ToolError.Internal("${e.javaClass.simpleName} in $name"),
            )
        }
    }
