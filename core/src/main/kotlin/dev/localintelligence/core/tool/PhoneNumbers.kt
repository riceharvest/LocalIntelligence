package dev.localintelligence.core.tool

/**
 * Phone numbers in text, compared by their last nine digits, so "+31 6 1234 5678" and
 * "06-12345678" are the same number.
 *
 * Used by the agent loop's grounding check: a call or a text goes only to a number the user
 * wrote or a tool returned (contacts, the call log, a message). WHY: with every tool callable,
 * LFM3-1.7B asked to turn the volume down placed phone.call to "+16005550123", a number it
 * made up. The confirmation card would have stopped it, but a made-up number is never right,
 * so the model is told to look the number up instead of the user being asked to approve it.
 */
object PhoneNumbers {
    /** A run of digits with the separators people write in numbers. */
    private val IN_TEXT = Regex("""\+?\d[\d ().\-]{5,}\d""")
    private val WHOLE = Regex("""\+?[\d ().\-]+""")

    /** Shorter runs are short codes, years or amounts, not a person's number. */
    private const val MIN_DIGITS = 7
    private const val KEY_DIGITS = 9

    /** The keys of every phone number in [text]. */
    fun keysIn(text: String): Set<String> =
        IN_TEXT.findAll(text).mapNotNull { keyOf(it.value) }.toSet()

    /** The key of [value] when all of it is a phone number, else null (a name, an email address). */
    fun keyOfWhole(value: String): String? =
        value.trim().takeIf { WHOLE.matches(it) }?.let(::keyOf)

    private fun keyOf(number: String): String? {
        val digits = number.filter { it.isDigit() }
        return if (digits.length < MIN_DIGITS) null else digits.takeLast(KEY_DIGITS)
    }
}
