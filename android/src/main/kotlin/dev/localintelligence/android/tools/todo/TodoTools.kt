package dev.localintelligence.android.tools.todo

import dev.localintelligence.core.agent.TodoList
import dev.localintelligence.core.model.ToolArgs
import dev.localintelligence.core.tool.AgentTool
import dev.localintelligence.core.tool.ToolContext
import dev.localintelligence.core.tool.ToolDefinition
import dev.localintelligence.core.tool.ToolError
import dev.localintelligence.core.tool.ToolResult
import dev.localintelligence.core.tool.catalogue.V0ToolCatalogue
import kotlinx.serialization.json.JsonArray

// =====================================================================================
// todo.update: the task checklist of a multi-step task.
//
// The list itself is owned by the runtime (TodoList, held on Session), so it survives
// compaction and is re-injected into every prompt. This tool only replaces it. The
// definition is read from V0ToolCatalogue rather than written out again, so the name,
// tier and origin the catalogue checks cannot drift from the tool.
//
// The observation is a count, not the items: the items are the model's own text and
// the runtime already shows them to the model on every step.
// =====================================================================================

/** Replaces the conversation's task list with the whole list the model sends. */
class TodoUpdateTool(private val list: TodoList) : AgentTool {

    override val definition: ToolDefinition = V0ToolCatalogue.byName(TodoList.TOOL_NAME)
        ?: error("${TodoList.TOOL_NAME} is missing from V0ToolCatalogue")

    override suspend fun execute(args: ToolArgs, context: ToolContext): ToolResult {
        val raw = args["items"] as? JsonArray ?: return invalid("todo.update needs items: the whole list, as an array.")
        return when (val parsed = TodoList.parse(raw)) {
            is TodoList.Parsed.Invalid -> invalid(parsed.message)
            is TodoList.Parsed.Ok -> {
                list.replace(parsed.items)
                if (parsed.items.isEmpty()) {
                    ToolResult(success = true, observation = "Task list cleared.")
                } else {
                    val done = parsed.items.count { it.status == TodoList.Status.DONE }
                    val open = parsed.items.count {
                        it.status == TodoList.Status.PENDING || it.status == TodoList.Status.IN_PROGRESS
                    }
                    ToolResult(
                        success = true,
                        observation = "Task list saved: $done of ${parsed.items.size} done, $open to go.",
                    )
                }
            }
        }
    }

    private fun invalid(message: String) = ToolResult(
        success = false,
        observation = message,
        error = ToolError.InvalidArguments(message),
    )
}

/** Every tool of the task-list family. One tool, so one entry. */
fun todoTools(list: TodoList): List<AgentTool> = listOf(TodoUpdateTool(list))
