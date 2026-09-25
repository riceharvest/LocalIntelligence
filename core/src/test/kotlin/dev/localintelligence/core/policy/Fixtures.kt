package dev.localintelligence.core.policy

import dev.localintelligence.core.model.ToolArgs
import dev.localintelligence.core.tool.AgentTool
import dev.localintelligence.core.tool.ToolContext
import dev.localintelligence.core.tool.ToolDefinition
import dev.localintelligence.core.tool.ToolResult
import dev.localintelligence.core.tool.ToolRisk
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * Builders for the tests in this package.
 *
 * WHY these exist rather than a shared test fixture: the whole point of the
 * policy engine is that a decision is a function of (definition, arguments,
 * config) and nothing else. A test that shares mutable state with its
 * neighbours cannot prove that, so every test builds its own inputs.
 */
object Fixtures {

    /** A tool definition with a real object schema, like the shipped tools. */
    fun tool(
        name: String,
        risk: ToolRisk,
        props: Map<String, String> = emptyMap(),
        required: List<String> = emptyList(),
        permission: String? = null,
        category: String = name.substringBefore('.', "test"),
    ): ToolDefinition = ToolDefinition(
        name = name,
        description = "Test tool $name.",
        category = category,
        schema = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put("properties", buildJsonObject {
                props.forEach { (key, type) ->
                    put(key, buildJsonObject { put("type", JsonPrimitive(type)) })
                }
            })
            put("required", buildJsonArray { required.forEach { add(JsonPrimitive(it)) } })
        },
        risk = risk,
        tags = setOf(category),
        requiredPermission = permission,
    )

    /** An argument blob from string arguments. */
    fun args(vararg pairs: Pair<String, Any?>): ToolArgs = buildJsonObject {
        for ((key, value) in pairs) {
            when (value) {
                null -> put(key, JsonNull)
                is String -> put(key, JsonPrimitive(value))
                is Int -> put(key, JsonPrimitive(value))
                is Long -> put(key, JsonPrimitive(value))
                is Boolean -> put(key, JsonPrimitive(value))
                is List<*> -> put(key, buildJsonArray { value.forEach { add(JsonPrimitive(it?.toString() ?: "")) } })
                is JsonElement -> put(key, value)
                else -> put(key, JsonPrimitive(value.toString()))
            }
        }
    }

    /** An argument blob from a raw JSON string. For genuinely malformed input. */
    fun rawArgs(json: String): ToolArgs = Json.parseToJsonElement(json) as JsonObject

    /** A [ToolDefinition] the registry accepts but that is never executed. */
    fun inertTool(definition: ToolDefinition): AgentTool = object : AgentTool {
        override val definition: ToolDefinition = definition
        override suspend fun execute(args: ToolArgs, context: ToolContext): ToolResult =
            ToolResult(success = true, observation = "executed")
    }

    /** Serialized view of a decision, for readable assertion failures. */
    fun render(decision: Decision): String =
        "${decision.toolName}[${decision.rule}] -> ${decision.outcome}: ${decision.justification}"
}

/** Minimal parser for the raw-JSON fixture. Deterministic and dependency-free. */
private object Json {
    fun parseToJsonElement(text: String): JsonElement =
        kotlinx.serialization.json.Json.parseToJsonElement(text)
}
