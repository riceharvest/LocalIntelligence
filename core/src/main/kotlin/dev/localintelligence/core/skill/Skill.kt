package dev.localintelligence.core.skill

/**
 * A SKILL.md file, read: the frontmatter's `name` and `description`, and the
 * markdown after it. The folder it lives in is not part of the value; that is
 * the storage layer's business.
 */
data class Skill(
    val name: String,
    val description: String,
    val body: String,
    /**
     * Tools this skill names in `allowed-tools:`, or empty when it names none. While the skill
     * runs, calls to any other tool are refused, except the core tools (see SkillsPlugin).
     *
     * WHY THIS DIFFERS FROM CLAUDE CODE: there, `allowed-tools` pre-approves tools so they run
     * without a prompt and does not restrict anything. Here the same key restricts: the app's
     * skills are for a small model that must not wander, and the owner asked for this.
     */
    val allowedTools: Set<String> = emptySet(),
)
