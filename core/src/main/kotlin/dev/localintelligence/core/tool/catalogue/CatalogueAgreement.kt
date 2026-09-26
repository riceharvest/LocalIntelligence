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
) {
    /** True when both sides say the same thing about the same set of tools. */
    val isEmpty: Boolean
        get() = unlisted.isEmpty() && unimplemented.isEmpty() &&
            riskTierMismatch.isEmpty() &&
                observationOriginMismatch.isEmpty() &&
                categoryMismatch.isEmpty()

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
    }.joinToString(separator = " ")
}

/** The agreement check itself. Pure, total, and cheap enough to run at composition time. */
object CatalogueAgreement {

    /**
     * Compares [shipped] against [catalogue] and returns every disagreement.
     *
     * Never throws and never short-circuits: it reports all four classes at once,
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
        }
        return CatalogueDrift(
            unlisted = unlisted,
            unimplemented = unimplemented,
            riskTierMismatch = riskTierMismatch.sorted(),
            observationOriginMismatch = observationOriginMismatch.sorted(),
            categoryMismatch = categoryMismatch.sorted(),
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
