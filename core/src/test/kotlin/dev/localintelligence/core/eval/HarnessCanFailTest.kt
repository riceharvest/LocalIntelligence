package dev.localintelligence.core.eval

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves the suite is capable of FAILING.
 *
 * A harness that only ever reports 100% is indistinguishable from one that checks
 * nothing. Each test below deliberately breaks something and asserts the score
 * moves. That is what makes the headline number mean something.
 *
 * The mutations are applied to a `CompletedRun` value, never to the real loop, so
 * nothing here can weaken production code.
 */
class HarnessCanFailTest {

    private fun score(task: EvalTask, run: CompletedRun) = EvalScorer().score(task, run)

    private fun runReal(task: EvalTask) =
        runBlocking { RealAgentControllerRunner().run(task) }

    @Test
    fun `the real AgentController passes the full suite`() {
        val failures = TaskSuite.all().mapNotNull { task ->
            val outcome = score(task, runReal(task))
            if (outcome.passed) null else "${task.id}: ${outcome.failures}"
        }
        assertEquals(
            "the real loop must pass every task; failures:\n" + failures.joinToString("\n"),
            0,
            failures.size,
        )
    }

    @Test
    fun `swapping the executed tool fails the task`() {
        val task = TaskSuite.all().first { it.id == "battery-level" }
        val run = runReal(task)
        assertTrue("baseline must pass, got ${score(task, run).failures}", score(task, run).passed)

        val mutated = run.copy(
            executedCalls = run.executedCalls.map { it.copy(name = "clock.read") },
        )
        val after = score(task, mutated)
        assertTrue("a wrong tool must fail, got: ${after.failures}", !after.passed)
    }

    @Test
    fun `an unnecessary extra tool call fails the task`() {
        val task = TaskSuite.all().first { it.id == "battery-level" }
        val run = runReal(task)
        assertTrue(score(task, run).passed)

        val mutated = run.copy(
            executedCalls = run.executedCalls + ExecutedCall(
                "device.battery",
                dev.localintelligence.core.model.ToolArgs(emptyMap()),
            ),
        )
        assertTrue("an extra call must fail", !score(task, mutated).passed)
    }

    @Test
    fun `a missing final answer fails a task that requires one`() {
        val task = TaskSuite.all().first { it.id == "battery-level" }
        val run = runReal(task)
        assertTrue(score(task, run).passed)

        assertTrue("no answer must fail", !score(task, run.copy(finalAnswer = null)).passed)
    }

    @Test
    fun `a loop that never invokes a tool fails`() {
        val task = TaskSuite.all().first { it.id == "battery-level" }
        val run = runReal(task)
        assertTrue(score(task, run).passed)

        val inert = run.copy(executedCalls = emptyList(), invokedCalls = emptyList())
        assertTrue("an inert loop must fail", !score(task, inert).passed)
    }

    @Test
    fun `a loop that loops forever is caught by the step limit`() {
        // A model that keeps asking for the same thing, forever. The runtime must
        // terminate it. If the loop detector or the step limit were removed, this
        // test would hang rather than pass — which is the point.
        val task = TaskSuite.all().first { it.id == "battery-level" }
        val repetitive = task.copy(
            modelScript = List(40) { FakeModelBackend.callTool("battery.read") },
        )
        val outcome = score(repetitive, runReal(repetitive))
        assertTrue(
            "a runaway loop must not count as success, got: ${outcome.failures}",
            !outcome.passed,
        )
    }

    @Test
    fun `a loop that skips the confirmation gate fails`() {
        val task = TaskSuite.all().first { it.id == "search-file-then-share" }
        assertTrue("this task must require confirmation", task.requiresConfirmation != null)

        val run = runReal(task)
        assertTrue("baseline must pass", score(task, run).passed)

        // Claim the gate fired for the wrong tool, or not at all.
        val bypassed = run.copy(awaitingConfirmationFor = null)
        val after = score(task, bypassed)
        assertTrue(
            "executing a risky tool without asking must fail, got: ${after.failures}",
            !after.passed,
        )
    }

    @Test
    fun `dropping a required argument fails the task`() {
        // Must be a task whose expected call HAS arguments, otherwise stripping
        // them is a no-op and the test proves nothing.
        val task = TaskSuite.all().first { t ->
            t.expectedCalls.any { it.args.isNotEmpty() }
        }
        val run = runReal(task)
        val before = score(task, run)
        assertTrue("baseline must pass, got ${before.failures}", before.passed)

        val stripped = run.copy(
            executedCalls = run.executedCalls.map {
                it.copy(args = dev.localintelligence.core.model.ToolArgs(emptyMap()))
            },
        )
        val after = score(task, stripped)
        assertTrue(
            "dropping a required argument must fail, got: ${after.failures}",
            !after.passed,
        )
    }

    @Test
    fun `the suite has the specified category counts`() {
        val all = TaskSuite.all()
        assertEquals(50, all.size)
        assertEquals(10, all.count { it.category == TaskCategory.SINGLE })
        assertEquals(10, all.count { it.category == TaskCategory.TWO })
        assertEquals(10, all.count { it.category == TaskCategory.MULTI })
        assertEquals(5, all.count { it.category == TaskCategory.MEMORY })
        assertEquals(5, all.count { it.category == TaskCategory.AMBIGUITY })
        assertEquals(5, all.count { it.category == TaskCategory.FAILURE })
        assertEquals(5, all.count { it.category == TaskCategory.IMPOSSIBLE })
    }

    @Test
    fun `task ids are unique`() {
        val ids = TaskSuite.all().map { it.id }
        assertEquals("duplicate ids would corrupt the report", ids.size, ids.toSet().size)
    }

    @Test
    fun `the reference loop and the real loop agree on every task`() {
        // The reference double is kept so it cannot rot. If a change to the real
        // loop silently breaks a behaviour the tasks depend on, the two disagree
        // and this fails.
        val disagreements = TaskSuite.all().mapNotNull { task ->
            val real = score(task, runReal(task)).passed
            val reference = score(
                task,
                runBlocking { ReferenceAgentLoop().run(task) },
            ).passed
            if (real != reference) task.id else null
        }
        assertEquals(
            "the real loop and the reference loop disagree on: ${disagreements.joinToString()}",
            0,
            disagreements.size,
        )
    }
}
