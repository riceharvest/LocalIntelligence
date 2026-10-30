package dev.localintelligence.android.tools.history

import android.content.Context
import android.util.Log
import dev.localintelligence.android.data.HistoryQueries
import dev.localintelligence.android.data.LocalIntelligenceDatabase
import dev.localintelligence.android.data.MessageDao
import dev.localintelligence.android.data.MessageEntity
import dev.localintelligence.android.data.ObservationDao
import dev.localintelligence.android.data.ObservationHit
import dev.localintelligence.android.data.ObservationQueries
import dev.localintelligence.android.tools.device.ArgCoerce
import dev.localintelligence.android.tools.device.ArgResult
import dev.localintelligence.android.tools.device.guarded
import dev.localintelligence.core.memory.MemoryIndex
import dev.localintelligence.core.memory.MemoryText
import dev.localintelligence.core.model.ObservationOrigin
import dev.localintelligence.core.model.ToolArgs
import dev.localintelligence.core.tool.AgentTool
import dev.localintelligence.core.tool.ObservationStore
import dev.localintelligence.core.tool.ObservationTruncator
import dev.localintelligence.core.tool.ToolContext
import dev.localintelligence.core.tool.ToolDefinition
import dev.localintelligence.core.tool.ToolError
import dev.localintelligence.core.tool.ToolResult
import dev.localintelligence.core.tool.ToolRisk
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// =====================================================================================
// HistoryTools: history.search, a read-only search over earlier chat messages and
// saved tool results.
//
// Lives in :android because both tables are Room, and :core cannot see Room.
// Messages: the user's and the assistant's text only; the cut-down tool output stored
// with a chat is not searched. Saved results: the full text of long tool results (the
// `observations` table), each returned as a handle (`obs#41 from web.fetch, 3 Oct`)
// and a snippet, so the model can read more with observations.read instead of
// carrying old output in its context.
//
// The observation is fenced as NETWORK origin (see the catalogue entry): an earlier
// assistant reply can repeat web text the model read at the time, and a saved result
// IS such text, so the result is treated as untrusted rather than as the user's own
// words.
// =====================================================================================

/** The snippet budget from the tool spec: one line per hit, at most this many characters. */
internal const val HISTORY_SNIPPET_CHARS = 160

private const val DEFAULT_RESULTS = 5
private const val MAX_RESULTS = 10
private const val MAX_QUERY_CHARS = 200

/** Saved tool results listed per search, after the messages. A handle each, so a few are enough. */
private const val MAX_SAVED_RESULTS = 3

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

    /** `obs#41 from web.fetch, 3 Oct — ...`: the handle observations.read takes, and where it came from. */
    fun savedLine(id: Long, toolName: String, date: String, snippet: String): String =
        "obs#$id from $toolName, $date — $snippet"

    /**
     * Drops the lowest-ranked line of the longer list until [render] fits the
     * observation budget, so a full page of messages cannot push the saved results
     * (which come last) off the end. Keeps at least one line.
     */
    fun fit(messages: List<String>, saved: List<String>, render: (List<String>, List<String>) -> String): String {
        var m = messages
        var s = saved
        var text = render(m, s)
        while (text.length > ObservationTruncator.DEFAULT_BUDGET_CHARS && m.size + s.size > 1) {
            if (m.size >= s.size) m = m.dropLast(1) else s = s.dropLast(1)
            text = render(m, s)
        }
        return text
    }

    private val WHITESPACE = Regex("\\s+")
}

/**
 * `history.search`: searches the messages table and returns the best matches, newest
 * first among equal scores.
 */
class HistorySearchTool(
    private val messages: Lazy<MessageDao>,
    /** Saved tool results, or null to search messages only. */
    private val observations: Lazy<ObservationDao>? = null,
    private val zone: ZoneId = ZoneId.systemDefault(),
) : AgentTool {

    private val dateFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(zone)

    /** `3 Oct`, in English whatever the phone's language: it is model-visible text. */
    private val savedDateFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH).withZone(zone)
    private val savedDateYearFormat: DateTimeFormatter =
        DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH).withZone(zone)

    override val definition = ToolDefinition(
        name = "history.search",
        description = "Searches earlier conversations and saved tool results for words and returns " +
            "the matches with their dates. Use when the user refers to something said or found before.",
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
            val saved = searchSaved(terms)
            if (ranked.isEmpty() && saved.isEmpty()) {
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
            val savedLines = saved.map { hit ->
                HistoryText.savedLine(
                    id = hit.id,
                    toolName = hit.toolName,
                    date = savedDate(hit.createdAt),
                    snippet = HistoryText.snippet(hit.snippet.orEmpty(), terms),
                )
            }
            val observation = HistoryText.fit(lines, savedLines) { m, s ->
                buildString {
                    if (m.isNotEmpty()) {
                        append("${m.size} earlier message(s) mention \"$query\", best match first:\n")
                        append(m.joinToString("\n"))
                    } else {
                        append("No earlier message mentions \"$query\".")
                    }
                    if (s.isNotEmpty()) {
                        append("\nSaved tool results that mention it (read more with ")
                        append(ObservationStore.READ_TOOL).append("):\n")
                        append(s.joinToString("\n"))
                    }
                }
            }
            ToolResult(
                success = true,
                observation = observation,
                data = buildJsonObject {
                    put("matches", lines.size)
                    put("saved_matches", savedLines.size)
                    put("scanned", rows.size)
                },
            )
        }
    }

    /**
     * The saved tool results that contain the most of [terms], newest first among
     * equals. Empty when there is no store or it cannot be read: a failure here must
     * not hide the messages that were found.
     */
    private suspend fun searchSaved(terms: List<String>): List<ObservationHit> {
        val dao = observations ?: return emptyList()
        val statement = ObservationQueries.buildSearch(terms) ?: return emptyList()
        return try {
            dao.value.search(statement)
                .sortedWith(compareByDescending<ObservationHit> { it.hits }.thenByDescending { it.id })
                .take(MAX_SAVED_RESULTS)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("HistorySearchTool", "saved results not searched (${e.javaClass.simpleName})")
            emptyList()
        }
    }

    /** `3 Oct` this year, `3 Oct 2025` before. */
    private fun savedDate(millis: Long): String {
        val instant = Instant.ofEpochMilli(millis)
        val sameYear = instant.atZone(zone).year == LocalDate.now(zone).year
        return (if (sameYear) savedDateFormat else savedDateYearFormat).format(instant)
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
 * (`docs/architecture.md` section 16). One handle serves both tables.
 */
fun historyTools(context: Context): List<AgentTool> {
    val app = context.applicationContext
    val database = lazy { LocalIntelligenceDatabase.build(app) }
    val messages = lazy { database.value.messageDao() }
    val observations = lazy { database.value.observationDao() }
    return listOf(HistorySearchTool(messages, observations))
}
