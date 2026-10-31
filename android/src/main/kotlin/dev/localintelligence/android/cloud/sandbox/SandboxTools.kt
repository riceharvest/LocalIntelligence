package dev.localintelligence.android.cloud.sandbox

import dev.localintelligence.android.tools.web.WebArgs
import dev.localintelligence.core.model.ObservationOrigin
import dev.localintelligence.core.model.ToolArgs
import dev.localintelligence.core.tool.AgentTool
import dev.localintelligence.core.tool.ObservationTruncator
import dev.localintelligence.core.tool.ToolContext
import dev.localintelligence.core.tool.ToolDefinition
import dev.localintelligence.core.tool.ToolError
import dev.localintelligence.core.tool.ToolResult
import dev.localintelligence.core.tool.ToolRisk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.net.SocketTimeoutException

// ===========================================================================
// The three sandbox tools. Every one is EXTERNAL_COMMUNICATION: the call sends something to a
// computer the user set up (a cloud sandbox, their SSH server, or Termux), so each call asks first,
// and the approval card shows the command, path or content in full.
//
// Their output is NETWORK origin, so the loop fences it as third-party text. A command can print
// anything, and a downloaded file can hold anything.
// ===========================================================================

/** The longest command the model may send. */
private const val MAX_COMMAND_CHARS = 10_000

/** The longest path, in characters. Longer is not a path anyone means. */
private const val MAX_PATH_CHARS = 512

/** The most a write may carry, in UTF-8 bytes. */
private const val MAX_WRITE_BYTES = 256 * 1024

/**
 * Characters of output shown to the model. Head and tail are kept, because the end of a log
 * usually holds the result. The loop saves a longer observation and offers it back by id.
 */
private const val MAX_OUTPUT_CHARS = 20_000

/** Bytes checked for a NUL before a file is called binary. */
private const val BINARY_SNIFF_BYTES = 8 * 1024

/** The tools of this plugin, in catalogue order. Descriptions match V0ToolCatalogue exactly. */
private const val RUN_DESCRIPTION =
    "Runs a shell command in the user's own cloud computer and returns the exit code and output."
private const val WRITE_DESCRIPTION =
    "Writes a text file in the user's own cloud computer; the file is overwritten if it exists."
private const val READ_DESCRIPTION =
    "Reads a text file from the user's own cloud computer and returns its text."

/** `sandbox.run`: one shell command, with its exit code and output. */
internal class SandboxRunTool(private val session: SandboxSession) : AgentTool {
    override val definition: ToolDefinition = ToolDefinition(
        name = "sandbox.run",
        description = RUN_DESCRIPTION,
        category = "sandbox",
        schema = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put(
                "properties",
                buildJsonObject {
                    putJsonObject("command") {
                        put("type", JsonPrimitive("string"))
                        put("maxLength", JsonPrimitive(MAX_COMMAND_CHARS))
                        put("description", JsonPrimitive("The shell command to run, for example \"python3 script.py\"."))
                    }
                    putJsonObject("timeout_seconds") {
                        put("type", JsonPrimitive("integer"))
                        put("minimum", JsonPrimitive(1))
                        put("maximum", JsonPrimitive(600))
                        put("description", JsonPrimitive("Seconds before the command is stopped. 60 by default, at most 600."))
                    }
                },
            )
            put("required", buildJsonArray { add(JsonPrimitive("command")) })
        },
        risk = ToolRisk.EXTERNAL_COMMUNICATION,
        observationOrigin = ObservationOrigin.NETWORK,
        tags = setOf(
            "run", "command", "shell", "terminal", "execute", "script", "python", "code", "cloud", "sandbox",
        ),
        requiredPermission = null,
    )

    override suspend fun execute(args: ToolArgs, context: ToolContext): ToolResult = guarded("sandbox.run") {
        val command = WebArgs.coerceString(args["command"])
            ?.takeIf { it.isNotEmpty() && it.length <= MAX_COMMAND_CHARS }
            ?: return@guarded ToolResult(
                success = false,
                observation = "sandbox.run needs command, a shell command of 1 to $MAX_COMMAND_CHARS characters.",
                error = ToolError.InvalidArguments("command"),
            )
        val timeout = WebArgs.coerceInt(args["timeout_seconds"], fallback = 60, min = 1, max = 600)
        val ready = session.ready(context.signal)
        val result = ready.provider.exec(ready.info, command, timeout)
        val output = result.output.trimEnd()
        val observation = buildString {
            append("Exit code ").append(result.exitCode).append('.')
            if (output.isEmpty()) {
                append(" No output.")
            } else {
                append('\n').append(ObservationTruncator.preview(output, MAX_OUTPUT_CHARS, id = null))
            }
        }
        ToolResult(
            success = true,
            observation = observation,
            data = buildJsonObject { put("exit_code", JsonPrimitive(result.exitCode)) },
        )
    }
}

/** `sandbox.write_file`: writes UTF-8 text to a path, creating folders and overwriting. */
internal class SandboxWriteFileTool(private val session: SandboxSession) : AgentTool {
    override val definition: ToolDefinition = ToolDefinition(
        name = "sandbox.write_file",
        description = WRITE_DESCRIPTION,
        category = "sandbox",
        schema = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put(
                "properties",
                buildJsonObject {
                    putJsonObject("path") {
                        put("type", JsonPrimitive("string"))
                        put("maxLength", JsonPrimitive(MAX_PATH_CHARS))
                        put("description", JsonPrimitive("Where to write the file in the sandbox, for example \"notes/todo.txt\"."))
                    }
                    putJsonObject("content") {
                        put("type", JsonPrimitive("string"))
                        put("description", JsonPrimitive("The full text of the file. At most 256 KB."))
                    }
                },
            )
            put("required", buildJsonArray { add(JsonPrimitive("path")); add(JsonPrimitive("content")) })
        },
        risk = ToolRisk.EXTERNAL_COMMUNICATION,
        observationOrigin = ObservationOrigin.NETWORK,
        tags = setOf("write file", "save file", "create file", "file", "sandbox", "cloud"),
        requiredPermission = null,
    )

    override suspend fun execute(args: ToolArgs, context: ToolContext): ToolResult = guarded("sandbox.write_file") {
        val path = validPath(args["path"])
            ?: return@guarded ToolResult(
                success = false,
                observation = "sandbox.write_file needs path, a file path in the sandbox.",
                error = ToolError.InvalidArguments("path"),
            )
        // Not trimmed: a file's text is written exactly as the model sent it.
        val content = (args["content"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: return@guarded ToolResult(
                success = false,
                observation = "sandbox.write_file needs content, the text to write.",
                error = ToolError.InvalidArguments("content"),
            )
        val bytes = content.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_WRITE_BYTES) {
            return@guarded ToolResult(
                success = false,
                observation = "sandbox.write_file takes at most 256 KB of content.",
                error = ToolError.InvalidArguments("content too large"),
            )
        }
        val ready = session.ready(context.signal)
        ready.provider.writeFile(ready.info, path, bytes)
        ToolResult(success = true, observation = "Wrote ${bytes.size} bytes to $path in the sandbox.")
    }
}

/** `sandbox.read_file`: the text of one file. Binary files are not shown. */
internal class SandboxReadFileTool(private val session: SandboxSession) : AgentTool {
    override val definition: ToolDefinition = ToolDefinition(
        name = "sandbox.read_file",
        description = READ_DESCRIPTION,
        category = "sandbox",
        schema = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put(
                "properties",
                buildJsonObject {
                    putJsonObject("path") {
                        put("type", JsonPrimitive("string"))
                        put("maxLength", JsonPrimitive(MAX_PATH_CHARS))
                        put("description", JsonPrimitive("The file to read in the sandbox, for example \"output/result.txt\"."))
                    }
                },
            )
            put("required", buildJsonArray { add(JsonPrimitive("path")) })
        },
        risk = ToolRisk.EXTERNAL_COMMUNICATION,
        observationOrigin = ObservationOrigin.NETWORK,
        tags = setOf("read file", "open file", "show file", "file", "sandbox", "cloud"),
        requiredPermission = null,
    )

    override suspend fun execute(args: ToolArgs, context: ToolContext): ToolResult = guarded("sandbox.read_file") {
        val path = validPath(args["path"])
            ?: return@guarded ToolResult(
                success = false,
                observation = "sandbox.read_file needs path, a file path in the sandbox.",
                error = ToolError.InvalidArguments("path"),
            )
        val ready = session.ready(context.signal)
        val bytes = ready.provider.readFile(ready.info, path)
        when {
            bytes.isEmpty() -> ToolResult(success = true, observation = "$path is empty.")
            bytes.take(BINARY_SNIFF_BYTES).any { it == 0.toByte() } -> ToolResult(
                success = false,
                observation = "$path is not a text file, so it is not shown.",
                error = ToolError.Unavailable("binary file"),
            )
            else -> {
                val text = String(bytes, Charsets.UTF_8)
                ToolResult(
                    success = true,
                    observation = "$path (${bytes.size} bytes):\n" +
                        ObservationTruncator.preview(text, MAX_OUTPUT_CHARS, id = null),
                )
            }
        }
    }
}

/** A path as the model sent it: one line of text, within the length cap. Null when unusable. */
private fun validPath(raw: JsonElement?): String? =
    WebArgs.coerceString(raw)?.takeIf { path ->
        path.isNotEmpty() && path.length <= MAX_PATH_CHARS &&
            path.none { it == '\n' || it == '\r' || it == '\u0000' }
    }

/**
 * Runs one tool body off the main thread and turns every failure into a [ToolResult]. A
 * [CancellationException] is rethrown, because it is the loop stopping, not a failure. Anything
 * else must not escape execute() into the agent loop.
 */
private suspend fun guarded(name: String, block: suspend () -> ToolResult): ToolResult =
    withContext(Dispatchers.IO) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: SandboxFailure) {
            ToolResult(success = false, observation = e.observation, error = e.error)
        } catch (e: SocketTimeoutException) {
            ToolResult(
                success = false,
                observation = "$name: the sandbox did not answer in time. Try again.",
                error = ToolError.Timeout("$name socket timeout"),
            )
        } catch (e: IOException) {
            ToolResult(
                success = false,
                observation = "$name could not reach the sandbox (${e.javaClass.simpleName}). " +
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
