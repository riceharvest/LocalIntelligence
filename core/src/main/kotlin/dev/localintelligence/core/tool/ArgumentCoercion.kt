package dev.localintelligence.core.tool

import dev.localintelligence.core.model.ToolArgs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Fixes the argument types a small model gets wrong, driven by the tool's own
 * JSON schema, before the schema check sees the call.
 *
 * WHY HERE AND NOT IN EACH TOOL: a 1-3B model writes `"3"` for an integer, `"true"`
 * for a boolean and a bare string where a list belongs. Each tool used to parse
 * those itself, or reject them, so the same mistake was fixed in some tools and
 * not others. The schema already says what each argument is, so one function
 * reads it once for every tool. Tools keep their own defensive parsing; this only
 * removes the common cases before they reach it.
 *
 * ## The rules, and what is deliberately not converted
 *
 *  - `integer` / `number` from a string that is exactly a number (`"3"`, `" -2.5 "`).
 *    `"3.0"` is not an integer and stays a string, so the schema check reports it.
 *  - `boolean` from exactly `"true"` or `"false"`, in any case.
 *  - `object` from a string that is a JSON object, and `array` from a string that is
 *    a JSON array.
 *  - `array` from a bare string: a one-item list, with the item coerced to the item
 *    schema.
 *  - Nothing else. A number is never made a string, a list is never made a number,
 *    and an unparseable string is left as it was. A property with no `type`, a list
 *    of types, or `anyOf`/`oneOf` is left alone, because the right type is not
 *    known.
 *
 * Returns [args] itself when nothing changed, so the common path allocates nothing.
 */
object ArgumentCoercion {
    private val INTEGER = Regex("-?\\d{1,18}")
    private val NUMBER = Regex("-?\\d+(\\.\\d+)?([eE][-+]?\\d+)?")

    /** Reads the JSON a string may hold. A parse failure leaves the string as it was. */
    private val json = Json

    /** Coerces [args] to the argument types [schema] declares. */
    fun coerce(schema: JsonObject, args: ToolArgs): ToolArgs = coerceObject(schema, args)

    private fun coerceObject(schema: JsonObject, args: JsonObject): JsonObject {
        val properties = schema["properties"] as? JsonObject ?: return args
        var changed = false
        val out = LinkedHashMap<String, JsonElement>(args.size)
        for ((key, value) in args) {
            val spec = properties[key] as? JsonObject
            val fixed = if (spec == null) value else coerceValue(spec, value)
            if (fixed !== value) changed = true
            out[key] = fixed
        }
        return if (changed) JsonObject(out) else args
    }

    private fun coerceValue(spec: JsonObject, value: JsonElement): JsonElement {
        val type = (spec["type"] as? JsonPrimitive)?.contentOrNull ?: return value
        return when (type) {
            "integer" -> stringOf(value)?.trim()?.takeIf { INTEGER.matches(it) }
                ?.let { JsonPrimitive(it.toLong()) } ?: value

            "number" -> stringOf(value)?.trim()?.takeIf { NUMBER.matches(it) }
                ?.let { JsonPrimitive(it.toDouble()) } ?: value

            "boolean" -> stringOf(value)?.trim()?.lowercase()?.let { text ->
                when (text) {
                    "true" -> JsonPrimitive(true)
                    "false" -> JsonPrimitive(false)
                    else -> null
                }
            } ?: value

            "object" -> when {
                value is JsonObject -> coerceObject(spec, value)
                else -> parseJson(value)?.takeIf { it is JsonObject }?.let { coerceObject(spec, it as JsonObject) }
                    ?: value
            }

            "array" -> when (value) {
                is JsonArray -> coerceItems(spec, value)
                // A JSON array in a string is parsed; any other string is the one item.
                is JsonPrimitive -> if (value.isString) {
                    val text = value.content.trim()
                    if (text.startsWith("[")) {
                        parseJson(value)?.takeIf { it is JsonArray }?.let { coerceItems(spec, it as JsonArray) }
                            ?: value
                    } else {
                        val itemSpec = spec["items"] as? JsonObject
                        JsonArray(listOf(if (itemSpec == null) value else coerceValue(itemSpec, value)))
                    }
                } else {
                    value
                }

                else -> value
            }

            else -> value
        }
    }

    private fun coerceItems(spec: JsonObject, array: JsonArray): JsonArray {
        val itemSpec = spec["items"] as? JsonObject ?: return array
        var changed = false
        val out = array.map { item ->
            coerceValue(itemSpec, item).also { if (it !== item) changed = true }
        }
        return if (changed) JsonArray(out) else array
    }

    /** The text of a JSON string value, or null for any other value. */
    private fun stringOf(value: JsonElement): String? =
        (value as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun parseJson(value: JsonElement): JsonElement? {
        val text = stringOf(value) ?: return null
        return try {
            json.parseToJsonElement(text.trim())
        } catch (e: Exception) {
            null
        }
    }
}
