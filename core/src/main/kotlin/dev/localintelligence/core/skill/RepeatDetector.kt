package dev.localintelligence.core.skill

import dev.localintelligence.core.tool.lexicalTokens

/** One answered request and the tool calls that answered it, in order. [success] is true when every call succeeded. */
data class RepeatRun(val request: String, val tools: List<String>, val success: Boolean)

/** A request the user keeps making the same way. [key] names it for the "not now" record. */
data class RepeatCandidate(val request: String, val tools: List<String>, val key: String)

/**
 * Finds a request the user has made the same way [REQUESTS] times, so the app can offer one line:
 * "Save this as a skill?". No model call: the match is lexical, and the same tools in the same
 * set, each run successful.
 *
 * WHY THE CUTOFFS: a request counts as the same when at least half of its meaningful words are
 * shared (Jaccard), so "set a timer for 5 minutes" and "set a timer for 10 minutes" match. Stop
 * words are dropped first, or every request would match every other on "the" and "a".
 */
object RepeatDetector {
    const val REQUESTS = 3
    const val SIMILARITY = 0.5

    /** Same minimum as the existing "save as skill" draft: one tool is a lookup, not a procedure. */
    const val MIN_TOOLS = 2

    /**
     * The candidate for the newest run in [runs], or null. The newest run must be successful and
     * use at least [MIN_TOOLS] distinct tools; the earlier runs that match it count toward [REQUESTS].
     */
    fun candidate(runs: List<RepeatRun>): RepeatCandidate? {
        val last = runs.lastOrNull() ?: return null
        val tools = last.tools.distinct()
        if (!last.success || tools.size < MIN_TOOLS) return null
        val words = content(last.request)
        if (words.isEmpty()) return null
        val toolSet = tools.toSet()
        val same = runs.count { run ->
            run.success && run.tools.toSet() == toolSet && jaccard(content(run.request), words) >= SIMILARITY
        }
        if (same < REQUESTS) return null
        return RepeatCandidate(last.request, tools, keyFor(words, toolSet))
    }

    /** A stable name for a request and its tools: the same request, however it is worded, gets the same key. */
    fun keyFor(words: Set<String>, tools: Set<String>): String {
        val text = words.sorted().joinToString(" ") + "|" + tools.sorted().joinToString(" ")
        return text.hashCode().toUInt().toString(16)
    }

    private fun content(text: String): Set<String> =
        lexicalTokens(text).filterNot { it in STOP_WORDS }.toSet()

    private fun jaccard(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val common = a.intersect(b).size.toDouble()
        return common / a.union(b).size
    }

    private val STOP_WORDS = setOf(
        "the", "and", "for", "with", "that", "this", "from", "into", "you", "your", "me", "my", "can",
        "could", "please", "what", "how", "are", "was", "will", "would", "should", "about", "just",
        "its", "not", "but", "all", "any", "some", "then", "there", "have", "has", "had", "make",
    )
}
