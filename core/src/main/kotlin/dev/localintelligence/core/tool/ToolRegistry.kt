package dev.localintelligence.core.tool

import dev.localintelligence.core.model.ToolArgs
import dev.localintelligence.core.tool.catalogue.CatalogueAgreement
import dev.localintelligence.core.tool.catalogue.V0ToolCatalogue
import dev.localintelligence.core.trace.SelectionReport
import dev.localintelligence.core.trace.UnselectedTool
import java.text.Normalizer
import java.util.Locale
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
 * [CatalogueAgreement] for what is checked and why schema deliberately is not.
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
 * v0 uses lexical scoring only — no LLM. What it returns is not a ranked list
 * with a tail the model is meant to ignore: `AgentController` hands this
 * result to BOTH the system prompt and `GrammarBuilder.forActions`, so a tool
 * left out is unspeakable. Returning a smaller set saves context at the cost of
 * making tasks impossible, and [dev.localintelligence.core.agent.AgentConfig.maxVisibleTools]
 * is set from a measured trade between exactly those two.
 *
 * ## `maxTools >= available.size` used to return the input order, unscored
 *
 * This opened with `if (available.size <= maxTools) return available`, so a
 * caller asking for at least the whole registry got the registry's own order
 * with no scoring at all. Every tool was still callable, so nothing FAILED —
 * but the result's meaning silently depended on the ceiling: the same call
 * returned a relevance-ordered list at k=9 and an arbitrarily-ordered one at
 * k=25, with no way for a caller to tell which it had got.
 *
 * **Removed**, after checking what it cost and what it was worth:
 *
 *  - **Order is not load-bearing today, and that was verified rather than
 *    assumed.** Every consumer treats the result as a SET:
 *    `AgentController` maps it to definitions for the prompt and the grammar,
 *    `.toSet()`s it for the parser's allow-list, and `joinToString`s it into a
 *    "not available" error. Nothing reads position 0 as "the best match", so
 *    registry order and relevance order produce the same behaviour.
 *  - **So this was a latent trap, not a live bug** — and the trap was the
 *    ceiling itself. `AgentConfig.maxVisibleTools` is exactly the knob a
 *    future change-set would turn, and a caller who turned it to 25 would
 *    silently receive the other branch and never know.
 *  - **Removing it is set-preserving, so it cannot cost recall.** When
 *    `maxTools >= available.size` the new code returns the same tools in
 *    relevance order; the membership is identical and only the ordering
 *    changes. The harness re-runs all 176 cases to prove it.
 *
 * It was not a performance guard worth keeping: it saved a tokenisation pass
 * over at most 25 short strings, which is noise beside one inference.
 */
fun interface ToolSelector {
    fun select(
        task: String,
        sessionKeywords: List<String>,
        available: List<AgentTool>,
        maxTools: Int,
    ): List<AgentTool>

    /**
     * The same decision, with the reasoning attached.
     *
     * ## WHY THIS EXISTS AND WHY IT IS A DEFAULT RATHER THAN AN ABSTRACT MEMBER
     *
     * An unselected tool is not "less likely", it is **uncallable**: this
     * selector's list is what `AgentController.buildRequest` hands to
     * `GrammarBuilder.forActions`, so a name outside it cannot be produced by a
     * grammar-constrained decode at all. A trace that records only the chosen
     * list therefore cannot distinguish "the model chose not to call
     * `calendar.create`" from "`calendar.create` was never offered", and those
     * are completely different bugs with completely different fixes.
     *
     * It is a default member with a body because [ToolSelector] is a
     * `fun interface` and every implementation in this repository - including
     * the one a future eval harness writes - has to keep working unchanged. The
     * default is HONEST about what it knows: it reports
     * [UnselectedTool.SCORE_UNREPORTED] and names the strategy `"unreported"`,
     * rather than inventing a score it did not compute. A trace that claims a
     * ranking no selector produced is worse than a trace that admits there was
     * none.
     */
    fun explain(
        task: String,
        sessionKeywords: List<String>,
        available: List<AgentTool>,
        maxTools: Int,
    ): SelectionReport {
        val chosen = select(task, sessionKeywords, available, maxTools)
        val names = chosen.map { it.definition.name }
        return SelectionReport(
            strategy = "unreported",
            maxTools = maxTools,
            availableCount = available.size,
            selected = names,
            unselected = available
                .map { it.definition.name }
                .filterNot { it in names }
                .map { UnselectedTool(name = it, reason = "cut by this selector; score not reported") },
        )
    }
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
 * **The second bullet has since been fixed, and not in this class.** On the
 * 176-case dataset, 13 cases (7.4%) still had an expected tool scoring exactly
 * zero, and all 13 were repaired by adding words to the TAG LISTS on the
 * `:android` side — `pdf`, `block out`, `tell them`, `anything new`,
 * `look up`, `link`, and so on. Zero of them were fixed here, because the
 * scorer had no opinion to change. The zero-score count is now 0/176 on that
 * dataset and the independent held-out probe (`core/tool/holdout/`) goes
 * 20/25 -> 22/25.
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
 * reported. The tag lists above now carry both `meeting` and `meetings`, so
 * that particular gap is closed the way this contract says it should be.
 *
 * ## Tokenisation, and the one thing it could not fix
 *
 * `tokenize` used to split on `Regex("[^a-z0-9]+")`, an ASCII-only class. For
 * ASCII that is indistinguishable from correct; for anything else it fails
 * silently in two distinct ways — `"öffne"` tokenised to the *corrupted*
 * token `ffne` (not a missing letter, a different word), and `"検索して"`
 * tokenised to `[]`, so every tool scored zero and the visible set was decided
 * by the alphabetical tie-break. It is now a Unicode word-character class plus
 * NFC normalisation, with the `> 2` character floor exempted for scripts that
 * do not separate words with spaces. **The ASCII path is byte-identical**,
 * which the harness verifies by re-running all 176 cases.
 *
 * 6 -> 10 buys 8.1 points of retrieval for 101 prompt tokens, against a
 * working limit of `ContextCeiling.workingLimit(modelWindow)` — 2662 on this
 * app's 4096 allocation, not the 6000 prefill cost cap. Against 2662 those 101
 * tokens are 3.8% of the budget rather than 1.7%, so the trade is worse than
 * it looked when the ceiling was the cap. It is `AgentConfig.maxVisibleTools`,
 * one constant, in a file this change-set does not own — and it trades against
 * the opposite constraint, that a 1-3B model chooses less reliably from 10
 * tools than from 6. That is a product call with a measurement on both sides,
 * not a heuristic somebody should quietly pick.
 *
 * This does not make the selector multilingual. The catalogue is English, so
 * `bel Annabel` now tokenises honestly and still matches nothing: correct
 * tokenisation turns a silent zero into an honest low score, it does not
 * cross a language boundary.
 *
 * The lever that *is* measured, and the one that was taken, is width:
 *
 * | visible tools | tasks made possible | mean system-prompt tokens |
 * |---------------|---------------------:|-------------------------:|
 * | 3             |            164/176  |  331                     |
 * | 6 (was)       |            171/176  |  411                     |
 * | **10 (ships)**|    **176/176**      |  **516**                 |
 * | 12            |            176/176  |  566                     |
 * | all 25        |            176/176  |  894                     |
 *
 * The 176/176 is a SATURATED metric, not a solved selector. The row above it
 * used to read 167/176, and the 13 cases it was missing were missing because
 * the expected tool shared no word with any description or tag. Those were
 * fixed in the tag lists, and the words were chosen while reading these 176
 * utterances — so 100% here is the shape of an overfit, and the honest
 * generalisation figure is the independent held-out probe in
 * `core/tool/holdout/`, which goes 20/25 -> 22/25.
 *
 * Note what that does to the width argument: k=3 alone is now 93.2% and k=6
 * is 97.2%, so most of what 6 -> 10 was bought for has been bought back by
 * fixing the tags instead. The ceiling is kept at 10 on the strength of an
 * unmeasured small-model-reliability argument, not on this table.
 *
 * Measured on the 176-case dataset committed at
 * `core/tool/eval/SelectorDataset.kt`, against the 25 tools `:android` ships,
 * over a REPLACEMENT for the utterance list the old numbers used. The old
 * figures (120 pinned utterances, 86 held out, 61.6% at k=6) are not
 * comparable to these and are not restated here: the dataset is different, the
 * absolute percentages therefore differ, and quoting both as one trend would
 * be inventing a curve. What carries over is the SHAPE — recall rises
 * steeply to about 10 and then flattens — and the shape is what the constant
 * is set from.
 *
 * `AgentConfig.maxVisibleTools` now ships at 10. The tie rate is why it is 10
 * and not 12: at k=10 the 10th and 11th tools score identically on 86.9% of
 * turns and at k=12 on 96.6%, so width past 10 is bought from the alphabet
 * rather than from the ranking. See that constant's KDoc for the full
 * reasoning, the width-safety check against the working limit, and the half of
 * the trade that stays unmeasured.
 *
 * **These numbers are reproducible.** `core/tool/eval/` holds the dataset, the
 * tool snapshot and a `main()` harness; run
 *
 * ```
 * ./gradlew :core:compileKotlin
 * ./core/src/main/kotlin/dev/localintelligence/core/tool/eval/run-recall-harness.sh
 * ```
 *
 * and it re-derives every row above from the live selector, reports whether the
 * snapshot still agrees with the shipped catalogue, and fails loudly if the
 * scorer has changed underneath a quoted figure. The old numbers could not be
 * re-derived at all, which is the reason they were unfalsifiable rather than
 * merely old.
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
    ): List<AgentTool> = rank(task, sessionKeywords, available, maxTools).tools

    /**
     * The scored report, including which of the three paths below ran.
     *
     * ## WHY THE STRATEGY IS RECORDED AT ALL
     *
     * [select] has three exits and two of them do not score anything:
     * an empty registry returns empty, and a registry that already fits inside
     * [maxTools] returns itself untouched. A trace that reported only the
     * resulting list would render both as "the selector chose these", which is
     * a claim it did not make - and the second one matters, because "everything
     * was offered" and "these six beat the other nineteen" are the two facts a
     * retrieval miss looks like from either side.
     */
    override fun explain(
        task: String,
        sessionKeywords: List<String>,
        available: List<AgentTool>,
        maxTools: Int,
    ): SelectionReport {
        if (available.isEmpty()) {
            return SelectionReport(strategy = "empty-registry", maxTools = maxTools, availableCount = 0)
        }
        if (available.size <= maxTools) {
            // Nothing was scored and nothing was cut. Reported as the pass-through
            // it is, so a reader can tell this apart from a real ranking.
            return SelectionReport(
                strategy = "all-fit",
                maxTools = maxTools,
                availableCount = available.size,
                selected = available.map { it.definition.name },
                unselected = emptyList(),
            )
        }
        val ranked = rank(task, sessionKeywords, available, maxTools)
        val cut = ranked.ordered.drop(ranked.tools.size)
        return SelectionReport(
            strategy = "lexical",
            maxTools = maxTools,
            availableCount = available.size,
            selected = ranked.tools.map { it.definition.name },
            unselected = cut.mapIndexed { index, entry ->
                UnselectedTool(
                    name = entry.first.definition.name,
                    score = entry.second,
                    // +1 so a rank of 1 is the best tool, not the first cut.
                    rank = ranked.tools.size + index + 1,
                    reason = reasonFor(entry.second, ranked.tools.size),
                )
            },
        )
    }

    /**
     * Why a tool lost its place, in the selector's own terms.
     *
     * A zero score is called out because it is the failure this class's own
     * KDoc measures: "30.2% of turns give the correct tool a score of exactly
     * zero - no shared word with any description or tag." A cut tool with a
     * score of zero did not lose a ranking, it was never in one, and a trace
     * that rendered it as "rank 14" would hide that completely.
     */
    private fun reasonFor(score: Int, cutAt: Int): String = when {
        score <= 0 -> "scored 0: no shared word with any name, description or tag"
        else -> "scored $score, below the top $cutAt"
    }

    private fun rank(
        task: String,
        sessionKeywords: List<String>,
        available: List<AgentTool>,
        maxTools: Int,
    ): Ranked {
        // BOTH EARLY EXITS ARE PRESERVED VERBATIM, AND THE SECOND ONE IS A
        // BEHAVIOURAL CONTRACT RATHER THAN AN OPTIMISATION.
        //
        // `available.size <= maxTools` returns the registry's own list, in the
        // registry's own order. Sorting it instead would be "equivalent" in the
        // sense that the same tools are shown - and would change the order they
        // are shown in, which is the order the system prompt lists them and
        // therefore the order the grammar offers them. A small model reads a
        // list, and the first tool in a list is not the same as the second. The
        // shipped behaviour is pass-through, and a tracing change is not the
        // place to quietly re-rank a prompt.
        if (available.isEmpty()) return Ranked(emptyList(), emptyList())
        if (available.size <= maxTools) return Ranked(available, emptyList())

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

        val ordered = scored.sortedWith(
            compareByDescending<Pair<AgentTool, Int>> { it.second }.thenBy { it.first.definition.name },
        )
        return Ranked(ordered.take(maxTools).map { it.first }, ordered)
    }

    /** The chosen tools, and the full ranking they were taken from. */
    private class Ranked(val tools: List<AgentTool>, val ordered: List<Pair<AgentTool, Int>>)

    private fun tokenize(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        // Locale.ROOT matters: a Turkish default locale case-folds `I` to a
        // dotless `ı` and silently breaks every ASCII tag.
        val normalized = Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFC)

        val out = ArrayList<String>()
        val current = StringBuilder()
        var i = 0
        while (i < normalized.length) {
            val cp = normalized.codePointAt(i)
            val charCount = Character.charCount(cp)
            if (isWordCharacter(cp)) {
                current.appendCodePoint(cp)
            } else if (current.isNotEmpty()) {
                out += current.toString()
                current.setLength(0)
            }
            i += charCount
        }
        if (current.isNotEmpty()) out += current.toString()

        return out.filter { token ->
            if (isWordLike(token)) token.length > MIN_WORD_LENGTH else true
        }
    }

    private fun isWordCharacter(codePoint: Int): Boolean {
        val type = Character.getType(codePoint)
        return type == Character.UPPERCASE_LETTER.toInt() ||
            type == Character.LOWERCASE_LETTER.toInt() ||
            type == Character.TITLECASE_LETTER.toInt() ||
            type == Character.MODIFIER_LETTER.toInt() ||
            type == Character.OTHER_LETTER.toInt() ||
            type == Character.DECIMAL_DIGIT_NUMBER.toInt() ||
            type == Character.LETTER_NUMBER.toInt() ||
            type == Character.OTHER_NUMBER.toInt() ||
            type == Character.NON_SPACING_MARK.toInt() ||
            type == Character.COMBINING_SPACING_MARK.toInt()
    }

    private fun isWordLike(token: String): Boolean =
        Character.getType(token.codePointAt(0)) != Character.OTHER_LETTER.toInt() ||
            !isUnspacedScript(token.codePointAt(0))

    private fun isUnspacedScript(codePoint: Int): Boolean =
        when (codePoint) {
            in 0x3040..0x30FF -> true // Hiragana, Katakana
            in 0x3400..0x4DBF -> true // CJK Unified Ext A
            in 0x4E00..0x9FFF -> true // CJK Unified
            in 0xF900..0xFAFF -> true // CJK Compatibility Ideographs
            in 0xAC00..0xD7AF -> true // Hangul syllables
            in 0x0E00..0x0E7F -> true // Thai
            in 0x20000..0x2FA1F -> true // CJK Unified Ext B-F
            else -> false
        }

    private companion object {
        /** Shortest token the scorer will consider for non-CJK scripts. */
        const val MIN_WORD_LENGTH = 2

    }
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
