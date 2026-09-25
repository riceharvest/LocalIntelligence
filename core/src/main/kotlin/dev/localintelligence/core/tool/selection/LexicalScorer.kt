package dev.localintelligence.core.tool.selection

import dev.localintelligence.core.tool.AgentTool
import dev.localintelligence.core.tool.ToolDefinition

// ===========================================================================
// LexicalScorer.kt — the scoring kernel, in one place and pure.
//
// WHY A SEPARATE SCORER
// ======================
//
// Before this change the scoring lived inside `LexicalToolSelector` and
// `AndroidEvalTest` proved a second copy in the eval suite ranked identically
// to the first. That duplication is now a liability: two implementations of
// six weights plus a tokenizer plus a synonym table is three places to forget
// to update, and a benchmark whose replication has silently drifted explains
// misses wrongly — which is worse than not explaining them at all.
//
// So the kernel is public, pure and here, and both the selector and the
// benchmark's diagnostic call it. The replication test still exists and still
// has teeth: it now checks the real selector against the shared kernel, which
// is the invariant that actually matters (the shipped ranking is the kernel).
// ===========================================================================

/**
 * Scores tools against a task, deterministically.
 *
 * Split out of [dev.localintelligence.core.tool.LexicalToolSelector] so the
 * weights, the tokenizer and the synonym expansion are one auditable unit. The
 * brief for a "perfect loop with bad tool selection still fails" is only
 * defensible if the selection half is itself inspectable.
 */
object LexicalScorer {

    /**
     * Per-field weights. Unchanged from the pre-stemming selector on purpose.
     *
     * A name match is the strongest signal because the name is the only part of
     * a tool the model will also see in the grammar; a description match is
     * prose and therefore noisier; a tag match sits in between because tags are
     * curated keywords rather than either. Changing these would move the tuned
     * suite for reasons that have nothing to do with stemming, which would make
     * the before/after table unreadable.
     */
    const val NAME_WEIGHT = 4
    const val DESCRIPTION_WEIGHT = 2
    const val TAG_WEIGHT = 3
    const val KEYWORD_DESCRIPTION_WEIGHT = 1
    const val KEYWORD_TAG_WEIGHT = 1

    /**
     * A literal dotted tool name in the task ("use files.search") is worth
     * [SUBSTRING_BONUS] on its own — more than any combination of field hits.
     * A small model asked to "call a tool" reaches for a name, so an exact name
     * in the utterance is the strongest possible evidence of intent.
     */
    const val SUBSTRING_BONUS = 10

    /**
     * Weight for a hit that arrived through synonym expansion rather than
     * directly.
     *
     * One point against three for a tag hit. The point of the number is the
     * ratio, not the value: expansion must be able to pull a tool INTO a
     * six-wide window when nothing matched, and must never be able to outrank a
     * tool that the user actually named. Two points would let a two-synonym
     * match beat a real tag hit, which is how "text" (a tag on three unrelated
     * tools) became a ranking bug before this weight existed.
     */
    const val EXPANSION_WEIGHT = 1

    /** Tokens this short are dropped. Two letters carry no retrieval signal. */
    const val MIN_TOKEN_LENGTH = 3

    /**
     * Scores one tool against one task.
     *
     * [task] and [sessionKeywords] are stemmed and expanded; the tool's own
     * name, description and tags are stemmed but NOT expanded — see the
     * query-only expansion note in [LexicalNormalizer]. That asymmetry is the
     * whole design: it lets a synonym pull a tool into the window without ever
     * making two tools look more alike to each other.
     */
    fun score(
        task: String,
        sessionKeywords: List<String>,
        def: ToolDefinition,
    ): Int = score(task, sessionKeywords, def, LexicalNormalizer.synonymTable)

    /**
     * Scores with an explicit synonym table.
     *
     * The public overload pins the shipped table. The internal one exists so
     * `SynonymAblationTest` can suppress entries one at a time without mutating
     * production state — see [rank].
     */
    internal fun score(
        task: String,
        sessionKeywords: List<String>,
        def: ToolDefinition,
        table: Map<String, Set<String>>,
    ): Int {
        val directStems = LexicalNormalizer.normalizedTokens(task).toSet()
        val expansionStems = LexicalNormalizer.expansionOnlyStems(task, table)
        val keywordStems = sessionKeywords
            .flatMap { LexicalNormalizer.normalizedTokens(it) }
            .toSet()

        val nameStems = LexicalNormalizer.normalizedTokens(def.name).toSet()
        val descStems = LexicalNormalizer.normalizedTokens(def.description).toSet()
        val tagStems = def.tags.flatMap { LexicalNormalizer.normalizedTokens(it) }.toSet()

        val direct = directStems.intersect(descStems).size * DESCRIPTION_WEIGHT +
            directStems.intersect(tagStems).size * TAG_WEIGHT +
            directStems.intersect(nameStems).size * NAME_WEIGHT +
            keywordStems.intersect(descStems).size * KEYWORD_DESCRIPTION_WEIGHT +
            keywordStems.intersect(tagStems).size * KEYWORD_TAG_WEIGHT

        // Expansion is counted only against tags and names: a synonym reaching a
        // tool's DESCRIPTION means the synonym has become a description word,
        // which is a tool-authoring decision, not a query-side one.
        val expansion = expansionStems.intersect(tagStems).size * EXPANSION_WEIGHT +
            expansionStems.intersect(nameStems).size * EXPANSION_WEIGHT

        val substring = if (task.contains(def.name, ignoreCase = true)) SUBSTRING_BONUS else 0

        return direct + expansion + substring
    }

    /** Scores an [AgentTool] using the same kernel. */
    fun score(task: String, sessionKeywords: List<String>, tool: AgentTool): Int =
        score(task, sessionKeywords, tool.definition)

    /**
     * Ranks and truncates, with the table supplied.
     *
     * This is the one place the ranking exists, so the shipped selector and the
     * ablation harness in the test source set cannot disagree about what a
     * filtered synonym table produces. The suppression hook is `internal` and
     * exists for exactly one caller: `SynonymAblationTest`, which has to answer
     * "does this entry earn its place" by removing entries one at a time.
     */
    internal fun rank(
        task: String,
        sessionKeywords: List<String>,
        available: List<AgentTool>,
        maxTools: Int,
        table: Map<String, Set<String>>,
    ): List<AgentTool> {
        if (available.isEmpty()) return emptyList()
        if (available.size <= maxTools) return available

        val scored = available.map { tool ->
            tool to score(task, sessionKeywords, tool.definition, table)
        }

        // Ties break on name so the ranking is total and reproducible: two runs
        // of the same input must produce byte-identical prompts, or a bad turn
        // cannot be reproduced from a log.
        return scored.sortedWith(
            compareByDescending<Pair<AgentTool, Int>> { it.second }
                .thenBy { it.first.definition.name },
        ).take(maxTools).map { it.first }
    }

    /**
     * The raw components, for a miss that needs diagnosing.
     *
     * [LexicalNormalizer] and the benchmark's `LexicalScore` both exist to
     * answer "why did it miss". This is the one place the answer comes from, so
     * the diagnosis cannot disagree with the ranking it is explaining.
     */
    fun breakdown(
        task: String,
        sessionKeywords: List<String>,
        def: ToolDefinition,
    ): ScoreBreakdown {
        val directStems = LexicalNormalizer.normalizedTokens(task).toSet()
        val expansionStems = LexicalNormalizer.expansionOnlyStems(task)
        val keywordStems = sessionKeywords
            .flatMap { LexicalNormalizer.normalizedTokens(it) }
            .toSet()
        val nameStems = LexicalNormalizer.normalizedTokens(def.name).toSet()
        val descStems = LexicalNormalizer.normalizedTokens(def.description).toSet()
        val tagStems = def.tags.flatMap { LexicalNormalizer.normalizedTokens(it) }.toSet()

        val directDesc = directStems.intersect(descStems)
        val directTags = directStems.intersect(tagStems)
        val directName = directStems.intersect(nameStems)
        val expandedTags = expansionStems.intersect(tagStems)
        val expandedName = expansionStems.intersect(nameStems)
        val substring = if (task.contains(def.name, ignoreCase = true)) SUBSTRING_BONUS else 0

        return ScoreBreakdown(
            descriptionHits = directDesc,
            tagHits = directTags,
            nameHits = directName,
            keywordDescriptionHits = keywordStems.intersect(descStems),
            keywordTagHits = keywordStems.intersect(tagStems),
            expandedTagHits = expandedTags,
            expandedNameHits = expandedName,
            substringBonus = substring,
        )
    }
}

/** Which stems matched which field, so a miss can be read without a debugger. */
data class ScoreBreakdown(
    val descriptionHits: Set<String>,
    val tagHits: Set<String>,
    val nameHits: Set<String>,
    val keywordDescriptionHits: Set<String>,
    val keywordTagHits: Set<String>,
    val expandedTagHits: Set<String>,
    val expandedNameHits: Set<String>,
    val substringBonus: Int,
) {
    val total: Int
        get() = descriptionHits.size * LexicalScorer.DESCRIPTION_WEIGHT +
            tagHits.size * LexicalScorer.TAG_WEIGHT +
            nameHits.size * LexicalScorer.NAME_WEIGHT +
            keywordDescriptionHits.size * LexicalScorer.KEYWORD_DESCRIPTION_WEIGHT +
            keywordTagHits.size * LexicalScorer.KEYWORD_TAG_WEIGHT +
            (expandedTagHits.size + expandedNameHits.size) * LexicalScorer.EXPANSION_WEIGHT +
            substringBonus

    fun render(toolName: String): String = buildString {
        appendLine("$toolName  total=$total")
        if (nameHits.isNotEmpty()) appendLine("  name        ${nameHits.sorted()} x${LexicalScorer.NAME_WEIGHT}")
        if (tagHits.isNotEmpty()) appendLine("  tags        ${tagHits.sorted()} x${LexicalScorer.TAG_WEIGHT}")
        if (descriptionHits.isNotEmpty()) {
            appendLine("  description ${descriptionHits.sorted()} x${LexicalScorer.DESCRIPTION_WEIGHT}")
        }
        if (expandedTagHits.isNotEmpty()) {
            appendLine("  expanded    ${expandedTagHits.sorted()} x${LexicalScorer.EXPANSION_WEIGHT} (synonym)")
        }
        if (expandedNameHits.isNotEmpty()) {
            appendLine("  expanded    ${expandedNameHits.sorted()} x${LexicalScorer.EXPANSION_WEIGHT} (synonym)")
        }
        if (keywordTagHits.isNotEmpty()) {
            appendLine("  keyword     ${keywordTagHits.sorted()}")
        }
        if (keywordDescriptionHits.isNotEmpty()) {
            appendLine("  keyword     ${keywordDescriptionHits.sorted()} (description)")
        }
        if (substringBonus > 0) appendLine("  substring   +$substringBonus")
    }
}
