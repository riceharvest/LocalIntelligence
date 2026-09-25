package dev.localintelligence.core.policy

import dev.localintelligence.core.policy.Fixtures.args
import dev.localintelligence.core.policy.Fixtures.render
import dev.localintelligence.core.policy.Fixtures.tool
import dev.localintelligence.core.tool.ToolRisk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The risk-tier matrix from `docs/architecture.md` §8, one test per row.
 *
 * These are the tests that would have caught a policy engine that quietly
 * widened. Each asserts the exact [PolicyOutcome] AND the [PolicyRule] that
 * produced it, so a test cannot pass because the right answer arrived for the
 * wrong reason.
 */
class RiskTierTest {

    private val policy = RiskPolicy()

    // ------------------------------------------------------------- executes

    @Test
    fun `read-only executes without asking`() {
        val d = policy.evaluate(tool("battery.read", ToolRisk.READ_ONLY), args())
        assertEquals(PolicyOutcome.EXECUTE, d.outcome)
        assertEquals(PolicyRule.RISK_TIER, d.rule)
    }

    @Test
    fun `reversible executes without asking`() {
        val d = policy.evaluate(
            tool("calendar.create", ToolRisk.REVERSIBLE, props = mapOf("title" to "string")),
            args("title" to "Lunch"),
        )
        assertEquals(PolicyOutcome.EXECUTE, d.outcome)
    }

    // ------------------------------------------------------------- confirms

    @Test
    fun `destructive confirms`() {
        val d = policy.evaluate(
            tool("files.delete", ToolRisk.DESTRUCTIVE, props = mapOf("path" to "string")),
            args("path" to "/data/data/com.example/files/notes.txt"),
        )
        assertEquals(PolicyOutcome.REQUIRE_CONFIRMATION, d.outcome)
        assertEquals(PolicyRule.RISK_TIER, d.rule)
    }

    @Test
    fun `external communication to a trusted target confirms`() {
        val trusted = RiskPolicy(
            PolicyConfig.default.copy(knownTargets = setOf("+31612345678")),
        )
        val d = trusted.evaluate(
            tool("sms.send", ToolRisk.EXTERNAL_COMMUNICATION, props = mapOf("to" to "string", "body" to "string")),
            args("to" to "+31612345678", "body" to "Running ten minutes late, order without me"),
        )
        assertEquals(PolicyOutcome.REQUIRE_CONFIRMATION, d.outcome)
        assertEquals(PolicyRule.RISK_TIER, d.rule)
    }

    // --------------------------------------------------------------- blocks

    @Test
    fun `privileged is blocked and is not confirmable`() {
        val d = policy.evaluate(tool("device.root", ToolRisk.PRIVILEGED), args())
        assertEquals(PolicyOutcome.BLOCK, d.outcome)
        assertEquals(PolicyRule.PRIVILEGED_DISABLED, d.rule)
        assertFalse(d.requiresConfirmation)
    }

    @Test
    fun `privileged stays blocked even when the config tries to auto-execute it`() {
        // The dangerous config: someone widens autoExecuteTiers to everything.
        val reckless = RiskPolicy(
            PolicyConfig.default.copy(
                autoExecuteTiers = ToolRisk.entries.toSet(),
            ),
        )
        val d = reckless.evaluate(tool("device.shizuku", ToolRisk.PRIVILEGED), args())
        assertEquals(PolicyOutcome.BLOCK, d.outcome)
        assertEquals(PolicyRule.PRIVILEGED_DISABLED, d.rule)
    }

    @Test
    fun `privileged stays blocked even when a custom rule returns EXECUTE`() {
        val withRule = RiskPolicy(
            PolicyConfig.default.copy(
                rules = listOf(
                    PolicyRuleOverride { _, _, _ ->
                        PolicyDecisionFixtures.executeFor("device.root", ToolRisk.PRIVILEGED)
                    },
                ),
            ),
        )
        val d = withRule.evaluate(tool("device.root", ToolRisk.PRIVILEGED), args())
        assertEquals("a custom rule must not be able to run a privileged tool", PolicyOutcome.BLOCK, d.outcome)
    }

    @Test
    fun `a permission the host has not granted requires permission, not a block`() {
        val d = policy.evaluate(
            tool("contacts.search", ToolRisk.READ_ONLY, permission = "android.permission.READ_CONTACTS"),
            args("query" to "dario"),
        )
        assertEquals(PolicyOutcome.REQUIRE_PERMISSION, d.outcome)
        assertEquals(PolicyRule.PERMISSION_NOT_GRANTED, d.rule)
    }

    @Test
    fun `a granted permission lets the call through`() {
        val granted = RiskPolicy(
            PolicyConfig.default.copy(grantedPermissions = setOf("android.permission.READ_CONTACTS")),
        )
        val d = granted.evaluate(
            tool("contacts.search", ToolRisk.READ_ONLY, permission = "android.permission.READ_CONTACTS"),
            args("query" to "dario"),
        )
        assertEquals(PolicyOutcome.EXECUTE, d.outcome)
    }
}

/** Decisions hand-built for rule-overload tests, where the engine must clamp them. */
internal object PolicyDecisionFixtures {
    fun executeFor(toolName: String, risk: ToolRisk): Decision = Decision(
        outcome = PolicyOutcome.EXECUTE,
        toolName = toolName,
        risk = risk,
        rule = PolicyRule.CUSTOM,
        justification = "A rule insists this is safe to run unattended.",
        arguments = Fixtures.args(),
        definition = Fixtures.tool(toolName, risk),
    )
}
