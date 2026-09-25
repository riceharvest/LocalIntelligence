package dev.localintelligence.app

import dev.localintelligence.core.model.ToolArgs
import dev.localintelligence.core.tool.AgentTool
import dev.localintelligence.core.tool.ToolContext
import dev.localintelligence.core.tool.ToolDefinition
import dev.localintelligence.core.tool.ToolResult
import dev.localintelligence.core.tool.ToolRisk
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * A tool at the risk level that requires a human.
 *
 * Used to drive the service into its suspended-but-not-terminal state, which is
 * the one place where "is this terminal?" and "should the service still be
 * running?" deliberately disagree.
 */
internal class DestructiveToolForServiceTest : AgentTool {
    override val definition = destructiveFileDelete

    var executions = 0
        private set

    override suspend fun execute(args: ToolArgs, context: ToolContext): ToolResult {
        executions += 1
        return ToolResult(success = true, observation = "deleted 1 file")
    }
}

/**
 * A minimal object schema.
 *
 * The real validator checks argument *shape*, not semantics, so a schema this
 * small exercises the confirmation gate without testing the validator itself.
 */
private val destructiveFileDelete = ToolDefinition(
    name = "file.delete",
    description = "Deletes a file.",
    category = "file",
    schema = buildJsonObject {
        put("type", "object")
        put(
            "properties",
            buildJsonObject { put("path", buildJsonObject { put("type", "string") }) },
        )
        putJsonArray("required") { }
    },
    risk = ToolRisk.DESTRUCTIVE,
    tags = setOf("delete"),
)
