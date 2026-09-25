package dev.localintelligence.android.di

import dev.localintelligence.android.tools.notifications.NotificationListTool
import dev.localintelligence.android.tools.web.WebFetchTool
import dev.localintelligence.core.tool.AgentTool
import dev.localintelligence.core.tool.ToolRisk
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Test doubles and a fake tool, shared across the di tests.
 *
 * The fake tool is a real [AgentTool] rather than a mock because these tests
 * assert on [dev.localintelligence.core.tool.ToolDefinition] and on what the
 * registry does with a list — a mock would only prove that the mock was
 * configured the way the test expected.
 */
internal class FakeTool(
    name: String,
    description: String = "Does a thing and returns the result.",
    category: String = "fake",
    // A contract-valid no-argument schema. `docs/tool-contract.md` requires
    // "properties" AND "required"; a fixture that quietly omitted `required`
    // would make every test that uses it assert on a malformed tool.
    schema: JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject { })
        put("required", buildJsonArray { })
    },
    private val result: String = "ok",
) : AgentTool {
    override val definition = dev.localintelligence.core.tool.ToolDefinition(
        name = name,
        description = description,
        category = category,
        schema = schema,
        risk = ToolRisk.READ_ONLY,
    )

    override suspend fun execute(
        args: dev.localintelligence.core.model.ToolArgs,
        context: dev.localintelligence.core.tool.ToolContext,
    ) = dev.localintelligence.core.tool.ToolResult(success = true, observation = result)
}

/**
 * A tool whose schema is missing the `required` array, which
 * `docs/tool-contract.md` makes mandatory. Used to prove the factory reports
 * contract violations instead of letting them through to the model.
 */
internal fun malformedSchemaTool(name: String = "fake.broken"): FakeTool = FakeTool(
    name = name,
    schema = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("untyped", buildJsonObject { put("description", "no type here") })
        })
        // no "required"
    },
)

/** The two real wave-2 tools whose constructors need no `Context`. */
internal fun contextFreeRealTools(): List<AgentTool> = listOf(
    WebFetchTool(),
    NotificationListTool(),
)

/** Builds a `JsonObject` property node with a type, for schema assertions. */
internal fun typedProperty(type: String, description: String = "d"): JsonObject =
    buildJsonObject {
        put("type", JsonPrimitive(type))
        put("description", JsonPrimitive(description))
    }
