package dev.localintelligence.core.tool.catalogue

import dev.localintelligence.core.model.ToolArgs
import dev.localintelligence.core.tool.AgentTool
import dev.localintelligence.core.tool.ToolContext
import dev.localintelligence.core.tool.ToolDefinition
import dev.localintelligence.core.tool.ToolError
import dev.localintelligence.core.tool.ToolResult
import kotlinx.serialization.json.JsonObject

/**
 * A definition-only [AgentTool], for tests only.
 *
 * This exists so the retrieval smoke test can drive the **shipped**
 * `LexicalToolSelector` instead of a re-implementation of it. A catalogue test that
 * scores with its own scorer proves only that the catalogue agrees with the test's
 * scorer; this way it proves the catalogue agrees with the code the runtime runs.
 *
 * It is deliberately in the test source set. A definition-only tool in `main` would
 * be one `require`-free refactor away from being registered at runtime, where calling
 * it returns a failed result forever.
 */
class DefinitionOnlyTool(
    override val definition: ToolDefinition,
) : AgentTool {
    override suspend fun execute(args: JsonObject, context: ToolContext): ToolResult =
        ToolResult(
            success = false,
            observation = "Not implemented: ${definition.name} is a catalogue entry with no body.",
            error = ToolError.Unavailable("no implementation in :core"),
        )
}

/** Every catalogue definition, adapted so the real selector can score them. */
internal fun catalogueAsTools(): List<AgentTool> =
    V0ToolCatalogue.definitions.map(::DefinitionOnlyTool)
