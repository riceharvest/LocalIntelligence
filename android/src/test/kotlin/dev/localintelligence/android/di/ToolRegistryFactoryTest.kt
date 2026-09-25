package dev.localintelligence.android.di

import dev.localintelligence.core.tool.AgentTool
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Registry assembly: uniqueness, contract shape, and the failure report.
 *
 * The duplicate-name case is not hypothetical. This repo is built by several
 * agents in parallel, each landing a tool family, and `SimpleToolRegistry`
 * throws on a duplicate — which means the *app* would crash on start for a
 * mistake made in a merge. These tests turn that into a diagnosable report.
 */
class ToolRegistryFactoryTest {

    // ----------------------------------------------------------------- the happy path

    @Test
    fun `a clean tool set produces a populated registry`() {
        val report = ToolRegistryFactory.assemble({
            listOf(FakeTool("device.battery"), FakeTool("clipboard.read"))
        })

        assertTrue(report.isWellFormed)
        assertEquals(2, report.registry.all().size)
        assertNotNull(report.registry.byName("device.battery"))
    }

    @Test
    fun `an empty tool set is well formed and empty`() {
        val report = ToolRegistryFactory.assemble({ emptyList() })

        assertTrue(report.isWellFormed)
        assertEquals(0, report.registry.all().size)
    }

    // ------------------------------------------------------------------ uniqueness

    @Test
    fun `duplicate tool names are reported, not thrown`() {
        // Two agents each registering "device.battery" is the real scenario.
        val report = ToolRegistryFactory.assemble({
            listOf(
                FakeTool("device.battery", category = "device"),
                FakeTool("device.battery", category = "other"),
            )
        })

        assertFalse(report.isWellFormed)
        assertEquals(listOf("device.battery"), report.duplicates)
    }

    @Test
    fun `a duplicate yields a registry that exposes no tools`() {
        // Shadowing one tool with another means the model calls whichever won an
        // arbitrary map ordering, so the registry is empty instead: a visible
        // no-op beats a silently wrong tool.
        val report = ToolRegistryFactory.assemble({
            listOf(FakeTool("device.battery"), FakeTool("device.battery"))
        })

        assertEquals(0, report.registry.all().size)
        assertNull(report.registry.byName("device.battery"))
    }

    @Test
    fun `several duplicates are all reported`() {
        val report = ToolRegistryFactory.assemble({
            listOf(
                FakeTool("device.battery"),
                FakeTool("device.battery"),
                FakeTool("clock.alarm"),
                FakeTool("clock.alarm"),
            )
        })

        assertEquals(listOf("clock.alarm", "device.battery"), report.duplicates)
    }

    @Test
    fun `names differing only past the first segment are not duplicates`() {
        val report = ToolRegistryFactory.assemble({
            listOf(FakeTool("device.battery"), FakeTool("device.battery.level"))
        })

        assertTrue(report.isWellFormed)
        assertEquals(2, report.registry.all().size)
    }

    // ------------------------------------------------------------------- the shape

    @Test
    fun `a blank description is reported`() {
        val report = ToolRegistryFactory.assemble({ listOf(FakeTool("device.battery", description = "")) })

        assertFalse(report.isWellFormed)
        assertTrue(
            "expected a description complaint, got ${report.malformed}",
            report.malformed.any { it.contains("description is blank") },
        )
    }

    @Test
    fun `a name that is not lowercase verb dot noun is reported`() {
        val report = ToolRegistryFactory.assemble({ listOf(FakeTool("Device_Battery")) })

        assertFalse(report.isWellFormed)
        assertTrue(report.malformed.any { it.contains("is not lowercase verb.noun") })
    }

    @Test
    fun `a name with no dot is reported`() {
        val report = ToolRegistryFactory.assemble({ listOf(FakeTool("battery")) })

        assertFalse(report.isWellFormed)
        assertTrue(report.malformed.any { it.contains("is not lowercase verb.noun") })
    }

    @Test
    fun `a two word noun in a tool name is accepted`() {
        // `device.open_settings` is the contract's stated allowance.
        val report = ToolRegistryFactory.assemble({ listOf(FakeTool("device.open_settings")) })

        assertTrue("unexpected: ${report.malformed}", report.isWellFormed)
    }

    @Test
    fun `a schema with no required array is reported`() {
        val report = ToolRegistryFactory.assemble({ listOf(malformedSchemaTool()) })

        assertFalse(report.isWellFormed)
        assertTrue(
            "expected a required-array complaint, got ${report.malformed}",
            report.malformed.any { it.contains("no required array") },
        )
    }

    @Test
    fun `an untyped schema property is reported`() {
        val report = ToolRegistryFactory.assemble({ listOf(malformedSchemaTool()) })

        assertTrue(
            "expected an untyped-property complaint, got ${report.malformed}",
            report.malformed.any { it.contains("property 'untyped' has no type") },
        )
    }

    @Test
    fun `a schema that is not an object type is reported`() {
        val report = ToolRegistryFactory.assemble({
            listOf(
                FakeTool(
                    name = "device.battery",
                    schema = buildJsonObject {
                        put("type", "string")
                        put("properties", buildJsonObject { })
                        put("required", kotlinx.serialization.json.buildJsonArray { })
                    },
                ),
            )
        })

        assertTrue(report.malformed.any { it.contains("schema type is not object") })
    }

    @Test
    fun `a blank category is reported`() {
        val report = ToolRegistryFactory.assemble({ listOf(FakeTool("device.battery", category = "")) })

        assertTrue(report.malformed.any { it.contains("category is blank") })
    }

    @Test
    fun `the report names the offending tool`() {
        val report = ToolRegistryFactory.assemble({ listOf(malformedSchemaTool("device.broken")) })

        assertTrue(
            "a person fixing this needs to know which tool: ${report.malformed}",
            report.malformed.all { it.startsWith("device.broken:") },
        )
    }

    // --------------------------------------------------- the real wave-2 tools

    @Test
    fun `the real context-free tools satisfy the contract`() {
        // `WebFetchTool` and the notification tools take no `Context`, so their
        // actual shipped definitions are checked here rather than a stand-in.
        val real: List<AgentTool> = contextFreeRealTools()

        val report = ToolRegistryFactory.assemble { real }

        assertTrue("real tools are malformed: ${report.malformed}", report.isWellFormed)
        assertEquals(2, report.registry.all().size)
    }

    @Test
    fun `the real context-free tools have unique names`() {
        val names = contextFreeRealTools().map { it.definition.name }

        assertEquals(names.size, names.toSet().size)
    }

    @Test
    fun `every real context-free tool has a non-empty description`() {
        contextFreeRealTools().forEach { tool ->
            assertTrue(
                "${tool.definition.name} has no description",
                tool.definition.description.isNotBlank(),
            )
        }
    }
}
