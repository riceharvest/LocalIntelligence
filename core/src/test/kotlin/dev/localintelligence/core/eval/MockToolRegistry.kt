package dev.localintelligence.core.eval

import dev.localintelligence.core.model.ToolArgs
import dev.localintelligence.core.tool.AgentTool
import dev.localintelligence.core.tool.SimpleToolRegistry
import dev.localintelligence.core.tool.ToolRegistry
import dev.localintelligence.core.tool.ToolSelector
import kotlinx.serialization.json.JsonObject

/** Result of the validation gate. Mirrors `ValidationOutcome` from docs/wave1-contract.md. */
sealed interface GateOutcome {
    data class Ok(val tool: AgentTool) : GateOutcome
    data class Rejected(val observation: String) : GateOutcome
}

/**
 * Fun interface matching `ToolCallValidatorGate` in docs/wave1-contract.md.
 *
 * Declared structurally rather than imported so this file compiles before
 * AgentController lands. See [DocumentedArgumentGate] for the semantics.
 */
fun interface ToolCallValidatorGate {
    fun validate(name: String, args: ToolArgs, visible: List<AgentTool>): GateOutcome
}

/**
 * A [ToolRegistry] over scripted tools, plus a visibility-scoped selector.
 *
 * Visibility is the point: the harness must be able to say "the model may see
 * exactly these six tools" and then prove it called one of the eleven it
 * could not see. A registry that quietly returns everything would make every
 * tool-selection assertion vacuous.
 */
class MockToolRegistry(tools: List<ScriptedTool>) : ToolRegistry {

    private val delegate = SimpleToolRegistry(tools)

    /** Every tool in the registry, including ones a task hides from the model. */
    override fun all(): List<AgentTool> = delegate.all()

    override fun byName(name: String): AgentTool? = delegate.byName(name)

    /** The scripted doubles, in registration order, for call-sequence assertions. */
    val scripted: List<ScriptedTool> = tools

    fun scriptedNamed(name: String): ScriptedTool? = scripted.firstOrNull { it.definition.name == name }

    /** Every call received by every tool, ordered by tool then call index. */
    fun allCalls(): List<RecordedCall> = scripted.flatMap { it.calls }

    /** Total calls across the registry, including calls that threw. */
    val totalCalls: Int get() = scripted.sumOf { it.callCount }

    /** Calls repeating a byte-identical (name, args) pair within the same tool. */
    val duplicateCalls: Int get() = scripted.sumOf { it.duplicateCallCount }

    fun reset() = scripted.forEach { it.calls.clear() }

    /**
     * Visibility-scoped selector. Returns exactly [visible], in declaration
     * order, capped at [maxTools] — deterministic, with no lexical scoring that
     * could silently drop the tool a task needs.
     */
    fun selectorFor(visible: List<String>, maxTools: Int = 8): ToolSelector =
        ToolSelector { _, _, _, _ ->
            visible.mapNotNull { byName(it) }.take(maxTools)
        }

    /** The gate the loop uses. Delegates to [DocumentedArgumentGate]. */
    fun gate(): ToolCallValidatorGate = DocumentedArgumentGate(this)
}

/**
 * Argument validation with the semantics `docs/tool-contract.md` mandates:
 * unknown top-level argument keys are checked against `schema["properties"]`.
 *
 * NOTE — discrepancy filed, not fixed. The frozen
 * `dev.localintelligence.core.tool.ToolCallValidator.validate` checks
 * `args.keys.any { it !in tool.definition.schema.keys }`, i.e. against the
 * schema's own top-level keys (`type`, `properties`, `required`). Under a real
 * object schema that rejects *every* argument of *every* call, because no
 * argument is ever named "type" or "properties". The contract document says
 * the check belongs against `schema["properties"]`, which is what a tool
 * actually declares its arguments in.
 *
 * The frozen file is not mine to edit, and `ToolCallValidatorGate` exists in
 * wave-1 precisely so the loop does not depend on that object's shape. So the
 * harness injects this gate and the eval suite measures the documented
 * behaviour. Integration should reconcile the object with the contract.
 */
class DocumentedArgumentGate(private val registry: MockToolRegistry) : ToolCallValidatorGate {

    override fun validate(name: String, args: ToolArgs, visible: List<AgentTool>): GateOutcome {
        val tool = visible.firstOrNull { it.definition.name == name }
            ?: return GateOutcome.Rejected(
                "Unknown tool \"$name\". Available: " + visible.joinToString(", ") {
                    it.definition.name
                } + "."
            )

        val declared = declaredArgumentNames(tool.definition.schema)
        val unexpected = args.keys.filter { it !in declared }
        if (unexpected.isNotEmpty()) {
            return GateOutcome.Rejected(
                "Tool \"$name\" got unexpected argument(s): ${unexpected.joinToString(", ")}. " +
                    "Expected only: ${declared.sorted().joinToString(", ")}."
            )
        }

        // Repeated failures mark a tool unavailable; reject before re-execution.
        if (registry.byName(name) == null) {
            return GateOutcome.Rejected("Tool \"$name\" is not available.")
        }
        return GateOutcome.Ok(tool)
    }

    companion object {
        /** Argument names from `schema.properties`, falling back to top-level keys. */
        fun declaredArgumentNames(schema: JsonObject): Set<String> {
            val properties = schema["properties"] as? JsonObject
            if (properties != null) return properties.keys
            // Tolerates a schema that declares arguments at the top level.
            return schema.keys - setOf("type", "properties", "required", "description")
        }
    }
}
