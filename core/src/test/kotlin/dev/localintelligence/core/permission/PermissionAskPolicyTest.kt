package dev.localintelligence.core.permission

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bounded ask policy: the thing that stops the loop spamming dialogs.
 *
 * No wall clock anywhere in this file, on purpose. The policy is a counter, and
 * these tests prove it is a counter by calling it in a tight loop with no delay
 * and asserting the bound still holds.
 */
class PermissionAskPolicyTest {

    private val perm = Permission("android.permission.READ_CALENDAR")

    @Test
    fun `a granted permission never needs a prompt`() {
        val policy = PermissionAskPolicy()
        assertEquals(
            PermissionAskPolicy.Decision.Allowed,
            policy.decide(perm, PermissionState.GRANTED),
        )
        assertEquals(0, policy.totalAsks())
    }

    @Test
    fun `a soft denial is askable until the budget runs out`() {
        val policy = PermissionAskPolicy(maxAsksPerPermission = 2)

        // The first ask is offered, and taking it spends one prompt.
        assertTrue(policy.decide(perm, PermissionState.DENIED) is PermissionAskPolicy.Decision.Ask)
        assertTrue(policy.tryAsk(perm, PermissionState.DENIED))
        assertEquals(1, policy.asksFor(perm))

        val second = policy.decide(perm, PermissionState.DENIED)
        assertTrue(second is PermissionAskPolicy.Decision.Ask)
        assertTrue(policy.tryAsk(perm, PermissionState.DENIED))
        assertEquals(2, policy.asksFor(perm))

        // Budget spent: refuse, permanently, with the reason that tells the
        // model to stop rather than to try again.
        val third = policy.decide(perm, PermissionState.DENIED)
        assertEquals(
            PermissionAskPolicy.Decision.Refuse(PermissionAskPolicy.RefusalReason.ASK_BUDGET_SPENT),
            third,
        )
    }

    @Test
    fun `a permanent denial is refused even with a full budget`() {
        val policy = PermissionAskPolicy(maxAsksPerPermission = 10)
        repeat(10) { policy.tryAsk(perm, PermissionState.DENIED) }
        val decision = policy.decide(perm, PermissionState.DENIED_PERMANENTLY)
        assertEquals(
            PermissionAskPolicy.Decision.Refuse(
                PermissionAskPolicy.RefusalReason.PERMANENTLY_DENIED,
            ),
            decision,
        )
    }

    @Test
    fun `a not-applicable permission is refused and never prompted`() {
        val policy = PermissionAskPolicy()
        assertEquals(
            PermissionAskPolicy.Decision.Refuse(PermissionAskPolicy.RefusalReason.NOT_APPLICABLE),
            policy.decide(perm, PermissionState.NOT_APPLICABLE),
        )
        assertEquals(0, policy.totalAsks())
    }

    @Test
    fun `repeated attempts do not spam - the prompt count is bounded`() {
        // 1000 attempts in a tight loop, no delay, no fake clock. The bound must
        // come from the counter alone.
        val policy = PermissionAskPolicy(maxAsksPerPermission = 2)
        var prompted = 0
        repeat(1000) {
            val decision = policy.decide(perm, PermissionState.DENIED)
            if (decision is PermissionAskPolicy.Decision.Ask) {
                prompted++
                policy.tryAsk(perm, PermissionState.DENIED)
            }
        }
        assertEquals("1000 attempts must produce exactly the budget", 2, prompted)
        assertEquals(2, policy.totalAsks())
    }

    @Test
    fun `the budget is per permission, not global`() {
        val policy = PermissionAskPolicy(maxAsksPerPermission = 1)
        val a = Permission("android.permission.READ_CALENDAR")
        val b = Permission("android.permission.READ_CONTACTS")

        policy.tryAsk(a, PermissionState.DENIED)
        assertTrue(policy.decide(a, PermissionState.DENIED) is PermissionAskPolicy.Decision.Refuse)
        // b has not been asked for, so it still has its own budget.
        assertTrue(policy.decide(b, PermissionState.DENIED) is PermissionAskPolicy.Decision.Ask)
        assertEquals(1, policy.totalAsks())
    }

    @Test
    fun `decide is pure and does not spend the budget`() {
        val policy = PermissionAskPolicy(maxAsksPerPermission = 2)
        repeat(50) { policy.decide(perm, PermissionState.DENIED) }
        assertEquals("decide must not mutate the counts", 0, policy.asksFor(perm))
    }

    @Test
    fun `tryAsk spends nothing when the state forbids a prompt`() {
        val policy = PermissionAskPolicy(maxAsksPerPermission = 5)
        // The bug this guards: a prompt recorded against a permission the
        // system will never ask about again. That is the retry loop.
        assertFalse(policy.tryAsk(perm, PermissionState.DENIED_PERMANENTLY))
        assertEquals(0, policy.totalAsks())

        assertFalse(policy.tryAsk(perm, PermissionState.NOT_APPLICABLE))
        assertEquals(0, policy.totalAsks())

        assertFalse(
            "a granted permission must never spend a prompt",
            policy.tryAsk(perm, PermissionState.GRANTED),
        )
        assertEquals(0, policy.totalAsks())
    }

    @Test
    fun `tryAsk respects the budget and refuses once it is spent`() {
        val policy = PermissionAskPolicy(maxAsksPerPermission = 1)
        assertTrue(policy.tryAsk(perm, PermissionState.DENIED))
        assertFalse(policy.tryAsk(perm, PermissionState.DENIED))
        assertEquals(1, policy.asksFor(perm))
    }

    @Test
    fun `reset clears the counts`() {
        val policy = PermissionAskPolicy(maxAsksPerPermission = 1)
        policy.tryAsk(perm, PermissionState.DENIED)
        assertEquals(1, policy.totalAsks())
        policy.reset()
        assertEquals(0, policy.totalAsks())
        assertTrue(
            "after a real state change the agent gets its budget back",
            policy.decide(perm, PermissionState.DENIED) is PermissionAskPolicy.Decision.Ask,
        )
    }

    @Test
    fun `a budget below one is rejected at construction`() {
        // A zero budget would deadlock the agent: it could never ask, even once.
        var threw = false
        try {
            PermissionAskPolicy(maxAsksPerPermission = 0)
        } catch (expected: IllegalArgumentException) {
            threw = true
        }
        assertTrue("a zero budget must fail loudly, not silently", threw)
    }
}
