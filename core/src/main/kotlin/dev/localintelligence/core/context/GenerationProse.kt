package dev.localintelligence.core.context

/**
 * The part of one model generation a human should read.
 *
 * WHY THIS EXISTS: the wire format the model is forced to emit is
 * `<respond>answer</respond>` or `<tool name="X">{args}</tool>`, and a turn
 * may put ordinary prose in front of either. The loop needs that text parsed
 * into an action; a transcript needs the *other* half — the words — with the
 * markup taken out. `ActionParserImpl` answers the first question and its
 * result is an action, not a sentence, so the display half had nowhere to
 * live. This is that half, and nothing else.
 *
 * IT IS NOT A PARSER. It never validates, repairs or rejects. It reads the
 * same tag names [ActionParserImpl] documents and removes them, because
 * showing a user `<tool name="device.battery">{}</tool>` is showing them the
 * protocol rather than the answer. Every function here degrades to "the text,
 * trimmed" when the tags are absent or unterminated, which is the case that
 * matters most: a generation cut off mid-sentence must still yield the
 * sentence that was produced.
 *
 * Precedence mirrors the parser exactly — a `<tool>` that opens before the
 * `<respond>` is a tool turn — so a step is described the same way here as it
 * is executed there. Getting that backwards would attribute a tool call's
 * arguments to the user as though they were prose.
 */
object GenerationProse {

    private const val RESPOND_OPEN = "<respond>"
    private const val RESPOND_CLOSE = "</respond>"
    private const val THINK_OPEN = "<think>"
    private const val THINK_CLOSE = "</think>"

    /** Opening tag only. A name attribute is optional in the format. */
    private val TOOL_OPEN = Regex("""<tool(\s[^>]*)?>""", RegexOption.IGNORE_CASE)
    private val RESPOND_OPEN_RE = Regex("""<respond\s*>""", RegexOption.IGNORE_CASE)
    private val RESPOND_CLOSE_RE = Regex("""</respond\s*>""", RegexOption.IGNORE_CASE)

    /**
     * The displayable text of one generation, whichever kind of turn it is.
     *
     * The single entry point for a transcript: the caller has raw generation
     * text and does not know, or care, whether the model was answering or
     * calling a tool.
     */
    fun display(raw: String): String {
        val respondAt = RESPOND_OPEN_RE.find(raw)?.range?.first ?: -1
        val toolAt = TOOL_OPEN.find(raw)?.range?.first ?: -1
        return if (toolAt >= 0 && (respondAt < 0 || toolAt < respondAt)) {
            beforeTool(raw)
        } else {
            answer(raw)
        }
    }

    /**
     * What the model said *before* it called a tool.
     *
     * Empty when it said nothing first, which is the common case and is not an
     * error: a step that goes straight to `<tool>` has no prose to show, and
     * the caller must not invent a placeholder for it.
     */
    fun beforeTool(raw: String): String {
        val tool = TOOL_OPEN.find(raw) ?: return answer(raw)
        return answer(raw.substring(0, tool.range.first))
    }

    /**
     * The model's thought in one raw generation, or empty when it did not think.
     *
     * Two shapes reach here: a `<think>...</think>` block, and a bare `</think>` whose
     * opening tag the chat template put in the prompt (LFM3 does this), so everything
     * before the last close is the thought. A thought that never closed (the output limit
     * ran out inside it) is empty: it is not an answer and is not shown as one.
     */
    fun thought(raw: String): String {
        val close = raw.lastIndexOf(THINK_CLOSE, ignoreCase = true)
        if (close < 0) return ""
        val before = raw.substring(0, close)
        val open = before.lastIndexOf(THINK_OPEN, ignoreCase = true)
        return (if (open >= 0) before.substring(open + THINK_OPEN.length) else before).trim()
    }

    /** [raw] after its last thought close, so a thought never reads as the answer. */
    private fun withoutThought(raw: String): String {
        val close = raw.lastIndexOf(THINK_CLOSE, ignoreCase = true)
        return if (close < 0) raw else raw.substring(close + THINK_CLOSE.length)
    }

    /**
     * The body of a `<respond>` turn.
     *
     * An unterminated `<respond>` takes the rest of the string rather than
     * nothing. That is deliberate and load-bearing: a cancelled or failed
     * generation is exactly a respond whose closing tag never arrived, and
     * returning empty for it would discard the model's own words at the
     * precise moment the user needs to see what it managed to say.
     */
    private fun answer(generated: String): String {
        val raw = withoutThought(generated)
        val open = RESPOND_OPEN_RE.find(raw)
        val body = if (open != null) raw.substring(open.range.last + 1) else raw
        val close = RESPOND_CLOSE_RE.find(body)
        val text = if (close != null) body.substring(0, close.range.first) else body
        return text.trim()
    }
}
