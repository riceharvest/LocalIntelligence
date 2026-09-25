package dev.localintelligence.core.policy

import dev.localintelligence.core.policy.Fixtures.args
import dev.localintelligence.core.policy.Fixtures.tool
import dev.localintelligence.core.tool.ToolRisk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Caller-supplied configuration, and the clamp that keeps it from becoming a
 * back door.
 *
 * A policy engine whose rules can be loosened by a config file is not a policy
 * engine. These tests pin the asymmetry: configuration can make the harness
 * *stricter*, never more permissive than the built-in floor.
 */
class PolicyConfigTest {

    private val readTool = tool("files.read", ToolRisk.READ_ONLY, props = mapOf("path" to "string"))
    private val deleteTool = tool("files.delete", ToolRisk.DESTRUCTIVE, props = mapOf("path" to "string"))

    @Test
    fun `a custom rule can tighten an execute into a confirmation`() {
        val strict = RiskPolicy(
            PolicyConfig.default.copy(
                rules = listOf(
                    PolicyRuleOverride { definition, a, current ->
                        if (definition.name == "files.read") {
                            current.copy(
                                outcome = PolicyOutcome.REQUIRE_CONFIRMATION,
                                rule = PolicyRule.CUSTOM,
                                justification = "Reading is held by this installation's policy.",
                            )
                        } else {
                            null
                        }
                    },
                ),
            ),
        )
        val d = strict.evaluate(readTool, args("path" to "/data/data/com.example/files/a.txt"))
        assertEquals(PolicyOutcome.REQUIRE_CONFIRMATION, d.outcome)
        assertEquals(PolicyRule.CUSTOM, d.rule)
    }

    @Test
    fun `a custom rule cannot loosen a confirmation into an execute`() {
        val reckless = RiskPolicy(
            PolicyConfig.default.copy(
                rules = listOf(
                    PolicyRuleOverride { definition, a, current ->
                        if (definition.name == "files.delete") {
                            current.copy(
                                outcome = PolicyOutcome.EXECUTE,
                                rule = PolicyRule.CUSTOM,
                                justification = "A rule says deletes are fine.",
                            )
                        } else {
                            null
                        }
                    },
                ),
            ),
        )
        val d = reckless.evaluate(deleteTool, args("path" to "/data/data/com.example/files/a.txt"))
        assertEquals("a rule must not be able to authorise a delete", PolicyOutcome.REQUIRE_CONFIRMATION, d.outcome)
        assertTrue(
            "the trace must say the attempt to weaken was ignored",
            d.justification.contains("ignored"),
        )
    }

    @Test
    fun `a throwing custom rule blocks rather than opening the gate`() {
        val brittle = RiskPolicy(
            PolicyConfig.default.copy(
                rules = listOf(
                    PolicyRuleOverride { _, _, _ -> throw IllegalStateException("boom") },
                ),
            ),
        )
        val d = brittle.evaluate(readTool, args("path" to "/data/data/com.example/files/a.txt"))
        assertEquals(PolicyOutcome.BLOCK, d.outcome)
        assertEquals(PolicyRule.CUSTOM, d.rule)
        assertTrue(d.justification.contains("IllegalStateException"))
    }

    @Test
    fun `a custom rule cannot reach a privileged tool`() {
        val d = RiskPolicy(
            PolicyConfig.default.copy(
                rules = listOf(
                    PolicyRuleOverride { definition, a, current ->
                        current.copy(
                            outcome = PolicyOutcome.EXECUTE,
                            justification = "please",
                            rule = PolicyRule.CUSTOM,
                        )
                    },
                ),
            ),
        ).evaluate(tool("device.root", ToolRisk.PRIVILEGED), args())
        assertEquals(PolicyOutcome.BLOCK, d.outcome)
    }

    @Test
    fun `bulk threshold is caller-configurable in the safe direction`() {
        val strict = RiskPolicy(PolicyConfig.default.copy(bulkThreshold = 1))
        val d = strict.evaluate(
            tool("files.delete", ToolRisk.DESTRUCTIVE, props = mapOf("paths" to "array")),
            args("paths" to listOf("/data/data/com.example/files/a.txt", "/data/data/com.example/files/b.txt")),
        )
        assertEquals(PolicyRule.BULK_OPERATION, d.rule)
    }

    @Test
    fun `a custom path argument name is honoured`() {
        val custom = RiskPolicy(PolicyConfig.default.copy(pathArgumentNames = setOf("filepath")))
        val d = custom.evaluate(
            tool("files.delete", ToolRisk.DESTRUCTIVE, props = mapOf("filepath" to "string")),
            args("filepath" to "/storage/emulated/0/Documents/salaries.csv"),
        )
        assertEquals(PolicyRule.PATH_ESCAPES_APP_STORAGE, d.rule)
    }

    @Test
    fun `a rule that abstains leaves the built-in decision alone`() {
        val abstaining = RiskPolicy(
            PolicyConfig.default.copy(rules = listOf(PolicyRuleOverride { _, _, _ -> null })),
        )
        val withRule = abstaining.evaluate(deleteTool, args("path" to "/data/data/com.example/files/a.txt"))
        val without = RiskPolicy().evaluate(deleteTool, args("path" to "/data/data/com.example/files/a.txt"))
        assertEquals(without.outcome, withRule.outcome)
        assertEquals(PolicyRule.RISK_TIER, withRule.rule)
    }

    @Test
    fun `the default config is the strict one`() {
        // If someone ever edits the shipped defaults into something permissive,
        // this fails and the diff gets reviewed.
        assertEquals(PolicyConfig.DEFAULT_MAX_DESTRUCTIVE_PER_TASK, PolicyConfig.default.maxDestructivePerTask)
        assertEquals(
            setOf(ToolRisk.READ_ONLY, ToolRisk.REVERSIBLE),
            PolicyConfig.default.autoExecuteTiers,
        )
        assertTrue("no targets are trusted until the user says so", PolicyConfig.default.knownTargets.isEmpty())
        assertTrue("no permissions are granted until the host says so", PolicyConfig.default.grantedPermissions.isEmpty())
    }

    @Test
    fun `a decision cannot be constructed without a justification`() {
        val threw = try {
            Decision(
                outcome = PolicyOutcome.EXECUTE,
                toolName = "files.delete",
                risk = ToolRisk.DESTRUCTIVE,
                rule = PolicyRule.RISK_TIER,
                justification = "  ",
                arguments = args(),
                definition = deleteTool,
            )
            false
        } catch (e: IllegalArgumentException) {
            true
        }
        assertTrue("an unjustified Decision must be impossible to construct", threw)
    }

    @Test
    fun `restrictiveness orders the outcomes from open to closed`() {
        assertTrue(
            Decision.restrictiveness(PolicyOutcome.BLOCK) >
                Decision.restrictiveness(PolicyOutcome.REQUIRE_CONFIRMATION),
        )
        assertTrue(
            Decision.restrictiveness(PolicyOutcome.REQUIRE_CONFIRMATION) >
                Decision.restrictiveness(PolicyOutcome.REQUIRE_PERMISSION),
        )
        assertTrue(
            Decision.restrictiveness(PolicyOutcome.REQUIRE_PERMISSION) >
                Decision.restrictiveness(PolicyOutcome.EXECUTE),
        )
    }
}
