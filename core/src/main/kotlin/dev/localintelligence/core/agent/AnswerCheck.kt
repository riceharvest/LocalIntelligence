package dev.localintelligence.core.agent

/**
 * The answer check: a number or a name in a final answer, given after tool use, must appear in
 * a tool result or in the user's own words. Otherwise the model is told once which value has no
 * source.
 *
 * ## WHY, AND WHY ONLY THESE TWO KINDS
 *
 * A small on-device model that has just read a page will sometimes quote a price, a count or a
 * person's name it did not read. The phone-number check of [PhoneNumbers] already stops a message
 * going to a number nobody gave; this extends the same idea to what the answer says.
 *
 * The app ships in 21 languages, and capitalisation is not a reliable signal in most of them
 * (German capitalises every noun; weekdays and months are capitalised in English too). So a single
 * capitalised word is never checked. A name is checked only as a run of two or more consecutive
 * capitalised words, which is far rarer in ordinary prose.
 *
 * ## THE RULES
 *
 *  - A number is a run of digits, optionally joined by `,` or `.` (thousands or decimal marks).
 *    Its key is the digits alone, so "160,341" and "160.341" are the same value. Only numbers
 *    with two or more digits in the key are checked.
 *  - A name is two or more consecutive capitalised words of 3+ letters each. Each word must
 *    appear as a word in a source. The name rule is skipped entirely for text with no Latin
 *    letters (Arabic, Thai, Cyrillic and so on), where the check cannot tell a name from prose.
 *
 * Pure, and it never runs on a reply without tools: the caller decides when to ask.
 */
object AnswerCheck {

    /** Digit runs, joined by a thousands or decimal mark. */
    private val NUMBER = Regex("\\d+(?:[.,]\\d+)*")

    /** A capitalised word of 3+ letters. Unicode-aware, so Dutch and German names work. */
    private const val CAP_WORD = "\\p{Lu}\\p{Ll}{2,}"

    /** Two or more consecutive capitalised words, separated by single spaces. */
    private val NAME_RUN = Regex("(?<!\\p{L})$CAP_WORD(?: $CAP_WORD)+(?!\\p{L})")

    /** Any run of letters of three or more, lower-cased: the source words a name is matched against. */
    private val WORD = Regex("\\p{L}{3,}")

    private val LATIN = Regex("\\p{IsLatin}")

    /** Keys a source text contributes: numbers as `#digits`, and its words in lower case. */
    fun tokensIn(text: String): Set<String> {
        val out = HashSet<String>()
        for (m in NUMBER.findAll(text)) {
            val key = digitsOf(m.value)
            if (key.length >= 2) out += "#$key"
        }
        for (m in WORD.findAll(text)) out += m.value.lowercase()
        return out
    }

    /**
     * The first value in [answer] with no source, shown as written, or null when every checkable
     * value has one. A value is sourced when [sourced] says so for its key.
     */
    fun unsupported(answer: String, sourced: (String) -> Boolean): String? {
        for (m in NUMBER.findAll(answer)) {
            val key = digitsOf(m.value)
            if (key.length >= 2 && !sourced("#$key")) return m.value
        }
        if (LATIN.containsMatchIn(answer)) {
            for (m in NAME_RUN.findAll(answer)) {
                val words = m.value.split(' ')
                val missing = words.firstOrNull { !sourced(it.lowercase()) }
                if (missing != null) return m.value
            }
        }
        return null
    }

    private fun digitsOf(value: String): String = value.filter { it.isDigit() }
}
