package dev.localintelligence.core.agent

import dev.localintelligence.core.model.ToolArgs
import dev.localintelligence.core.tool.AgentTool
import dev.localintelligence.core.tool.ToolRegistry
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The tool a model calls to hand a self-contained job to a subtask. The same name and tier
 * are in V0ToolCatalogue and on the Android tool; CatalogueAgreement checks both.
 *
 * WHY A FIXED NAME AND NOT A ROLE: a subtask is always the same thing here, one child run on the
 * resident model with a small step cap. There is no orchestrator tier to choose.
 */
const val SUBTASK_TOOL_NAME = "task.delegate"

/**
 * Steps a subtask may take before it must answer. Small on purpose: on a phone each step is a
 * prefill of the subtask's own prompt, and the parent waits for every one of them.
 */
const val SUBTASK_MAX_STEPS = 6

/**
 * The most characters of a subtask's answer that come back to the parent. The parent's context
 * gets this and nothing else from the subtask. Chosen here, not measured.
 */
const val SUBTASK_SUMMARY_CHARS = 600

/**
 * The tools a subtask may call: the parent's registry, narrowed to [names] when the model named
 * some, and never including [SUBTASK_TOOL_NAME].
 *
 * WHY NO NESTING IS ENFORCED HERE AND NOT ONLY IN THE LOOP: a child that could see
 * `task.delegate` could start a grandchild, and a grandchild's budget is not the parent's. Removing
 * the name from the registry means the validator rejects it as unknown, so no path reaches it.
 */
internal class SubtaskRegistry(
    private val base: ToolRegistry,
    private val names: Set<String>?,
) : ToolRegistry {
    private fun offered(tool: AgentTool): Boolean =
        tool.definition.name != SUBTASK_TOOL_NAME && (names == null || tool.definition.name in names)

    override fun all(): List<AgentTool> = base.all().filter(::offered)

    override fun byName(name: String): AgentTool? = base.byName(name)?.takeIf(::offered)
}

/** The `goal` argument: the job, as the model wrote it. Null when it is missing or blank. */
internal fun subtaskGoal(args: ToolArgs): String? =
    (args["goal"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

/**
 * The `tools` argument: a comma-separated list of tool names, or null for "the parent's tools".
 *
 * WHY A STRING AND NOT A JSON ARRAY: the grammar the model is held to is built from the schema, and
 * a string is the one argument shape every backend already handles. A list would be a second shape
 * the grammar must learn for one tool.
 */
internal fun subtaskToolNames(args: ToolArgs): Set<String>? {
    val raw = (args["tools"] as? JsonPrimitive)?.contentOrNull ?: return null
    return raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet().ifEmpty { null }
}

/** The subtask's answer, cut to [SUBTASK_SUMMARY_CHARS] with an ellipsis when it was longer. */
internal fun capSummary(text: String): String {
    val trimmed = text.trim()
    if (trimmed.length <= SUBTASK_SUMMARY_CHARS) return trimmed
    return trimmed.take(SUBTASK_SUMMARY_CHARS).trimEnd() + "…"
}
