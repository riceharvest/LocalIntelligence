package dev.localintelligence.core.tool.catalogue

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * A single JSON Schema builder for every tool in [V0ToolCatalogue].
 *
 * `required` is emitted as a JSON **array**, which is what JSON Schema specifies and
 * what the `required` field in `docs/tool-contract.md` means. Some early tool
 * implementations emitted it as an object (`{ "content": "..." }`); that is a
 * description map, not a constraint, and a model reading it is told nothing about
 * which arguments are mandatory. One builder means the whole catalogue cannot drift
 * into two dialects.
 *
 * `additionalProperties: false` is set globally. `ToolCallValidator` already rejects
 * unknown top-level argument names against `properties`, so this is the schema
 * saying the same thing to a grammar-constrained backend instead of letting the two
 * disagree.
 */
internal fun objSchema(
    required: List<String> = emptyList(),
    properties: JsonObjectBuilder.() -> Unit = {},
): JsonObject = buildJsonObject {
    put("type", "object")
    putJsonObject("properties", properties)
    putJsonArray("required") {
        required.forEach { add(it) }
    }
    put("additionalProperties", false)
}
