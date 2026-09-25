package dev.localintelligence.core.policy

import dev.localintelligence.core.policy.Fixtures.args
import dev.localintelligence.core.policy.Fixtures.tool
import dev.localintelligence.core.tool.ToolRisk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Blast-radius and rate limits.
 *
 * The threat: every individual call looks fine. A delete of one file, approved
 * once, is reasonable. Thirty of them in a row is a wiped photo library, and no
 * confirmation dialog ever mentioned it. Limits have to be counted somewhere the
 * model cannot see or influence.
 */
class BlastRadiusTest {

    private fun policyWith(limits: PolicyConfig) = RiskPolicy(limits)

    @Test
    fun `a task can only perform a bounded number of destructive actions`() {
        val policy = policyWith(PolicyConfig.default.copy(maxDestructivePerTask = 3))
        val tool = tool("files.delete", ToolRisk.DESTRUCTIVE, props = mapOf("path" to "string"))

        repeat(3) { i ->
            val d = policy.evaluate(tool, args("path" to "/data/data/com.example/files/f$i.txt"))
            assertEquals("delete #$i should still be confirmable", PolicyOutcome.REQUIRE_CONFIRMATION, d.outcome)
            // A confirmed destructive action spends destructive budget.
            policy.recordConfirmed(ToolRisk.DESTRUCTIVE)
        }

        val fourth = policy.evaluate(tool, args("path" to "/data/data/com.example/files/f3.txt"))
        assertEquals("the fourth delete in one task is refused", PolicyOutcome.BLOCK, fourth.outcome)
        assertEquals(PolicyRule.LIMIT_EXCEEDED, fourth.rule)
    }

    @Test
    fun `read-only calls do not consume the destructive budget`() {
        val policy = policyWith(PolicyConfig.default.copy(maxDestructivePerTask = 1))
        val read = tool("files.read", ToolRisk.READ_ONLY, props = mapOf("path" to "string"))
        val del = tool("files.delete", ToolRisk.DESTRUCTIVE, props = mapOf("path" to "string"))

        repeat(50) { i ->
            policy.evaluate(read, args("path" to "/data/data/com.example/files/f$i.txt"))
            policy.recordExecuted(ToolRisk.READ_ONLY)
        }
        val d = policy.evaluate(del, args("path" to "/data/data/com.example/files/x.txt"))
        assertEquals("reads must not starve a delete of its budget", PolicyOutcome.REQUIRE_CONFIRMATION, d.outcome)
    }

    @Test
    fun `a denied call does not consume budget`() {
        val policy = policyWith(PolicyConfig.default.copy(maxActionsPerTask = 2))
        val privileged = tool("device.root", ToolRisk.PRIVILEGED)
        repeat(20) { policy.evaluate(privileged, args()) }

        val read = tool("battery.read", ToolRisk.READ_ONLY)
        val d = policy.evaluate(read, args())
        assertEquals("a blocked call must not count against the action limit", PolicyOutcome.EXECUTE, d.outcome)
    }

    @Test
    fun `the total action limit eventually denies even safe calls`() {
        val policy = policyWith(PolicyConfig.default.copy(maxActionsPerTask = 5))
        val read = tool("battery.read", ToolRisk.READ_ONLY)
        repeat(5) { policy.recordExecuted(ToolRisk.READ_ONLY) }

        val d = policy.evaluate(read, args())
        assertEquals(PolicyOutcome.BLOCK, d.outcome)
        assertEquals(PolicyRule.LIMIT_EXCEEDED, d.rule)
    }

    @Test
    fun `resetting a task restores the budget`() {
        val policy = policyWith(PolicyConfig.default.copy(maxDestructivePerTask = 1))
        val del = tool("files.delete", ToolRisk.DESTRUCTIVE, props = mapOf("path" to "string"))

        policy.recordConfirmed(ToolRisk.DESTRUCTIVE)
        assertEquals(PolicyOutcome.BLOCK, policy.evaluate(del, args("path" to "/data/data/a/1")).outcome)

        policy.resetTask()
        assertEquals(
            "a new task must not inherit the previous task's headroom",
            PolicyOutcome.REQUIRE_CONFIRMATION,
            policy.evaluate(del, args("path" to "/data/data/a/1")).outcome,
        )
    }

    @Test
    fun `the denial reason explains the limit in the user's terms`() {
        val policy = policyWith(PolicyConfig.default.copy(maxDestructivePerTask = 1))
        policy.recordConfirmed(ToolRisk.DESTRUCTIVE)
        val d = policy.evaluate(tool("files.delete", ToolRisk.DESTRUCTIVE), args("path" to "/data/data/a/1"))
        assertTrue("must name the tier: ${d.justification}", d.justification.contains("destructive"))
        assertTrue("must state the limit: ${d.justification}", d.justification.contains("the limit is 1"))
    }

    @Test
    fun `a permissive limit config does not make destructive auto-execute`() {
        val wide = PolicyConfig.default.copy(
            maxActionsPerTask = Int.MAX_VALUE,
            maxDestructivePerTask = Int.MAX_VALUE,
            destructiveBulkBlockThreshold = Int.MAX_VALUE,
            bulkThreshold = Int.MAX_VALUE,
            autoExecuteTiers = ToolRisk.entries.toSet(),
        )
        val policy = policyWith(wide)
        val d = policy.evaluate(
            tool("files.delete", ToolRisk.DESTRUCTIVE, props = mapOf("path" to "string")),
            args("path" to "/data/data/com.example/files/a.txt"),
        )
        assertNotEquals("a wide limit config must not silently execute deletes", PolicyOutcome.EXECUTE, d.outcome)
    }
}

/**
 * The properties that must hold for EVERY input, not just the cases someone
 * thought to write down.
 *
 * Uses a deterministic seeded generator rather than a random one: a safety
 * regression should reproduce on the same seed in CI tomorrow, and a property
 * test that changes its input every run is a flaky safety test, which is worse
 * than no test.
 */
class PolicyPropertyTest {

    /**
     * A tiny linear congruential generator. Not for cryptography — for
     * reproducible fuzzing with no dependency.
     */
    private class Lcg(seed: Long) {
        private var state = seed
        fun next(bound: Int): Int {
            state = (state * 6364136223846793005L + 1442695040888963407L)
            val v = (state ushr 33).toInt()
            return if (bound <= 0) 0 else ((v % bound) + bound) % bound
        }

        fun pick(values: List<String>): String = values[next(values.size)]
    }

    private val toolNames = listOf(
        "files.delete", "sms.send", "calendar.delete", "files.share", "device.root",
        "battery.read", "calendar.create", "files.read", "notification.post",
    )

    private val risks = listOf(
        ToolRisk.READ_ONLY, ToolRisk.REVERSIBLE, ToolRisk.DESTRUCTIVE,
        ToolRisk.EXTERNAL_COMMUNICATION, ToolRisk.PRIVILEGED,
    )

    private val paths = listOf(
        "/data/data/com.example/files/a.txt",
        "/storage/emulated/0/Documents/b.pdf",
        "../../etc/passwd",
        "/data/data/com.example/../other/c.txt",
        "",
        "relative/path.txt",
    )

    private val targets = listOf("+31611111111", "mom", "", "https://evil.example", "unknown-contact")

    private val bodies = listOf("running late", "https://bit.ly/abc", "", "see https://example.com/x for details")

    private fun randomArgs(rng: Lcg): Map<String, Any?> = buildMap {
        if (rng.next(4) > 0) put("path", paths[rng.next(paths.size)])
        if (rng.next(3) > 0) put("to", targets[rng.next(targets.size)])
        if (rng.next(3) > 0) put("body", bodies[rng.next(bodies.size)])
        if (rng.next(4) == 0) put("count", rng.next(500) - 100)
        if (rng.next(5) == 0) put("paths", (1..(1 + rng.next(300))).map { "/data/data/com.example/files/f$it" })
    }

    @Test
    fun `no input ever produces EXECUTE for a destructive tool`() {
        val policy = RiskPolicy()
        repeat(20_000) { seed ->
            val rng = Lcg(seed.toLong())
            val name = rng.pick(toolNames)
            val risk = risks[rng.next(risks.size)]
            if (risk != ToolRisk.DESTRUCTIVE) return@repeat

            val decision = policy.evaluate(tool(name, risk, props = mapOf("path" to "string")), args(*randomArgs(rng).toList().toTypedArray()))
            assertNotEquals(
                "seed=$seed ${Fixtures.render(decision)}",
                PolicyOutcome.EXECUTE,
                decision.outcome,
            )
        }
    }

    @Test
    fun `no input ever produces EXECUTE for a privileged tool`() {
        val policy = RiskPolicy()
        repeat(20_000) { seed ->
            val rng = Lcg(seed.toLong())
            val decision = policy.evaluate(
                tool(rng.pick(toolNames), ToolRisk.PRIVILEGED),
                args(*randomArgs(rng).toList().toTypedArray()),
            )
            assertNotEquals("seed=$seed", PolicyOutcome.EXECUTE, decision.outcome)
            assertEquals("seed=$seed", PolicyRule.PRIVILEGED_DISABLED, decision.rule)
        }
    }

    @Test
    fun `every decision carries a justification that a human can read`() {
        val policy = RiskPolicy()
        repeat(20_000) { seed ->
            val rng = Lcg(seed.toLong())
            val decision = policy.evaluate(
                tool(rng.pick(toolNames), rng.let { risks[it.next(risks.size)] }),
                args(*randomArgs(rng).toList().toTypedArray()),
            )
            assertTrue("seed=$seed produced a blank justification", decision.justification.isNotBlank())
            assertTrue("seed=$seed produced a too-short justification", decision.justification.length >= 20)
            assertTrue("seed=$seed produced an unfinished sentence", decision.justification.trim().endsWith("."))
        }
    }

    @Test
    fun `the default config denies whenever it is unsure`() {
        val defaultPolicy = RiskPolicy()
        // A path the app does not own is not "inside app storage" by default,
        // so even a destructive call is held rather than assumed safe.
        assertEquals(
            PolicyOutcome.REQUIRE_CONFIRMATION,
            defaultPolicy.evaluate(tool("mystery.tool", ToolRisk.DESTRUCTIVE), args("path" to "/x")).outcome,
        )
        assertEquals(
            "a path outside app storage must be escalated, not waved through",
            PolicyRule.PATH_ESCAPES_APP_STORAGE,
            defaultPolicy.evaluate(tool("mystery.tool", ToolRisk.DESTRUCTIVE), args("path" to "/x")).rule,
        )
        // And nothing is trusted or granted until the caller says so.
        assertFalse(
            "an empty knownTargets set must not imply trust",
            PolicyConfig.default.knownTargets.contains("+31611111111"),
        )
        assertTrue(PolicyConfig.default.grantedPermissions.isEmpty())
    }

    @Test
    fun `evaluation is deterministic across repeated runs`() {
        val first = RiskPolicy()
        val second = RiskPolicy()
        repeat(5_000) { seed ->
            val rngA = Lcg(seed.toLong())
            val rngB = Lcg(seed.toLong())
            val toolA = tool(rngA.pick(toolNames), risks[rngA.next(risks.size)])
            val toolB = tool(rngB.pick(toolNames), risks[rngB.next(risks.size)])
            val a = first.evaluate(toolA, args(*randomArgs(rngA).toList().toTypedArray()))
            val b = second.evaluate(toolB, args(*randomArgs(rngB).toList().toTypedArray()))
            assertEquals("seed=$seed diverged", a, b)
        }
    }
}
