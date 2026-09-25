package dev.localintelligence.core.model.token

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Monotonicity, which is the property that makes this estimator safe to use for
 * search and for budgets.
 *
 * The contract: `count(a + b) >= count(a)` for all `a` and `b`. If it fails,
 * a caller can grow a prompt and watch the estimated cost go DOWN, and any
 * trim-until-it-fits loop terminates on a non-monotone function. On a phone
 * that loop runs until the battery dies.
 *
 * The run-length cost functions are all non-decreasing in run length by
 * construction, so the only ways this can break are:
 *
 *  - a class reclassified as characters are appended (a run that changes class
 *    mid-way),
 *  - the `atEndOfInput` whitespace special case, which pays one MORE token for
 *    a trailing space than an interior one,
 *  - integer overflow on absurd input.
 *
 * Each of those is tested here deliberately, plus a randomized sweep.
 */
class TokenCounterMonotonicityTest {

    private val counter = HeuristicTokenCounter()

    /**
     * Append one character at a time and assert the count never falls.
     *
     * Catches the whitespace boundary case specifically: appending a space to
     * `"a"` should not DECREASE the count, even though a trailing space is
     * priced differently from an interior one.
     */
    @Test
    fun `appending any single character never lowers the count`() {
        val alphabet = buildAlphabet()
        val seeds = listOf(
            "",
            "a",
            "hello world",
            "fun f(x: Int) = x + 1",
            """{"tool":"calendar.search","args":{"q":"standup"}}""",
            "検索 calendars 検索",
            "👨‍👩‍👧‍👦 family",
            "1234567890 42 3.14",
            "a  b   c    d",
            "\n\n\n    \t",
        )

        for (seed in seeds) {
            var prefix = seed
            var previous = counter.count(prefix)
            for (c in alphabet) {
                val extended = prefix + c
                val now = counter.count(extended)
                assertTrue(
                    // The ACTUAL prefix, and codepoints rather than characters:
                    // half of this alphabet is invisible or combining, so a
                    // message built from the glyphs is unreadable and cannot be
                    // reproduced by hand.
                    "count(${describe(extended)}) = $now but " +
                        "count(${describe(prefix)}) = $previous",
                    now >= previous,
                )
                prefix = extended
                previous = now
            }
        }
    }

    /**
     * The general property, randomized.
     *
     * No new dependency: `kotlin.random` ships with the stdlib, and a fixed
     * seed means a failure is reproducible. 4000 trials of random text built
     * from a class-stratified alphabet, because uniform-random ASCII would
     * almost never produce a CJK run or a ZWJ sequence and would miss exactly
     * the cases that are hardest to get right.
     */
    @Test
    fun `appending random text never lowers the count`() {
        val rng = Random(seed = 0xC0FFEE)
        val alphabet = buildAlphabet()
        val trials = 4000
        var checked = 0

        repeat(trials) {
            val seed = buildString(rng.nextInt(0, 40)) { append(alphabet.random(rng)) }
            val add = buildString(rng.nextInt(1, 40)) { append(alphabet.random(rng)) }
            val before = counter.count(seed)
            val after = counter.count(seed + add)
            assertTrue(
                "seed='$seed' add='$add' before=$before after=$after",
                after >= before,
            )
            checked++
        }
        assertEquals(trials, checked)
    }

    /**
     * Growing a document one word at a time.
     *
     * The realistic shape of the property: the agent loop appends an
     * observation, re-measures, and decides. A non-monotone count here would
     * make it re-add content it just dropped.
     */
    @Test
    fun `growing a document word by word is monotone`() {
        val words = listOf(
            "Task:", "search", "calendar", "for", "standup", "3", "results",
            "検索", "結果", "👍", "fun", "main()", "{", "}", "\n", "    ",
            "\"key\":", "value", "192.168.1.20",
        )
        val rng = Random(seed = 99)
        var text = ""
        var previous = counter.count(text)
        repeat(2000) {
            text += words[rng.nextInt(words.size)] + " "
            val now = counter.count(text)
            assertTrue("after appending at length ${text.length}: $now < $previous", now >= previous)
            previous = now
        }
    }

    /**
     * The estimator must not overflow into a negative number.
     *
     * A negative count would make every budget check pass. `String.repeat`
     * cannot make a 2-billion-char string on a default heap, so this uses a
     * large-but-feasible 4M characters — enough that `n * CJK_NUMERATOR` in
     * the CJK branch is still comfortably inside Int range, which is exactly
     * the arithmetic that could go wrong.
     */
    @Test
    fun `large input does not overflow`() {
        assertTrue(counter.count("中".repeat(2_000_000)) > 0)
        assertTrue(counter.count("a".repeat(2_000_000)) > 0)
        assertTrue(counter.count(" ".repeat(2_000_000)) > 0)
        assertTrue(counter.count("\n".repeat(2_000_000)) > 0)
    }

    /**
     * A prefix is never more expensive than the whole.
     *
     * The other half of monotonicity, stated without reference to what is
     * appended: any prefix of a string costs at most the string. This is the
     * form a truncation helper actually relies on.
     */
    @Test
    fun `every prefix costs at most the full string`() {
        val rng = Random(seed = 4242)
        val alphabet = buildAlphabet()
        repeat(300) {
            val text = buildString(rng.nextInt(1, 200)) { append(alphabet.random(rng)) }
            val full = counter.count(text)
            for (i in 1 until text.length) {
                val prefix = counter.count(text.substring(0, i))
                assertTrue(
                    "prefix of length $i costs $prefix > full $full for '$text'",
                    prefix <= full,
                )
            }
        }
    }

    /**
     * An alphabet weighted towards the classes that are actually hard.
     *
     * Uniform random would spend 95% of its time on ASCII letters and never
     * once test a ZWJ sequence, an emoji, a fullwidth form, or a run
     * boundary between two different classes.
     */
    /** Codepoint-escaped, so a failure names the character and not a glyph. */
    private fun describe(text: String): String =
        text.map { c ->
            val v = c.code
            if (v in 33..126 && c.isLetterOrDigit().not() || c.isLetterOrDigit()) {
                c.toString()
            } else {
                "\\u" + v.toString(16).padStart(4, '0').uppercase()
            }
        }.joinToString("")

    private fun buildAlphabet(): List<Char> = buildList {
        addAll("abcdefghijklmnopqrstuvwxyz".toList())
        addAll("ABCDEFGHIJKLMNOPQRSTUVWXYZ".toList())
        addAll("0123456789".toList())
        addAll(" \t\n\r".toList())
        addAll("{}[]()<>:;,.!?\"'`|/\\+=*&%^$#@~-_".toList())
        // CJK, kana, hangul, fullwidth.
        addAll("中文日本語漢字テストかなカナ한국어".toList())
        addAll("ａｂｃ１２３！？「」".toList())
        // Emoji, ZWJ, skin-tone and variation selectors, regional indicators.
        addAll("👍🎉😀🚀❤️".toList())
        add('‍')
        addAll("🏽🏻🏿".toList())
        add('️')
        addAll("🇳🇱".toList())
        // Non-Latin letters that must NOT fall through to a per-byte rule.
        addAll("ПриветЗдра".toList())
        addAll("αθήνα".toList())
        addAll("مرحبا".toList())
    }
}
