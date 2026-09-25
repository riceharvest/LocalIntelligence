package dev.localintelligence.android.di

import dev.localintelligence.core.tool.AgentTool
import dev.localintelligence.core.tool.SimpleToolRegistry
import dev.localintelligence.core.tool.ToolRegistry
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * The registry as a *policy*, split from the registry as a *value*.
 *
 * WHY this is an interface and not a `List<AgentTool>` constructor parameter:
 * most real tools cannot be instantiated on a plain JVM. `AppsListTool` and
 * `FilesListTool` take a `android.content.Context`, and the `Context` stub on
 * the unit-test classpath throws on `applicationContext`, so a JVM test that
 * built the production list would fail on a framework limitation rather than on
 * a wiring bug. Splitting "where tools come from" out of "how a registry is
 * assembled and checked" is what lets this wiring be tested for real on the JVM,
 * in seconds, with no emulator — the same trade `docs/architecture.md` §2 makes
 * for `:core`.
 *
 * Production wires [AndroidToolSource]. Tests wire a list. Neither uses
 * reflection.
 */
fun interface ToolSource {
    fun tools(): List<AgentTool>
}

/**
 * The validated result of assembling a registry.
 *
 * Carries the diagnostics rather than only throwing, because a duplicate tool
 * name is a *merge* failure: two agents registered the same name, and whoever
 * fixes it needs to know which two. `SimpleToolRegistry` throws with the names
 * but that would take down app start for what is a one-line registration fix.
 */
data class ToolRegistryReport(
    val registry: ToolRegistry,
    val duplicates: List<String>,
    val malformed: List<String>,
) {
    val isWellFormed: Boolean get() = duplicates.isEmpty() && malformed.isEmpty()
}

/**
 * Assembles and checks the tool registry.
 *
 * This is the one part of the graph that is pure data in and pure data out,
 * which is why it has no `Context` and why the whole duplicate-name matrix runs
 * on the JVM.
 */
object ToolRegistryFactory {

    /**
     * Builds a registry from [source], reporting rather than throwing on
     * contract violations.
     *
     * The checks are the ones a *wiring* bug can cause, deliberately not the
     * ones a tool author causes. A tool with a weak description is that
     * author's bug, but two tools sharing a name is a wiring bug, and only the
     * wiring layer can see both.
     */
    fun assemble(source: ToolSource): ToolRegistryReport {
        val tools = source.tools()
        val duplicates = tools
            .groupBy { it.definition.name }
            .filterValues { it.size > 1 }
            .keys
            .sorted()
        val malformed = tools
            .mapNotNull { problemWith(it.definition.name, it.definition.description, it.definition.category, it.definition.schema) }
            .sorted()

        // Only build the real registry when names are unique: SimpleToolRegistry
        // requires uniqueness and would throw, and a caller reading a report
        // should not have to catch an exception to see the diagnostics.
        val registry = if (duplicates.isEmpty()) SimpleToolRegistry(tools) else NoRegistry
        return ToolRegistryReport(registry, duplicates, malformed)
    }

    /**
     * One sentence per contract violation, or null when the tool is fine.
     *
     * Takes the definition's fields rather than the tool so the same checks run
     * over a [dev.localintelligence.core.tool.ToolDefinition] that no tool
     * instance was needed to produce — which is how the JVM suite validates the
     * real registration list without a `Context`.
     */
    fun problemWith(
        name: String,
        description: String,
        category: String,
        schema: JsonObject,
    ): String? {
        val problems = mutableListOf<String>()

        if (name.isBlank()) problems += "name is blank"
        if (!NAME_SHAPE.matches(name)) problems += "name '$name' is not lowercase verb.noun"
        if (description.isBlank()) problems += "description is blank"
        if (category.isBlank()) problems += "category is blank"

        if (schema["type"]?.jsonPrimitive?.content != "object") problems += "schema type is not object"
        val properties = schema["properties"]
        if (properties !is JsonObject) problems += "schema has no properties object"
        if (schema["required"] !is JsonArray) problems += "schema has no required array"
        if (properties is JsonObject) {
            // A property with no type is un-constrainable: the validator accepts
            // any value and the tool must defend against any shape at runtime.
            // Cheap to catch here, expensive to debug from a bad tool result.
            properties.forEach { (key, value) ->
                val type = ((value as? JsonObject)?.get("type") as? JsonPrimitive)?.content
                if (type.isNullOrBlank()) problems += "property '$key' has no type"
            }
        }

        return if (problems.isEmpty()) null else "$name: ${problems.joinToString("; ")}"
    }

    /** `docs/tool-contract.md`: dotted, lowercase, `verb.noun`, noun may be two words. */
    private val NAME_SHAPE = Regex("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+$")
}

/**
 * Stands in for the real registry when [ToolSource] produced duplicate names.
 *
 * Exists so [ToolRegistryFactory.assemble] can return a usable value alongside
 * its diagnostics instead of throwing. Empty rather than partial on purpose: a
 * caller that ignores the report and runs anyway gets a model that calls no
 * tools — a visible no-op — rather than a silently shadowed duplicate.
 */
object NoRegistry : ToolRegistry {
    override fun all(): List<AgentTool> = emptyList()
    override fun byName(name: String): AgentTool? = null
}
