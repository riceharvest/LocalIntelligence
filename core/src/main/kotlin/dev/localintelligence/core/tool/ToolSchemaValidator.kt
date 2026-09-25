package dev.localintelligence.core.tool

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The structural contract every [ToolDefinition] schema must satisfy, in one place.
 *
 * ## Why this exists
 *
 * JSON Schema defines `required` as an **array of strings**. Eight Android tools
 * emitted it as an object instead:
 *
 * ```kotlin
 * put("required", buildJsonObject {})        // {}  -- not valid JSON Schema
 * put("required", buildJsonObject {          // {"uri": "A content:// document URI."}
 *     put("uri", "A content:// document URI.")
 * })
 * ```
 *
 * That second form is a *description map*, not a constraint. The failure is silent
 * rather than loud, because every consumer of the field is written defensively:
 *
 *  - [dev.localintelligence.core.model.GrammarBuilder] reads
 *    `schema["required"] as? JsonArray`. An object does not cast, so it yields
 *    `null`, `.orEmpty()` swallows it, and the grammar is built as if the tool took
 *    no mandatory arguments. Every key in the grammar then becomes optional, so a
 *    grammar-constrained backend is never forced to emit the argument the tool
 *    actually needs.
 *  - [V0ToolCatalogue]-style coherence checks report `required` only when it is
 *    already a [JsonArray], so a tool written this way is skipped rather than
 *    flagged.
 *
 * Nothing throws, no test goes red, and the tool is simply allowed to receive calls
 * that omit an argument it cannot work without — surfacing later as a confusing
 * runtime error instead of a schema error at the point of the mistake.
 *
 * ## What it checks
 *
 * The four rules below are the ones whose violation is silent:
 *
 *  1. `required` is present, and is a JSON **array** (empty is correct and valid).
 *  2. every entry in `required` is a JSON string.
 *  3. every entry in `required` names a property that actually exists — a required
 *     key with no matching property describes a call the model can never satisfy.
 *  4. `additionalProperties` is declared, so the schema and
 *     [ToolCallValidator]'s unknown-argument rejection cannot disagree.
 *
 * Every method returns a value rather than throwing, so a report can print all
 * violations at once instead of one per run.
 */
object ToolSchemaValidator {

    /**
     * Every structural violation in [schema], in a form fit for an assertion
     * message. Empty means the schema is well-formed.
     *
     * [toolName] is used only to make each message locatable.
     */
    fun violations(toolName: String, schema: JsonObject): List<String> {
        val problems = mutableListOf<String>()

        val properties = schema["properties"]
        if (properties !is JsonObject) {
            problems += "\"properties\" must be a JSON object"
            // Without properties, rule 3 cannot be evaluated meaningfully.
            return problems
        }

        // -- 1. required present and an array ---------------------------------
        val required = schema["required"]
        if (required == null) {
            problems += "\"required\" is missing; emit an array, empty if nothing is mandatory"
        } else if (required !is JsonArray) {
            problems += "\"required\" must be a JSON array of strings, but was " +
                "${required::class.simpleName} (\"${required}\"). " +
                "A description map here is silently ignored by every consumer."
        }

        if (required is JsonArray) {
            // -- 2. every entry is a string -----------------------------------
            required.forEachIndexed { index, element ->
                if (element !is JsonPrimitive || !element.isString) {
                    problems += "required[$index] is not a JSON string: $element"
                }
            }
            // -- 3. every entry exists in properties --------------------------
            required.filterIsInstance<JsonPrimitive>()
                .filter { it.isString }
                .map { it.content }
                .filterNot { it in properties }
                .forEach { problems += "required \"$it\" is not declared in properties" }
        }

        // -- 4. additionalProperties declared ---------------------------------
        if (schema["additionalProperties"] !is JsonPrimitive) {
            problems += "\"additionalProperties\" is missing; declare false so the schema " +
                "agrees with ToolCallValidator's unknown-argument rejection"
        }

        return problems
    }

    /**
     * The mandatory argument names in [schema], or `null` when `required` is absent
     * or is not an array.
     *
     * `null` is deliberately distinct from an empty list: "no arguments are
     * mandatory" and "this schema does not say" are different facts, and conflating
     * them is how the eight-tool bug stayed invisible.
     */
    fun requiredArguments(schema: JsonObject): List<String>? {
        val required = schema["required"] ?: return null
        if (required !is JsonArray) return null
        return required.filterIsInstance<JsonPrimitive>()
            .filter { it.isString }
            .map { it.content }
    }

    /** True when [schema] has no structural violations. */
    fun isValid(schema: JsonObject): Boolean = violations("tool", schema).isEmpty()
}
