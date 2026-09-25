package dev.localintelligence.core.eval

import dev.localintelligence.core.tool.AgentTool
import dev.localintelligence.core.tool.SimpleToolRegistry
import dev.localintelligence.core.tool.ToolDefinition
import dev.localintelligence.core.tool.ToolRegistry
import dev.localintelligence.core.tool.ToolRisk
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// ===========================================================================
// AndroidToolRegistry.kt — the real tool set as one assertable object.
//
// WHAT THIS FILE ANSWERS
// ======================
//
// The 50-task suite validates the agent LOOP against mock tools. It says
// nothing about the tools themselves. Five tools can be perfectly looped and
// still be unusable, because a 1-3B model reliably chooses from 3-6 tools and
// not from 25.
//
// So this file treats the Android tool set as a single artifact with its own
// properties, and derives three things from it:
//
//   1. [AndroidToolCoherence] — structural rules. Names, categories, tags,
//      description shape, JSON Schema well-formedness, risk classification.
//      Violations are values, not exceptions, so a report can print all of
//      them at once instead of one per CI run.
//
//   2. [ToolOverlap] — which pairs of tools look alike to a lexical retriever.
//      Two tools that overlap heavily are two tools a small model will confuse,
//      and that confusion is a design bug in the tool set, not a model bug.
//
//   3. [AndroidToolSet] — the assembled registry plus the prompt-size budget,
//      which is the constraint the whole selection strategy exists to satisfy.
// ===========================================================================

/**
 * The assembled Android tool set.
 *
 * A thin wrapper over [SimpleToolRegistry] so the benchmark measures the real
 * registry behaviour — including the duplicate-name check, which
 * [SimpleToolRegistry]'s `init` block already enforces by throwing.
 */
class AndroidToolSet private constructor(
    val registry: ToolRegistry,
    val tools: List<AndroidStubTool>,
) {
    val definitions: List<ToolDefinition> get() = tools.map { it.definition }
    val size: Int get() = tools.size

    companion object {
        /**
         * Builds the registry from [AndroidToolStubs]. Duplicate names throw
         * from [SimpleToolRegistry] — deliberately: a duplicate is a shipping
         * bug, not a reportable finding, and a registry that silently keeps the
         * last one would make every name-uniqueness assertion below vacuous.
         */
        fun build(specs: List<AndroidToolSpec> = AndroidToolStubs.specs): AndroidToolSet {
            val tools = specs.map { spec ->
                AndroidStubTool(definition = AndroidToolStubs.definitionOf(spec), spec = spec)
            }
            return AndroidToolSet(SimpleToolRegistry(tools), tools)
        }
    }
}

// ---------------------------------------------------------------------------
// 1. Coherence
// ---------------------------------------------------------------------------

/** One broken rule, tied to the tool and the rule it broke. */
data class CoherenceViolation(
    val tool: String,
    val rule: String,
    val detail: String,
) {
    fun render(): String = "$tool  [$rule]  $detail"
}

/** The verdict over the whole set. Empty [violations] means coherent. */
data class CoherenceReport(val violations: List<CoherenceViolation>) {
    val coherent: Boolean get() = violations.isEmpty()
    fun forTool(name: String): List<CoherenceViolation> = violations.filter { it.tool == name }
    fun rulesBroken(): Set<String> = violations.map { it.rule }.toSet()
}

/**
 * Every structural rule a tool must satisfy, checked in one pass.
 *
 * The rules come from `docs/tool-contract.md` §"Adding a tool: the checklist",
 * and each one exists because its violation has a concrete failure mode on a
 * real device — a name the grammar cannot emit, a category that hides a tool
 * from a by-category query, a schema that rejects every argument, a description
 * a 3B model cannot compare against its neighbours.
 */
object AndroidToolCoherence {

    /** `verb.noun`, lowercase, no dots inside either half. */
    val NAME_PATTERN = Regex("^[a-z][a-z0-9_]*\\.[a-z][a-z0-9_]*$")

    /** Descriptions must fit the prompt budget; the contract's own ceiling. */
    const val MAX_DESCRIPTION_CHARS = 120

    const val MIN_TAGS = 4
    const val MAX_TAGS = 8

    /**
     * Verbs a description may start with.
     *
     * A whitelist, not a heuristic. "Starts with a verb" is undecidable in
     * general, and a benchmark that guesses would report noise. Pinning the list
     * means a tool author either uses one of these verbs or gets told exactly
     * which one to use — which is the actionable version of the same rule.
     */
    val ALLOWED_LEADING_VERBS: Set<String> = setOf(
        "reads", "lists", "writes", "searches", "creates", "cancels", "deletes",
        "opens", "launches", "shares", "sends", "fetches", "clears", "vibrates",
        "gets", "sets", "finds", "copies", "exports", "imports", "replies",
    )

    /** The JSON types a schema property may declare. */
    val JSON_TYPES: Set<String> = setOf("string", "integer", "number", "boolean", "array", "object")

    /**
     * Consequence phrases a risky tool's description must contain.
     *
     * The runtime shows the description in the confirmation dialog. A delete
     * that says only "Deletes a file" gives the user nothing to consent to, and
     * a share that says only "Shares a file" hides that the data leaves the
     * device. Requiring the consequence in the text is what makes the dialog
     * honest.
     */
    val DESTRUCTIVE_CONSEQUENCES: Set<String> = setOf(
        "permanently", "not moved to a trash", "cannot be undone", "irreversible",
        "will not be shown again", "is removed", "no recovery", "unrecoverable",
    )

    val EXTERNAL_CONSEQUENCES: Set<String> = setOf(
        "the recipient receives", "other app", "can then send it on", "leaves the device",
        "sent to", "another person", "third party", "posts to", "sends it",
    )

    fun check(definitions: List<ToolDefinition>): CoherenceReport =
        CoherenceReport(definitions.flatMap { checkOne(it) })

    /** The retriever's own tokenizer, so every rule here sees what it sees. */
    private fun tokensOf(text: String): List<String> =
        AndroidOverlapAnalyzer.tokenize(text)

    fun checkOne(def: ToolDefinition): List<CoherenceViolation> = buildList {
        checkName(def, this)
        checkCategory(def, this)
        checkTags(def, this)
        checkDescription(def, this)
        checkSchema(def, this)
        checkRisk(def, this)
    }

    private fun checkName(def: ToolDefinition, out: MutableList<CoherenceViolation>) {
        if (!NAME_PATTERN.matches(def.name)) {
            out += CoherenceViolation(
                def.name, "name-format",
                "must match ${NAME_PATTERN.pattern} (lowercase namespace.verb)",
            )
        }
        if (def.name != def.name.trim()) {
            out += CoherenceViolation(def.name, "name-whitespace", "name has surrounding whitespace")
        }
    }

    /**
     * The one naming rule this benchmark does NOT enforce: verb.noun.
     *
     * `docs/tool-contract.md` says "Name it verb.noun" and
     * `docs/wave1-contract.md` says "verb.noun, lowercase, dotted". Every
     * shipped name is in fact `namespace.verb` — `clipboard.write`,
     * `alarm.cancel`, `files.read_text` — with 9 of 25 breaking the rule
     * harder, as bare `noun.noun`: `device.battery`, `device.info`,
     * `alarm.list`, `files.list`, `apps.list`, `notifications.list`.
     *
     * Rather than pick a winner and fail 25 tests over it, the benchmark
     * measures the consequence and lets the owners decide. The consequence is
     * small but real: `LexicalToolSelector` weights name tokens 4x, and for a
     * bare `noun.noun` name the noun half still matches, so retrieval is not
     * hurt. The confusion risk is in the *model*, not the retriever: asked to
     * "call a tool", a small model reaches for the verb. So the doc and the
     * implementation disagree, and the fix belongs in the doc or in the names,
     * not in a test that picks for them.
     */
    fun namingFindings(specs: List<AndroidToolSpec> = AndroidToolStubs.specs): List<String> =
        specs.mapNotNull { spec ->
            val namespace = spec.name.substringBefore('.')
            if (namespace in ALLOWED_LEADING_VERBS) null
            else "${spec.name}: first half \"$namespace\" is a category, not a verb " +
                "(contract says verb.noun; the set is namespace.verb)"
        }

    private fun checkCategory(def: ToolDefinition, out: MutableList<CoherenceViolation>) {
        if (def.category !in AndroidToolStubs.categories) {
            out += CoherenceViolation(
                def.name, "category-known",
                "\"${def.category}\" is not one of ${AndroidToolStubs.categories.sorted()}",
            )
        }
        // Category must be the name's namespace, or byCategory() and the
        // package layout disagree and neither can be trusted as documentation.
        val namespace = def.name.substringBefore('.', "")
        if (namespace.isNotEmpty() && def.category != namespace) {
            out += CoherenceViolation(
                def.name, "category-namespace",
                "category \"${def.category}\" does not match the name's namespace \"$namespace\"",
            )
        }
    }

    private fun checkTags(def: ToolDefinition, out: MutableList<CoherenceViolation>) {
        if (def.tags.size < MIN_TAGS || def.tags.size > MAX_TAGS) {
            out += CoherenceViolation(
                def.name, "tag-count",
                "declares ${def.tags.size} tags, contract requires $MIN_TAGS-$MAX_TAGS",
            )
        }
        def.tags.filter { it != it.lowercase() }.forEach {
            out += CoherenceViolation(
                def.name, "tag-lowercase", "tag \"$it\" is not lowercase; retrieval compares lowercased",
            )
        }
        def.tags.filter { it.isBlank() }.forEach {
            out += CoherenceViolation(def.name, "tag-blank", "has a blank tag")
        }
        // A tag is NOT redundant for repeating a description word. The selector
        // weights a tag hit 3x and a description hit 2x, so a word appearing in
        // both places is worth 5, not 2 — repeating the user's word in the tags
        // is the cheapest way to raise a tool's score, and that is by design.
    }

    private fun checkDescription(def: ToolDefinition, out: MutableList<CoherenceViolation>) {
        val desc = def.description.trim()

        if (desc.length > MAX_DESCRIPTION_CHARS) {
            out += CoherenceViolation(
                def.name, "description-length",
                "${desc.length} chars, ceiling is $MAX_DESCRIPTION_CHARS",
            )
        }
        // One sentence: exactly one full stop, and it terminates the string.
        // Two sentences means the second one is a use-instruction the model has
        // to parse out of a prompt line that is already competing for attention.
        val stops = desc.count { it == '.' }
        if (stops != 1 || !desc.endsWith(".")) {
            out += CoherenceViolation(
                def.name, "description-sentence",
                "must be one sentence ending in a full stop (found $stops full stops)",
            )
        }
        val first = tokensOf(desc).firstOrNull()
        if (first == null || first !in ALLOWED_LEADING_VERBS) {
            out += CoherenceViolation(
                def.name, "description-verb",
                "must start with one of ${ALLOWED_LEADING_VERBS.sorted().take(8).joinToString()}, " +
                    "etc; starts with \"${first ?: "<none>"}\"",
            )
        }
    }

    private fun checkSchema(def: ToolDefinition, out: MutableList<CoherenceViolation>) {
        val schema = def.schema

        val type = schema["type"] as? JsonPrimitive
        if (type?.content != "object") {
            out += CoherenceViolation(
                def.name, "schema-type",
                "type must be \"object\", was ${type?.content ?: "missing"}",
            )
        }

        val properties = schema["properties"] as? JsonObject
        if (properties == null) {
            out += CoherenceViolation(
                def.name, "schema-properties",
                "\"properties\" must be a JSON object; without it ToolCallValidator " +
                    "rejects every argument of every call",
            )
            return
        }

        properties.forEach { (name, value) ->
            val prop = value as? JsonObject
            if (prop == null) {
                out += CoherenceViolation(
                    def.name, "schema-property-shape", "property \"$name\" is not a JSON object",
                )
                return@forEach
            }
            val propType = (prop["type"] as? JsonPrimitive)?.content
            if (propType == null) {
                out += CoherenceViolation(
                    def.name, "schema-property-type", "property \"$name\" declares no \"type\"",
                )
            } else if (propType !in JSON_TYPES) {
                out += CoherenceViolation(
                    def.name, "schema-property-type",
                    "property \"$name\" has type \"$propType\", not one of ${JSON_TYPES.sorted()}",
                )
            }
        }

        val required = schema["required"] as? JsonArray
        if (required == null) {
            out += CoherenceViolation(
                def.name, "schema-required", "\"required\" must be a JSON array, present even if empty",
            )
            return
        }
        required.forEachIndexed { index, element ->
            val value = (element as? JsonPrimitive)?.content
            if (value == null) {
                out += CoherenceViolation(
                    def.name, "schema-required-type", "required[$index] is not a string",
                )
            } else if (value !in properties) {
                // A required name with no matching property is a call the model
                // can never satisfy: the grammar cannot emit an argument the
                // schema does not declare.
                out += CoherenceViolation(
                    def.name, "schema-required-unknown",
                    "required \"$value\" is not declared in properties",
                )
            }
        }
    }

    private fun checkRisk(def: ToolDefinition, out: MutableList<CoherenceViolation>) {
        if (def.risk == ToolRisk.PRIVILEGED) {
            out += CoherenceViolation(
                def.name, "risk-privileged",
                "PRIVILEGED tools are refused by the runtime in v0, so this tool is dead code",
            )
        }
        val lower = def.description.lowercase()
        when (def.risk) {
            ToolRisk.DESTRUCTIVE -> {
                if (DESTRUCTIVE_CONSEQUENCES.none { it in lower }) {
                    out += CoherenceViolation(
                        def.name, "risk-consequence",
                        "DESTRUCTIVE description must state the consequence " +
                            "(e.g. ${DESTRUCTIVE_CONSEQUENCES.take(3).joinToString(", ")})",
                    )
                }
            }
            ToolRisk.EXTERNAL_COMMUNICATION -> {
                if (EXTERNAL_CONSEQUENCES.none { it in lower }) {
                    out += CoherenceViolation(
                        def.name, "risk-consequence",
                        "EXTERNAL_COMMUNICATION description must state who receives the data " +
                            "(e.g. ${EXTERNAL_CONSEQUENCES.take(3).joinToString(", ")})",
                    )
                }
            }
            else -> Unit
        }
        // Note what is deliberately NOT checked here: that the description
        // mentions its permission. `ToolDefinition.requiredPermission` is
        // documented as "documentation + UI only", and the confirmation dialog
        // reads that field directly — so a tool that omits the permission from
        // its prose is still honest to the user. Enforcing the opposite would
        // force every description to carry a string the prompt does not need,
        // against a 120-character budget, for 12 tools.
    }
}

// ---------------------------------------------------------------------------
// 2. Overlap — which tools a lexical retriever will confuse
// ---------------------------------------------------------------------------

/**
 * One pair of tools, scored by how much of their retrieval surface collides.
 *
 * [overlap] is the overlap coefficient: shared tokens divided by the size of the
 * SMALLER set. Jaccard would punish a big tool for being big; the overlap
 * coefficient does not, and confusability is exactly the case where one tool's
 * vocabulary is a subset of another's — `calendar.search` inside a set that
 * also contains `calendar.create` with the same tag words.
 */
data class ToolOverlap(
    val a: String,
    val b: String,
    val shared: List<String>,
    val union: List<String>,
) {
    val score: Double
        get() = if (shared.isEmpty() || union.isEmpty()) 0.0
        else shared.size.toDouble() / minOf(tokenCount(a), tokenCount(b)).coerceAtLeast(1)

    /** The tags/words both tools use. What a tool author has to change. */
    fun sharedTokens(): String = shared.joinToString(", ")

    fun render(): String = "%.2f  %s <-> %s\n         shared: %s".format(
        score, a, b, sharedTokens(),
    )

    private fun tokenCount(name: String): Int =
        AndroidOverlapAnalyzer.tokensOf(name).size
}

/**
 * Pairwise token overlap over the whole tool set.
 *
 * The tokenizer deliberately mirrors `LexicalToolSelector.tokenize`: lowercase,
 * split on non-alphanumerics, drop tokens of two characters or fewer. Anything
 * else would measure a vocabulary the retriever does not actually use, and a
 * confusability score that does not match the retriever is fiction.
 */
object AndroidOverlapAnalyzer {

    /** A pair above this score is reported as confusable. */
    const val CONFUSABLE_THRESHOLD = 0.60

    fun tokenize(text: String): List<String> =
        text.lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length > 2 }

    fun tokensOf(name: String): List<String> {
        val def = AndroidToolStubs.specFor(name)
        return (tokenize(def.name) + tokenize(def.description) +
            def.tags.flatMap { tokenize(it) }).toSet().toList()
    }

    /** Every pair, worst overlap first. */
    fun analyze(specs: List<AndroidToolSpec> = AndroidToolStubs.specs): List<ToolOverlap> {
        val tokens = specs.associate { it.name to tokensOf(it.name).toSet() }
        val out = mutableListOf<ToolOverlap>()
        for (i in specs.indices) {
            for (j in i + 1 until specs.size) {
                val a = specs[i]
                val b = specs[j]
                val ta = tokens.getValue(a.name)
                val tb = tokens.getValue(b.name)
                val shared = (ta intersect tb).toList().sorted()
                if (shared.isEmpty()) continue
                out += ToolOverlap(a.name, b.name, shared, (ta union tb).toList().sorted())
            }
        }
        return out.sortedWith(
            compareByDescending<ToolOverlap> { it.score }.thenBy { it.a }.thenBy { it.b },
        )
    }

    /** Only the pairs at or above [CONFUSABLE_THRESHOLD]. */
    fun confusable(
        specs: List<AndroidToolSpec> = AndroidToolStubs.specs,
    ): List<ToolOverlap> = analyze(specs).filter { it.score >= CONFUSABLE_THRESHOLD }
}
