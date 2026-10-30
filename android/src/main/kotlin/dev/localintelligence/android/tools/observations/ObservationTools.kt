package dev.localintelligence.android.tools.observations

import dev.localintelligence.android.tools.device.ArgCoerce
import dev.localintelligence.core.model.ObservationOrigin
import dev.localintelligence.core.model.ToolArgs
import dev.localintelligence.core.tool.AgentTool
import dev.localintelligence.core.tool.ObservationReader
import dev.localintelligence.core.tool.ObservationStore
import dev.localintelligence.core.tool.ToolContext
import dev.localintelligence.core.tool.ToolDefinition
import dev.localintelligence.core.tool.ToolError
import dev.localintelligence.core.tool.ToolResult
import dev.localintelligence.core.tool.ToolRisk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

// =====================================================================================
// observations.read: the rest of a tool result that was too long to show.
//
// A long result reaches the model as a head-and-tail preview ending in
// `[obs#41: 18,240 chars. More: observations.read with id 41 ...]`. This tool reads
// the stored full text back: the passages matching a query, or the text from an
// offset. The rendering is ObservationReader's, in :core.
//
// The stored text is whatever the tool returned, and a web page or a notification is
// text a third party wrote, so the observation is declared NETWORK: fenced as quoted
// content, NOT instructions, the same way web.fetch is.
// =====================================================================================

private const val MAX_QUERY_CHARS = 200

private val WHITESPACE = Regex("\\s+")

/** `obs#41`, `#41`, `41`: the number in whatever form a model writes it. */
private val ID_DIGITS = Regex("\\d{1,18}")

/** `observations.read`: part of a stored tool result, by query or by offset. */
class ObservationReadTool(private val store: ObservationStore) : AgentTool {

    override val definition = ToolDefinition(
        name = ObservationStore.READ_TOOL,
        description = "Reads more of a long tool result that was cut short, by its obs number. " +
            "Returns the passages matching a query, or the text from an offset.",
        category = "observations",
        schema = SCHEMA,
        risk = ToolRisk.READ_ONLY,
        observationOrigin = ObservationOrigin.NETWORK,
        tags = setOf(
            "obs", "read more", "rest of", "more of the page", "cut short", "truncated", "next part",
        ),
        requiredPermission = null,
    )

    override suspend fun execute(args: ToolArgs, context: ToolContext): ToolResult {
        if (context.signal.isCancelled()) {
            return ToolResult(
                success = false,
                observation = "Cancelled before the saved result was read.",
                error = ToolError.Cancelled("cancelled before observations.read"),
            )
        }
        val id = idOf(args) ?: return invalid(
            "id is required: the number after obs#, for example 41 for obs#41.",
        )
        val query = (args["query"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
            ?.replace(WHITESPACE, " ")?.trim()?.take(MAX_QUERY_CHARS)?.takeIf { it.isNotEmpty() }
        val offset = intOf(args, "offset")?.coerceAtLeast(0) ?: 0
        val maxChars = (intOf(args, "max_chars") ?: ObservationStore.MAX_READ_CHARS)
            .coerceIn(ObservationReader.MIN_READ_CHARS, ObservationStore.MAX_READ_CHARS)

        val text = withContext(Dispatchers.IO) { store.read(id, query, offset, maxChars) }
            ?: return ToolResult(
                success = false,
                observation = "There is no saved result obs#$id. Only the newest " +
                    "${ObservationStore.MAX_KEPT} are kept; use an obs number shown in this chat.",
                error = ToolError.NotFound("obs#$id"),
            )
        return ToolResult(
            success = true,
            observation = text,
            data = buildJsonObject {
                put("id", id)
                put("offset", offset)
                if (query != null) put("query", query)
            },
        )
    }

    private fun invalid(reason: String) = ToolResult(
        success = false,
        observation = reason,
        error = ToolError.InvalidArguments(reason),
    )

    private companion object {
        /** The id as a number, from `41`, `"41"`, `"obs#41"` or `41.0`. Null when there is none. */
        fun idOf(args: ToolArgs): Long? {
            val raw = args["id"] as? JsonPrimitive ?: return null
            if (raw is JsonNull) return null
            val text = raw.content.trim()
            val id = text.toLongOrNull()
                ?: text.toDoubleOrNull()?.takeIf { it.isFinite() }?.toLong()
                ?: ID_DIGITS.find(text)?.value?.toLongOrNull()
            return id?.takeIf { it > 0 }
        }

        /**
         * An integer argument, tolerating `"6,000"`: the read results print counts with
         * thousands separators, and a model may copy one. Null when absent or unreadable,
         * so the caller's default applies.
         */
        fun intOf(args: ToolArgs, key: String): Int? {
            val raw = args[key] as? JsonPrimitive ?: return null
            if (raw is JsonNull) return null
            val text = raw.content.trim().replace(",", "").replace("_", "")
            val value = text.toLongOrNull() ?: text.toDoubleOrNull()?.takeIf { it.isFinite() }?.toLong()
            return value?.coerceIn(-ArgCoerce.MAX_ABS_INT.toLong(), ArgCoerce.MAX_ABS_INT.toLong())?.toInt()
        }

        val SCHEMA: ToolArgs = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("id") {
                    put("type", "integer")
                    put("minimum", 1)
                    put("description", "The obs number, for example 41 for obs#41.")
                }
                putJsonObject("query") {
                    put("type", "string")
                    put("maxLength", MAX_QUERY_CHARS)
                    put("description", "Words to look for in it. Leave out to read in order.")
                }
                putJsonObject("offset") {
                    put("type", "integer")
                    put("minimum", 0)
                    put("description", "Character to start reading at, 0 by default. Not used with a query.")
                }
                putJsonObject("max_chars") {
                    put("type", "integer")
                    put("minimum", ObservationReader.MIN_READ_CHARS)
                    put("maximum", ObservationStore.MAX_READ_CHARS)
                    put("description", "Characters to return, ${ObservationStore.MAX_READ_CHARS} by default and at most.")
                }
            }
            putJsonArray("required") { add("id") }
        }
    }
}

/**
 * The saved-results tool family: one tool over [store]. The store is passed in so a
 * composition root that already holds one (the loop writes to it) can share it.
 */
fun observationTools(store: ObservationStore): List<AgentTool> = listOf(ObservationReadTool(store))
