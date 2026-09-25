package dev.localintelligence.core.model.token

import dev.localintelligence.core.model.ChatMessage
import dev.localintelligence.core.tool.ToolDefinition
import dev.localintelligence.core.tool.ToolRisk
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The per-step question, asked and answered before a prefill is paid for.
 *
 * The failure this prevents is specific: a tool returns 2KB, the model is
 * already generating, and the next prefill silently exceeds the window. On a
 * phone that is four seconds and a visible stall; the budget check has to
 * happen BEFORE the call, not after.
 */
class StepEnforcerTest {

    private fun tool(name: String, desc: String = "Does $name.") = ToolDefinition(
        name = name,
        description = desc,
        category = "test",
        schema = JsonObject(emptyMap()),
        risk = ToolRisk.READ_ONLY,
    )

    private fun state(
        system: String = "You operate this Android device on behalf of the user.",
        task: String = "Find the PDF I downloaded yesterday and share it.",
        summary: String = "",
        memories: List<String> = emptyList(),
        recentTurns: List<ChatMessage> = emptyList(),
        observations: List<String> = emptyList(),
        tools: List<ToolDefinition> = emptyList(),
    ) = ContextState(system, task, summary, memories, recentTurns, observations, tools)

    private fun words(n: Int) = (1..n).joinToString(" ") { "observation$it" }

    /**
     * Realistic tool output of about [chars] characters.
     *
     * Word lists are too cheap per character to overflow a 6K window inside
     * the 16K per-step bound, and this test is about a runaway observation,
     * not about a synthetic one. Real tool output is digit- and
     * punctuation-dense, which is exactly what makes it expensive.
     */
    private fun toolOutput(chars: Int): String {
        val line = "Found 3 events on 2026-09-26: standup 09:30, 1:1 11:00, gym 18:15.\n"
        // Truncated to the REQUESTED length. The per-step bound is a hard
        // limit and the caller's number is the one that has to win.
        return buildString {
            while (length + line.length <= chars) append(line)
        }
    }

    // ---------------------------------------------------------- does it fit

    @Test
    fun `an ordinary step fits`() {
        val enforcer = StepEnforcer(ContextBudget(6000))
        val plan = enforcer.evaluate(
            state(tools = listOf(tool("files.search"), tool("apps.share"))),
            ProposedStep(replyCandidate = "I found the PDF and shared it."),
        )
        assertTrue("expected Fits, got $plan", plan is StepPlan.Fits)
        assertTrue(plan.headroom > 0)
    }

    @Test
    fun `an empty step costs nothing extra`() {
        val enforcer = StepEnforcer(ContextBudget(6000))
        val s = state()
        val plan = enforcer.evaluate(s) as StepPlan.Fits
        assertEquals(0, plan.addedTokens)
        assertEquals(plan.currentTokens, plan.projectedTokens)
    }

    @Test
    fun `fits is consistent with evaluate`() {
        val enforcer = StepEnforcer(ContextBudget(500))
        val s = state(observations = listOf(words(80)))
        val step = ProposedStep(replyCandidate = words(60))
        assertEquals(enforcer.evaluate(s, step) is StepPlan.Fits, enforcer.fits(s, step))
    }

    @Test
    fun `a runaway reply is caught before the next prefill`() {
        // The specific 1B failure: the model does not stop talking. It must be
        // caught at THIS step, not discovered when the next prompt is built.
        // 16K characters of digit-dense tool output is ~10K tokens: inside the
        // per-step bound, and well past a 6K window.
        val enforcer = StepEnforcer(ContextBudget(6000))
        val step = ProposedStep(
            replyCandidate = toolOutput(StepEnforcer.MAX_CANDIDATE_CHARS),
        )
        val plan = enforcer.evaluate(state(), step)
        assertTrue("expected Trim, got $plan", plan is StepPlan.Trim)
    }

    @Test
    fun `a large tool observation is budgeted before the tool runs`() {
        // The observation is the model's next INPUT. Discovering the overflow
        // after the tool call is the expensive ordering.
        val enforcer = StepEnforcer(ContextBudget(800))
        val plan = enforcer.evaluate(
            state(),
            ProposedStep(observationCandidate = words(300)),
        )
        assertTrue("expected Trim, got $plan", plan is StepPlan.Trim)
    }

    @Test
    fun `a large summary is budgeted`() {
        val enforcer = StepEnforcer(ContextBudget(800))
        val plan = enforcer.evaluate(state(), ProposedStep(summaryCandidate = words(300)))
        assertTrue("expected Trim, got $plan", plan is StepPlan.Trim)
    }

    // ------------------------------------------------ cheapest reduction

    @Test
    fun `the cheapest reduction names a component and a cost`() {
        val enforcer = StepEnforcer(ContextBudget(200, reserveForOutputTokens = 0))
        val s = state(
            observations = listOf(words(60)),
            recentTurns = listOf(ChatMessage.Assistant(words(60))),
            memories = listOf(words(60)),
        )
        val reduction = enforcer.cheapestReduction(s, ProposedStep(replyCandidate = words(60)))
        assertNotNull("expected a reduction", reduction)
        assertEquals(UsageBucket.OBSERVATION, reduction!!.component)
        assertTrue("freed ${reduction.tokens}", reduction.tokens >= 1)
        assertTrue(reduction.description.isNotBlank())
    }

    @Test
    fun `no reduction is offered when the step already fits`() {
        val enforcer = StepEnforcer(ContextBudget(6000))
        assertNull(enforcer.cheapestReduction(state(), ProposedStep(replyCandidate = "ok")))
    }

    @Test
    fun `no reduction is offered when nothing can be dropped`() {
        // The step alone is over the limit: reply + arguments are the step, so
        // there is no honest reduction to name. Returning a fake one would
        // send the loop into a trim that cannot converge.
        val enforcer = StepEnforcer(ContextBudget(300, reserveForOutputTokens = 0))
        assertNull(
            enforcer.cheapestReduction(
                state(task = "short"),
                ProposedStep(toolArguments = words(400)),
            ),
        )
    }

    // --------------------------------------------------- unfixable reported

    @Test
    fun `an unfixable step is marked rather than given an empty plan`() {
        val enforcer = StepEnforcer(ContextBudget(300, reserveForOutputTokens = 0))
        val plan = enforcer.evaluate(
            state(task = "short"),
            ProposedStep(toolArguments = toolOutput(StepEnforcer.MAX_CANDIDATE_CHARS)),
        ) as StepPlan.Trim
        assertTrue("adjustments should be empty", plan.adjustments.isEmpty())
        assertTrue("must be flagged unfixable", plan.unfixable)
        assertTrue(plan.overage > 0)
    }

    // -------------------------------------------------------- the arithmetic

    @Test
    fun `the plan is self consistent`() {
        // Every case carries its own arithmetic so a caller can log it without
        // re-running the estimator, and a test can check it without a second
        // implementation. Verify the invariant holds.
        val enforcer = StepEnforcer(ContextBudget(900, reserveForOutputTokens = 0))
        val s = state(
            summary = words(40),
            memories = listOf(words(40)),
            recentTurns = listOf(ChatMessage.Assistant(words(40))),
            observations = listOf(words(40)),
        )
        val step = ProposedStep(replyCandidate = words(80), observationCandidate = words(20))
        val plan = enforcer.evaluate(s, step)
        assertEquals(plan.currentTokens + plan.addedTokens, plan.projectedTokens)
        assertEquals(plan.limit - plan.projectedTokens, plan.headroom)
    }

    @Test
    fun `a trim plan frees enough to fit when the context is trimmable`() {
        val enforcer = StepEnforcer(ContextBudget(200, reserveForOutputTokens = 0))
        val s = state(
            observations = listOf(words(60), words(60)),
            recentTurns = listOf(ChatMessage.Assistant(words(60))),
        )
        val plan = enforcer.evaluate(s, ProposedStep(replyCandidate = words(60)))
        assertTrue(plan is StepPlan.Trim)
        plan as StepPlan.Trim
        if (!plan.unfixable) {
            val freed = plan.adjustments.sumOf { it.tokens }
            assertTrue("freed $freed must cover overage ${plan.overage}", freed >= plan.overage)
        }
    }

    // ------------------------------------------------------ pathological input

    @Test
    fun `an oversized candidate is rejected at construction`() {
        // A 4MB logcat dump is a real Android possibility. It must not be
        // accepted into the budget check and priced char by char on every step.
        var threw = false
        try {
            ProposedStep(observationCandidate = "x".repeat(StepEnforcer.MAX_CANDIDATE_CHARS + 1))
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue("oversized observation must be rejected", threw)
    }

    @Test
    fun `every candidate field is bounded`() {
        val over = "x".repeat(StepEnforcer.MAX_CANDIDATE_CHARS + 1)
        for (build in listOf<(String) -> ProposedStep>(
            { ProposedStep(replyCandidate = it) },
            { ProposedStep(toolArguments = it) },
            { ProposedStep(observationCandidate = it) },
            { ProposedStep(summaryCandidate = it) },
        )) {
            var threw = false
            try {
                build(over)
            } catch (e: IllegalArgumentException) {
                threw = true
            }
            assertTrue("field must be bounded", threw)
        }
    }

    @Test
    fun `a candidate at exactly the bound is accepted`() {
        val atBound = "x".repeat(StepEnforcer.MAX_CANDIDATE_CHARS)
        val step = ProposedStep(observationCandidate = atBound)
        assertEquals(atBound.length, step.observationCandidate.length)
    }

    @Test
    fun `pathological text does not blow up the check`() {
        // Deeply nested JSON, a 4MB-adjacent observation, and pure emoji, all
        // priced in one call. Must complete and stay bounded.
        val enforcer = StepEnforcer(ContextBudget(2000, reserveForOutputTokens = 0))
        val nested = buildString {
            repeat(200) { append("{\"k\":[") }
            append("1")
            repeat(200) { append("]}") }
        }
        val step = ProposedStep(
            replyCandidate = "👨‍👩‍👧‍👦".repeat(500),
            observationCandidate = nested,
            toolArguments = "{\"a\":1}".repeat(500),
        )
        val plan = enforcer.evaluate(state(tools = listOf(tool("t"))), step)
        assertTrue(plan.projectedTokens > 0)
    }

    @Test
    fun `a very long unbroken token in a candidate is handled`() {
        // One "word" of 16K characters with no separators. Prices as a small
        // number of sub-tokens, must not hang, must not overflow.
        val enforcer = StepEnforcer(ContextBudget(4000, reserveForOutputTokens = 0))
        val step = ProposedStep(
            observationCandidate = "A".repeat(StepEnforcer.MAX_CANDIDATE_CHARS),
        )
        val plan = enforcer.evaluate(state(), step)
        assertTrue("projected=${plan.projectedTokens}", plan.projectedTokens > 0)
    }

    // ------------------------------------------------------------ purity

    @Test
    fun `evaluate does not mutate its inputs`() {
        // A per-step check that mutated state would be a heisenbug: the same
        // inputs would give different answers on the next step.
        val enforcer = StepEnforcer(ContextBudget(500, reserveForOutputTokens = 0))
        val s = state(observations = listOf(words(40)))
        val step = ProposedStep(replyCandidate = words(60))
        val first = enforcer.evaluate(s, step)
        val second = enforcer.evaluate(s, step)
        assertEquals(first.projectedTokens, second.projectedTokens)
        assertEquals(s.observations.size, 1)
        assertEquals(words(40), s.observations[0])
    }

    @Test
    fun `the enforcer is stateless across calls`() {
        val enforcer = StepEnforcer(ContextBudget(6000))
        val s = state()
        val warm = enforcer.evaluate(s, ProposedStep(replyCandidate = words(50)))
        repeat(100) {
            assertEquals(warm.projectedTokens, enforcer.evaluate(s, ProposedStep(words(50))).projectedTokens)
        }
    }

    @Test
    fun `an empty proposed step is recognised`() {
        assertTrue(ProposedStep().isEmpty)
        assertFalse(ProposedStep(replyCandidate = "x").isEmpty)
    }
}
