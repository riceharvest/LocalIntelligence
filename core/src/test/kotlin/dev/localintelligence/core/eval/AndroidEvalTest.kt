package dev.localintelligence.core.eval

import dev.localintelligence.core.context.SystemPrompts
import dev.localintelligence.core.tool.AgentTool
import dev.localintelligence.core.tool.LexicalToolSelector
import dev.localintelligence.core.tool.ToolRisk
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Android tool-coherence and retrieval benchmark, as CI tests.
 *
 * These are the tests that answer the question the 50-task suite structurally
 * cannot: not "does the loop work" but "is the tool set usable by a small
 * model". A perfectly functioning loop over 25 badly-named, badly-tagged tools
 * is a phone assistant that answers the wrong question confidently.
 *
 * Every assertion here is a claim about the real tool set, checked against the
 * real `LexicalToolSelector` and the real `SystemPrompts`. Nothing is stubbed
 * except the tools themselves, which cannot be otherwise: `:android` does not
 * exist on this branch, and `:core` may not depend on it.
 */
class AndroidEvalTest {

    // =======================================================================
    // 1. Coherence — structure
    // =======================================================================

    @Test
    fun `every tool name is unique and well formed`() {
        val names = AndroidToolStubs.specs.map { it.name }
        val duplicates = names.groupBy { it }.filterValues { it.size > 1 }.keys
        assertTrue("duplicate tool names: $duplicates", duplicates.isEmpty())
        assertEquals(names.size, names.toSet().size)

        val malformed = names.filterNot { AndroidToolCoherence.NAME_PATTERN.matches(it) }
        assertTrue("malformed tool names: $malformed", malformed.isEmpty())
    }

    @Test
    fun `the registry builds and resolves every declared tool`() {
        val toolSet = AndroidToolSet.build()
        assertEquals(AndroidToolStubs.specs.size, toolSet.size)
        AndroidToolStubs.specs.forEach { spec ->
            assertNotNull(
                "registry cannot resolve ${spec.name}",
                toolSet.registry.byName(spec.name),
            )
        }
    }

    @Test
    fun `every category is known and matches the name namespace`() {
        val report = AndroidToolCoherence.check(AndroidToolSet.build().definitions)
        val problems = report.violations.filter {
            it.rule == "category-known" || it.rule == "category-namespace"
        }
        assertTrue("category problems:\n" + problems.joinToString("\n") { it.render() }, problems.isEmpty())
    }

    @Test
    fun `every tool declares 4 to 8 lowercase tags`() {
        val report = AndroidToolCoherence.check(AndroidToolSet.build().definitions)
        val problems = report.violations.filter { it.rule == "tag-count" || it.rule == "tag-lowercase" }
        assertTrue("tag problems:\n" + problems.joinToString("\n") { it.render() }, problems.isEmpty())
    }

    @Test
    fun `every description is one verb-first sentence under the budget`() {
        val report = AndroidToolCoherence.check(AndroidToolSet.build().definitions)
        val problems = report.violations.filter {
            it.rule == "description-sentence" || it.rule == "description-verb" ||
                it.rule == "description-length"
        }
        assertTrue("description problems:\n" + problems.joinToString("\n") { it.render() }, problems.isEmpty())
    }

    // =======================================================================
    // 2. Coherence — schemas
    // =======================================================================

    @Test
    fun `every schema is a well formed object schema`() {
        val definitions = AndroidToolSet.build().definitions
        val problems = definitions.flatMap { def ->
            AndroidToolCoherence.checkOne(def).filter { it.rule.startsWith("schema") }
        }
        assertTrue(
            "schema problems:\n" + problems.joinToString("\n") { it.render() },
            problems.isEmpty(),
        )
    }

    @Test
    fun `every schema declares properties and required, even when empty`() {
        // A schema missing `properties` makes ToolCallValidator reject every
        // argument of every call, and a missing `required` makes the grammar
        // builder guess whether an argument is optional. Both must be present.
        AndroidToolSet.build().definitions.forEach { def ->
            assertTrue("${def.name} has no properties", def.schema.containsKey("properties"))
            assertTrue("${def.name} has no required", def.schema.containsKey("required"))
        }
    }

    @Test
    fun `every required argument is declared as a property`() {
        AndroidToolStubs.specs.forEach { spec ->
            val properties = AndroidToolStubs.schemaOf(spec)["properties"]!!
                .let { it as kotlinx.serialization.json.JsonObject }.keys
            spec.required.forEach { required ->
                assertTrue(
                    "${spec.name} requires \"$required\", which is not a property",
                    required in properties,
                )
            }
        }
    }

    @Test
    fun `every declared argument is used by at least one retrieval case`() {
        // A tool with arguments no case ever supplies is either untested or
        // unreachable, and either way the schema is decoration.
        val unused = AndroidToolStubs.specs.filter { spec ->
            spec.args.isNotEmpty() && spec.name !in AndroidTaskSuite.coveredTools
        }.map { it.name }
        assertTrue("no retrieval case covers: $unused", unused.isEmpty())
    }

    // =======================================================================
    // 3. Coherence — risk
    // =======================================================================

    @Test
    fun `no tool is classified PRIVILEGED`() {
        // PRIVILEGED is refused by AgentController in v0, so such a tool is
        // dead code that still occupies a slot in the model's prompt.
        val privileged = AndroidToolSet.build().definitions
            .filter { it.risk == ToolRisk.PRIVILEGED }
            .map { it.name }
        assertTrue("PRIVILEGED tools are disabled in v0: $privileged", privileged.isEmpty())
    }

    @Test
    fun `destructive and external tools describe their consequence`() {
        val risky = AndroidToolSet.build().definitions
            .filter { it.risk == ToolRisk.DESTRUCTIVE || it.risk == ToolRisk.EXTERNAL_COMMUNICATION }
        assertTrue("no risky tools at all — the risk enum is untested", risky.isNotEmpty())

        val vague = risky.filter { def ->
            val lower = def.description.lowercase()
            val cues = if (def.risk == ToolRisk.DESTRUCTIVE) {
                AndroidToolCoherence.DESTRUCTIVE_CONSEQUENCES
            } else {
                AndroidToolCoherence.EXTERNAL_CONSEQUENCES
            }
            cues.none { it in lower }
        }.map { it.name }
        assertTrue(
            "risky tools whose description omits the consequence: $vague",
            vague.isEmpty(),
        )
    }

    @Test
    fun `every risky tool is one the runtime would actually gate`() {
        // Sanity on the risk enum itself: requiresConfirmation is derived, so
        // these two classes are exactly the gated set.
        val gated = AndroidToolStubs.specs.filter { it.risk.requiresConfirmation }.map { it.name }
        assertTrue("expected a gated set to exist", gated.isNotEmpty())
        assertTrue(
            "gated tools must be DESTRUCTIVE or EXTERNAL_COMMUNICATION",
            gated.all { it in riskyToolNames() },
        )
    }

    // =======================================================================
    // 4. Retrieval — the headline number
    // =======================================================================

    @Test
    fun `retrieval hit rate clears the floor`() {
        val report = AndroidRetrievalBenchmark().run()

        // Printed unconditionally: on a pass the numbers are the deliverable,
        // and on a fail they are the diagnosis.
        println(AndroidEvalReportRenderer.render(AndroidEvalReport.build()))

        val detail = report.misses.joinToString("\n") { miss ->
            "  \"${miss.case.utterance}\"\n" +
                "      expected ${miss.case.expected} (score ${miss.expectedScore}) " +
                "was not in the top-${report.topN}\n" +
                "      retrieved: ${miss.selected.joinToString(", ")}"
        }

        assertTrue(
            "retrieval hit rate ${"%.1f".format(report.hitRate * 100)}% is below the " +
                "floor ${"%.0f".format(AndroidEvalReport.MIN_HIT_RATE * 100)}%.\n" +
                "Each miss is a missing tag on a tool, not a selector bug:\n$detail",
            report.hitRate >= AndroidEvalReport.MIN_HIT_RATE,
        )
    }

    @Test
    fun `the benchmark covers every tool in the registry`() {
        val covered = AndroidTaskSuite.coveredTools
        val orphans = AndroidToolStubs.specs.map { it.name }.filterNot { it in covered }
        assertTrue("tools with no retrieval case: $orphans", orphans.isEmpty())
    }

    @Test
    fun `no case is reverse-engineered from the tool's own name or description`() {
        // A case must be written the way a person speaks, not the way the tool
        // is declared. Two cheats would inflate the headline number:
        //   - the utterance contains the tool's NAME ("use files.search"), which
        //     earns a flat +10 substring bonus in the real selector;
        //   - the utterance is the description restated, or carries nearly every
        //     one of the tool's tags, which means the case was written by
        //     reading the declaration rather than imagining a user.
        //
        // Note what is deliberately NOT a cheat: sharing ONE keyword. "read my
        // clipboard" containing the tag "clipboard" is the retrieval working as
        // designed, and forbidding it would empty the benchmark of easy cases
        // and flatter the hard ones.
        val cheats = AndroidTaskSuite.cases.mapNotNull { case ->
            val spec = AndroidToolStubs.specFor(case.expected)
            val tokens = LexicalScore.tokens(case.utterance)
            val descTokens = LexicalScore.tokens(spec.description)
            val tagTokens = spec.tags.flatMap { LexicalScore.tokens(it) }.toSet()

            // The literal dotted name is the only thing that earns the flat +10
            // substring bonus, so it is the only name-shaped leak that matters.
            // Overlapping the name's halves token-wise is not a leak at all:
            // "read my clipboard" contains both words of clipboard.read, and
            // demanding a paraphrase there would empty the benchmark of the most
            // natural utterances in it.
            val nameLeak = case.utterance.contains(spec.name, ignoreCase = true)
            val descLeak = descTokens.count { it in tokens } >= descTokens.size - 1
            val tagLeak = tagTokens.size >= 4 && tagTokens.count { it in tokens } >= tagTokens.size - 1

            if (nameLeak || descLeak || tagLeak) {
                "${case.id}: \"${case.utterance}\" leaks ${spec.name} " +
                    "(name=$nameLeak description=$descLeak tags=$tagLeak)"
            } else {
                null
            }
        }
        assertTrue(
            "these cases are written from the declaration, not from a user:\n" +
                cheats.joinToString("\n"),
            cheats.isEmpty(),
        )
    }

    @Test
    fun `the score replication ranks identically to the real selector`() {
        // LexicalScore exists to explain misses. If it ever disagrees with the
        // real selector it would explain them wrongly, which is worse than not
        // explaining them at all.
        val tools = AndroidToolSet.build().registry.all()
        val selector = LexicalToolSelector()

        // Must be strictly fewer than the tool count: `LexicalToolSelector`
        // short-circuits with `if (available.size <= maxTools) return available`,
        // returning registry order unsorted. Asking for all 25 would compare
        // registration order against a scored ranking and "fail" for the wrong
        // reason — the bug this test exists to catch would mask itself.
        val topN = tools.size - 1
        assertTrue("need at least 3 tools to compare rankings", topN >= 3)

        AndroidTaskSuite.cases.forEach { case ->
            val real = selector.select(case.utterance, emptyList(), tools, topN)
                .map { it.definition.name }
            val replicated = tools
                .map { it to LexicalScore.of(case.utterance, it) }
                .sortedWith(
                    compareByDescending<Pair<AgentTool, Int>> { it.second }
                        .thenBy { it.first.definition.name },
                )
                .take(topN) // the real selector truncates; the replication must too
                .map { it.first.definition.name }

            assertEquals(
                "replication diverged on \"${case.utterance}\"",
                real, replicated,
            )
        }
    }

    /**
     * The generalization probe: 22 utterances that were never used to choose a tag.
     *
     * The 34 cases in [AndroidTaskSuite] were used to FIND the tag defects, and
     * the stubs were corrected until every one passed. A benchmark tuned against
     * its own cases measures nothing, so this set exists to measure something
     * else: whether the fixes generalise to phrases nobody wrote them for.
     *
     * These were written deliberately obliquely — "nuke the screenshot", "jot
     * down that the code is 1234", "what did I paste" — because that is how
     * people talk to a phone assistant, and because a retriever that only works
     * when the user uses the tool's own vocabulary is not a retriever, it is a
     * lookup table.
     *
     * [HELD_OUT_FLOOR] is a REGRESSION BASELINE, not a target. The measured
     * value is 10/22 = 45.5%. Fixing the tuned suite to 100% moved this number
     * by a few points, not to parity, and that gap is the finding: a lexical
     * retriever over 25 tools tops out near half on natural phrasing, because
     * its vocabulary is the tool authors' vocabulary and not the user's.
     * Chasing it with more tags is a losing game — see the PR for the numbers.
     */
    /** Delegates to the probe in AndroidEvalReport.kt so the report and the test
     *  can never score different sets. */
    private val heldOut: List<AndroidRetrievalCase> get() = AndroidGeneralizationProbe.cases

    @Test
    fun `generalization probe reports the true hit rate on unseen phrasing`() {
        val report = AndroidRetrievalBenchmark().run(
            heldOut, AndroidToolSet.build().registry.all(),
        )
        println(
            "GENERALIZATION PROBE: ${report.hits}/${report.total} = " +
                "${"%.1f".format(report.hitRate * 100)}% on utterances never used to pick a tag"
        )
        // Deliberately NOT gated on hit rate. See HELD_OUT_FLOOR: the number is
        // a measurement, and every miss in it is a documented finding rather
        // than a defect to be tuned away by adding the words this probe happens
        // to use. What IS asserted is the floor, so the tool set cannot get
        // WORSE than the baseline without someone noticing.
        assertTrue(
            "generalization probe fell below its ${"%.0f".format(HELD_OUT_FLOOR * 100)}% " +
                "regression baseline: ${report.hits}/${report.total}. Misses:\n" +
                report.misses.joinToString("\n") { miss ->
                    "  \"${miss.case.utterance}\" -> ${miss.case.expected} " +
                        "(score ${miss.expectedScore}), got ${miss.selected.take(4)}"
                },
            report.hitRate >= HELD_OUT_FLOOR,
        )
    }

    @Test
    fun `the probe cases are not a subset of the tuning cases`() {
        val tuned = AndroidTaskSuite.cases.map { it.utterance.lowercase() }.toSet()
        val reused = heldOut.filter { it.utterance.lowercase() in tuned }
        assertTrue("probe cases reused from the tuning set: $reused", reused.isEmpty())
        heldOut.forEach { case ->
            assertFalse(
                "probe case ${case.id} repeats its tool's name",
                case.utterance.contains(case.expected, ignoreCase = true),
            )
        }
    }

    @Test
    fun `the probe covers every tool in the registry`() {
        val covered = heldOut.map { it.expected }.toSet()
        val orphans = AndroidToolStubs.specs.map { it.name }.filterNot { it in covered }
        assertTrue("tools with no probe case: $orphans", orphans.isEmpty())
    }

    // =======================================================================
    // 5. Overlap — confusable pairs
    // =======================================================================

    @Test
    fun `overlap analysis finds pairs and ranks them worst first`() {
        val overlaps = AndroidOverlapAnalyzer.analyze()
        assertTrue("no overlapping pairs at all — the analysis is broken", overlaps.isNotEmpty())
        // Sorted descending, so the worst offender is the first line of the
        // report and nobody has to sort it themselves.
        val scores = overlaps.map { it.score }
        assertEquals("overlaps must be sorted worst first", scores.sortedDescending(), scores)
    }

    @Test
    fun `no confusable pair is left unreported`() {
        // The threshold is a reporting line, not a licence to ship. If a pair
        // is genuinely indistinguishable to a retriever it has to be fixed, so
        // this asserts the count is small enough to actually fix rather than
        // asserting it is zero — a set this size will always have a near-tie.
        val confusable = AndroidOverlapAnalyzer.confusable()
        val text = confusable.joinToString("\n") { it.render() }
        assertTrue(
            "too many confusable pairs to fix individually (${confusable.size}):\n$text",
            confusable.size <= MAX_CONFUSABLE_PAIRS,
        )
    }

    @Test
    fun `the two clipboard tools are not interchangeable`() {
        // read and write over the same buffer, with the same nouns. This is the
        // canonical confusable pair in the set and the reason the overlap
        // report exists.
        val pair = AndroidOverlapAnalyzer.analyze().firstOrNull {
            setOf(it.a, it.b) == setOf("clipboard.read", "clipboard.write")
        }
        assertNotNull("clipboard pair not analysed at all", pair)
        assertTrue(
            "clipboard.read and clipboard.write overlap ${pair!!.score} — " +
                "shared: ${pair.sharedTokens()}",
            pair.score < AndroidOverlapAnalyzer.CONFUSABLE_THRESHOLD,
        )
    }

    // =======================================================================
    // 6. Budget — what the prompt costs
    // =======================================================================

    @Test
    fun `the full tool set fits the prompt budget`() {
        val budget = PromptBudget.measure()
        println("PROMPT BUDGET\n" + budget.render())
        assertTrue(
            "the full tool set renders to ${budget.fullPromptChars} chars, over the " +
                "FULL_SET_BUDGET_CHARS ceiling",
            budget.fullPromptChars <= FULL_SET_BUDGET_CHARS,
        )
    }

    @Test
    fun `selecting a typical utterance costs far less than the full set`() {
        val budget = PromptBudget.measure()
        assertTrue(
            "the typical selection (${budget.typicalSelectionChars} chars) must be " +
                "well under the full set (${budget.fullPromptChars} chars), or the " +
                "selector is not earning its place",
            budget.typicalSelectionChars * 2 < budget.fullPromptChars,
        )
    }

    @Test
    fun `a typical selection really is six tools or fewer`() {
        val budget = PromptBudget.measure()
        assertTrue(
            "selection returned ${budget.typicalSelectionSize} tools",
            budget.typicalSelectionSize in 1..AndroidRetrievalBenchmark.DEFAULT_TOP_N,
        )
    }

    @Test
    fun `the selection is not just the first N tools in the registry`() {
        // A selector that returned a prefix would satisfy every size assertion
        // above while being completely useless. This is the one that catches it.
        val selected = LexicalToolSelectorRunner.select(
            "what's on my calendar tomorrow", AndroidToolSet.build(),
        ).map { it.definition.name }
        val prefix = AndroidToolStubs.specs.take(selected.size).map { it.name }
        assertFalse(
            "selection is a registry prefix: $selected",
            selected == prefix,
        )
        assertTrue("calendar.search must be selected", selected.contains("calendar.search"))
    }

    // =======================================================================
    // 7. The benchmark can FAIL
    //
    // A benchmark that cannot report a miss is indistinguishable from one that
    // checks nothing. Each test below breaks the tool set on purpose and
    // asserts the score moves. Nothing here touches a file.
    // =======================================================================

    @Test
    fun `stripping a tool's tags drops it out of the retrieval window`() {
        // The case deliberately never says "files" or "search", so with the tags
        // and description gone there is no signal left to match on. (Crippling
        // calendar.search would not work: the name half "calendar" still matches
        // "what's on my calendar tomorrow" and buys it 4 points on its own.)
        val crippled = AndroidToolStubs.specs.map { spec ->
            if (spec.name != "files.search") spec
            else spec.copy(
                tags = setOf("agenda"),
                description = "Returns things.",
            )
        }
        val tools = AndroidToolSet.build(crippled).registry.all()
        val outcome = AndroidRetrievalBenchmark().run(
            listOf(AndroidTaskSuite.cases.first { it.id == "files/where-is" }),
            tools,
        ).outcomes.single()

        assertFalse(
            "a tool stripped of its tags must fall out of the window, " +
                "got ${outcome.selected}",
            outcome.hit,
        )
        assertEquals(0, outcome.expectedScore)
    }

    @Test
    fun `renaming a tool out of the set is reported as a miss, not silently ignored`() {
        val renamed = AndroidToolStubs.specs.map { spec ->
            if (spec.name == "web.fetch") spec.copy(name = "net.download", category = "net") else spec
        }
        val tools = AndroidToolSet.build(renamed).registry.all()
        val outcome = AndroidRetrievalBenchmark().run(
            listOf(AndroidTaskSuite.cases.first { it.id == "web/weather" }),
            tools,
        ).outcomes.single()

        assertFalse("a renamed tool must not answer for its old name", outcome.hit)
    }

    @Test
    fun `a broken schema is caught by the coherence check`() {
        val broken = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put("properties", buildJsonObject {
                // No "type" on the property — the classic hand-rolled schema bug.
                put("url", buildJsonObject { })
            })
            // "required" is missing entirely.
        }
        val violations = AndroidToolCoherence.checkOne(
            AndroidToolStubs.definitionOf(AndroidToolStubs.specFor("web.fetch")).copy(schema = broken),
        )
        val rules = violations.map { it.rule }.toSet()
        assertTrue("missing property type not caught: $violations", "schema-property-type" in rules)
        assertTrue("missing required not caught: $violations", "schema-required" in rules)
    }

    @Test
    fun `a required argument with no property is caught`() {
        val schema = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put("properties", buildJsonObject { })
            put(
                "required",
                kotlinx.serialization.json.buildJsonArray {
                    add(JsonPrimitive("text"))
                },
            )
        }
        val violations = AndroidToolCoherence.checkOne(
            AndroidToolStubs.definitionOf(AndroidToolStubs.specFor("clipboard.write")).copy(schema = schema),
        )
        assertTrue(
            "an undeclared required argument must be caught, got $violations",
            violations.any { it.rule == "schema-required-unknown" },
        )
    }

    @Test
    fun `a PRIVILEGED tool is caught`() {
        val violations = AndroidToolCoherence.checkOne(
            AndroidToolStubs.definitionOf(AndroidToolStubs.specFor("device.info"))
                .copy(risk = ToolRisk.PRIVILEGED),
        )
        assertTrue(
            "a PRIVILEGED tool must be flagged, got $violations",
            violations.any { it.rule == "risk-privileged" },
        )
    }

    @Test
    fun `a destructive tool with no consequence wording is caught`() {
        val violations = AndroidToolCoherence.checkOne(
            AndroidToolStubs.definitionOf(AndroidToolStubs.specFor("files.delete"))
                .copy(description = "Deletes a file."),
        )
        assertTrue(
            "a vague destructive description must be flagged, got $violations",
            violations.any { it.rule == "risk-consequence" },
        )
    }

    @Test
    fun `a two-sentence description is caught`() {
        val violations = AndroidToolCoherence.checkOne(
            AndroidToolStubs.definitionOf(AndroidToolStubs.specFor("device.battery"))
                .copy(description = "Reads the battery level. Use it sparingly because it drains."),
        )
        assertTrue(
            "a two-sentence description must be flagged, got $violations",
            violations.any { it.rule == "description-sentence" },
        )
    }

    @Test
    fun `a tool with no tags is caught`() {
        val violations = AndroidToolCoherence.checkOne(
            AndroidToolStubs.definitionOf(AndroidToolStubs.specFor("alarm.list")).copy(tags = emptySet()),
        )
        assertTrue(
            "an untagged tool must be flagged, got $violations",
            violations.any { it.rule == "tag-count" },
        )
    }

    @Test
    fun `an unknown category is caught`() {
        val violations = AndroidToolCoherence.checkOne(
            AndroidToolStubs.definitionOf(AndroidToolStubs.specFor("apps.open"))
                .copy(category = "launcher"),
        )
        assertTrue(
            "an unknown category must be flagged, got $violations",
            violations.any { it.rule == "category-known" },
        )
    }

    // =======================================================================
    // 8. Reporting
    // =======================================================================

    @Test
    fun `the report renders every section`() {
        val text = AndroidEvalReportRenderer.render(AndroidEvalReport.build())
        listOf("COHERENCE", "RETRIEVAL", "OVERLAP", "PROMPT BUDGET", "VERDICT").forEach {
            assertTrue("report is missing the $it section", text.contains(it))
        }
        assertTrue("report has no headline number", text.contains("hit rate:"))
    }

    @Test
    fun `the report is deterministic`() {
        val first = AndroidEvalReportRenderer.render(AndroidEvalReport.build())
        val second = AndroidEvalReportRenderer.render(AndroidEvalReport.build())
        assertEquals(first, second)
    }

    @Test
    fun `the stubs are labelled with the real file each one mirrors`() {
        // A stub that does not say which real class it stands for cannot be kept
        // in sync by anyone.
        AndroidToolStubs.specs.forEach { spec ->
            assertTrue(
                "${spec.name} does not name the file it mirrors",
                spec.mirrors.startsWith("dev/localintelligence/android/tools/") &&
                    spec.mirrors.endsWith(".kt"),
            )
        }
    }

    // =======================================================================
    // Helpers
    // =======================================================================

    private fun riskyToolNames(): Set<String> = AndroidToolStubs.specs
        .filter { it.risk == ToolRisk.DESTRUCTIVE || it.risk == ToolRisk.EXTERNAL_COMMUNICATION }
        .map { it.name }
        .toSet()

    private companion object {
        /**
         * 25 tools rendered into the system prompt must stay under 4 KB.
         *
         * At roughly 4 characters per token that is ~1K tokens of pure tool
         * description on every single turn of every task. The v0 working limit
         * is 6000 tokens, so the full set would be a sixth of the entire
         * context before the user has said anything. The selector is what
         * prevents that, and this number is what makes the saving legible.
         */
        const val FULL_SET_BUDGET_CHARS = 4000

        /**
         * A small ceiling, not zero. Two near-identical tools in a 25-tool set
         * are a fact of life; twenty are a design failure.
         */
        const val MAX_CONFUSABLE_PAIRS = 4

        /**
         * 40% — a REGRESSION BASELINE on the generalization probe, not a target.
         *
         * The measured value is 10/22 = 45.5%. It is set below that on purpose:
         * this number is a measurement of a real limitation (a bag-of-words
         * retriever only knows the vocabulary it was given), not a defect to be
         * tuned away. Chasing it by adding a tag per probe phrase is how the
         * tuned suite reached a meaningless 100% in the first place.
         *
         * The floor's job is to make the tool set fail loudly if it gets WORSE —
         * if a future change strips tags and the probe drops below this, that is
         * a regression. Raising it means genuinely improving retrieval, which is
         * a selector or embedding decision, not a tag edit.
         */
        const val HELD_OUT_FLOOR = 0.40
    }
}
