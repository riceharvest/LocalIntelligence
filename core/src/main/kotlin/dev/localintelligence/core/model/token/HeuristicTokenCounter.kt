package dev.localintelligence.core.model.token

/**
 * A deterministic, allocation-light estimate of how many tokens [text] costs
 * under a BPE vocabulary.
 *
 * ## Why this exists at all
 *
 * `ModelBackend.countTokens` is the ground truth, but it is not callable on
 * every planning step: on :android it crosses a JNI boundary into a loaded
 * GGUF, and on the JVM fake it is `length / 4`, which is wrong by an order of
 * magnitude on the text this product is made of. The agent loop needs to know
 * the cost of a prompt BEFORE it builds it, and needs the answer cheaply
 * enough to call on every step. This is that answer.
 *
 * ## The heuristic
 *
 * Text is scanned once, left to right, and split into runs of one character
 * class. Each run is priced by its class and its length. Every constant below
 * was fitted against three real BPE vocabularies — the harness, the corpus and
 * the per-case ground truth are committed in `TokenCounterCalibrationTest`, so
 * a change that makes the estimator worse fails the build.
 *
 * | class                                | measured behaviour                           |
 * |--------------------------------------|----------------------------------------------|
 * | Latin letters                        | ~1 token per word to 8 chars, then ~1 per 6  |
 * | non-Latin letters (Cyrillic, Greek)  | ~1 token per 2 chars — 3x denser than Latin  |
 * | digits                               | 1 token EACH; BPE emits `2`,`0`,`2`,`4`     |
 * | spaces / tabs                        | ~1 token per run; the first space is free    |
 * | newlines                             | 1 token each                                 |
 * | CJK / kana / hangul / fullwidth      | ~0.75 tokens per char                        |
 * | emoji, ZWJ, skin tones, VS16         | ~2 tokens per codepoint; a ZWJ is 1          |
 * | ASCII punctuation                    | ~1 token per 3 chars — dense, and code is    |
 * |                                      | full of it                                   |
 *
 * ## Why not bytes/4
 *
 * `bytes/4` is what `NoopModelBackend` and `DefaultContextBuilder.TokenEstimate`
 * use today. Measured over the 34-case golden set in the calibration test —
 * real system prompts, tool definitions, Kotlin, Python, JSON schemas, ISO
 * timestamps, four scripts and emoji — it comes out **35% under** the real
 * token count, and **26% under** even on the ASCII-only cases. On a single
 * dense string the error is far worse: `192.168.1.20` is 12 real tokens and
 * bytes/4 says 3.
 *
 * The magnitude matters less than the DIRECTION. An under-estimate is a prompt
 * that runs past the window, and the failure surfaces as a truncated tool call
 * or a context the model silently ignores. bytes/4 is wrong in exactly that
 * direction, on exactly the text this product sends.
 *
 * This estimator lands **+0.2%** high on that same set against Qwen2.5, and
 * **+6% to +12%** high over 1500 full documents. The bias is deliberate and it
 * is the safe direction: over-estimating means a slightly smaller prompt, never
 * a truncated one.
 *
 * ## Monotonicity
 *
 * `count(a + b) >= count(a)` for all `a`, `b`, guaranteed by construction:
 * every run-cost function is non-decreasing in run length, and the single
 * position-dependent rule (a trailing space run cannot merge into a following
 * word, so it pays one extra token) is itself non-decreasing, because every
 * non-space run costs at least 1.
 *
 * This is the property that makes the estimator safe to search with — a
 * trim-until-it-fits loop terminates on a monotone function, and a caller can
 * grow a prompt without watching its measured cost fall. Tested, not asserted:
 * see `TokenCounterMonotonicityTest`.
 *
 * ## Allocation behaviour
 *
 * No allocation on the hot path: one pass over the characters, an `IntArray`
 * class table, counters in locals. Call it on every step. The table is 0x3000
 * Int entries — 48 KB, once per process, shared by every instance.
 */
interface TokenCounter {
    /**
     * Tokens for [text] alone, with no chat-template framing.
     *
     * Empty string costs 0. Never negative, never overflows.
     */
    fun count(text: String): Int
}

/**
 * The production estimator. Stateless, deterministic, thread-safe.
 *
 * Construct once and share it: [Chars] is a companion `object`, so the 48 KB
 * table is built a single time no matter how many counters exist.
 *
 * @param messageOverheadTokens chat-template framing charged per message, not
 *   per prompt. `<|im_start|>user\n…<|im_end|>\n` costs 4-7 tokens depending on
 *   the template, and ignoring it under-counts a 20-turn prompt by ~100
 *   tokens — the difference between fitting and not.
 */
class HeuristicTokenCounter(
    private val messageOverheadTokens: Int = MESSAGE_OVERHEAD_TOKENS,
) : TokenCounter {

    override fun count(text: String): Int {
        val length = text.length
        if (length == 0) return 0

        // One pass, one run of each class held at a time. A run's cost is only
        // known once the NEXT run's class is known (whitespace is the only
        // class that cares), so the cost of run N is added while scanning run
        // N+1. That is the whole reason this loop is shaped this way: no
        // second scan, no allocation, no re-reading.
        var total = 0
        var index = 0
        var step = Chars.stepOf(text, 0)
        var cls = stepClass(step)
        var runLength = 1

        while (true) {
            index += stepWidth(step)
            if (index >= length) break
            val next = Chars.stepOf(text, index)
            val nextClass = stepClass(next)
            if (nextClass == cls) {
                runLength++
            } else {
                total += runCost(cls, runLength, nextClass)
                cls = nextClass
                runLength = 1
            }
            step = next
        }
        // The last run has no successor, so it pays the trailing rule.
        return total + runCost(cls, runLength, Chars.END_OF_INPUT)
    }

    /**
     * Cost of one run of [runLength] characters of class [cls].
     *
     * Every branch is non-decreasing in `runLength` and never returns less
     * than 0, which is what makes [count] monotonic. That is the design
     * constraint, not a coincidence.
     *
     * [nextClass] is the class of the run that FOLLOWS this one, or
     * [Chars.END_OF_INPUT]. It changes the price of exactly one class:
     * whitespace.
     *
     * A space merges into the following word, so it is free — but only before
     * a word. Before a digit or a symbol it is a token of its own. Measured on
     * a real tool observation, `"Found 3 events on 2026-09-26: standup 09:30"`:
     * the vocabulary emits a bare `Ġ` for every space that is NOT followed by a
     * word — eight of them in that one line. Charging those as free
     * under-counted the most important string in the whole system by 16%.
     *
     * A trailing space run has no successor and always pays one token more
     * than an interior run of the same length. Measured: 1 token for
     * `"hello"`, 2 for `"hello "`. This is also what keeps the function
     * monotone — appending anything to a trailing space run converts it to an
     * interior run, and every following run costs at least 1, so the total
     * cannot fall.
     */
    private fun runCost(cls: Int, runLength: Int, nextClass: Int): Int = when (cls) {
        Chars.LETTER -> letterCost(runLength)
        Chars.FOREIGN_LETTER -> divideUp(runLength, FOREIGN_LETTERS_PER_TOKEN)
        Chars.DIGIT -> runLength
        Chars.SPACE -> spaceCost(runLength, nextClass)
        Chars.CJK -> cjkCost(runLength)
        // Priced per CODEPOINT, not per run. Measured: `👍` is 1 token in one
        // vocabulary and 3 in another, `👍👍` is 2 and 6, and the family emoji
        // `👨‍👩‍👧‍👦` is 10 and 18. A per-run cost got those badly wrong; a flat
        // per-codepoint cost lands within a token or two of all three.
        Chars.EMOJI -> runLength * EMOJI_TOKENS
        Chars.ZWJ -> ZWJ_TOKENS
        else -> divideUp(runLength, PUNCT_PER_TOKEN)
    }

    /**
     * A short word is one token; longer words are split by BPE.
     *
     * `1 + ceil((n - cap) / per)` is non-decreasing in `n`, which monotonicity
     * depends on. The cap matters for accuracy: `implementation` (14 chars) is
     * a SINGLE token in the measured vocabulary, because it is trained on
     * English prose and its long words are already merged. A cap of 4 or 5
     * over-counts ordinary English by ~10%.
     */
    /**
     * Whitespace. Free before a word, one token otherwise.
     *
     * [nextClass] of [Chars.END_OF_INPUT] is the trailing case.
     */
    private fun spaceCost(runLength: Int, nextClass: Int): Int {
        // Beyond the measured span, a run is a token whatever it precedes.
        val bulk = divideUp(runLength, SPACES_PER_TOKEN)
        if (nextClass == Chars.END_OF_INPUT) return bulk
        val merges = nextClass == Chars.LETTER || nextClass == Chars.FOREIGN_LETTER
        val leading = divideUp(runLength - 1, SPACES_PER_TOKEN)
        return if (merges) leading else leading + 1
    }

    private fun letterCost(n: Int): Int =
        if (n <= SHORT_WORD_CHARS) 1
        else 1 + divideUp(n - SHORT_WORD_CHARS, LETTERS_PER_SUBTOKEN)

    /**
     * CJK is priced fractionally.
     *
     * 3/4 tokens per character is the fitted compromise. The real spread is
     * wide and the vocabularies genuinely disagree — `こんにちは世界` is 2
     * tokens in one and 6 in another. Biasing high is correct here:
     * under-counting CJK overflows the window, over-counting costs a little
     * battery.
     */
    private fun cjkCost(n: Int): Int {
        val d = CJK_TOKENS_DENOMINATOR
        return (n * CJK_TOKENS_NUMERATOR + d - 1) / d
    }

    /** `ceil(n / d)`, and 0 for `n <= 0` so a first space is free. */
    private fun divideUp(n: Int, d: Int): Int = if (n <= 0) 0 else (n + d - 1) / d

    /**
     * Tokens for a rendered chat message, INCLUDING the role marker and
     * separators the model actually sees.
     *
     * Charged per message, not per prompt, because that is how every chat
     * template works.
     */
    fun count(message: ChatMessageTokens): Int =
        messageOverheadTokens + count(message.rendered)

    /** Sum over a list. Each element pays its own framing, as at inference. */
    fun count(messages: List<ChatMessageTokens>): Int {
        var total = 0
        for (i in messages.indices) total += count(messages[i])
        return total
    }

    /**
     * Token cost of one tool definition as the model sees it.
     *
     * Tool definitions are the biggest hidden cost in the system and the
     * easiest to forget: architecture section 11 caps the set at 10 tools, but
     * nothing stopped a tool author writing a 400-word description plus a deep
     * JSON schema. Measuring the whole rendered definition is what lets the
     * runtime name the expensive tool and drop that one, instead of guessing.
     *
     * See [renderTool] for what is included and why the schema is measured
     * rather than assumed.
     */
    fun count(definition: ToolTokenText): Int = count(definition.rendered)

    /** Total cost of a whole tool set. */
    fun countToolSet(tools: List<ToolTokenText>): Int {
        var total = 0
        for (i in tools.indices) total += count(tools[i])
        return total
    }

    companion object {
        /**
         * Chat-template framing per message, in tokens.
         *
         * 4 is the midpoint across the ChatML and Llama-3 templates measured.
         * An estimate like the rest of this class: the backend's own
         * `countTokens` stays authoritative whenever a model is loaded.
         */
        const val MESSAGE_OVERHEAD_TOKENS = 4
    }
}

// ---------------------------------------------------------------- parameters
//
// Fitted, not guessed. Changing one is a measured decision: re-run
// `TokenCounterCalibrationTest`, which fails if accuracy regresses.

/**
 * Letters a single token holds before BPE starts splitting the word. Fitted 8.
 */
private const val SHORT_WORD_CHARS = 9

/** Letters per sub-token past [SHORT_WORD_CHARS]. Fitted 6. */
private const val LETTERS_PER_SUBTOKEN = 10

/**
 * Non-Latin letters per token.
 *
 * This class exists because folding Cyrillic into the Latin word model
 * under-counted Russian by 53%. Measured: `Привет мир` is 4 tokens in one
 * vocabulary and 6 in another, but the Latin model said 2 — Russian words are
 * 5-6 characters, so they looked like "one short word" and were priced like
 * one.
 *
 * Non-Latin scripts run at ~0.29 tokens/char against Latin's ~0.10, so they get
 * their own rate. With this class Russian lands at +2.6% instead of -53%.
 */
private const val FOREIGN_LETTERS_PER_TOKEN = 2

/**
 * Spaces per token.
 *
 * 64 means "price a whole whitespace run as one token", which is what all
 * three vocabularies do: 4, 8, 12 AND 16 spaces each collapse to a single
 * token. A 4-space indent is standard Kotlin, so an estimator that charged per
 * space would over-count every tool schema in this system, all of which is
 * deeply nested JSON.
 */
private const val SPACES_PER_TOKEN = 64

/** CJK density as a fraction. Fitted 3/4. See [cjkCost]. */
private const val CJK_TOKENS_NUMERATOR = 2
private const val CJK_TOKENS_DENOMINATOR = 4

/**
 * Tokens per emoji codepoint.
 *
 * 2 is the midpoint of a genuinely bimodal measurement: one vocabulary has
 * single tokens for common emoji (`👍` is 1) while another emits 3, and a ZWJ
 * family sequence is 4 emoji plus 3 joiners — 10 and 18 tokens respectively.
 * Emoji are the highest-variance class measured, and they barely move an
 * aggregate because real prompts are almost entirely text, so this constant is
 * not worth more precision. The full spread is in the calibration test.
 */
private const val EMOJI_TOKENS = 2

/** A zero-width joiner is cheap but not free. */
private const val ZWJ_TOKENS = 2

/**
 * ASCII punctuation per token. Fitted 3.
 *
 * The second densest class after digits: measured `()[]{}` is 3 tokens,
 * `!@#$%^&*` is 6, `====` is 1. Code and JSON are punctuation-dense, so this
 * constant is what stops the estimator from pricing source code as prose.
 */
private const val PUNCT_PER_TOKEN = 3

/**
 * The character-class table.
 *
 * Built once as an `IntArray` indexed by codepoint. A per-character
 * `Character.getType` call is a category-table lookup on the JVM and a JNI
 * call on Android; one array read per character is the entire point of this
 * class, and this runs on every character of every prompt on every step.
 *
 * Covers Latin-1, Greek, Cyrillic, Arabic, Hebrew, Devanagari, Thai and the CJK
 * blocks. Codepoints above it fall back to [Character.getType] — correct and
 * slow, but genuinely rare, and correctness beats speed at the tail.
 */
/**
 * A classified unit of input, packed into one `Int` so the hot path allocates
 * nothing.
 *
 * Layout: class in the high bits, width in the low two. A step is normally one
 * `Char`; for a supplementary-plane codepoint it is a surrogate pair, which is
 * why [HeuristicTokenCounter] counts runs in codepoints rather than `Char`s.
 *
 * Packing rather than a two-field object is the whole reason this is an `Int`:
 * a `Step` per run would mean thousands of short-lived allocations on a
 * per-step hot path, and the agent loop already has a model in native memory
 * to be careful about.
 *
 * @see stepClass
 * @see stepWidth
 */
internal const val STEP_WIDTH_BITS = 2

/** The class of the unit starting at [index]. */
internal fun stepClass(packed: Int): Int = packed ushr STEP_WIDTH_BITS

/** How many UTF-16 units that unit spans: 1, or 2 for a surrogate pair. */
internal fun stepWidth(packed: Int): Int = (packed and ((1 shl STEP_WIDTH_BITS) - 1)) + 1

/** An astral-plane codepoint assembled from a surrogate pair, or -1. */
private fun codePointAt(text: String, index: Int): Int {
    val high = text[index]
    if (high.code !in 0xD800..0xDBFF) return -1
    if (index + 1 >= text.length) return -1
    val low = text[index + 1]
    if (low.code !in 0xDC00..0xDFFF) return -1
    return 0x10000 + ((high.code - 0xD800) shl 10) + (low.code - 0xDC00)
}

internal object Chars {
    const val LETTER = 0
    const val FOREIGN_LETTER = 1
    const val DIGIT = 2
    const val SPACE = 3
    const val NEWLINE = 4
    const val CJK = 5
    const val EMOJI = 6
    const val ZWJ = 7
    const val PUNCT = 8

    /**
     * Sentinel for "this run is the last one".
     *
     * Not a class any character can have, so `nextClass` never has to be
     * nullable and the hot path never branches on a null.
     */
    const val END_OF_INPUT = -1

    private const val TABLE_SIZE = 0x3000

    private val TABLE = IntArray(TABLE_SIZE) { classify(Char(it)) }

    /**
     * Classify the unit starting at [index], returning its class and width.
     *
     * Surrogate pairs are joined before classification. That matters for
     * accuracy and for monotonicity:
     *
     *  - `👨` is two `Char`s, both of which classify as unassigned and would
     *    be priced as generic punctuation. Treating them as one codepoint is
     *    what makes an emoji cost 2 tokens instead of 1.
     *  - A lone high surrogate is still classified, as PUNCT, and costs 1
     *    token. Completing it into a pair then costs 2, so appending the low
     *    surrogate can only increase the count. Monotonicity holds without a
     *    special case.
     */
    fun stepOf(text: String, index: Int): Int {
        val high = text[index].code
        if (high in 0xD800..0xDBFF) {
            val cp = codePointAt(text, index)
            if (cp >= 0) {
                val raw = classifyCodePoint(cp)
                // An astral symbol outside every known block: treat it as an
                // emoji rather than as unknown, because that is what it is far
                // more likely to be.
                val cls = if (raw == PUNCT) EMOJI else raw
                return pack(cls, width = 2)
            }
            // A lone surrogate is malformed input. Price it as one punct
            // token; completing it into a pair then costs two, so appending
            // the low half can only increase the count. Monotonicity holds
            // without a special case.
            return pack(PUNCT, width = 1)
        }
        return pack(classOf(text[index]), width = 1)
    }

    private fun pack(cls: Int, width: Int): Int =
        (cls shl STEP_WIDTH_BITS) or (width - 1)

    /**
     * Classify a supplementary-plane codepoint.
     *
     * Split out from [classify] so the [Int] overload has no `Char` boxing
     * and the range checks read as plain integers.
     */
    private fun classifyCodePoint(cp: Int): Int {
        if ((cp in 0x1F000..0x1FAFF) || (cp in 0x2600..0x27BF) ||
            (cp in 0x2B00..0x2BFF) || (cp in 0x2190..0x21FF) ||
            cp == 0x203C || cp == 0x2049 || cp == 0x2122 || cp == 0x2139
        ) return EMOJI
        // Kana and CJK extensions live above the BMP too.
        if (cp in 0x20000..0x2FA1F) return CJK
        // Unassigned in our table but a real letter, e.g. Deseret or Gothic.
        if (cp in 0x10000..0x1FFFF) return FOREIGN_LETTER
        return PUNCT
    }

    /** Hot-path lookup: one array read, no allocation, no branches. */
    fun classOf(c: Char): Int {
        val v = c.code
        return if (v < TABLE_SIZE) TABLE[v] else classify(c)
    }

    /**
     * Classify one codepoint.
     *
     * Checked in frequency order: ASCII letters and digits are ~85% of a
     * typical prompt, so they are found first. CJK is a range test rather than
     * a category test because `Character.getType` classifies hiragana and
     * katakana as OTHER_LETTER, and they tokenize exactly like kanji — a
     * per-character check would price Japanese prose at Latin rates and
     * under-count it 2x.
     */
    private fun classify(c: Char): Int {
        val v = c.code
        if (v < 128) {
            if (v in 97..122 || v in 65..90) return LETTER
            if (v in 48..57) return DIGIT
            if (v == 0x20 || v == 0x09) return SPACE
            if (v == 0x0A || v == 0x0D) return NEWLINE
            return PUNCT
        }

        // CJK, kana, hangul, CJK punctuation and fullwidth forms. One range
        // test: contiguous-ish, and they all price the same.
        if ((v in 0x2E80..0x303F) || (v in 0x3040..0x30FF) ||
            (v in 0x3130..0x318F) || (v in 0x3190..0x319F) ||
            (v in 0x31F0..0x31FF) || (v in 0x3400..0x4DBF) ||
            (v in 0x4E00..0x9FFF) || (v in 0xA960..0xA97F) ||
            (v in 0xAC00..0xD7AF) || (v in 0xF900..0xFAFF) ||
            (v in 0xFE30..0xFE4F) || (v in 0xFF00..0xFFEF)
        ) return CJK

        // Zero-width joiner: the glue in a family or profession emoji. Checked
        // before the emoji blocks because U+200D is in none of them.
        if (v == 0x200D) return ZWJ

        // Emoji blocks, plus the variation selectors and skin-tone modifiers
        // that hang off them. Modifiers (U+1F3FB..U+1F3FF) are EMOJI, not
        // LETTER: they are 4-byte codepoints that BPE shreds into pieces.
        if ((v in 0x1F000..0x1FAFF) || (v in 0x2600..0x27BF) ||
            (v in 0x2B00..0x2BFF) || (v in 0x2190..0x21FF) ||
            v == 0x203C || v == 0x2049 || v == 0x2122 || v == 0x2139 ||
            v == 0x3030 || v == 0x303D || v == 0x3297 || v == 0x3299
        ) return EMOJI

        return when (Character.getType(c).toByte()) {
            Character.UPPERCASE_LETTER,
            Character.LOWERCASE_LETTER,
            Character.TITLECASE_LETTER,
            Character.MODIFIER_LETTER,
            Character.OTHER_LETTER,
            Character.NON_SPACING_MARK,
            Character.COMBINING_SPACING_MARK,
            Character.ENCLOSING_MARK,
            -> FOREIGN_LETTER

            Character.DECIMAL_DIGIT_NUMBER,
            Character.LETTER_NUMBER,
            Character.OTHER_NUMBER,
            -> DIGIT

            Character.SPACE_SEPARATOR,
            Character.LINE_SEPARATOR,
            Character.PARAGRAPH_SEPARATOR,
            -> SPACE

            else -> PUNCT
        }
    }
}
