package dev.localintelligence.android.permission

import dev.localintelligence.core.permission.PermissionAskPolicy
import dev.localintelligence.core.permission.PermissionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The platform-signals-to-state mapping, tested without a device.
 *
 * This is the heart of the Android side. If it is wrong, an agent either loops
 * on a permission the user can no longer grant, or refuses to use one the user
 * would happily approve. There is no emulator in this repo, so the resolver is
 * separated from `Context` precisely so this table can be asserted.
 */
class AndroidPermissionStateResolverTest {

    private fun resolve(
        granted: Boolean = false,
        declared: Boolean = true,
        rationale: Boolean = false,
        prompts: Int = 0,
    ) = AndroidPermissionStateResolver.resolve(
        granted = granted,
        declaredInManifest = declared,
        shouldShowRationale = rationale,
        promptsSoFar = prompts,
    )

    @Test
    fun `a granted declared permission is granted`() {
        assertEquals(PermissionState.GRANTED, resolve(granted = true, declared = true))
    }

    @Test
    fun `granted wins even when the manifest read is unreliable`() {
        // A declared check that failed must not downgrade a real grant.
        assertEquals(PermissionState.GRANTED, resolve(granted = true, declared = false))
    }

    @Test
    fun `a permission the app never declared is not applicable`() {
        assertEquals(
            PermissionState.NOT_APPLICABLE,
            resolve(granted = false, declared = false),
        )
    }

    @Test
    fun `a not-declared permission is never reported as a recoverable denial`() {
        val state = resolve(granted = false, declared = false, rationale = true)
        assertFalse(
            "asking for a permission the app never declared must not be retryable",
            state.isRetryable,
        )
    }

    @Test
    fun `a rationale means a soft denial that asking again can fix`() {
        val state = resolve(granted = false, declared = true, rationale = true)
        assertEquals(PermissionState.DENIED, state)
        assertTrue("a soft denial must stay retryable", state.isRetryable)
    }

    @Test
    fun `no rationale before any prompt is still a soft denial`() {
        val state = resolve(granted = false, declared = true, rationale = false, prompts = 0)
        assertEquals(PermissionState.DENIED, state)
        assertTrue(state.isRetryable)
    }

    @Test
    fun `no rationale after a prompt is a PERMANENT denial`() {
        val state = resolve(granted = false, declared = true, rationale = false, prompts = 1)
        assertEquals(PermissionState.DENIED_PERMANENTLY, state)
        assertFalse(
            "this is the single most important assertion in the package: " +
                "a permanent denial must not be retryable",
            state.isRetryable,
        )
    }

    @Test
    fun `a permanent denial stays permanent no matter how many prompts were spent`() {
        for (prompts in 1..10) {
            val state = resolve(granted = false, declared = true, rationale = false, prompts = prompts)
            assertEquals(PermissionState.DENIED_PERMANENTLY, state)
            assertFalse(state.isRetryable)
        }
    }

    @Test
    fun `the soft and permanent paths are distinguished only by the prompt count`() {
        // Same platform signals, one fact of history. Proves the mapping turns
        // on session state rather than on anything the platform reports.
        val before = resolve(prompts = 0)
        val after = resolve(prompts = 1)
        assertEquals(PermissionState.DENIED, before)
        assertEquals(PermissionState.DENIED_PERMANENTLY, after)
    }

    @Test
    fun `the policy overload agrees with the explicit one`() {
        val permission = dev.localintelligence.core.permission.Permission(
            "android.permission.READ_CALENDAR",
        )
        val policy = PermissionAskPolicy(maxAsksPerPermission = 1)
        assertTrue(policy.tryAsk(permission, PermissionState.DENIED))

        val viaPolicy = AndroidPermissionStateResolver.resolve(
            granted = false,
            declaredInManifest = true,
            shouldShowRationale = false,
            policy = policy,
            permission = permission,
        )
        val viaExplicit = resolve(granted = false, declared = true, rationale = false, prompts = 1)
        assertEquals(viaExplicit, viaPolicy)
    }

    @Test
    fun `every combination resolves to a state and never throws`() {
        for (granted in listOf(true, false)) {
            for (declared in listOf(true, false)) {
                for (rationale in listOf(true, false)) {
                    for (prompts in 0..3) {
                        val state = resolve(granted, declared, rationale, prompts)
                        assertTrue("$state is not a real state", state in PermissionState.entries)
                    }
                }
            }
        }
    }

    @Test
    fun `no combination ever yields a retryable permanent state`() {
        // The invariant, swept across the whole input space rather than
        // asserted one case at a time.
        for (granted in listOf(true, false)) {
            for (declared in listOf(true, false)) {
                for (rationale in listOf(true, false)) {
                    for (prompts in 0..5) {
                        val state = resolve(granted, declared, rationale, prompts)
                        if (state == PermissionState.DENIED_PERMANENTLY) {
                            assertFalse(
                                "prompts=$prompts produced a retryable permanent denial",
                                state.isRetryable,
                            )
                        }
                        if (state == PermissionState.GRANTED) {
                            assertTrue(state.allowsExecution)
                        } else {
                            assertFalse(
                                "$state must block execution",
                                state.allowsExecution,
                            )
                        }
                    }
                }
            }
        }
    }
}
