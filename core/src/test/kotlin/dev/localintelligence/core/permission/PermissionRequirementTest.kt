package dev.localintelligence.core.permission

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Requirement classification: turning a tool into the permissions it needs
 * without touching the frozen `ToolDefinition`.
 */
class PermissionRequirementTest {

    private val calendar = Permission("android.permission.READ_CALENDAR")

    @Test
    fun `a registered tool yields its requirements`() {
        val source = DefaultPermissionRequirementSource(
            mapOf(
                "calendar.search" to listOf(
                    PermissionRequirement(calendar, "your calendar"),
                ),
            ),
        )
        val reqs = source.requirementsFor("calendar.search")
        assertEquals(1, reqs.size)
        assertEquals(calendar, reqs[0].permission)
        assertEquals("your calendar", reqs[0].capabilityLabel)
    }

    @Test
    fun `an unregistered tool yields no requirements`() {
        val source = DefaultPermissionRequirementSource()
        assertTrue(source.requirementsFor("battery.read").isEmpty())
    }

    @Test
    fun `a requirement is satisfied only by a granted state`() {
        val req = PermissionRequirement(calendar, "your calendar")
        assertTrue(req.isSatisfiedBy(PermissionState.GRANTED))
        assertFalse(req.isSatisfiedBy(PermissionState.DENIED))
        assertFalse(req.isSatisfiedBy(PermissionState.DENIED_PERMANENTLY))
        assertFalse(
            "not-applicable must not count as satisfied",
            req.isSatisfiedBy(PermissionState.NOT_APPLICABLE),
        )
    }

    @Test
    fun `known permissions get a human label`() {
        val source = DefaultPermissionRequirementSource()
        assertEquals("your calendar", source.labelFor(calendar))
        assertEquals(
            "your contacts",
            source.labelFor(Permission("android.permission.READ_CONTACTS")),
        )
    }

    @Test
    fun `an unknown permission still gets a non-empty readable label`() {
        val source = DefaultPermissionRequirementSource()
        val label = source.labelFor(Permission("com.example.SOME_WEIRD_THING"))
        assertTrue("label must not be blank", label.isNotBlank())
        assertFalse("a raw id must not reach the model", label.contains("com.example"))
    }

    @Test
    fun `humanise handles empty and underscore names`() {
        assertEquals("that", DefaultPermissionRequirementSource.humanise(""))
        assertEquals(
            "read media images",
            DefaultPermissionRequirementSource.humanise("READ_MEDIA_IMAGES"),
        )
    }

    @Test
    fun `a blank permission id is rejected at construction`() {
        var threw = false
        try {
            Permission("   ")
        } catch (expected: IllegalArgumentException) {
            threw = true
        }
        assertTrue("a blank permission id must fail loudly", threw)
    }

    @Test
    fun `an absurdly long permission id is rejected`() {
        // Bound stated in the class so an observation or map key cannot grow
        // without limit on a device short of RAM.
        var threw = false
        try {
            Permission("x".repeat(Permission.MAX_ID_CHARS + 1))
        } catch (expected: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test
    fun `shortName strips the package prefix`() {
        assertEquals("READ_CALENDAR", calendar.shortName)
        assertEquals("ODD", Permission("ODD").shortName)
    }

    @Test
    fun `a custom requirement source is honoured by the guard`() {
        val custom = PermissionRequirementSource { name ->
            if (name == "custom.tool") listOf(PermissionRequirement(calendar, "your calendar"))
            else emptyList()
        }
        val broker = FakePermissionBroker(mutableMapOf(calendar to PermissionState.DENIED))
        val g = PermissionGuard(broker, PermissionAskPolicy(), custom)
        assertTrue(g.check("custom.tool") is PermissionGuard.Verdict.Denied)
        assertEquals(PermissionGuard.Verdict.Proceed, g.check("other.tool"))
    }
}
