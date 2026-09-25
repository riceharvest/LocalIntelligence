package dev.localintelligence.core.tool.catalogue

import dev.localintelligence.core.tool.LexicalToolSelector
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The retrieval smoke test: does the catalogue actually let the right tool be found?
 *
 * Every other test in this package checks that the catalogue is *well formed*. This
 * one checks that it *works*, and it is the test the whole package exists for.
 *
 * ## It scores with the shipped selector
 *
 * The scorer is [LexicalToolSelector] — the same class `AgentController` calls at
 * runtime, unmodified. The catalogue definitions are wrapped in [DefinitionOnlyTool]
 * so it has something to score. A bespoke scorer written in a test would only prove
 * the catalogue agrees with that test's idea of relevance; this proves it agrees with
 * the code that ships.
 *
 * ## The task set
 *
 * 7 utterances are verbatim from the "Target tasks" list in `docs/architecture.md` §1.
 * The rest are the real user phrasings already in this repo's eval suite
 * (`core/src/test/.../eval/TaskSuite.kt`) and the doc examples in `docs/evals.md`,
 * re-pointed at the canonical tool names. Together they exercise all 26 tools: a tool
 * no example task can retrieve is a tool whose tags are guesswork.
 *
 * ## The assertion
 *
 * For each utterance, the expected tool must be in the top [VISIBLE_SLOTS] candidates.
 * Six is the top of the band `docs/architecture.md` §11 specifies (3-6 tools, hard
 * maximum 8), and every multi-tool task in the set also asserts its second tool lands
 * in the same six — because a chain whose second step is not visible is a chain the
 * model cannot take.
 */
class V0ToolCatalogueRetrievalTest {

    private data class Case(
        val id: String,
        val utterance: String,
        /** The tool that must be selected, ranked. */
        val expectedInOrder: List<String>,
        val source: String,
    )

    private val cases: List<Case> = listOf(
        // -- verbatim from docs/architecture.md section 1, "Target tasks"
        Case(
            "arch-calendar-tomorrow",
            "What's on my calendar tomorrow?",
            listOf("calendar.search"),
            "architecture.md target task",
        ),
        Case(
            "arch-contact-copy-number",
            "Find Dario in my contacts and copy his number.",
            listOf("contacts.search", "clipboard.write"),
            "architecture.md target task",
        ),
        Case(
            "arch-find-pdf-share",
            "Find the PDF I downloaded yesterday and share it.",
            listOf("files.search", "apps.share"),
            "architecture.md target task",
        ),
        Case(
            "arch-set-alarm",
            "Set an alarm for 8:30.",
            listOf("alarm.create"),
            "architecture.md target task",
        ),
        Case(
            "arch-battery-before-leaving",
            "Check battery level and tell me whether I should charge before leaving.",
            listOf("device.battery"),
            "architecture.md target task",
        ),
        Case(
            "arch-web-then-note",
            "Look up X on the web and save the answer to a note.",
            listOf("web.fetch", "files.write_text"),
            "architecture.md target task",
        ),

        // -- real user phrasings from the repo's own eval suite / evals.md
        Case(
            "battery-level",
            "What's my battery level?",
            listOf("device.battery"),
            "TaskSuite single-tool",
        ),
        Case(
            "which-phone",
            "Which phone am I holding?",
            listOf("device.info"),
            "TaskSuite single-tool",
        ),
        Case(
            "what-alarms",
            "What alarms do I have set?",
            listOf("alarm.list"),
            "TaskSuite single-tool",
        ),
        Case(
            "copy-text",
            "Copy the text \"meet me at the bridge at six\" to my clipboard.",
            listOf("clipboard.write"),
            "TaskSuite single-tool",
        ),
        Case(
            "what-is-on-clipboard",
            "What's on my clipboard?",
            listOf("clipboard.read"),
            "author phrasing",
        ),
        Case(
            "find-bram-copy",
            "Find Bram's phone number and copy it to my clipboard.",
            listOf("contacts.search", "clipboard.write"),
            "TaskSuite two-tool",
        ),
        Case(
            "call-dave",
            "Call Dave.",
            listOf("contacts.search"),
            "TaskSuite ambiguity",
        ),
        Case(
            "get-rid-of-alarm",
            "Get rid of my old alarm.",
            listOf("alarm.cancel"),
            "TaskSuite ambiguity",
        ),
        Case(
            "find-note-then-alarm",
            "Set an alarm for the time mentioned in my note about the standup.",
            listOf("alarm.create", "files.search"),
            "TaskSuite two-tool",
        ),
        Case(
            "block-out-deep-work",
            "Check tomorrow's schedule, then block out 16:00 to 17:00 for deep work.",
            listOf("calendar.search", "calendar.create"),
            "TaskSuite multi-step",
        ),
        Case(
            "next-appointment-with-alice",
            "What is my next appointment with Alice?",
            listOf("calendar.search"),
            "docs/evals.md example",
        ),
        Case(
            "share-report",
            "Find the quarterly report and share it with my team chat.",
            listOf("files.search", "apps.share"),
            "TaskSuite two-tool",
        ),
        Case(
            "send-me-the-report",
            "Send me the quarterly report.",
            listOf("files.search", "apps.share"),
            "TaskSuite ambiguity",
        ),
        Case(
            "list-downloads",
            "List everything in my Downloads folder.",
            listOf("files.list"),
            "TaskSuite failure",
        ),
        Case(
            "delete-old-invoice",
            "Delete the old invoice PDF.",
            listOf("files.delete"),
            "author phrasing",
        ),
        Case(
            "summarise-the-file",
            "Read this file and tell me what it says.",
            listOf("files.read_text"),
            "author phrasing",
        ),
        Case(
            "open-maps",
            "Open Maps.",
            listOf("apps.open"),
            "author phrasing",
        ),
        Case(
            "what-apps",
            "What apps do I have installed?",
            listOf("apps.list"),
            "author phrasing",
        ),
        Case(
            "open-bluetooth-settings",
            "Turn on bluetooth for me.",
            listOf("device.open_settings"),
            "author phrasing",
        ),
        Case(
            "buzz-the-phone",
            "Buzz the phone, I can't find it.",
            listOf("device.vibrate"),
            "author phrasing",
        ),
        Case(
            "what-came-in",
            "What messages did I miss?",
            listOf("notifications.list"),
            "author phrasing",
        ),
        Case(
            "clear-that-notification",
            "Clear that WhatsApp notification.",
            listOf("notifications.dismiss", "notifications.list"),
            "author phrasing",
        ),
        Case(
            "text-back",
            "Reply to the WhatsApp notification: on my way.",
            listOf("notifications.reply", "notifications.list"),
            "author phrasing: reply is keyed on a key only list returns",
        ),
        Case(
            "weather-online",
            "What's the weather in Oslo right now?",
            listOf("web.fetch"),
            "TaskSuite impossible",
        ),
    )

    private val tools = catalogueAsTools()
    private val selector = LexicalToolSelector()

    @Test
    fun `every expected tool in every example task is retrievable in the visible set`() {
        val failures = mutableListOf<String>()
        val report = mutableListOf<String>()

        for (case in cases) {
            val visible = selector.select(case.utterance, emptyList(), tools, VISIBLE_SLOTS)
                .map { it.definition.name }
            val ranked = case.expectedInOrder.map { expected ->
                val rank = visible.indexOf(expected)
                Triple(expected, rank, rank >= 0)
            }
            ranked.forEach { (name, rank, ok) ->
                if (!ok) {
                    failures += "${case.id} (\"${case.utterance}\"): $name not in $visible"
                }
            }
            val rankText = ranked.joinToString(", ") { (name, rank, _) ->
                if (rank >= 0) "$name#$rank" else "$name=MISSING"
            }
            report += "  %-26s %-56s -> %s".format(case.id, "\"${case.utterance}\"", rankText)
        }

                println(
            "retrieval smoke test, top $VISIBLE_SLOTS of ${tools.size} tools:\n" +
                report.joinToString("\n"),
        )
        assertTrue(
            "retrieval failures:\n" + failures.joinToString("\n"),
            failures.isEmpty(),
        )
    }

    @Test
    fun `the expected tool is ranked first for every single-tool task`() {
        // Only for one-tool tasks. For a two-tool task the lexical scorer is not a
        // planner: "find Bram's number and copy it" ties contacts.search and
        // clipboard.write, and alphabetical tiebreak is an acceptable outcome because
        // both are in the same six slots and the model picks between them. Asserting
        // an order there would be asserting that lexical scoring is discourse-ordered.
        val failures = cases.filter { it.expectedInOrder.size == 1 }.mapNotNull { case ->
            val visible = selector.select(case.utterance, emptyList(), tools, VISIBLE_SLOTS)
                .map { it.definition.name }
            val expected = case.expectedInOrder.first()
            if (visible.firstOrNull() != expected) {
                "${case.id}: expected $expected first, got $visible"
            } else {
                null
            }
        }
        assertTrue(
            "first-choice retrieval failures:\n" + failures.joinToString("\n"),
            failures.isEmpty(),
        )
    }

    @Test
    fun `every multi-tool task puts both of its tools in the same visible set`() {
        val failures = cases.filter { it.expectedInOrder.size > 1 }.mapNotNull { case ->
            val visible = selector.select(case.utterance, emptyList(), tools, VISIBLE_SLOTS)
                .map { it.definition.name }
            val missing = case.expectedInOrder.filterNot { it in visible }
            if (missing.isNotEmpty()) "${case.id}: missing $missing from $visible" else null
        }
        assertTrue(
            "a chain whose later step is not in the same six slots is a chain the model " +
                "cannot take:\n" + failures.joinToString("\n"),
            failures.isEmpty(),
        )
    }

    @Test
    fun `the selector is not trivially returning everything`() {
        val visible = selector.select("Check battery level.", emptyList(), tools, VISIBLE_SLOTS)
        assertTrue(
            "selector returned ${visible.size} tools, the smoke test proves nothing if " +
                "it is not actually filtering",
            visible.size == VISIBLE_SLOTS,
        )
        assertTrue(
            "battery is not the top pick for a battery task: " +
                visible.map { it.definition.name },
            visible.first().definition.name == "device.battery",
        )
    }

    @Test
    fun `every tool in the catalogue is retrievable by at least one example task`() {
        val reachable = mutableSetOf<String>()
        for (case in cases) {
            selector.select(case.utterance, emptyList(), tools, VISIBLE_SLOTS)
                .forEach { reachable += it.definition.name }
        }
        val unreachable = V0ToolCatalogue.names().toSet() - reachable
        assertTrue(
            "no example task ever surfaces ${unreachable.joinToString()}. A tool nobody " +
                "can reach is dead weight in the retrieval race; add an example task or " +
                "cut the tool.",
            unreachable.isEmpty(),
        )
    }

    @Test
    fun `retrieval is stable across a wide candidate window`() {
        // The runtime may hand the selector fewer or more tools depending on what
        // else is registered. Ranking must not depend on the window size, or the same
        // utterance retrieves differently depending on unrelated code.
        val narrow = selector.select("Set an alarm for 8:30.", emptyList(), tools, 3)
            .map { it.definition.name }
        val wide = selector.select("Set an alarm for 8:30.", emptyList(), tools, 12)
            .map { it.definition.name }
        assertTrue(
            "alarm.create should be first at any window size: narrow=$narrow wide=$wide",
            narrow.first() == "alarm.create" && wide.first() == "alarm.create",
        )
    }

    @Test
    fun `the catalogued descriptions carry the retrieval weight, not just the tags`() {
        // If a tool only ever wins because of its tags, the description is dead weight
        // in the prompt and a good candidate for the token budget. Score the same
        // utterance with and without tags and report the delta.
        val bare = V0ToolCatalogue.definitions.map {
            DefinitionOnlyTool(it.copy(tags = emptySet()))
        }
        val rows = V0ToolCatalogue.definitions.map { def ->
            val withTags = selector.select(exampleFor(def.name), emptyList(), tools, VISIBLE_SLOTS)
                .map { it.definition.name }
            val without = selector.select(exampleFor(def.name), emptyList(), bare, VISIBLE_SLOTS)
                .map { it.definition.name }
            val rankWith = withTags.indexOf(def.name)
            val rankWithout = without.indexOf(def.name)
            Triple(def.name, rankWith, rankWithout)
        }
        println(
            "rank with tags -> rank with descriptions only:\n" +
                rows.joinToString("\n") {
                    "  %-24s %s".format(it.first, "${it.second} -> ${it.third}")
                },
        )
        val descriptionsAlone = rows.count { it.third >= 0 }
        assertTrue(
            "only $descriptionsAlone of ${rows.size} tools are retrievable from name + " +
                "description alone. The budget only pays for itself if the prose works.",
            descriptionsAlone >= rows.size * 2 / 3,
        )
    }

    private fun exampleFor(toolName: String): String =
        cases.firstOrNull { it.expectedInOrder.contains(toolName) }?.utterance
            ?: "how do i use $toolName"

    companion object {
        /** Top of the 3-6 band from docs/architecture.md section 11. */
        const val VISIBLE_SLOTS = 6
    }
}
