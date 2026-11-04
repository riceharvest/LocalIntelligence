package dev.localintelligence.core.documents

import java.util.regex.Pattern

/**
 * Entity decoding and control-character stripping shared by [PageText] (in :core)
 * and `HtmlText` (in :android, used for search snippets and non-page bodies).
 *
 * WHY IT LIVES HERE: the page extractor must decode entities and strip control
 * characters exactly as the HTML path does, and :core cannot depend on :android.
 */
object HtmlEntities {

    private val NAMED = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
        // WHY U+00A0 for nbsp: the reference keeps it; HtmlText's whitespace pass turns it into a space anyway.
        "nbsp" to " ", "ensp" to " ", "emsp" to " ", "thinsp" to " ",
        "copy" to "©", "reg" to "®", "trade" to "™", "deg" to "°",
        "euro" to "€", "pound" to "£", "yen" to "¥", "cent" to "¢",
        "sect" to "§", "para" to "¶", "middot" to "·", "bull" to "•",
        "dagger" to "†", "prime" to "′", "laquo" to "«", "raquo" to "»",
        "ldquo" to "“", "rdquo" to "”", "lsquo" to "‘", "rsquo" to "’",
        "mdash" to "—", "ndash" to "–", "hellip" to "…",
        "times" to "×", "divide" to "÷", "plusmn" to "±", "frac12" to "½",
        "frac14" to "¼", "sup2" to "²", "sup3" to "³", "micro" to "µ",
        "ne" to "≠", "le" to "≤", "ge" to "≥", "larr" to "←", "rarr" to "→",
        "harr" to "↔", "infin" to "∞", "alpha" to "α", "beta" to "β",
        "gamma" to "γ", "delta" to "δ", "pi" to "π", "sigma" to "σ",
        "omega" to "ω", "euro " to "€",
    )

    private val ENTITY = Regex("&(#[0-9]{1,8}|#[xX][0-9a-fA-F]{1,7}|[a-zA-Z][a-zA-Z0-9]{1,9});")

    /**
     * Control and format characters, which a page can use to break a keyword
     * apart (`acme​corp` reads as `acmecorp`) or to smuggle a direction
     * override into the observation. Newline and tab are excluded because
     * whitespace collapsing already handles them meaningfully.
     */
    private val CONTROL = Regex("[\\p{Cc}\\p{Cf}&&[^\\n\\t]]")

    /** Single pass, so `&amp;lt;` decodes to the literal text `&lt;`. */
    fun decode(text: String): String =
        ENTITY.replace(text) { m ->
            val body = m.groupValues[1]
            when {
                body.startsWith("#x") || body.startsWith("#X") ->
                    codePoint(body.substring(2), 16)?.let(::safeChar) ?: m.value
                body.startsWith("#") ->
                    codePoint(body.substring(1), 10)?.let(::safeChar) ?: m.value
                else -> NAMED[body] ?: m.value
            }
        }

    /** Removes control and format characters, keeping newline and tab. */
    fun stripControl(text: String): String = CONTROL.replace(text, "")

    private fun codePoint(digits: String, radix: Int): Int? =
        digits.toIntOrNull(radix)?.takeIf { it in 1..0x10FFFF && !(it in 0xD800..0xDFFF) }

    /** Lone surrogates become U+FFFD rather than corrupting the String. */
    private fun safeChar(cp: Int): String = if (cp in 0xD800..0xDFFF) "�" else String(Character.toChars(cp))
}

/**
 * Main text of an HTML page, for `web.fetch`.
 *
 * A port of the benchmark's reference extractor (`hand.py`): a linear tag scanner, an explicit
 * stack, and per-line rules that keep the article and drop menus, language lists and
 * link-heavy lines. The point is that a page's navigation does not fill the preview.
 *
 * Only the differences listed in the KDoc of each function are intended; the constants,
 * regexes and the order of decisions are the reference's.
 *
 * No recursion anywhere, and no regex that backtracks past a tag, so a hostile page costs
 * time linear in its size.
 */
object PageText {

    /**
     * Open tags deeper than this are not pushed on the stack. WHY: a page can nest tags
     * without end, and the stack is walked on every close tag. Past the cap an open tag
     * simply gets no effect, which is the same as a tag the extractor does not know.
     */
    private const val MAX_DEPTH = 512

    private val SKIP = setOf(
        "script", "style", "noscript", "template", "svg", "math", "head", "iframe", "canvas", "object",
        "embed", "button", "textarea", "video", "audio", "map", "picture",
    )
    private val VOID = setOf(
        "area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source", "track", "wbr",
    )
    private val BLOCK = setOf(
        "p", "div", "br", "li", "ul", "ol", "dl", "dt", "dd", "tr", "table", "thead", "tbody", "tfoot", "caption",
        "h1", "h2", "h3", "h4", "h5", "h6", "section", "article", "main", "header", "footer", "nav", "aside",
        "blockquote", "pre", "hr", "figure", "figcaption", "form", "address", "details", "summary", "center", "menu",
        "dialog",
    )
    private val HEAD = setOf("h1", "h2", "h3", "h4", "h5", "h6")
    private val BOILER_TAGS = setOf("nav", "footer", "aside", "menu", "dialog", "select")
    private val BOILER_ROLES = setOf(
        "navigation", "banner", "contentinfo", "complementary", "search", "menu", "menubar", "dialog", "alert",
    )
    private val RAW_TEXT = setOf("script", "style", "textarea", "title")

    private val BOILER = Regex(
        """(^|[-_ ])(nav|navbar|navigation|menu|breadcrumbs?|sidebar|sidenav|footer|masthead|cookies?|consent|popup|modal|""" +
            """newsletter|share|sharing|social|sponsor(ed)?|promo|adverts?|advertisement|ads?|related|recommend(ed|ations)?|""" +
            """langlinks|interlanguage|lang-selector|language-selector|toc|catlinks|navbox|vector-menu|vector-toc|""" +
            """vector-dropdown|mw-portlet|mw-editsection|reflist|references|skip-link|signup|subscribe)($|[-_ ])""",
        RegexOption.IGNORE_CASE,
    )
    private val HIDDEN = Regex(
        """(^|[-_ ])(hidden|sr-only|visually-hidden|screen-reader-text|d-none)($|[-_ ])""",
        RegexOption.IGNORE_CASE,
    )
    private val MAIN = Regex(
        """(^|[-_ ])(article|article-body|articlebody|article-content|post-content|post-body|entry-content|""" +
            """story-body|markdown-body|mw-parser-output|main-content|content-main|page-content|product-description)($|[-_ ])""",
        RegexOption.IGNORE_CASE,
    )
    private val ATTR = Regex(
        """([a-zA-Z_:][-a-zA-Z0-9_:.]*)(?:\s*=\s*("[^"]*"|'[^']*'|[^\s>]+))?""",
    )

    /**
     * Whitespace as Python's `\s` sees it (Unicode, plus the file separators U+001C..U+001F),
     * so a no-break space collapses the same way the reference collapses it.
     */
    private val WS: Pattern = Pattern.compile("[\\s\\x1C-\\x1F]+", Pattern.UNICODE_CHARACTER_CLASS)

    /** Main text of [html]: the title, then the main lines, then "Other text on the page:" and the rest. */
    fun fullText(html: String): String {
        val e = extract(html)
        val head = (if (e.title.isNotEmpty()) e.title + "\n" else "") + e.primary.joinToString("\n") { it.text }
        if (e.rest.isEmpty()) return head
        return head + "\n\nOther text on the page:\n" + e.rest.joinToString("\n") { it.text }
    }

    private class Tok(val kind: Kind, val name: String = "", val attrs: Map<String, String> = emptyMap(), val text: String = "")

    private enum class Kind { TEXT, OPEN, CLOSE, VOID, TITLE }

    /** One visible line of text, with what the line-level rules need to know about where it sat. */
    private class Line(
        val text: String,
        val links: Int,
        val boiler: Boolean,
        val main: Boolean,
        val table: Boolean,
        val head: Boolean,
    ) {
        /** Length in code points, as the reference counts it. */
        val len: Int = text.codePointCount(0, text.length)
    }

    /** One open element: what it added to the skip, boiler and main counters, so a close can take it back. */
    private class Frame(val name: String, val skip: Int, val boiler: Int, val main: Int)

    private class Extracted(val title: String, val primary: List<Line>, val rest: List<Line>)

    /**
     * Linear tag scanner: yields text and tags, skips comments and declarations, and blanks
     * out the bodies of script, style, textarea and title.
     *
     * Stray quotes: the reference returns when a tag's `>` is not found outside quotes, which
     * loses the rest of the page. Here that tag ends at the first `>` after its name, and quotes
     * are ignored from then on. WHY the flag: it keeps a hostile page linear, because the
     * quote-aware search runs to the end of the page at most once.
     */
    private fun scan(src: String): List<Tok> {
        val out = ArrayList<Tok>()
        val n = src.length
        var i = 0
        var quotesOff = false
        while (i < n) {
            val lt = src.indexOf('<', i)
            if (lt < 0) {
                if (i < n) out.add(Tok(Kind.TEXT, text = src.substring(i)))
                return out
            }
            if (lt > i) out.add(Tok(Kind.TEXT, text = src.substring(i, lt)))
            if (src.startsWith("<!--", lt)) {
                val e = src.indexOf("-->", lt + 4)
                i = if (e < 0) n else e + 3
                continue
            }
            if (lt + 1 < n && (src[lt + 1] == '!' || src[lt + 1] == '?')) {
                val e = src.indexOf('>', lt)
                i = if (e < 0) n else e + 1
                continue
            }
            val closing = lt + 1 < n && src[lt + 1] == '/'
            val j = lt + if (closing) 2 else 1
            if (j >= n || !src[j].isLetter()) {
                out.add(Tok(Kind.TEXT, text = "<"))
                i = lt + 1
                continue
            }
            var k = j
            while (k < n && (src[k].isLetterOrDigit() || src[k] == '-' || src[k] == '_' || src[k] == ':')) k++
            val name = src.substring(j, k).lowercase()
            var m = if (quotesOff) -1 else tagEnd(src, k)
            if (m < 0) {
                quotesOff = true
                m = src.indexOf('>', k)
                if (m < 0) return out
            }
            val raw = src.substring(k, m)
            if (closing) {
                out.add(Tok(Kind.CLOSE, name = name))
            } else {
                val attrs = HashMap<String, String>()
                val window = if (raw.length < 4000) raw else raw.substring(0, 4000)
                for (a in ATTR.findAll(window)) {
                    attrs[a.groupValues[1].lowercase()] = a.groups[2]?.value.orEmpty().trim { it == '"' || it == '\'' }
                }
                val kind = if (raw.endsWith("/") || name in VOID) Kind.VOID else Kind.OPEN
                out.add(Tok(kind, name = name, attrs = attrs))
            }
            i = m + 1
            if (!closing && name in RAW_TEXT && !raw.endsWith("/")) {
                // WHY indexOf(ignoreCase): lowercasing the whole page for every raw-text tag is quadratic.
                val e = src.indexOf("</$name", i, ignoreCase = true)
                if (name == "title") {
                    out.add(Tok(Kind.TITLE, text = src.substring(i, if (e >= 0) e else n)))
                }
                i = if (e < 0) n else e
            }
        }
        return out
    }

    /** Index of the first `>` at or after [from] that is outside quotes, or -1 when there is none. */
    private fun tagEnd(src: String, from: Int): Int {
        var quote = '\u0000'
        var p = from
        while (p < src.length) {
            val c = src[p]
            if (quote != '\u0000') {
                if (c == quote) quote = '\u0000'
            } else if (c == '"' || c == '\'') {
                quote = c
            } else if (c == '>') {
                return p
            }
            p++
        }
        return -1
    }

    private fun collapse(s: String): String = WS.matcher(s).replaceAll(" ")

    /** Visible lines in document order, with the title. */
    private fun linesOf(src: String): Pair<String, List<Line>> {
        val stack = ArrayList<Frame>()
        var skip = 0
        var boiler = 0
        var main = 0
        var table = 0
        var linkd = 0
        var heading = 0
        val buf = StringBuilder()
        var linkChars = 0
        val out = ArrayList<Line>()
        var title = ""

        fun flush() {
            var t = collapse(buf.toString()).trim { it == ' ' || it == '|' }
            t = HtmlEntities.decode(t)
            // WHY after decoding: an entity can produce a control character (`&#8203;`), and a line
            // must not carry one into an observation.
            t = HtmlEntities.stripControl(t)
            if (t.isNotEmpty()) {
                out.add(Line(t, linkChars, boiler > 0, main > 0, table > 0, heading > 0))
            }
            buf.setLength(0)
            linkChars = 0
        }

        for (tok in scan(src)) {
            when (tok.kind) {
                Kind.TITLE -> {
                    // WHY stripControl: the title is the first thing in the observation, same as a line.
                    title = HtmlEntities.stripControl(collapse(HtmlEntities.decode(tok.text))).trim()
                    continue
                }
                Kind.TEXT -> {
                    if (skip == 0) {
                        buf.append(tok.text)
                        if (linkd > 0) linkChars += tok.text.trim().let { it.codePointCount(0, it.length) }
                    }
                    continue
                }
                else -> Unit
            }
            val name = tok.name
            if (tok.kind == Kind.CLOSE) {
                if (stack.none { it.name == name }) continue
                if (name in BLOCK) flush()
                while (stack.isNotEmpty()) {
                    val s = stack.removeAt(stack.size - 1)
                    skip -= s.skip
                    boiler -= s.boiler
                    main -= s.main
                    if (s.name == "table") table--
                    if (s.name == "a") linkd--
                    if (s.name in HEAD) heading--
                    if (s.name == name) break
                }
                if ((name == "td" || name == "th") && skip == 0) buf.append(" | ")
                if (name in BLOCK) flush()
                continue
            }
            if (name == "br" || name == "hr") {
                flush()
                continue
            }
            if (tok.kind == Kind.VOID) continue
            if (name in BLOCK) flush()
            val attrs = tok.attrs
            val ci = attrs["class"].orEmpty() + " " + attrs["id"].orEmpty()
            val style = attrs["style"].orEmpty().replace(" ", "").lowercase()
            val hid = name in SKIP || attrs.containsKey("hidden") || attrs["aria-hidden"] == "true" ||
                "display:none" in style || "visibility:hidden" in style ||
                (name != "html" && name != "body" && HIDDEN.containsMatchIn(ci))
            val role = attrs["role"].orEmpty().lowercase()
            val ismain = name == "main" || name == "article" || role == "main" || role == "article" ||
                MAIN.containsMatchIn(ci) || attrs["itemprop"] == "articleBody"
            val isb = name in BOILER_TAGS || role in BOILER_ROLES || (name == "header" && main == 0) ||
                (name !in setOf("html", "body", "main", "article") && !ismain && BOILER.containsMatchIn(ci))
            val frame = Frame(
                name = name,
                skip = if (hid) 1 else 0,
                // A main element cancels the boiler count it inherits, so text inside an article is kept.
                boiler = if (ismain) -boiler else if (isb) 1 else 0,
                main = if (ismain) 1 else 0,
            )
            // WHY the cap: past MAX_DEPTH the element is not pushed, and none of its counters apply,
            // so a close tag can never take back what was never added.
            if (stack.size >= MAX_DEPTH) continue
            stack.add(frame)
            skip += frame.skip
            boiler += frame.boiler
            main += frame.main
            if (name == "table") table++
            if (name == "a") linkd++
            if (name in HEAD) heading++
        }
        flush()
        return Pair(title, out)
    }

    /** jusText's idea: mostly link text, or too short to say anything. Table rows and headings keep short text. */
    private fun isJunk(ln: Line): Boolean {
        val n = ln.len
        val density = ln.links.toDouble() / maxOf(n, 1)
        if (ln.table || ln.head) return n < 2 || (ln.boiler && density > 0.5)
        if (density > 0.5) return n < 40 || ln.boiler
        return n < 25 && ln.links > 0
    }

    private fun extract(src: String): Extracted {
        val (title, ls) = linesOf(src)
        val keep = ls.filterNot { isJunk(it) }
        val main = keep.filter { it.main && !it.boiler }
        var primary = if (main.sumOf { it.len } >= 500) main else keep.filter { !it.boiler }
        // Defuddle/Readability: too little means the filter was wrong.
        if (primary.sumOf { it.len } < 250) primary = keep.ifEmpty { ls }
        val ids = HashSet<Line>(primary)
        val rest = keep.filter { it !in ids && it.len >= 20 }
        return Extracted(title, primary, rest)
    }
}
