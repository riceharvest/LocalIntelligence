package dev.pidroid.core.agent

import kotlinx.serialization.json.JsonObject
import dev.pidroid.core.model.ToolArgs

/**
 * The ONLY two things the model is allowed to produce.
 *
 * No thought. No plan. No critique. No confidence. No reflection.
 * Reasoning stays inside the model. Everything the runtime must reason about
 * is handled deterministically in Kotlin — see LoopDetector and ContextBuilder.
 */
sealed interface AgentAction {
    data class Respond(val text: String) : AgentAction
    data class CallTool(val name: String, val arguments: ToolArgs) : AgentAction
}

/** A parsed-but-untrusted action, before validation. */
data class RawAction(
    val payload: String,
    val finishReason: String? = null,
)

sealed interface ActionParseResult {
    data class Parsed(val action: AgentAction) : ActionParseResult
    data class Malformed(val reason: String, val raw: String) : ActionParseResult
}

/**
 * Parses raw model output into an [AgentAction].
 *
 * Implementations must be total: any unparseable output yields
 * [ActionParseResult.Malformed] rather than throwing.
 */
fun interface ActionParser {
    fun parse(raw: String, allowedTools: Set<String>): ActionParseResult
}
