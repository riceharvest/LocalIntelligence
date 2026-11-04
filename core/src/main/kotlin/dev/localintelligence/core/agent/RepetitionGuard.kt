package dev.localintelligence.core.agent

import kotlin.math.ceil

/**
 * Stops a model that repeats one fragment until its output budget runs out.
 *
 * A small on-device model in a repetition loop spends its whole generation
 * echoing one line, and on a phone every token of that is battery and time the
 * user watches. This is a port of Hermes `agent/repetition_guard.py`
 * (`is_repetition_dominated`, `is_runaway_repetition`), with the same rule: a
 * contiguous run of 60-character windows, or one normalised line, or one exact
 * periodic unit, covers at least half of the text and repeats at least five
 * times.
 *
 * WHY THE LINE-SHAPE TEST ON TOP: a legitimately repetitive reply, such as
 * distinct batch rows that share long prefixes, can trip the window scan. A loop
 * re-emits the same few lines, so at most half of a looping reply's non-empty
 * lines are distinct. Requiring that keeps a correct answer from being cut.
 *
 * WHY 400 CHARACTERS AND NOT HERMES'S 16,000: Hermes stops its streams at that
 * length because its endpoints have no output cap. A phone reply is bounded by
 * the output budget far below 16,000 characters, so a threshold that high would
 * never fire before the budget ran out, which is the failure this exists to stop.
 */
object RepetitionGuard {
    /** Shorter text is never judged: a short answer repeats words by nature. */
    const val MIN_FRAGMENT_LENGTH = 400

    /** Exact-repeat window, in characters. */
    private const val REPEAT_WINDOW = 60

    /** A repeat seen fewer times than this is ordinary phrasing reuse. */
    private const val MIN_REPEAT_COUNT = 5

    /** The repeated part must cover at least this share of the text. */
    private const val DOMINANCE_RATIO = 0.5

    /** A loop has at most this share of distinct lines. */
    private const val DISTINCT_LINE_RATIO = 0.5

    /** Below this many lines a dominated text is a single-line loop, which is judged on the repeat alone. */
    private const val MIN_LINES_FOR_SHAPE = 5

    /** Evenly spaced anchors the periodic scan samples, which bounds its cost. */
    private const val MAX_ANCHOR_SAMPLES = 32

    /** Later occurrences of one anchor the periodic scan follows. */
    private const val MAX_ANCHOR_MATCHES = 8

    /** True when [text] is a repetition loop: its repeats dominate it and it has the shape of one. */
    fun isLooping(text: String): Boolean {
        if (!isDominated(text)) return false
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (lines.size < MIN_LINES_FOR_SHAPE) return true
        return lines.toSet().size <= lines.size * DISTINCT_LINE_RATIO
    }

    /** True when a repeated run covers at least half of [text]. Fail-open for short input. */
    private fun isDominated(text: String): Boolean {
        val n = text.length
        if (n < MIN_FRAGMENT_LENGTH) return false
        return lineDominated(text, n) || windowDominated(text, n) || periodicDominated(text, n)
    }

    /** One normalised line repeated enough to cover the text: the common echo shape. */
    private fun lineDominated(text: String, n: Int): Boolean {
        val counts = HashMap<String, Int>()
        for (line in text.lineSequence()) {
            val norm = line.trim()
            if (norm.isNotEmpty()) counts[norm] = (counts[norm] ?: 0) + 1
        }
        return counts.any { (line, c) ->
            c >= MIN_REPEAT_COUNT && c.toDouble() * line.length >= n * DOMINANCE_RATIO
        }
    }

    /**
     * One 60-character window that recurs often enough to cover half the text.
     * Catches loops whose repeats differ by a counter or a noise token.
     */
    private fun windowDominated(text: String, n: Int): Boolean {
        val needed = maxOf(MIN_REPEAT_COUNT, ceil(n * DOMINANCE_RATIO / REPEAT_WINDOW).toInt())
        val counts = HashMap<String, Int>()
        for (i in 0..n - REPEAT_WINDOW) {
            val key = text.substring(i, i + REPEAT_WINDOW)
            val c = (counts[key] ?: 0) + 1
            if (c >= needed) return true
            counts[key] = c
        }
        return false
    }

    /**
     * A long exact periodic unit whose 60-character windows each recur too rarely
     * for [windowDominated]. Anchors are sampled evenly; each anchor's later
     * occurrences give a candidate period, which is expanded to the full run so
     * coverage is measured over the whole repeating unit.
     */
    private fun periodicDominated(text: String, n: Int): Boolean {
        val maxStart = n - REPEAT_WINDOW
        if (maxStart < 1) return false
        val step = maxOf(1, (maxStart + MAX_ANCHOR_SAMPLES - 2) / (MAX_ANCHOR_SAMPLES - 1))
        val starts = ArrayList<Int>()
        var s = 0
        while (s <= maxStart) {
            starts += s
            s += step
        }
        if (starts.last() != maxStart) starts += maxStart

        // Runs already expanded and rejected, as (left, right, period). A later anchor inside one
        // of them whose period is a multiple of that run's period would walk the same run again.
        val rejected = ArrayList<Triple<Int, Int, Int>>()
        for (start in starts) {
            val anchor = text.substring(start, start + REPEAT_WINDOW)
            var searchFrom = start + 1
            for (k in 0 until MAX_ANCHOR_MATCHES) {
                val match = text.indexOf(anchor, searchFrom)
                if (match < 0) break
                val period = match - start
                searchFrom = match + 1
                if (rejected.any { (lo, hi, p) -> lo <= start && start < hi && period % p == 0 }) continue
                val (left, right) = expandRun(text, n, start, period)
                if (right - left >= MIN_REPEAT_COUNT * period && right - left >= n * DOMINANCE_RATIO) return true
                rejected += Triple(left, right, period)
            }
        }
        return false
    }

    /** Expands one known equal window to the `[left, right)` bounds of its exact periodic run. */
    private fun expandRun(text: String, n: Int, start: Int, period: Int): Pair<Int, Int> {
        var left = start
        while (left > 0 && text[left - 1] == text[left - 1 + period]) left--
        var right = start + REPEAT_WINDOW
        while (right + period < n && text[right] == text[right + period]) right++
        return left to right + period
    }
}

/**
 * [RepetitionGuard] applied while a reply streams, so a loop is cut as it forms
 * instead of after the output budget is spent.
 *
 * The first check runs once [RepetitionGuard.MIN_FRAGMENT_LENGTH] characters have
 * arrived, and the gap between checks doubles up to [TAIL_CHARS]. Each check reads
 * only the tail, so the work stays linear in the reply and the held text stays
 * bounded. This is Hermes's `RunawayStreamWatch` schedule with the phone threshold.
 */
class RepetitionWatch {
    private val tail = StringBuilder()
    private var chars = 0
    private var nextCheck = RepetitionGuard.MIN_FRAGMENT_LENGTH

    /** Adds one streamed piece. True once the reply has become a repetition loop. */
    fun feed(piece: String): Boolean {
        if (piece.isEmpty()) return false
        tail.append(piece)
        chars += piece.length
        if (chars < nextCheck) return false
        nextCheck = chars + minOf(chars, TAIL_CHARS)
        if (tail.length > TAIL_CHARS) tail.delete(0, tail.length - TAIL_CHARS)
        return RepetitionGuard.isLooping(tail.toString())
    }

    private companion object {
        /** The most text one check reads. A loop shows in a tail this size. */
        const val TAIL_CHARS = 4_000
    }
}
