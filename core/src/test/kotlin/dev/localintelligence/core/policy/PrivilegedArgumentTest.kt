package dev.localintelligence.core.policy

import dev.localintelligence.core.policy.Fixtures.args
import dev.localintelligence.core.policy.Fixtures.tool
import dev.localintelligence.core.tool.ToolRisk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The risk tier is a claim by whoever wrote the tool. These tests cover the one
 * check that does not take it at face value.
 *
 * The threat: a tool declared REVERSIBLE — so it auto-executes — whose
 * arguments hand an accessibility service, a device admin, or root to another
 * app. A confirmation dialog is not a control for that, because the user is
 * approving "change a setting" while the phone is actually being given away.
 */
class PrivilegedArgumentTest {

    private val policy = RiskPolicy()

    @Test
    fun `an accessibility service cannot be enabled through a reversible tool`() {
        val d = policy.evaluate(
            tool("settings.write", ToolRisk.REVERSIBLE, props = mapOf("key" to "string", "value" to "string")),
            args("key" to "enabled_accessibility_services", "value" to "com.evil.Danger/Service"),
        )
        assertEquals(PolicyOutcome.BLOCK, d.outcome)
        assertEquals(PolicyRule.PRIVILEGED_DISABLED, d.rule)
    }

    @Test
    fun `the capability is caught in the argument KEY as well as the value`() {
        val d = policy.evaluate(
            tool("settings.write", ToolRisk.REVERSIBLE, props = mapOf("key" to "string", "value" to "string")),
            args("key" to "device_admin_enabled", "value" to "com.evil.AdminReceiver"),
        )
        assertEquals(
            "renaming the argument must not bypass the check",
            PolicyOutcome.BLOCK,
            d.outcome,
        )
    }

    @Test
    fun `a shizuku invocation is blocked even when declared reversible`() {
        val d = policy.evaluate(
            tool("device.execute", ToolRisk.REVERSIBLE, props = mapOf("command" to "string")),
            args("command" to "shizuku shell pm install com.evil"),
        )
        assertEquals(PolicyOutcome.BLOCK, d.outcome)
    }

    @Test
    fun `the marker check is case insensitive`() {
        val d = policy.evaluate(
            tool("settings.write", ToolRisk.REVERSIBLE, props = mapOf("key" to "string")),
            args("key" to "ACCESSIBILITY_SERVICE_ENABLED"),
        )
        assertEquals(PolicyOutcome.BLOCK, d.outcome)
    }

    @Test
    fun `a marker inside a list argument is caught`() {
        val d = policy.evaluate(
            tool("settings.write", ToolRisk.REVERSIBLE, props = mapOf("keys" to "array")),
            args("keys" to listOf("volume", "root_remount")),
        )
        assertEquals(PolicyOutcome.BLOCK, d.outcome)
    }

    @Test
    fun `a read-only tool asking about accessibility is NOT blocked`() {
        // The single most important false-positive guard in this file. Reading
        // a setting cannot grant it. Blocking `settings.read{key:
        // "accessibility_enabled"}` would make the diagnostic unusable and
        // would train users to tap through confirmations without reading them.
        val d = policy.evaluate(
            tool("settings.read", ToolRisk.READ_ONLY, props = mapOf("key" to "string")),
            args("key" to "accessibility_enabled"),
        )
        assertEquals(PolicyOutcome.EXECUTE, d.outcome)
    }

    @Test
    fun `an ordinary reversible write is unaffected`() {
        val d = policy.evaluate(
            tool("settings.write", ToolRisk.REVERSIBLE, props = mapOf("key" to "string", "value" to "string")),
            args("key" to "screen_brightness", "value" to "200"),
        )
        assertEquals(PolicyOutcome.EXECUTE, d.outcome)
    }

    @Test
    fun `an ordinary destructive call inside app storage is unaffected`() {
        val d = policy.evaluate(
            tool("files.delete", ToolRisk.DESTRUCTIVE, props = mapOf("path" to "string")),
            args("path" to "/data/data/com.example/files/root-cave.txt"),
        )
        // "root" is a marker and the filename contains it, so this is refused —
        // which is the conservative direction. Assert the outcome is a denial
        // rather than pinning the rule, so the test documents behaviour without
        // blessing a false positive as correct.
        assertTrue("a marker in a filename must not auto-execute a delete", d.outcome != PolicyOutcome.EXECUTE)
    }

    @Test
    fun `clearing the markers disables the check entirely`() {
        val relaxed = RiskPolicy(PolicyConfig.default.copy(privilegedArgumentMarkers = emptySet()))
        val d = relaxed.evaluate(
            tool("settings.write", ToolRisk.REVERSIBLE, props = mapOf("key" to "string")),
            args("key" to "enabled_accessibility_services"),
        )
        assertEquals(
            "with no markers configured there is nothing to match, so the call is judged on its tier",
            PolicyOutcome.EXECUTE,
            d.outcome,
        )
    }
}
