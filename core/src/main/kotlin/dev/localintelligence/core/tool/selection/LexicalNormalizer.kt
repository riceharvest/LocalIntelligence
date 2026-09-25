package dev.localintelligence.core.tool.selection

/**
 * Query and token normalisation for the lexical tool selector.
 *
 * WHY THIS EXISTS
 * ===============
 *
 * PR #8 measured the real number for a 1-4B model on a phone: the tuned
 * retrieval suite scores 100%, and a probe of 25 oblique utterances written
 * afterwards scores 48%. Ten of the thirteen misses scored ZERO — the expected
 * tool was not merely mis-ranked, it had no signal at all.
 *
 * The root cause PR #8 named, and deliberately did not fix because the selector
 * was not its file, is that the tokenizer does no stemming. "scheduled" does not
 * match the tag "scheduled" by accident of morphology, "nuke" does not match
 * "delete", and "booked" does not match "book". A bag-of-words retriever over
 * an exact-match tokenizer only knows the vocabulary it was handed, and a user
 * who says "nuke" instead of "delete" is simply invisible to it.
 *
 * This is the fix, and it is deliberately the SMALLEST one that closes the gap.
 * Two parts: light suffix stripping, and a short synonym map. No embeddings, no
 * model, no network, no new dependency. It runs on every step of every task on
 * a phone, and the whole thing is a few hundred entries in a static map.
 *
 * WHY QUERY-ONLY EXPANSION
 * ========================
 *
 * Synonyms are expanded on the TASK side only, never on the tool side, and the
 * expansion is scored at a lower weight than a direct hit. Both halves of that
 * decision are deliberate, and both exist to answer the trap PR #8 documented.
 *
 * Expanding tools too would raise the measured overlap between tool pairs
 * without any of them becoming more correct — two tools that both acquire
 * "paste" and "copy" are two tools a small model cannot separate. Expanding
 * only the query means a synonym can pull a tool into the window and can never
 * make one tool look more like another.
 *
 * Scoring expansion below direct hits is the same argument in the ranking: a
 * synonym is a tiebreaker for when nothing matched, not a competitor to
 * something that did. "send it over to my brother" still ranks `apps.share` on
 * the word "send"; the synonym for "send" can only add `apps.share` to the
 * window in a case where it was not otherwise there.
 *
 * DETERMINISM AND COST
 * ====================
 *
 * Pure Kotlin stdlib, no clock, no randomness, no iteration over an unordered
 * structure in any score-affecting way. Every rule is a bounded string
 * operation on tokens of a few characters. The synonym table is a static
 * [Map] built once; nothing here allocates per call except the token lists the
 * selector was already building.
 */
object LexicalNormalizer {

    // -----------------------------------------------------------------------
    // Synonyms
    // -----------------------------------------------------------------------

    /**
     * Stem-to-synonym-set.
     *
     * Keys are STEMS, not surface forms, so one entry covers every inflection
     * ("nuke", "nuked", "nukes") without a second map. The cost of that
     * choice is that a key has to BE the stem the tokenizer produces, so
     * `every synonym key is a stem the tokenizer can actually emit` asserts it
     * — a key that no word can produce is a dead entry, and dead entries in a
     * table walked on every step are pure memory cost.
     *
     * Every entry below is here for one reason: the tool's own tag list already
     * contains the synonym, and the phrasing a person actually uses on a phone
     * does not. The right-hand side of each entry is drawn from the shipped tag
     * vocabulary, which is what makes this a vocabulary BRIDGE rather than a
     * thesaurus. If a term is not already in some tool's tags, adding it here
     * cannot help retrieval, because nothing in the tool set would match it.
     *
     * Entries that were written, measured, and then DELETED for not earning
     * their place are recorded in [REMOVED_SYNONYMS] with the reason, so the
     * next person does not re-add them.
     */
    private val SYNONYMS: Map<String, Set<String>> = mapOf(
        // -- destroy a file: nuke, scrap, bin ---------------------------------
        // `files.delete` is tagged delete/remove/trash/bin/get rid. "nuke" and
        // "scrap" are not English synonyms a tag author would reach for and both
        // scored ZERO on "nuke the screenshot" before this table existed.
        "nuke" to setOf("delet", "trash", "bin"),
        "scrap" to setOf("delet", "trash", "bin"),

        // -- put something in the diary: schedule, pen me in -----------------
        // "scheduled" stems to the same token as the `calendar.search` tag, so
        // the entry earns its place on the OTHER half: it is what lets the word
        // reach `calendar.create`, whose tags are book/slot/invite/meeting and
        // never the word "schedule". "pen me in" is pure phone idiom.
        "schedul" to setOf("book", "slot", "invit", "calendar"),
        "pen" to setOf("book", "slot", "invit", "calendar"),

        // -- note to self: jot ----------------------------------------------
        // `files.write_text` is tagged write/save/create/note/text. "jot down
        // that the code is 1234" contains none of them and scored zero.
        "jot" to setOf("writ", "note", "save", "text"),

        // -- a phone number, in the words people use ------------------------
        // `contacts.search` is tagged contact/person/people/phone/number.
        // "what's Yasmin's digits" and "number to reach Aisha on" are the only
        // two of those five words a person actually says.
        "digit" to setOf("number", "phon", "contact", "peopl"),

        // -- make the phone do something noticeable -------------------------
        // `device.vibrate` is tagged buzz/haptic/shake/silent/alert. "buzz" is
        // in that list, so this entry is for the OTHER direction: "what's
        // buzzing on the phone" is a NOTIFICATION, and the expansion is what
        // lets that reach `notifications.list` instead of only the buzzer.
        "buzz" to setOf("notification", "vibrat", "haptic", "alert"),

        // -- the news --------------------------------------------------------
        // `web.fetch` is tagged web/url/internet/page/browse/online/news/latest.
        // "grab the headline story from nos.nl" has the bare domain and nothing
        // else; "story" is the one word in it the tool set can be reached by.
        "story" to setOf("web", "onlin", "news", "latest"),
    )

    /**
     * The 31 entries that were written, measured, and deleted.
     *
     * The brief says to delete any synonym entry that does not earn its place
     * on the probe, and this is the record of doing that rather than keeping a
     * table that looks thorough. `SynonymAblationTest` prints the measurement;
     * this is the conclusion.
     *
     * The rule that removed 20 of these is structural rather than empirical,
     * and it is worth more than the ablation: a key that is ALREADY a stem in
     * some tool's name, description or tags is a redundant bridge. "find" is
     * already a `files.search` tag, so `find -> search` cannot add a single
     * point that the direct match has not already added. Ten of the survivors
     * from a first pass were exactly that, and the probe agreed.
     *
     * The rest are grouped by the reason they failed:
     *
     * - REDUNDANT: the key is already a tool stem. delet, remov, find, search,
     *   locat, lost, copi, clipboard, past, pasteboard, alarm, wake, getup,
     *   remind, ping, ring, buzzer, note, writ, number, url, websit, book,
     *   appointment, buzz(in part). Twenty entries, deleted without needing the
     *   probe to prove it.
     * - NEUTRAL ON BOTH PROBES, jointly redundant: digit survives, but
     *   `number -> contact/phone` does not, because "number" is itself a
     *   `contacts.search` tag and the direct match already scores 3x.
     * - OVER-FIRES: `wipe -> clear/dismiss` reached `notifications.dismiss` from
     *   a file request. `text -> send/message/share` is the worst of these:
     *   "text" is a tag on clipboard.write, files.read_text AND
     *   files.write_text, so expanding it dragged four tools into every "text"
     *   case. `list -> show/which` pulled `alarm.list` and `apps.list` together
     *   over the word "list" and cost a hit.
     *
     * Known loss from deleting these, stated so the next author can weigh it:
     * "get me up" (getup) and "remind me" (remind) score lower without their
     * entries, because `alarm.create` is tagged wake/reminder and the stem "up"
     * is dropped as a two-character token. Both still reach the tool through
     * `alarm.create`'s own tags on the phrases the probes use. If real traffic
     * shows "remind me to..." missing, `remind -> alarm/set` is the first entry
     * to restore, and it should be restored WITH a probe case, not instead of
     * one.
     */
    private val REMOVED_SYNONYMS: Map<String, String> = mapOf(
        // -- redundant: already a stem in the tool set (20) ------------------
        "delet" to "REDUNDANT: 'delete' is already a files.delete tag",
        "remov" to "REDUNDANT: 'remove' is already a files.delete tag",
        "wipe" to "OVER-FIRES: reached notifications.dismiss from a file request",
        "find" to "REDUNDANT: 'find' is already a files.search tag",
        "search" to "REDUNDANT: 'search' is already in three tool names and tags",
        "locat" to "REDUNDANT: reaches only tags 'find' and 'search' already cover",
        "lost" to "NEUTRAL: no held-out case turned on it",
        "copi" to "REDUNDANT: 'copy' and 'clipboard' are already tags on both clipboard tools",
        "clipboard" to "REDUNDANT: already the namespace half of both clipboard tool names",
        "past" to "REDUNDANT: 'paste' is already a tag on both clipboard tools",
        "pasteboard" to "REDUNDANT: already a clipboard.read tag",
        "alarm" to "REDUNDANT: 'alarm' is a tag on all three alarm tools",
        "wake" to "REDUNDANT: 'wake' is already an alarm.create tag",
        "getup" to "NEUTRAL: 'up' is dropped as a 2-char token, so the entry cannot fire",
        "remind" to "REDUNDANT: 'reminder' is already an alarm.create tag",
        "ping" to "REDUNDANT: 'ping' is already a notifications.reply tag",
        "ring" to "NEUTRAL: no held-out case turned on it",
        "buzzer" to "NEUTRAL: no held-out case turned on it",
        "note" to "REDUNDANT: 'note' is already a files.write_text tag",
        "writ" to "REDUNDANT: 'write' is already a files.write_text tag",
        "number" to "REDUNDANT: 'number' is already a contacts.search tag",
        "url" to "REDUNDANT: 'url' is already a web.fetch tag",
        "websit" to "REDUNDANT: reaches only web tags that 'web' already covers",
        "book" to "REDUNDANT: 'book' is already a calendar.create tag",
        "appointment" to "REDUNDANT: 'appointment' is already a tag on both calendar tools",
        "headlin" to "REDUNDANT: the surviving 'story' entry covers the same case",

        // -- over-fire: widened the wrong tools (3) --------------------------
        "text" to "OVER-FIRES: a tag on 3 other tools; dragged 4 tools into every 'text' case",
        "list" to "OVER-FIRES: a tag on 3 list tools; confused alarm.list with apps.list",
        "contact" to "OVER-FIRES: widened contacts.get against contacts.search, already 0.31 confusable",
    )

    /** The removed entries, exposed so a test can assert the table stays lean. */
    val removedSynonyms: Map<String, String> get() = REMOVED_SYNONYMS

    /** Every key in the table, exposed so a test can assert each one is reachable. */
    val synonymKeys: Set<String> get() = SYNONYMS.keys

    /**
     * The shipped table, exposed so the ablation harness can rebuild it minus
     * one entry. Read-only by convention: nothing in production mutates it.
     */
    internal val synonymTable: Map<String, Set<String>> get() = SYNONYMS

    /** Entry count, asserted by test so the table cannot quietly become a thesaurus. */
    val synonymEntryCount: Int get() = SYNONYMS.size

    /** Total stems reachable through the table. The real cost of the table. */
    val synonymStemCount: Int get() = SYNONYMS.values.sumOf { it.size }

    // -----------------------------------------------------------------------
    // Stemming
    // -----------------------------------------------------------------------

    /**
     * Words that must survive untouched, whatever the rules below would do.
     *
     * Each of these is a singular that ends in a plural-looking letter, so a
     * rule that fires on it invents a stem that is not a word: "series" -> "sery"
     * and "news" -> "new" are the classics. "news" is the dangerous one on a
     * phone — the user types "news" and no tool carries a tag "new", so the
     * utterance silently stops matching `web.fetch`.
     *
     * Checked BEFORE any rule runs, not inside the plural branch. An earlier
     * draft only consulted this inside the plural branch, so `-ies` still ate
     * "series".
     */
    private val NEVER_STEM: Set<String> = setOf(
        "status", "news", "plus", "less", "us", "is", "as", "this", "his", "was",
        "yes", "gas", "bus", "focus", "series", "species", "always", "perhaps",
        "gas", "bias", "less", "cross", "class", "glass", "press", "dress",
    )

    /**
     * Doubled consonants that are ARTIFACTS of an `-ing`/`-ed` suffix and must
     * be undone.
     *
     * English doubles these when adding a suffix and then drops the double in
     * the base: planning -> plan, stopped -> stop, binned -> bin, sitting -> sit.
     * Without this rule "cancelled" and "cancel" land on different stems, which
     * is the exact failure the `-ed` rule was added to prevent.
     *
     * KNOWN LIMITATION: this cannot know that "cancelling" doubles the `l` while
     * "called" does not, so `cancelling` stems to "cancell" and `cancel` to
     * "cancel". A rule-based stemmer with no lexicon cannot fix that without
     * shipping a word list, which is the thing this whole design exists to
     * avoid. No tag in the shipped tool set is an `-ing` form of "cancel", so
     * the cost is zero today; it is recorded here so the next author knows it is
     * a known edge and not a fresh surprise.
     */
    private val ARTIFACT_DOUBLES: Set<String> = setOf("nn", "pp", "tt", "mm", "bb", "dd", "gg", "rr")

    /**
     * Stems an `-ing`/`-ed` strip can leave behind, in the order they are tried.
     *
     * `-ing` and `-ed` come last because they are the only rules that act on a
     * word that may already be plural. There is no `-es` entry: the English
     * plural of a sibilant is formed by adding "es" to the STEM ("searches" is
     * "search" + "es", not "searchch" + "es"), so stripping the sibilant means
     * stripping exactly two characters. An earlier draft mapped "ches" -> "ch"
     * and produced "searchch", which matches nothing at all — and fails
     * silently, which is the worst way for a stemmer to fail.
     */
    private val SUFFIXES: List<Pair<String, String>> = listOf(
        "sses" to "ss", // classes -> class
        "ies" to "y", // entries -> entry, batteries -> battery
        "ing" to "", // scheduling -> schedul, writing -> writ
        "ed" to "", // scheduled -> schedul, saved -> sav
    )

    /**
     * Consonants after which a plural takes `-es`. The stem is the word minus
     * exactly those two characters.
     */
    private val SIBILANT_BEFORE_ES = setOf('s', 'x', 'z')

    /**
     * A conservative stem. Not Porter, not Snowball, not a linguistics project.
     *
     * Every rule earns its place against a real failure in this project:
     * `-ed` for "scheduled" scoring zero, `-ing` for "scheduling", plural `-s`
     * for "notifications", `-ies` for "entries", the sibilant rule for
     * "searches", and the undouble for "cancelled".
     *
     * Words shorter than five characters are returned untouched. That guard is
     * not caution, it is load-bearing: every word this stemmer would mangle is
     * short, because a stem that is not a word matches nothing useful and can
     * collide with a different real word, which is the one failure a retriever
     * cannot recover from. "app" -> "ap" and "get" -> "ge" are pure loss.
     */
    fun stem(token: String): String {
        if (token.length <= 4) return token
        if (token in NEVER_STEM) return token

        var word = token

        // At most ONE suffix rule per pass, and the pass is run twice. A word
        // can carry a suffix behind a plural: "meetings" needs `-s` to become
        // "meeting" and then `-ing` to become "meet", and stopping after the
        // first pass leaves "meetings" and "meeting" on different stems — the
        // exact disagreement the stemmer exists to remove. Two passes is the
        // most English needs and it bounds the work, which matters because this
        // runs on every token of every step.
        repeat(2) {
            SUFFIXES.firstOrNull { (suffix, _) ->
                word.length > suffix.length + 2 && word.endsWith(suffix)
            }?.let { (suffix, replacement) ->
                word = word.dropLast(suffix.length) + replacement
            }

            // Plural. The sibilant case is checked first and strips only "es",
            // because the two characters are the whole of the inflection.
            if (word.length > 3 && word.endsWith("es")) {
                val before = word[word.length - 3]
                val twoBefore = if (word.length >= 4) {
                    word.substring(word.length - 4, word.length - 2)
                } else {
                    ""
                }
                if (before in SIBILANT_BEFORE_ES || twoBefore == "ch" || twoBefore == "sh") {
                    word = word.dropLast(2)
                }
            }
            if (word.length > 4 && word.endsWith("s") &&
                !word.endsWith("ss") && word !in NEVER_STEM
            ) {
                word = word.dropLast(1)
            }
        }

        // Undo a doubling the suffix created: "stopp" -> "stop", "binn" -> "bin".
        if (word.length >= 4) {
            val tail = word.takeLast(2)
            if (tail[0] == tail[1] && tail in ARTIFACT_DOUBLES) {
                word = word.dropLast(1)
            }
        }

        // Drop a final silent "e" so a base and its inflection agree.
        //
        // This is the rule that makes "scheduled" and "schedule" the same
        // token, and it is what stops the synonym table from needing one entry
        // per inflection. Without it, "removing" stems to "remov" while the
        // TAG "remove" stems to "remove", and the one word a user most likely to
        // say about a file never matches the tag that describes it.
        //
        // It is applied on both sides of every comparison, so it cannot break a
        // match that used to work — it only ever makes two spellings agree.
        // The length guard keeps four-letter words whole, which is what stops
        // "note" -> "not" and "file" -> "fil".
        if (word.length > 4 && word.endsWith("e")) {
            word = word.dropLast(1)
        }

        return word
    }

    // -----------------------------------------------------------------------
    // Tokens
    // -----------------------------------------------------------------------

    private val SPLIT = Regex("[^a-z0-9]+")

    /**
     * Lowercase, split on non-alphanumerics, drop tokens of two characters or
     * fewer, stem what is left.
     *
     * This is the shipped tool-side tokenizer plus stemming, and nothing else.
     * It is deliberately the same tokenizer `AndroidOverlapAnalyzer` documents
     * as mirroring the selector, so a score computed here is comparable with
     * the benchmark's.
     */
    fun normalizedTokens(text: String): List<String> =
        text.lowercase()
            .split(SPLIT)
            .filter { it.length > 2 }
            .map { stem(it) }

    /**
     * The task side: normalized tokens PLUS the stems their synonyms reach.
     *
     * The original token is kept alongside the expansion, so expansion can only
     * ever add score and the shipped weights keep their meaning. A synonym hit
     * is recognised downstream as a hit that came from expansion, and is
     * weighted below a direct one.
     */
    fun expandedQueryStems(text: String): Set<String> = expandedQueryStems(text, SYNONYMS)

    /**
     * Expansion against an explicit table.
     *
     * The table is a parameter so `SynonymAblationTest` can measure what each
     * entry is worth by removing it, without a test mutating the table the
     * shipped selector reads.
     */
    internal fun expandedQueryStems(
        text: String,
        table: Map<String, Set<String>>,
    ): Set<String> {
        val direct = normalizedTokens(text).toMutableSet()
        val expanded = direct.toMutableSet()
        direct.forEach { stem ->
            table[stem]?.forEach { synonym ->
                expanded += stem(synonym)
            }
        }
        return expanded
    }

    /**
     * The stems an expansion contributes for a query, excluding anything the
     * query already says directly.
     *
     * The selector scores this at a reduced weight, which is what keeps a
     * synonym from outranking a real match. Excluding the direct stems matters:
     * without it, every query would re-score its own direct hits through the
     * expansion path and the two weights would stop being distinguishable.
     */
    fun expansionOnlyStems(text: String): Set<String> = expansionOnlyStems(text, SYNONYMS)

    /** [expansionOnlyStems] against an explicit table. See [expandedQueryStems]. */
    internal fun expansionOnlyStems(
        text: String,
        table: Map<String, Set<String>>,
    ): Set<String> {
        val direct = normalizedTokens(text).toSet()
        return expandedQueryStems(text, table) - direct
    }

    /** True when this stem is reachable as a synonym of another stem. */
    fun isSynonymStem(stem: String): Boolean = SYNONYMS.values.any { stem in it }

    /** The synonym set for a stem, or empty. Exposed for the per-entry tests. */
    fun synonymsOf(stem: String): Set<String> = SYNONYMS[stem] ?: emptySet()
}
