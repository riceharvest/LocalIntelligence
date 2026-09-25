package dev.localintelligence.core.policy

import dev.localintelligence.core.policy.Fixtures.args
import dev.localintelligence.core.policy.Fixtures.rawArgs
import dev.localintelligence.core.policy.Fixtures.tool
import dev.localintelligence.core.tool.ToolRisk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The brief's central claim, as tests: risk depends on the ARGUMENTS, not on
 * the tool name. Two calls to the same tool must be able to land on different
 * outcomes, or the engine is just a lookup table on `ToolRisk` wearing a
 * policy-shaped hat.
 */
class ArgumentSensitivityTest {

    // ---------------------------------------------- same tool, different risk

    @Test
    fun `sharing to a known contact and to a stranger are decided differently`() {
        val tool = tool(
            "files.share",
            ToolRisk.EXTERNAL_COMMUNICATION,
            props = mapOf("path" to "string", "to" to "string"),
        )
        val path = "/data/data/com.example/files/report.pdf"
        val trusted = RiskPolicy(PolicyConfig.default.copy(knownTargets = setOf("mom")))

        val toMom = trusted.evaluate(tool, args("path" to path, "to" to "mom"))
        val toStranger = trusted.evaluate(tool, args("path" to path, "to" to "+31600000000"))

        assertEquals("a known contact is the lower-friction case", PolicyRule.RISK_TIER, toMom.rule)
        assertEquals("an unrecognised target is called out by name", PolicyRule.EXTERNAL_TO_UNKNOWN_TARGET, toStranger.rule)
        assertNotEquals(toStranger.rule, toMom.rule)
        assertTrue(toStranger.justification.contains("+31600000000"))
    }

    @Test
    fun `an unknown target is never auto-executed even for a reversible tool`() {
        // The engine's own risk tier, not the tool author's, decides comms.
        val d = RiskPolicy(PolicyConfig.default).evaluate(
            tool("messaging.share", ToolRisk.EXTERNAL_COMMUNICATION, props = mapOf("to" to "string")),
            args("to" to "+31600000000"),
        )
        assertEquals(PolicyOutcome.REQUIRE_CONFIRMATION, d.outcome)
    }

    @Test
    fun `a bare-link body is called out even to a trusted contact`() {
        val d = RiskPolicy(PolicyConfig.default.copy(knownTargets = setOf("mom"))).evaluate(
            tool("sms.send", ToolRisk.EXTERNAL_COMMUNICATION, props = mapOf("to" to "string", "body" to "string")),
            args("to" to "mom", "body" to "https://bit.ly/3xYz"),
        )
        assertEquals(PolicyOutcome.REQUIRE_CONFIRMATION, d.outcome)
        assertEquals(PolicyRule.MESSAGE_IS_BARE_LINK, d.rule)
    }

    @Test
    fun `a link with prose around it is not treated as a bare link`() {
        val d = RiskPolicy(PolicyConfig.default.copy(knownTargets = setOf("mom"))).evaluate(
            tool("sms.send", ToolRisk.EXTERNAL_COMMUNICATION, props = mapOf("to" to "string", "body" to "string")),
            args("to" to "mom", "body" to "the invoice is here https://example.com/inv/9"),
        )
        assertEquals(PolicyRule.RISK_TIER, d.rule)
    }

    @Test
    fun `a recipient-less external send is held rather than trusted`() {
        val d = RiskPolicy(PolicyConfig.default).evaluate(
            tool("sms.send", ToolRisk.EXTERNAL_COMMUNICATION, props = mapOf("body" to "string")),
            args("body" to "hello"),
        )
        assertEquals(PolicyOutcome.REQUIRE_CONFIRMATION, d.outcome)
        assertEquals(PolicyRule.EXTERNAL_TO_UNKNOWN_TARGET, d.rule)
    }

    // ------------------------------------------------------ path sensitivity

    @Test
    fun `a path inside app storage is not escalated`() {
        val d = RiskPolicy(PolicyConfig.default).evaluate(
            tool("files.delete", ToolRisk.DESTRUCTIVE, props = mapOf("path" to "string")),
            args("path" to "/data/data/com.example/files/tmp.txt"),
        )
        assertEquals(PolicyRule.RISK_TIER, d.rule)
    }

    @Test
    fun `a path outside app storage escalates a reversible write`() {
        val d = RiskPolicy(PolicyConfig.default).evaluate(
            tool("files.write", ToolRisk.REVERSIBLE, props = mapOf("path" to "string")),
            args("path" to "/storage/emulated/0/Documents/tax.pdf"),
        )
        assertEquals("a reversible tier is not a licence to write outside app storage",
            PolicyOutcome.REQUIRE_CONFIRMATION, d.outcome)
        assertEquals(PolicyRule.PATH_ESCAPES_APP_STORAGE, d.rule)
    }

    @Test
    fun `a traversal path is treated as escaping even when it resolves back inside`() {
        val d = RiskPolicy(PolicyConfig.default).evaluate(
            tool("files.delete", ToolRisk.DESTRUCTIVE, props = mapOf("path" to "string")),
            args("path" to "/data/data/com.example/files/../files/tmp.txt"),
        )
        assertEquals(PolicyRule.PATH_ESCAPES_APP_STORAGE, d.rule)
    }

    @Test
    fun `a traversal is classified as escaping even when it normalises back inside`() {
        // `/data/data/com.example/../../secrets.txt` normalises to
        // `/data/secrets.txt`, which does not start with an app root — but the
        // property that matters is that a `..` is never silently laundered into
        // an "inside app storage" verdict. Classification, not string shape, is
        // the assertion.
        val escaping = "/data/data/com.example/../../secrets.txt"
        val insideLooking = "/data/data/com.example/files/../files/tmp.txt"

        assertTrue(
            "a path that walks out of the app root must not count as inside",
            ArgumentInspector.isInsideAppStorage(escaping, PolicyConfig.default).not(),
        )
        assertTrue(
            "a path that walks out and back is still escalated: a model emitting " +
                "'..' is confused or probing, and either way it is not eyeballable",
            ArgumentInspector.isInsideAppStorage(insideLooking, PolicyConfig.default).not(),
        )
        assertTrue(ArgumentInspector.hasTraversalSegment(insideLooking))
    }

    @Test
    fun `an empty app-storage-root set denies every path rather than allowing all`() {
        val noRoots = PolicyConfig.default.copy(appStorageRoots = emptySet())
        val d = RiskPolicy(noRoots).evaluate(
            tool("files.delete", ToolRisk.DESTRUCTIVE, props = mapOf("path" to "string")),
            args("path" to "/data/data/com.example/files/tmp.txt"),
        )
        assertEquals(PolicyRule.PATH_ESCAPES_APP_STORAGE, d.rule)
    }

    // ------------------------------------------------------- bulk sensitivity

    @Test
    fun `a destructive batch over the bulk threshold confirms`() {
        // 12 items: over the default bulk threshold of 10, well under the
        // destructive block threshold of 100. This is the band where asking the
        // user is still the right call.
        val batch = (1..12).map { "/data/data/com.example/files/f$it.txt" }
        val d = RiskPolicy(PolicyConfig.default).evaluate(
            tool("files.delete", ToolRisk.DESTRUCTIVE, props = mapOf("paths" to "array")),
            args("paths" to batch),
        )
        assertEquals(PolicyOutcome.REQUIRE_CONFIRMATION, d.outcome)
        assertEquals(PolicyRule.BULK_OPERATION, d.rule)
    }

    @Test
    fun `a very large destructive batch is blocked rather than confirmed`() {
        val many = (1..500).map { "/data/data/com.example/files/f$it.txt" }
        val d = RiskPolicy(PolicyConfig.default).evaluate(
            tool("files.delete", ToolRisk.DESTRUCTIVE, props = mapOf("paths" to "array")),
            args("paths" to many),
        )
        assertEquals("confirming a 500-file delete is not a control", PolicyOutcome.BLOCK, d.outcome)
        assertEquals(PolicyRule.BULK_OPERATION, d.rule)
    }

    @Test
    fun `a single item is not bulk`() {
        val d = RiskPolicy(PolicyConfig.default).evaluate(
            tool("files.delete", ToolRisk.DESTRUCTIVE, props = mapOf("paths" to "array")),
            args("paths" to listOf("/data/data/com.example/files/only.txt")),
        )
        assertEquals(PolicyRule.RISK_TIER, d.rule)
    }

    @Test
    fun `a bulk call on a reversible tool confirms instead of blocking`() {
        val many = (1..50).map { "/data/data/com.example/files/f$it.txt" }
        val d = RiskPolicy(PolicyConfig.default).evaluate(
            tool("files.write", ToolRisk.REVERSIBLE, props = mapOf("paths" to "array")),
            args("paths" to many),
        )
        assertEquals(PolicyOutcome.REQUIRE_CONFIRMATION, d.outcome)
        assertEquals(PolicyRule.BULK_OPERATION, d.rule)
    }

    // --------------------------------------------------- fail-safe arguments

    @Test
    fun `a null argument blocks instead of being read as absent`() {
        val d = RiskPolicy(PolicyConfig.default).evaluate(
            tool("sms.send", ToolRisk.EXTERNAL_COMMUNICATION, props = mapOf("to" to "string", "body" to "string")),
            args("to" to null, "body" to "hi"),
        )
        assertEquals(PolicyOutcome.BLOCK, d.outcome)
        assertEquals(PolicyRule.MALFORMED_ARGUMENTS, d.rule)
    }

    @Test
    fun `an unreadable count is treated as unbounded, not as one`() {
        val d = RiskPolicy(PolicyConfig.default).evaluate(
            tool("files.delete", ToolRisk.DESTRUCTIVE, props = mapOf("count" to "integer")),
            args("count" to "many"),
        )
        assertEquals(PolicyOutcome.BLOCK, d.outcome)
        assertEquals(PolicyRule.MALFORMED_ARGUMENTS, d.rule)
    }

    @Test
    fun `an object where a string belongs is malformed, not coerced`() {
        val d = RiskPolicy(PolicyConfig.default).evaluate(
            tool("sms.send", ToolRisk.EXTERNAL_COMMUNICATION, props = mapOf("to" to "string")),
            rawArgs("""{"to": {"nested": "value"}}"""),
        )
        assertEquals(PolicyOutcome.BLOCK, d.outcome)
        assertEquals(PolicyRule.MALFORMED_ARGUMENTS, d.rule)
    }

    @Test
    fun `a malformed argument blocks even a read-only tool`() {
        // A read cannot destroy, but an argument we cannot read means we do not
        // know WHAT is being read. Refusing is cheap; leaking is not.
        val d = RiskPolicy(PolicyConfig.default).evaluate(
            tool("files.read", ToolRisk.READ_ONLY, props = mapOf("path" to "string")),
            rawArgs("""{"path": {"nested": "object"}}"""),
        )
        assertEquals(PolicyOutcome.BLOCK, d.outcome)
        assertEquals(PolicyRule.MALFORMED_ARGUMENTS, d.rule)
    }

    @Test
    fun `a list of paths is valid but is still escalated for escaping app storage`() {
        // Not malformed — `paths` may legitimately be a list. It is refused for
        // a stronger reason: relative paths are not inside app storage.
        val d = RiskPolicy(PolicyConfig.default).evaluate(
            tool("files.read", ToolRisk.READ_ONLY, props = mapOf("paths" to "array")),
            rawArgs("""{"paths": ["a.txt", "b.txt"]}"""),
        )
        assertEquals(PolicyOutcome.REQUIRE_CONFIRMATION, d.outcome)
        assertEquals(PolicyRule.PATH_ESCAPES_APP_STORAGE, d.rule)
    }

    @Test
    fun `a negative count is not silently treated as zero`() {
        val d = RiskPolicy(PolicyConfig.default).evaluate(
            tool("files.delete", ToolRisk.DESTRUCTIVE, props = mapOf("count" to "integer")),
            args("count" to -5),
        )
        // It parses, so this is not "malformed" — but it must not read as "no
        // bulk". Assert the engine is not confused into executing anything.
        assertTrue(d.outcome != PolicyOutcome.EXECUTE)
    }
}
