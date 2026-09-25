package dev.localintelligence.android.tools

import android.content.Context
import android.content.ContextWrapper
import dev.localintelligence.android.tools.apps.AppsListTool
import dev.localintelligence.android.tools.apps.AppsOpenTool
import dev.localintelligence.android.tools.apps.AppsShareTool
import dev.localintelligence.android.tools.apps.appTools
import dev.localintelligence.android.tools.files.FilesDeleteTool
import dev.localintelligence.android.tools.files.FilesListTool
import dev.localintelligence.android.tools.files.FilesReadTextTool
import dev.localintelligence.android.tools.files.FilesSearchTool
import dev.localintelligence.android.tools.files.FilesWriteTextTool
import dev.localintelligence.core.tool.AgentTool
import dev.localintelligence.core.tool.ToolSchemaValidator
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Android-side guard against a malformed `required` field. (P0)
 *
 * ## The bug
 *
 * Eight tool definitions emitted `required` as a JSON **object**:
 *
 * ```kotlin
 * put("required", buildJsonObject {})                        // {}
 * put("required", buildJsonObject { put("uri", "A content:// URI.") })  // a description map
 * ```
 *
 * JSON Schema requires an **array of strings**. The object form is ignored by every
 * consumer — `GrammarBuilder` reads the field with `as? JsonArray`, gets `null`,
 * `.orEmpty()`s it, and emits a grammar in which *no* argument is mandatory. So a
 * grammar-constrained model was never forced to supply the one argument the tool
 * cannot work without, and the failure surfaced at execution time instead of at the
 * schema.
 *
 * Nothing crashed and no test went red when this was introduced, which is exactly
 * why it needs a mechanical, total check rather than a review rule.
 *
 * ## Why these tests are total
 *
 * [every tool in the module is checked] walks the full assembled tool set, so a
 * NINTH tool written by a future agent is covered the day it lands. The per-tool
 * tests below additionally pin the eight that were wrong, so a regression names the
 * tool that broke instead of reporting one anonymous offender.
 *
 * ## Why the tools can be built here
 *
 * There is no Robolectric on this classpath, so a real `Context` cannot execute.
 * But every `AgentTool.definition` is a `val` initialised in the constructor from
 * literals — it never touches `appContext`. Allocating a `ContextWrapper` without
 * running its constructor (via `Unsafe.allocateInstance`) therefore yields a Context
 * inert enough to satisfy the parameter, while the tool's own constructor runs
 * normally and produces the genuine schema. That is what makes this a real test of
 * the shipped definitions rather than of a copy of them.
 */
class ToolSchemaRequiredRegressionTest {

    /**
     * A `Context` that is never dereferenced. The tool constructors only store it.
     */
    private fun inertContext(): Context {
        val field = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe")
        field.isAccessible = true
        val unsafe = field.get(null)
        return field.type.getMethod("allocateInstance", Class::class.java)
            .invoke(unsafe, ContextWrapper::class.java) as Context
    }

    private fun toolContext(): Context = inertContext()

    /** Every tool this module ships, assembled the way production assembles it. */
    private fun allAndroidTools(): List<AgentTool> {
        val ctx = toolContext()
        return buildList {
            addAll(appTools(ctx))
            addAll(dev.localintelligence.android.tools.files.fileTools(ctx))
        }
    }

    // ------------------------------------------------- the repo-wide guard

    @Test
    fun `every tool in the module is checked`() {
        // Guards the guard: if the assembled set ever comes back empty, every
        // "all tools are valid" assertion below would pass vacuously.
        assertTrue(
            "expected the assembled Android tool set to be non-empty",
            allAndroidTools().size >= 8,
        )
    }

    @Test
    fun `no Android tool ships a required that is not a JSON array of strings`() {
        val offenders = allAndroidTools().mapNotNull { tool ->
            val def = tool.definition
            val required = def.schema["required"]
            if (required != null && required !is JsonArray) {
                "${def.name}: \"required\" is ${required::class.simpleName} ($required), " +
                    "not a JSON array"
            } else {
                null
            }
        }
        assertEquals(
            "A tool schema declares \"required\" as something other than a JSON array. " +
                "JSON Schema requires an array of strings; an object is a description " +
                "map that every consumer silently ignores, so the tool accepts calls " +
                "that omit a mandatory argument. Emit putJsonArray(\"required\") { ... }.",
            emptyList<String>(),
            offenders,
        )
    }

    @Test
    fun `no Android tool violates the full schema contract`() {
        val offenders = allAndroidTools().flatMap { tool ->
            val def = tool.definition
            ToolSchemaValidator.violations(def.name, def.schema).map { "${def.name}: $it" }
        }
        assertEquals(
            "Tool schema structural violations. See ToolSchemaValidator for the rules " +
                "and why each one fails silently.",
            emptyList<String>(),
            offenders,
        )
    }

    @Test
    fun `no Android tool requires an argument it does not declare`() {
        val offenders = allAndroidTools().mapNotNull { tool ->
            val def = tool.definition
            val properties = def.schema["properties"] ?: return@mapNotNull null
            val declared = (properties as? kotlinx.serialization.json.JsonObject)?.keys
                ?: return@mapNotNull null
            val dangling = ToolSchemaValidator.requiredArguments(def.schema)
                ?.filterNot { it in declared }
                ?: return@mapNotNull null
            if (dangling.isEmpty()) null else "${def.name}: not declared in properties: $dangling"
        }
        assertEquals(
            "A tool requires an argument its own schema never declares, describing a " +
                "call the model can never satisfy.",
            emptyList<String>(),
            offenders,
        )
    }

    // ------------------------------------- the eight tools that were wrong

    /**
     * The eight sites fixed in this change, with the `required` value each one must
     * now carry. The *reason* each is mandatory or optional lives in the tool's
     * `execute()`, and the PR body argues it; this test is the mechanical pin.
     */
    @Test
    fun `the eight fixed tools declare the required arguments their execute needs`() {
        val ctx = toolContext()
        val expected = mapOf(
            // No argument is mandatory: `limit` has a default and readRows() works
            // with no arguments at all.
            FilesListTool(ctx).definition.name to emptyList(),
            // `query` is NOT mandatory. execute() accepts ANY ONE of query / mime /
            // modified_after / modified_before and refuses only when all four are
            // absent — a constraint JSON Schema `required` cannot express, so the
            // honest encoding is the empty array plus the runtime check that
            // already exists.
            FilesSearchTool(ctx).definition.name to emptyList(),
            // `uri` is the only argument; execute() returns
            // InvalidArguments("missing uri") without it.
            FilesReadTextTool(ctx).definition.name to listOf("uri"),
            // `content` is mandatory: execute() refuses null/empty content before
            // doing anything else. `uri` and `name` stay optional because omitting
            // them means "create a new file in Downloads", a supported path.
            FilesWriteTextTool(ctx).definition.name to listOf("content"),
            // Either `uri` OR `name` suffices; execute() refuses only when both are
            // absent. Neither is individually mandatory.
            FilesDeleteTool(ctx).definition.name to emptyList(),
            // `query` and `limit` both have defaults.
            AppsListTool(ctx).definition.name to emptyList(),
            // Either `package` OR `name`; execute() refuses only when both absent.
            AppsOpenTool(ctx).definition.name to emptyList(),
            // Either `uri` OR `text`; AppShareTarget.resolve() returns
            // ShareTarget.Nothing when both are absent.
            AppsShareTool(ctx).definition.name to emptyList(),
        )
        assertEquals(
            "the eight tools fixed in this change must each be pinned here; found " +
                expected.size,
            8,
            expected.size,
        )

        expected.forEach { (name, required) ->
            val tool = allAndroidTools().first { it.definition.name == name }
            val schema = tool.definition.schema
            assertEquals(
                "$name: \"required\" must be a JSON array",
                JsonArray::class.java,
                schema["required"]!!::class.java,
            )
            assertEquals(
                "$name: wrong required arguments",
                required,
                ToolSchemaValidator.requiredArguments(schema),
            )
            // The empty-array cases are the ones most likely to be "helpfully"
            // rewritten into an object, so assert the array-ness directly.
            val entries = (schema["required"] as JsonArray).map { (it as JsonPrimitive).content }
            assertEquals("$name: wrong required entries", required, entries)
        }
    }

    @Test
    fun `the two mandatory-argument tools are readable by a consumer that casts to an array`() {
        // This is the exact consumer the P0 disabled. A `as? JsonArray` returns
        // null for the old object form and silently yields "nothing is mandatory",
        // so asserting non-null here is asserting the fix is observable.
        val ctx = toolContext()
        listOf(
            FilesReadTextTool(ctx).definition,
            FilesWriteTextTool(ctx).definition,
        ).forEach { def ->
            val required = def.schema["required"] as? JsonArray
            assertNotNull(
                "${def.name}: GrammarBuilder reads \"required\" with `as? JsonArray`; " +
                    "a non-array makes it null and every argument optional",
                required,
            )
            assertTrue(
                "${def.name}: expected a non-empty required array",
                required!!.isNotEmpty(),
            )
        }
    }
}
