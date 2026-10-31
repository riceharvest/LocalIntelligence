package dev.localintelligence.android.cloud.browser

import dev.localintelligence.core.tool.ToolError

// ===========================================================================
// What the browser tools need from a cloud browser service, and nothing a particular service
// adds. Browser Use Cloud is the one implementation so far. Steel or Browserbase implement this
// interface; the tools, the settings and the polling stay as they are.
//
// The shape follows a task model, not a browser model: a task is started with one natural-language
// instruction, its state is polled, and its result text is read once it has finished. The live
// view address is a separate read, because only an open browser has one.
// ===========================================================================

/** Limits and the optional saved profile one task is started with. */
data class BrowserStartOptions(
    /** The most the task may spend, in US dollars. The service stops the task at this cost. */
    val maxSpendUsd: Double,
    /** A saved browser profile whose logins the task starts with, or null for a clean browser. */
    val profileId: String?,
)

/** A task's state as the service reports it now, such as "running" or "completed". */
data class BrowserTaskStatus(val id: String, val state: String) {
    /** True once the service will not change the task again. */
    val isTerminal: Boolean get() = state in TERMINAL_STATES

    companion object {
        /** States the service reports for a task that has stopped for good. */
        val TERMINAL_STATES: Set<String> = setOf("completed", "failed", "cancelled")
    }
}

/** A finished task's outcome. [text] is the agent's answer, which is third-party text. */
data class BrowserTaskResult(val state: String, val text: String?, val error: String?)

/**
 * What a cloud browser service must do for the tools. Every method throws [BrowserFailure] for an
 * expected failure (bad key, no credit, unknown task). Implementations do their blocking work on
 * Dispatchers.IO, because the callers are suspend functions on the agent's thread.
 */
interface CloudBrowserProvider {
    /** Starts a task and returns its id at once. The task then runs on the service's side. */
    suspend fun start(instruction: String, options: BrowserStartOptions): String

    /** The task's state now. Cheap: it carries no result text. */
    suspend fun status(taskId: String): BrowserTaskStatus

    /** The task's outcome. Meaningful once [BrowserTaskStatus.isTerminal] is true. */
    suspend fun result(taskId: String): BrowserTaskResult

    /** The address of the task's open browser, or null when none is open. Shown to the user to watch. */
    suspend fun liveUrl(taskId: String): String?
}

/**
 * An expected failure. [observation] is one plain sentence, the same text the model and the user
 * see, and [error] is its typed kind.
 */
class BrowserFailure(val error: ToolError, val observation: String) : Exception(observation)
