package dev.localintelligence.core.memory

/**
 * The one tokenizer both memory stores agree on.
 *
 * ## WHY THIS IS ITS OWN FILE AND NOT A LINE IN EACH STORE
 *
 * `InMemoryMemoryStore` (`:core`, in RAM) and `RoomMemoryStore` (`:android`, in
 * SQLite) must return the *same rows for the same corpus*, or the app's
 * behaviour changes silently when the database degrades — which is exactly
 * when a user least wants the app to change its mind. The previous arrangement
 * was two copies of the same rule in two modules, one of which could not be
 * changed without touching a file in `:android`. One implementation, called by
 * both, is the only arrangement that keeps the promise.
 *
 * ## WHAT IS ACTUALLY WRONG WITH THE OLD RULE
 *
 * The old rule was: lowercase, split on `[^a-z0-9]+`, drop anything not longer
 * than 2 characters. Measured against a five-memory corpus (see the retrieval
 * evidence in `docs/memory-model.md` and the PR that introduced this file), it
 * failed in four distinct ways, each of which is a *retrieval* failure rather
 * than a cosmetic one:
 *
 * 1. **Stopwords were indexed and matched.** "The" is three characters, so it
 *    survived the length filter. A query of `"the"` returned every memory whose
 *    text contains an English article, and a query of `"how much is the gym"`
 *    returned four of five memories including two about wifi, because "the" is
 *    the only term that connected them.
 * 2. **No inflectional normalisation.** `"passwords"` returned nothing against
 *    a stored `"wifi password"`. The user is asking about the same thing.
 * 3. **Apostrophes and hyphens split words that are one word.** `"what's"`
 *    became `"what"` + `"s"`, and `"wi-fi"` became `"wi"` + `"fi"` — two
 *    fragments, *both* of which are then dropped by the length filter. A
 *    memory about wi-fi was literally unsearchable by the word wi-fi.
 * 4. **Digits survived but carried no weight.** `"4417"`, `"192"`, `"168"`,
 *    `"1016"` were all indexed as ordinary terms, so a street number matched
 *    as readily as a street name.
 *
 * ## WHAT IS DELIBERATELY NOT HERE
 *
 * No embeddings, no vector store, no stemmer library, no stopword list fetched
 * from the network. Three reasons, in order of weight: the product decision in
 * `docs/architecture.md` §14 is lexical; a synonym table is a second retrieval
 * system that needs its own evaluation, and there is no corpus to evaluate it
 * against; and the failure this fixes is *worse* than a synonym miss — it is a
 * query returning the wrong memories, or none.
 *
 * The cost of that decision is stated plainly in [MemoryIndex]: a query whose
 * terms appear nowhere in a memory still retrieves nothing. `"where do I live"`
 * does not find a memory that says `"home address"`. That is a real limitation,
 * it is a limitation of lexical retrieval, and it is not fixable without
 * embeddings. It is documented rather than hidden.
 */
object MemoryText {

    /**
     * Words that connect sentences and mean nothing on their own.
     *
     * WHY an explicit list and not a length threshold: the old rule's length
     * threshold was a *proxy* for "common word", and a 3-letter threshold
     * admits "the", "and", "for", "are", "was", "you", "not", "but", "all",
     * "can", "how", "why", "who" — fourteen of the twenty most frequent English
     * words are three letters or fewer and therefore matched everything. A
     * length filter cannot separate "cat" from "the". Only a list can.
     *
     * WHY the list is short: every entry is a word that appeared in a real
     * query during the audit and retrieved nothing but noise. Adding a word
     * that is occasionally meaningful costs recall; adding one that is almost
     * always noise costs precision on every query. This list is the second kind.
     */
    private val STOPWORDS: Set<String> = setOf(
        // articles, conjunctions, prepositions
        "the", "and", "but", "for", "nor", "yet", "so", "with", "from", "into",
        "onto", "upon", "over", "under", "about", "after", "before", "between",
        "during", "through", "within", "without", "across", "against", "along",
        "around", "behind", "beyond", "near", "than", "that", "this", "these",
        "those", "there", "here", "then", "when", "where", "while", "which",
        "who", "whom", "whose", "what", "why", "how",
        // verbs and particles with no standalone content
        "are", "was", "were", "been", "being", "have", "has", "had", "does",
        "did", "doing", "done", "will", "would", "can", "could", "shall",
        "should", "may", "might", "must", "not", "you", "your", "yours", "our",
        "ours", "his", "her", "hers", "its", "their", "theirs", "them", "they",
        "get", "got", "give", "gave", "take", "took", "make", "made", "tell",
        "told", "say", "said", "know", "knew", "want", "need", "let", "please",
        "just", "also", "very", "much", "many", "more", "most", "some", "any",
        "all", "both", "each", "few", "other", "such", "only", "own", "same",
        "too", "can", "does", "am",
        // interrogatives that survive the stopword filter only as noise
        "am", "im", "ive", "youre", "whats", "wheres", "hows",
    )

    /** Shortest token kept. Two characters, because "wi" and "fi" are fragments. */
    const val MIN_TOKEN_LENGTH: Int = 3

    /**
     * Apostrophes and hyphens *inside* a word are removed, not treated as
     * separators.
     *
     * WHY: the old rule split on every non-alphanumeric character, so
     * `"wi-fi"` tokenised to `"wi"`,`"fi"` and `"what's"` to `"what"`,`"s"`.
     * Both fragments are then shorter than the length filter and both are
     * discarded, so the word vanished from the index entirely. Deleting the
     * character *inside* a run of letters, and keeping it as a separator
     * everywhere else, makes `"wi-fi"` → `"wifi"` and `"what's"` → `"whats"`
     * (which then stems to `"what"`, a stopword, which is correct — "what" is
     * not a thing to search for).
     */
    private val INNER_JOINER = Regex("(?<=[a-z0-9])['’_-](?=[a-z0-9])")

    private val SEPARATOR = Regex("[^a-z0-9]+")

    /**
     * Reduces a token to a comparison form.
     *
     * ## Why a hand-rolled stemmer and not Porter
     *
     * Porter (and Snowball, and any of the other dozen) are designed for
     * large-document retrieval where the recall gain across millions of tokens
     * pays for the collisions. This index holds tens of memories. The two
     * things a user actually needs are `"password"` to match `"passwords"` and
     * `"meeting"` to match `"meetings"`, and the only rule that delivers both
     * without the machinery is: strip a plural `s`, and strip a trailing `ing`.
     *
     * ## Why `-ed` is deliberately NOT stripped
     *
     * The obvious third rule is wrong often enough to not be worth it.
     * `"stored" -> "stor"` is not a word, and it does not merge with
     * `"store"` — it creates a *third* form that matches neither. The English
     * rules that recover it (restore the dropped `e` when the stem ends in a
     * consonant cluster) misfire on `"asked" -> "aske"`, `"used" -> "use"`,
     * `"walked" -> "walke"`. A personal-memory index is queried about nouns,
     * not about the past tense of verbs, so the rule buys almost no recall and
     * costs precision on every word it touches. It is left out on purpose, and
     * a word stored as `"stored"` will not be found by a query for `"store"`.
     *
     * ## Why the rules run to a fixed point
     *
     * `"meetings"` must reach the same form as `"meeting"`. One pass gives
     * `"meeting"` (plural rule) and then stops, while `"meeting"` gives
     * `"meet"` — two forms, and the plural no longer matches the singular.
     * Applying the rules until nothing changes makes both `"meet"`. The bound
     * of three passes is not a guess: the longest chain in ordinary English is
     * plural-then-ing (`meetings`), and each pass strictly shortens the token.
     */
    fun stem(token: String): String {
        var current = token
        repeat(3) {
            val next = stemOnce(current)
            if (next == current) return current
            current = next
        }
        return current
    }

    /** One reduction step. See [stem] for why it is applied to a fixed point. */
    private fun stemOnce(token: String): String {
        if (token.length < 4) return token
        // -ing: "meeting" -> "meet", "parking" -> "park". Guarded on the
        // remainder so "king", "ring" and "thing" keep their stems (2 and 3
        // characters) rather than becoming "k", "r" and "th".
        if (token.endsWith("ing") && token.length - 3 >= 3) {
            val stem = token.dropLast(3)
            // English doubles the final consonant before -ing: "running" ->
            // "runn" -> "run", "stopping" -> "stopp" -> "stop". Without this
            // the rule produces a form that matches neither the verb nor
            // anything else.
            if (stem.length >= 4 && isConsonant(stem[stem.length - 1]) &&
                stem[stem.length - 1] == stem[stem.length - 2]
            ) {
                return stem.dropLast(1)
            }
            return stem
        }
        // plural -es after a sibilant: "boxes" -> "box", "dishes" -> "dish",
        // "buses" -> "bus".
        if (token.endsWith("es") && token.length - 2 >= 3) {
            val stem = token.dropLast(2)
            if (stem.last() in "sxzh") return stem
        }
        // plural -s: "passwords" -> "password", "meetings" -> "meeting" (and
        // then the -ing rule takes it to "meet" on the next pass). Refused
        // when the token already ends in "ss", which is what keeps "address"
        // and "dishes"' sibling "classes" intact.
        if (token.endsWith("s") && !token.endsWith("ss") && token.length - 1 >= 3) {
            return token.dropLast(1)
        }
        return token
    }

    /** ASCII consonant test, deliberately narrow: this is English, not Unicode. */
    private fun isConsonant(c: Char): Boolean = c in "bcdfghjklmnpqrstvwxyz"

    /**
     * The indexed/searchable form of a piece of text: distinct, stemmed,
     * stopword-free tokens.
     *
     * Order is preserved on purpose. Ranking reads term positions in the
     * aggregate score, and a stable order makes an index reproducible.
     */
    fun terms(text: String): List<String> {
        val joined = INNER_JOINER.replace(text.lowercase(), "")
        val out = LinkedHashSet<String>()
        for (raw in SEPARATOR.split(joined)) {
            if (raw.length < MIN_TOKEN_LENGTH) continue
            if (raw in STOPWORDS) continue
            val stemmed = stem(raw)
            if (stemmed.length < MIN_TOKEN_LENGTH) continue
            if (stemmed in STOPWORDS) continue
            out += stemmed
        }
        return out.toList()
    }

    /**
     * The keyword projection persisted alongside a memory.
     *
     * Space-joined because both backends match on it: the in-RAM store splits
     * it back apart, and the SQL store wraps it in spaces and compares it
     * whole-token (see `MemoryQueries.LIKE_PREDICATE`). One producer for both.
     */
    fun keywords(text: String): String = terms(text).joinToString(" ")
}
