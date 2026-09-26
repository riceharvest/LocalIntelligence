package dev.localintelligence.core.tool.catalogue

import dev.localintelligence.core.tool.AgentTool
import dev.localintelligence.core.tool.ToolDefinition

/**
 * The catalogue/registry agreement check, in BOTH directions, as data.
 *
 * `requireCatalogueAgreement` in `:android` (see `AndroidTools.kt`) checks ONE
 * direction: every shipped tool has a catalogue entry. The direction that
 * matters more is the other one — **a catalogue entry with no implementation
 * behind it** — because "in the catalogue" and "exists" are different claims.
 *
 * ## How bad is an unimplemented catalogue entry, honestly
 *
 * Less than it looks at run time. The grammar and the system prompt are both
 * built from the *registry*, never from the catalogue:
 *
 * ```
 * AgentController.buildRequest(visible)                         // AgentController.kt
 *   -> GrammarBuilder.forActions(visible.map { it.definition })
 *   -> contextBuilder.build(..., visible.map { it.definition })
 * ```
 *
 * `visible` is a subset of the registry, so a catalogued-only tool is never
 * offered to the model and can never be called. Its blast radius is
 * documentation, a `byName()` that returns a definition for a tool that does
 * not exist, and a catalogue that claims a capability the app lacks.
 *
 * The dangerous version is the one that is a single refactor away: any code
 * that builds a prompt, a grammar or a permission screen from
 * [V0ToolCatalogue] would offer a tool that cannot execute, and the model would
 * call it and get a validation failure. This check closes the class, so that
 * version cannot arrive either.
 *
 * ## What is checked, and what is deliberately not
 *
 * Checked, in both directions:
 *
 *  - **Names.** A shipped tool with no catalogue entry, and a catalogue entry
 *    with no shipped tool. The first means retrieval and the policy have no
 *    reference to agree with; the second means the catalogue is lying about
 *    what the product can do.
 *  - **Risk tier**, against the catalogue as the reference. Risk is the one
 *    field that must never drift, because
 *    [dev.localintelligence.core.policy.RiskPolicy] gates on it.
 *  - **Description, category and tags.** These are NOT duplicated any more —
 *    both sides build their [ToolDefinition] from the same [ToolMeta]
 *    descriptor, so agreement is structural. It is checked anyway, and the
 *    reason matters: a structural guarantee only holds for code that goes
 *    through the descriptor, and the failure this catches is exactly someone
 *    hand-writing a `ToolDefinition` with its own description. That is a
 *    well-typed, compiling, silent mistake, and it is the one that shipped a
 *    whole change-set's tag tuning to a file the model never reads.
 *  - **Schema**, deliberately NOT checked. The `:android` schemas are the real
 *    ones: richer argument documentation, per-tool argument names, and
 *    `minProperties` constraints the implementations enforce. The catalogue's
 *    are a shorter restatement kept as documentation. Unifying them is a real
 *    migration with real breakage risk, and it is tracked as follow-up rather
 *    than smuggled in here.
 *  - **Schema**, by reference equality. Both sides now read the same
 *    [ToolSchemas] val, so this check cannot fail for a tool written correctly.
 *    It exists to catch the one that was not: a tool that builds its own schema
 *    literal instead of referencing the shared declaration, which is exactly
 *    the drift that used to go unnoticed. See [CatalogueDrift.schemaMismatch].
 *
 * ## What is still NOT checked, and why that is a decision
 *
 * Description and tags are not compared at all, because they are separately
 * written strings on each side. Unlike the schema, no code path depends on
 * them agreeing, so unifying them means choosing which wording reaches the
 * model — a prompt-quality change with a measurement attached, not a
 * correctness fix. The schema was different in kind: it decides which argument
 * NAMES a call may use, and both sides had one.
 */
data class CatalogueDrift(
    /** Shipped tools with no catalogue entry. */
    val unlisted: List<String>,
    /** Catalogue entries with no shipped tool behind them. */
    val unimplemented: List<String>,
    /** `"name: catalogue=TIER tool=TIER"` per mismatch. */
    val riskTierMismatch: List<String>,
    /** `"name: catalogue=<origin> tool=<origin>"` per mismatch. */
    val observationOriginMismatch: List<String>,
    /** `"name: catalogue=<c> tool=<c>"` per mismatch. */
    val categoryMismatch: List<String>,
    /** `"name: ..."` per mismatch, with both descriptions quoted. */
    val descriptionMismatch: List<String>,
    /** `"name: only in catalogue=[...] only in tool=[...]"` per mismatch. */
    val tagMismatch: List<String>,
    /**
     * Shipped tools whose schema is not the shared [ToolSchemas] declaration.
     *
     * Compared by IDENTITY, not by content. Content equality would let a tool
     * carry a hand-copied literal that happens to match today, which is the
     * failure this is meant to catch: the copy that rots the moment either side
     * is edited. Identity means the only way to pass is to reference the same
     * object, so a schema cannot be forked without the build noticing.
     */
    val schemaMismatch: List<String>,
) {
    /**
     * True when both sides say the same thing about the same set of tools.
     *
     * [observationOriginMismatch] is part of this, and was briefly dropped when
     * the ToolMeta refactor merged: the check still *computed* the list, and
     * `describe()` still explained it, so the field looked guarded while
     * `isEmpty` returned true with origin drift present and `require()` let the
     * app boot. A security check that is computed and printed but never acted on
     * is worse than no check, because it reads as covered.
     */
    val isEmpty: Boolean
        get() = unlisted.isEmpty() && unimplemented.isEmpty() &&
            riskTierMismatch.isEmpty() &&
            observationOriginMismatch.isEmpty() &&
            categoryMismatch.isEmpty() &&
            descriptionMismatch.isEmpty() &&
            tagMismatch.isEmpty() &&
            schemaMismatch.isEmpty()

    /**
     * One sentence per class of drift, in the order a reader should act on them.
     *
     * Empty when [isEmpty], so a caller can put it straight in an exception
     * message without producing "the tools disagree: ".
     */
    fun describe(): String = buildList {
        if (unlisted.isNotEmpty()) {
            add(
                "shipped but not in the catalogue ($unlisted): the selector's index and " +
                    "the policy have no entry to agree with. Add them or stop shipping them.",
            )
        }
        if (unimplemented.isNotEmpty()) {
            add(
                "catalogued but not implemented ($unimplemented): the catalogue documents a " +
                    "tool the product cannot call. Implement it or remove it — a documented " +
                    "tool that does not exist is a promise to the model and to the user.",
            )
        }
        if (riskTierMismatch.isNotEmpty()) {
            add(
                "risk tier drift ($riskTierMismatch): the policy gates on the tool's own tier, " +
                    "so a mismatch means one side understates how dangerous the call is.",
            )
        }
        if (observationOriginMismatch.isNotEmpty()) {
            add(
                "observation origin drift ($observationOriginMismatch): this decides whether a " +
                    "tool's output is fenced as third-party text, so a tool reading LOCAL where the " +
                    "catalogue says NETWORK strips the untrusted fence from attacker-controlled text.",
            )
        }
        if (categoryMismatch.isNotEmpty()) {
            add(
                "category drift ($categoryMismatch): the catalogue's per-category counts are " +
                    "documentation, and a wrong one is a wrong claim about what the product does.",
            )
        }
        if (descriptionMismatch.isNotEmpty()) {
            add(
                "description drift ($descriptionMismatch): both sides are supposed to read the " +
                    "one string in ToolMeta, so this means a ToolDefinition was hand-written " +
                    "instead of built from ToolMeta.<TOOL>.define(...). The model reads the " +
                    "tool's copy, so the catalogue is documenting text it never sends.",
            )
        }
        if (tagMismatch.isNotEmpty()) {
            add(
                "retrieval tag drift ($tagMismatch): the lexical selector scores the shipped " +
                    "tool's tags, so a tool whose tags differ from its catalogue entry is " +
                    "reachable by different words on the two sides — which is the failure this " +
                    "check exists to make impossible. Build the definition from " +
                    "ToolMeta.<TOOL>.define(...) instead of restating tags.",
            )
        }
        if (schemaMismatch.isNotEmpty()) {
            add(
                "schema drift ($schemaMismatch): the tool does not use the shared ToolSchemas " +
                    "declaration. That is how the catalogue and the tool ended up disagreeing " +
                    "about argument names and bounds on all 25 tools while nothing failed. " +
                    "Reference ToolSchemas.<tool> from both sides instead of writing a literal.",
            )
        }
    }.joinToString(separator = " ")
}

/** The agreement check itself. Pure, total, and cheap enough to run at composition time. */
object CatalogueAgreement {

    /**
     * Compares [shipped] against [catalogue] and returns every disagreement.
     *
     * Never throws and never short-circuits: it reports all six classes at once,
     * because a reader fixing drift wants the whole list, not the first entry
     * repeated once per run.
     */
    fun inspect(
        shipped: List<AgentTool>,
        catalogue: List<ToolDefinition>,
    ): CatalogueDrift {
        val shippedByName = shipped.associateBy { it.definition.name }
        val catalogueByName = catalogue.associateBy { it.name }

        val unlisted = shippedByName.keys.filterNot { it in catalogueByName }.sorted()
        val unimplemented = catalogueByName.keys.filterNot { it in shippedByName }.sorted()

        val riskTierMismatch = mutableListOf<String>()
        val observationOriginMismatch = mutableListOf<String>()
        val categoryMismatch = mutableListOf<String>()
        val descriptionMismatch = mutableListOf<String>()
        val tagMismatch = mutableListOf<String>()
        val schemaMismatch = mutableListOf<String>()
        for ((name, tool) in shippedByName) {
            val def = tool.definition
            val catalogued = catalogueByName[name] ?: continue
            if (catalogued.risk != def.risk) {
                riskTierMismatch += "$name: catalogue=${catalogued.risk} tool=${def.risk}"
            }
            // WHY THE ORIGIN IS COMPARED HERE AND NOT LEFT TO REVIEW: it is the
            // field that decides whether a tool's output is fenced as third-party
            // text. A tool that silently reads LOCAL where the catalogue says
            // NETWORK removes the fence from an attacker's page without changing
            // a single line of code a reviewer would notice.
            if (catalogued.observationOrigin != def.observationOrigin) {
                observationOriginMismatch +=
                    "$name: catalogue=${catalogued.observationOrigin} tool=${def.observationOrigin}"
            }
            if (catalogued.category != def.category) {
                categoryMismatch += "$name: catalogue=${catalogued.category} tool=${def.category}"
            }
            if (catalogued.description != def.description) {
                descriptionMismatch += "$name: catalogue=${quote(catalogued.description)} " +
                    "tool=${quote(def.description)}"
            }
            if (catalogued.tags != def.tags) {
                tagMismatch += "$name: only in catalogue=${
                    quoteTags(catalogued.tags - def.tags)
                } only in tool=${quoteTags(def.tags - catalogued.tags)}"
            }
            // Identity, deliberately — see [CatalogueDrift.schemaMismatch]. The
            // two declarations are the same `ToolSchemas` val, so this is true
            // for every correctly written tool, and false for exactly one kind
            // of mistake: a tool carrying its own schema literal.
            if (catalogued.schema !== def.schema) {
                schemaMismatch += "$name: schema is not the shared ToolSchemas declaration"
            }
        }
        return CatalogueDrift(
            unlisted = unlisted,
            unimplemented = unimplemented,
            riskTierMismatch = riskTierMismatch.sorted(),
            observationOriginMismatch = observationOriginMismatch.sorted(),
            categoryMismatch = categoryMismatch.sorted(),
            descriptionMismatch = descriptionMismatch.sorted(),
            tagMismatch = tagMismatch.sorted(),
            schemaMismatch = schemaMismatch.sorted(),
        )
    }

    /**
     * [inspect], as a composition-time gate.
     *
     * An exception rather than a warning, and the direction of failure matters:
     * the drift it catches is silent by construction. A tool with no catalogue
     * entry is invisible to the selector's index; a mismatched tier is a wrong
     * decision rather than an error; a catalogue entry with nothing behind it is
     * a documented capability. None of them crash, none of them log, and all of
     * them are wrong. Failing at composition means the app does not start with
     * a tool set that disagrees with its own documentation.
     *
     * ## Why an EMPTY registry is not drift
     *
     * `SimpleToolRegistry(emptyList())` is a real, supported state: it is what
     * a tool layer that has not been wired up looks like, and the loop handles
     * it (an empty `visible` yields an empty grammar and a prose-only model).
     * Treating it as "all 25 tools were deleted" would turn an unfinished
     * wiring step into a start-up crash, which is the wrong trade in the
     * direction that matters — a crash at `AppContainer` construction is far
     * worse than an agent that cannot call anything yet. So the reverse check
     * is skipped when nothing ships, and skipped loudly: [describe] the
     * assumption at the call site.
     */
    fun require(shipped: List<AgentTool>, catalogue: List<ToolDefinition>): List<AgentTool> {
        if (shipped.isEmpty()) return shipped
        val drift = inspect(shipped, catalogue)
        check(drift.isEmpty) {
            "the ${shipped.size} shipped tools and the ${catalogue.size}-entry catalogue " +
                "disagree. " + drift.describe()
        }
        return shipped
    }
}

/**
 * Quote a description for a drift message.
 *
 * Truncated, because a description is two clauses at most today and a future
 * one could be a paragraph — and a start-up failure whose message is longer
 * than the log line is a start-up failure nobody reads.
 */
private fun quote(text: String): String {
    val clipped = if (text.length <= 80) text else text.take(77) + "..."
    return "\"" + clipped + "\""
}

/** Quote a tag set difference for a drift message. */
private fun quoteTags(tags: Set<String>): String =
    if (tags.isEmpty()) "[]" else tags.sorted().joinToString(prefix = "[", postfix = "]")
