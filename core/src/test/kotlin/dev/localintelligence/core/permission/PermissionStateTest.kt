package dev.localintelligence.core.permission

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The state model itself, and the DENIED / DENIED_PERMANENTLY split.
 *
 * The split is the reason this package exists: an agent that cannot tell them
 * apart retries forever. These tests are the executable form of that claim.
 */
class PermissionStateTest {

    @Test
    fun `every state has a distinct and total classification`() {
        val all = PermissionState.entries
        assertEquals("all four states must be modelled", 4, all.size)
        // No two states may be indistinguishable on the two axes that decide
        // what the agent does next.
        assertEquals(
            "exactly one state permits execution",
            1,
            all.count { it.allowsExecution },
        )
        assertEquals(
            "only GRANTED permits execution",
            listOf(PermissionState.GRANTED),
            all.filter { it.allowsExecution },
        )
        assertEquals(
            "only DENIED is retryable",
            listOf(PermissionState.DENIED),
            all.filter { it.isRetryable },
        )
    }

    @Test
    fun `granted is the only state that lets a tool run`() {
        assertTrue(PermissionState.GRANTED.allowsExecution)
        assertFalse(PermissionState.GRANTED.blocksExecution)
    }

    @Test
    fun `soft denial is retryable and does not need settings`() {
        assertTrue(PermissionState.DENIED.isRetryable)
        assertTrue(PermissionState.DENIED.blocksExecution)
        assertFalse(
            "a soft denial is fixed by asking, not by the user going to Settings",
            PermissionState.DENIED.requiresUserInSettings,
        )
    }

    @Test
    fun `permanent denial is NOT retryable and requires settings`() {
        assertFalse(
            "retrying a permanent denial is the loop bug this package prevents",
            PermissionState.DENIED_PERMANENTLY.isRetryable,
        )
        assertTrue(PermissionState.DENIED_PERMANENTLY.blocksExecution)
        assertTrue(PermissionState.DENIED_PERMANENTLY.requiresUserInSettings)
    }

    @Test
    fun `not applicable is blocked, unfixable and never retryable`() {
        assertFalse(PermissionState.NOT_APPLICABLE.allowsExecution)
        assertFalse(PermissionState.NOT_APPLICABLE.isRetryable)
        assertTrue(PermissionState.NOT_APPLICABLE.blocksExecution)
    }

    @Test
    fun `permanent and soft denial are not interchangeable`() {
        assertNotEquals(
            "collapsing these two states is what causes infinite retry",
            PermissionState.DENIED.requiresUserInSettings,
            PermissionState.DENIED_PERMANENTLY.requiresUserInSettings,
        )
        assertNotEquals(
            PermissionState.DENIED.isRetryable,
            PermissionState.DENIED_PERMANENTLY.isRetryable,
        )
    }
}
