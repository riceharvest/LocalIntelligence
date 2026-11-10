package dev.localintelligence.android.tools.web

import android.util.Log
import dev.localintelligence.android.cloud.CredentialSource
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
import dev.localintelligence.core.settings.SettingsMode
import dev.localintelligence.core.settings.SettingsReader
import dev.localintelligence.core.settings.mode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import dev.localintelligence.android.tools.info.INFO_USER_AGENT
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.UnknownHostException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLException

// ===========================================================================
// web.search — NETWORK_EGRESS query to the provider chosen in Settings.
//
// The default (DuckDuckGo) is a chain of free services, tried in order by
// WebSearcher: DuckDuckGo, Bing, Brave Search, then Wikipedia. The next one is
// asked only when the one before gave a block, an error, a timeout or zero
// results. Brave, Tavily, Exa and SearXNG are reached through KeyedSearch.kt and
// are never chained; the choice is WebSearchSettings.PROVIDER.
//
// WHY HTML ENDPOINTS AND NOT AN API: every search API with a usable free
// tier needs a key, and a key compiled into a phone app is a key anyone can
// extract and spend. The HTML endpoints need none. They are scraped rather than
// contracted, so their markup can change under us; each parser degrades to
// "no results" and never throws. Wikipedia is the one JSON API, and it is last
// because it answers facts, not prices or news.
//
// WHAT LEAVES THE PHONE: the query text, once per service that is asked, as the
// search parameter of a GET to that service. No cookies, no referer, no account.
// The confirmation dialog runs before this, and docs/threat-model.md (T3) lists
// each service and when it is used.
//
// THE RESULTS ARE UNTRUSTED. Titles and snippets are written by whoever built
// each page. They get the same NETWORK origin as web.fetch, so ModelBackend
// fences them; that fence comes from the definition's observationOrigin, which
// CatalogueAgreement checks against V0ToolCatalogue.
//
// Transport, timeouts, byte cap, redirect rule and the private-address check on
// result links all come from WebTools.kt. web.fetch itself is untouched.
// ===========================================================================

/** The DuckDuckGo endpoint. Fixed here, never taken from the model, so the destination is never a model choice. */
internal const val SEARCH_HOST: String = "html.duckduckgo.com"
internal const val SEARCH_ENDPOINT: String = "https://$SEARCH_HOST/html/"

/**
 * A normal mobile browser identity for the HTML services. DuckDuckGo's HTML endpoint
 * serves a bot challenge to clients that announce themselves as libraries, so the
 * HTML services get this one. Wikipedia does not: its User-Agent policy treats a copied
 * browser identity as malicious, so it gets [INFO_USER_AGENT] instead.
 */
internal const val SEARCH_USER_AGENT: String =
    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

/**
 * The whole web.search budget. A person is waiting, so the chain stops asking new
 * services after this, and a service started just before it gets only the time left.
 */
internal const val SEARCH_BUDGET_MS: Long = 20_000L

/** One service's share of the budget, so a service that hangs does not use up the turn of one that works. */
internal const val SEARCH_SERVICE_MS: Long = 6_000L

/** How long a service that blocked us is skipped for the rest of the process. */
internal const val SEARCH_COOLDOWN_MS: Long = 5L * 60L * 1000L

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

/** Tags stripped, entities decoded, whitespace collapsed, for one title or snippet fragment. */
internal fun cleanSearchText(fragment: String): String =
    HtmlText.toPlainText(fragment, CLEAN_BUDGET, contentType = "text/html")
        .text
        .replace(Regex("\\s+"), " ")
        .trim()

/**
 * The address a result link points at, or null when it is not a page we should send
 * the model to. Only http(s) survives [WebUrls.validate], and a link back into the
 * service itself (its own ads and redirects) is dropped, as is a private address.
 */
internal fun directResultUrl(raw: String, ownHosts: List<String>): String? {
    val href = cleanSearchText(raw).trim()
    val absolute = if (href.startsWith("//")) "https:$href" else href
    val verdict = WebUrls.validate(absolute) as? UrlVerdict.Accepted ?: return null
    if (ownHosts.any { verdict.host == it || verdict.host.endsWith(".$it") }) return null
    return verdict.url
}

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
    private fun clean(fragment: String): String = cleanSearchText(fragment)

    /**
     * True when DuckDuckGo answered with its bot challenge instead of results.
     * Used to say "blocked" rather than "no results", which would be a lie.
     */
    fun isBotChallenge(html: String): Boolean =
        html.contains("anomaly-modal", ignoreCase = true) || html.contains("captcha", ignoreCase = true)
}

// ----------------------------------------------------------------------------
// The other free services. Each one is a parser over a page it does not own, so
// each degrades the same way: a page it cannot read is zero hits, never a throw.
// ----------------------------------------------------------------------------

/**
 * Bing's results page. One `li.b_algo` per result: the title and link are the `h2`
 * inside the `b_algoheader` anchor, and the snippet is the first `p` in the caption.
 *
 * The first anchor in each block is an icon link with no heading, so the match
 * requires the `h2` right after the anchor's `>`, which skips it.
 */
internal object BingHtml {

    private val HEADING = Regex(
        "<a\\b[^>]*?\\bhref=\"([^\"]*)\"[^>]*>\\s*<h2[^>]*>(.*?)</h2>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val CAPTION = Regex(
        "class=\"b_caption\"[^>]*>.*?<p\\b[^>]*>(.*?)</p>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    /** Bing sometimes wraps a link as `bing.com/ck/a?...&u=a1<base64url>`; the target is in `u`. */
    private val WRAPPED = Regex("[?&]u=a1([A-Za-z0-9_\\-]+)")
    private val OWN_HOSTS = listOf("bing.com")

    fun parse(html: String, limit: Int): List<SearchHit> {
        val hits = mutableListOf<SearchHit>()
        for (block in html.split("class=\"b_algo\"").drop(1)) {
            if (hits.size >= limit) break
            val part = block.substringBefore("</li>")
            val heading = HEADING.find(part) ?: continue
            val title = cleanSearchText(heading.groupValues[2])
            val url = resultUrl(heading.groupValues[1]) ?: continue
            if (title.isEmpty()) continue
            val snippet = CAPTION.find(part)?.groupValues?.get(1)?.let(::cleanSearchText).orEmpty()
            hits += SearchHit(title, url, snippet)
        }
        return hits
    }

    private fun resultUrl(raw: String): String? {
        val href = cleanSearchText(raw)
        val target = if (href.contains("bing.com/ck/a")) {
            val encoded = WRAPPED.find(href)?.groupValues?.get(1) ?: return null
            decodeBase64Url(encoded) ?: return null
        } else {
            href
        }
        return directResultUrl(target, OWN_HOSTS)
    }

    private fun decodeBase64Url(encoded: String): String? = try {
        val padded = encoded + "=".repeat((4 - encoded.length % 4) % 4)
        String(java.util.Base64.getUrlDecoder().decode(padded), Charsets.UTF_8)
    } catch (e: IllegalArgumentException) {
        null
    }
}

/**
 * Brave Search's HTML results page. Each web result is a `div` with `data-type="web"`;
 * the link is the first anchor in it, the title is the `title` div, and the snippet is
 * the `content` div. Brave puts a relative age ("17 hours ago -") in front of some
 * snippets, which is removed because it is not what the page says.
 *
 * News, video and ad blocks use other `data-type` values and are never read.
 */
internal object BraveHtml {

    private val LINK = Regex("<a\\s[^>]*?href=\"([^\"]*)\"", RegexOption.IGNORE_CASE)
    private val TITLE = Regex(
        "class=\"title[ \"][^>]*>(.*?)</div>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val CONTENT = Regex(
        "class=\"content [^\"]*\"[^>]*>(.*?)</div>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val AGE_PREFIX = Regex("^\\d+\\s+\\w+\\s+ago\\s*-\\s*")
    private val OWN_HOSTS = listOf("search.brave.com", "brave.com")

    fun parse(html: String, limit: Int): List<SearchHit> {
        val hits = mutableListOf<SearchHit>()
        for (block in html.split("data-type=\"web\"").drop(1)) {
            if (hits.size >= limit) break
            // Cut at the next block of any kind, so a snippet is never read from a news or ad block.
            val part = block.substringBefore("data-type=\"")
            val link = LINK.find(part)?.groupValues?.get(1) ?: continue
            val url = directResultUrl(link, OWN_HOSTS) ?: continue
            val title = TITLE.find(part)?.groupValues?.get(1)?.let(::cleanSearchText).orEmpty()
            if (title.isEmpty()) continue
            val snippet = CONTENT.find(part)?.groupValues?.get(1)
                ?.let(::cleanSearchText)
                ?.replace(AGE_PREFIX, "")
                .orEmpty()
            hits += SearchHit(title, url, snippet)
        }
        return hits
    }
}

/**
 * Wikipedia's search API (`action=query&list=search`). A JSON reply, so no HTML is
 * scraped. The result link is built from the title, because the API returns no link:
 * spaces become underscores, the title is percent-encoded, and the host is the
 * language subdomain. A reply that is not the documented shape is zero hits.
 */
internal class WikipediaSearch(
    /** The language subdomain, already checked by [wikipediaLanguage]. */
    val language: String,
) : FreeSearchService {

    override val label: String = "Wikipedia"
    override val id: String = "wikipedia"
    // WHY NOT SEARCH_USER_AGENT: Wikimedia's User-Agent policy treats a copied browser
    // identity as malicious. InfoTools' identifying string is the one WikiTool already sends.
    override val userAgent: String = INFO_USER_AGENT

    override fun url(query: String, maxResults: Int): String =
        "https://$language.wikipedia.org/w/api.php?action=query&list=search&format=json" +
            "&srlimit=$maxResults&srsearch=" + URLEncoder.encode(query, "UTF-8")

    override fun parse(body: String, limit: Int): List<SearchHit> {
        val root = try {
            Json.parseToJsonElement(body) as? JsonObject
        } catch (e: Exception) {
            null
        } ?: return emptyList()
        val results = ((root["query"] as? JsonObject)?.get("search") as? JsonArray) ?: return emptyList()
        val hits = mutableListOf<SearchHit>()
        for (element in results) {
            if (hits.size >= limit) break
            val item = element as? JsonObject ?: continue
            val title = cleanSearchText(text(item, "title"))
            if (title.isEmpty()) continue
            val slug = URLEncoder.encode(title.replace(' ', '_'), "UTF-8")
            val url = WebUrls.validate("https://$language.wikipedia.org/wiki/$slug") as? UrlVerdict.Accepted ?: continue
            hits += SearchHit(title, url.url, cleanSearchText(text(item, "snippet")))
        }
        return hits
    }

    private fun text(item: JsonObject, key: String): String =
        (item[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
}

/**
 * The language subdomain Wikipedia is asked on, from the device language. Anything that
 * is not a plain language code becomes English: a host is built from this value, so it
 * must never carry a model's or a device's odd string into a URL.
 */
internal fun wikipediaLanguage(tag: String): String {
    val code = tag.lowercase(Locale.ROOT)
    return if (Regex("^[a-z]{2,3}$").matches(code)) code else "en"
}

// ----------------------------------------------------------------------------
// The chain
// ----------------------------------------------------------------------------

/**
 * One free service in the web.search chain. Each one is a URL, a parser, and the
 * identity it is asked with. Adding or removing a service is one line in [freeSearchChain].
 */
internal interface FreeSearchService {
    /** The name shown as the source of a result, e.g. "Bing". */
    val label: String

    /** The key for the cooldown map. */
    val id: String

    /** The identity the request is sent with. */
    val userAgent: String

    fun url(query: String, maxResults: Int): String

    /** The hits in [body]. An unreadable body is zero hits, never an exception. */
    fun parse(body: String, limit: Int): List<SearchHit>

    /**
     * True when a page with zero hits is a block, not an honest empty result. Only asked
     * when [parse] found nothing, so a page with results is never read as a block.
     */
    fun isBlockPage(body: String): Boolean = false
}

/** DuckDuckGo's HTML results page. Bot challenges are the case the chain exists for. */
internal object DuckDuckGoSearch : FreeSearchService {
    override val label: String = "DuckDuckGo"
    override val id: String = "duckduckgo"
    override val userAgent: String = SEARCH_USER_AGENT

    override fun url(query: String, maxResults: Int): String =
        SEARCH_ENDPOINT + "?q=" + URLEncoder.encode(query, "UTF-8")

    override fun parse(body: String, limit: Int): List<SearchHit> = DuckDuckGoHtml.parse(body, limit)

    override fun isBlockPage(body: String): Boolean = DuckDuckGoHtml.isBotChallenge(body)
}

internal object BingSearch : FreeSearchService {
    override val label: String = "Bing"
    override val id: String = "bing"
    override val userAgent: String = SEARCH_USER_AGENT

    override fun url(query: String, maxResults: Int): String =
        "https://www.bing.com/search?q=" + URLEncoder.encode(query, "UTF-8")

    override fun parse(body: String, limit: Int): List<SearchHit> = BingHtml.parse(body, limit)

    override fun isBlockPage(body: String): Boolean = isChallengePage(body)
}

internal object BraveSearch : FreeSearchService {
    override val label: String = "Brave Search"
    override val id: String = "brave"
    override val userAgent: String = SEARCH_USER_AGENT

    override fun url(query: String, maxResults: Int): String =
        "https://search.brave.com/search?q=" + URLEncoder.encode(query, "UTF-8")

    override fun parse(body: String, limit: Int): List<SearchHit> = BraveHtml.parse(body, limit)

    override fun isBlockPage(body: String): Boolean = isChallengePage(body)
}

/** Words a block page carries and an empty results page does not. Checked only on zero hits. */
private fun isChallengePage(body: String): Boolean =
    body.contains("captcha", ignoreCase = true) ||
        body.contains("unusual traffic", ignoreCase = true) ||
        body.contains("are you a robot", ignoreCase = true)

/**
 * The order of the free chain. The default is DuckDuckGo, then the two HTML services that
 * parse, then Wikipedia. Startpage and Qwant were probed and left out: Startpage answers
 * with a proof-of-work challenge and Qwant's results are rendered by script. To drop a
 * service, delete its line.
 */
internal fun freeSearchChain(language: String): List<FreeSearchService> = listOf(
    DuckDuckGoSearch,
    BingSearch,
    BraveSearch,
    WikipediaSearch(language),
)

/**
 * Services that blocked us recently, kept in memory for [SEARCH_COOLDOWN_MS]. A chain
 * that is blocked for a whole session would otherwise ask the same service on every
 * search. Monotonic time, so a clock change cannot shorten or extend a cooldown.
 */
internal object SearchCooldown {
    private val skipUntil = ConcurrentHashMap<String, Long>()

    fun isCoolingDown(id: String): Boolean {
        val until = skipUntil[id] ?: return false
        return System.nanoTime() - until < 0
    }

    fun markBlocked(id: String) {
        skipUntil[id] = System.nanoTime() + SEARCH_COOLDOWN_MS * 1_000_000L
    }

    fun clear() = skipUntil.clear()
}

/** Puts services on cooldown as if they had blocked us, so each step of the chain can be tested. Debug control only. */
fun blockSearchServicesForTest(ids: List<String>) = ids.forEach(SearchCooldown::markBlocked)

/**
 * Where each service's request goes instead of its real address (service id to base address), so an emulator
 * test can make a service answer, hang, block or fail on purpose. Empty in every normal run, and only the debug
 * build's control channel sets it. Setting it also clears the cooldowns, so a test starts from a known chain.
 */
@Volatile
private var searchRoutes: Map<String, String> = emptyMap()

fun setSearchRoutesForTest(routes: Map<String, String>) {
    searchRoutes = routes
    SearchCooldown.clear()
}

/** [url] with its address replaced by the test route of [serviceId], keeping the query string. */
internal fun routedForTest(serviceId: String, url: String): String {
    val base = searchRoutes[serviceId] ?: return url
    val query = url.substringAfter('?', "")
    return if (query.isEmpty()) base else "$base?$query"
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

/** Builds the opener for one request. Production passes the timeouts left in the budget; a test passes a script. */
internal typealias SearchOpenerFactory = (userAgent: String, connectTimeoutMs: Int, readTimeoutMs: Int) -> ResponseOpener

/** What one service said. Only [Found] ends the chain early. */
internal sealed interface ServiceAttempt {
    class Found(val hits: List<SearchHit>) : ServiceAttempt

    /** The service answered and had nothing for this query. Not a failure, so it is said. */
    data object Empty : ServiceAttempt

    /** One short line for the log. Holds no query text. */
    fun describe(): String = when (this) {
        is Found -> "${hits.size} results"
        Empty -> "no results"
        is Blocked -> "blocked ($reason)"
        is Failed -> "failed ($reason)"
        Cancelled -> "cancelled"
    }

    /** A bot check or a refusal. The service goes on cooldown. */
    class Blocked(val reason: String) : ServiceAttempt

    /** A timeout, a network error or an odd status. The next service is asked. */
    class Failed(val reason: String) : ServiceAttempt

    data object Cancelled : ServiceAttempt
}

/**
 * The whole search, minus the coroutine. Blocking; the tool calls it on
 * [Dispatchers.IO]. Every exit is a [ToolResult], as in [WebFetcher].
 *
 * The chain is [freeSearchChain]. The first service with results wins. A service that
 * gives a block, an error, a timeout or zero results hands over to the next one, and a
 * service that blocked is skipped for [SEARCH_COOLDOWN_MS]. The whole chain shares one
 * [SEARCH_BUDGET_MS]. When every service failed, the person hears one plain sentence, and
 * the model is not told to retry, because a retry only hits the same blocks again.
 */
internal class WebSearcher(
    private val openerFor: SearchOpenerFactory,
    /** Read once per search. Wikipedia's language is the only thing it changes. */
    private val language: String = wikipediaLanguage(Locale.getDefault().language),
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

            val startedAt = System.nanoTime()
            val answered = mutableListOf<String>()
            for (service in freeSearchChain(language)) {
                val leftMs = SEARCH_BUDGET_MS - (System.nanoTime() - startedAt) / 1_000_000L
                if (leftMs <= 0L) break
                if (SearchCooldown.isCoolingDown(service.id)) continue

                val attempt = attempt(service, query, maxResults, context, minOf(leftMs, SEARCH_SERVICE_MS))
                // Which service did what, so the chain can be checked. Never the query: it is private.
                Log.i("WebSearch", "${service.label}: ${attempt.describe()}")
                when (attempt) {
                    is ServiceAttempt.Found -> return found(query, attempt.hits, service.label)
                    ServiceAttempt.Empty -> answered += service.label
                    is ServiceAttempt.Blocked -> SearchCooldown.markBlocked(service.id)
                    is ServiceAttempt.Failed -> Unit
                    ServiceAttempt.Cancelled -> return cancelled()
                }
            }

            // Every service either had nothing or failed. "Nothing" from at least one of them is
            // an honest answer; a total failure is the one sentence that tells the person what to do.
            if (answered.isNotEmpty()) {
                return ToolResult(
                    success = true,
                    observation = "No results for \"$query\" from ${answered.joinToString(" or ")}. " +
                        "Try different or fewer words.",
                    data = buildJsonObject {
                        put("query", JsonPrimitive(query))
                        put("results", JsonPrimitive(0))
                    },
                )
            }
            return ToolResult(
                success = false,
                observation = SEARCH_UNAVAILABLE,
                error = ToolError.Unavailable("every search service failed"),
            )
        } catch (e: SecurityException) {
            return ToolResult(
                success = false,
                observation = "Android refused the network request for web search " +
                    "(${e.javaClass.simpleName}). The device is missing the INTERNET permission " +
                    "or a network policy blocked it.",
                error = ToolError.PermissionDenied("SecurityException: ${e.javaClass.simpleName}"),
            )
        }
    }

    /**
     * One service, one request, with the redirect rule of web.fetch: at most MAX_REDIRECTS
     * hops, each re-validated, and only to the same host or its www twin. Every failure is
     * an [ServiceAttempt], never a throw, so the chain can go on to the next service.
     * [SecurityException] is the one exception left alone: a missing INTERNET permission
     * applies to every service, so [search] reports it once.
     */
    private fun attempt(
        service: FreeSearchService,
        query: String,
        maxResults: Int,
        context: ToolContext,
        leftMs: Long,
    ): ServiceAttempt {
        // The timeouts never exceed what is left of the budget, so one slow service cannot take the rest.
        val connectMs = minOf(CONNECT_TIMEOUT_MS.toLong(), leftMs).toInt()
        val readMs = minOf(READ_TIMEOUT_MS.toLong(), leftMs).toInt()
        val opener = openerFor(service.userAgent, connectMs, readMs)
        val endpoint = routedForTest(service.id, service.url(query, maxResults))
        var current = endpoint
        var redirects = 0
        var response: OpenedResponse? = null
        try {
            while (true) {
                response?.close()
                val opened = opener.open(current, context.signal)
                response = opened
                if (context.signal.isCancelled()) return ServiceAttempt.Cancelled

                if (isRedirectStatus(opened.status)) {
                    when (val hop = WebUrls.resolveRedirect(current, opened.location, redirects)) {
                        is RedirectVerdict.Rejected -> return ServiceAttempt.Failed(hop.reason)
                        is RedirectVerdict.Follow -> {
                            current = hop.url
                            redirects = hop.redirects
                        }
                    }
                    continue
                }

                if (opened.status !in 200..299) {
                    // A refusal or an overloaded service. Both mean: stop asking it for a while.
                    return ServiceAttempt.Blocked("${service.label} answered HTTP ${opened.status}")
                }

                val body = BodyReader.readCapped(opened.body, SEARCH_MAX_BODY_BYTES, context.signal)
                if (body.cancelled) return ServiceAttempt.Cancelled
                val text = String(body.bytes, WebFetchContent.charsetOf(opened.contentType))

                val hits = service.parse(text, maxResults)
                if (hits.isEmpty()) {
                    return if (service.isBlockPage(text)) {
                        ServiceAttempt.Blocked("${service.label} showed a block page")
                    } else {
                        ServiceAttempt.Empty
                    }
                }
                return ServiceAttempt.Found(hits)
            }
        } catch (e: UnknownHostException) {
            return ServiceAttempt.Failed("${service.label}: no such host")
        } catch (e: SocketTimeoutException) {
            return ServiceAttempt.Failed("${service.label}: timed out")
        } catch (e: SSLException) {
            return ServiceAttempt.Failed("${service.label}: secure connection failed")
        } catch (e: IOException) {
            return ServiceAttempt.Failed("${service.label}: ${e.javaClass.simpleName}")
        } catch (e: SecurityException) {
            throw e
        } catch (e: RuntimeException) {
            // A parser or platform bug in one service must not stop the chain. Type only, never a trace.
            return ServiceAttempt.Failed("${service.label}: ${e.javaClass.simpleName}")
        } finally {
            response?.close()
        }
    }

    private fun found(query: String, hits: List<SearchHit>, source: String): ToolResult = ToolResult(
        success = true,
        observation = ObservationTruncator.truncate(SearchObservations.render(query, hits, source = source)),
        data = buildJsonObject {
            put("query", JsonPrimitive(query))
            put("results", JsonPrimitive(hits.size))
            put("source", JsonPrimitive(source))
        },
    )

    private fun cancelled(): ToolResult = ToolResult(
        success = false,
        observation = "Cancelled before the search finished.",
        error = ToolError.Cancelled("signal raised during search"),
    )
}

/**
 * The one sentence the person and the model get when no service answered. It says what to
 * do next, and it does not name a block or tell the model to retry.
 */
internal const val SEARCH_UNAVAILABLE: String =
    "Search is not available right now. Answer from what you know and say it may be out of date, " +
        "or tell the person to try again later."

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
    private val openerFor: SearchOpenerFactory,
    private val grant: PlatformGrant,
    /** Read live, as OpenRouterPlugin reads its key: a provider change applies to the next search. */
    private val settings: SettingsReader,
) : AgentTool {

    constructor(grant: PlatformGrant, settings: SettingsReader) :
        this(httpSearchOpeners, grant, settings)

    // WHY no weather example and the pointer to weather.get: with "weather amsterdam" as the
    // example query, LFM3-1.7B sent weather questions here although weather.get was in
    // tools.find's listing ("There's no direct weather.get listed in my primary tools").
    override val definition: ToolDefinition = ToolDefinition(
        name = "web.search",
        description = "Searches the web and returns the top results as numbered titles, links and " +
            "short snippets. Use web.fetch on a link to read the page. For weather, use weather.get.",
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
                                JsonPrimitive("The search words, for example \"rijksmuseum opening hours\"."),
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
                    ?: return@withContext invalid("No query argument. Pass the search words, for example query=\"rijksmuseum opening hours\".")
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
                when (val provider = searchProviderFor(settings)) {
                    SearchProvider.DUCKDUCKGO -> WebSearcher(openerFor).search(compact, maxResults, context, grant)
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
            CredentialSource.searchKey(settings, provider).orEmpty()
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
        /** Production: one real connection per request, with the timeouts left in the budget. */
        internal val httpSearchOpeners: SearchOpenerFactory = { userAgent, connectMs, readMs ->
            HttpUrlConnectionOpener(userAgent = userAgent, connectTimeoutMs = connectMs, readTimeoutMs = readMs)
        }

        /** Test seam: a [WebSearchTool] backed by a scripted transport, the same one for every service. */
        fun withOpener(opener: ResponseOpener, grant: PlatformGrant): WebSearchTool =
            WebSearchTool({ _, _, _ -> opener }, grant, SettingsReader.DEFAULTS)
    }
}

/**
 * The provider a search uses. Simple mode has no provider choice, so it reads DuckDuckGo;
 * with Extras active that Simple default becomes Exa, the managed search. A Technical choice
 * always stands. A keyed provider that fails is reported as a failure and never replaced; the
 * DuckDuckGo default is the one that falls back, through the free chain in [freeSearchChain].
 */
internal fun searchProviderFor(reader: SettingsReader): SearchProvider {
    val chosen = SearchProvider.fromId(reader[WebSearchSettings.PROVIDER])
    val simpleDefault = chosen == SearchProvider.DUCKDUCKGO && reader.mode == SettingsMode.SIMPLE
    return if (simpleDefault && CredentialSource.extrasSearchAvailable(reader)) SearchProvider.EXA else chosen
}
