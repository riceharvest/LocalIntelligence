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
     * runs, the prompt and the tool grammar list only these tools plus the core tools
     * (PluginHooks.toolScope), so the model never sees the others. A call to any other tool is
     * still refused (beforeTool), as a backstop. An unscoped skill among the matched ones
     * leaves the run unrestricted (see SkillsPlugin).
     *
     * WHY THIS DIFFERS FROM OTHER AGENT TOOLS: in some of them, `allowed-tools` pre-approves tools so they run
     * without a prompt and does not restrict anything. Here the same key restricts: the app's
     * skills are for a small model that must not wander, and the owner asked for this.
     */
    val allowedTools: Set<String> = emptySet(),
)
