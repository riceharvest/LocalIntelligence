package dev.localintelligence.android.skills

import dev.localintelligence.core.agent.NATIVE_CORE_TOOLS
import dev.localintelligence.core.model.ToolArgs
import dev.localintelligence.core.plugin.AgentPlugin
import dev.localintelligence.core.plugin.PluginHooks
import dev.localintelligence.core.plugin.ToolGate
import dev.localintelligence.core.tool.ObservationStore
import dev.localintelligence.core.tool.ToolFinder
import dev.localintelligence.core.settings.SettingDef
import dev.localintelligence.core.settings.SettingSection
import dev.localintelligence.core.settings.SettingsReader
import dev.localintelligence.core.skill.Skill
import dev.localintelligence.core.skill.SkillSelector

/** The settings the skills plugin declares, and the per-skill switch keys. */
object SkillSettings {
    val MAX_PER_STEP = SettingDef.IntValue(
        key = "skills.max_per_step",
        title = "Skills per step",
        help = "How many matching skills are added to each request. Small models do best with one.",
        section = SettingSection.PLUGINS,
        default = 1, min = 1, max = 3,
    )

    val CHAR_BUDGET = SettingDef.IntValue(
        key = "skills.char_budget",
        title = "Skill text size",
        help = "The most characters of skill text added to one request. Longer skills are cut.",
        section = SettingSection.PLUGINS,
        default = 1500, min = 200, max = 6000, step = 100,
    )

    /**
     * One switch per installed skill, keyed by its folder. Built on demand, so
     * the screen and the plugin read the same key. `simple = true`, because a
     * switched-off skill must stay off in Simple mode too; a non-simple row
     * would read as its Simple default during a run.
     */
    fun enabled(slug: String): SettingDef.Toggle = SettingDef.Toggle(
        key = "skill.$slug.enabled",
        title = slug,
        help = "Use this skill when a request matches it.",
        section = SettingSection.PLUGINS,
        default = true,
        simple = true,
    )
}

/**
 * Skills: saved instructions the assistant follows when a request matches one.
 *
 * Only a hook is used. [contextForStep] adds the best-matching enabled skills to
 * the system prompt, trimmed to the character budget, and adds no tools. The
 * skills are read once per run, in [hooks], so a skill saved mid-run applies
 * from the next message.
 */
class SkillsPlugin(private val repository: SkillRepository) : AgentPlugin {
    override val id: String = "skills"
    override val title: String = "Skills"
    override val summary: String = "Step-by-step instructions you saved. Used when a request matches one."
    override val settings: List<SettingDef<*>> = listOf(SkillSettings.MAX_PER_STEP, SkillSettings.CHAR_BUDGET)

    override fun hooks(settings: SettingsReader): PluginHooks? {
        val enabled: List<Skill> = repository.list()
            .filter { settings[SkillSettings.enabled(it.slug)] }
            .map { it.skill }
        if (enabled.isEmpty()) return null
        return SkillHooks(
            skills = enabled,
            max = settings[SkillSettings.MAX_PER_STEP],
            budget = settings[SkillSettings.CHAR_BUDGET],
        )
    }

    private class SkillHooks(
        private val skills: List<Skill>,
        private val max: Int,
        private val budget: Int,
    ) : PluginHooks {
        private val selector = SkillSelector()

        /**
         * The tools a scoped skill allows for the current step, or null when no restriction
         * applies. Set by [contextForStep] at the start of every step, so a run that reuses this
         * object never keeps the last run's scope.
         */
        @Volatile private var allowed: Set<String>? = null

        override fun beforeTool(name: String, args: ToolArgs): ToolGate {
            val scope = allowed ?: return ToolGate.Proceed
            if (name in scope || name in CORE_TOOLS) return ToolGate.Proceed
            return ToolGate.Deny(
                "The skill for this request allows only: ${scope.sorted().joinToString(", ")}, " +
                    "and the core tools.",
            )
        }

        override fun contextForStep(task: String): List<String> {
            val chosen = selector.select(task, skills, max)
            allowed = scopeOf(chosen)
            if (chosen.isEmpty()) return emptyList()
            val lines = ArrayList<String>()
            var left = budget
            for (skill in chosen) {
                val head = "Skill: ${skill.name}\n"
                if (left <= head.length + MIN_BODY) break
                val body = clip(skill.body, left - head.length)
                lines += head + body
                left -= head.length + body.length
            }
            return if (lines.isEmpty()) emptyList() else listOf(lines.joinToString("\n\n"))
        }
    }

    private companion object {
        /** Below this the header alone would be the whole injection, which is not useful. */
        const val MIN_BODY = 40

        /**
         * The tools every step may call whatever skill runs: the controller's core list, the
         * saved-result reader and the tool finder. Restricting these would strand the model.
         */
        val CORE_TOOLS: Set<String> = NATIVE_CORE_TOOLS.toSet() + setOf(ObservationStore.READ_TOOL, ToolFinder.NAME)

        /**
         * The union of what the chosen skills allow, or null when no restriction applies. A step
         * is restricted only when every chosen skill names tools: one unscoped skill among them
         * keeps today's behaviour, because it may need a tool the others do not name.
         */
        fun scopeOf(chosen: List<Skill>): Set<String>? {
            if (chosen.isEmpty() || chosen.any { it.allowedTools.isEmpty() }) return null
            return chosen.flatMap { it.allowedTools }.toSet()
        }

        /** Cuts at a word boundary and marks the cut, so the model knows the text is partial. */
        fun clip(text: String, max: Int): String {
            if (text.length <= max) return text
            if (max <= 1) return ""
            val cut = text.lastIndexOf(' ', startIndex = max - 2).takeIf { it > 0 } ?: (max - 1)
            return text.substring(0, cut).trimEnd() + "…"
        }
    }
}
