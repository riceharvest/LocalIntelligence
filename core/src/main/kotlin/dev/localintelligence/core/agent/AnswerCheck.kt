package dev.localintelligence.core.agent

/**
 * The answer check: a number or a proper name in a final answer, given after tool use, must
 * appear in a tool result or in the user's own words. Otherwise the model is told once which
 * value has no source.
 *
 * ## WHY, AND WHY ONLY THESE TWO KINDS
 *
 * A small on-device model that has just read a page will still sometimes quote a price, a
 * count or a person's name it did not read. The phone-number check of [PhoneNumbers] already
 * stops a message going to a number nobody gave; this extends the same idea to what the
 * answer says. Dates, quantities written in words, and general nouns are not checked: they
 * are too many and too often paraphrased to test without a retry on nearly every reply.
 *
 * ## THE TWO RULES
 *
 *  - A number is every run of two or more digits. The run is compared on its own, so
 *    "1.250,00" needs "250" and "00" in the source, not the joined digits. Single digits
 *    (list numbers, "2 of 3") are not checked.
 *  - A name is a capitalised word of three or more letters that does not start a sentence
 *    (after `.`, `!`, `?`, `:`, `;`, a line break, or the start of the text). Its lower-case
 *    form must appear as a word in the source. A capitalised common noun that the source
 *    never uses (a weekday, a month, a language in another language) is flagged too; the
 *    retry is once per run and the answer goes through either way, so that cost is accepted.
 *
 * Pure, and it never runs on a reply without tool use: the caller decides when to ask.
 */
object AnswerCheck {

    private val DIGIT_RUN = Regex("\\d{2,}")

    /** A capitalised word of 3+ letters not preceded by a letter. Unicode-aware for Dutch and German. */
    private val CAPITALISED = Regex("(?<!\\p{L})\\p{Lu}\\p{Ll}{2,}(?!\\p{L})")

    /** Characters that, before a word, mean the word starts a sentence or a list item. */
    private const val SENTENCE_BREAKS = ".!?:;\n"

    /** Every checkable token in [text]: digit runs as `#digits`, names as lower-case words. */
    fun tokensIn(text: String): Set<String> {
        val out = HashSet<String>()
        for (m in DIGIT_RUN.findAll(text)) out += "#" + m.value
        for (m in CAPITALISED.findAll(text)) {
            if (!startsSentence(text, m.range.first)) out += m.value.lowercase()
        }
        // Source words are also needed for names, which are plain lower-case words.
        for (m in Regex("\\p{L}{3,}").findAll(text)) out += m.value.lowercase()
        return out
    }

    /**
     * The first value in [answer] that [grounded] does not contain, shown as the model would
     * write it, or null when every checkable value has a source.
     */
    fun unsupported(answer: String, grounded: Set<String>): String? {
        for (m in DIGIT_RUN.findAll(answer)) {
            if ("#" + m.value !in grounded) return m.value
        }
        for (m in CAPITALISED.findAll(answer)) {
            if (startsSentence(answer, m.range.first)) continue
            if (m.value.lowercase() !in grounded) return m.value
        }
        return null
    }

    private fun startsSentence(text: String, index: Int): Boolean {
        var i = index - 1
        while (i >= 0 && (text[i] == ' ' || text[i] == '\t' || text[i] == '"' || text[i] == '(' ||
                text[i] == '*' || text[i] == '-' || text[i] == '“' || text[i] == '•')
        ) {
            i--
        }
        return i < 0 || text[i] in SENTENCE_BREAKS
    }
}
