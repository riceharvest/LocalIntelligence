package dev.localintelligence.android.tools.subtask

import dev.localintelligence.core.agent.SUBTASK_TOOL_NAME
import dev.localintelligence.core.model.ToolArgs
import dev.localintelligence.core.tool.AgentTool
import dev.localintelligence.core.tool.ToolContext
import dev.localintelligence.core.tool.ToolDefinition
import dev.localintelligence.core.tool.ToolError
import dev.localintelligence.core.tool.ToolResult
import dev.localintelligence.core.tool.catalogue.V0ToolCatalogue

/**
 * The registry entry for `task.delegate`. The agent loop runs a subtask itself (see
 * AgentController.delegate), because a subtask needs the loop's own state and can raise a confirmation
 * that only the loop can return. So this execute is never reached through the loop. It exists so the
 * name is in the registry, where the validator, the policy and the catalogue agreement check it.
 */
fun subtaskTools(): List<AgentTool> = listOf(SubtaskDelegateTool())

private class SubtaskDelegateTool : AgentTool {
    override val definition: ToolDefinition =
        checkNotNull(V0ToolCatalogue.byName(SUBTASK_TOOL_NAME)) { "$SUBTASK_TOOL_NAME missing from catalogue" }

    override suspend fun execute(args: ToolArgs, context: ToolContext): ToolResult = ToolResult(
        success = false,
        observation = "$SUBTASK_TOOL_NAME runs only inside the agent loop. Do the work yourself, or answer without it.",
        error = ToolError.Unavailable("$SUBTASK_TOOL_NAME called outside the agent loop"),
    )
}
