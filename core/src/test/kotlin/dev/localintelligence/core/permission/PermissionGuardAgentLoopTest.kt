package dev.localintelligence.core.permission

import dev.localintelligence.core.tool.AgentTool
import dev.localintelligence.core.tool.ToolDefinition
import dev.localintelligence.core.tool.ToolResult
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The end-to-end claim of this package, in the shape the agent loop uses it.
 *
 * docs/architecture.md §18 lists "permission denied -> correct
 * ToolError.PermissionDenied, not a crash" as part of the definition of done.
 * This test wires a real `AgentTool` to a real `PermissionGuard` and asserts
 * the tool body never ran, which is stronger than asserting it returned an
 * error: a tool that executes and then fails has already done the thing the
 * permission was supposed to prevent.
 */
class PermissionGuardAgentLoopTest {

    private val calendar = Permission("android.permission.READ_CALENDAR")

    /** Counts its own invocations so the test can prove the guard stopped it. */
    private class CountingTool(
        override val definition: ToolDefinition,
    ) : AgentTool {
        var executions: Int = 0
            private set

        override suspend fun execute(
            args: dev.localintelligence.core.model.ToolArgs,
            context: dev.localintelligence.core.tool.ToolContext,
        ): ToolResult {
            executions++
            return ToolResult(success = true, observation = "3 events found")
        }
    }

    private fun tool(name: String = "calendar.search") = CountingTool(
        ToolDefinition(
            name = name,
            description = "Search the user's calendar for events.",
            category = "calendar",
            schema = buildJsonObject {
                put("type", "object")
                put(
                    "properties",
                    buildJsonObject { put("query", buildJsonObject { put("type", "string") }) },
                )
            },
            risk = dev.localintelligence.core.tool.ToolRisk.READ_ONLY,
            tags = setOf("calendar", "events"),
            requiredPermission = calendar.id,
        ),
    )

    private fun guardFor(state: PermissionState, agentTool: AgentTool) = PermissionGuard(
        broker = FakePermissionBroker(mutableMapOf(calendar to state)),
        askPolicy = PermissionAskPolicy(),
        requirements = DefaultPermissionRequirementSource(
            mapOf(
                agentTool.definition.name to listOf(
                    PermissionRequirement(calendar, "your calendar"),
                ),
            ),
        ),
    )

    @Test
    fun `a granted tool runs normally`() = runTest {
        val agentTool = tool()
        val g = guardFor(PermissionState.GRANTED, agentTool)

        assertEquals(PermissionGuard.Verdict.Proceed, g.check(agentTool.definition.name))
        val result = agentTool.execute(buildJsonObject { }, dev.localintelligence.core.tool.ToolContext())
        assertTrue(result.success)
        assertEquals(1, agentTool.executions)
    }

    @Test
    fun `a denied tool is blocked BEFORE the body executes`() = runTest {
        val agentTool = tool()
        val g = guardFor(PermissionState.DENIED, agentTool)

        val verdict = g.check(agentTool.definition.name)
        assertTrue(verdict is PermissionGuard.Verdict.Denied)
        assertEquals("the tool body must not have run", 0, agentTool.executions)

        // If the loop still called it anyway, the denial is the answer it gets.
        val result = (verdict as PermissionGuard.Verdict.Denied).denial.toToolResult()
        assertFalse(result.success)
        assertTrue(result.error is dev.localintelligence.core.tool.ToolError.PermissionDenied)
    }

    @Test
    fun `a permanently denied tool is blocked before execution and is terminal`() = runTest {
        val agentTool = tool()
        val g = guardFor(PermissionState.DENIED_PERMANENTLY, agentTool)

        val denial = (g.check(agentTool.definition.name)
            as PermissionGuard.Verdict.Denied).denial
        assertEquals(0, agentTool.executions)
        assertTrue(denial.isTerminal)
        assertTrue(
            "the model must be told not to retry",
            denial.observation.lowercase().contains("do not retry"),
        )
    }

    @Test
    fun `a full agent run against a denied tool never reaches the tool body`() = runTest {
        // The loop-forever scenario, made executable: repeated attempts against
        // a permanently denied permission produce no side effects and no
        // prompts after the first.
        val agentTool = tool()
        val policy = PermissionAskPolicy(maxAsksPerPermission = 2)
        val g = PermissionGuard(
            broker = FakePermissionBroker(
                mutableMapOf(calendar to PermissionState.DENIED_PERMANENTLY),
            ),
            askPolicy = policy,
            requirements = DefaultPermissionRequirementSource(
                mapOf(
                    agentTool.definition.name to listOf(
                        PermissionRequirement(calendar, "your calendar"),
                    ),
                ),
            ),
        )

        var prompts = 0
        repeat(14) {
            val denial = (g.check(agentTool.definition.name)
                as PermissionGuard.Verdict.Denied).denial
            if (denial.decision is PermissionAskPolicy.Decision.Ask) {
                prompts++
                policy.tryAsk(calendar, PermissionState.DENIED)
            }
        }
        assertEquals("14 attempts must not produce 14 dialogs", 0, prompts)
        assertEquals("14 attempts must not execute the tool", 0, agentTool.executions)
    }

    @Test
    fun `the denial observation satisfies the frozen tool-contract rules`() = runTest {
        val agentTool = tool()
        val g = guardFor(PermissionState.DENIED, agentTool)
        val denial = (g.check(agentTool.definition.name)
            as PermissionGuard.Verdict.Denied).denial
        val observation = denial.toToolResult().observation

        assertTrue(
            "under the general 2048 budget",
            observation.length <= dev.localintelligence.core.tool.ObservationTruncator
                .DEFAULT_BUDGET_CHARS,
        )
        assertTrue(
            "under the tighter permission budget",
            observation.length <= PermissionObservation.BUDGET_CHARS,
        )
        assertFalse("no stack trace", observation.contains("\tat "))
        assertFalse("no exception class", observation.contains("SecurityException"))
        assertTrue(PermissionObservation.containsNoPlatformNoise(observation))
    }
}
