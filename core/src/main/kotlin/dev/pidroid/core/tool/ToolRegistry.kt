package dev.pidroid.core.tool

import dev.pidroid.core.model.ToolArgs

/** Everything the agent loop needs to route a tool call. Pure data, no Android. */
interface ToolRegistry {
    fun all(): List<AgentTool>
    fun byName(name: String): AgentTool?
    fun byCategory(category: String): List<AgentTool> =
        all().filter { it.definition.category == category }
}

/** In-memory registry. The default implementation; :android may decorate it. */
class SimpleToolRegistry(tools: List<AgentTool> = emptyList()) : ToolRegistry {
    private val byName: Map<String, AgentTool> = tools.associateBy { it.definition.name }

    init {
        require(byName.size == tools.size) {
            val dupes = tools.groupBy { it.definition.name }.filterValues { it.size > 1 }.keys
            "duplicate tool names: $dupes"
        }
    }

    override fun all(): List<AgentTool> = byName.values.toList()
    override fun byName(name: String): AgentTool? = byName[name]
}

/**
 * Picks the handful of tools worth showing the model.
 *
 * v0 uses lexical scoring only — no LLM. Returning 3-6 tools instead of 20 is
 * the single biggest context saving in the system.
 */
fun interface ToolSelector {
    fun select(
        task: String,
        sessionKeywords: List<String>,
        available: List<AgentTool>,
        maxTools: Int,
    ): List<AgentTool>
}

/**
 * Default lexical selector: exact substring match on name/description/tags,
 * plus token overlap. Deterministic and dependency-free.
 */
class LexicalToolSelector : ToolSelector {

    override fun select(
        task: String,
        sessionKeywords: List<String>,
        available: List<AgentTool>,
        maxTools: Int,
    ): List<AgentTool> {
        if (available.isEmpty()) return emptyList()
        if (available.size <= maxTools) return available

        val taskTokens = tokenize(task).toSet()
        val keywordTokens = sessionKeywords.flatMap { tokenize(it) }.toSet()

        val scored = available.map { tool ->
            val def = tool.definition
            val nameTokens = tokenize(def.name).toSet()
            val descTokens = tokenize(def.description).toSet()
            val tagTokens = def.tags.flatMap { tokenize(it) }.toSet()

            val overlap = taskTokens.intersect(descTokens).size * 2 +
                taskTokens.intersect(tagTokens).size * 3 +
                taskTokens.intersect(nameTokens).size * 4 +
                keywordTokens.intersect(descTokens).size +
                keywordTokens.intersect(tagTokens).size

            // Exact substring in the task is a strong signal a small model needs.
            val substringHit = if (task.contains(def.name, ignoreCase = true)) 10 else 0

            tool to (overlap + substringHit)
        }

        return scored.sortedWith(
            compareByDescending<Pair<AgentTool, Int>> { it.second }.thenBy { it.first.definition.name },
        ).take(maxTools).map { it.first }
    }

    private fun tokenize(text: String): List<String> =
        text.lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length > 2 }
}

/**
 * Truncates a tool observation to the model-visible budget.
 * Deterministic: never throws, always returns valid text.
 */
object ObservationTruncator {
    const val DEFAULT_BUDGET_CHARS = 2048

    fun truncate(observation: String, budget: Int = DEFAULT_BUDGET_CHARS): String {
        if (observation.length <= budget) return observation
        val head = observation.take(budget - 40)
        val dropped = observation.length - head.length
        return "$head\n…[truncated $dropped chars]"
    }
}

/** Validates a tool call against the registry before execution. */
object ToolCallValidator {
    sealed interface Result {
        data class Valid(val tool: AgentTool) : Result
        data class Rejected(val observation: String) : Result
    }

    fun validate(
        name: String,
        args: ToolArgs,
        visible: List<AgentTool>,
        registry: ToolRegistry,
    ): Result {
        val tool = visible.firstOrNull { it.definition.name == name }
            ?: return Result.Rejected("Unknown tool \"$name\".")

        if (args.keys.any { it !in tool.definition.schema.keys }) {
            return Result.Rejected(
                "Tool \"$name\" got unexpected argument(s): " +
                    args.keys.filter { it !in tool.definition.schema.keys }.joinToString(),
            )
        }
        // Unavailable tools (repeated failures) are rejected before re-execution.
        if (registry.byName(name) == null) {
            return Result.Rejected("Tool \"$name\" is not available.")
        }
        return Result.Valid(tool)
    }
}
