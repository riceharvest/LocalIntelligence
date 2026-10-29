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
)
