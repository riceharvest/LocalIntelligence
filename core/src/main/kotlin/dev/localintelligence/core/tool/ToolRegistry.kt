package dev.localintelligence.core.tool

import dev.localintelligence.core.model.ToolArgs
import dev.localintelligence.core.tool.catalogue.CatalogueAgreement
import dev.localintelligence.core.tool.catalogue.V0ToolCatalogue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Everything the agent loop needs to route a tool call. Pure data, no Android. */
interface ToolRegistry {
    fun all(): List<AgentTool>
    fun byName(name: String): AgentTool?
    fun byCategory(category: String): List<AgentTool> =
        all().filter { it.definition.category == category }
}

/**
 * In-memory registry. The default implementation; :android may decorate it.
 *
 * ## The catalogue agreement check lives HERE, not only in the composition root
 *
 * [catalogue] defaults to [V0ToolCatalogue] and is verified in `init`, in both
 * directions, by [CatalogueAgreement]. That default is the whole reason the
 * check is structural rather than a convention:
 *
 *  - the shipped composition root is `SimpleToolRegistry(androidTools(context))`
 *    — one positional argument — and it keeps working unchanged, so the
 *    bidirectional gate became live without editing a single file outside
 *    `:core`;
 *  - any future registry gets the gate for free, including one built by a
 *    different composition root, a debug screen, or a future `:android` tool
 *    package. A check that lives only in `AppContainer` is a check that the
 *    next composition root quietly drops.
 *
 * The check fires at construction, so drift is a start-up failure rather than a
 * model that confidently calls a tool the product does not have. See
 * [CatalogueAgreement] for what is checked and why description, tags and schema
 * deliberately are not.
 *
 * @param catalogue the reference set. Pass an empty list to opt out — for a
 *   registry of tools that are not part of the shipped v0 catalogue at all,
 *   which is a deliberate statement rather than an omission.
 */
class SimpleToolRegistry(
    tools: List<AgentTool> = emptyList(),
    catalogue: List<ToolDefinition> = V0ToolCatalogue.definitions,
) : ToolRegistry {
    private val byName: Map<String, AgentTool> = tools.associateBy { it.definition.name }

    init {
        require(byName.size == tools.size) {
            val dupes = tools.groupBy { it.definition.name }.filterValues { it.size > 1 }.keys
            "duplicate tool names: $dupes"
        }
        // Bidirectional. Catches a shipped tool the catalogue has never heard
        // of AND a catalogue entry nothing implements.
        if (catalogue.isNotEmpty()) CatalogueAgreement.require(tools, catalogue)
    }

    override fun all(): List<AgentTool> = byName.values.toList()
    override fun byName(name: String): AgentTool? = byName[name]
}

/**
 * Picks the handful of tools worth showing the model.
 *
 * v0 uses lexical scoring only — no LLM. Returning 3-6 tools instead of 20 is
 * the single biggest context saving in the system.
 */
fun interface ToolSelector {
    fun select(
        task: String,
        sessionKeywords: List<String>,
        available: List<AgentTool>,
        maxTools: Int,
    ): List<AgentTool>
}

/**
 * Default lexical selector: exact substring match on name/description/tags,
 * plus token overlap. Deterministic and dependency-free.
 *
 * ## What this selector actually decides, which is harder to recover from than it looks
 *
 * It does not rank tools. It chooses which tools exist for the turn, because
 * `AgentController.buildRequest` hands the SAME list to both the system prompt
 * and `GrammarBuilder.forActions`, and the grammar makes an unselected tool
 * *unspeakable*: `toolcall ::= "<tool name=\"X\">" … | "<tool name=\"Y\">" …`.
 * So a retrieval miss is not a worse answer, it is a task the agent cannot
 * perform. Selection accuracy is an upper bound on task success, not a
 * component of it.
 *
 * ## Measured behaviour, and why nothing was changed here
 *
 * Offline, against the 25 tools `:android` actually ships (their definitions,
 * not [dev.localintelligence.core.tool.catalogue.V0ToolCatalogue]'s — the
 * prompt and this scorer both read the registry), over 120 pinned utterances
 * (34 tag-tuned, 25 and 61 held out). Expected tool inside the shipped
 * top-6: **61.6%** over the 86 held-out cases. Two facts about that number
 * matter more than the number:
 *
 *  - **84.9% of turns have the 6th and 7th tool tied on score**, so the cut is
 *    decided by the `thenBy { name }` alphabetical tie-break. Most turns are a
 *    coin flip dressed as a ranking.
 *  - **30.2% of turns give the correct tool a score of exactly zero** — no
 *    shared word with any description or tag. The scorer has no opinion, and no
 *    re-weighting of an opinion it does not have can help.
 *
 * ## The alphabetical tie-break was measured, and it is the best available one
 *
 * `thenBy { name }` looks like the defect, so it was measured against six
 * alternatives over the 170-turn held-out corpus: raw name-substring, raw
 * description-word, risk tier, required-arg count, character-trigram
 * similarity, and normalised trigram similarity. **None is significant at the
 * shipped k=6.** The best, normalised trigram, is +3 turns on 19 discordant
 * ones (McNemar exact p=0.65) — inside the noise of a random-tie null the
 * shipped order already sits at the 72nd percentile of.
 *
 * The reason is structural rather than a matter of not having found the right
 * signal yet. Of the 53 turns where the correct tool is *tied but below the
 * cut*, **37 have all 25 tools scoring zero** — there is nothing in the request
 * for any ordering to key on. Of the 16 that do have a scorer signal, a raw
 * name-substring fires on the correct tool in **0**. A tie-break reorders tools
 * the scorer could already tell apart; where the scorer is silent, every order
 * is equally uninformed and the question is a coin, not a ranking.
 *
 * Two of the six are actively worse and are worth naming. Ordering by **risk
 * tier** costs 8 turns at k=10: read-only-first helps the read tools and
 * buries the write ones. A **category-diverse** window fixes the visible
 * pathology — the shipped k=6 window covers at most two categories on 60/170
 * turns, a diverse one covers more than two on all 170 — and still loses three
 * turns, because only 10 of the 53 recoverable ties put the correct tool
 * against a same-category rival, and spreading is usually right and
 * occasionally splits the *correct pair*, `contacts.get` from
 * `contacts.search`. A tidier window is not a more capable agent.
 *
 * So the tie rate stays at 84%, and the honest statement is that on a lexical
 * selector over natural language it is close to irreducible without a model.
 * Re-run it with `./core/src/main/kotlin/dev/localintelligence/core/tool/eval/
 * run-heldout-harness.sh tiebreak`, which prints the mechanism proof, all
 * seven variants, the null, and the paired tests. Nothing here quotes a number
 * that tool cannot re-derive.
 *
 * ### What a tie-break might be worth that this corpus cannot see
 *
 * Selectability is not the only thing an ordering does. It also decides the
 * order the model reads, and a 1-3B model's choice is not uniform over six
 * names. A window ordered *toward the more likely tool* may therefore be worth
 * more than a window that merely *contains* it — and the corpus, which has no
 * model in it, cannot measure that at all. That is the strongest argument for
 * revisiting this, and it is not an argument this file gets to make on the
 * evidence available.
 *
 * Four inline variants were measured and all are recorded here rather than
 * shipped, because three are neutral-or-worse and the fourth is a tag-list
 * defect wearing a selector's clothes:
 *
 * | variant                    | held-out 86 | note                                    |
 * |----------------------------|------------:|-----------------------------------------|
 * | this, as shipped           |    53/86    | 61.6%                                   |
 * | + stopword removal         |    50/86    | **worse.** The function words are shared, but removing them removes the little signal there is |
 * | + category in the text     |    53/86    | no change; the namespace is already in the name |
 * | + phrase bonus (>=2 words)  |    53/86    | no change                               |
 * | + suffix folding           |    55/86    | +2 cases, and **both are the same tag**: `notifications.dismiss` is tagged `banner` and two utterances say "banners" |
 *
 * Suffix folding is a real +2 and 0 regressions, and it is still not a selector
 * change: the catalogue's own contract says the TAG carries the inflection the
 * user types, and one tag is missing one `s`. Normalising the scorer to paper
 * over a tag list would make the next missing inflection invisible instead of
 * reported.
 *
 * The lever that *is* measured, and the one worth taking, is width:
 *
 * | visible tools | held-out hit@ | mean system-prompt tokens |
 * |---------------|--------------:|-------------------------:|
 * | 3             |         50.0% |  141                     |
 * | **6 (shipped)** |   **61.6%** |  **219**             |
 * | 8             |         62.8% |  271                     |
 * | 10            |         70.9% |  320                     |
 * | 12            |         74.4% |  367                     |
 *
 * 6 -> 10 buys 8.1 points of retrieval for 101 prompt tokens, against a 6000
 * token working limit. It is `AgentConfig.maxVisibleTools`, one constant, in a
 * file this change-set does not own — and it trades against the opposite
 * constraint, that a 1-3B model chooses less reliably from 10 tools than from
 * 6. That is a product call with a measurement on both sides, not a heuristic
 * somebody should quietly pick.
 *
 * Reproducing these numbers needs the held-out utterance lists, which are not
 * in this repository. There is no harness here, so these are stated as a
 * measurement with its inputs named, not as a claim anybody can re-run from
 * `main`. `docs/evals.md` records that gap.
 *
 * ## Do not quote a retrieval number without the tool set it was measured on
 *
 * A retrieval number is a statement about a selector AND a tool set. This one
 * was measured against the 25 definitions `:android` actually ships, which is
 * why the prompt and the scorer both read the registry rather than
 * [dev.localintelligence.core.tool.catalogue.V0ToolCatalogue]. A number
 * measured against a different tool set does not transfer.
 */
class LexicalToolSelector : ToolSelector {

    override fun select(
        task: String,
        sessionKeywords: List<String>,
        available: List<AgentTool>,
        maxTools: Int,
    ): List<AgentTool> {
        if (available.isEmpty()) return emptyList()
        if (available.size <= maxTools) return available

        val taskTokens = tokenize(task).toSet()
        val keywordTokens = sessionKeywords.flatMap { tokenize(it) }.toSet()

        val scored = available.map { tool ->
            val def = tool.definition
            val nameTokens = tokenize(def.name).toSet()
            val descTokens = tokenize(def.description).toSet()
            val tagTokens = def.tags.flatMap { tokenize(it) }.toSet()

            val overlap = taskTokens.intersect(descTokens).size * 2 +
                taskTokens.intersect(tagTokens).size * 3 +
                taskTokens.intersect(nameTokens).size * 4 +
                keywordTokens.intersect(descTokens).size +
                keywordTokens.intersect(tagTokens).size

            // Exact substring in the task is a strong signal a small model needs.
            val substringHit = if (task.contains(def.name, ignoreCase = true)) 10 else 0

            tool to (overlap + substringHit)
        }

        return scored.sortedWith(
            compareByDescending<Pair<AgentTool, Int>> { it.second }.thenBy { it.first.definition.name },
        ).take(maxTools).map { it.first }
    }

    private fun tokenize(text: String): List<String> =
        text.lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length > 2 }
}

/**
 * Truncates a tool observation to the model-visible budget.
 * Deterministic: never throws, always returns valid text.
 */
object ObservationTruncator {
    const val DEFAULT_BUDGET_CHARS = 2048

    fun truncate(observation: String, budget: Int = DEFAULT_BUDGET_CHARS): String {
        if (budget <= 0) return ""
        if (observation.length <= budget) return observation
        // The suffix must fit inside the budget, and a budget smaller than the
        // suffix must not produce a negative take().
        val marker = "…[truncated]"
        if (budget <= marker.length) return observation.take(budget)
        val head = observation.take(budget - marker.length)
        return head + marker
    }
}

/**
 * Validates a tool call against the registry before execution.
 *
 * ## The two failures this catches, and why they are the two that matter
 *
 * A grammar-constrained backend makes a hallucinated tool name unspeakable, so
 * the reachable failures are both about ARGUMENTS, and both are the errors a
 * 1-3B model actually makes: it invents an argument name, or it omits one the
 * tool needs. Both messages are read by the model on its next turn, so both are
 * written as corrections rather than as diagnostics.
 *
 * What that means concretely, and it is the whole design of this object:
 *
 *  - **Every rejection names the fix.** "unexpected argument(s): foo" alone
 *    leaves a small model to guess what the right name is; the same message
 *    that lists the offending names also lists the legal ones, and marks which
 *    are required, because one message that teaches the whole shape is worth
 *    more than three messages that each teach a fragment.
 *  - **No stack trace, no class name, no enum.** `IllegalStateException:
 *    required` is the text a compiler produces, not a correction. The model has
 *    to be able to act on the sentence without knowing Kotlin.
 *  - **Bounded.** A rejection goes back into the prompt on the turn that is
 *    already going wrong, so it is capped like any other model-visible output.
 *
 * ## Why the unknown-tool branch is kept even though the parser catches it first
 *
 * `ActionParserImpl` rejects a name outside the allow-list before a `CallTool`
 * is ever constructed, so on the production path this branch does not fire. It
 * stays because [dev.localintelligence.core.agent.ToolCallValidatorGate] is a
 * substitution point: a caller that swaps the gate, or a future parser, gets
 * this rather than a crash. It also carries the *legal* names, which is what
 * makes it useful rather than a bare "no".
 */
object ToolCallValidator {
    /** Cap on how many offending argument names are echoed back to the model. */
    const val MAX_NAMED_ARGS = 8

    sealed interface Result {
        data class Valid(val tool: AgentTool) : Result
        data class Rejected(val observation: String) : Result
    }

    fun validate(
        name: String,
        args: ToolArgs,
        visible: List<AgentTool>,
        registry: ToolRegistry,
    ): Result {
        val tool = visible.firstOrNull { it.definition.name == name }
            ?: return Result.Rejected(unknownTool(name, visible))

        val schema = tool.definition.schema
        val properties = (schema["properties"] as? JsonObject)?.keys.orEmpty()
        val required = (schema["required"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?.toSet()
            .orEmpty()

        // Order matters: a name the tool does not have is a *different* mistake
        // from one it needs and did not get, and a model that invented three
        // argument names has not also forgotten the required one. Reporting the
        // invention first keeps the correction to one subject at a time.
        val unexpected = args.keys.filter { it !in properties }
        if (unexpected.isNotEmpty()) {
            // A confused model can emit dozens of invented argument names. This
            // observation goes straight back into the prompt, so it is capped like
            // any other model-visible output — a 4KB rejection would blow the
            // context budget on the very turn that is already going wrong.
            val named = unexpected.take(MAX_NAMED_ARGS).joinToString()
            val more = if (unexpected.size > MAX_NAMED_ARGS) {
                " (+${unexpected.size - MAX_NAMED_ARGS} more)"
            } else {
                ""
            }
            return Result.Rejected(
                ObservationTruncator.truncate(
                    "$name does not take \"$named\"$more. " +
                        "Call it as <tool name=\"$name\">${shape(properties, required)}</tool>. " +
                        "Use only the names listed."
                )
            )
        }

        // A missing required argument was previously not an error at all here: the
        // call validated, ran, and the tool failed somewhere further down, so the
        // model was told "no such thing" instead of "you left out the name". The
        // grammar does not close this on its own either — `GrammarBuilder.argsRule`
        // emits the first required key unconditionally and wraps every key after
        // it in `( ... )?`, so for a tool with two or more required arguments only
        // the first is actually enforced (GrammarBuilder.kt:147-152). Two shipped
        // tools are in that state, `calendar.search` and `calendar.create`. This
        // check is the one that holds, and it reads the same schema.
        val missing = required.filter { it !in args }
        if (missing.isNotEmpty()) {
            val named = missing.take(MAX_NAMED_ARGS).joinToString()
            val more = if (missing.size > MAX_NAMED_ARGS) {
                " (+${missing.size - MAX_NAMED_ARGS} more)"
            } else {
                ""
            }
            return Result.Rejected(
                ObservationTruncator.truncate(
                    "$name needs $named$more and the call left it out, so nothing ran. " +
                        "Call it as <tool name=\"$name\">${shape(properties, required)}</tool>."
                )
            )
        }
        // Unavailable tools (repeated failures) are rejected before re-execution.
        if (registry.byName(name) == null) {
            return Result.Rejected("$name is not available right now. Use something else.")
        }
        return Result.Valid(tool)
    }

    /**
     * The legal call shape as a model must write it, with required names marked.
     *
     * `{"hour": 7, "label": "bread"}` — required names plain, optional names in
     * square brackets, and nothing else. It is deliberately not JSON Schema: a
     * schema fragment is a document, and the model needs a template it can copy.
     * A required name shown as optional is the one mistake that produces a call
     * that parses and then does nothing, which is why the two are distinguished
     * at all.
     *
     * A tool with no declared properties renders as `{}`, which is the truth for
     * `device.battery` and friends rather than an error.
     */
    private fun shape(properties: Set<String>, required: Set<String>): String {
        if (properties.isEmpty()) return "{}"
        val ordered = properties.filter { it in required } + properties.filter { it !in required }
        return ordered.joinToString(separator = ", ", prefix = "{", postfix = "}") { key ->
            if (key in required) "\"$key\": value" else "[\"$key\": value]"
        }
    }

    /**
     * The rejection for a name the model may not call, carrying the names it may.
     *
     * The same shape [dev.localintelligence.core.agent.ActionParserImpl] uses, and
     * for the same reason: "no" is not recoverable for a small model and the list
     * is. Capped, because a 25-name list on a turn that is already wrong is a
     * poor trade for a bounded one — and in practice the grammar means a model
     * only ever sees the handful that are visible anyway.
     */
    private fun unknownTool(name: String, visible: List<AgentTool>): String {
        val legal = visible.map { it.definition.name }.sorted()
        if (legal.isEmpty()) {
            return "There is no tool called \"$name\", and no tools are available this turn. " +
                "Answer in <respond>text</respond> instead."
        }
        val shown = legal.take(MAX_NAMED_ARGS)
        val more = if (legal.size > shown.size) " (+${legal.size - shown.size} more)" else ""
        return "There is no tool called \"$name\". Available tools this turn: " +
            shown.joinToString(", ") + more + ". Use one of those names exactly."
    }
}
