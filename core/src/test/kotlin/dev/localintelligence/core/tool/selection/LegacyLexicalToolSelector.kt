package dev.localintelligence.core.tool.selection

import dev.localintelligence.core.tool.AgentTool
import dev.localintelligence.core.tool.ToolSelector

// ===========================================================================
// LegacyLexicalToolSelector.kt — the selector as it was before stemming.
//
// WHY A FROZEN COPY INSTEAD OF A GIT CHECKOUT
// ===========================================
//
// The before/after claim in the PR is only worth something if the "before" can
// be re-measured by anyone, forever, without checking out an old commit. This is
// a verbatim copy of the `LexicalToolSelector` body as it stood on
// origin/main@cedb20d: lowercase, split on non-alphanumerics, drop tokens of
// two characters or fewer, no stemming, no synonyms, the same weights.
//
// It lives in the TEST source set on purpose. It is an instrument, not a
// shipped code path: nothing in `:core` main references it, so the production
// selector cannot accidentally keep a branch that only exists to make a
// before/after chart line up.
//
// If the weights in `LexicalToolSelector` ever change, this copy must change in
// the same commit, or the two columns silently stop being comparable. That is
// asserted by `the legacy baseline is still the shipped algorithm`.
@Deprecated(
    "Frozen pre-stemming selector, kept only to re-measure the before column.",
    level = DeprecationLevel.WARNING,
)
class LegacyLexicalToolSelector : ToolSelector {

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
