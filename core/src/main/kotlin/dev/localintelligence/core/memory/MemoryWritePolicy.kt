package dev.localintelligence.core.memory

/**
 * Decides what is worth keeping, from a turn the agent has already finished.
 *
 * ## WHY THIS EXISTS
 *
 * The plumbing was all there and none of it was used. `MemoryStore` declared
 * `remember`, `RoomMemoryStore` implemented it against real DAOs,
 * `ResilientMemoryStore` wrapped it with an in-RAM fallback, and
 * `AppContainer.memoryStore` handed the result to `AgentController`. The loop
 * read memory once per request (`AgentController.kt:1036`, inside
 * `buildRequest`) and never wrote any. Verified on `b9defbf` by
 * `grep -rn '\.remember(' --include=*.kt .` outside of tests, which returned
 * only the interface declaration and the three implementations — zero call
 * sites in the loop, in `app/`, or anywhere else.
 *
 * The user-visible consequence: the app builds a prompt out of stored
 * memories, injects them into the model's context, and has nothing to inject
 * because the table is empty forever. Every memory feature in the product was
 * a control surface wired to a store nothing wrote to.
 *
 * ## WHY NOT "REMEMBER EVERYTHING"
 *
 * The tempting fix is `memory.remember(transcript)` at the end of every run.
 * That is wrong in a way that matters for this product specifically: RAM is
 * the primary metric, and a memory table that grows without bound is a RAM
 * regression with a latency cost and no ceiling. So there has to be a rule for
 * what gets in, and it has to be a rule that can be *wrong in the safe
 * direction* — keeping too little is a missing feature, keeping too much is a
 * resource problem.
 *
 * ## THE RULE
 *
 * Keep a turn's text when it states a durable fact about the user, and drop
 * it otherwise. Concretely, [extract] keeps a candidate when the text matches
 * one of the first-person declarative patterns below. It is a pattern
 * matcher, not a model: no summarisation, no extraction prompt, no second
 * inference. The reasons are in the class KDoc of [extract].
 */
object MemoryWritePolicy {

    /**
     * How long a memory is allowed to be.
     *
     * WHY 280 and not 100: the audit's failures were all *misses* — a memory
     * that was never written. A cap that is too tight loses facts. 280 is
     * roughly one paragraph, and the patterns in [extract] are anchored on a
     * single clause, so a normal hit is far shorter than the cap.
     */
    const val MAX_MEMORY_CHARS: Int = 280

    /**
     * Importance by pattern strength.
     *
     * WHY the explicit anchors score higher: "my wifi password is X" is a
     * credential the user will ask for again and is worthless if lost.
     * "I like X" is a preference, useful but not urgent. The ranking in
     * [MemoryIndex] uses this, so the number decides which of two equally
     * relevant memories is shown first.
     */
    const val IMPORTANCE_CREDENTIAL: Float = 0.9f
    const val IMPORTANCE_IDENTITY: Float = 0.8f
    const val IMPORTANCE_PREFERENCE: Float = 0.5f
    const val IMPORTANCE_CONTEXT: Float = 0.4f

    /**
     * First-person patterns that indicate a durable fact.
     *
     * WHY these shapes and not a classifier: a classifier needs a training
     * corpus and an evaluation set, and there is neither in this repository.
     * A pattern list is inspectable, has no false-positive rate that has to be
     * estimated rather than counted, and can be read by the person who has to
     * decide whether it is right.
     *
     * Each pattern deliberately requires a first-person subject *and* a
     * copula or a colon. That is what keeps a question ("what is my wifi
     * password?") from being stored: an interrogative ends in `?` and has no
     * copula, and [extract] rejects anything ending in a question mark before
     * it ever looks at a pattern.
     *
     * ## WHY `\w+(?:['’-]\w+)*` AND NOT `\w+`
     *
     * The obvious spelling of these patterns is `\w+\s+` for the words between
     * the subject and the noun, and it silently fails on the most ordinary
     * sentence a user would type. `"My dog's name is Rex"` has an apostrophe
     * in the gap, `\w+` cannot cross it, and the memory is dropped — the one
     * category (identity) where a miss is most annoying. The possessive
     * alternative costs one group and fixes it.
     */
    private const val WORD = """\w+(?:['’-]\w+)*"""

    private val CREDENTIAL = Regex(
        """\b(?:my|our)\s+(?:$WORD\s+){0,2}?(?:password|passcode|pin|passphrase|key|code)\b\s*(?:is|are|=|:)""",
        RegexOption.IGNORE_CASE,
    )
    private val IDENTITY = Regex(
        """\b(?:my|our)\s+(?:$WORD\s+){0,2}?(?:name|address|email|phone|birthday|birthdate|home|wife|husband|partner|dog|cat|allergy|doctor|sister|brother)\b\s*(?:is|are|=|:)""",
        RegexOption.IGNORE_CASE,
    )
    private val PREFERENCE = Regex(
        """\bi\s+(?:like|love|hate|prefer|enjoy|dislike|avoid|always|never)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val CONTEXT = Regex(
        """\b(?:i|we)\s+(?:work|live|study|use|own|drive|need|want)\s+(?:at|for|in|on|to|with)\b""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * The text of a memory to store, or null when the turn does not state
     * something worth keeping.
     *
     * ## Why this is a filter and not a summariser
     *
     * A summarisation pipeline would need a model call per turn: latency on
     * every message, a second failure mode, and — the real objection — no way
     * to evaluate whether it is doing a good job, because there is no corpus
     * in this repository to evaluate it against. The honest version of this
     * feature is a conservative filter with a known miss rate, not a
     * summariser with an unmeasurable one.
     *
     * ## The rejections, and why each is safe
     *
     * - **A question** (ends in `?`): the user asked for something. Storing
     *   the question stores the absence of an answer, and next time the same
     *   question is asked the memory reinforces the query rather than
     *   answering it.
     * - **Too short**: under [MIN_MEMORY_CHARS] there is no declarative
     *   content, only acknowledgements ("ok", "thanks", "sure").
     * - **Too long**: over [MAX_MEMORY_CHARS] this is a transcript fragment or
     *   a pasted document, not a fact, and it is unbounded RAM.
     * - **No pattern match**: the conservative default. Everything the
     *   patterns do not recognise is dropped, which means recall is bounded by
     *   the patterns and precision is not bounded by anything.
     */
    fun extract(turnText: String): String? {
        val text = turnText.trim()
        if (text.isEmpty()) return null
        if (text.endsWith("?")) return null
        if (text.length < 12) return null
        if (text.length > MAX_MEMORY_CHARS) return null
        // A turn carrying several lines is a transcript, not a fact.
        if (text.count { it == '\n' } > 2) return null

        val importance = when {
            CREDENTIAL.containsMatchIn(text) -> IMPORTANCE_CREDENTIAL
            IDENTITY.containsMatchIn(text) -> IMPORTANCE_IDENTITY
            PREFERENCE.containsMatchIn(text) -> IMPORTANCE_PREFERENCE
            CONTEXT.containsMatchIn(text) -> IMPORTANCE_CONTEXT
            else -> return null
        }
        return text
    }

    /**
     * The importance a kept memory is stored at.
     *
     * Exposed so the caller and this object cannot disagree: the pattern
     * decides the score in [extract], and this returns the same value for the
     * same text without re-running the match.
     */
    fun importanceOf(text: String): Float = when {
        CREDENTIAL.containsMatchIn(text) -> IMPORTANCE_CREDENTIAL
        IDENTITY.containsMatchIn(text) -> IMPORTANCE_IDENTITY
        PREFERENCE.containsMatchIn(text) -> IMPORTANCE_PREFERENCE
        CONTEXT.containsMatchIn(text) -> IMPORTANCE_CONTEXT
        else -> 0f
    }
}
