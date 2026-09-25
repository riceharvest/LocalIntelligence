package dev.pidroid.core.agent

import dev.pidroid.core.model.ToolArgs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Wire format. The model emits EXACTLY ONE of two single-line tags:
 *
 *   <respond>the answer text</respond>
 *   <tool name="calendar.search">{"query":"alice"}</tool>
 *
 * Respond text is taken verbatim between the tags and is never re-scanned, so the
 * model may talk *about* the tags safely. Arguments are one-line JSON; a bounded
 * repair (code fences, trailing commas, single quotes, unquoted keys, a stray
 * prefix) runs before we give up, and it never invents an argument the model did
 * not emit. Everything else is [ActionParseResult.Malformed] — this parser never throws.
 *
 * `GrammarBuilder.forActions()` in :core/model emits the GBNF for this exact
 * format. Grammar and parser are one format; change them together.
 */
object ActionParserImpl : ActionParser {

    /** Hard ceiling on argument text. Bounds the repair passes and blocks pathological nesting. */
    private const val MAX_ARGS_CHARS = 4096

    /** Bounded repair: at most this many rewrite passes, each of which must change the text. */
    private const val MAX_REPAIR_PASSES = 3

    private const val RESPOND_OPEN = "<respond>"
    private const val RESPOND_CLOSE = "</respond>"
    private const val TOOL_OPEN = "<tool"
    private const val TOOL_CLOSE = "</tool>"

    private val TOOL_OPEN_RE = Regex("""<tool(\s[^>]*)?>""", RegexOption.IGNORE_CASE)
    private val NAME_ATTR_RE =
        Regex("""\bname\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))""", RegexOption.IGNORE_CASE)

    // ---------------------------------------------------------------- entry point

    override fun parse(raw: String, allowedTools: Set<String>): ActionParseResult =
        try {
            parseUnsafe(raw, allowedTools)
        } catch (t: Throwable) {
            // Total by construction. A StackOverflowError from 10k nested '[' is the
            // realistic path here; the caller must never see it.
            ActionParseResult.Malformed("Model output could not be parsed.", raw)
        }

    private fun parseUnsafe(raw: String, allowedTools: Set<String>): ActionParseResult {
        if (raw.isBlank()) {
            return malformed("Model produced no output. Reply with <respond>your answer</respond>.", raw)
        }

        val respondAt = indexOfTag(raw, RESPOND_OPEN)
        val toolAt = TOOL_OPEN_RE.find(raw)?.range?.first ?: -1

        return when {
            respondAt < 0 && toolAt < 0 ->
                malformed(
                    "No action found. Reply with <respond>text</respond> " +
                        "or <tool name=\"NAME\">{\"arg\":value}</tool>.",
                    raw,
                )

            toolAt >= 0 && (respondAt < 0 || toolAt < respondAt) ->
                parseTool(raw, toolAt, allowedTools)

            else -> parseRespond(raw, respondAt)
        }
    }

    // ---------------------------------------------------------------- respond

    private fun parseRespond(raw: String, start: Int): ActionParseResult {
        val bodyStart = start + RESPOND_OPEN.length
        val closeAt = indexOfTag(raw, RESPOND_CLOSE, bodyStart)
        val body = if (closeAt >= 0) {
            raw.substring(bodyStart, closeAt)
        } else {
            // Tolerate a dropped closing tag: take the rest of the line, nothing more.
            val nl = raw.indexOf('\n', bodyStart)
            if (nl >= 0) raw.substring(bodyStart, nl) else raw.substring(bodyStart)
        }

        val text = body.trim()
        if (text.isEmpty()) {
            // Finishing a task with a blank answer is a silent failure; re-prompt instead.
            return malformed("Empty <respond>. Put the answer inside <respond>...</respond>.", raw)
        }
        return ActionParseResult.Parsed(AgentAction.Respond(text))
    }

    // ---------------------------------------------------------------- tool call

    private fun parseTool(raw: String, tagStart: Int, allowedTools: Set<String>): ActionParseResult {
        val openEnd = raw.indexOf('>', tagStart)
        if (openEnd < 0) {
            return malformed("Unterminated <tool> tag.", raw)
        }
        val attrs = raw.substring(tagStart + TOOL_OPEN.length, openEnd)
        val name = nameAttribute(attrs)?.trim().orEmpty()

        if (allowedTools.isEmpty()) {
            // The model was offered nothing, so a tool call is definitely wrong.
            return malformed(
                "No tools are available this turn, so a tool call cannot be correct. " +
                    "Reply with <respond>your answer</respond>.",
                raw,
            )
        }
        if (name.isEmpty()) {
            return malformed(
                "Tool tag is missing a name. Legal tools: ${legal(allowedTools)}.",
                raw,
            )
        }
        if (name !in allowedTools) {
            return malformed(
                "Unknown tool \"$name\". Legal tools: ${legal(allowedTools)}.",
                raw,
            )
        }

        val bodyStart = openEnd + 1
        val closeAt = indexOfTag(raw, TOOL_CLOSE, bodyStart)
        val body = if (closeAt >= 0) {
            raw.substring(bodyStart, closeAt)
        } else {
            val nl = raw.indexOf('\n', bodyStart)
            if (nl >= 0) raw.substring(bodyStart, nl) else raw.substring(bodyStart)
        }

        if (body.length > MAX_ARGS_CHARS) {
            return malformed("Arguments for \"$name\" are too long (${body.length} chars).", raw)
        }
        val args = parseArgsObject(body)
            ?: return malformed("Arguments for \"$name\" are not a JSON object.", raw)

        return ActionParseResult.Parsed(AgentAction.CallTool(name, args))
    }

    /** Reads the `name` attribute off a `<tool ...>` open tag, tolerating quote style. */
    private fun nameAttribute(attrs: String): String? {
        val m = NAME_ATTR_RE.find(attrs) ?: return null
        for (group in 1..3) {
            val value = m.groupValues[group]
            if (value.isNotEmpty()) return value
        }
        return null
    }

    private fun legal(allowedTools: Set<String>): String =
        allowedTools.sorted().joinToString(", ")

    // ---------------------------------------------------------------- JSON + bounded repair

    /**
     * Strict parse, then at most [MAX_REPAIR_PASSES] rewrites, then one last resort:
     * a lone `{...}` span lifted out of surrounding prose. Every step is a pure
     * text transformation — no value is ever invented, so a missing argument stays
     * missing and the tool's own validation still sees the truth.
     */
    private fun parseArgsObject(body: String): ToolArgs? {
        val cleaned = stripCodeFence(body).trim()
        if (cleaned.isEmpty()) {
            // No arguments emitted at all. An empty object states exactly that; it does
            // not guess at a required argument. Tool-level validation still applies.
            return ToolArgs(emptyMap())
        }

        strictObject(cleaned)?.let { return it }

        var candidate = cleaned
        repeat(MAX_REPAIR_PASSES) {
            val repaired = repairOnce(candidate)
            if (repaired == candidate) return@repeat
            candidate = repaired
            strictObject(candidate)?.let { return it }
        }

        val span = loneObjectSpan(cleaned) ?: return null
        strictObject(span)?.let { return it }
        var second = span
        repeat(MAX_REPAIR_PASSES) {
            val repaired = repairOnce(second)
            if (repaired == second) return@repeat
            second = repaired
            strictObject(second)?.let { return it }
        }
        return null
    }

    private fun strictObject(text: String): JsonObject? = try {
        Json.parseToJsonElement(text) as? JsonObject
    } catch (t: Throwable) {
        null
    }

    private fun stripCodeFence(text: String): String {
        var t = text.trim()
        if (!t.startsWith("```")) return t
        t = t.substring(3)
        val nl = t.indexOf('\n')
        if (nl in 0..24) t = t.substring(nl + 1) // drop a ```json / ```JSON info string
        val end = t.lastIndexOf("```")
        if (end >= 0) t = t.substring(0, end)
        return t.trim()
    }

    /** Only lifts a span when the braces are unambiguous, so it cannot eat a nested object. */
    private fun loneObjectSpan(text: String): String? {
        val first = text.indexOf('{')
        val last = text.lastIndexOf('}')
        if (first < 0 || last <= first) return null
        if (text.count { it == '{' } != 1 || text.count { it == '}' } != 1) return null
        return text.substring(first, last + 1)
    }

    /**
     * One string-aware rewrite pass, applying all three tolerated defects at once:
     * trailing commas, single-quoted strings, and unquoted keys. String literals are
     * copied through untouched, so `{"note": "it's fine"}` survives intact.
     */
    private fun repairOnce(input: String): String {
        val out = StringBuilder(input.length)
        val n = input.length
        var i = 0
        var prev = ' ' // last significant char emitted outside a string literal

        while (i < n) {
            val c = input[i]
            when {
                c == '"' -> {
                    val end = jsonStringEnd(input, i)
                    if (end < 0) {
                        out.append(c); i++
                    } else {
                        out.append(input, i, end); i = end
                    }
                    prev = '"'
                }

                c == '\'' && isValuePosition(prev) -> {
                    val end = singleQuotedEnd(input, i + 1)
                    if (end < 0) {
                        out.append(c); i++
                    } else {
                        out.append('"').append(escapeForDouble(input, i + 1, end)).append('"')
                        i = end + 1
                    }
                    prev = '"'
                }

                c == ',' && isClosingBrace(nextNonWs(input, i + 1)) -> {
                    i++ // drop the trailing comma; the whitespace after it still gets copied
                }

                isIdentStart(c) && (prev == '{' || prev == ',') -> {
                    var j = i
                    while (j < n && isIdentPart(input[j])) j++
                    if (nextNonWs(input, j) == ':') {
                        out.append('"').append(input, i, j).append('"')
                        prev = '"'
                        i = j
                    } else {
                        out.append(c); prev = c; i++
                    }
                }

                else -> {
                    out.append(c)
                    if (!c.isWhitespace()) prev = c
                    i++
                }
            }
        }
        return out.toString()
    }

    private fun isValuePosition(prev: Char): Boolean =
        prev == '{' || prev == '[' || prev == ',' || prev == ':'

    private fun isClosingBrace(c: Char): Boolean = c == '}' || c == ']'

    private fun isIdentStart(c: Char): Boolean = c.isLetter() || c == '_' || c == '$'

    private fun isIdentPart(c: Char): Boolean =
        isIdentStart(c) || c.isDigit() || c == '-' || c == '.'

    private fun nextNonWs(s: String, from: Int): Char {
        var i = from
        while (i < s.length && s[i].isWhitespace()) i++
        return if (i < s.length) s[i] else ' '
    }

    /** Index just past the closing quote of the double-quoted literal starting at [from], or -1. */
    private fun jsonStringEnd(s: String, from: Int): Int {
        var i = from + 1
        while (i < s.length) {
            when (s[i]) {
                '\\' -> i += 2
                '"' -> return i + 1
                else -> i++
            }
        }
        return -1
    }

    /** Index of the closing quote of the single-quoted literal starting at [from], or -1. */
    private fun singleQuotedEnd(s: String, from: Int): Int {
        var i = from
        while (i < s.length) {
            when (s[i]) {
                '\\' -> i += 2
                '\'' -> return i
                else -> i++
            }
        }
        return -1
    }

    private fun escapeForDouble(s: String, from: Int, until: Int): String {
        val out = StringBuilder(until - from)
        for (i in from until until) {
            when (val c = s[i]) {
                '\\' -> out.append("\\\\")
                '"' -> out.append("\\\"")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> out.append(c)
            }
        }
        return out.toString()
    }

    // ---------------------------------------------------------------- small helpers

    private fun indexOfTag(s: String, tag: String, from: Int = 0): Int {
        var i = from
        while (i <= s.length - tag.length) {
            val at = s.indexOf(tag, i)
            if (at < 0 || at > s.length - tag.length) return -1
            if (s.regionMatches(at, tag, 0, tag.length, ignoreCase = true)) return at
            i = at + 1
        }
        return -1
    }

    private fun malformed(reason: String, raw: String): ActionParseResult.Malformed =
        ActionParseResult.Malformed(reason, raw)
}
