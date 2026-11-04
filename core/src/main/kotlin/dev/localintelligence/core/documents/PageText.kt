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
 * A port of the Firecrawl method the benchmark chose (`hand3.py`, which uses `hand2.py`'s scanner):
 * the page's `onlyMainContent` rules on a linear scanner. Elements that Firecrawl excludes (header,
 * footer, nav, aside, and classes or ids such as sidebar, ad, share, cookie) are detached unless they
 * hold `#main`; hidden elements are dropped. The text that was detached is returned after the main
 * text, behind [OTHER_TEXT_MARKER], so the model can still read it with `observations.read`.
 *
 * No recursion, no regex that backtracks past a tag, and a depth cap, so a hostile page costs time
 * linear in its size.
 */
object PageText {

    /** Separates the main text from the detached text in [fullText]. Also what the previews look for. */
    const val OTHER_TEXT_MARKER: String = "\n\nOther text on the page:\n"

    /**
     * Open elements deeper than this are not tracked. WHY: a page can nest tags without end, and the
     * open-element path is copied for every line. Past the cap an element has no effect.
     */
    private const val MAX_DEPTH = 512

    private val VOID = setOf(
        "area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source", "track", "wbr",
    )
    private val RAW_TEXT = setOf("script", "style", "textarea", "title")
    private val REMOVE = setOf(
        "script", "style", "noscript", "head", "template", "svg", "math", "iframe", "canvas", "object", "embed",
        "video", "audio", "map", "picture", "select", "textarea", "button",
    )
    private val EX_TAGS = setOf("header", "footer", "nav", "aside")
    private val EX_CLASS = setOf(
        "header", "top", "navbar", "footer", "bottom", "sidebar", "side", "aside", "modal", "popup", "overlay", "ad",
        "ads", "advert", "lang-selector", "language", "social", "social-media", "social-links", "menu", "navigation",
        "breadcrumbs", "share", "widget", "cookie", "fc-decoration", "grecaptcha-badge",
    )
    private val EX_ID = setOf(
        "header", "footer", "sidebar", "modal", "ad", "language-selector", "social", "nav", "breadcrumbs", "share",
        "widget", "cookie",
    )
    private val FORCE_ID = setOf("main")
    private val HIDDEN_CLASSES = setOf("hidden", "sr-only", "visually-hidden", "screen-reader-text")
    private val BLOCK = setOf(
        "p", "div", "br", "li", "ul", "ol", "dl", "dt", "dd", "tr", "table", "thead", "tbody", "tfoot", "caption",
        "h1", "h2", "h3", "h4", "h5", "h6", "section", "article", "main", "header", "footer", "nav", "aside",
        "blockquote", "pre", "hr", "figure", "figcaption", "form", "address", "details", "summary", "center", "menu",
        "dialog",
    )
    private val PREFIX = mapOf(
        "h1" to "# ", "h2" to "## ", "h3" to "### ", "h4" to "#### ", "h5" to "##### ", "h6" to "###### ", "li" to "- ",
    )

    private val ATTR = Regex("""([a-zA-Z_:][-a-zA-Z0-9_:.]*)(?:\s*=\s*("[^"]*"|'[^']*'|[^\s>]+))?""")

    /**
     * Whitespace as Python's `\s` sees it (Unicode, plus U+001C..U+001F), so a no-break space collapses
     * the same way the reference collapses it.
     */
    private val WS: Pattern = Pattern.compile("[\\s\\x1C-\\x1F]+", Pattern.UNICODE_CHARACTER_CLASS)

    /** Main text of [html]: the title, the main lines, then [OTHER_TEXT_MARKER] and the detached lines when there are any. */
    fun fullText(html: String): String {
        val e = extract(html)
        val head = (if (e.title.isNotEmpty()) e.title + "\n" else "") + e.main.joinToString("\n")
        if (e.rest.isEmpty()) return head
        return head + OTHER_TEXT_MARKER + e.rest.joinToString("\n")
    }

    private enum class Kind { TEXT, OPEN, CLOSE, VOID, TITLE }

    private class Tok(val kind: Kind, val name: String = "", val attrs: Map<String, String> = emptyMap(), val text: String = "")

    /** An open element on the stack: its name and whether its text is hidden. */
    private class Frame(val name: String, val hidden: Int)

    /**
     * A line of text and the id of the deepest element that contained it (-1 for none). The ancestors
     * are the parent chain, so the line needs no copy of its path: a copy per line is quadratic in the
     * nesting depth, and a hostile page could make that gigabytes.
     */
    private class Out(val text: String, val deepest: Int)

    private class Extracted(val title: String, val main: List<String>, val rest: List<String>)

    private fun collapse(s: String): String = WS.matcher(s).replaceAll(" ")

    /** Lengths of the lines, summed. */
    private fun total(lines: List<String>): Int = lines.sumOf { it.length }

    /**
     * Linear tag scanner (the scanner of `hand2.py`). A quote opens a value only right after `=`, as in
     * the HTML spec, so `<a href="x"">` cannot swallow the page. An unclosed value ends the tag at the
     * first `>`, and from then on quotes are not trusted for the rest of the page: that is what keeps a
     * hostile page linear.
     */
    private fun scan(src: String): Sequence<Tok> = sequence {
        val n = src.length
        var i = 0
        var plain = false
        while (i < n) {
            val lt = src.indexOf('<', i)
            if (lt < 0) {
                if (i < n) yield(Tok(Kind.TEXT, text = src.substring(i)))
                return@sequence
            }
            if (lt > i) yield(Tok(Kind.TEXT, text = src.substring(i, lt)))
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
                yield(Tok(Kind.TEXT, text = "<"))
                i = lt + 1
                continue
            }
            var k = j
            while (k < n && (src[k].isLetterOrDigit() || src[k] == '-' || src[k] == '_' || src[k] == ':')) k++
            val name = src.substring(j, k).lowercase()
            var m = k
            while (m < n) {
                val c = src[m]
                if (c == '>') break
                if (c == '=' && !plain) {
                    var v = m + 1
                    while (v < n && src[v] in " \t\r\n\u000C") v++
                    if (v < n && (src[v] == '"' || src[v] == '\'')) {
                        val e = src.indexOf(src[v], v + 1)
                        if (e < 0) {
                            // An unclosed value: end the tag at the first ">", and stop trusting quotes.
                            plain = true
                            m = k
                            continue
                        }
                        m = e + 1
                        continue
                    }
                    m = v
                    continue
                }
                m++
            }
            if (m >= n) return@sequence
            val raw = src.substring(k, m)
            if (closing) {
                yield(Tok(Kind.CLOSE, name = name))
            } else {
                val attrs = HashMap<String, String>()
                val window = if (raw.length < 4000) raw else raw.substring(0, 4000)
                for (a in ATTR.findAll(window)) {
                    attrs[a.groupValues[1].lowercase()] = a.groups[2]?.value.orEmpty().trim { it == '"' || it == '\'' }
                }
                val kind = if (raw.endsWith("/") || name in VOID) Kind.VOID else Kind.OPEN
                yield(Tok(kind, name = name, attrs = attrs))
            }
            i = m + 1
            if (!closing && name in RAW_TEXT && !raw.endsWith("/")) {
                // WHY indexOf(ignoreCase): lowercasing the whole page for every raw-text tag is quadratic.
                val e = src.indexOf("</$name", i, ignoreCase = true)
                if (name == "title") yield(Tok(Kind.TITLE, text = src.substring(i, if (e >= 0) e else n)))
                i = if (e < 0) n else e
            }
        }
    }

    /**
     * The page's lines with their element paths, split into main text and detached text.
     *
     * [strict] false removes only script and style, and [dropHidden] false keeps hidden elements. The
     * extractor uses that second, looser reading once when the strict one finds almost nothing.
     */
    private fun linesOf(src: String, dropHidden: Boolean = true, strict: Boolean = true): Extracted {
        // One slot per element at most: an element needs an opening tag of at least 3 characters.
        val cap = src.length / 3 + 1
        val excluded = BooleanArray(cap)
        val parent = IntArray(cap)
        var count = 0
        val forced = ArrayList<Int>()
        val stack = ArrayList<Frame>()
        val path = ArrayList<Int>()
        var skip = 0
        val buf = StringBuilder()
        var kindPrefix = ""
        val out = ArrayList<Out>()
        var title = ""

        fun flush() {
            var t = collapse(buf.toString())
            t = HtmlEntities.decode(t)
            // WHY after decoding: an entity can produce a control character (`&#8203;`), and a line
            // must not carry one into an observation.
            t = HtmlEntities.stripControl(t).trim { it == ' ' || it == '|' }
            if (t.isNotEmpty()) out.add(Out(kindPrefix + t, if (path.isEmpty()) -1 else path[path.size - 1]))
            buf.setLength(0)
            kindPrefix = ""
        }

        fun popTo(name: String?) {
            while (stack.isNotEmpty()) {
                val s = stack.removeAt(stack.size - 1)
                skip -= s.hidden
                path.removeAt(path.size - 1)
                if (name != null && s.name == name) break
            }
        }

        for (tok in scan(src)) {
            when (tok.kind) {
                Kind.TITLE -> {
                    title = HtmlEntities.stripControl(collapse(HtmlEntities.decode(tok.text))).trim()
                    continue
                }
                Kind.TEXT -> {
                    if (skip == 0) buf.append(tok.text)
                    continue
                }
                else -> Unit
            }
            val name = tok.name
            if (tok.kind == Kind.CLOSE) {
                if (stack.none { it.name == name }) continue
                if (name in BLOCK) flush()
                popTo(name)
                if ((name == "td" || name == "th") && skip == 0) buf.append(" | ")
                if (name in BLOCK) flush()
                continue
            }
            if (name == "br" || name == "hr") {
                flush()
                continue
            }
            if (tok.kind == Kind.VOID) continue
            // The spec lets a page omit </head>: <body> or any block element ends it. A parser does this for free.
            if ((name == "body" || name in BLOCK) && stack.any { it.name == "head" }) popTo("head")
            if (name in BLOCK) flush()
            if (name in PREFIX && skip == 0) kindPrefix = PREFIX.getValue(name)
            val attrs = tok.attrs
            val classes = attrs["class"].orEmpty().split(WS.toRegex()).filter { it.isNotEmpty() }.toSet()
            val eid = attrs["id"].orEmpty()
            val style = attrs["style"].orEmpty().replace(" ", "").lowercase()
            val hid = (if (strict) name in REMOVE else name == "script" || name == "style") ||
                (dropHidden && ("hidden" in attrs || attrs["aria-hidden"] == "true" ||
                    "display:none" in style || "visibility:hidden" in style ||
                    classes.any { it in HIDDEN_CLASSES }))
            val ex = name in EX_TAGS || classes.any { it in EX_CLASS } || eid in EX_ID ||
                attrs["aria-label"].orEmpty().lowercase() == "cookie consent"
            // WHY the cap: past MAX_DEPTH an element is not tracked at all, so no close can take back what
            // was never added.
            if (stack.size >= MAX_DEPTH) continue
            val i = count++
            excluded[i] = ex
            parent[i] = if (path.isEmpty()) -1 else path[path.size - 1]
            if (eid in FORCE_ID) forced.add(i)
            val hidden = if (hid) 1 else 0
            stack.add(Frame(name, hidden))
            path.add(i)
            skip += hidden
        }
        flush()
        // An element holding #main stays: walk up and clear every excluded ancestor.
        for (i in forced) {
            var j = i
            while (j >= 0) {
                excluded[j] = false
                j = parent[j]
            }
        }
        // Effective exclusion, once per element. A parent's id is lower than its child's, so one pass in id
        // order sees each parent first: an element is out when it or any ancestor is excluded.
        val effective = BooleanArray(count)
        for (i in 0 until count) {
            effective[i] = excluded[i] || (parent[i] >= 0 && effective[parent[i]])
        }
        val main = ArrayList<String>()
        val rest = ArrayList<String>()
        for (o in out) {
            (if (o.deepest >= 0 && effective[o.deepest]) rest else main).add(o.text)
        }
        return Extracted(title, main, rest)
    }

    /**
     * Firecrawl's empty-main rule, and the retry: if main and rest together are under 200 characters,
     * an unclosed element probably ate the page, so parse once more with hidden elements kept and only
     * script and style removed. Use that reading when it has more than twice the text.
     */
    private fun extract(src: String): Extracted {
        var e = linesOf(src)
        if (total(e.main) + total(e.rest) < 200) {
            val second = linesOf(src, dropHidden = false, strict = false)
            if (total(second.main) + total(second.rest) > 2 * (total(e.main) + total(e.rest))) {
                e = Extracted(second.title.ifEmpty { e.title }, second.main, second.rest)
            }
        }
        // Firecrawl: empty main content falls back to the whole page.
        if (e.main.joinToString("").isBlank()) return Extracted(e.title, e.main + e.rest, emptyList())
        return e
    }
}
