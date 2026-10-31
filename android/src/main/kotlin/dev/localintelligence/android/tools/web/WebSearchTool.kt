package dev.localintelligence.android.tools.web

import dev.localintelligence.core.model.ObservationOrigin
import dev.localintelligence.core.model.ToolArgs
import dev.localintelligence.core.tool.AgentTool
import dev.localintelligence.core.tool.ObservationTruncator
import dev.localintelligence.core.tool.ToolContext
import dev.localintelligence.core.tool.ToolDefinition
import dev.localintelligence.core.tool.ToolError
import dev.localintelligence.core.tool.ToolResult
import dev.localintelligence.core.tool.ToolRisk
import dev.localintelligence.core.tool.contracts.PermissionDenial
import dev.localintelligence.core.tool.contracts.PlatformGrant
import dev.localintelligence.core.tool.contracts.ToolPermissions
import dev.localintelligence.core.settings.SettingsReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.UnknownHostException
import javax.net.ssl.SSLException

// ===========================================================================
// web.search — NETWORK_EGRESS query to the provider chosen in Settings.
//
// DuckDuckGo (the default, no key) is the HTML endpoint below. Brave, Tavily, Exa
// and SearXNG are reached through KeyedSearch.kt; the choice is WebSearchSettings.PROVIDER.
//
// WHY THE HTML ENDPOINT AND NOT AN API: every search API with a usable free
// tier needs a key, and a key compiled into a phone app is a key anyone can
// extract and spend. The HTML endpoint needs none. It is scraped rather than
// contracted, so its markup can change under us; the parser below degrades to
// "no results" and never throws.
//
// WHAT LEAVES THE PHONE: the query text, once, as the `q` parameter of a GET to
// html.duckduckgo.com. No cookies, no referer, no account. The confirmation
// dialog runs before this, and docs/threat-model.md (T3) records the exposure.
//
// THE RESULTS ARE UNTRUSTED. Titles and snippets are written by whoever built
// each page. They get the same NETWORK origin as web.fetch, so ModelBackend
// fences them; that fence comes from the definition's observationOrigin, which
// CatalogueAgreement checks against V0ToolCatalogue.
//
// Transport, timeouts, byte cap, redirect rule and the private-address check on
// result links all come from WebTools.kt. web.fetch itself is untouched.
// ===========================================================================

/** The endpoint. Fixed here, never taken from the model, so the destination is never a model choice. */
internal const val SEARCH_HOST: String = "html.duckduckgo.com"
internal const val SEARCH_ENDPOINT: String = "https://$SEARCH_HOST/html/"

/**
 * A normal mobile browser identity. DuckDuckGo's HTML endpoint serves a bot
 * challenge to clients that announce themselves as libraries, so this is the
 * one tool that does not use the "LocalIntelligence/1.0" agent string.
 */
internal const val SEARCH_USER_AGENT: String =
    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

internal const val DEFAULT_RESULTS: Int = 5
internal const val MAX_RESULTS: Int = 10

/** The query is a few words. A longer one is a pasted document, and it is refused rather than sent. */
internal const val MAX_QUERY_CHARS: Int = 200

/** Per-result caps, so ten results fit the 2048-char observation budget. */
internal const val TITLE_CHARS: Int = 100
internal const val SNIPPET_CHARS: Int = 160

/** Budget for stripping one title or snippet. Larger than any cap above, so nothing is cut early. */
private const val CLEAN_BUDGET: Int = 1000

/** One search hit as the page presented it. */
internal data class SearchHit(val title: String, val url: String, val snippet: String)

// ----------------------------------------------------------------------------
// Parsing
// ----------------------------------------------------------------------------

/**
 * Plain string/regex extraction from DuckDuckGo's HTML results page.
 *
 * Titles are `<a class="result__a">`. Snippets are `<a class="result__snippet">`.
 * Each snippet belongs to the most recent title, so a result with no snippet
 * still comes out as a result.
 *
 * Only `<a>` is matched on purpose. A `<div>` pattern that stops at its first
 * `</div>` would swallow the result anchors nested inside it.
 */
internal object DuckDuckGoHtml {

    private val ANCHOR = Regex(
        "<a\\b([^>]*)>(.*?)</a\\s*>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val CLASS_ATTR = Regex("""\bclass\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE)
    private val HREF_ATTR = Regex("""\bhref\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE)
    private val UDDG = Regex("""[?&]uddg=([^&#]+)""")

    /** A title with its snippet filled in later. */
    private class Pending(val title: String, val url: String) {
        var snippet: String? = null
    }

    fun parse(html: String, limit: Int): List<SearchHit> {
        val pending = mutableListOf<Pending>()
        var current: Pending? = null
        for (match in ANCHOR.findAll(html)) {
            val attrs = match.groupValues[1]
            val classes = CLASS_ATTR.find(attrs)?.groupValues?.get(1)?.split(' ').orEmpty()
            when {
                "result__a" in classes -> {
                    val url = resultUrl(attrs)
                    current = null
                    if (url != null) {
                        val hit = Pending(clean(match.groupValues[2]), url)
                        if (hit.title.isNotEmpty()) {
                            pending += hit
                            current = hit
                        }
                    }
                }
                "result__snippet" in classes -> {
                    current?.let { if (it.snippet == null) it.snippet = clean(match.groupValues[2]) }
                    current = null
                }
            }
        }
        return pending.take(limit).map { SearchHit(it.title, it.url, it.snippet.orEmpty()) }
    }

    /**
     * The real destination behind a result link.
     *
     * DuckDuckGo wraps each link as `//duckduckgo.com/l/?uddg=<encoded url>`, so
     * the unwrap is the decoding step. Only http(s) survives [WebUrls.validate],
     * and a result that points back at DuckDuckGo (its own ads and redirects)
     * is dropped. Private and single-label addresses are also refused, the same
     * as a web.fetch URL, so a planted result cannot send the model to the LAN.
     */
    private fun resultUrl(attrs: String): String? {
        val href = HREF_ATTR.find(attrs)?.groupValues?.get(1)?.let(::clean)?.trim() ?: return null
        val absolute = if (href.startsWith("//")) "https:$href" else href
        val target = if (absolute.contains("duckduckgo.com/l/")) {
            val encoded = UDDG.find(absolute)?.groupValues?.get(1) ?: return null
            try {
                URLDecoder.decode(encoded, "UTF-8")
            } catch (e: IllegalArgumentException) {
                return null
            }
        } else {
            absolute
        }
        val verdict = WebUrls.validate(target) as? UrlVerdict.Accepted ?: return null
        if (verdict.host == SEARCH_HOST || verdict.host.endsWith(".duckduckgo.com")) return null
        return verdict.url
    }

    /**
     * Tags stripped, entities decoded, whitespace collapsed. [HtmlText] does all
     * three, so a `&amp;` in a title reaches the model as `&`, not as markup.
     */
    private fun clean(fragment: String): String =
        HtmlText.toPlainText(fragment, CLEAN_BUDGET, contentType = "text/html")
            .text
            .replace(Regex("\\s+"), " ")
            .trim()

    /**
     * True when DuckDuckGo answered with its bot challenge instead of results.
     * Used to say "blocked" rather than "no results", which would be a lie.
     */
    fun isBotChallenge(html: String): Boolean =
        html.contains("anomaly-modal", ignoreCase = true) || html.contains("captcha", ignoreCase = true)
}

// ----------------------------------------------------------------------------
// Observation construction
// ----------------------------------------------------------------------------

internal object SearchObservations {

    /** Reserved at the end, so a cut list still says it was cut. */
    private const val CUT_NOTE: String = "\n(more results cut to fit the observation)"

    /** Numbered compact lines: "1. Title — https://host/path" then "   snippet". */
    fun render(
        query: String,
        hits: List<SearchHit>,
        source: String,
        budget: Int = ObservationTruncator.DEFAULT_BUDGET_CHARS,
    ): String {
        val out = StringBuilder("Results for \"$query\" from $source:")
        var cut = false
        for ((index, hit) in hits.withIndex()) {
            val block = buildString {
                append("\n${index + 1}. ").append(clip(hit.title, TITLE_CHARS))
                append(" — ").append(hit.url)
                if (hit.snippet.isNotEmpty()) {
                    append("\n   ").append(clip(hit.snippet, SNIPPET_CHARS))
                }
            }
            if (out.length + block.length + CUT_NOTE.length > budget) {
                cut = true
                break
            }
            out.append(block)
        }
        if (cut) out.append(CUT_NOTE)
        return out.toString()
    }

    /** Cuts to [max] with a single-character ellipsis, so the cut is visible. */
    fun clip(text: String, max: Int): String =
        if (text.length <= max) text else text.take(max - 1).trimEnd() + "…"
}

// ----------------------------------------------------------------------------
// The searcher
// ----------------------------------------------------------------------------

/**
 * The whole search, minus the coroutine. Blocking; the tool calls it on
 * [Dispatchers.IO]. Every exit is a [ToolResult], as in [WebFetcher].
 */
internal class WebSearcher(
    private val opener: ResponseOpener,
) {

    fun search(query: String, maxResults: Int, context: ToolContext, grant: PlatformGrant): ToolResult {
        try {
            if (context.signal.isCancelled()) return cancelled()
            if (!grant.isGranted(ToolPermissions.INTERNET_REQUIREMENT)) {
                return ToolResult(
                    success = false,
                    observation = PermissionDenial.observation("web.search", ToolPermissions.INTERNET_REQUIREMENT),
                    error = ToolError.PermissionDenied(PermissionDenial.summary(ToolPermissions.INTERNET_REQUIREMENT)),
                )
            }

            val endpoint = SEARCH_ENDPOINT + "?q=" + URLEncoder.encode(query, "UTF-8")
            var current = endpoint
            var redirects = 0
            var response: OpenedResponse? = null
            try {
                // The same redirect rule as web.fetch: at most MAX_REDIRECTS hops,
                // each one re-validated, and only to the same host or its www twin.
                while (true) {
                    response?.close()
                    val opened = opener.open(current, context.signal)
                    response = opened
                    if (context.signal.isCancelled()) return cancelled()

                    if (isRedirectStatus(opened.status)) {
                        when (val hop = WebUrls.resolveRedirect(current, opened.location, redirects)) {
                            is RedirectVerdict.Rejected -> return unavailable(hop.reason)
                            is RedirectVerdict.Follow -> {
                                current = hop.url
                                redirects = hop.redirects
                            }
                        }
                        continue
                    }

                    if (opened.status !in 200..299) {
                        return unavailable(
                            "DuckDuckGo answered HTTP ${opened.status}. The search service is " +
                                "refusing or overloaded; do not retry immediately.",
                        )
                    }

                    val body = BodyReader.readCapped(opened.body, MAX_BODY_BYTES, context.signal)
                    if (body.cancelled) return cancelled()
                    val html = String(body.bytes, WebFetchContent.charsetOf(opened.contentType))

                    val hits = DuckDuckGoHtml.parse(html, maxResults)
                    if (hits.isEmpty()) {
                        if (DuckDuckGoHtml.isBotChallenge(html)) {
                            return unavailable(
                                "DuckDuckGo blocked this search with a bot check. Do not retry " +
                                    "right away; tell the user search is unavailable for now.",
                            )
                        }
                        return ToolResult(
                            success = true,
                            observation = "No results for \"$query\" from DuckDuckGo. " +
                                "Try different or fewer words.",
                            data = buildJsonObject {
                                put("query", JsonPrimitive(query))
                                put("results", JsonPrimitive(0))
                            },
                        )
                    }

                    return ToolResult(
                        success = true,
                        observation = ObservationTruncator.truncate(
                            SearchObservations.render(query, hits, source = "DuckDuckGo"),
                        ),
                        data = buildJsonObject {
                            put("query", JsonPrimitive(query))
                            put("results", JsonPrimitive(hits.size))
                            put("bodyTruncated", JsonPrimitive(body.truncated))
                        },
                    )
                }
            } finally {
                response?.close()
            }
        } catch (e: UnknownHostException) {
            return ToolResult(
                success = false,
                observation = "Could not reach $SEARCH_HOST: no such host (${e.javaClass.simpleName}). " +
                    "The connection may be down; check it, then try once more.",
                error = ToolError.Unavailable("UnknownHostException: no such host"),
            )
        } catch (e: SocketTimeoutException) {
            return ToolResult(
                success = false,
                observation = "Web search timed out after " +
                    "${CONNECT_TIMEOUT_MS / 1000}s connect / ${READ_TIMEOUT_MS / 1000}s read. " +
                    "The network may be slow; do not retry immediately.",
                error = ToolError.Timeout("socket timeout"),
            )
        } catch (e: SSLException) {
            return unavailable(
                "The secure connection to $SEARCH_HOST failed (${e.javaClass.simpleName}). " +
                    "Do not retry; report that search is unavailable.",
            )
        } catch (e: SecurityException) {
            return ToolResult(
                success = false,
                observation = "Android refused the network request to $SEARCH_HOST " +
                    "(${e.javaClass.simpleName}). The device is missing the INTERNET permission " +
                    "or a network policy blocked it.",
                error = ToolError.PermissionDenied("SecurityException: ${e.javaClass.simpleName}"),
            )
        } catch (e: IOException) {
            return unavailable("could not reach $SEARCH_HOST (${e.javaClass.simpleName}). Check the connection, then try once more.")
        } catch (e: RuntimeException) {
            // As in WebFetcher: an unexpected platform exception must not reach the
            // agent loop. Report the type only, never a stack trace.
            return ToolResult(
                success = false,
                observation = "web.search failed unexpectedly (${e.javaClass.simpleName}). " +
                    "This is a tool bug; do not retry the same query.",
                error = ToolError.Internal("${e.javaClass.simpleName} in WebSearcher.search"),
            )
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

// ----------------------------------------------------------------------------
// The tool
// ----------------------------------------------------------------------------

/**
 * `web.search`: search the web and return the top results as titles, links and
 * snippets. Use `web.fetch` on one of the links to read the page.
 *
 * NETWORK_EGRESS, for the same reason as `web.fetch`: the query leaves the device
 * and the reply is text a third party wrote. The confirmation dialog cannot name
 * a destination host for this tool, because its only argument is the query, so
 * it says "an external host". That is a known gap, recorded in the report, not
 * something this file works around.
 */
class WebSearchTool private constructor(
    private val opener: ResponseOpener,
    private val grant: PlatformGrant,
    /** Read live, as OpenRouterPlugin reads its key: a provider change applies to the next search. */
    private val settings: SettingsReader,
) : AgentTool {

    constructor(grant: PlatformGrant, settings: SettingsReader) :
        this(HttpUrlConnectionOpener(userAgent = SEARCH_USER_AGENT), grant, settings)

    override val definition: ToolDefinition = ToolDefinition(
        name = "web.search",
        description = "Searches the web and returns the top results as numbered titles, links and " +
            "short snippets. Use web.fetch on a link to read the page.",
        category = "web",
        schema = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put(
                "properties",
                buildJsonObject {
                    put(
                        "query",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("maxLength", JsonPrimitive(MAX_QUERY_CHARS))
                            put(
                                "description",
                                JsonPrimitive("The search words, for example \"weather amsterdam\"."),
                            )
                        },
                    )
                    put(
                        "max_results",
                        buildJsonObject {
                            put("type", JsonPrimitive("integer"))
                            put("minimum", JsonPrimitive(1))
                            put("maximum", JsonPrimitive(MAX_RESULTS))
                            put(
                                "description",
                                JsonPrimitive("How many results to return, $DEFAULT_RESULTS by default, at most $MAX_RESULTS."),
                            )
                        },
                    )
                },
            )
            put("required", kotlinx.serialization.json.buildJsonArray { add(JsonPrimitive("query")) })
        },
        risk = ToolRisk.NETWORK_EGRESS,
        // Stated, as in WebFetchTool: the results are written by third parties,
        // and this is what makes ModelBackend fence them.
        observationOrigin = ObservationOrigin.NETWORK,
        // No fixedHost: the provider is a setting, so one fixed host would be wrong for
        // every provider but DuckDuckGo. The confirmation says "an external host" instead.
        tags = setOf(
            "search", "search for", "look up", "google", "search the web", "web search", "find online",
        ),
        requiredPermission = null,
    )

    override suspend fun execute(args: ToolArgs, context: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            try {
                val query = WebArgs.coerceString(args["query"])
                    ?: return@withContext invalid("No query argument. Pass the search words, for example query=\"weather amsterdam\".")
                if (query.length > MAX_QUERY_CHARS) {
                    return@withContext invalid(
                        "That query is ${query.length} characters, over the $MAX_QUERY_CHARS limit. " +
                            "Shorten it to the key words.",
                    )
                }
                val maxResults = WebArgs.coerceInt(
                    args["max_results"],
                    fallback = DEFAULT_RESULTS,
                    min = 1,
                    max = MAX_RESULTS,
                )
                val compact = query.replace(Regex("\\s+"), " ")
                when (val provider = SearchProvider.fromId(settings[WebSearchSettings.PROVIDER])) {
                    SearchProvider.DUCKDUCKGO -> WebSearcher(opener).search(compact, maxResults, context, grant)
                    else -> keyedSearch(provider, compact, maxResults, context)
                }
            } catch (e: Exception) {
                ToolResult(
                    success = false,
                    observation = "web.search could not run (${e.javaClass.simpleName}). " +
                        "Do not retry the same query.",
                    error = ToolError.Internal("${e.javaClass.simpleName} in WebSearchTool.execute"),
                )
            }
        }

    /**
     * A keyed or self-hosted provider. The secret comes from [CredentialSource] (or the
     * SearXNG address from Settings), and a missing one is said plainly, not guessed at.
     */
    private fun keyedSearch(provider: SearchProvider, query: String, maxResults: Int, context: ToolContext): ToolResult {
        val secret = if (provider == SearchProvider.SEARXNG) {
            settings[WebSearchSettings.SEARXNG_URL].trim()
        } else {
            CredentialSource(settings).keyFor(provider).orEmpty()
        }
        if (secret.isEmpty()) {
            val observation = if (provider == SearchProvider.SEARXNG) {
                "SearXNG needs the address of your instance. Add it in Settings, under Plugins, " +
                    "or switch the search provider to DuckDuckGo."
            } else {
                "${provider.label} needs an API key. Add it in Settings, under Plugins, " +
                    "or switch the search provider to DuckDuckGo."
            }
            return ToolResult(
                success = false,
                observation = observation,
                error = ToolError.PermissionDenied("no ${provider.id} credential"),
            )
        }
        return KeyedSearch.search(provider, query, maxResults, secret, grant, context.signal)
    }

    private fun invalid(observation: String): ToolResult = ToolResult(
        success = false,
        observation = observation,
        error = ToolError.InvalidArguments(observation.take(100)),
    )

    internal companion object {
        /** Test seam: a [WebSearchTool] backed by a scripted transport. */
        fun withOpener(opener: ResponseOpener, grant: PlatformGrant): WebSearchTool =
            WebSearchTool(opener, grant, SettingsReader.DEFAULTS)
    }
}
