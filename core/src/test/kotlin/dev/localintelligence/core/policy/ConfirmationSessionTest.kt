package dev.localintelligence.core.policy

import dev.localintelligence.core.policy.Fixtures.args
import dev.localintelligence.core.policy.Fixtures.rawArgs
import dev.localintelligence.core.policy.Fixtures.tool
import dev.localintelligence.core.tool.ToolRisk
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The TOCTOU tests.
 *
 * An agent harness that asks a human "may I send this to Alice?" and then
 * sends it to Mallory has not asked the human anything. Every test here is an
 * attempt to get a gated action to run without the approval that was granted
 * for it, and every one of them must fail closed.
 */
class ConfirmationSessionTest {

    private val policy = RiskPolicy()

    private fun destructiveDecision(toolName: String = "files.delete", path: String = "/data/data/com.example/files/a.txt") =
        policy.evaluate(
            tool(toolName, ToolRisk.DESTRUCTIVE, props = mapOf("path" to "string")),
            args("path" to path),
        )

    // ------------------------------------------------------------- basic gate

    @Test
    fun `a gated call parks and blocks execution until approved`() {
        val decision = destructiveDecision()
        assertTrue(decision.requiresConfirmation)

        val session = ConfirmationSession.park(decision)
        assertNotNull("a REQUIRE_CONFIRMATION decision must be parkable", session)
        assertEquals(ConfirmationSession.State.PENDING, session!!.currentState)
        assertTrue(session.isPending)
    }

    @Test
    fun `a non-gated decision cannot be parked`() {
        val execute = policy.evaluate(tool("battery.read", ToolRisk.READ_ONLY), args())
        assertNull("an EXECUTE decision has nothing to confirm", ConfirmationSession.park(execute))

        val blocked = policy.evaluate(tool("device.root", ToolRisk.PRIVILEGED), args())
        assertNull("a BLOCKED decision must never become confirmable by parking it", ConfirmationSession.park(blocked))
    }

    @Test
    fun `approval yields exactly the proposed arguments`() {
        val proposed = "/data/data/com.example/files/report.pdf"
        val session = ConfirmationSession.park(destructiveDecision(path = proposed))!!
        val approval = (session.resolve(true) as ConfirmationOutcome.Approved).approval

        val claimed = approval.claim("files.delete", args("path" to proposed))
        assertNotNull(claimed)
        assertEquals("the executed path must be the approved path", proposed, claimed!!["path"]?.toString()?.trim('"'))
    }

    // ----------------------------------------------------------------- replay

    @Test
    fun `approving twice does not issue a second approval`() {
        val session = ConfirmationSession.park(destructiveDecision())!!
        val first = session.resolve(true)
        assertTrue(first is ConfirmationOutcome.Approved)

        val second = session.resolve(true)
        assertTrue("a replayed approve must be refused", second is ConfirmationOutcome.AlreadyResolved)
        assertEquals(ConfirmationSession.State.APPROVED, session.currentState)
    }

    @Test
    fun `an approval can only be spent once`() {
        val session = ConfirmationSession.park(destructiveDecision())!!
        val approval = (session.resolve(true) as ConfirmationOutcome.Approved).approval
        val theArgs = args("path" to "/data/data/com.example/files/a.txt")

        assertNotNull(approval.claim("files.delete", theArgs))
        assertNull("a second claim must yield nothing", approval.claim("files.delete", theArgs))
        assertEquals(ConfirmationSession.State.SPENT, session.currentState)
        assertTrue(approval.isSpent)
    }

    @Test
    fun `a deny cannot be flipped into an approve on a second call`() {
        val session = ConfirmationSession.park(destructiveDecision())!!
        assertTrue(session.resolve(false) is ConfirmationOutcome.Denied)

        val second = session.resolve(true)
        assertTrue(second is ConfirmationOutcome.AlreadyResolved)
        assertEquals(ConfirmationSession.State.DENIED, session.currentState)
    }

    @Test
    fun `an approve cannot be flipped into a deny on a second call`() {
        val session = ConfirmationSession.park(destructiveDecision())!!
        session.resolve(true)

        val second = session.resolve(false)
        assertTrue(second is ConfirmationOutcome.AlreadyResolved)
        assertEquals(ConfirmationSession.State.APPROVED, session.currentState)
    }

    // ------------------------------------------ approve-then-mutate (TOCTOU)

    @Test
    fun `swapping the recipient after approval does not execute`() {
        val toolDef = tool("sms.send", ToolRisk.EXTERNAL_COMMUNICATION, props = mapOf("to" to "string"))
        val approvedArgs = args("to" to "+31611111111")
        val decision = policy.evaluate(toolDef, approvedArgs)
        val session = ConfirmationSession.park(decision)!!
        val approval = (session.resolve(true) as ConfirmationOutcome.Approved).approval

        val swapped = approval.claim("sms.send", args("to" to "+31622222222"))
        assertNull("the user approved +31611111111, not +31622222222", swapped)
    }

    @Test
    fun `swapping the path after approval does not execute`() {
        val session = ConfirmationSession.park(
            destructiveDecision(path = "/data/data/com.example/files/holiday-photos.zip"),
        )!!
        val approval = (session.resolve(true) as ConfirmationOutcome.Approved).approval

        val swapped = approval.claim("files.delete", args("path" to "/data/data/com.example/files/tax-return.pdf"))
        assertNull(swapped)
    }

    @Test
    fun `retargeting a different tool after approval does not execute`() {
        val session = ConfirmationSession.park(destructiveDecision(toolName = "files.delete"))!!
        val approval = (session.resolve(true) as ConfirmationOutcome.Approved).approval

        // Same arguments, different tool: still refused.
        val redirected = approval.claim("files.trash", args("path" to "/data/data/com.example/files/a.txt"))
        assertNull(redirected)
    }

    @Test
    fun `adding an extra argument after approval does not execute`() {
        val session = ConfirmationSession.park(
            destructiveDecision(path = "/data/data/com.example/files/a.txt"),
        )!!
        val approval = (session.resolve(true) as ConfirmationOutcome.Approved).approval

        val augmented = approval.claim(
            "files.delete",
            rawArgs("""{"path": "/data/data/com.example/files/a.txt", "recursive": true}"""),
        )
        assertNull("the user was never shown a recursive flag", augmented)
    }

    @Test
    fun `key order does not defeat the fingerprint`() {
        val session = ConfirmationSession.park(
            policy.evaluate(
                tool("sms.send", ToolRisk.EXTERNAL_COMMUNICATION, props = mapOf("to" to "string", "body" to "string")),
                rawArgs("""{"to": "+31611111111", "body": "hi"}"""),
            ),
        )!!
        val approval = (session.resolve(true) as ConfirmationOutcome.Approved).approval

        val reordered = approval.claim(
            "sms.send",
            rawArgs("""{"body": "hi", "to": "+31611111111"}"""),
        )
        assertNotNull("the same call written in a different key order is the same call", reordered)
    }

    @Test
    fun `a string that looks like a number is a different argument`() {
        val session = ConfirmationSession.park(
            policy.evaluate(
                tool("files.delete", ToolRisk.DESTRUCTIVE, props = mapOf("path" to "string", "count" to "integer")),
                rawArgs("""{"path": "/data/data/com.example/files/a.txt", "count": 1}"""),
            ),
        )!!
        val approval = (session.resolve(true) as ConfirmationOutcome.Approved).approval

        val typeChanged = approval.claim(
            "files.delete",
            rawArgs("""{"path": "/data/data/com.example/files/a.txt", "count": "1"}"""),
        )
        assertNull("1 and \"1\" are different requests to a destructive tool", typeChanged)
    }

    @Test
    fun `reordering a list argument is a different call`() {
        val session = ConfirmationSession.park(
            policy.evaluate(
                tool("files.delete", ToolRisk.DESTRUCTIVE, props = mapOf("paths" to "array")),
                rawArgs("""{"paths": ["/data/data/com.example/files/a.txt", "/data/data/com.example/files/b.txt"]}"""),
            ),
        )!!
        val approval = (session.resolve(true) as ConfirmationOutcome.Approved).approval

        val reordered = approval.claim(
            "files.delete",
            rawArgs("""{"paths": ["/data/data/com.example/files/b.txt", "/data/data/com.example/files/a.txt"]}"""),
        )
        assertNull("a reordered list is a different proposal", reordered)
    }

    // ------------------------------------------------------- session identity

    @Test
    fun `session ids are deterministic`() {
        val a = ConfirmationSession.park(destructiveDecision())!!.id
        val b = ConfirmationSession.park(destructiveDecision())!!.id
        assertEquals("the same proposal must produce the same id across runs", a, b)
    }

    @Test
    fun `different proposals get different session ids`() {
        val a = ConfirmationSession.park(destructiveDecision(path = "/data/data/com.example/files/a.txt"))!!.id
        val b = ConfirmationSession.park(destructiveDecision(path = "/data/data/com.example/files/b.txt"))!!.id
        assertTrue("distinct actions must not share an id", a != b)
    }

    @Test
    fun `the session remembers exactly what it parked`() {
        val decision = destructiveDecision(path = "/data/data/com.example/files/salaries.csv")
        val session = ConfirmationSession.park(decision)!!

        assertEquals("files.delete", session.toolName)
        assertEquals(ToolRisk.DESTRUCTIVE, session.risk)
        assertEquals(PolicyRule.RISK_TIER, session.rule)
        assertEquals(decision.justification, session.justification)
        assertEquals(
            "salaries.csv",
            session.proposedArguments["path"]?.toString()?.trim('"')?.substringAfterLast('/'),
        )
        assertTrue(session.matches("files.delete", args("path" to "/data/data/com.example/files/salaries.csv")))
        assertFalse(session.matches("files.delete", args("path" to "/data/data/com.example/files/other.csv")))
    }

    @Test
    fun `a denial tells the model not to retry the same call`() {
        val session = ConfirmationSession.park(destructiveDecision())!!
        val denied = session.resolve(false) as ConfirmationOutcome.Denied
        assertTrue(denied.reason.contains("Do not call it again"))
        assertTrue(denied.reason.contains("files.delete"))
    }

    @Test
    fun `an unresolved decision throws nothing and always carries a justification`() {
        val decisions = listOf(
            destructiveDecision(),
            policy.evaluate(tool("device.root", ToolRisk.PRIVILEGED), args()),
            policy.evaluate(tool("battery.read", ToolRisk.READ_ONLY), args()),
        )
        decisions.forEach { d ->
            assertTrue(d.justification.isNotBlank())
            assertTrue(d.justification.length > 20)
        }
    }

    /** A stand-in for the runtime path that renders a decision to a human. */
    @Test
    fun `the parked justification is what a human would read`() {
        val session = ConfirmationSession.park(
            policy.evaluate(
                tool("files.delete", ToolRisk.DESTRUCTIVE, props = mapOf("path" to "string")),
                args("path" to "/data/data/com.example/files/only-copy-of-photos.jpg"),
            ),
        )!!
        assertTrue(session.justification.contains("only-copy-of-photos.jpg"))
        assertTrue(session.justification.contains("cannot be undone"))
    }

    /** Guards the fixture itself: a mutable map must not be the source of truth. */
    @Test
    fun `the claimed arguments are the session's own, not the caller's`() {
        val session = ConfirmationSession.park(destructiveDecision())!!
        val approval = (session.resolve(true) as ConfirmationOutcome.Approved).approval
        val claimed = approval.claim("files.delete", args("path" to "/data/data/com.example/files/a.txt"))!!
        assertEquals(session.proposedArguments, claimed)
    }

    @Suppress("unused")
    private fun unusedButKeepsImportsHonest() = buildJsonObject {
        put("x", JsonPrimitive("y"))
    }
}
