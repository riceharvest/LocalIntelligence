package dev.localintelligence.core.memory

import dev.localintelligence.core.plugin.AgentPlugin
import dev.localintelligence.core.plugin.PluginHooks
import dev.localintelligence.core.settings.SettingDef
import dev.localintelligence.core.settings.SettingSection
import dev.localintelligence.core.settings.SettingsReader

/**
 * "About you": one short note the user writes about themselves, shown to the model on
 * every step while the plugin is on.
 *
 * ## WHY IT IS A SETTING AND NOT A MEMORY ROW
 *
 * [MemoryWritePolicy] decides what the agent may remember on its own, and it is a
 * deliberately narrow pattern filter. A profile is the opposite: the user writes it
 * deliberately, edits it in Settings, and can see all of it at once. Storing it as a
 * [SettingDef] puts it in the same one store and the same Simple/Technical rules as
 * every other option, and nothing else in the app writes to it.
 *
 * ## WHY THE CAP IS 1,375
 *
 * Hermes' USER.md is capped at that many characters, and the number is kept because it
 * is a known-good size: enough for a paragraph of facts, small enough that it is about
 * 350 tokens in a prompt a 3B model has to hold alongside the tools.
 *
 * Nothing here calls a model or touches a database. The hook reads one setting per run.
 */
class ProfilePlugin : AgentPlugin {
    override val id: String = "memory.profile"
    override val title: String = "About you"
    override val summary: String =
        "A short note about you that the assistant sees on every step. You write it; nothing else changes it."

    override val settings: List<SettingDef<*>> = listOf(PROFILE)

    override fun hooks(settings: SettingsReader): PluginHooks = object : PluginHooks {
        override fun contextForStep(task: String): List<String> {
            val text = settings[PROFILE].trim()
            if (text.isEmpty()) return emptyList()
            return listOf("About the user:", text)
        }
    }

    companion object {
        /** Hermes USER.md cap. See the class KDoc for the number. */
        const val MAX_CHARS: Int = 1_375

        val PROFILE = SettingDef.Text(
            key = "memory.profile.text",
            title = "About you",
            help = "Facts the assistant should always know: your name, home city, work, " +
                "how you like answers. Leave empty to share nothing.",
            section = SettingSection.GENERAL,
            default = "",
            maxLength = MAX_CHARS,
            simple = true,
        )
    }
}
