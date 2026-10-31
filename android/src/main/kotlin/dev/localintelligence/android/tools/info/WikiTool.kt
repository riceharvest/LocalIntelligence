package dev.localintelligence.android.tools.info

import dev.localintelligence.android.tools.web.SearchObservations
import dev.localintelligence.android.tools.web.WebArgs
import dev.localintelligence.core.model.ObservationOrigin
import dev.localintelligence.core.model.ToolArgs
import dev.localintelligence.core.tool.AgentTool
import dev.localintelligence.core.tool.ToolContext
import dev.localintelligence.core.tool.ToolDefinition
import dev.localintelligence.core.tool.ToolError
import dev.localintelligence.core.tool.ToolResult
import dev.localintelligence.core.tool.ToolRisk
import dev.localintelligence.core.tool.contracts.PlatformGrant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.net.URLEncoder

// ===========================================================================
// wiki.search: the best Wikipedia match for a query, as a short extract and a link.
//
// Two requests: the REST search finds the best article title, then the REST page
// summary returns its extract. Both are GET, neither needs a key, and both are on
// the language's own host, so the only thing the model picks is the language code,
// and that is checked against a strict pattern before it becomes part of a host name.
//
// The extract is third-party text, so the tool is NETWORK origin and is fenced.
// ===========================================================================

private const val SERVICE: String = "Wikipedia"
private const val DEFAULT_LANGUAGE: String = "en"

/** A Wikipedia language code such as en, nl or pt-br. Anything else is refused before it touches a host name. */
private val LANGUAGE_CODE = Regex("[a-z]{2,3}(-[a-z]{2,8})?")

/** Characters of extract kept. The observation has a 2048-char budget shared with the rest of the reply. */
private const val EXTRACT_CHARS: Int = 700

private val WIKI_SCHEMA: JsonObject = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") {
        putJsonObject("query") {
            put("type", "string")
            put("maxLength", 200)
            put("description", "The name or topic to look up, for example Haarlem.")
        }
        putJsonObject("language") {
            put("type", "string")
            put("maxLength", 10)
            put("description", "Wikipedia language code, for example en or nl. Default en.")
        }
    }
    putJsonArray("required") { add("query") }
}

class WikiSearchTool(private val grant: PlatformGrant) : AgentTool {

    override val definition: ToolDefinition = ToolDefinition(
        name = "wiki.search",
        description = "Looks up a Wikipedia article and returns its title, a short extract and its link.",
        category = "info",
        schema = WIKI_SCHEMA,
        risk = ToolRisk.READ_ONLY,
        observationOrigin = ObservationOrigin.NETWORK,
        tags = setOf("wikipedia", "who is", "what is"),
        requiredPermission = null,
    )

    override suspend fun execute(args: ToolArgs, context: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            try {
                val query = WebArgs.coerceString(args["query"])
                    ?: return@withContext invalidResult(
                        "No query argument. Pass the name to look up, for example query=\"Haarlem\".",
                    )
                val language = WebArgs.coerceString(args["language"])?.lowercase() ?: DEFAULT_LANGUAGE
                if (!LANGUAGE_CODE.matches(language)) {
                    return@withContext invalidResult("language must be a Wikipedia code such as en or nl.")
                }
                internetDenied("wiki.search", grant)?.let { return@withContext it }
                if (context.signal.isCancelled()) return@withContext cancelledResult("wiki.search")

                val search = getJson(
                    "https://$language.wikipedia.org/w/rest.php/v1/search/page" +
                        "?q=${URLEncoder.encode(query, "UTF-8")}&limit=1",
                    context.signal,
                ) as? JsonObject ?: throw UnreadableReply()
                val page = (search["pages"] as? JsonArray)?.firstOrNull() as? JsonObject
                val key = page?.get("key").textOrNull()
                    ?: return@withContext ToolResult(
                        success = false,
                        observation = "No Wikipedia article matches \"$query\" in $language. Try other words.",
                        error = ToolError.NotFound("no article for $query"),
                    )
                if (context.signal.isCancelled()) return@withContext cancelledResult("wiki.search")

                val summary = getJson(
                    "https://$language.wikipedia.org/api/rest_v1/page/summary/${pathSegment(key)}",
                    context.signal,
                ) as? JsonObject ?: throw UnreadableReply()
                val title = summary["title"].textOrNull() ?: key
                val extract = summary["extract"].textOrNull()?.takeIf { it.isNotBlank() }
                    ?: page?.get("description").textOrNull().orEmpty()
                val link = "https://$language.wikipedia.org/wiki/${pathSegment(key)}"
                val body = if (extract.isBlank()) "No summary is available." else SearchObservations.clip(extract, EXTRACT_CHARS)
                ToolResult(
                    success = true,
                    observation = "Wikipedia ($language): $title. $body\n$link",
                    data = buildJsonObject {
                        put("title", title)
                        put("url", link)
                    },
                )
            } catch (e: Exception) {
                failureFor("Wikipedia", e)
            }
        }

    /** A title as one URL path segment. URLEncoder writes a space as "+", which a path does not read as a space. */
    private fun pathSegment(title: String): String =
        URLEncoder.encode(title, "UTF-8").replace("+", "%20")
}
