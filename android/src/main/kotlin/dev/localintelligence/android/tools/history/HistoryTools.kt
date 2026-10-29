package dev.localintelligence.android.tools.history

import android.content.Context
import dev.localintelligence.android.data.HistoryQueries
import dev.localintelligence.android.data.LocalIntelligenceDatabase
import dev.localintelligence.android.data.MessageDao
import dev.localintelligence.android.data.MessageEntity
import dev.localintelligence.android.tools.device.ArgCoerce
import dev.localintelligence.android.tools.device.ArgResult
import dev.localintelligence.android.tools.device.guarded
import dev.localintelligence.core.memory.MemoryIndex
import dev.localintelligence.core.memory.MemoryText
import dev.localintelligence.core.model.ObservationOrigin
import dev.localintelligence.core.model.ToolArgs
import dev.localintelligence.core.tool.AgentTool
import dev.localintelligence.core.tool.ToolContext
import dev.localintelligence.core.tool.ToolDefinition
import dev.localintelligence.core.tool.ToolError
import dev.localintelligence.core.tool.ToolResult
import dev.localintelligence.core.tool.ToolRisk
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// =====================================================================================
// HistoryTools: history.search, a read-only search over earlier chat messages.
//
// Lives in :android because the messages table is Room, and :core cannot see Room.
// Reads the user's and the assistant's text only. Tool observations are never searched:
// they are raw device output, and the model has no need to recall them.
//
// The observation is fenced as NETWORK origin (see the catalogue entry): an earlier
// assistant reply can repeat web text the model read at the time, so the result is
// treated as untrusted rather than as the user's own words.
// =====================================================================================

/** The snippet budget from the tool spec: one line per hit, at most this many characters. */
internal const val HISTORY_SNIPPET_CHARS = 160

private const val DEFAULT_RESULTS = 5
private const val MAX_RESULTS = 10
private const val MAX_QUERY_CHARS = 200

/**
 * Pure text rendering for [HistorySearchTool]. No android.* and no Room, so the decisions
 * here can be read on their own.
 */
object HistoryText {

    /**
     * One compact snippet around the first query term that appears in [text], at most
     * [HISTORY_SNIPPET_CHARS] characters, whitespace collapsed, with an ellipsis on each
     * side that was cut.
     */
    fun snippet(text: String, terms: List<String>): String {
        val collapsed = text.replace(WHITESPACE, " ").trim()
        val hit = terms
            .mapNotNull { term -> collapsed.indexOf(term, ignoreCase = true).takeIf { it >= 0 } }
            .minOrNull() ?: 0
        var start = (hit - 50).coerceAtLeast(0)
        if (start > 0 && Character.isLowSurrogate(collapsed[start])) start++
        val lead = if (start > 0) "…" else ""
        val body = collapsed.substring(start)
        if (lead.length + body.length <= HISTORY_SNIPPET_CHARS) return lead + body
        val room = HISTORY_SNIPPET_CHARS - lead.length - 1
        return lead + body.take(room).trimEnd() + "…"
    }

    /** `2026-10-12 — you: ...`. The role is kept so the model can tell what the user said. */
    fun line(date: String, role: String, snippet: String): String = "$date — $role: $snippet"

    fun roleOf(kind: String): String = if (kind == "user") "you" else "assistant"

    private val WHITESPACE = Regex("\\s+")
}

/**
 * `history.search`: searches the messages table and returns the best matches, newest
 * first among equal scores.
 */
class HistorySearchTool(
    private val messages: Lazy<MessageDao>,
    private val zone: ZoneId = ZoneId.systemDefault(),
) : AgentTool {

    private val dateFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(zone)

    override val definition = ToolDefinition(
        name = "history.search",
        description = "Searches earlier conversations for words and returns the matching " +
            "messages with their dates. Use when the user refers to something said before.",
        category = "history",
        schema = SCHEMA,
        risk = ToolRisk.READ_ONLY,
        observationOrigin = ObservationOrigin.NETWORK,
        tags = setOf(
            "remember when", "last time", "we talked about", "earlier conversation",
            "history", "earlier", "before", "previous chat",
        ),
        requiredPermission = null,
    )

    override suspend fun execute(args: ToolArgs, context: ToolContext): ToolResult {
        if (context.signal.isCancelled()) {
            return ToolResult(
                success = false,
                observation = "Cancelled before the conversation history could be searched.",
                error = ToolError.Cancelled("cancelled before history.search"),
            )
        }

        val query = when (val parsed = ArgCoerce.string(args, "query")) {
            is ArgResult.Present -> parsed.value.trim().take(MAX_QUERY_CHARS)
            is ArgResult.Missing -> return invalid("query is required: the words to look for in earlier chats.")
            is ArgResult.Unusable -> return invalid("query must be a string, but was ${parsed.got}.")
        }

        val limit = when (val parsed = ArgCoerce.int(args, "max_results")) {
            is ArgResult.Present -> ArgCoerce.clamp(parsed.value, 1, MAX_RESULTS)
            else -> DEFAULT_RESULTS
        }

        val terms = MemoryText.terms(query).distinct()
        val patterns = HistoryQueries.patternsFor(query)
        val statement = HistoryQueries.buildSearch(patterns)
            ?: return invalid(
                "\"$query\" has no word the search can use. Ask again with the specific words, " +
                    "for example the name or the topic.",
            )

        return guarded("history.search") {
            val rows: List<MessageEntity> = messages.value.searchText(statement)
            val ranked = MemoryIndex.rank(
                query = query,
                items = rows,
                termsOf = { MemoryText.terms(it.text.orEmpty()) },
                importanceOf = { 0f },
                limit = limit,
            )
            if (ranked.isEmpty()) {
                return@guarded ToolResult(
                    success = true,
                    observation = "No earlier conversation mentions \"$query\". " +
                        "Say so, and do not guess what was said.",
                    data = buildJsonObject {
                        put("matches", 0)
                        put("scanned", rows.size)
                    },
                )
            }

            val lines = ranked.map { hit ->
                val row = hit.item
                HistoryText.line(
                    date = dateFormat.format(Instant.ofEpochMilli(row.createdAt)),
                    role = HistoryText.roleOf(row.kind),
                    snippet = HistoryText.snippet(row.text.orEmpty(), terms),
                )
            }
            ToolResult(
                success = true,
                observation = "${lines.size} earlier message(s) mention \"$query\", best match first:\n" +
                    lines.joinToString("\n"),
                data = buildJsonObject {
                    put("matches", lines.size)
                    put("scanned", rows.size)
                },
            )
        }
    }

    private fun invalid(reason: String) = ToolResult(
        success = false,
        observation = reason,
        error = ToolError.InvalidArguments(reason),
    )

    private companion object {
        val SCHEMA: ToolArgs = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("query") {
                    put("type", "string")
                    put("maxLength", MAX_QUERY_CHARS)
                    put("description", "The words to look for, for example \"wifi password\".")
                }
                putJsonObject("max_results") {
                    put("type", "integer")
                    put("minimum", 1)
                    put("maximum", MAX_RESULTS)
                    put("description", "How many messages to return, 5 by default, at most 10.")
                }
            }
            putJsonArray("required") { add("query") }
        }
    }
}

/**
 * The history tool family. The database is opened on the first search, not when the
 * app starts, so a run that never searches history never creates the handle
 * (`docs/architecture.md` section 16).
 */
fun historyTools(context: Context): List<AgentTool> {
    val app = context.applicationContext
    val messages = lazy { LocalIntelligenceDatabase.build(app).messageDao() }
    return listOf(HistorySearchTool(messages))
}
