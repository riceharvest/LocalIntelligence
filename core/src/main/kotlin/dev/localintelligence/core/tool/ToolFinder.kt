package dev.localintelligence.core.tool

import dev.localintelligence.core.agent.Session
import dev.localintelligence.core.model.ObservationOrigin
import dev.localintelligence.core.model.ToolArgs
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.add

/**
 * `tools.find`: lets the model ask for a tool the prompt does not list.
 *
 * Offered only with the Technical setting "Let the model look up tools". The
 * system prompt then lists a few tools and this one; when the model needs
 * something else it asks, the matching tools' descriptions come back as this
 * call's result (appended to the conversation, so the prompt before it stays the
 * same), and those tools become callable for the rest of the conversation
 * ([Session.discoveredTools]). A smaller prompt, at the cost of trusting a small
 * model to look things up, which is why it is off by default.
 */
class ToolFinder(
    private val registry: ToolRegistry,
    private val selector: ToolSelector,
    private val session: Session,
) : AgentTool {
    override val definition: ToolDefinition = DEFINITION

    override suspend fun execute(args: ToolArgs, context: ToolContext): ToolResult {
        val query = (args["query"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        if (query.isEmpty()) {
            return ToolResult(success = false, observation = "Say what the tool should do, for example \"set an alarm\".")
        }
        val known = session.epochTools.toSet() + session.discoveredTools + NAME
        val candidates = registry.all().filter { it.definition.name !in known }
        val found = selector.select(query, emptyList(), candidates, MAX_FOUND)
        if (found.isEmpty()) {
            return ToolResult(success = true, observation = "No other tool matches \"$query\". Use the tools you have, or answer.")
        }
        found.forEach { session.discoveredTools += it.definition.name }
        val lines = found.joinToString("\n") { tool ->
            val def = tool.definition
            val argNames = (def.schema["properties"] as? JsonObject)?.keys.orEmpty()
            val arguments = if (argNames.isEmpty()) "no arguments" else "arguments: " + argNames.joinToString()
            "- ${def.name}: ${def.description} ($arguments)"
        }
        return ToolResult(
            success = true,
            observation = "These tools can be called now, like any other:\n$lines",
        )
    }

    companion object {
        const val NAME: String = "tools.find"

        /** Tools one lookup returns: enough to choose from, few enough to stay short. */
        const val MAX_FOUND: Int = 3

        val DEFINITION: ToolDefinition = ToolDefinition(
            name = NAME,
            description = "Finds a tool that is not in this list, by what it should do, and makes it callable. " +
                "Use it when none of the listed tools fits.",
            category = "tools",
            schema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("query") {
                        put("type", "string")
                        put("maxLength", 120)
                        put("description", "What the tool should do, for example \"set an alarm\".")
                    }
                }
                putJsonArray("required") { add("query") }
                put("additionalProperties", false)
            },
            risk = ToolRisk.READ_ONLY,
            observationOrigin = ObservationOrigin.LOCAL,
            tags = setOf("find a tool", "look up a tool", "other tool"),
        )
    }
}
