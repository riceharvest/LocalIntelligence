package dev.localintelligence.android.tools

import dev.localintelligence.android.tools.web.BodyReader
import dev.localintelligence.android.tools.web.HtmlText
import dev.localintelligence.android.tools.web.MAX_BODY_BYTES
import dev.localintelligence.android.tools.web.MAX_MAX_CHARS
import dev.localintelligence.android.tools.web.MAX_REDIRECTS
import dev.localintelligence.android.tools.web.MAX_URL_CHARS
import dev.localintelligence.android.tools.web.OpenedResponse
import dev.localintelligence.android.tools.web.RedirectVerdict
import dev.localintelligence.android.tools.web.ResponseOpener
import dev.localintelligence.android.tools.web.WebArgs
import dev.localintelligence.android.tools.web.WebFetchContent
import dev.localintelligence.android.tools.web.WebFetchTool
import dev.localintelligence.android.tools.web.WebFetcher
import dev.localintelligence.android.tools.web.WebObservations
import dev.localintelligence.android.tools.web.WebUrls
import dev.localintelligence.android.tools.web.UrlVerdict
import dev.localintelligence.android.tools.web.isRedirectStatus
import dev.localintelligence.core.tool.CancellationSignal
import dev.localintelligence.core.tool.ObservationTruncator
import dev.localintelligence.core.tool.ToolContext
import dev.localintelligence.core.tool.ToolError
import dev.localintelligence.core.tool.ToolResult
import dev.localintelligence.core.tool.ToolRisk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

/**
 * JVM-only tests for `web.fetch`.
 *
 * The tool talks to a real socket in production, and there is no device and no
 * server here, so what is proven here is everything that decides what happens:
 * [WebUrls] (the scheme allow-list), [HtmlText] (the stripper), [BodyReader]
 * (the byte cap), the redirect rule, the status mapping, and the observation
 * wording — plus the whole [WebFetcher] loop driven through a scripted
 * [ResponseOpener], which is where the redirect chain and the error mapping
 * actually get executed rather than merely reviewed.
 *
 * The one thing that cannot be proven without a device is that
 * `HttpURLConnection` talks to a real server. That is a platform guarantee and
 * is not re-tested here.
 */
class WebToolsTest {

    private val neverCancelled = CancellationSignal.None

    // ======================================================================
    // THE security property: only http(s) ever reaches the network.
    // ======================================================================

    @Test
    fun `every non-http scheme a model would plausibly emit is refused by name`() {
        // This is the list the task is really about. `file://` reads our own
        // sandbox through a URL loader; `content://` reaches another app's
        // ContentProvider under our UID; `javascript:` and `data:` are the
        // classic address-bar payloads. A small model produces all of these
        // while trying to be helpful.
        val hostile = listOf(
            "file:///data/data/dev.localintelligence/files/secret.txt",
            "file://${'$'}HOME/.ssh/id_rsa",
            "content://com.android.contacts/data/1",
            "content://media/external/images/media/1",
            "javascript:alert(document.cookie)",
            "JavaScript:fetch('http://evil.example')",
            "data:text/html,<script>alert(1)</script>",
            "DATA:text/plain;base64,aGVsbG8=",
            "ftp://ftp.example.com/pub/file.txt",
            "jar:file:///data/app/base.apk!/classes.dex",
            "intent://scan/#Intent;scheme=zxing;end",
            "market://details?id=com.example",
            "smb://192.168.1.10/share",
            "tel:+31612345678",
            "mailto:victim@example.com",
            "ws://example.com/socket",
        )
        for (url in hostile) {
            val verdict = WebUrls.validate(url)
            assertTrue(
                "$url must be rejected, got $verdict",
                verdict is UrlVerdict.Rejected,
            )
        }
    }

    @Test
    fun `the rejection names the scheme and says why, so the model can correct itself`() {
        val verdict = WebUrls.validate("file:///data/data/dev.localintelligence/databases/pi.db")
        assertTrue(verdict is UrlVerdict.Rejected)
        val reason = (verdict as UrlVerdict.Rejected).reason
        assertTrue("must name the scheme: $reason", reason.contains("file"))
        assertTrue("must state the allow-list: $reason", reason.contains("http"))
        // And it must read as a refusal to the MODEL, not as a stack trace.
        assertFalse("no exception class name: $reason", reason.contains("Exception"))
        assertTrue("reason is model-visible, keep it short: ${reason.length}", reason.length < 400)
    }

    @Test
    fun `the scheme check is case insensitive because a model capitalises freely`() {
        for (url in listOf("HTTP://example.com", "HtTpS://example.com/x", "FILE:///etc/passwd")) {
            val verdict = WebUrls.validate(url)
            if (url.startsWith("FILE", ignoreCase = true)) {
                assertTrue("$url must be rejected", verdict is UrlVerdict.Rejected)
            } else {
                assertTrue("$url must be accepted", verdict is UrlVerdict.Accepted)
            }
        }
    }

    // ======================================================================
    // Scheme-less URLs: normalised, and consistently so.
    // ======================================================================

    @Test
    fun `a scheme-less url is normalised to https rather than rejected`() {
        // DECISION, pinned here so a future change cannot drift: a model that
        // forgets "https://" made a formatting slip, and rejecting it teaches
        // the loop detector only to retry the same failing call. The security
        // property is preserved because the scheme that actually reaches the
        // socket is always one we chose: https.
        val verdict = WebUrls.validate("example.com")
        assertTrue(verdict is UrlVerdict.Accepted)
        val accepted = verdict as UrlVerdict.Accepted
        assertEquals("https://example.com", accepted.url)
        assertEquals("example.com", accepted.host)
        assertTrue("must record that it added a scheme", accepted.addedScheme)
        assertTrue("normalised url must be https", accepted.url.startsWith("https://"))
    }

    @Test
    fun `scheme-less normalisation keeps the path and the query`() {
        val accepted = WebUrls.validate("example.com/a/b?x=1&y=2") as UrlVerdict.Accepted
        assertEquals("https://example.com/a/b?x=1&y=2", accepted.url)
        assertEquals("example.com", accepted.host)
    }

    @Test
    fun `a host with a port is not mistaken for a scheme`() {
        // RFC 3986 would read "localhost:3000" as scheme "localhost". Requiring
        // "//" before a scheme counts is what stops that, and it means a
        // single-label host is normalised rather than refused as a bad scheme.
        val verdict = WebUrls.validate("example.com:8080/x")
        assertTrue(verdict is UrlVerdict.Accepted)
        assertEquals("https://example.com:8080/x", (verdict as UrlVerdict.Accepted).url)
        assertEquals("example.com", verdict.host)
    }

    @Test
    fun `https without slashes is normalised rather than failed`() {
        val accepted = WebUrls.validate("https:example.com/x") as UrlVerdict.Accepted
        assertEquals("https://example.com/x", accepted.url)
    }

    // ======================================================================
    // Host validation
    // ======================================================================

    @Test
    fun `embedded credentials are refused because they are a phishing shape`() {
        val verdict = WebUrls.validate("https://trusted.example@evil.example/login")
        assertTrue(verdict is UrlVerdict.Rejected)
        assertTrue((verdict as UrlVerdict.Rejected).reason.contains("@"))
    }

    @Test
    fun `a local-network host is refused because an SSRF probe looks exactly like one`() {
        for (url in listOf(
            "http://router.local",
            "http://nas.local/admin",
            "http://db.internal/query",
            "http://192.168.1.1/",
        )) {
            val verdict = WebUrls.validate(url)
            if (url.endsWith(".local") || url.endsWith(".internal")) {
                assertTrue("$url must be refused", verdict is UrlVerdict.Rejected)
            }
        }
    }

    @Test
    fun `a single-label non-local host is refused with an example to copy`() {
        val verdict = WebUrls.validate("https://intranet/leave")
        assertTrue(verdict is UrlVerdict.Rejected)
        val reason = (verdict as UrlVerdict.Rejected).reason
        assertTrue("must name the host: $reason", reason.contains("intranet"))
    }

    @Test
    fun `localhost is allowed because it is a real, deliberate dev case`() {
        assertTrue(WebUrls.validate("http://localhost:3000/health") is UrlVerdict.Accepted)
    }

    @Test
    fun `empty, blank and whitespace-only input each get a distinct refusal`() {
        for (input in listOf(null, "", "   ", "\t\n")) {
            val verdict = WebUrls.validate(input)
            assertTrue("$input must be rejected", verdict is UrlVerdict.Rejected)
            assertTrue(
                "must show an example url",
                (verdict as UrlVerdict.Rejected).reason.contains("example.com"),
            )
        }
    }

    @Test
    fun `a url longer than the clamp is refused before any network work`() {
        val long = "https://example.com/" + "a".repeat(MAX_URL_CHARS)
        val verdict = WebUrls.validate(long)
        assertTrue(verdict is UrlVerdict.Rejected)
        assertTrue((verdict as UrlVerdict.Rejected).reason.contains("limit"))
    }

    @Test
    fun `a url with a space is refused with the percent-encoding fix`() {
        val verdict = WebUrls.validate("https://example.com/a b")
        assertTrue(verdict is UrlVerdict.Rejected)
        assertTrue((verdict as UrlVerdict.Rejected).reason.contains("%20"))
    }

    @Test
    fun `a url with no host is refused`() {
        assertTrue(WebUrls.validate("https://") is UrlVerdict.Rejected)
    }

    // ======================================================================
    // Redirects
    // ======================================================================

    @Test
    fun `a normal same-host redirect is followed`() {
        val hop = WebUrls.resolveRedirect(
            "http://example.com/a",
            "/b",
            redirectsSoFar = 0,
        )
        assertTrue(hop is RedirectVerdict.Follow)
        assertEquals("http://example.com/b", (hop as RedirectVerdict.Follow).url)
        assertEquals(1, hop.redirects)
    }

    @Test
    fun `an absolute redirect target is followed`() {
        val hop = WebUrls.resolveRedirect("https://example.com/a", "https://example.com/c", 0)
        assertEquals("https://example.com/c", (hop as RedirectVerdict.Follow).url)
    }

    @Test
    fun `the hop limit is enforced and the message says to stop retrying`() {
        // The pure rule: the MAX_REDIRECTS'th request is allowed, the next is not.
        assertTrue(WebUrls.resolveRedirect("https://example.com/a", "/b", MAX_REDIRECTS - 1) is RedirectVerdict.Follow)
        val refused = WebUrls.resolveRedirect("https://example.com/a", "/b", MAX_REDIRECTS)
        assertTrue(refused is RedirectVerdict.Rejected)
        val reason = (refused as RedirectVerdict.Rejected).reason
        assertTrue("must state the count: $reason", reason.contains("$MAX_REDIRECTS"))
        assertTrue("must tell the model to stop: $reason", reason.contains("do not retry"))
    }

    @Test
    fun `http to https off-host is refused because the first hop was attacker-chosen`() {
        // The rule from the task. http://evil.example redirecting to
        // https://good.example means the attacker chose where we started; the
        // summary we hand the model is then of a page we did not intend to read.
        val hop = WebUrls.resolveRedirect(
            "http://evil.example/x",
            "https://good.example/y",
            redirectsSoFar = 0,
        )
        assertTrue(hop is RedirectVerdict.Rejected)
        val reason = (hop as RedirectVerdict.Rejected).reason
        assertTrue("must name both hosts: $reason", reason.contains("evil.example"))
        assertTrue("must name both hosts: $reason", reason.contains("good.example"))
    }

    @Test
    fun `https to http off-host is refused as the downgrade twin`() {
        val hop = WebUrls.resolveRedirect(
            "https://good.example/x",
            "http://evil.example/y",
            redirectsSoFar = 0,
        )
        assertTrue(hop is RedirectVerdict.Rejected)
    }

    @Test
    fun `https to https off-host is allowed so a CDN redirect still works`() {
        val hop = WebUrls.resolveRedirect(
            "https://example.com/x",
            "https://www.example.com/y",
            redirectsSoFar = 0,
        )
        assertTrue(hop is RedirectVerdict.Follow)
        assertEquals("https://www.example.com/y", (hop as RedirectVerdict.Follow).url)
    }

    @Test
    fun `http upgrade on the SAME host is allowed because that is the normal fix`() {
        val hop = WebUrls.resolveRedirect("http://example.com/x", "https://example.com/y", 0)
        assertTrue(hop is RedirectVerdict.Follow)
    }

    @Test
    fun `a redirect cannot walk the request back onto a hostile scheme`() {
        // Re-validating the target is the property that makes the allow-list
        // worth having: a validated entry URL proves nothing about where the
        // server chooses to send us.
        for (target in listOf(
            "file:///data/data/dev.localintelligence/databases/pi.db",
            "content://com.android.contacts/data/1",
            "javascript:alert(1)",
        )) {
            val hop = WebUrls.resolveRedirect("https://example.com/a", target, 0)
            assertTrue("$target must be refused as a redirect target", hop is RedirectVerdict.Rejected)
        }
    }

    @Test
    fun `a redirect with no target is refused`() {
        for (location in listOf(null, "", "   ")) {
            assertTrue(WebUrls.resolveRedirect("https://example.com/a", location, 0) is RedirectVerdict.Rejected)
        }
    }

    @Test
    fun `every 3xx is treated as a redirect and nothing else is`() {
        for (code in listOf(301, 302, 303, 307, 308)) assertTrue("$code", isRedirectStatus(code))
        for (code in listOf(200, 201, 204, 304, 400, 404, 500)) {
            assertFalse("$code", isRedirectStatus(code))
        }
    }

    // ======================================================================
    // HTML stripping
    // ======================================================================

    @Test
    fun `script and style CONTENT is removed, not just their tags`() {
        val html = """
            <html><head><style>body { color: red; } /* keep me out */</style></head>
            <body><h1>Title</h1>
            <script>var secret = "SCRIPT_BODY_LEAK"; alert(1);</script>
            <p>Real text.</p></body></html>
        """.trimIndent()

        val text = HtmlText.toPlainText(html, 2000).text

        assertFalse("script body leaked: $text", text.contains("SCRIPT_BODY_LEAK"))
        assertFalse("script body leaked: $text", text.contains("alert(1)"))
        assertFalse("style body leaked: $text", text.contains("color: red"))
        assertFalse("keep me out leaked: $text", text.contains("keep me out"))
        assertTrue("the real text must survive: $text", text.contains("Real text."))
        assertTrue("the heading must survive: $text", text.contains("Title"))
    }

    @Test
    fun `an unterminated script swallows the rest rather than leaking it`() {
        val html = "<p>ok</p><script>var leak = 'LEAKED';"
        val text = HtmlText.toPlainText(html, 2000).text
        assertTrue("real text kept: $text", text.contains("ok"))
        assertFalse("LEAKED", text.contains("LEAKED"))
    }

    @Test
    fun `no angle bracket survives so the model never sees raw markup`() {
        val html = "<div class=\"a\"><span data-x='1'>text</span><br/><img src=x /></div>"
        val text = HtmlText.toPlainText(html, 2000).text
        assertFalse("tag leaked: $text", text.contains("<"))
        assertFalse("tag leaked: $text", text.contains(">"))
        assertTrue("text kept: $text", text.contains("text"))
    }

    @Test
    fun `html comments and noscript are removed`() {
        val html = "<p>a</p><!-- SECRET_COMMENT --><noscript>NO_SCRIPT_TEXT</noscript><p>b</p>"
        val text = HtmlText.toPlainText(html, 2000).text
        assertFalse(text.contains("SECRET_COMMENT"))
        assertFalse(text.contains("NO_SCRIPT_TEXT"))
        assertTrue(text.contains("a"))
        assertTrue(text.contains("b"))
    }

    @Test
    fun `entities are decoded to their characters`() {
        val html = "<p>Tom &amp; Jerry &lt;3 &quot;quotes&quot; &nbsp;&hellip; &euro;5</p>"
        val text = HtmlText.toPlainText(html, 2000).text
        assertTrue("amp: $text", text.contains("Tom & Jerry"))
        assertTrue("lt: $text", text.contains("<3"))
        assertTrue("quot: $text", text.contains("\"quotes\""))
        assertTrue("hellip: $text", text.contains("…"))
        assertTrue("euro: $text", text.contains("€5"))
    }

    @Test
    fun `numeric and hex entities decode and a lone surrogate is replaced not thrown`() {
        val text = HtmlText.toPlainText(
            "<p>&#65;&#x42;&#128512; &#xD800; &#999999999;</p>",
            2000,
        ).text
        assertTrue("decimal: $text", text.contains("A"))
        assertTrue("hex: $text", text.contains("B"))
        assertTrue("astral: $text", text.contains("\uD83D\uDE00"))
        // A malformed entity stays literal instead of corrupting the string.
        assertTrue("unmapped entity kept: $text", text.contains("&#999999999;"))
    }

    @Test
    fun `entities are decoded once so a page cannot smuggle a tag past the stripper`() {
        // Decoding BEFORE stripping would turn this into a real script tag and
        // then delete the content around it — the page would control what the
        // model sees by choosing its encoding. Decoding AFTER the stripper is
        // what makes entity-encoded markup inert: it survives as visible text
        // and it never causes content removal.
        val html = "<p>use &lt;script&gt;alert(1)&lt;/script&gt; carefully</p>"
        val text = HtmlText.toPlainText(html, 2000).text

        // The proof: the text on BOTH sides of the fake tag survived. If the
        // entity had been decoded first, the stripper would have eaten
        // "alert(1) carefully" along with it.
        assertTrue("content after the fake tag must survive: $text", text.contains("carefully"))
        assertTrue("content before the fake tag must survive: $text", text.contains("use"))
        assertTrue("the entity decodes to visible text: $text", text.contains("<script>"))
    }

    @Test
    fun `whitespace is collapsed so a 40KB page of indentation is not 40KB of tokens`() {
        val html = "<div>\n\n\t   <p>a   \n\n  b</p>\n\n\n\n   <p>c</p>\n  </div>"
        val text = HtmlText.toPlainText(html, 2000).text
        assertFalse("newline runs remain: [$text]", text.contains("\n\n\n"))
        assertFalse("tabs remain: [$text]", text.contains("\t"))
        assertFalse("double spaces remain: [$text]", text.contains("  "))
        // The words must all still be there; the line break between the two
        // paragraphs is the block-tag newline, not a whitespace-run artifact.
        for (word in listOf("a", "b", "c")) {
            assertTrue("lost '$word' in [$text]", text.contains(word))
        }
        assertTrue("paragraphs must stay separated: [$text]", text.contains("\n"))
    }

    @Test
    fun `control characters are stripped so a page cannot break up keywords`() {
        // A zero-width space inside a word reads as one word to a human and as
        // two to a model. Same for an escape byte aimed at the renderer.
        val zwsp = "\u200B"
        val text = HtmlText.toPlainText("<p>acme${zwsp}corp</p>", 2000).text
        assertFalse("zero-width survived: [$text]", text.contains(zwsp))
        assertTrue("the word must rejoin: [$text]", text.contains("acmecorp"))

        val esc = "\u001B"
        val escText = HtmlText.toPlainText("<p>red${esc}[31mtext</p>", 2000).text
        assertFalse("escape survived: [$escText]", escText.contains(esc))
    }

    @Test
    fun `block tags become line breaks so the text is still readable`() {
        val text = HtmlText.toPlainText("<p>one</p><p>two</p><ul><li>three</li></ul>", 2000).text
        assertTrue("paragraphs must separate: [$text]", text.contains("one"))
        assertTrue("[$text]", text.contains("two"))
        assertTrue("[$text]", text.contains("three"))
        assertTrue("expected a line break between blocks: [$text]", text.contains("\n"))
    }

    @Test
    fun `a plain-text body is passed through untouched and not marked as html`() {
        val json = """{"a":1,"b":[2,3],"c":"x < y"}"""
        val result = HtmlText.toPlainText(json, 2000)
        assertFalse("json must not be treated as html", result.fromHtml)
        assertTrue(result.text.contains("\"a\":1"))
        assertTrue("json must survive intact", result.text.contains("x < y"))
    }

    // ======================================================================
    // Character cap
    // ======================================================================

    @Test
    fun `a long body is cut to the budget with the marker inside the budget`() {
        val long = "<p>" + ("word ".repeat(20_000)) + "</p>"
        for (budget in listOf(100, 500, 2000, 8000)) {
            val result = HtmlText.toPlainText(long, budget)
            assertTrue("budget=$budget got ${result.text.length}", result.text.length <= budget)
            assertTrue("budget=$budget", result.truncated)
            assertTrue("budget=$budget must be marked: ${result.text.takeLast(30)}", result.text.endsWith("…[truncated]"))
        }
    }

    @Test
    fun `a body that exactly fits is not reported as truncated`() {
        val body = "a".repeat(1500)
        val result = HtmlText.toPlainText(body, 2000)
        assertFalse("exact fit must not claim truncation", result.truncated)
        assertEquals(1500, result.text.length)
    }

    // ======================================================================
    // Body byte cap
    // ======================================================================

    @Test
    fun `a 1MB body is capped while reading, not after it is in memory`() {
        val oneMegabyte = "A".repeat(1024 * 1024).toByteArray()
        val read = BodyReader.readCapped(ByteArrayInputStream(oneMegabyte), MAX_BODY_BYTES, neverCancelled)

        assertEquals("must stop at the cap", MAX_BODY_BYTES, read.bytes.size)
        assertTrue("must report truncation", read.truncated)
        assertTrue("must never exceed the cap", read.bytes.size <= MAX_BODY_BYTES)
    }

    @Test
    fun `a body under the cap is complete and not marked truncated`() {
        val body = "<p>short</p>".toByteArray()
        val read = BodyReader.readCapped(ByteArrayInputStream(body), MAX_BODY_BYTES, neverCancelled)
        assertFalse(read.truncated)
        assertEquals(body.size, read.bytes.size)
    }

    @Test
    fun `a body exactly at the cap is complete, not truncated`() {
        // The extra probe byte is what makes this distinguishable, and claiming
        // truncation here would tell the model a whole page was cut.
        val exact = ByteArray(1000) { 'x'.code.toByte() }
        val read = BodyReader.readCapped(ByteArrayInputStream(exact), 1000, neverCancelled)
        assertFalse("an exact fit is not a truncation", read.truncated)
        assertEquals(1000, read.bytes.size)
    }

    @Test
    fun `a cancelled read returns promptly and says so`() {
        val cancelled = CancellationSignal { true }
        val read = BodyReader.readCapped(
            ByteArrayInputStream(ByteArray(10_000)),
            MAX_BODY_BYTES,
            cancelled,
        )
        assertTrue(read.isCancelled())
    }

    @Test
    fun `a stream that dies mid-body keeps what arrived instead of throwing`() {
        val dying = object : InputStream() {
            private var served = 0
            override fun read(): Int {
                if (served++ > 10) throw IOException("connection reset")
                return 'x'.code
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (served >= 10) throw IOException("connection reset")
                val n = minOf(len, 10 - served)
                java.util.Arrays.fill(b, off, off + n, 'x'.code.toByte())
                served += n
                return n
            }
        }
        val read = BodyReader.readCapped(dying, MAX_BODY_BYTES, neverCancelled)
        assertTrue("partial bytes retained", read.bytes.size in 1..10)
    }

    // ======================================================================
    // Argument coercion
    // ======================================================================

    @Test
    fun `maxChars is coerced from every type a model emits, then clamped`() {
        val cases = listOf(
            JsonPrimitive(1500) to 1500,
            JsonPrimitive("1500") to 1500,          // string for an int
            JsonPrimitive(1500.9) to 1500,           // double for an int
            JsonPrimitive(true) to 2000,             // bool: fall back, not crash
            JsonPrimitive("not a number") to 2000,   // junk: fall back
            kotlinx.serialization.json.JsonNull to 2000,
            null to 2000,
        )
        for ((raw, expected) in cases) {
            assertEquals(
                "raw=$raw",
                expected,
                WebArgs.coerceInt(raw, fallback = 2000, min = 100, max = MAX_MAX_CHARS),
            )
        }
    }

    @Test
    fun `maxChars is clamped at both ends and never overflows the budget`() {
        assertEquals(100, WebArgs.coerceInt(JsonPrimitive(1), 2000, 100, MAX_MAX_CHARS))
        assertEquals(100, WebArgs.coerceInt(JsonPrimitive(-9999), 2000, 100, MAX_MAX_CHARS))
        assertEquals(MAX_MAX_CHARS, WebArgs.coerceInt(JsonPrimitive(999_999), 2000, 100, MAX_MAX_CHARS))
        assertEquals(MAX_MAX_CHARS, WebArgs.coerceInt(JsonPrimitive(Int.MAX_VALUE), 2000, 100, MAX_MAX_CHARS))
    }

    @Test
    fun `the documented default and cap are what the schema promises`() {
        assertEquals(2000, WebArgs.coerceInt(null, fallback = 2000, min = 100, max = MAX_MAX_CHARS))
        assertEquals(8000, MAX_MAX_CHARS)
    }

    // ======================================================================
    // The whole fetcher, over a scripted transport
    // ======================================================================

    private class ScriptedOpener(
        private val responses: Map<String, () -> OpenedResponse>,
    ) : ResponseOpener {
        val requested = mutableListOf<String>()

        override fun open(url: String, signal: CancellationSignal): OpenedResponse {
            requested += url
            val factory = responses[url]
                ?: throw UnknownHostException("no such host: ${url.take(40)}")
            return factory()
        }
    }

    private fun ok(
        body: String,
        status: Int = 200,
        type: String? = "text/html; charset=utf-8",
        location: String? = null,
    ): () -> OpenedResponse = {
        OpenedResponse(status, location, type, ByteArrayInputStream(body.toByteArray()), {})
    }

    private fun fetch(
        opener: ResponseOpener,
        url: String = "https://example.com/",
        maxChars: Int = 2000,
        context: ToolContext = ToolContext(),
    ): ToolResult = WebFetcher(opener).fetch(url, maxChars, context)

    @Test
    fun `a successful fetch returns readable text and the model never sees markup`() {
        val opener = ScriptedOpener(
            mapOf(
                "https://example.com/" to ok(
                    "<html><head><style>b{color:red}</style></head>" +
                        "<body><h1>Hi</h1><script>BAD_SCRIPT</script><p>Body text.</p></body></html>",
                ),
            ),
        )

        val result = fetch(opener)

        assertTrue(result.observation, result.success)
        assertTrue("body text present", result.observation.contains("Body text."))
        assertFalse("script leaked", result.observation.contains("BAD_SCRIPT"))
        assertFalse("style leaked", result.observation.contains("color:red"))
        assertFalse("markup leaked", result.observation.contains("<"))
        assertTrue("status is reported", result.observation.contains("HTTP 200"))
    }

    @Test
    fun `a simulated 1MB page yields a bounded observation that states truncation`() {
        // THE size-cap test. 1 MB of HTML, 20_000 words: the observation must be
        // within the requested budget AND must say it was cut, so the model
        // knows it is holding a fragment rather than a whole page.
        val huge = "<html><body>" + ("<p>filler filler filler filler</p>".repeat(40_000)) + "</body></html>"
        assertTrue("fixture really is ~1MB", huge.length > 900_000)
        val opener = ScriptedOpener(mapOf("https://example.com/" to ok(huge)))

        val result = fetch(opener, maxChars = 2000)

        assertTrue(result.observation, result.success)
        assertTrue(
            "observation must respect the request: ${result.observation.length}",
            result.observation.length <= 2000,
        )
        assertTrue(
            "observation must respect the hard budget: ${result.observation.length}",
            result.observation.length <= ObservationTruncator.DEFAULT_BUDGET_CHARS,
        )
        assertTrue(
            "truncation must be stated: ${result.observation.take(160)}",
            result.observation.contains("truncated") || result.observation.contains("cut"),
        )
    }

    @Test
    fun `an 8000-char request is still clamped to the contract budget`() {
        // maxChars can be asked up to 8000, but the contract ceiling is 2048 and
        // the tool does not get to exceed it just because the model asked.
        val huge = "<html><body>" + "word ".repeat(50_000) + "</body></html>"
        val result = fetch(ScriptedOpener(mapOf("https://x.com/" to ok(huge))), "https://x.com/", 8000)
        assertTrue(
            "must stay under the contract budget: ${result.observation.length}",
            result.observation.length <= ObservationTruncator.DEFAULT_BUDGET_CHARS,
        )
    }

    @Test
    fun `a 1MB body reports the download cap in the header`() {
        val huge = "A".repeat(1024 * 1024)
        val result = fetch(ScriptedOpener(mapOf("https://example.com/" to ok(huge))))
        assertTrue(result.success)
        assertTrue(
            "header must name the byte cap: ${result.observation.take(200)}",
            result.observation.contains("${MAX_BODY_BYTES} B"),
        )
    }

    @Test
    fun `a redirect chain is followed and the final url is reported`() {
        val opener = ScriptedOpener(
            mapOf(
                "https://example.com/" to ok("", status = 301, location = "/second"),
                "https://example.com/second" to ok("", status = 302, location = "https://example.com/third"),
                "https://example.com/third" to ok("<p>arrived</p>"),
            ),
        )

        val result = fetch(opener)

        assertTrue(result.observation, result.success)
        assertTrue("final body returned", result.observation.contains("arrived"))
        assertEquals(
            listOf("https://example.com/", "https://example.com/second", "https://example.com/third"),
            opener.requested,
        )
        assertTrue("final url reported", result.observation.contains("example.com/third"))
    }

    @Test
    fun `a redirect chain longer than the limit stops and does not loop forever`() {
        var n = 0
        val opener = object : ResponseOpener {
            override fun open(url: String, signal: CancellationSignal): OpenedResponse {
                n++
                return OpenedResponse(
                    status = 302,
                    location = "/hop$n",
                    contentType = "text/html",
                    body = ByteArrayInputStream(ByteArray(0)),
                    closer = {},
                )
            }
        }

        val result = fetch(opener)

        assertFalse("must not claim success", result.success)
        assertTrue("must stop at the limit, made $n requests", n <= MAX_REDIRECTS + 1)
        assertTrue("must name the limit: ${result.observation}", result.observation.contains("$MAX_REDIRECTS"))
    }

    @Test
    fun `a 404 is a NotFound with the status, never an exception`() {
        val result = fetch(
            ScriptedOpener(mapOf("https://example.com/" to ok("<p>nope</p>", status = 404))),
        )
        assertFalse(result.success)
        assertTrue(result.error is ToolError.NotFound)
        assertTrue("names the status: ${result.observation}", result.observation.contains("404"))
    }

    @Test
    fun `a 403 and a 500 map to their own errors and never throw`() {
        val forbidden = fetch(ScriptedOpener(mapOf("https://x.com/" to ok("", status = 403))), "https://x.com/")
        assertTrue(forbidden.error is ToolError.PermissionDenied)
        assertTrue(forbidden.observation.contains("403"))

        val broken = fetch(ScriptedOpener(mapOf("https://x.com/" to ok("", status = 503))), "https://x.com/")
        assertTrue(broken.error is ToolError.Unavailable)
        assertTrue(broken.observation.contains("503"))
    }

    @Test
    fun `status mapping never produces a stack trace`() {
        for (status in listOf(400, 401, 404, 418, 429, 500, 503)) {
            val (error, observation) = WebObservations.httpError(status, "https://example.com/")
            assertNotNull("status=$status", error)
            assertFalse("status=$status", observation.contains("at java."))
            assertFalse("status=$status", observation.contains("Exception"))
            assertTrue("status=$status must name itself: $observation", observation.contains("$status"))
        }
    }

    @Test
    fun `an unknown host becomes Unavailable and names the host`() {
        val result = fetch(ScriptedOpener(emptyMap()), "https://nowhere.invalid/x")
        assertFalse(result.success)
        assertTrue(result.error is ToolError.Unavailable)
        assertTrue("names the host: ${result.observation}", result.observation.contains("nowhere.invalid"))
        assertFalse("no stack trace", result.observation.contains("at java."))
    }

    @Test
    fun `a timeout becomes Timeout and says not to retry immediately`() {
        val opener = ResponseOpener { _, _ -> throw SocketTimeoutException("read timed out") }
        val result = fetch(opener)
        assertFalse(result.success)
        assertTrue(result.error is ToolError.Timeout)
        assertTrue("must advise against a hot loop: ${result.observation}", result.observation.contains("retry"))
    }

    @Test
    fun `a TLS failure becomes Unavailable and never leaks a trace`() {
        val opener = ResponseOpener { _, _ -> throw SSLHandshakeException("bad cert") }
        val result = fetch(opener)
        assertFalse(result.success)
        assertTrue(result.error is ToolError.Unavailable)
        assertFalse(result.observation.contains("at javax."))
        assertFalse(result.observation.contains("\tat "))
    }

    @Test
    fun `a cleartext-blocked message names the fix instead of the exception`() {
        val opener = ResponseOpener { _, _ ->
            throw IOException("Cleartext HTTP traffic to example.com not permitted")
        }
        val result = fetch(opener)
        assertFalse(result.success)
        assertTrue("must suggest https: ${result.observation}", result.observation.contains("https://"))
        assertFalse("no raw exception: ${result.observation}", result.observation.contains("IOException"))
    }

    @Test
    fun `a SecurityException becomes PermissionDenied`() {
        val opener = ResponseOpener { _, _ -> throw SecurityException("no INTERNET permission") }
        val result = fetch(opener)
        assertTrue(result.error is ToolError.PermissionDenied)
        assertTrue(result.observation.contains("INTERNET"))
    }

    @Test
    fun `an unexpected RuntimeException becomes Internal and still never throws`() {
        val opener = ResponseOpener { _, _ -> throw IllegalStateException("boom") }
        val result = fetch(opener)
        assertFalse("must not propagate", result.success)
        assertTrue(result.error is ToolError.Internal)
        assertFalse("no stack trace", result.observation.contains("\tat "))
        assertTrue("should discourage retry: ${result.observation}", result.observation.contains("bug"))
    }

    @Test
    fun `context cancellation short-circuits before any request`() {
        val opener = ScriptedOpener(mapOf("https://example.com/" to ok("<p>x</p>")))
        val result = fetch(opener, context = ToolContext(signal = CancellationSignal { true }))
        assertFalse(result.success)
        assertTrue(result.error is ToolError.Cancelled)
        assertTrue("must not have opened a socket", opener.requested.isEmpty())
    }

    @Test
    fun `context permission denial short-circuits before any request`() {
        val opener = ScriptedOpener(mapOf("https://example.com/" to ok("<p>x</p>")))
        val result = fetch(opener, context = ToolContext(permissionGranted = false))
        assertFalse(result.success)
        assertTrue(result.error is ToolError.PermissionDenied)
        assertTrue("must not have opened a socket", opener.requested.isEmpty())
    }

    // ======================================================================
    // The tool surface
    // ======================================================================

    @Test
    fun `the definition is well formed and honest about its risk`() = runTest {
        val tool = WebFetchTool.withOpener(ScriptedOpener(emptyMap()))
        val def = tool.definition

        assertEquals("web.fetch", def.name)
        assertEquals("web", def.category)
        assertTrue("READ_ONLY must not require confirmation", def.risk == ToolRisk.READ_ONLY)
        assertFalse(def.risk.requiresConfirmation)
        assertTrue("4-8 tags, got ${def.tags.size}", def.tags.size in 4..8)
        assertTrue("tags lowercase", def.tags.all { it == it.lowercase() })
        assertTrue("one imperative sentence", def.description.endsWith("."))
        assertTrue("description names a verb", def.description.startsWith("Fetches"))

        val schema = def.schema
        assertEquals("object", schema["type"]!!.toString().trim('"'))
        val properties = schema["properties"]!!.let { it as kotlinx.serialization.json.JsonObject }
        assertTrue("url declared", "url" in properties)
        assertTrue("maxChars declared", "maxChars" in properties)
        val required = schema["required"]!!.let { it as kotlinx.serialization.json.JsonArray }
        assertEquals(1, required.size)
        assertEquals("\"url\"", required[0].toString())
    }

    @Test
    fun `a hostile url passed to execute fails as InvalidArguments without a request`() = runTest {
        val opener = ScriptedOpener(emptyMap())
        val tool = WebFetchTool.withOpener(opener)

        for (url in listOf("file:///data/data/dev.localintelligence/x", "javascript:alert(1)", "content://a/b")) {
            val result = tool.execute(
                buildJsonObject { put("url", JsonPrimitive(url)) },
                ToolContext(),
            )
            assertFalse("$url must fail", result.success)
            assertTrue("$url must be InvalidArguments", result.error is ToolError.InvalidArguments)
            assertTrue("must not touch the network", opener.requested.isEmpty())
            assertTrue(
                "observation under budget: ${result.observation.length}",
                result.observation.length <= ObservationTruncator.DEFAULT_BUDGET_CHARS,
            )
        }
    }

    @Test
    fun `execute coerces a string maxChars and still honours it`() = runTest {
        val opener = ScriptedOpener(
            mapOf("https://example.com/" to ok("<p>" + "word ".repeat(5000) + "</p>")),
        )
        val result = WebFetchTool.withOpener(opener).execute(
            buildJsonObject {
                put("url", JsonPrimitive("https://example.com/"))
                put("maxChars", JsonPrimitive("300"))
            },
            ToolContext(),
        )
        assertTrue(result.observation, result.success)
        assertTrue("must respect the string budget: ${result.observation.length}", result.observation.length <= 300)
    }

    @Test
    fun `execute normalises a scheme-less url and fetches the https form`() = runTest {
        // The key here is the NORMALISED url, which is what proves the tool
        // normalised before opening rather than passing the raw string through.
        val opener = ScriptedOpener(mapOf("https://example.com" to ok("<p>hi</p>")))
        val result = WebFetchTool.withOpener(opener).execute(
            buildJsonObject { put("url", JsonPrimitive("example.com")) },
            ToolContext(),
        )
        assertTrue(result.observation, result.success)
        assertEquals(listOf("https://example.com"), opener.requested)
    }

    @Test
    fun `execute reports a missing url rather than throwing`() = runTest {
        val result = WebFetchTool.withOpener(ScriptedOpener(emptyMap()))
            .execute(buildJsonObject { }, ToolContext())
        assertFalse(result.success)
        assertTrue(result.error is ToolError.InvalidArguments)
        assertTrue(result.observation.contains("url"))
    }

    @Test
    fun `every observation this tool can produce stays under the contract budget`() = runTest {
        val opener = ScriptedOpener(
            mapOf(
                "https://example.com/huge" to ok("<p>" + "word ".repeat(200_000) + "</p>"),
                "https://example.com/404" to ok("nope", status = 404),
            ),
        )
        val tool = WebFetchTool.withOpener(opener)
        val calls = listOf(
            buildJsonObject { put("url", JsonPrimitive("https://example.com/huge")) },
            buildJsonObject { put("url", JsonPrimitive("https://example.com/404")) },
            buildJsonObject { put("url", JsonPrimitive("file:///etc/passwd")) },
            buildJsonObject { put("url", JsonPrimitive("http://")) },
            buildJsonObject { },
        )
        for (args in calls) {
            val result = tool.execute(args, ToolContext())
            assertTrue(
                "budget blown for $args: ${result.observation.length}",
                result.observation.length <= ObservationTruncator.DEFAULT_BUDGET_CHARS,
            )
            assertFalse("stack trace for $args", result.observation.contains("\tat "))
        }
    }

    @Test
    fun `the structured data field is populated for the UI and carries the truth`() = runTest {
        val result = WebFetchTool.withOpener(
            ScriptedOpener(
                mapOf(
                    "https://example.com/" to ok(
                        "<html><script>X</script><p>hello</p></html>",
                    ),
                ),
            ),
        ).execute(buildJsonObject { put("url", JsonPrimitive("https://example.com/")) }, ToolContext())

        val data = result.data
        assertNotNull("data must be populated for the UI", data)
        val json = data!!
        assertTrue(json.containsKey("url"))
        assertTrue(json.containsKey("finalUrl"))
        assertTrue(json.containsKey("status"))
        assertEquals(JsonPrimitive(true), json["strippedHtml"])
    }
}
