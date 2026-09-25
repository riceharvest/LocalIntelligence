package dev.pidroid.core.eval

import dev.pidroid.core.agent.ActionParseResult
import dev.pidroid.core.tool.ObservationTruncator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Tests for the harness itself.
 *
 * A green eval suite proves nothing unless the harness is known to be able to
 * fail. Every assertion type below is tested in BOTH directions: a scenario that
 * should pass does, and a deliberately broken version of the same scenario does
 * not. A scorer that cannot fail is a scorer that always says yes, and an eval
 * that always says yes is decoration.
 */
class HarnessSelfTest {

    // =======================================================================
    // 1. Suite integrity
    // =======================================================================

    @Test
    fun `suite has exactly 50 tasks`() {
        assertEquals(50, TaskSuite.all().size)
    }

    @Test
    fun `every category has exactly the specified count`() {
        val counts = EvalMain.run().categoryCounts()
        TaskCategory.entries.forEach { category ->
            assertEquals(
                "category ${category.slug} has the wrong number of tasks",
                category.expectedCount,
                counts[category],
            )
        }
    }

    @Test
    fun `task slugs are unique`() {
        val slugs = TaskSuite.all().map { it.slug }
        assertEquals(slugs.size, slugs.toSet().size)
    }

    @Test
    fun `every referenced tool exists in the library`() {
        TaskSuite.all().forEach { task ->
            task.visibleTools.forEach { tool ->
                assertTrue(
                    "task ${task.slug} references unknown tool $tool",
                    KNOWN_TOOL_NAMES.contains(tool),
                )
            }
            task.expectedCalls.forEach { call ->
                assertTrue(
                    "task ${task.slug} expects unknown tool ${call.tool}",
                    KNOWN_TOOL_NAMES.contains(call.tool),
                )
            }
        }
    }

    @Test
    fun `every task has a non-empty utterance, a script and visible tools`() {
        TaskSuite.all().forEach { task ->
            assertTrue("${task.slug} has no utterance", task.utterance.isNotBlank())
            assertTrue("${task.slug} has no model script", task.modelScript.isNotEmpty())
            assertTrue("${task.slug} has no visible tools", task.visibleTools.isNotEmpty())
        }
    }

    @Test
    fun `every model script ends in a response or a gated call`() {
        // A script that never terminates would silently rely on the fake's
        // exhaustion fallback. That is fine for a test, never for a task.
        TaskSuite.all().forEach { task ->
            val last = task.modelScript.last()
            val terminates = last.contains("\"action\":\"respond\"") || task.requiresConfirmation != null
            assertTrue("task ${task.slug} has a script that never terminates", terminates)
        }
    }

    @Test
    fun `core sources contain no android imports`() {
        // :core is a pure JVM module. An android.* import here fails CI, and it
        // is cheaper to fail in a test than in someone's emulator session.
        val mainDir = File("src/main/kotlin")
        if (!mainDir.exists()) return // run from a different cwd; nothing to check
        val offenders = mainDir.walkTopDown()
            .filter { it.extension == "kt" }
            .flatMap { file ->
                file.readLines()
                    .withIndex()
                    .filter { (_, line) -> line.trimStart().startsWith("import android") }
                    .map { (i, line) -> "${file.path}:${i + 1}: $line" }
            }
            .toList()
        assertTrue("android imports in :core -> $offenders", offenders.isEmpty())
    }

    // =======================================================================
    // 2. The suite passes end to end
    // =======================================================================

    @Test
    fun `the whole suite passes against the reference loop`() {
        val report = EvalMain.run()
        val failures = report.outcomes.filterNot { it.passed }
        assertTrue(
            "suite failures:\n" + failures.joinToString("\n") { "${it.task.slug}: ${it.failures}" },
            failures.isEmpty(),
        )
        assertEquals(50, report.total)
    }

    @Test
    fun `the suite is deterministic across runs`() {
        val first = EvalMain.run().outcomes.map { it.task.slug to it.passed }
        val second = EvalMain.run().outcomes.map { it.task.slug to it.passed }
        assertEquals(first, second)
    }

    @Test
    fun `the report renders a line per task and both headline numbers`() {
        val text = EvalReport.render(EvalMain.run())
        assertEquals(50, text.lines().count { it.startsWith("[ok]") || it.startsWith("[FAIL]") })
        assertTrue(text.contains("primary:"))
        assertTrue(text.contains("secondary:"))
        assertTrue(text.contains("50 tasks |"))
    }

    // =======================================================================
    // 3. The scorer can FAIL — each assertion, in the negative
    // =======================================================================

    @Test
    fun `a wrong tool choice fails the task`() = runBlocking {
        val task = singleTask("battery.read")
        val run = runOf(task, listOf(FakeModelBackend.callTool("wifi.read"), reply("43%")))
        val outcome = EvalScorer().score(task, run)
        assertFalse(outcome.passed)
        assertTrue(outcome.failures.any { it.contains("expected tool battery.read, got wifi.read") })
    }

    @Test
    fun `a wrong argument fails the task`() = runBlocking {
        val task = taskWithArgs()
        val run = runOf(task, listOf(FakeModelBackend.callTool("clipboard.write", args("text" to "wrong")), reply("done")))
        val outcome = EvalScorer().score(task, run)
        assertFalse(outcome.passed)
        assertTrue(outcome.failures.any { it.contains("argument") })
    }

    @Test
    fun `a missing argument fails the task`() = runBlocking {
        val task = taskWithArgs()
        val run = runOf(task, listOf(FakeModelBackend.callTool("clipboard.write"), reply("done")))
        val outcome = EvalScorer().score(task, run)
        assertFalse(outcome.passed)
        assertTrue(outcome.failures.any { it.contains("missing argument") })
    }

    @Test
    fun `an extra unexpected call fails the task`() = runBlocking {
        val task = singleTask("battery.read")
        val run = runOf(
            task,
            listOf(
                FakeModelBackend.callTool("battery.read"),
                FakeModelBackend.callTool("wifi.read"),
                reply("43%"),
            ),
        )
        val outcome = EvalScorer().score(task, run)
        assertFalse(outcome.passed)
        assertTrue(outcome.failures.any { it.contains("extra tool call") })
    }

    @Test
    fun `a wrong action sequence fails the task`() = runBlocking {
        val task = taskRequiring("contacts.search", "clipboard.write")
        val run = runOf(
            task,
            listOf(FakeModelBackend.callTool("clipboard.write", args("text" to "x")), reply("ok")),
        )
        val outcome = EvalScorer().score(task, run)
        assertFalse(outcome.passed)
        // It must name the tool it got instead of the tool it wanted.
        assertTrue(
            "failure should name the wrong tool: ${outcome.failures}",
            outcome.failures.any { it.contains("contacts.search") || it.contains("step 1") },
        )
    }

    @Test
    fun `a call to a tool outside the visible set fails the task`() = runBlocking {
        val task = singleTask("battery.read")
        // browser.search is a real tool, but this task does not expose it.
        val run = runOf(
            task,
            listOf(FakeModelBackend.callTool("browser.search", args("query" to "battery")), reply("done")),
        )
        val outcome = EvalScorer().score(task, run)
        assertFalse(outcome.passed)
        assertTrue(outcome.failures.any { it.contains("expected") })
    }

    @Test
    fun `a forbidden tool fails the task`() = runBlocking {
        val task = singleTask("battery.read").copy(
            limits = TaskLimits(forbiddenTools = setOf("battery.read"))
        )
        val run = runOf(task, listOf(FakeModelBackend.callTool("battery.read"), reply("43%")))
        val outcome = EvalScorer().score(task, run)
        assertFalse(outcome.passed)
        assertTrue(outcome.failures.any { it.contains("forbidden tool") })
    }

    @Test
    fun `exceeding the step ceiling fails the task`() = runBlocking {
        val task = singleTask("battery.read").copy(limits = TaskLimits(maxSteps = 1))
        val run = runOf(task, listOf(FakeModelBackend.callTool("battery.read"), reply("43%")))
        val outcome = EvalScorer().score(task, run)
        assertFalse(outcome.passed)
        assertTrue(outcome.failures.any { it.contains("limit is 1") })
    }

    @Test
    fun `a hallucinated success on an impossible task fails the task`() = runBlocking {
        val task = impossibleTask()
        val run = runOf(task, listOf(reply("Done! I turned the TV off for you.")))
        val outcome = EvalScorer().score(task, run)
        assertFalse(outcome.passed)
        assertTrue(outcome.failures.any { it.contains("does not satisfy") })
    }

    @Test
    fun `inventing a phone number after a denied permission fails the task`() = runBlocking {
        val task = deniedContactsTask()
        val run = runOf(
            task,
            listOf(
                FakeModelBackend.callTool("contacts.search", args("query" to "Bram")),
                reply("His number is +31612345678."),
            ),
        )
        val outcome = EvalScorer().score(task, run)
        assertFalse(outcome.passed)
        assertTrue(outcome.failures.any { it.contains("avoids") })
    }

    // =======================================================================
    // 4. Loop detection — the 14-retry failure mode
    // =======================================================================

    @Test
    fun `a model that retries a denied call is stopped, not obeyed`() = runBlocking {
        // This is the scenario docs/evals.md calls out by name.
        val task = deniedCalendarTask()
        val script = buildList {
            repeat(14) {
                add(FakeModelBackend.callTool("calendar.search", args("date" to "2026-10-02")))
            }
            add(reply("I can't read your calendar; the permission is not granted."))
        }
        val run = runOf(task, script)

        // The model asked 14 times. The runtime must have executed it once and
        // stopped. Both halves matter: executing 14 times is a battery bug,
        // and stopping the run without ever trying is not an agent.
        val attempts = run.attemptedCalls.count { it.name == "calendar.search" }
        assertTrue("the model should have retried, got $attempts attempts", attempts > 1)
        assertEquals(
            "the tool must not have been invoked more than once",
            1,
            run.invokedCalls.count { it.name == "calendar.search" },
        )
        assertTrue(
            "the run must not have run all 14 scripted steps, got ${run.steps}",
            run.steps < script.size,
        )
        // And the harness must SEE the storm, even though the runtime stopped it.
        assertTrue("duplicate_calls must count attempts, got ${run.duplicateCalls}", run.duplicateCalls > 0)
    }

    @Test
    fun `duplicate calls are counted and reported`() = runBlocking {
        val task = singleTask("battery.read").copy(limits = TaskLimits(maxDuplicateCalls = 0))
        val run = runOf(
            task,
            listOf(
                FakeModelBackend.callTool("battery.read"),
                FakeModelBackend.callTool("battery.read"),
                reply("43%"),
            ),
        )
        assertTrue(run.duplicateCalls >= 1)
    }

    // =======================================================================
    // 5. FakeModelBackend
    // =======================================================================

    @Test
    fun `the fake returns its script in order`() = runBlocking {
        val backend = FakeModelBackend(listOf("a", "b", "c"))
        val r1 = backend.generate(request())
        val r2 = backend.generate(request())
        val r3 = backend.generate(request())
        assertEquals(listOf("a", "b", "c"), listOf(r1.text, r2.text, r3.text))
        assertEquals(3, backend.generationCount)
    }

    @Test
    fun `the fake records every prompt it was given`() = runBlocking {
        val backend = FakeModelBackend(listOf("a", "b"))
        backend.generate(request())
        backend.generate(request())
        assertEquals(2, backend.requests.size)
        assertEquals(2, backend.prompts.size)
        assertTrue(backend.prompts.first().contains("SYSTEM"))
    }

    @Test
    fun `the fake fires its request callback`() = runBlocking {
        val seen = mutableListOf<Int>()
        val backend = FakeModelBackend(listOf("a", "b"))
        backend.onRequest = { _, index -> seen += index }
        backend.generate(request())
        backend.generate(request())
        assertEquals(listOf(0, 1), seen)
    }

    @Test
    fun `the fake reports exhaustion instead of crashing`() = runBlocking {
        val backend = FakeModelBackend(listOf("only-one"))
        backend.generate(request())
        val extra = backend.generate(request())
        assertEquals(1, backend.exhaustionCount)
        assertTrue(extra.text.contains("out of scripted steps"))
    }

    @Test
    fun `the fake is byte-identical across runs`() = runBlocking {
        fun once() = runBlocking {
            val b = FakeModelBackend(listOf("x", "y"))
            listOf(b.generate(request()).text, b.generate(request()).text, b.outputTokensTotal)
        }
        assertEquals(once(), once())
    }

    // =======================================================================
    // 6. ScriptedTool
    // =======================================================================

    @Test
    fun `a scripted tool records the exact calls it received`() = runBlocking {
        val tool = ScriptedTools.ok("battery.read", "43%")
        tool.execute(args(), ctx())
        tool.execute(args(), ctx())
        assertEquals(2, tool.callCount)
    }

    @Test
    fun `a denied tool returns permission_denied`() = runBlocking {
        val tool = ScriptedTools.denied("contacts.search", "android.permission.READ_CONTACTS")
        val result = tool.execute(args(), ctx())
        assertFalse(result.success)
        assertTrue(result.error is dev.pidroid.core.tool.ToolError.PermissionDenied)
    }

    @Test
    fun `a throwing tool actually throws`() {
        val tool = ScriptedTools.throwing("storage.read")
        val thrown = runCatching { runBlocking { tool.execute(args(), ctx()) } }
        assertTrue(thrown.isFailure)
    }

    @Test
    fun `a huge observation is available to the truncator`() = runBlocking {
        val tool = ScriptedTools.huge("files.search", "many rows")
        val result = tool.execute(args(), ctx())
        assertTrue(result.observation.length > ObservationTruncator.DEFAULT_BUDGET_CHARS)
        val truncated = ObservationTruncator.truncate(result.observation)
        assertTrue(truncated.length <= ObservationTruncator.DEFAULT_BUDGET_CHARS)
        assertTrue(truncated.contains("truncated"))
    }

    @Test
    fun `duplicate call counting ignores distinct arguments`() = runBlocking {
        val tool = ScriptedTools.ok("calendar.search", "one event")
        tool.execute(args("date" to "2026-10-01"), ctx())
        tool.execute(args("date" to "2026-10-02"), ctx())
        tool.execute(args("date" to "2026-10-02"), ctx())
        assertEquals(3, tool.callCount)
        assertEquals(1, tool.duplicateCallCount)
    }

    // =======================================================================
    // 7. The validation gate
    // =======================================================================

    @Test
    fun `the gate accepts a legal argument`() {
        val registry = MockToolRegistry(listOf(TaskToolLibrary.toolFor("clipboard.write")))
        val tool = registry.all().first()
        val outcome = registry.gate().validate("clipboard.write", args("text" to "hi"), listOf(tool))
        assertTrue(outcome is GateOutcome.Ok)
    }

    @Test
    fun `the gate rejects an argument the schema does not declare`() {
        val registry = MockToolRegistry(listOf(TaskToolLibrary.toolFor("clipboard.write")))
        val tool = registry.all().first()
        val outcome = registry.gate().validate("clipboard.write", args("nonsense" to "hi"), listOf(tool))
        assertTrue(outcome is GateOutcome.Rejected)
    }

    @Test
    fun `the gate rejects a tool that is not visible`() {
        val registry = MockToolRegistry(listOf(TaskToolLibrary.toolFor("battery.read")))
        val outcome = registry.gate().validate("browser.search", args(), emptyList())
        assertTrue(outcome is GateOutcome.Rejected)
        assertTrue((outcome as GateOutcome.Rejected).observation.contains("Unknown tool"))
    }

    @Test
    fun `the gate reads schema properties, not schema top-level keys`() {
        // Documents the discrepancy with the frozen ToolCallValidator, which
        // checks args against schema.keys (type/properties/required) and would
        // therefore reject every argument of every call.
        val registry = MockToolRegistry(listOf(TaskToolLibrary.toolFor("clipboard.write")))
        val tool = registry.all().first()
        assertTrue("schema must use properties", tool.definition.schema.containsKey("properties"))
        val outcome = registry.gate().validate("clipboard.write", args("text" to "x"), listOf(tool))
        assertTrue("a legal argument must survive validation", outcome is GateOutcome.Ok)
    }

    // =======================================================================
    // 8. The parser
    // =======================================================================

    @Test
    fun `the parser reads a respond action`() {
        val parsed = EvalActionParser().parse(FakeModelBackend.respond("hello"), emptySet())
        assertTrue(parsed is ActionParseResult.Parsed)
        assertEquals("hello", (parsed as ActionParseResult.Parsed).action.let {
            (it as dev.pidroid.core.agent.AgentAction.Respond).text
        })
    }

    @Test
    fun `the parser reads a call action with arguments`() {
        val raw = FakeModelBackend.callTool("clipboard.write", args("text" to "hi"))
        val parsed = EvalActionParser().parse(raw, setOf("clipboard.write"))
        assertTrue(parsed is ActionParseResult.Parsed)
        val action = (parsed as ActionParseResult.Parsed).action
        assertTrue(action is dev.pidroid.core.agent.AgentAction.CallTool)
        assertEquals("hi", (action as dev.pidroid.core.agent.AgentAction.CallTool).arguments.flatten()["text"])
    }

    @Test
    fun `the parser never throws on garbage`() {
        listOf("", "   ", "not json at all", "{", "[]", "{}", "{\"action\":\"fly\"}").forEach { raw ->
            val parsed = EvalActionParser().parse(raw, emptySet())
            assertTrue("expected Malformed for: $raw", parsed is ActionParseResult.Malformed)
        }
    }

    @Test
    fun `the parser rejects a respond with no text`() {
        val parsed = EvalActionParser().parse("{\"action\":\"respond\",\"text\":\"\"}", emptySet())
        assertTrue(parsed is ActionParseResult.Malformed)
    }

    // =======================================================================
    // 9. The reference loop
    // =======================================================================

    @Test
    fun `the loop truncates a huge observation before the model sees it`() = runBlocking {
        val task = hugeObservationTask()
        val run = ReferenceAgentLoop().run(task)
        assertTrue(
            "observation was ${run.maxObservationChars} chars",
            run.maxObservationChars <= ObservationTruncator.DEFAULT_BUDGET_CHARS,
        )
    }

    @Test
    fun `the loop turns a thrown tool exception into a failed result, not a crash`() = runBlocking {
        val task = throwingToolTask()
        val run = ReferenceAgentLoop().run(task)
        assertNotNull(run.finalAnswer)
        assertEquals(listOf("storage.read"), run.failedTools)
    }

    @Test
    fun `the loop gates a destructive tool behind confirmation`() = runBlocking {
        val task = confirmationTask("alarm.delete")
        val run = ReferenceAgentLoop().run(task)
        assertEquals("alarm.delete", run.awaitingConfirmationFor)
        assertTrue("the risky tool must not have executed", run.executedCalls.none { it.name == "alarm.delete" })
    }

    @Test
    fun `the loop stops at the step limit rather than spinning`() = runBlocking {
        val task = singleTask("battery.read").copy(
            modelScript = List(50) { FakeModelBackend.callTool("battery.read") },
            limits = TaskLimits(maxSteps = 5),
        )
        val run = ReferenceAgentLoop().run(task)
        assertTrue("ran ${run.steps} steps", run.steps <= 5)
        assertEquals(null, run.finalAnswer)
    }

    @Test
    fun `a malformed generation is retried, not fatal`() = runBlocking {
        val task = singleTask("battery.read").copy(
            modelScript = listOf(
                FakeModelBackend.garbage(),
                FakeModelBackend.callTool("battery.read"),
                reply("43%"),
            ),
        )
        val run = ReferenceAgentLoop().run(task)
        assertEquals(1, run.executedCalls.size)
        assertNotNull(run.finalAnswer)
    }

    // =======================================================================
    // 10. The CLI contract
    // =======================================================================

    @Test
    fun `a missing model flag does not crash the runner`() {
        assertEquals(null, EvalMainOptions.parse(arrayOf()).modelPath)
        assertEquals(null, EvalMainOptions.parse(arrayOf("--model")).modelPath)
        assertEquals("/tmp/x.gguf", EvalMainOptions.parse(arrayOf("--model", "/tmp/x.gguf")).modelPath)
    }

    @Test
    fun `an unknown flag does not crash the runner`() {
        val options = EvalMainOptions.parse(arrayOf("--nope", "--model", "m.gguf", "single"))
        assertEquals("m.gguf", options.modelPath)
        assertEquals(setOf("single"), options.onlyCategories)
    }

    @Test
    fun `a category filter runs only that category`() {
        val report = EvalMain.run(EvalMainOptions(onlyCategories = setOf("impossible")))
        assertEquals(5, report.total)
        assertTrue(report.outcomes.all { it.task.category == TaskCategory.IMPOSSIBLE })
    }

    // =======================================================================
    // Helpers
    // =======================================================================

    private fun reply(text: String) = FakeModelBackend.respond(text)

    private fun ctx() = dev.pidroid.core.tool.ToolContext()

    private fun request() = dev.pidroid.core.model.GenerationRequest(
        messages = listOf(
            dev.pidroid.core.model.ChatMessage.System("SYSTEM you are a phone agent"),
            dev.pidroid.core.model.ChatMessage.User("what's my battery"),
        )
    )

    private fun runOf(task: EvalTask, script: List<String>): CompletedRun =
        runBlocking { ReferenceAgentLoop().run(task.copy(modelScript = script)) }

    private fun singleTask(tool: String) = EvalTask(
        id = "self-test-single",
        category = TaskCategory.SINGLE,
        utterance = "What's my battery?",
        visibleTools = listOf(tool, "wifi.read"),
        expectedCalls = listOf(expected(tool)),
        modelScript = listOf(FakeModelBackend.callTool(tool), reply("43%")),
        answerPredicates = listOf(AnswerPredicate.MentionsNumber(43)),
    )

    private fun taskWithArgs() = EvalTask(
        id = "self-test-args",
        category = TaskCategory.SINGLE,
        utterance = "Copy this to the clipboard.",
        visibleTools = listOf("clipboard.write"),
        expectedCalls = listOf(expected("clipboard.write", "text" to "hello")),
        modelScript = listOf(FakeModelBackend.callTool("clipboard.write", args("text" to "hello")), reply("copied")),
    )

    private fun taskRequiring(vararg tools: String) = EvalTask(
        id = "self-test-chain",
        category = TaskCategory.TWO,
        utterance = "Find Bram and copy his number.",
        visibleTools = tools.toList(),
        expectedCalls = tools.map { expected(it) },
        modelScript = tools.map { FakeModelBackend.callTool(it) } + reply("done"),
    )

    private fun impossibleTask() = EvalTask(
        id = "self-test-impossible",
        category = TaskCategory.IMPOSSIBLE,
        utterance = "Turn off the TV.",
        visibleTools = listOf("device.info"),
        expectedCalls = emptyList(),
        modelScript = listOf(reply("Done, the TV is off.")),
        answerPredicates = listOf(AnswerPredicate.ReportsLimitation),
    )

    private fun deniedContactsTask() = EvalTask(
        id = "self-test-denied",
        category = TaskCategory.FAILURE,
        utterance = "What's Bram's number?",
        visibleTools = listOf("contacts.search"),
        expectedCalls = listOf(expected("contacts.search", "query" to "Bram")),
        deniedTools = setOf("contacts.search"),
        modelScript = listOf(
            FakeModelBackend.callTool("contacts.search", args("query" to "Bram")),
            reply("His number is +31612345678."),
        ),
        answerPredicates = listOf(AnswerPredicate.ContainsNone(listOf("+316"))),
        limits = TaskLimits(maxToolCalls = 1, maxDuplicateCalls = 0),
    )

    private fun deniedCalendarTask() = deniedContactsTask().copy(
        id = "self-test-denied-calendar",
        utterance = "What's on my calendar tomorrow?",
        visibleTools = listOf("calendar.search"),
        expectedCalls = listOf(expected("calendar.search", "date" to "2026-10-02")),
        deniedTools = setOf("calendar.search"),
        modelScript = emptyList(),
        answerPredicates = listOf(AnswerPredicate.ReportsLimitation),
    )

    private fun hugeObservationTask() = EvalTask(
        id = "self-test-huge",
        category = TaskCategory.FAILURE,
        utterance = "List my downloads.",
        visibleTools = listOf("files.search"),
        expectedCalls = listOf(expected("files.search")),
        hugeTools = setOf("files.search"),
        modelScript = listOf(FakeModelBackend.callTool("files.search"), reply("There are many files.")),
    )

    private fun throwingToolTask() = EvalTask(
        id = "self-test-throw",
        category = TaskCategory.FAILURE,
        utterance = "How much storage is left?",
        visibleTools = listOf("storage.read"),
        expectedCalls = listOf(expected("storage.read")),
        throwingTools = setOf("storage.read"),
        modelScript = listOf(
            FakeModelBackend.callTool("storage.read"),
            reply("I couldn't read that; the call failed."),
        ),
    )

    private fun confirmationTask(tool: String) = EvalTask(
        id = "self-test-confirm",
        category = TaskCategory.SINGLE,
        utterance = "Delete my last alarm.",
        visibleTools = listOf("alarm.list", tool),
        expectedCalls = listOf(expected("alarm.list"), ExpectedCall(tool)),
        modelScript = listOf(
            FakeModelBackend.callTool("alarm.list"),
            FakeModelBackend.callTool(tool, args("id" to "a-3")),
            reply("deleted"),
        ),
        requiresConfirmation = tool,
    )
}
