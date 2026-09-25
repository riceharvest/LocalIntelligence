package dev.pidroid.core.tool

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the frozen validator and truncator.
 *
 * The validator previously compared argument names against the schema's TOP-LEVEL
 * keys ("type", "properties", "required") instead of the keys inside "properties".
 * With a real JSON Schema that rejects every legitimate argument of every call.
 * Three independent workstreams hit this; these tests pin the fixed behaviour so
 * it cannot silently regress.
 */
class ToolCallValidatorTest {

    private fun tool(
        name: String,
        properties: Map<String, String>,
        required: List<String> = emptyList(),
    ): AgentTool = object : AgentTool {
        override val definition = ToolDefinition(
            name = name,
            description = "test tool",
            category = "test",
            schema = buildJsonObject {
                put("type", "object")
                put(
                    "properties",
                    buildJsonObject {
                        properties.forEach { (k, v) -> put(k, buildJsonObject {
                            put("type", JsonPrimitive(v))
                        }) }
                    },
                )
                put("required", buildJsonObject { })
                required.forEach { }
            },
            risk = ToolRisk.READ_ONLY,
        )

        override suspend fun execute(args: kotlinx.serialization.json.JsonObject, context: ToolContext) =
            ToolResult(success = true, observation = "ok")
    }

    private fun registryOf(vararg tools: AgentTool) = SimpleToolRegistry(tools.toList())

    @Test
    fun `accepts every argument declared in schema properties`() {
        val t = tool("calendar.search", mapOf("query" to "string", "days" to "integer"))
        val result = ToolCallValidator.validate(
            "calendar.search",
            buildJsonObject {
                put("query", "alice")
                put("days", 7)
            },
            listOf(t),
            registryOf(t),
        )
        assertTrue("legit arguments must be accepted, got $result", result is ToolCallValidator.Result.Valid)
    }

    @Test
    fun `accepts a tool with no declared properties and no args`() {
        val t = tool("device.battery", emptyMap())
        val result = ToolCallValidator.validate("device.battery", buildJsonObject { }, listOf(t), registryOf(t))
        assertTrue(result is ToolCallValidator.Result.Valid)
    }

    @Test
    fun `rejects an argument not declared in properties`() {
        val t = tool("calendar.search", mapOf("query" to "string"))
        val result = ToolCallValidator.validate(
            "calendar.search",
            buildJsonObject { put("nope", "x") },
            listOf(t),
            registryOf(t),
        )
        assertTrue("unknown arg must be rejected", result is ToolCallValidator.Result.Rejected)
        assertTrue(
            "rejection must name the offending argument",
            (result as ToolCallValidator.Result.Rejected).observation.contains("nope"),
        )
    }

    @Test
    fun `schema keywords are not treated as argument names`() {
        // "type"/"properties"/"required" are schema keywords. A tool that happens
        // to declare an argument literally named "type" is legitimate.
        val t = tool("files.search", mapOf("type" to "string", "query" to "string"))
        val result = ToolCallValidator.validate(
            "files.search",
            buildJsonObject { put("type", "pdf") },
            listOf(t),
            registryOf(t),
        )
        assertTrue(result is ToolCallValidator.Result.Valid)
    }

    @Test
    fun `rejects an unknown tool name`() {
        val t = tool("a.b", emptyMap())
        val result = ToolCallValidator.validate("a.zzz", buildJsonObject { }, listOf(t), registryOf(t))
        assertTrue(result is ToolCallValidator.Result.Rejected)
    }

    @Test
    fun `rejects a tool that is not in the registry`() {
        val t = tool("a.b", emptyMap())
        val result = ToolCallValidator.validate("a.b", buildJsonObject { }, listOf(t), SimpleToolRegistry())
        assertTrue(result is ToolCallValidator.Result.Rejected)
    }

    @Test
    fun `rejection observation is short enough to feed back to a model`() {
        val t = tool("a.b", mapOf("x" to "string"))
        val long = (1..200).joinToString(",") { "unexpectedArg$it" }
        val result = ToolCallValidator.validate(
            "a.b",
            buildJsonObject { long.split(",").forEach { put(it, "v") } },
            listOf(t),
            registryOf(t),
        )
        val obs = (result as ToolCallValidator.Result.Rejected).observation
        assertTrue("observation must respect the budget, was ${obs.length}", obs.length <= ObservationTruncator.DEFAULT_BUDGET_CHARS)
    }
}

class ObservationTruncatorTest {

    @Test
    fun `short observation is returned unchanged`() {
        assertEquals("hello", ObservationTruncator.truncate("hello"))
    }

    @Test
    fun `long observation is truncated within budget`() {
        val out = ObservationTruncator.truncate("x".repeat(10_000))
        assertTrue("was ${out.length}", out.length <= ObservationTruncator.DEFAULT_BUDGET_CHARS)
        assertTrue("truncation must be visible to the model", out.contains("[truncated]"))
    }

    @Test
    fun `tiny budget does not throw`() {
        // take(budget - 40) used to throw StringIndexOutOfBounds for budget < 40.
        for (budget in 0..64) {
            val out = ObservationTruncator.truncate("y".repeat(500), budget)
            assertTrue("budget=$budget produced ${out.length}", out.length <= budget.coerceAtLeast(0))
        }
    }

    @Test
    fun `zero and negative budgets return empty`() {
        assertEquals("", ObservationTruncator.truncate("anything", 0))
        assertEquals("", ObservationTruncator.truncate("anything", -5))
    }
}
