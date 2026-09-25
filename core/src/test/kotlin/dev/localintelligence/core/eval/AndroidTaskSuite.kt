package dev.localintelligence.core.eval

import dev.localintelligence.core.tool.AgentTool
import dev.localintelligence.core.tool.LexicalToolSelector
import dev.localintelligence.core.tool.ToolSelector

// ===========================================================================
// AndroidTaskSuite.kt — the retrieval benchmark.
//
// THE QUESTION
// ============
//
// `docs/evals.md` asks: "How small can the model get before the agent loop
// stops being reliable?" The answer, for a phone agent, is decided long before
// the model runs. A 1-3B model reliably picks from 3-6 tools. If the shipped
// registry has 25 tools whose names, tags and descriptions overlap, the model is
// choosing from 25 and losing — and no amount of loop work fixes that.
//
// So this is not a model eval. There is no model here. This measures whether
// the tool set is SELECTABLE: for a realistic utterance, is the right tool
// inside the top 6 the real selector returns?
//
// The utterances are written the way people talk, not the way tools are named.
// "how much battery do I have" is not a tag; it is a sentence. The gap between
// the two is the entire measurement.
// ===========================================================================

/**
 * One retrieval case.
 *
 * [expected] is the tool the first agent step should call. [alsoExpected] are
 * tools the same utterance needs later; they must be visible too, because
 * `AgentController` re-selects every step and a tool that only becomes
 * relevant after an observation still has to be in the window.
 */
data class AndroidRetrievalCase(
    val id: String,
    val utterance: String,
    val expected: String,
    val alsoExpected: List<String> = emptyList(),
    /** Harder than the plain case: phrased indirectly, as a real user would. */
    val hard: Boolean = false,
) {
    /** Every tool the utterance needs across its steps. */
    val allExpected: Set<String> get() = (listOf(expected) + alsoExpected).toSet()
}

/** One case, scored against the real selector. */
data class RetrievalOutcome(
    val case: AndroidRetrievalCase,
    /** Tool names the selector returned, best first. */
    val selected: List<String>,
    /** 1-based position of [AndroidRetrievalCase.expected], or null if absent. */
    val rank: Int?,
    /** The expected tool's lexical score, replicated for diagnostics. */
    val expectedScore: Int,
    /** The winning score in this case. */
    val topScore: Int,
) {
    val hit: Boolean get() = rank != null
    val allExpectedVisible: Boolean get() = case.allExpected.all { it in selected }

    /**
     * A hit that only happened because the expected tool sorted first by name
     * among tools that scored nothing.
     *
     * Worth separating: with 25 tools and a 6-wide window, roughly a fifth of
     * the set is always in the top 6 by alphabetical accident. Counting those
     * as retrieval would make this benchmark report a number nobody earned.
     */
    val earned: Boolean get() = hit && expectedScore > 0
}

/** Aggregate over the whole benchmark. */
data class RetrievalReport(
    val outcomes: List<RetrievalOutcome>,
    val topN: Int,
) {
    val total: Int get() = outcomes.size
    val hits: Int get() = outcomes.count { it.hit }
    val earnedHits: Int get() = outcomes.count { it.earned }
    val misses: List<RetrievalOutcome> get() = outcomes.filterNot { it.hit }
    val weakHits: List<RetrievalOutcome> get() = outcomes.filter { it.hit && !it.earned }
    val fullyVisible: Int get() = outcomes.count { it.allExpectedVisible }

    /** The headline number. Below 85% the tool set is not shippable as-is. */
    val hitRate: Double get() = if (total == 0) 0.0 else hits.toDouble() / total
    val earnedRate: Double get() = if (total == 0) 0.0 else earnedHits.toDouble() / total

    fun forHardCases(): List<RetrievalOutcome> = outcomes.filter { it.case.hard }
    fun forEasyCases(): List<RetrievalOutcome> = outcomes.filterNot { it.case.hard }

    fun hitRateFor(predicate: (AndroidRetrievalCase) -> Boolean): Double {
        val subset = outcomes.filter { predicate(it.case) }
        return if (subset.isEmpty()) 0.0 else subset.count { it.hit }.toDouble() / subset.size
    }
}

// ---------------------------------------------------------------------------
// The utterances
// ---------------------------------------------------------------------------

object AndroidTaskSuite {

    /**
     * 36 utterances covering all 25 tools, 11 of them phrased the indirect way
     * people actually speak.
     *
     * Written against the tool set, not against the selector. Every case is a
     * thing a person could type into a phone assistant; none of them repeats a
     * tool name or a tag verbatim, because an utterance that contains the tag
     * tests nothing.
     */
    val cases: List<AndroidRetrievalCase> = listOf(
        // -- device ---------------------------------------------------------
        AndroidRetrievalCase(
            id = "device/battery-level",
            utterance = "how much battery do I have",
            expected = "device.battery",
        ),
        AndroidRetrievalCase(
            id = "device/battery-low",
            utterance = "is my phone about to die",
            expected = "device.battery",
            hard = true,
        ),
        AndroidRetrievalCase(
            id = "device/specs",
            utterance = "what phone am I running and how much storage is left",
            expected = "device.info",
        ),
        AndroidRetrievalCase(
            id = "device/vibrate",
            utterance = "can you make the phone buzz for a second",
            expected = "device.vibrate",
        ),
        AndroidRetrievalCase(
            id = "device/open-settings",
            utterance = "open the bluetooth settings",
            expected = "device.open_settings",
        ),

        // -- clipboard ------------------------------------------------------
        AndroidRetrievalCase(
            id = "clipboard/copy",
            utterance = "copy the tracking number to the clipboard",
            expected = "clipboard.write",
        ),
        AndroidRetrievalCase(
            id = "clipboard/read",
            utterance = "read my clipboard",
            expected = "clipboard.read",
        ),
        AndroidRetrievalCase(
            id = "clipboard/read-later",
            utterance = "what did I just copy",
            expected = "clipboard.read",
            hard = true,
        ),

        // -- alarm ----------------------------------------------------------
        AndroidRetrievalCase(
            id = "alarm/set",
            utterance = "set an alarm for 8:30",
            expected = "alarm.create",
        ),
        AndroidRetrievalCase(
            id = "alarm/wake-me",
            utterance = "wake me up at six on weekdays",
            expected = "alarm.create",
            hard = true,
        ),
        AndroidRetrievalCase(
            id = "alarm/list",
            utterance = "what alarms do I have set",
            expected = "alarm.list",
        ),
        AndroidRetrievalCase(
            id = "alarm/cancel",
            utterance = "delete the 22:00 alarm",
            expected = "alarm.cancel",
        ),

        // -- calendar -------------------------------------------------------
        AndroidRetrievalCase(
            id = "calendar/tomorrow",
            utterance = "what's on my calendar tomorrow",
            expected = "calendar.search",
        ),
        AndroidRetrievalCase(
            id = "calendar/afternoon",
            utterance = "do I have anything scheduled for this afternoon",
            expected = "calendar.search",
            hard = true,
        ),
        AndroidRetrievalCase(
            id = "calendar/book",
            utterance = "add a dentist appointment on friday at 10am",
            expected = "calendar.create",
        ),

        // -- contacts -------------------------------------------------------
        AndroidRetrievalCase(
            id = "contacts/find-and-copy",
            utterance = "find Dario's number and copy it",
            expected = "contacts.search",
            alsoExpected = listOf("clipboard.write"),
        ),
        AndroidRetrievalCase(
            id = "contacts/phone",
            utterance = "what's Bram's phone number",
            expected = "contacts.search",
        ),
        AndroidRetrievalCase(
            id = "contacts/record",
            utterance = "pull up the full contact record for Bram",
            expected = "contacts.get",
        ),

        // -- files ----------------------------------------------------------
        AndroidRetrievalCase(
            id = "files/list-folder",
            utterance = "what's in my Documents folder",
            expected = "files.list",
        ),
        AndroidRetrievalCase(
            id = "files/find-pdf",
            utterance = "find the pdf I downloaded yesterday and share it",
            expected = "files.search",
            alsoExpected = listOf("apps.share"),
        ),
        AndroidRetrievalCase(
            id = "files/search-name",
            utterance = "search for invoice.pdf",
            expected = "files.search",
        ),
        AndroidRetrievalCase(
            id = "files/where-is",
            utterance = "where did I save the tax document",
            expected = "files.search",
            hard = true,
        ),
        AndroidRetrievalCase(
            id = "files/read",
            utterance = "read what's inside notes.txt",
            expected = "files.read_text",
        ),
        AndroidRetrievalCase(
            id = "files/save",
            utterance = "save this shopping list to a file called groceries.txt",
            expected = "files.write_text",
        ),
        AndroidRetrievalCase(
            id = "files/delete",
            utterance = "get rid of /Documents/old-draft.pdf",
            expected = "files.delete",
        ),

        // -- apps -----------------------------------------------------------
        AndroidRetrievalCase(
            id = "apps/installed",
            utterance = "which apps do I have installed",
            expected = "apps.list",
        ),
        AndroidRetrievalCase(
            id = "apps/open",
            utterance = "open Spotify",
            expected = "apps.open",
        ),
        AndroidRetrievalCase(
            id = "apps/open-camera",
            utterance = "launch the camera",
            expected = "apps.open",
            hard = true,
        ),
        AndroidRetrievalCase(
            id = "apps/share",
            utterance = "share this text with my brother through Signal",
            expected = "apps.share",
        ),

        // -- notifications --------------------------------------------------
        AndroidRetrievalCase(
            id = "notifications/pending",
            utterance = "what notifications are waiting for me",
            expected = "notifications.list",
        ),
        AndroidRetrievalCase(
            id = "notifications/reply",
            utterance = "reply to Bram's message notification saying on my way",
            expected = "notifications.reply",
        ),
        AndroidRetrievalCase(
            id = "notifications/dismiss",
            utterance = "clear the notifications off my lock screen",
            expected = "notifications.dismiss",
        ),

        // -- web ------------------------------------------------------------
        AndroidRetrievalCase(
            id = "web/weather",
            utterance = "look up the weather and save it to a note",
            expected = "web.fetch",
            alsoExpected = listOf("files.write_text"),
        ),
        AndroidRetrievalCase(
            id = "web/news",
            utterance = "check the latest news on nos.nl",
            expected = "web.fetch",
        ),
    )

    /** Every tool the benchmark is supposed to reach. */
    val coveredTools: Set<String> get() = cases.flatMap { it.allExpected }.toSet()
}

// ---------------------------------------------------------------------------
// Running the benchmark
// ---------------------------------------------------------------------------

/**
 * Scores the benchmark against the REAL [LexicalToolSelector].
 *
 * The selector is injected so a test can prove the benchmark reports a miss when
 * a tool is genuinely unreachable — [LexicalToolSelector] is not a stub and
 * never will be; a benchmark that scored a fake selector would be measuring
 * fiction.
 */
class AndroidRetrievalBenchmark(
    private val selector: ToolSelector = LexicalToolSelector(),
    private val topN: Int = DEFAULT_TOP_N,
) {

    fun run(
        cases: List<AndroidRetrievalCase> = AndroidTaskSuite.cases,
        tools: List<AgentTool> = AndroidToolSet.build().registry.all(),
    ): RetrievalReport = RetrievalReport(
        outcomes = cases.map { case -> score(case, tools) },
        topN = topN,
    )

    private fun score(case: AndroidRetrievalCase, tools: List<AgentTool>): RetrievalOutcome {
        // No session keywords: the benchmark measures first-turn selection,
        // which is the worst case. A keyword-carrying second turn can only be
        // easier, and a benchmark that flattered itself with history would not
        // be measuring what ships.
        val selected = selector.select(case.utterance, emptyList(), tools, topN)
        val names = selected.map { it.definition.name }
        val scores = tools.associate { it.definition.name to LexicalScore.of(case.utterance, it) }
        val expectedScore = scores[case.expected] ?: 0
        val topScore = names.firstNotNullOfOrNull { scores[it] ?: 0 } ?: 0
        return RetrievalOutcome(
            case = case,
            selected = names,
            rank = names.indexOf(case.expected).takeIf { it >= 0 }?.plus(1),
            expectedScore = expectedScore,
            topScore = topScore,
        )
    }

    companion object {
        /** `AgentConfig.maxVisibleTools` is 6; a 3B model's reliable window. */
        const val DEFAULT_TOP_N = 6
    }
}

/**
 * A faithful re-implementation of `LexicalToolSelector`'s scoring, for
 * diagnostics only.
 *
 * [AndroidRetrievalBenchmark] ranks with the real selector. This exists to
 * answer "why did it miss" — the score tells the tool author which token the
 * retriever actually saw. `AndroidEvalTest` proves the replication ranks
 * identically to the real selector on every case, so it cannot drift into
 * telling a plausible lie.
 */
object LexicalScore {

    fun of(utterance: String, tool: AgentTool): Int {
        val def = tool.definition
        val taskTokens = tokens(utterance).toSet()
        val nameTokens = tokens(def.name).toSet()
        val descTokens = tokens(def.description).toSet()
        val tagTokens = def.tags.flatMap { tokens(it) }.toSet()

        val overlap = taskTokens.intersect(descTokens).size * 2 +
            taskTokens.intersect(tagTokens).size * 3 +
            taskTokens.intersect(nameTokens).size * 4

        val substringHit = if (utterance.contains(def.name, ignoreCase = true)) 10 else 0
        return overlap + substringHit
    }

    /** The same tokenizer the real selector uses, including the 2-char drop. */
    fun tokens(text: String): List<String> =
        text.lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length > 2 }
}
