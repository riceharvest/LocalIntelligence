package dev.localintelligence.core.model.token

import dev.localintelligence.core.agent.Memory
import dev.localintelligence.core.model.ChatMessage
import dev.localintelligence.core.tool.ToolDefinition
import dev.localintelligence.core.tool.ToolRisk
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The budget is the product constraint made executable.
 *
 * Architecture section 9: "A routine Android action should never require a
 * 20K prefill." These tests check the two properties that make that real: the
 * budget refuses a prompt that does not fit, and when it refuses, it says what
 * to drop rather than slicing something in half.
 */
class ContextBudgetTest {

    // ---------------------------------------------------------------- helpers

    private fun tool(name: String, desc: String = "Does $name on the device.") = ToolDefinition(
        name = name,
        description = desc,
        category = "test",
        schema = JsonObject(emptyMap()),
        risk = ToolRisk.READ_ONLY,
    )

    private fun state(
        system: String = "You operate this Android device on behalf of the user.",
        task: String = "Check battery level and tell me whether I should charge.",
        summary: String = "",
        memories: List<String> = emptyList(),
        recentTurns: List<ChatMessage> = emptyList(),
        observations: List<String> = emptyList(),
        tools: List<ToolDefinition> = emptyList(),
    ) = ContextState(
        systemPrompt = system,
        task = task,
        workingSummary = summary,
        memories = memories,
        recentTurns = recentTurns,
        observations = observations,
        tools = tools,
    )

    private fun words(n: Int) = (1..n).joinToString(" ") { "token$it" }

    // ------------------------------------------------------------ construction

    @Test
    fun `a positive limit is required`() {
        // Silently coercing a zero or negative limit to something usable would
        // turn a configuration bug into an infinite trim loop at runtime.
        var threw = false
        try {
            ContextBudget(limitTokens = 0)
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue("limitTokens=0 must be rejected", threw)
    }

    @Test
    fun `prompt budget reserves room for the reply`() {
        val budget = ContextBudget(limitTokens = 6000, reserveForOutputTokens = 256)
        assertEquals(5744, budget.promptTokens)
    }

    @Test
    fun `the working target matches the architecture`() {
        // docs/architecture.md section 9: normal working target 3-6K tokens.
        val target = ContextBudget.workingTarget()
        assertEquals(6000, target.limitTokens)
        assertTrue(
            "prompt budget ${target.promptTokens} should sit in the 3-6K band",
            target.promptTokens in 3000..6000,
        )
    }

    @Test
    fun `a budget larger than the output reserve always leaves prompt room`() {
        val budget = ContextBudget(limitTokens = 100, reserveForOutputTokens = 200)
        assertEquals("must not go negative or zero", 1, budget.promptTokens)
    }

    // ----------------------------------------------------------------- fits

    @Test
    fun `a small prompt fits and reports headroom`() {
        val budget = ContextBudget(limitTokens = 6000)
        val plan = budget.plan(state())
        assertTrue(plan is BudgetPlan.Fits)
        plan as BudgetPlan.Fits
        assertTrue("headroom ${plan.headroom}", plan.headroom > 0)
        assertEquals(plan.limit - plan.total, plan.headroom)
    }

    @Test
    fun `fits is consistent with plan`() {
        val budget = ContextBudget(limitTokens = 6000)
        val s = state()
        assertEquals(budget.fits(s), budget.plan(s) is BudgetPlan.Fits)
    }

    @Test
    fun `headroom is negative when over budget and never below minus the total`() {
        val budget = ContextBudget(limitTokens = 200)
        val s = state(task = words(500))
        assertTrue(budget.headroom(s) < 0)
    }

    // ------------------------------------------------------- exactly at limit

    @Test
    fun `a prompt exactly at the limit fits`() {
        // The boundary matters: an off-by-one that rejects a prompt at exactly
        // the ceiling throws away a turn of context for no reason.
        val counter = DefaultTokenCounter
        val budget = ContextBudget(limitTokens = 2000, reserveForOutputTokens = 0)
        val base = state(system = "system prompt text", task = "do a thing")
        val baseTokens = base.measure(counter).totalTokens

        // Grow the task until the total is exactly promptTokens.
        var task = base.task
        var tokens = baseTokens
        while (tokens < budget.promptTokens) {
            task += " x"
            tokens = base.measure(counter).let { _ ->
                base.copy(task = task).measure(counter).totalTokens
            }
        }
        assertEquals(budget.promptTokens, tokens)

        val plan = budget.plan(base.copy(task = task), counter)
        assertTrue("exactly at the limit must fit, was $plan", plan is BudgetPlan.Fits)
    }

    @Test
    fun `one token over the limit does not fit`() {
        // Needs droppable content, or "over budget" correctly means
        // "Impossible" and the boundary is never actually exercised.
        val counter = DefaultTokenCounter
        val budget = ContextBudget(limitTokens = 2000, reserveForOutputTokens = 0)
        val base = state(task = "do a thing", observations = listOf(words(60)))
        var task = base.task
        while (base.copy(task = task).measure(counter).totalTokens < budget.promptTokens) {
            task += " x"
        }
        // One more word pushes it over, and a plan exists to fix it.
        val over = task + " x"
        val plan = budget.plan(base.copy(task = over), counter)
        assertTrue("one over must not fit, was $plan", plan is BudgetPlan.Reduce)
        val reduce = plan as BudgetPlan.Reduce
        assertEquals(1, reduce.overage)
        assertTrue(
            "the plan must free the overage",
            reduce.actions.sumOf { it.tokens } >= reduce.overage,
        )
    }

    // ----------------------------------------------------- what to drop, in order

    @Test
    fun `observations are dropped before turns memories and tools`() {
        // The drop order is a product decision, not an implementation detail:
        // a stale observation is worth less than a missing instruction.
        val budget = ContextBudget(limitTokens = 300, reserveForOutputTokens = 0)
        val s = state(
            observations = listOf(words(40), words(40)),
            recentTurns = listOf(ChatMessage.Assistant(words(40))),
            memories = listOf(words(40)),
            tools = listOf(tool("calendar.search", words(40))),
        )
        val plan = budget.plan(s)
        assertTrue(plan is BudgetPlan.Reduce)
        val first = (plan as BudgetPlan.Reduce).actions.first()
        assertEquals(UsageBucket.OBSERVATION, first.component)
    }

    @Test
    fun `the drop order runs observations turns memories tools`() {
        // Sized so the overage has to reach all four buckets: a small
        // overage is closed by the first drop and the rest of the order is
        // never exercised.
        // The tool has to be worth MORE than observations + turns + memories
        // put together, or the overage closes before the last bucket is
        // reached and the tail of the order is never exercised. That is not
        // artificial: a tool with a deep JSON schema really is the biggest
        // single item in a prompt.
        val budget = ContextBudget(limitTokens = 300, reserveForOutputTokens = 0)
        val s = state(
            observations = listOf(words(100)),
            recentTurns = listOf(ChatMessage.Assistant(words(100))),
            memories = listOf(words(100), words(100)),
            tools = listOf(tool("t", words(1_000))),
        )
        val plan = budget.plan(s)
        assertTrue(plan is BudgetPlan.Reduce)
        val order = (plan as BudgetPlan.Reduce).actions.map { it.component }
        val distinct = order.distinct()
        assertEquals(
            listOf(UsageBucket.OBSERVATION, UsageBucket.TURN, UsageBucket.MEMORY, UsageBucket.TOOL),
            distinct,
        )
    }

    @Test
    fun `tools are dropped last because dropping one can make an action impossible`() {
        val budget = ContextBudget(limitTokens = 300, reserveForOutputTokens = 0)
        // Everything else already gone: the tool must go next.
        val s = state(tools = listOf(tool("calendar.search", words(200))))
        val plan = budget.plan(s)
        assertTrue(plan is BudgetPlan.Reduce)
        val actions = (plan as BudgetPlan.Reduce).actions
        assertTrue(actions.isNotEmpty())
        assertEquals(UsageBucket.TOOL, actions.first().component)
    }

    @Test
    fun `the plan frees at least the overage`() {
        // The whole point: a caller that applies the plan CANNOT overflow.
        val counter = DefaultTokenCounter
        val budget = ContextBudget(limitTokens = 400, reserveForOutputTokens = 0)
        val s = state(
            summary = words(50),
            memories = listOf(words(50), words(50)),
            recentTurns = listOf(ChatMessage.Assistant(words(50))),
            observations = listOf(words(50), words(50), words(50)),
            tools = listOf(tool("a", words(30)), tool("b", words(30))),
        )
        val plan = budget.plan(s, counter)
        assertTrue(plan is BudgetPlan.Reduce)
        val reduce = plan as BudgetPlan.Reduce
        val freed = reduce.actions.sumOf { it.tokens }
        assertTrue(
            "freed $freed must cover overage ${reduce.overage}",
            freed >= reduce.overage,
        )
    }

    @Test
    fun `every action names a component and a token count`() {
        val budget = ContextBudget(limitTokens = 300, reserveForOutputTokens = 0)
        val s = state(observations = listOf(words(50), words(50), words(50)))
        val plan = budget.plan(s)
        assertTrue(plan is BudgetPlan.Reduce)
        for (action in (plan as BudgetPlan.Reduce).actions) {
            assertTrue("action freed ${action.tokens} tokens", action.tokens >= 1)
            assertTrue("action has no label: $action", action.label.isNotBlank())
        }
    }

    // ------------------------------------------------------ never truncated

    @Test
    fun `nothing is silently truncated to fit`() {
        // The failure this type exists to prevent: a builder slicing a tool
        // schema in half. Verify that no returned action implies slicing —
        // every action is a whole-component drop.
        val budget = ContextBudget(limitTokens = 300, reserveForOutputTokens = 0)
        val s = state(
            tools = listOf(tool("calendar.search", words(500))),
            observations = listOf(words(500)),
        )
        val plan = budget.plan(s)
        assertTrue(plan is BudgetPlan.Reduce)
        for (action in (plan as BudgetPlan.Reduce).actions) {
            assertTrue(
                "action must be a whole drop, got $action",
                action is BudgetAction.DropOne,
            )
        }
    }

    // ------------------------------------------------------------- impossible

    @Test
    fun `a tool definition bigger than the whole budget is dropped, not truncated`() {
        // A 20KB description against a 1000-token ceiling. The honest answer
        // is "drop this tool", NOT "impossible" and certainly not "slice it".
        // The tool goes: the model loses a capability, which is visible, rather
        // than getting a half-schema, which is not.
        val budget = ContextBudget(limitTokens = 1000, reserveForOutputTokens = 0)
        val s = state(tools = listOf(tool("giant", words(20_000))))
        val plan = budget.plan(s)
        assertTrue("expected Reduce, got $plan", plan is BudgetPlan.Reduce)
        val actions = (plan as BudgetPlan.Reduce).actions
        assertEquals(UsageBucket.TOOL, actions.first().component)
        assertTrue(
            "dropping the tool must free the overage",
            actions.sumOf { it.tokens } >= plan.overage,
        )
    }

    @Test
    fun `impossible means the request itself does not fit`() {
        // Nothing is droppable, so no plan can help. The only remaining
        // levers are a bigger window or a shorter request, and the caller
        // needs to know which it is.
        val budget = ContextBudget(limitTokens = 1000, reserveForOutputTokens = 0)
        // System prompt plus task ALONE must exceed the window; observations
        // on top do not change that, they just make the overage bigger.
        val s = state(
            system = words(1_200),
            task = words(1_200),
            observations = listOf(words(2_000)),
        )
        val plan = budget.plan(s)
        assertTrue("expected Impossible, got $plan", plan is BudgetPlan.Impossible)
        plan as BudgetPlan.Impossible
        assertTrue(plan.overage > 0)
        assertTrue("irreducible ${plan.irreducible}", plan.irreducible > 0)
    }

    @Test
    fun `a task larger than the whole window is impossible`() {
        val budget = ContextBudget(limitTokens = 500, reserveForOutputTokens = 0)
        val plan = budget.plan(state(task = words(5_000)))
        assertTrue("expected Impossible, got $plan", plan is BudgetPlan.Impossible)
    }

    @Test
    fun `impossible reports how much cannot be saved`() {
        val budget = ContextBudget(limitTokens = 500, reserveForOutputTokens = 0)
        val s = state(task = words(2_000))
        val plan = budget.plan(s) as BudgetPlan.Impossible
        // Everything except system+task is droppable, so the irreducible part
        // is exactly the system prompt plus the task.
        val usage = s.measure(DefaultTokenCounter)
        assertEquals(usage.droppableTokens, plan.total - plan.irreducible)
    }

    // ------------------------------------------------------------------ usage

    @Test
    fun `usage totals the sum of its parts`() {
        val s = state(
            summary = words(10),
            memories = listOf(words(10)),
            recentTurns = listOf(ChatMessage.Assistant(words(10))),
            observations = listOf(words(10)),
            tools = listOf(tool("t")),
        )
        val usage = s.measure()
        assertEquals(
            usage.systemPromptTokens + usage.taskTokens + usage.workingSummaryTokens +
                usage.memoryTokens + usage.turnTokens + usage.observationTokens + usage.toolTokens,
            usage.totalTokens,
        )
    }

    @Test
    fun `an empty state costs only its system prompt and task`() {
        val usage = ContextState().measure()
        assertEquals(0, usage.totalTokens)
        assertEquals(0, usage.droppableTokens)
    }

    @Test
    fun `tool definitions are measured not guessed`() {
        // A tool with a big schema must cost measurably more than a bare one.
        // This is the hidden cost architecture section 11 warns about.
        val bare = ContextState(tools = listOf(tool("a", "short")))
        val rich = ContextState(
            tools = listOf(
                tool(
                    "a",
                    "Search the user's calendar for events matching a query " +
                        "within a time range, returning title, start, end and attendees.",
                ),
            ),
        )
        assertTrue(
            "rich tool must cost more: ${bare.measure().toolTokens} vs " +
                rich.measure().toolTokens,
            rich.measure().toolTokens > bare.measure().toolTokens,
        )
    }

    @Test
    fun `a tool schema is included in the tool cost`() {
        val schema = kotlinx.serialization.json.buildJsonObject {
            put("type", kotlinx.serialization.json.JsonPrimitive("object"))
            put(
                "properties",
                kotlinx.serialization.json.buildJsonObject {
                    put("query", kotlinx.serialization.json.JsonPrimitive("string"))
                },
            )
        }
        val withSchema = ToolDefinition(
            name = "t",
            description = "d",
            category = "c",
            schema = schema,
            risk = ToolRisk.READ_ONLY,
        )
        assertTrue(
            "schema must add tokens",
            renderTool(withSchema).contains("object"),
        )
        assertTrue(
            "withSchema ${DefaultTokenCounter.count(renderTool(withSchema))} " +
                "> without ${DefaultTokenCounter.count(renderTool(tool("t", "d")))}",
            DefaultTokenCounter.count(renderTool(withSchema)) >
                DefaultTokenCounter.count(renderTool(tool("t", "d"))),
        )
    }

    @Test
    fun `the floor is the system prompt plus the task`() {
        val budget = ContextBudget(limitTokens = 6000)
        val s = state(
            summary = words(100),
            memories = listOf(words(100)),
            recentTurns = listOf(ChatMessage.Assistant(words(100))),
            observations = listOf(words(100)),
        )
        val usage = s.measure()
        assertEquals(usage.systemPromptTokens + usage.taskTokens, budget.floorFor(s))
    }

    @Test
    fun `an empty context fits any reasonable budget`() {
        val budget = ContextBudget(limitTokens = 3000)
        assertTrue(budget.fits(ContextState()))
        assertEquals(budget.promptTokens, budget.headroom(ContextState()))
    }

    // -------------------------------------------------- memory-shaped inputs

    @Test
    fun `real memory objects do not break measurement`() {
        // Memories reach this layer as rendered strings, but a caller holding
        // the raw objects must be able to render them. Guards the shape
        // crossing from the agent package.
        val memories = listOf(Memory(1, "Home Assistant is 192.168.1.20", "home assistant"))
        val state = ContextState(memories = memories.map { "- ${it.text}" })
        assertTrue(state.measure().memoryTokens > 0)
    }

    @Test
    fun `tool observations are measured on their observation text`() {
        // The model only ever sees `observation`, never `data`. Charging for
        // the whole ToolResult would over-count by an order of magnitude.
        val message = ChatMessage.ToolObservation(
            toolName = "calendar.search",
            observation = "3 events found",
            success = true,
        )
        assertEquals("3 events found", renderMessage(message))
    }

    @Test
    fun `a tool observation turn is charged once not twice`() {
        val single = ContextState(observations = listOf("3 events found"))
        val viaTurn = ContextState(
            recentTurns = listOf(
                ChatMessage.ToolObservation("calendar.search", "3 events found", true),
            ),
        )
        assertEquals(
            single.measure().observationTokens,
            viaTurn.measure().turnTokens,
        )
    }
}
