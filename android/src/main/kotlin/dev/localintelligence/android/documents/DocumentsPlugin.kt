package dev.localintelligence.android.documents

import dev.localintelligence.android.tools.documents.DocumentSearchTool
import dev.localintelligence.core.model.ToolArgs
import dev.localintelligence.core.plugin.AgentPlugin
import dev.localintelligence.core.plugin.PluginHooks
import dev.localintelligence.core.settings.SettingDef
import dev.localintelligence.core.settings.SettingSection
import dev.localintelligence.core.settings.SettingsReader
import dev.localintelligence.core.tool.AgentTool

/**
 * "Documents": answers from the PDFs and text files the user added.
 *
 * On by default, and it does nothing until a document exists: [tools] is empty
 * while the store is empty, so the model is never offered `documents.search` with
 * nothing to search. The Documents screen is the only place a document is added.
 *
 * ## WHY THE RESULT CAP IS AN AFTER-TOOL HOOK
 *
 * The tool is built once and shared by every run, but the "passages per search"
 * setting is read per run from the frozen settings snapshot, and the tool has no
 * way to see that snapshot. So the tool returns up to ten passages, one line each,
 * and this plugin keeps the first N lines of its observation for the run. The cap
 * is applied before the model sees anything, and the model can still ask for fewer
 * with `max_results`.
 */
class DocumentsPlugin(private val store: DocumentStore) : AgentPlugin {
    override val id: String = "documents"
    override val title: String = "Documents"
    override val summary: String =
        "Answers from the PDF and text files you added on the Documents screen. Nothing happens until you add one."

    override val settings: List<SettingDef<*>> = listOf(DOCUMENT_RESULTS)

    override fun tools(): List<AgentTool> = if (store.isEmpty()) emptyList() else shippedTools()

    /** The tool this plugin can offer, whether or not a document exists yet. For the catalogue check. */
    fun shippedTools(): List<AgentTool> = listOf(DocumentSearchTool(store))

    override fun hooks(settings: SettingsReader): PluginHooks {
        val cap = settings[DOCUMENT_RESULTS]
        return object : PluginHooks {
            override fun afterTool(name: String, args: ToolArgs, observation: String, success: Boolean): String {
                if (name != TOOL_NAME || !success) return observation
                return keepFirstPassages(observation, cap)
            }
        }
    }

    private companion object {
        const val TOOL_NAME = "documents.search"

        /**
         * The observation is a header line, then one line per passage. Keeps the header and the
         * first [max] passage lines, and leaves anything else untouched.
         */
        fun keepFirstPassages(observation: String, max: Int): String {
            val lines = observation.lines()
            if (lines.size <= max + 1) return observation
            return (lines.take(1) + lines.drop(1).take(max)).joinToString("\n")
        }
    }
}

/** How many passages one search adds to the request. Read per run, so a change applies from the next message. */
val DOCUMENT_RESULTS: SettingDef.IntValue = SettingDef.IntValue(
    key = "documents.results",
    title = "Passages per search",
    help = "How many passages from your documents a search adds. Small models do best with a few.",
    section = SettingSection.PLUGINS,
    default = 4,
    min = 1,
    max = 10,
)
