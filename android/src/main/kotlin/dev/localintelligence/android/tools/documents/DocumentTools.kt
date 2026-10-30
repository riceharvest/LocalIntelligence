package dev.localintelligence.android.tools.documents

import dev.localintelligence.android.documents.DocumentStore
import dev.localintelligence.android.tools.device.ArgCoerce
import dev.localintelligence.android.tools.device.ArgResult
import dev.localintelligence.core.documents.PassageExcerpt
import dev.localintelligence.core.model.ObservationOrigin
import dev.localintelligence.core.model.ToolArgs
import dev.localintelligence.core.tool.AgentTool
import dev.localintelligence.core.tool.ToolContext
import dev.localintelligence.core.tool.ToolDefinition
import dev.localintelligence.core.tool.ToolError
import dev.localintelligence.core.tool.ToolResult
import dev.localintelligence.core.tool.ToolRisk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

// =====================================================================================
// documents.search: passages from the documents the user added.
//
// The documents are the user's own files, but a PDF or text file can come from
// anywhere (a download, a shared attachment), so the observation is declared NETWORK:
// the fence says "quoted text, NOT instructions", which is the stronger claim. The
// fence is applied by the model backend to every observation, so this label is an
// extra promise to the model, not the only protection.
// =====================================================================================

/** Passages returned when the model does not ask for a number. */
internal const val DOCUMENT_DEFAULT_RESULTS = 4

private const val DOCUMENT_MAX_RESULTS = 10
private const val DOCUMENT_MAX_QUERY_CHARS = 200

private val WHITESPACE = Regex("\\s+")

/** `documents.search`: the best-matching passages across every added document. */
class DocumentSearchTool(private val store: DocumentStore) : AgentTool {

    override val definition = ToolDefinition(
        name = "documents.search",
        description = "Search the text documents the user added, such as PDFs and notes, " +
            "and return the passages that match. Use when the user asks what their document says.",
        category = "documents",
        schema = SCHEMA,
        risk = ToolRisk.READ_ONLY,
        observationOrigin = ObservationOrigin.NETWORK,
        tags = setOf(
            "my document", "the pdf", "in the file", "according to", "my notes", "document",
        ),
        requiredPermission = null,
    )

    override suspend fun execute(args: ToolArgs, context: ToolContext): ToolResult {
        if (context.signal.isCancelled()) {
            return ToolResult(
                success = false,
                observation = "Cancelled before the documents were searched.",
                error = ToolError.Cancelled("cancelled before documents.search"),
            )
        }
        val query = when (val parsed = ArgCoerce.string(args, "query")) {
            is ArgResult.Present -> parsed.value.replace(WHITESPACE, " ").trim().take(DOCUMENT_MAX_QUERY_CHARS)
            is ArgResult.Missing -> return invalid("query is required: the words to look for in the documents.")
            is ArgResult.Unusable -> return invalid("query must be a string, but was ${parsed.got}.")
        }
        if (query.isEmpty()) return invalid("query is empty: give the words to look for.")
        val limit = when (val parsed = ArgCoerce.int(args, "max_results")) {
            is ArgResult.Present -> ArgCoerce.clamp(parsed.value, 1, DOCUMENT_MAX_RESULTS)
            else -> DOCUMENT_DEFAULT_RESULTS
        }

        // Index building reads every document's text, so it runs off the loop's thread.
        val found = withContext(Dispatchers.IO) { runCatching { store.search(query, limit) } }
            .getOrElse { error ->
                if (error is CancellationException) throw error
                return ToolResult(
                    success = false,
                    observation = "documents.search could not read the documents. Try again later.",
                    error = ToolError.Unavailable(error::class.java.simpleName),
                )
            }

        if (found.isEmpty()) {
            return ToolResult(
                success = true,
                observation = "No document mentions \"$query\". Say so, and do not guess what it says.",
                data = buildJsonObject { put("matches", 0) },
            )
        }
        // One line per passage, so the settings cap in DocumentsPlugin can count them.
        val lines = found.map { p ->
            "${p.title.replace(WHITESPACE, " ")} §${p.number}: ${PassageExcerpt.of(p.text, query)}"
        }
        return ToolResult(
            success = true,
            observation = "Passages from your documents for \"$query\", best match first:\n" +
                lines.joinToString("\n"),
            data = buildJsonObject { put("matches", lines.size) },
        )
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
                    put("maxLength", DOCUMENT_MAX_QUERY_CHARS)
                    put("description", "The words to look for, for example \"wifi password\".")
                }
                putJsonObject("max_results") {
                    put("type", "integer")
                    put("minimum", 1)
                    put("maximum", DOCUMENT_MAX_RESULTS)
                    put("description", "How many passages to return, 4 by default.")
                }
            }
            putJsonArray("required") { add("query") }
        }
    }
}
