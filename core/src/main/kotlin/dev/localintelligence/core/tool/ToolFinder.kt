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
        // The selector ranks tools but does not cut off: it returns the best three
        // even when none overlaps the query. And its words include "for", "the" and
        // the like, which most descriptions share, so a query with no real match
        // would still make three unrelated tools callable. Only a tool that shares a
        // content word with the query is offered.
        val wanted = lexicalTokens(query).filterNot { it in FUNCTION_WORDS }.toSet()
        val matching = candidates.filter { tool -> wanted.any { it in wordsOf(tool) } }
        val found = selector.select(query, emptyList(), matching, MAX_FOUND)
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

        /**
         * Words a lookup must share with a tool to offer it. Function words only: the
         * lookup's own words ("find", "tool") are left in, since a tool description
         * rarely repeats them and the filter should not depend on that either.
         */
        private val FUNCTION_WORDS: Set<String> = setOf(
            "the", "and", "for", "with", "that", "this", "from", "into", "your", "you", "can",
            "could", "please", "want", "need", "make", "help", "some", "any", "all", "get", "use",
            "not", "are", "was", "will", "would", "should", "have", "has", "how", "what", "when",
            "where", "which", "who", "just", "also", "about", "there", "here",
        )

        /** Every word a tool offers for matching: its name, description and tags. */
        private fun wordsOf(tool: AgentTool): Set<String> {
            val def = tool.definition
            return (lexicalTokens(def.name) + lexicalTokens(def.description) + def.tags.flatMap { lexicalTokens(it) }).toSet()
        }

        /**
         * The tool names a lookup result lists, one `- name: description` line each.
         * Read back from the window so a tool stays callable while its result is there
         * (see AgentController.compactNow). A name is split at ": ", the separator this
         * file writes; a dotted or dashed name has no space before its colon.
         */
        fun namedIn(observation: String): Set<String> =
            observation.lineSequence()
                .filter { it.startsWith("- ") }
                .map { it.removePrefix("- ").substringBefore(":").trim() }
                .toSet()

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
