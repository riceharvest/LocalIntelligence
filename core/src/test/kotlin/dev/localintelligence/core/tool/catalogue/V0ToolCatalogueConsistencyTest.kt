package dev.localintelligence.core.tool.catalogue

import dev.localintelligence.core.context.TokenEstimate
import dev.localintelligence.core.tool.ToolDefinition
import dev.localintelligence.core.tool.ToolRisk
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Structural invariants of the canonical v0 catalogue.
 *
 * None of these tests call Android. All of them run in milliseconds, and every one of
 * them exists because its failure mode is silent: a malformed schema does not crash,
 * it just makes the model emit arguments the tool will reject, four steps into a task
 * that has already burned the context window.
 */
class V0ToolCatalogueConsistencyTest {

    private val defs = V0ToolCatalogue.definitions

    // ------------------------------------------------------------------- names

    @Test
    fun `names are unique`() {
        val dupes = defs.groupBy { it.name }.filterValues { it.size > 1 }.keys
        assertEquals("duplicate tool names: $dupes", emptySet<String>(), dupes)
    }

    @Test
    fun `names match the strict dot-namespaced lowercase regex`() {
        val bad = defs.map { it.name }.filterNot { NAME_REGEX.matches(it) }
        assertEquals(
            "names must be lowercase, dot-namespaced verb.noun with snake_case nouns: $bad",
            emptyList<String>(),
            bad,
        )
    }

    @Test
    fun `every name is verb dot noun`() {
        val bad = defs.filter { !VERB_NOUN.matches(it.name) }.map { it.name }
        assertEquals("expected verb.noun shape: $bad", emptyList<String>(), bad)
    }

    @Test
    fun `every category equals the first segment of its name`() {
        val bad = defs.filter { it.name.substringBefore('.') != it.category }
            .map { "${it.name} in category ${it.category}" }
        assertEquals("category must match the name's namespace: $bad", emptyList<String>(), bad)
    }

    @Test
    fun `every category is declared in the catalogue category list`() {
        val declared = V0ToolCatalogue.categories.toSet()
        val used = defs.map { it.category }.toSet()
        assertEquals(
            "categories used but not declared: ${used - declared}",
            emptySet<String>(),
            used - declared,
        )
        assertEquals(
            "categories declared but unused: ${declared - used}",
            emptySet<String>(),
            declared - used,
        )
    }

    // ----------------------------------------------------------------- schemas

    @Test
    fun `every schema is a JSON Schema object with a type`() {
        for (def in defs) {
            assertEquals("${def.name}: schema type", JsonPrimitive("object"), def.schema["type"])
            assertTrue(
                "${def.name}: schema must be non-empty",
                def.schema.isNotEmpty(),
            )
        }
    }

    @Test
    fun `every schema has a properties object`() {
        for (def in defs) {
            assertTrue(
                "${def.name}: schema.properties must be an object, was ${def.schema["properties"]}",
                def.schema["properties"] is JsonObject,
            )
        }
    }

    @Test
    fun `required is a JSON array and every entry exists in properties`() {
        for (def in defs) {
            val required = def.schema["required"]
            assertTrue(
                "${def.name}: required must be a JSON array, " +
                "was ${required?.javaClass?.simpleName}",
                required is JsonArray,
            )
            val declared = (def.schema["properties"] as JsonObject).keys
            val entries = (required as JsonArray).map { (it as JsonPrimitive).content }
            val unknown = entries.filterNot { it in declared }
            assertEquals(
                "${def.name}: required names not present in properties: $unknown",
                emptyList<String>(),
                unknown,
            )
            assertEquals(
                "${def.name}: required has duplicates: $entries",
                entries.size,
                entries.distinct().size,
            )
        }
    }

    @Test
    fun `every declared property has a type and a description`() {
        for (def in defs) {
            val properties = def.schema["properties"] as JsonObject
            for ((key, value) in properties) {
                val prop = value as? JsonObject
                    ?: error("${def.name}.$key: property must be an object, was $value")
                assertEquals(
                    "${def.name}.$key: missing type",
                    true,
                    prop["type"] is JsonPrimitive,
                )
                val desc = (prop["description"] as? JsonPrimitive)?.content
                assertTrue(
                    "${def.name}.$key: property description is what the model reads, it must exist",
                    !desc.isNullOrBlank(),
                )
            }
        }
    }

    @Test
    fun `additionalProperties is false so schema and validator agree`() {
        for (def in defs) {
            assertEquals(
                "${def.name}: additionalProperties",
                JsonPrimitive(false),
                def.schema["additionalProperties"],
            )
        }
    }

    @Test
    fun `argument names are snake_case`() {
        val bad = defs.flatMap { def ->
            (def.schema["properties"] as JsonObject).keys
                .filterNot { SNAKE_CASE.matches(it) }
                .map { "${def.name}: $it" }
        }
        assertEquals("argument names must be snake_case: $bad", emptyList<String>(), bad)
    }

    // ------------------------------------------------- descriptions and tags

    @Test
    fun `no tool has an empty description`() {
        val bad = defs.filter { it.description.isBlank() }.map { it.name }
        assertEquals("empty description: $bad", emptyList<String>(), bad)
    }

    @Test
    fun `descriptions are one or two sentences and start with a capital`() {
        for (def in defs) {
            val d = def.description
            assertTrue("${def.name}: description must start with a capital", d[0].isUpperCase())
            // A '.' inside a dotted tool name ("contacts.search") is not a sentence
            // boundary, so only a period followed by whitespace or end-of-string counts.
            val sentences = SENTENCE_END.findAll(d).count()
            assertTrue(
                "${def.name}: description is $sentences sentences, budget is 2 ($d)",
                sentences <= 2,
            )
            assertTrue("${def.name}: description must end in a period", d.endsWith("."))
        }
    }

    @Test
    fun `no tool has zero tags and every tag is lowercase and non-blank`() {
        for (def in defs) {
            assertTrue("${def.name}: zero tags", def.tags.isNotEmpty())
            val bad = def.tags.filter { it.isBlank() || it != it.lowercase() || it != it.trim() }
            assertEquals(
                "${def.name}: tags must be lowercase and trimmed: $bad",
                emptyList<String>(),
                bad,
            )
        }
    }

    @Test
    fun `tag count per tool stays in the 4-8 band`() {
        for (def in defs) {
            assertTrue(
                "${def.name}: ${def.tags.size} tags, contract asks for 4-8",
                def.tags.size in 4..8,
            )
        }
    }

    @Test
    fun `description token budget is respected`() {
        val used = defs.sumOf { TokenEstimate.tokens(it.description) }
        assertTrue(
            "description text costs $used tokens, budget is " +
                "${V0ToolCatalogue.DESCRIPTION_TOKEN_BUDGET}",
            used <= V0ToolCatalogue.DESCRIPTION_TOKEN_BUDGET,
        )
    }

    @Test
    fun `tag budget is respected`() {
        val used = defs.sumOf { it.tags.size }
        assertTrue(
            "catalogue carries $used tags, budget is ${V0ToolCatalogue.TAG_BUDGET}",
            used <= V0ToolCatalogue.TAG_BUDGET,
        )
    }

    // ------------------------------------------------------------- description
    // similarity, i.e. the retrieval-collision ceiling.

    @Test
    fun `no two descriptions are near-identical`() {
        val worst = worstPair()
        assertTrue(
            "descriptions for ${worst.a.name} and ${worst.b.name} are " +
                "${"%.3f".format(worst.score)} " +
                "similar (Jaccard ${"%.3f".format(worst.jaccard)}, trigram " +
                "${"%.3f".format(worst.trigram)}), ceiling is $SIMILARITY_CEILING. " +
                "They will collide during lexical retrieval.",
            worst.score < SIMILARITY_CEILING,
        )
    }

    @Test
    fun `the two nearest-description pairs are reported so regressions are visible`() {
        val ranked = allPairs().sortedByDescending { it.score }.take(5)
        val report = ranked.joinToString("\n") { pair ->
            "  %.3f  %-22s %-22s".format(pair.score, pair.a.name, pair.b.name)
        }
        println("closest description pairs (Jaccard, ceiling $SIMILARITY_CEILING):\n$report")
        assertEquals("expected 5 pairs", 5, ranked.size)
    }

    @Test
    fun `no two tools share more than half their tag set`() {
        val clashes = defs.flatMap { a ->
            defs.filter { b -> b.name > a.name }
                .map { b -> a to b }
        }.mapNotNull { (a, b) ->
            val shared = a.tags.intersect(b.tags)
            val jaccard = shared.size.toDouble() /
                (a.tags.size + b.tags.size - shared.size).toDouble()
            if (jaccard > TAG_JACCARD_CEILING) a.name to b.name to jaccard else null
        }
        assertTrue(
            "tools sharing too many tags will retrieve as the same tool: " +
                clashes.joinToString {
                    "${it.first.first}/${it.first.second}=${"%.2f".format(it.second)}"
                },
            clashes.isEmpty(),
        )
    }

    // ------------------------------------------------------------------- risk

    @Test
    fun `every tool has an explicit risk and none is PRIVILEGED`() {
        val privileged = defs.filter { it.risk == ToolRisk.PRIVILEGED }.map { it.name }
        assertEquals(
            "PRIVILEGED is disabled in v0 (docs/architecture.md 8): $privileged",
            emptyList<String>(),
            privileged,
        )
        // Risk is a non-nullable constructor argument, so "left default" is impossible
        // by type. What the type does not enforce is that the tier was *chosen*, so pin
        // the set of tiers in use and fail if an unexpected one appears.
        val inUse = defs.map { it.risk }.toSet()
        assertEquals(
            "unexpected risk tier(s) in the catalogue: ${inUse - EXPECTED_TIERS}",
            emptySet<ToolRisk>(),
            inUse - EXPECTED_TIERS,
        )
    }

    @Test
    fun `the read-only set is exactly the set that mutates nothing`() {
        val expectedReadOnly = setOf(
            "files.list", "files.search", "files.read_text",
            "apps.list",
            "clipboard.read",
            "device.battery", "device.info",
            "alarm.list",
            "calendar.search",
            "contacts.search", "contacts.get",
            "notifications.list",
            "web.fetch",
        )
        val actual = defs.filter { it.risk == ToolRisk.READ_ONLY }.map { it.name }.toSet()
        assertEquals(
            "READ_ONLY is a contract: a tool that writes must not be filed here, and a " +
                "tool that only reads must not be filed elsewhere. Change this list only " +
                "alongside a deliberate decision in the PR.",
            expectedReadOnly,
            actual,
        )
    }

    @Test
    fun `every tool that changes device state is at least REVERSIBLE`() {
        val tooLow = defs
            .filter { it.name in MUTATING_TOOLS }
            .filter { it.risk == ToolRisk.READ_ONLY }
            .map { it.name }
        assertEquals(
            "these tools change state on the device and cannot be READ_ONLY: $tooLow",
            emptyList<String>(),
            tooLow,
        )
    }

    @Test
    fun `gated tools either need a permission or are listed as needing none`() {
        // Confirmation is derived from risk, not from requiredPermission, so a gated
        // tool with no Android permission is legal: apps.share hands off to the system
        // share sheet, which prompts for the destination itself. What must not happen
        // is a gated tool whose permission status is unstated.
        val missing = defs
            .filter { it.risk.requiresConfirmation }
            .filter { it.requiredPermission.isNullOrBlank() }
            .filterNot { it.name in GATED_WITHOUT_PERMISSION }
            .map { it.name }
        assertEquals(
            "a tool the runtime will gate on confirmation must either declare what it " +
                "needs or be listed in GATED_WITHOUT_PERMISSION with a reason: $missing",
            emptyList<String>(),
            missing,
        )
        // And the converse: nothing on the exemption list may start needing one.
        val stale = GATED_WITHOUT_PERMISSION
            .mapNotNull { V0ToolCatalogue.byName(it) }
            .filterNot { it.requiredPermission.isNullOrBlank() }
            .map { it.name }
        assertEquals(
            "these tools now declare a permission, drop them from GATED_WITHOUT_PERMISSION: $stale",
            emptyList<String>(),
            stale,
        )
    }

    @Test
    fun `confirmation gating is exercised on both sides`() {
        val gated = defs.filter { it.risk.requiresConfirmation }
        assertTrue("expected some gated tools, got none", gated.isNotEmpty())
        val ungated = defs.filterNot { it.risk.requiresConfirmation }
        assertTrue("expected some ungated tools, got none", ungated.isNotEmpty())
    }

    @Test
    fun `every declared required permission is a real Android permission or service binding`() {
        val offenders = defs
            .mapNotNull { it.requiredPermission }
            .filter { it.isNotBlank() }
            .filterNot { PERMISSION_LIKE.matches(it.substringBefore(' ')) }
            .map { "unrecognised permission declaration: $it" }
        assertEquals(
            "requiredPermission is documentation the UI shows; a value that is neither a " +
                "permission nor a service binding teaches nobody anything: $offenders",
            emptyList<String>(),
            offenders,
        )
    }

    @Test
    fun `no tool invents a permission that is not a real Android one`() {
        // The substring before the first space is the permission name. Anything that
        // does not exist on the platform would send a user to a Settings screen that
        // does not exist.
        val known = KNOWN_PERMISSIONS
        val invented = defs.mapNotNull { it.requiredPermission }
            .map { it.substringBefore(' ') }
            .filter { it.startsWith("android.permission.") }
            .filterNot { it in known }
        assertEquals(
            "these permission names are not in the known set for this catalogue: $invented",
            emptyList<String>(),
            invented,
        )
    }

    // ---------------------------------------------------------- accessors and
    // invariants the Android layer depends on.

    @Test
    fun `byName and byCategory agree with the flat list`() {
        for (def in defs) {
            assertEquals(def, V0ToolCatalogue.byName(def.name))
        }
        for (category in V0ToolCatalogue.categories) {
            val inCategory = V0ToolCatalogue.byCategory(category)
            assertTrue("category $category is empty", inCategory.isNotEmpty())
            assertTrue(
                "category $category contains a tool from another category",
                inCategory.all { it.category == category },
            )
        }
        assertEquals(null, V0ToolCatalogue.byName("nope.nope"))
    }

    @Test
    fun `select skips unknown names instead of throwing`() {
        val selected = V0ToolCatalogue.select(listOf("web.fetch", "not.a.tool", "alarm.create"))
        assertEquals(listOf("web.fetch", "alarm.create"), selected.map { it.name })
    }

    @Test
    fun `catalogue is small enough to stay legible`() {
        assertTrue(
            "catalogue has ${defs.size} tools; above ~30 the retrieval race gets expensive " +
                "and the descriptions stop being maintained",
            defs.size <= 30,
        )
    }

    // ------------------------------------------------------------- similarity

    private data class Pair(val a: ToolDefinition, val b: ToolDefinition, val score: Double) {
        val jaccard: Double get() = jaccard(a.description, b.description)
        val trigram: Double get() = trigramSimilarity(a.description, b.description)
    }

    private fun allPairs(): List<Pair> = defs.flatMap { a ->
        defs.filter { it.name > a.name }
            .map { b -> Pair(a, b, similarity(a.description, b.description)) }
    }

    private fun worstPair(): Pair = allPairs().maxBy { it.score }

    companion object {
        /**
         * Ceiling on description similarity, as `max(token Jaccard, character-trigram
         * Jaccard)`. Two descriptions above this will score almost identically against
         * any task, so the selector can only break the tie alphabetically — which is
         * worse than losing, because the model is shown a tool and never told it lost.
         */
        const val SIMILARITY_CEILING = 0.55

        /** Two tools sharing more than this fraction of tags retrieve as one tool. */
        const val TAG_JACCARD_CEILING = 0.5

        /** Risk tiers the v0 catalogue is allowed to use. */
        private val EXPECTED_TIERS = setOf(
            ToolRisk.READ_ONLY,
            ToolRisk.REVERSIBLE,
            ToolRisk.DESTRUCTIVE,
            ToolRisk.EXTERNAL_COMMUNICATION,
        )

        /** Tools that change state on the device, by construction, not by inspection. */
        private val MUTATING_TOOLS = setOf(
            "clipboard.write", "apps.open", "apps.share", "device.open_settings",
            "device.vibrate", "alarm.create", "alarm.cancel", "files.write_text",
            "files.delete", "calendar.create", "calendar.delete", "notifications.reply",
            "notifications.dismiss",
        )

        private val SENTENCE_END = Regex("\\.(?=\\s|$)")

        /** Either a runtime permission or a named service-binding grant. */
        /**
         * Matches the permission token at the head of a `requiredPermission` string.
         * The string may carry an explanatory suffix
         * ("android.permission.WRITE_EXTERNAL_STORAGE (API<=28 only) or a SAF grant"),
         * so only the part before the first space is the name being validated.
         */
        private val PERMISSION_LIKE =
            Regex("^(android\\.permission\\.[A-Z_]+|[A-Z_]+_SERVICE)$")

        /**
         * The Android permissions this catalogue names. Kept explicit so a typo in a
         * permission string is a failing test rather than a dead end in a Settings
         * screen on a user's phone.
         */
        private val KNOWN_PERMISSIONS = setOf(
            "android.permission.READ_EXTERNAL_STORAGE",
            "android.permission.WRITE_EXTERNAL_STORAGE",
            "android.permission.READ_CALENDAR",
            "android.permission.WRITE_CALENDAR",
            "android.permission.READ_CONTACTS",
            "android.permission.VIBRATE",
            "android.permission.SCHEDULE_EXACT_ALARM",
            "android.permission.INTERNET",
            "android.permission.POST_NOTIFICATIONS",
        )

        /**
         * Gated tools that need no Android permission, and why. The runtime still
         * confirms them; the platform does the rest.
         */
        private val GATED_WITHOUT_PERMISSION = setOf(
            // Confirmed by the runtime, then handed to ACTION_SEND, which shows the
            // system share sheet. The user picks the destination in the sheet, so
            // there is no permission for this app to hold.
            "apps.share",
        )

        private val NAME_REGEX =
            Regex("^[a-z][a-z0-9]*(\\.[a-z][a-z0-9]*(_[a-z0-9]+)*)+$")
        private val VERB_NOUN =
            Regex("^[a-z][a-z0-9]*\\.[a-z][a-z0-9]*(_[a-z0-9]+)*$")
        private val SNAKE_CASE = Regex("^[a-z][a-z0-9]*(_[a-z0-9]+)*$")

        fun tokens(text: String): Set<String> = text
            .lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length > 2 }
            .toSet()

        private fun trigrams(text: String): Set<String> {
            val flat = text.lowercase().filter { it.isLetterOrDigit() || it == ' ' }
            if (flat.length < 3) return setOf(flat)
            return (0..flat.length - 3).map { flat.substring(it, it + 3) }.toSet()
        }

        fun jaccard(a: String, b: String): Double {
            val sa = tokens(a)
            val sb = tokens(b)
            if (sa.isEmpty() && sb.isEmpty()) return 1.0
            val shared = sa.intersect(sb).size
            val union = sa.union(sb).size
            return if (union == 0) 1.0 else shared.toDouble() / union
        }

        fun trigramSimilarity(a: String, b: String): Double {
            val shared = trigrams(a).intersect(trigrams(b)).size
            val union = trigrams(a).union(trigrams(b)).size
            return if (union == 0) 1.0 else shared.toDouble() / union
        }

        fun similarity(a: String, b: String): Double =
            maxOf(jaccard(a, b), trigramSimilarity(a, b))
    }
}
