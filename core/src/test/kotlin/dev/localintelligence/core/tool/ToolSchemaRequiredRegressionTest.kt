package dev.localintelligence.core.tool

import dev.localintelligence.core.model.ToolArgs
import dev.localintelligence.core.tool.catalogue.V0ToolCatalogue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The repo-wide guard against a malformed `required` field.
 *
 * ## The bug this stops
 *
 * Eight Android tool definitions emitted `required` as a JSON **object**:
 *
 * ```kotlin
 * put("required", buildJsonObject {})                       // {}
 * put("required", buildJsonObject { put("uri", "...") })    // a description map
 * ```
 *
 * JSON Schema requires an **array of strings**. The object form is ignored by every
 * consumer — [dev.localintelligence.core.model.GrammarBuilder] reads it with
 * `as? JsonArray`, gets `null`, and builds a grammar in which no argument is
 * mandatory — so the tool could be called without an argument it cannot work
 * without, and the failure surfaced as a runtime error rather than a schema error.
 *
 * ## Why a test and not a review rule
 *
 * The defect is invisible at the call site: it compiles, it type-checks,
 * `buildJsonObject` is happy, and no existing test went red when it was
 * introduced. So the check has to be mechanical and total. This test walks EVERY
 * registered [ToolDefinition] in :core, and [AndroidToolSchemaSourceTest] in :android
 * walks every schema in the Android tool sources — a new tool written by a new agent
 * is covered the day it lands, with no one having to remember.
 *
 * Both tests are deliberately *total* rather than a hardcoded list of the eight
 * known-bad tools: a list would pass the moment a ninth tool got it wrong.
 */
class ToolSchemaRequiredRegressionTest {

    /** Every tool definition the core module registers, from any source. */
    private fun allRegisteredDefinitions(): List<ToolDefinition> =
        V0ToolCatalogue.definitions

    // ------------------------------------------------------- the repo-wide guard

    @Test
    fun `no registered tool ships a required that is not a JSON array of strings`() {
        val offenders = allRegisteredDefinitions().mapNotNull { def ->
            val required = def.schema["required"]
            // The specific P0: an object where JSON Schema demands an array.
            if (required != null && required !is JsonArray) {
                "${def.name}: \"required\" is ${required::class.simpleName} ($required), " +
                    "not a JSON array"
            } else {
                null
            }
        }
        assertEquals(
            "A tool schema declares \"required\" as something other than a JSON array. " +
                "JSON Schema requires an array of strings; an object is a description " +
                "map that every consumer silently ignores, so the tool accepts calls " +
                "that omit a mandatory argument. Emit putJsonArray(\"required\") { ... }.",
            emptyList<String>(),
            offenders,
        )
    }

    @Test
    fun `every registered tool satisfies the full schema contract`() {
        val offenders = allRegisteredDefinitions().flatMap { def ->
            ToolSchemaValidator.violations(def.name, def.schema).map { "${def.name}: $it" }
        }
        assertEquals(
            "Tool schema structural violations. See ToolSchemaValidator for the rules " +
                "and why each one fails silently.",
            emptyList<String>(),
            offenders,
        )
    }

    @Test
    fun `every entry in required exists in properties`() {
        val offenders = allRegisteredDefinitions().mapNotNull { def ->
            val properties = def.schema["properties"] as? JsonObject ?: return@mapNotNull null
            val dangling = ToolSchemaValidator.requiredArguments(def.schema)
                ?.filterNot { it in properties }
                ?: return@mapNotNull null
            if (dangling.isEmpty()) null else "${def.name}: required names not in properties: $dangling"
        }
        assertEquals(
            "A tool requires an argument its own schema never declares, describing a " +
                "call the model can never satisfy.",
            emptyList<String>(),
            offenders,
        )
    }

    @Test
    fun `every tool declares additionalProperties`() {
        val offenders = allRegisteredDefinitions()
            .filterNot { it.schema["additionalProperties"] is JsonPrimitive }
            .map { it.name }
        assertEquals(
            "A tool schema omits \"additionalProperties\", so the schema and " +
                "ToolCallValidator's unknown-argument rejection can disagree.",
            emptyList<String>(),
            offenders,
        )
    }

    // ------------------------------------- the validator itself, on known shapes

    @Test
    fun `the validator rejects the object form that caused the P0`() {
        val objectForm = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject { put("query", buildJsonObject { put("type", "string") }) })
            // The bug: a description map, not a constraint.
            put("required", buildJsonObject { put("query", "A substring of the file name.") })
            put("additionalProperties", false)
        }
        val problems = ToolSchemaValidator.violations("files.search", objectForm)
        assertEquals(
            "An object-valued \"required\" must be reported as a violation",
            1,
            problems.size,
        )
        assertTrue(
            "the violation must name the array requirement, got: ${problems.single()}",
            problems.single().contains("must be a JSON array"),
        )
        assertEquals(
            "an object-valued \"required\" yields no mandatory arguments, which is the " +
                "silent failure the grammar inherits",
            null,
            ToolSchemaValidator.requiredArguments(objectForm),
        )
    }

    @Test
    fun `an empty array is valid and means nothing is mandatory`() {
        val optionalOnly = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject { put("limit", buildJsonObject { put("type", "integer") }) })
            putJsonArray("required") { }
            put("additionalProperties", false)
        }
        assertEquals(
            "an empty required array is correct JSON Schema, not an omission",
            emptyList<String>(),
            ToolSchemaValidator.violations("files.list", optionalOnly),
        )
        assertEquals(
            emptyList<String>(),
            ToolSchemaValidator.requiredArguments(optionalOnly),
        )
    }

    @Test
    fun `a populated array of strings is valid`() {
        val mandatory = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject { put("uri", buildJsonObject { put("type", "string") }) })
            putJsonArray("required") { add("uri") }
            put("additionalProperties", false)
        }
        assertEquals(
            emptyList<String>(),
            ToolSchemaValidator.violations("files.read_text", mandatory),
        )
        assertEquals(
            listOf("uri"),
            ToolSchemaValidator.requiredArguments(mandatory),
        )
    }

    @Test
    fun `a required name with no matching property is reported`() {
        val dangling = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject { put("limit", buildJsonObject { put("type", "integer") }) })
            putJsonArray("required") { add("nope") }
            put("additionalProperties", false)
        }
        assertTrue(
            "a required name absent from properties must be reported, got: " +
                ToolSchemaValidator.violations("t", dangling),
            ToolSchemaValidator.violations("t", dangling)
                .any { it.contains("not declared in properties") },
        )
    }

    @Test
    fun `a missing required field is reported`() {
        val absent = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject { put("query", buildJsonObject { put("type", "string") }) })
            put("additionalProperties", false)
        }
        assertTrue(
            "an absent \"required\" must be reported so the two dialects cannot diverge",
            ToolSchemaValidator.violations("t", absent).any { it.contains("\"required\" is missing") },
        )
        assertEquals(null, ToolSchemaValidator.requiredArguments(absent))
    }

    // --------------------------------- the test that would have caught the P0

    @Test
    fun `a tool with a mandatory argument rejects a call that omits it`() {
        val definition = ToolDefinition(
            name = "files.read_text",
            description = "Read the beginning of a text document.",
            category = "files",
            schema = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("uri", buildJsonObject { put("type", "string") })
                })
                putJsonArray("required") { add("uri") }
                put("additionalProperties", false)
            },
            risk = ToolRisk.READ_ONLY,
        )
        val tool = RejectingMissingRequiredTool(definition)

        // The declaration is readable, which is the whole point: before the fix the
        // object form made this list unreadable and the omission undetectable here.
        assertEquals(
            listOf("uri"),
            ToolSchemaValidator.requiredArguments(definition.schema),
        )

        val omitted = tool.validate(buildJsonObject { })
        assertTrue(
            "a call omitting the mandatory \"uri\" must be rejected, got: $omitted",
            omitted.startsWith("REJECTED"),
        )

        val supplied = tool.validate(buildJsonObject { put("uri", "content://doc/1") })
        assertEquals("ACCEPTED", supplied)
    }

    /**
     * Stands in for a tool whose contract is "at least these arguments are
     * mandatory". Mirrors what a runtime check does with the declared `required`,
     * which is the consumer the P0 disabled.
     */
    private class RejectingMissingRequiredTool(
        override val definition: ToolDefinition,
    ) : AgentTool {
        override suspend fun execute(args: ToolArgs, context: ToolContext): ToolResult =
            ToolResult(success = true, observation = "not exercised")

        fun validate(args: ToolArgs): String {
            val required = ToolSchemaValidator.requiredArguments(definition.schema)
                ?: return "REJECTED: schema does not declare required"
            val missing = required.filterNot { it in args }
            return if (missing.isEmpty()) "ACCEPTED" else "REJECTED: missing $missing"
        }
    }
}
