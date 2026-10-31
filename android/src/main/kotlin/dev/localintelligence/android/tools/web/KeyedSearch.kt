package dev.localintelligence.android.tools.web

import dev.localintelligence.core.tool.CancellationSignal
import dev.localintelligence.core.tool.ObservationTruncator
import dev.localintelligence.core.tool.ToolError
import dev.localintelligence.core.tool.ToolResult
import dev.localintelligence.core.tool.contracts.PermissionDenial
import dev.localintelligence.core.tool.contracts.PlatformGrant
import dev.localintelligence.core.tool.contracts.ToolPermissions
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URISyntaxException
import java.net.URLEncoder
import java.net.UnknownHostException
import javax.net.ssl.SSLException

// ===========================================================================
// The keyed and self-hosted search providers: Brave, Tavily, Exa and SearXNG.
// DuckDuckGo keeps its own path in WebSearcher and never comes here.
//
// Each provider is a request shape and a reply shape, and nothing else differs:
// the same transport, the same fence (every title and snippet is third-party text,
// so the tool's NETWORK origin fences it), the same one-sentence failures.
//
// THE DOCS (request and reply fields were read from these pages):
//   Brave  GET  https://api.search.brave.com/res/v1/web/search?q=&count=
//          header X-Subscription-Token; reply web.results[] {title,url,description}
//          https://api-dashboard.search.brave.com/app/documentation/web-search/get-started
//   Tavily POST https://api.tavily.com/search, header Authorization: Bearer;
//          body {query,max_results}; reply results[] {title,url,content}
//          https://docs.tavily.com/documentation/api-reference/endpoint/search
//   Exa    POST https://api.exa.ai/search, header x-api-key; body {query,numResults,
//          contents.text.maxCharacters}; reply results[] {title,url,text}
//          https://docs.exa.ai/reference/search
//   SearXNG GET <instance>/search?q=&format=json; reply results[] {title,url,content}
//          https://docs.searxng.org/dev/search_api.html
//
// A provider error is reported, never answered with another provider's results.
// ===========================================================================

/** Identity sent to the keyed APIs. They are not browsers, so no browser string is used. */
private const val API_USER_AGENT: String = "LocalIntelligence/1.0 (on-device agent)"

private const val BRAVE_ENDPOINT: String = "https://api.search.brave.com/res/v1/web/search"
private const val TAVILY_ENDPOINT: String = "https://api.tavily.com/search"
private const val EXA_ENDPOINT: String = "https://api.exa.ai/search"

/** Exa is asked for a short text per result, so ten results fit the observation. */
private const val EXA_MAX_CHARACTERS: Int = 300

/** Budget for stripping one title or snippet. Larger than any cap in the render, so nothing is cut early. */
private const val CLEAN_BUDGET: Int = 1000

private val WHITESPACE = Regex("\\s+")

/** One request, built from a provider and the user's query. */
private class Call(
    val method: String,
    val url: String,
    val headers: Map<String, String>,
    val body: String?,
    val userAgent: String,
)

internal object KeyedSearch {

    /**
     * Runs one search on [provider]. [secret] is the provider's key, or for SearXNG
     * the instance address. Blocking; the caller is on Dispatchers.IO. Every exit is
     * a [ToolResult], as in WebSearcher.
     */
    fun search(
        provider: SearchProvider,
        query: String,
        maxResults: Int,
        secret: String,
        grant: PlatformGrant,
        signal: CancellationSignal,
    ): ToolResult {
        if (!grant.isGranted(ToolPermissions.INTERNET_REQUIREMENT)) {
            return ToolResult(
                success = false,
                observation = PermissionDenial.observation("web.search", ToolPermissions.INTERNET_REQUIREMENT),
                error = ToolError.PermissionDenied(PermissionDenial.summary(ToolPermissions.INTERNET_REQUIREMENT)),
            )
        }
        if (signal.isCancelled()) return cancelled()
        try {
            val call = callFor(provider, query, maxResults, secret) ?: return ToolResult(
                success = false,
                observation = "The ${provider.label} address in Settings is not a web address. " +
                    "Use the full address, for example https://searx.example.org.",
                error = ToolError.Unavailable("${provider.id} address is not http or https"),
            )
            val reply = exchangeJson(call.method, call.url, call.headers, call.body, call.userAgent, signal)
            if (signal.isCancelled()) return cancelled()
            if (!reply.ok) return failedReply(provider, reply.status)

            val hits = parse(provider, reply.body, maxResults) ?: return unavailable(
                "${provider.label} sent a reply this app could not read. Do not retry right away.",
            )
            if (hits.isEmpty()) {
                return ToolResult(
                    success = true,
                    observation = "No results for \"$query\" from ${provider.label}. Try different or fewer words.",
                    data = buildJsonObject {
                        put("query", JsonPrimitive(query))
                        put("results", JsonPrimitive(0))
                        put("provider", JsonPrimitive(provider.id))
                    },
                )
            }
            return ToolResult(
                success = true,
                observation = ObservationTruncator.truncate(
                    SearchObservations.render(query, hits, provider.label),
                ),
                data = buildJsonObject {
                    put("query", JsonPrimitive(query))
                    put("results", JsonPrimitive(hits.size))
                    put("provider", JsonPrimitive(provider.id))
                },
            )
        } catch (e: UnknownHostException) {
            return unavailable(
                "Could not reach ${provider.label}: no such host (${e.javaClass.simpleName}). " +
                    "The connection may be down; check it, then try once more.",
            )
        } catch (e: SocketTimeoutException) {
            return ToolResult(
                success = false,
                observation = "${provider.label} timed out. Do not retry immediately.",
                error = ToolError.Timeout("socket timeout"),
            )
        } catch (e: SSLException) {
            return unavailable(
                "The secure connection to ${provider.label} failed (${e.javaClass.simpleName}). " +
                    "Do not retry; report that search is unavailable.",
            )
        } catch (e: SecurityException) {
            return ToolResult(
                success = false,
                observation = "Android refused the network request to ${provider.label}. " +
                    "A network policy may be blocking it.",
                error = ToolError.PermissionDenied("SecurityException: ${e.javaClass.simpleName}"),
            )
        } catch (e: IOException) {
            return unavailable(
                "could not reach ${provider.label} (${e.javaClass.simpleName}). " +
                    "Check the connection, then try once more.",
            )
        } catch (e: RuntimeException) {
            // As in WebSearcher: an unexpected exception must not reach the agent loop.
            return ToolResult(
                success = false,
                observation = "web.search failed unexpectedly (${e.javaClass.simpleName}). " +
                    "This is a tool bug; do not retry the same query.",
                error = ToolError.Internal("${e.javaClass.simpleName} in KeyedSearch.search"),
            )
        }
    }

    /** The request for [provider], or null when the SearXNG address is not http or https. */
    private fun callFor(provider: SearchProvider, query: String, maxResults: Int, secret: String): Call? =
        when (provider) {
            SearchProvider.BRAVE -> Call(
                method = "GET",
                url = "$BRAVE_ENDPOINT?q=${encode(query)}&count=$maxResults",
                headers = mapOf("X-Subscription-Token" to secret),
                body = null,
                userAgent = API_USER_AGENT,
            )
            SearchProvider.TAVILY -> Call(
                method = "POST",
                url = TAVILY_ENDPOINT,
                headers = mapOf("Authorization" to "Bearer $secret"),
                body = buildJsonObject {
                    put("query", query)
                    put("max_results", maxResults)
                }.toString(),
                userAgent = API_USER_AGENT,
            )
            SearchProvider.EXA -> Call(
                method = "POST",
                url = EXA_ENDPOINT,
                headers = mapOf("x-api-key" to secret),
                body = buildJsonObject {
                    put("query", query)
                    put("numResults", maxResults)
                    putJsonObject("contents") {
                        putJsonObject("text") { put("maxCharacters", EXA_MAX_CHARACTERS) }
                    }
                }.toString(),
                userAgent = API_USER_AGENT,
            )
            SearchProvider.SEARXNG -> searxngCall(secret, query)
            // WebSearcher owns DuckDuckGo. Reaching this line is a routing bug, and the
            // caller's RuntimeException handler reports it as one.
            SearchProvider.DUCKDUCKGO -> error("DuckDuckGo is not a keyed provider")
        }

    /** SearXNG is a user-chosen instance, often on the LAN, so private addresses are allowed here. */
    private fun searxngCall(base: String, query: String): Call? {
        val trimmed = base.trim()
        val uri = try {
            URI(trimmed)
        } catch (e: URISyntaxException) {
            return null
        }
        val scheme = uri.scheme?.lowercase()
        if ((scheme != "http" && scheme != "https") || uri.host.isNullOrEmpty()) return null
        return Call(
            method = "GET",
            url = "${trimmed.trimEnd('/')}/search?q=${encode(query)}&format=json",
            headers = emptyMap(),
            body = null,
            // SearXNG instances often refuse bot-like agents, so it gets the browser identity
            // that web.search already uses for DuckDuckGo.
            userAgent = SEARCH_USER_AGENT,
        )
    }

    /** The hits in [body], or null when the reply is not the shape the provider documents. */
    private fun parse(provider: SearchProvider, body: String, limit: Int): List<SearchHit>? {
        val results: JsonArray = try {
            val root = Json.parseToJsonElement(body) as? JsonObject ?: return null
            val array = when (provider) {
                SearchProvider.BRAVE -> (root["web"] as? JsonObject)?.get("results")
                else -> root["results"]
            }
            array as? JsonArray ?: return null
        } catch (e: IllegalArgumentException) {
            return null
        }
        val hits = mutableListOf<SearchHit>()
        for (element in results) {
            if (hits.size >= limit) break
            val item = element as? JsonObject ?: continue
            val title = plain(text(item, "title"))
            val url = WebUrls.validate(text(item, "url")) as? UrlVerdict.Accepted ?: continue
            if (title.isEmpty()) continue
            hits += SearchHit(title, url.url, plain(snippetOf(provider, item)))
        }
        return hits
    }

    private fun snippetOf(provider: SearchProvider, item: JsonObject): String? = when (provider) {
        SearchProvider.BRAVE -> text(item, "description")
        SearchProvider.TAVILY -> text(item, "content")
        SearchProvider.EXA -> text(item, "text")
            ?: ((item["highlights"] as? JsonArray)?.firstOrNull() as? JsonPrimitive)?.contentOrNull
        SearchProvider.SEARXNG -> text(item, "content")
        SearchProvider.DUCKDUCKGO -> null
    }

    /** A string field, or null for a missing or non-string one. */
    private fun text(item: JsonObject, key: String): String? =
        (item[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** Tags stripped and whitespace collapsed, the same cleaning WebSearcher gives DuckDuckGo text. */
    private fun plain(text: String?): String =
        HtmlText.toPlainText(text.orEmpty(), CLEAN_BUDGET, contentType = "text/html")
            .text
            .replace(WHITESPACE, " ")
            .trim()

    private fun encode(text: String): String = URLEncoder.encode(text, "UTF-8")

    /** A non-2xx reply, as one sentence. A key the provider refuses is named as a Settings problem. */
    private fun failedReply(provider: SearchProvider, status: Int): ToolResult {
        val label = provider.label
        return when (status) {
            401, 403 -> if (provider == SearchProvider.SEARXNG) {
                ToolResult(
                    success = false,
                    observation = "SearXNG refused the request (HTTP $status). The instance may have the " +
                        "JSON format turned off or block this app.",
                    error = ToolError.PermissionDenied("SearXNG HTTP $status"),
                )
            } else {
                ToolResult(
                    success = false,
                    observation = "$label refused the key (HTTP $status). Check the key in Settings.",
                    error = ToolError.PermissionDenied("$label HTTP $status"),
                )
            }
            429 -> unavailable("$label is rate-limiting this key (HTTP 429). Do not retry right away.")
            else -> unavailable("$label answered HTTP $status. Do not retry right away.")
        }
    }

    private fun unavailable(observation: String): ToolResult = ToolResult(
        success = false,
        observation = observation,
        error = ToolError.Unavailable(observation.take(120)),
    )

    private fun cancelled(): ToolResult = ToolResult(
        success = false,
        observation = "Cancelled before the search finished.",
        error = ToolError.Cancelled("signal raised during search"),
    )
}
