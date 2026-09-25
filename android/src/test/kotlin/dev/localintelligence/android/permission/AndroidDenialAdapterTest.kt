package dev.localintelligence.android.permission

import dev.localintelligence.core.permission.Permission
import dev.localintelligence.core.permission.PermissionAskPolicy
import dev.localintelligence.core.permission.PermissionGuard
import dev.localintelligence.core.permission.PermissionObservation
import dev.localintelligence.core.permission.PermissionRequirement
import dev.localintelligence.core.permission.PermissionState
import dev.localintelligence.core.tool.ToolError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A caught `SecurityException` must become a structured, model-readable denial.
 *
 * The literal messages here are the ones a real device produces. Every one of
 * them would be handed straight to a model by the naive `toString()` path, which
 * is exactly what docs/tool-contract.md forbids.
 */
class AndroidDenialAdapterTest {

    private val calendar = Permission("android.permission.READ_CALENDAR")

    private fun adapt(
        throwable: Throwable,
        policy: PermissionAskPolicy = PermissionAskPolicy(),
    ) = AndroidDenialAdapter.fromThrowable(
        toolName = "calendar.search",
        permission = calendar,
        capabilityLabel = "your calendar",
        throwable = throwable,
        askPolicy = policy,
    )

    @Test
    fun `a real android denial exception becomes a permission-denied result`() {
        val boom = SecurityException(
            "Permission Denial: querying content://com.android.calendar/raw/events " +
                "requires android.permission.READ_CALENDAR or " +
                "android.permission.WRITE_CALENDAR",
        )
        val result = adapt(boom)

        assertFalse(result.success)
        assertTrue(result.error is ToolError.PermissionDenied)
        assertEquals("permission_denied", result.error!!.code)
    }

    @Test
    fun `the observation leaks no class name, stack trace, uri or permission id`() {
        val boom = SecurityException(
            "Permission Denial: querying content://com.android.calendar/raw/events " +
                "requires android.permission.READ_CALENDAR",
        )
        val observation = adapt(boom).observation

        assertFalse(observation.contains("SecurityException"))
        assertFalse(observation.contains("java.lang"))
        assertFalse(observation.contains("content://"))
        assertFalse(observation.contains("READ_CALENDAR"))
        assertFalse(observation.contains("\tat "))
        assertTrue(
            "leaked platform noise: $observation",
            PermissionObservation.containsNoPlatformNoise(observation),
        )
    }

    @Test
    fun `the observation is under the length budget`() {
        val observations = listOf(
            SecurityException("Permission Denial: short"),
            SecurityException(
                "Permission Denial: querying content://com.android.contacts/data/phones " +
                    "requires android.permission.READ_CONTACTS or " +
                    "android.permission.WRITE_CONTACTS, pid=12345 uid=10123",
            ),
        )
        for (boom in observations) {
            val text = adapt(boom).observation
            assertTrue(
                "observation was ${text.length} chars: $text",
                text.length <= PermissionObservation.BUDGET_CHARS,
            )
        }
    }

    @Test
    fun `a denial after a prompt is reported as permanent`() {
        val policy = PermissionAskPolicy()
        policy.tryAsk(calendar, PermissionState.DENIED)

        val result = adapt(
            SecurityException("Permission Denial: requires android.permission.READ_CALENDAR"),
            policy,
        )
        assertEquals("permission_denied_permanently", result.error!!.message)
        assertTrue(
            "a permanent denial must tell the model to stop",
            result.observation.lowercase().contains("do not retry"),
        )
    }

    @Test
    fun `a denial before any prompt stays recoverable`() {
        val result = adapt(
            SecurityException("Permission Denial: requires android.permission.READ_CALENDAR"),
        )
        assertEquals("permission_denied", result.error!!.message)
        assertTrue(
            "a soft denial must invite one retry",
            result.observation.lowercase().contains("try again"),
        )
    }

    @Test
    fun `a caught exception after a prompt is a permanent denial, not a budget exhaustion`() {
        val policy = PermissionAskPolicy(maxAsksPerPermission = 5)
        policy.tryAsk(calendar, PermissionState.DENIED)

        val result = adapt(
            SecurityException("Permission Denial: requires android.permission.READ_CALENDAR"),
            policy,
        )
        // The distinction matters: the system already refused after a prompt, so
        // this is a real permanent denial, not our own budget running out. The
        // UI must be able to tell "go to Settings" from "wait for a new session".
        assertEquals("permission_denied_permanently", result.error!!.message)
    }

    @Test
    fun `an exhausted ask budget on a soft state is reported as budget spent`() {
        // The other branch of the same decision, reached through the guard:
        // the permission is still recoverable (rationale true, so DENIED) but we
        // have already shown the user the dialog. Uses the Android resolver so
        // the whole path is exercised with real platform signals.
        val policy = PermissionAskPolicy(maxAsksPerPermission = 1)
        policy.tryAsk(calendar, PermissionState.DENIED)

        val state = AndroidPermissionStateResolver.resolve(
            granted = false,
            declaredInManifest = true,
            shouldShowRationale = true,
            promptsSoFar = policy.asksFor(calendar),
        )
        assertEquals("a rationale must keep the state recoverable", PermissionState.DENIED, state)

        val guard = PermissionGuard(
            broker = object : dev.localintelligence.core.permission.PermissionBroker {
                override fun stateOf(
                    permission: dev.localintelligence.core.permission.Permission,
                ) = state
            },
            askPolicy = policy,
            requirements = { listOf(PermissionRequirement(calendar, "your calendar")) },
        )
        val denial = (guard.check("calendar.search")
            as PermissionGuard.Verdict.Denied).denial

        assertEquals("permission_ask_budget_spent", denial.toToolResult().error!!.message)
        assertTrue(
            "the model must be told to stop asking: ${denial.observation}",
            denial.observation.lowercase().contains("do not ask again"),
        )
        assertEquals("still only one dialog was shown", 1, policy.totalAsks())
    }

    @Test
    fun `repeated caught exceptions never exceed the prompt budget`() {
        // 20 caught exceptions from a tool that keeps calling anyway. The bound
        // holds at 1, not 2: a SecurityException after a prompt is classified
        // as a permanent denial, and a permanent denial is never prompted for
        // again regardless of the remaining budget. That is the design
        // working, not the budget being off by one.
        val policy = PermissionAskPolicy()
        var prompted = 0
        repeat(20) {
            val result = adapt(
                SecurityException("Permission Denial: requires android.permission.READ_CALENDAR"),
                policy,
            )
            if (result.error?.message == "permission_denied" &&
                policy.tryAsk(calendar, PermissionState.DENIED)
            ) {
                prompted++
            }
        }
        assertEquals(1, prompted)
        assertEquals("exactly one dialog was shown", 1, policy.totalAsks())
    }

    @Test
    fun `a permanent denial after one prompt stops the second prompt entirely`() {
        val policy = PermissionAskPolicy(maxAsksPerPermission = 5)
        assertTrue("first ask is legitimate", policy.tryAsk(calendar, PermissionState.DENIED))

        // Every subsequent caught exception is a permanent denial by definition
        // (a prompt already happened and the system still refused).
        repeat(10) {
            val result = adapt(
                SecurityException("Permission Denial: requires android.permission.READ_CALENDAR"),
                policy,
            )
            assertEquals("permission_denied_permanently", result.error!!.message)
        }
        assertEquals("no further prompts were spent", 1, policy.totalAsks())
    }

    @Test
    fun `a non-permission exception is an internal error, not a denial`() {
        val result = adapt(IllegalStateException("ContentResolver is closed"))
        assertFalse(result.success)
        assertTrue(result.error is ToolError.Internal)
        assertFalse(
            "must not masquerade as a permission problem",
            result.error is ToolError.PermissionDenied,
        )
    }

    @Test
    fun `a non-permission exception message is still sanitised`() {
        val result = adapt(
            IllegalStateException(
                "java.lang.NullPointerException at com.android.providers.CalendarProvider.query",
            ),
        )
        val text = result.observation
        assertFalse(text.contains("NullPointerException"))
        assertFalse(text.contains("com.android.providers"))
        assertTrue(PermissionObservation.containsNoPlatformNoise(text))
    }

    @Test
    fun `an exception with a null message is handled`() {
        val result = adapt(SecurityException())
        assertFalse(result.success)
        assertTrue(result.observation.isNotBlank())
    }

    @Test
    fun `the adapted result satisfies the frozen observation rules`() {
        val result = adapt(
            SecurityException(
                "Permission Denial: opening provider content://x requires " +
                    "android.permission.READ_CALENDAR, code -13",
            ),
        )
        assertTrue(
            "under the general tool budget",
            result.observation.length <=
                dev.localintelligence.core.tool.ObservationTruncator.DEFAULT_BUDGET_CHARS,
        )
        assertFalse(result.observation.contains("-13"))
    }

    @Test
    fun `the adapter agrees with the guard for the same state`() {
        // Same state reached two ways — the pre-check and the caught exception —
        // must produce the same user-facing sentence, or the model sees two
        // different stories for one missing permission.
        val broker = object : dev.localintelligence.core.permission.PermissionBroker {
            override fun stateOf(permission: Permission) = PermissionState.DENIED
        }
        val guard = PermissionGuard(
            broker = broker,
            askPolicy = PermissionAskPolicy(),
            requirements = { listOf(PermissionRequirement(calendar, "your calendar")) },
        )
        val fromGuard = (guard.check("calendar.search")
            as PermissionGuard.Verdict.Denied).denial.observation
        val fromException = adapt(
            SecurityException("Permission Denial: requires android.permission.READ_CALENDAR"),
        ).observation
        assertEquals(fromGuard, fromException)
    }
}
