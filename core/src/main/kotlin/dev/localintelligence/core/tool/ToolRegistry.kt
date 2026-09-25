package dev.localintelligence.core.tool

import dev.localintelligence.core.model.ToolArgs
import dev.localintelligence.core.tool.selection.LexicalNormalizer
import dev.localintelligence.core.tool.selection.LexicalScorer
import kotlinx.serialization.json.JsonObject

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
 * plus token overlap, on STEMMED tokens with a small synonym expansion.
 * Deterministic and dependency-free.
 *
 * The weights and the tie-break live in [LexicalScorer], one file, so the
 * scoring this class applies and the scoring the eval benchmark replicates
 * cannot drift apart. This class exists to turn a score into a ranked,
 * truncated list and nothing else.
 */
class LexicalToolSelector : ToolSelector {

    override fun select(
        task: String,
        sessionKeywords: List<String>,
        available: List<AgentTool>,
        maxTools: Int,
    ): List<AgentTool> = LexicalScorer.rank(
        task = task,
        sessionKeywords = sessionKeywords,
        available = available,
        maxTools = maxTools,
        table = LexicalNormalizer.synonymTable,
    )
}

/**
 * Truncates a tool observation to the model-visible budget.
 * Deterministic: never throws, always returns valid text.
 */
object ObservationTruncator {
    const val DEFAULT_BUDGET_CHARS = 2048

    fun truncate(observation: String, budget: Int = DEFAULT_BUDGET_CHARS): String {
        if (budget <= 0) return ""
        if (observation.length <= budget) return observation
        // The suffix must fit inside the budget, and a budget smaller than the
        // suffix must not produce a negative take().
        val marker = "…[truncated]"
        if (budget <= marker.length) return observation.take(budget)
        val head = observation.take(budget - marker.length)
        return head + marker
    }
}

/** Validates a tool call against the registry before execution. */
object ToolCallValidator {
    /** Cap on how many offending argument names are echoed back to the model. */
    const val MAX_NAMED_ARGS = 8

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

        val properties = tool.definition.schema["properties"]
            ?.let { it as? JsonObject }
            ?.keys
            ?: emptySet()

        val unexpected = args.keys.filter { it !in properties }
        if (unexpected.isNotEmpty()) {
            // A confused model can emit dozens of invented argument names. This
            // observation goes straight back into the prompt, so it is capped like
            // any other model-visible output — a 4KB rejection would blow the
            // context budget on the very turn that is already going wrong.
            val named = unexpected.take(MAX_NAMED_ARGS).joinToString()
            val more = if (unexpected.size > MAX_NAMED_ARGS) {
                " (+${unexpected.size - MAX_NAMED_ARGS} more)"
            } else {
                ""
            }
            return Result.Rejected(
                ObservationTruncator.truncate(
                    "Tool \"$name\" got unexpected argument(s): $named$more. " +
                        "It accepts: ${properties.joinToString()}"
                )
            )
        }
        // Unavailable tools (repeated failures) are rejected before re-execution.
        if (registry.byName(name) == null) {
            return Result.Rejected("Tool \"$name\" is not available.")
        }
        return Result.Valid(tool)
    }
}
