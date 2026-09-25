package dev.localintelligence.core.tool

import kotlinx.serialization.json.JsonObject
import dev.localintelligence.core.model.ToolArgs

/**
 * Risk classification. The RUNTIME decides what needs confirmation — never the
 * model, and never the tool implementation itself.
 */
enum class ToolRisk {
    READ_ONLY,
    REVERSIBLE,
    DESTRUCTIVE,
    EXTERNAL_COMMUNICATION,
    PRIVILEGED,
    ;

    val requiresConfirmation: Boolean
        get() = this == DESTRUCTIVE || this == EXTERNAL_COMMUNICATION
}

data class ToolDefinition(
    val name: String,
    val description: String,
    val category: String,
    val schema: JsonObject,
    val risk: ToolRisk,
    val tags: Set<String> = emptySet(),
    /** Android permission this tool needs, or null if none. Documentation + UI only. */
    val requiredPermission: String? = null,
)

/**
 * The result of a tool call.
 *
 * [observation] is the critical field: it is the ONLY thing fed back to the LLM.
 * It must be short, plain text, and already truncated. Never a stack trace,
 * never a raw Android object, never an unbounded JSON blob.
 */
data class ToolResult(
    val success: Boolean,
    val observation: String,
    val data: ToolArgs? = null,
    val error: ToolError? = null,
)

sealed class ToolError(val code: String, open val message: String) {
    data class InvalidArguments(override val message: String) : ToolError("invalid_arguments", message)
    data class PermissionDenied(override val message: String) : ToolError("permission_denied", message)
    data class NotFound(override val message: String) : ToolError("not_found", message)
    data class Unavailable(override val message: String) : ToolError("unavailable", message)
    data class Timeout(override val message: String) : ToolError("timeout", message)
    data class Cancelled(override val message: String) : ToolError("cancelled", message)
    data class Internal(override val message: String) : ToolError("internal", message)
}

/**
 * Context handed to a tool at execution time. Pure data — no Android types.
 * The Android layer is responsible for filling [permissionGranted].
 */
data class ToolContext(
    val permissionGranted: Boolean = true,
    /** Whether the user has already approved this specific call. */
    val userConfirmed: Boolean = false,
    val signal: CancellationSignal = CancellationSignal.None,
)

/** Minimal cancellation primitive so :core stays free of coroutine-internal types. */
fun interface CancellationSignal {
    fun isCancelled(): Boolean

    companion object {
        val None = CancellationSignal { false }
    }
}

/**
 * A single tool the agent can call.
 *
 * Implementations MUST:
 *  - never throw for an expected failure; return a failed [ToolResult]
 *  - keep [ToolResult.observation] under the observation budget
 *  - coerce and clamp arguments defensively (models emit wrong types)
 */
interface AgentTool {
    val definition: ToolDefinition
    suspend fun execute(args: ToolArgs, context: ToolContext): ToolResult
}
