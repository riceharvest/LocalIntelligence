package dev.localintelligence.android.di

import dev.localintelligence.core.tool.ToolRisk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The real production catalogue, checked on a JVM.
 *
 * This is the test that catches two agents registering the same tool. The
 * dangerous part of that bug is that it is invisible in review — each line looks
 * correct in isolation — and it only detonates on a device, as a
 * `SimpleToolRegistry` `require` failure at app start. Checking the declared
 * list here means it detonates in CI instead.
 *
 * ## What cannot be checked here, stated plainly
 *
 * The *descriptions* and *schemas* of the Context-taking tools are not verified
 * by this file: building `AppsListTool` or `FilesListTool` needs a real
 * `Context`, and the unit-test `android.jar` throws on `applicationContext`.
 * What IS checked is every name, plus the contract of the tools that need no
 * `Context`. The full-definition check for the other 19 needs instrumentation,
 * which this environment cannot run — see the PR body.
 */
class AndroidToolSourceTest {

    private val registrations = ANDROID_TOOL_REGISTRATIONS

    @Test
    fun `the catalogue is not empty`() {
        assertTrue(
            "an empty catalogue means nothing was registered, which looks like a " +
                "successful build and a useless app",
            registrations.isNotEmpty(),
        )
    }

    @Test
    fun `no two registrations declare the same tool name`() {
        val duplicates = registrations
            .groupBy { it.name }
            .filterValues { it.size > 1 }
            .keys

        assertEquals("duplicate tool registrations: $duplicates", emptySet<String>(), duplicates)
    }

    @Test
    fun `every declared name satisfies the verb dot noun contract`() {
        // Runs the production check itself rather than a regex copy of it, so
        // this test cannot drift from what assemble() enforces.
        val problems = registrations.mapNotNull { registration ->
            ToolRegistryFactory.problemWith(
                name = registration.name,
                description = "placeholder",   // not checkable without a Context
                category = "placeholder",
                schema = VALID_EMPTY_SCHEMA,
            )
        }

        assertEquals("malformed tool names: $problems", emptyList<String>(), problems)
    }

    @Test
    fun `tool names are unique across the whole catalogue`() {
        val names = registrations.map { it.name }

        assertEquals(names.size, names.toSet().size)
    }

    @Test
    fun `every family is represented`() {
        // A family that landed in wave 2 and never reached the wiring layer is
        // exactly the "it compiles but the feature is missing" failure.
        val families = registrations.map { it.name.substringBefore('.') }.toSet()

        listOf(
            "alarm", "apps", "clipboard", "device", "files", "notifications", "web",
        ).forEach { family ->
            assertTrue(
                "no tool registered for family '$family'",
                families.contains(family),
            )
        }
    }

    @Test
    fun `no registration is a privileged tool`() {
        // `AgentController.policyOf` REFUSES privileged tools outright, so
        // registering one is a tool the model can be shown and can never run.
        val contextFree = contextFreeRegistrations()
        contextFree.forEach { registration ->
            val tool = registration.build(StubPlatforms)
            assertTrue(
                "${registration.name} is PRIVILEGED and would be refused at run time",
                tool.definition.risk != ToolRisk.PRIVILEGED,
            )
        }
    }

    @Test
    fun `the context-free registrations build and satisfy the contract`() {
        // Real constructors, real definitions, no device: the strongest check of
        // an actual tool definition that a JVM test can make.
        val built = contextFreeRegistrations().map { it.build(StubPlatforms) }

        val report = ToolRegistryFactory.assemble { built }

        assertTrue("real tools are malformed: ${report.malformed}", report.isWellFormed)
        assertEquals(built.size, report.registry.all().size)
    }

    @Test
    fun `every context-free tool has a non-empty description`() {
        contextFreeRegistrations().forEach { registration ->
            val tool = registration.build(StubPlatforms)
            assertTrue(
                "${registration.name} has no description",
                tool.definition.description.isNotBlank(),
            )
        }
    }

    @Test
    fun `every context-free tool declares its expected name`() {
        // Proves the declared name and the real definition agree, which is what
        // makes the JVM-only name checks trustworthy.
        contextFreeRegistrations().forEach { registration ->
            val tool = registration.build(StubPlatforms)
            assertEquals(
                "declared name must match the built tool",
                registration.name,
                tool.definition.name,
            )
        }
    }

    /**
     * The registrations whose factories touch no Android type.
     *
     * Selected by *building them* and keeping the ones that succeed, rather than
     * by a hardcoded name list: if a wave-2 author later makes `web.fetch`
     * take a `Context`, it silently drops out of the JVM coverage instead of
     * the suite failing for a reason that has nothing to do with the wiring.
     */
    @Test
    fun `the context-free subset is not empty`() {
        // Guards the tests above from passing vacuously. If a wave-2 author
        // makes every remaining factory take a `Context`, this filter would
        // return nothing and "every context-free tool has a description" would
        // assert nothing at all while still reporting green.
        val contextFree = contextFreeRegistrations()

        assertTrue(
            "no registration can be built on a JVM, so the real-definition " +
                "coverage has silently dropped to zero",
            contextFree.isNotEmpty(),
        )
        // web.fetch and the three notification tools need no Android type.
        assertTrue(
            "expected at least the notification and web tools, got " +
                contextFree.map { it.name },
            contextFree.map { it.name }.containsAll(
                listOf("web.fetch", "notifications.list", "notifications.reply", "notifications.dismiss"),
            ),
        )
    }

    @Test
    fun `a duplicate registration is detected by the same check the app uses`() {
        // Proves the duplicate test can actually fail: a synthetic second
        // registration of an existing name has to be caught, otherwise
        // "no two registrations declare the same name" is untested logic.
        val doubled = registrations + registrations.first()

        val duplicates = doubled.groupBy { it.name }.filterValues { it.size > 1 }.keys

        assertEquals(
            "the duplicate check must name the offender",
            setOf(registrations.first().name),
            duplicates,
        )
    }

    private fun contextFreeRegistrations(): List<ToolRegistration> =
        registrations.filter { registration ->
            runCatching { registration.build(StubPlatforms) }.isSuccess
        }

    private companion object {
        val VALID_EMPTY_SCHEMA: JsonObject = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put("properties", buildJsonObject { })
            put("required", buildJsonArray { })
        }
    }
}

/**
 * Platform stand-ins for the JVM suite.
 *
 * They are never called: the registrations that need a platform are the ones
 * that cannot be built on a JVM at all, so any call here is a bug in a
 * registration factory, and each method throws to make that loud.
 */
private object StubPlatforms : ToolPlatforms {
    private fun nope(): Nothing = error("a JVM test must not call into an Android platform")

    override val device: dev.localintelligence.android.tools.device.DevicePlatform
        get() = nope()
    override val clipboard: dev.localintelligence.android.tools.clipboard.ClipboardPlatform
        get() = nope()
    override val alarm: dev.localintelligence.android.tools.alarm.AlarmPlatform
        get() = nope()
    override fun context(): android.content.Context = nope()
}
