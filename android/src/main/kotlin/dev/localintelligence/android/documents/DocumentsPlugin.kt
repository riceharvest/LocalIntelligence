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

    override val settings: List<SettingDef<*>> = listOf(DOCUMENT_RESULTS, DOCUMENT_SEARCH_MODE, DOCUMENT_EMBEDDING_MODEL)

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

/** Where the embedding model comes from: the EmbeddingGemma 2 Q8_0 build the hub suggests. */
const val DEFAULT_EMBEDDING_REPO: String = "ggml-org/embeddinggemma-2-GGUF"
const val DEFAULT_EMBEDDING_FILE: String = "embeddinggemma-2-Q8_0.gguf"

const val SEARCH_MODE_HYBRID: String = "hybrid"
const val SEARCH_MODE_KEYWORD: String = "keyword"

/**
 * How a search ranks passages. Hybrid adds meaning to the keywords when the embedding
 * model is on the phone; without the model it is keyword search either way.
 */
val DOCUMENT_SEARCH_MODE: SettingDef.Choice = SettingDef.Choice(
    key = "documents.search_mode",
    title = "Search mode",
    help = "Hybrid also finds passages by meaning when the document search model is on this phone, " +
        "so a question worded differently from the document can still find it. Keyword matches exact words only.",
    section = SettingSection.PLUGINS,
    default = SEARCH_MODE_HYBRID,
    options = listOf(
        SettingDef.Choice.Option(SEARCH_MODE_HYBRID, "Hybrid"),
        SettingDef.Choice.Option(SEARCH_MODE_KEYWORD, "Keyword only"),
    ),
    simple = true,
)

/** The GGUF file name, in the models folder, used for meaning search. Empty reads as the default. */
val DOCUMENT_EMBEDDING_MODEL: SettingDef.Text = SettingDef.Text(
    key = "documents.embedding_model",
    title = "Document search model",
    help = "File name of the embedding model in the models folder. The Documents screen fetches the default. " +
        "Documents are searched by meaning only while this file is on the phone.",
    section = SettingSection.PLUGINS,
    default = DEFAULT_EMBEDDING_FILE,
    maxLength = 120,
    blankMeansDefault = true,
)
