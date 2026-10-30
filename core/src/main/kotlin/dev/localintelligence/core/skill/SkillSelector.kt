package dev.localintelligence.core.skill

import dev.localintelligence.core.tool.lexicalTokens

/**
 * Picks the skills that fit a task, by the same lexical scoring the tool
 * selector uses: a word shared with the description counts 2, a word shared
 * with the name counts 4, and the task containing the whole name counts 10.
 *
 * Two differences from the tool selector, both because skill descriptions are
 * prose and tool descriptions are not:
 *
 *  - Stop words are dropped from both sides. A skill that says "Use this skill
 *    when the user asks..." shares "use", "this", "when", "user" with almost
 *    every request, and without this filter the threshold admits every skill.
 *  - Nothing is picked below [threshold]. A small model handed an unrelated
 *    persona follows it, so "no skill" is the right answer for most tasks.
 *
 * The whole-name bonus needs the name as a WORD of the task. A plain substring
 * test let a short name ("map", "pdf", "cat") win every task that happens to
 * contain it ("mapping", "pdfs", "location") with the full bonus of 10, which
 * clears the threshold on its own.
 */
class SkillSelector(private val threshold: Int = DEFAULT_THRESHOLD) {

    fun select(task: String, skills: List<Skill>, max: Int): List<Skill> {
        if (max <= 0 || skills.isEmpty() || task.isBlank()) return emptyList()
        val taskTokens = content(task)
        if (taskTokens.isEmpty()) return emptyList()

        return skills.map { skill ->
            val descTokens = content(skill.description)
            val nameTokens = content(skill.name)
            val score = taskTokens.intersect(descTokens).size * 2 +
                taskTokens.intersect(nameTokens).size * 4 +
                if (namedInTask(task, skill.name)) 10 else 0
            skill to score
        }
            .filter { (_, score) -> score >= threshold }
            .sortedWith(compareByDescending<Pair<Skill, Int>> { it.second }.thenBy { it.first.name })
            .take(max)
            .map { it.first }
    }

    private fun content(text: String): Set<String> =
        lexicalTokens(text).filterNot { it in STOP_WORDS }.toSet()

    /** [name] as a whole word or phrase of [task]: not inside a longer word of it. */
    private fun namedInTask(task: String, name: String): Boolean {
        if (name.isBlank()) return false
        val pattern = Regex("(?<![a-z0-9])" + Regex.escape(name) + "(?![a-z0-9])", RegexOption.IGNORE_CASE)
        return pattern.containsMatchIn(task)
    }

    companion object {
        /** Two description words, or one name word. */
        const val DEFAULT_THRESHOLD = 4

        private val STOP_WORDS = setOf(
            "the", "and", "for", "with", "that", "this", "these", "those", "from", "into", "onto",
            "your", "you", "yours", "our", "their", "they", "them", "its", "his", "her", "she", "him",
            "use", "used", "uses", "using", "when", "what", "where", "which", "while", "who", "how",
            "user", "users", "asks", "ask", "asked", "want", "wants", "need", "needs", "help", "helps",
            "skill", "skills", "make", "makes", "get", "gets", "give", "about", "also", "are", "was",
            "were", "will", "would", "should", "could", "can", "does", "did", "not", "but", "all",
            "any", "some", "more", "most", "than", "then", "there", "here", "have", "has", "had",
            "been", "being", "just", "only", "very", "such", "per", "via", "etc", "including",
        )
    }
}
