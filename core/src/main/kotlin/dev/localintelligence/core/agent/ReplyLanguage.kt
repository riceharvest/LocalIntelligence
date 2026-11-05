package dev.localintelligence.core.agent

/**
 * Whether a reply plainly is not in the language of the message that asked for it.
 *
 * WHY: the prompt asks for the person's language, but tool results are English and a small model
 * copies them. Measured: a German alarm request got "Alarm set for tomorrow at 7:00 AM", and alarm
 * replies in Thai, Japanese, Korean and Chinese came back in English.
 *
 * Two checks, both only ever cause one retry, and the first reply is kept if the retry is no better.
 * A message in a script other than Latin must get a reply with some of that script. A Latin message
 * with no common English word in it must not get a reply that reads as English. This is not language
 * detection: it cannot tell Spanish from Italian, and it does not need to.
 */
object ReplyLanguage {

    private val SCRIPTS = listOf(
        Regex("[\\u0600-\\u06FF]"), // Arabic
        Regex("[\\u0900-\\u097F]"), // Devanagari
        Regex("[\\u0980-\\u09FF]"), // Bengali
        Regex("[\\u0E00-\\u0E7F]"), // Thai
        Regex("[\\u0400-\\u04FF]"), // Cyrillic
        Regex("[\\uAC00-\\uD7AF]"), // Hangul
        Regex("[\\u3040-\\u30FF\\u4E00-\\u9FFF]"), // Japanese and Chinese
    )

    private val ENGLISH = setOf(
        "the", "is", "are", "a", "an", "my", "me", "i", "to", "for", "of", "in", "on", "at", "what", "how", "who",
        "when", "where", "why", "can", "you", "your", "please", "and", "it", "this", "that", "set", "turn", "tell",
    )

    /** Words that are rare in the other Latin-script languages, so two of them in a reply mean English. */
    private val ENGLISH_REPLY = setOf(
        "the", "your", "you", "is", "has", "been", "was", "wasn", "for", "with", "and", "will", "it", "this", "that", "couldn", "have",
    )

    private val WORD = Regex("\\p{L}+")

    fun mismatch(message: String, reply: String): Boolean {
        val script = SCRIPTS.firstOrNull { it.containsMatchIn(message) }
        if (script != null) return !script.containsMatchIn(reply)
        val asked = WORD.findAll(message).map { it.value.lowercase() }.toList()
        if (asked.size < 2 || asked.any { it in ENGLISH }) return false
        return WORD.findAll(reply).count { it.value.lowercase() in ENGLISH_REPLY } >= 2
    }
}
