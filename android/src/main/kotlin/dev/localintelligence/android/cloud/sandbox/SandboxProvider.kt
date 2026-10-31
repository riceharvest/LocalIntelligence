package dev.localintelligence.android.cloud.sandbox

import dev.localintelligence.core.tool.ToolError

/**
 * What the sandbox tools need from a cloud computer service, and nothing a particular service
 * adds. A second service implements this; the tools, the session and the settings stay as they are.
 *
 * Every method throws [SandboxFailure] for an expected failure (bad key, no credit, a missing
 * file, a sandbox that will not start). Implementations do their blocking work on
 * Dispatchers.IO, because the callers are suspend functions on the agent's thread.
 */
interface SandboxProvider {
    /** Creates a sandbox with the smallest default resources. It may still be starting. */
    suspend fun create(idleMinutes: Int): SandboxInfo

    /** The sandbox as the service reports it now. */
    suspend fun info(id: String): SandboxInfo

    /** Starts a stopped, archived or paused sandbox. */
    suspend fun start(id: String)

    /** Stops a sandbox. It keeps its files, so a later start resumes it. */
    suspend fun stop(id: String)

    /** Runs one shell command inside the sandbox. */
    suspend fun exec(info: SandboxInfo, command: String, timeoutSeconds: Int): ExecResult

    /** Writes [bytes] to [path] in the sandbox, creating folders and overwriting a file. */
    suspend fun writeFile(info: SandboxInfo, path: String, bytes: ByteArray)

    /** The bytes of the file at [path]. Throws when the file is larger than the provider's cap. */
    suspend fun readFile(info: SandboxInfo, path: String): ByteArray
}

/**
 * A sandbox as the service reports it. [state] is the service's own word for it, such as
 * "started". [toolbox] is the address the in-sandbox API answers on, or null when the service
 * did not give one.
 */
data class SandboxInfo(val id: String, val state: String, val toolbox: String?)

/** The command's exit code and its output. The service merges stdout and stderr; it does not split them. */
data class ExecResult(val exitCode: Int, val output: String)

/**
 * An expected failure. [observation] is one plain sentence, the same text the model and the
 * user see, and [error] is its typed kind.
 */
class SandboxFailure(val error: ToolError, val observation: String) : Exception(observation)
