package dev.localintelligence.core.permission

import dev.localintelligence.core.tool.ToolError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * In-memory broker used to test the policy without a device.
 *
 * Mirrors what a real Android broker can answer, including the part that is
 * easy to get wrong: a permission the app never declared is NOT_APPLICABLE
 * rather than denied, because asking the user for it would be nonsense.
 */
class FakePermissionBroker(
    private val states: MutableMap<Permission, PermissionState> = mutableMapOf(),
) : PermissionBroker {

    override fun stateOf(permission: Permission): PermissionState =
        states[permission] ?: PermissionState.NOT_APPLICABLE

    /** How many times the broker was asked about a permission. */
    var reads: Int = 0
        private set

    override fun statesOf(permissions: List<Permission>): Map<Permission, PermissionState> =
        permissions.associateWith { permission ->
            reads++
            stateOf(permission)
        }

    fun set(permission: Permission, state: PermissionState) {
        states[permission] = state
    }

    /** Grants everything currently in the map. */
    fun grantAll() {
        states.keys.forEach { states[it] = PermissionState.GRANTED }
    }
}

/**
 * The guard: a tool with a missing permission must be stopped BEFORE it runs,
 * with a reason the model can use.
 *
 * The "before execution" part is the whole point. A tool that runs and then
 * returns empty makes the agent tell the user they have no contacts, which is
 * worse than an honest refusal.
 */
class PermissionGuardTest {

    private val calendar = Permission("android.permission.READ_CALENDAR")
    private val contacts = Permission("android.permission.READ_CONTACTS")
    private val contactsWrite = Permission("android.permission.WRITE_CONTACTS")

    private fun guard(
        broker: PermissionBroker,
        source: PermissionRequirementSource,
        maxAsks: Int = 2,
    ) = PermissionGuard(broker, PermissionAskPolicy(maxAsks), source)

    private fun source(vararg entries: Pair<String, List<PermissionRequirement>>) =
        DefaultPermissionRequirementSource(entries.toMap())

    private fun req(permission: Permission, label: String = "your calendar") =
        PermissionRequirement(permission, label)

    // ---- broker reports the real state -------------------------------------

    @Test
    fun `broker reports granted for a granted permission`() {
        val broker = FakePermissionBroker(mutableMapOf(calendar to PermissionState.GRANTED))
        assertEquals(PermissionState.GRANTED, broker.stateOf(calendar))
    }

    @Test
    fun `broker reports denied for an ungranted permission`() {
        val broker = FakePermissionBroker(mutableMapOf(calendar to PermissionState.DENIED))
        assertEquals(PermissionState.DENIED, broker.stateOf(calendar))
    }

    @Test
    fun `an unknown permission is reported not-applicable, not a crash`() {
        val broker = FakePermissionBroker()
        val unknown = Permission("com.example.NEVER_DECLARED")
        assertEquals(PermissionState.NOT_APPLICABLE, broker.stateOf(unknown))
    }

    @Test
    fun `statesOf answers every permission asked about`() {
        val broker = FakePermissionBroker(
            mutableMapOf(
                calendar to PermissionState.GRANTED,
                contacts to PermissionState.DENIED,
            ),
        )
        val states = broker.statesOf(listOf(calendar, contacts, contactsWrite))
        assertEquals(3, states.size)
        assertEquals(PermissionState.GRANTED, states[calendar])
        assertEquals(PermissionState.DENIED, states[contacts])
        assertEquals(PermissionState.NOT_APPLICABLE, states[contactsWrite])
    }

    // ---- blocking before execution -----------------------------------------

    @Test
    fun `a granted tool proceeds`() {
        val broker = FakePermissionBroker(mutableMapOf(calendar to PermissionState.GRANTED))
        val g = guard(broker, source("calendar.search" to listOf(req(calendar))))
        assertEquals(PermissionGuard.Verdict.Proceed, g.check("calendar.search"))
        assertFalse(g.isBlocked("calendar.search"))
    }

    @Test
    fun `a tool requiring an ungranted permission is blocked before execution`() {
        val broker = FakePermissionBroker(mutableMapOf(calendar to PermissionState.DENIED))
        val g = guard(broker, source("calendar.search" to listOf(req(calendar))))

        val verdict = g.check("calendar.search")
        assertTrue("must be blocked", verdict is PermissionGuard.Verdict.Denied)
        val denial = (verdict as PermissionGuard.Verdict.Denied).denial
        assertEquals("calendar.search", denial.toolName)
        assertEquals(calendar, denial.permission)
        assertEquals(PermissionState.DENIED, denial.state)
        assertTrue(denial.observation.isNotBlank())
        assertTrue("the reason must be legible", denial.observation.contains("calendar"))
    }

    @Test
    fun `a blocked denial becomes a failed ToolResult with PermissionDenied`() {
        val broker = FakePermissionBroker(mutableMapOf(calendar to PermissionState.DENIED))
        val g = guard(broker, source("calendar.search" to listOf(req(calendar))))
        val denial = (g.check("calendar.search") as PermissionGuard.Verdict.Denied).denial

        val result = denial.toToolResult()
        assertFalse(result.success)
        assertEquals(denial.observation, result.observation)
        assertNotNull(result.error)
        assertTrue(result.error is ToolError.PermissionDenied)
        // The frozen ToolError.PermissionDenied always reports code
        // "permission_denied"; the finer reason travels in the message.
        assertEquals("permission_denied", result.error!!.code)
        assertEquals("permission_denied", result.error!!.message)
    }

    @Test
    fun `a permanently denied tool is terminal`() {
        val broker = FakePermissionBroker(
            mutableMapOf(calendar to PermissionState.DENIED_PERMANENTLY),
        )
        val g = guard(broker, source("calendar.search" to listOf(req(calendar))))
        val denial = (g.check("calendar.search") as PermissionGuard.Verdict.Denied).denial

        assertTrue("a permanent denial must be terminal", denial.isTerminal)
        // The distinction the UI groups on, carried in the error message.
        assertEquals("permission_denied_permanently", denial.toToolResult().error!!.message)
    }

    @Test
    fun `a not-applicable tool is terminal and says so`() {
        val broker = FakePermissionBroker(mutableMapOf(calendar to PermissionState.NOT_APPLICABLE))
        val g = guard(broker, source("calendar.search" to listOf(req(calendar))))
        val denial = (g.check("calendar.search") as PermissionGuard.Verdict.Denied).denial

        assertTrue(denial.isTerminal)
        assertEquals(
            "permission_not_applicable",
            denial.toToolResult().error!!.message,
        )
    }

    @Test
    fun `a tool with no declared requirements always proceeds`() {
        val broker = FakePermissionBroker(mutableMapOf(calendar to PermissionState.DENIED))
        val g = guard(broker, source("battery.read" to emptyList()))
        assertEquals(PermissionGuard.Verdict.Proceed, g.check("battery.read"))
    }

    @Test
    fun `an unknown tool name proceeds instead of crashing`() {
        val broker = FakePermissionBroker()
        val g = guard(broker, source("calendar.search" to listOf(req(calendar))))
        // A hallucinated tool name must not take the step down.
        assertEquals(PermissionGuard.Verdict.Proceed, g.check("tool.that.does.not.exist"))
    }

    @Test
    fun `the first blocking requirement wins`() {
        val broker = FakePermissionBroker(
            mutableMapOf(
                calendar to PermissionState.DENIED,
                contactsWrite to PermissionState.DENIED,
            ),
        )
        val g = guard(
            broker,
            source(
                "contacts.create" to listOf(req(contactsWrite, "your contacts"), req(calendar)),
            ),
        )
        val denial = (g.check("contacts.create") as PermissionGuard.Verdict.Denied).denial
        assertEquals(contactsWrite, denial.permission)
    }

    @Test
    fun `one granted requirement does not satisfy a denied one`() {
        val broker = FakePermissionBroker(
            mutableMapOf(
                calendar to PermissionState.GRANTED,
                contacts to PermissionState.DENIED,
            ),
        )
        val g = guard(
            broker,
            source("tool.both" to listOf(req(calendar), req(contacts, "your contacts"))),
        )
        assertTrue(g.check("tool.both") is PermissionGuard.Verdict.Denied)
    }

    @Test
    fun `a broker that throws blocks the tool instead of crashing the step`() {
        val exploding = object : PermissionBroker {
            override fun stateOf(permission: Permission): PermissionState =
                throw IllegalStateException("PackageManager is gone")
        }
        val g = guard(exploding, source("calendar.search" to listOf(req(calendar))))
        val verdict = g.check("calendar.search")
        assertTrue(
            "a broken broker must fail closed, not open",
            verdict is PermissionGuard.Verdict.Denied,
        )
        assertEquals(
            PermissionState.NOT_APPLICABLE,
            (verdict as PermissionGuard.Verdict.Denied).denial.state,
        )
    }

    // ---- the ask budget reaches the model ----------------------------------

    @Test
    fun `repeated blocked attempts do not spam - the observation changes to stop`() {
        val broker = FakePermissionBroker(mutableMapOf(calendar to PermissionState.DENIED))
        val policy = PermissionAskPolicy(maxAsksPerPermission = 1)
        val g = PermissionGuard(broker, policy, source("calendar.search" to listOf(req(calendar))))

        // First call: still askable, the observation invites a retry.
        val first = (g.check("calendar.search") as PermissionGuard.Verdict.Denied).denial
        assertTrue(first.decision is PermissionAskPolicy.Decision.Ask)
        assertTrue(first.observation.lowercase().contains("try again"))

        // The user gets that one prompt, and declines again.
        assertTrue(policy.tryAsk(calendar, PermissionState.DENIED))

        val second = (g.check("calendar.search") as PermissionGuard.Verdict.Denied).denial
        assertTrue(second.decision is PermissionAskPolicy.Decision.Refuse)
        assertTrue("an exhausted budget must be terminal", second.isTerminal)
        assertTrue(
            "the model must be told to stop asking",
            second.observation.lowercase().contains("do not ask again"),
        )
        assertEquals("exactly one prompt was shown", 1, policy.totalAsks())
    }

    @Test
    fun `fourteen blocked attempts produce at most the budget in prompts`() {
        val broker = FakePermissionBroker(mutableMapOf(calendar to PermissionState.DENIED))
        val policy = PermissionAskPolicy()
        val g = PermissionGuard(broker, policy, source("calendar.search" to listOf(req(calendar))))

        var prompts = 0
        repeat(14) {
            val denial = (g.check("calendar.search") as PermissionGuard.Verdict.Denied).denial
            if (denial.decision is PermissionAskPolicy.Decision.Ask) {
                prompts++
                policy.tryAsk(calendar, PermissionState.DENIED)
            }
        }
        assertEquals(2, prompts)
        assertEquals(2, policy.totalAsks())
    }

    @Test
    fun `a permanently denied tool never spends a prompt across 14 attempts`() {
        val broker = FakePermissionBroker(
            mutableMapOf(calendar to PermissionState.DENIED_PERMANENTLY),
        )
        val policy = PermissionAskPolicy()
        val g = PermissionGuard(broker, policy, source("calendar.search" to listOf(req(calendar))))

        repeat(14) {
            val denial = (g.check("calendar.search") as PermissionGuard.Verdict.Denied).denial
            if (denial.decision is PermissionAskPolicy.Decision.Ask) {
                policy.tryAsk(calendar, PermissionState.DENIED)
            }
        }
        assertEquals("a permanent denial must never prompt", 0, policy.totalAsks())
    }

    @Test
    fun `an exhausted ask budget still keeps the observation inside budget`() {
        val broker = FakePermissionBroker(mutableMapOf(calendar to PermissionState.DENIED))
        val policy = PermissionAskPolicy(maxAsksPerPermission = 1)
        policy.tryAsk(calendar, PermissionState.DENIED)
        val g = PermissionGuard(broker, policy, source("calendar.search" to listOf(req(calendar))))
        val denial = (g.check("calendar.search") as PermissionGuard.Verdict.Denied).denial
        assertTrue(
            "the concatenated hint was ${denial.observation.length} chars",
            denial.observation.length <= PermissionObservation.BUDGET_CHARS,
        )
    }

    @Test
    fun `guard observations never leak platform noise`() {
        val states = listOf(
            PermissionState.DENIED,
            PermissionState.DENIED_PERMANENTLY,
            PermissionState.NOT_APPLICABLE,
            PermissionState.GRANTED,
        )
        for (state in states) {
            val broker = FakePermissionBroker(mutableMapOf(calendar to state))
            val g = guard(broker, source("calendar.search" to listOf(req(calendar))))
            val verdict = g.check("calendar.search")
            if (verdict is PermissionGuard.Verdict.Denied) {
                val text = verdict.denial.observation
                assertTrue(
                    "$state leaked platform noise: $text",
                    PermissionObservation.containsNoPlatformNoise(text),
                )
                assertFalse(
                    "$state leaked an exception class name: $text",
                    text.contains("SecurityException"),
                )
                assertTrue("$state observation too long", text.length <= PermissionObservation.BUDGET_CHARS)
            }
        }
    }

    @Test
    fun `an unknown permission requirement does not crash the guard`() {
        val broker = FakePermissionBroker()
        val weird = Permission("com.example.SOMETHING_UNUSUAL")
        val g = guard(broker, source("mystery.tool" to listOf(req(weird, "some capability"))))
        val verdict = g.check("mystery.tool")
        assertTrue(verdict is PermissionGuard.Verdict.Denied)
        val denial = (verdict as PermissionGuard.Verdict.Denied).denial
        assertTrue(denial.observation.isNotBlank())
    }
}
