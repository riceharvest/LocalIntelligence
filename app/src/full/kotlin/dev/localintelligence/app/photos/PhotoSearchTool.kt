package dev.localintelligence.app.photos

import dev.localintelligence.android.tools.device.ArgCoerce
import dev.localintelligence.android.tools.device.ArgResult
import dev.localintelligence.app.photos.PhotoSearchHits
import dev.localintelligence.core.model.ToolArgs
import dev.localintelligence.core.plugin.AgentPlugin
import dev.localintelligence.core.settings.SettingDef
import dev.localintelligence.core.tool.AgentTool
import dev.localintelligence.core.tool.ToolContext
import dev.localintelligence.core.tool.ToolDefinition
import dev.localintelligence.core.tool.ToolError
import dev.localintelligence.core.tool.ToolResult
import dev.localintelligence.core.tool.catalogue.V0ToolCatalogue
import kotlinx.coroutines.CancellationException

// =====================================================================================
// photos.search (full build only, docs/adr/0002-play-and-full-builds.md). The play build has no
// READ_MEDIA_IMAGES and never registers this plugin, so its catalogue entry is listed in
// V0ToolCatalogue.FULL_BUILD_ONLY and skipped by the play build's agreement check.
//
// The tool reads only the index table. It never touches the photo library, so it needs no
// permission at search time; the permission is for indexing, on the Photo search screen.
// =====================================================================================

/** Passages returned when the model does not ask for a number. */
private const val PHOTO_DEFAULT_RESULTS = 4
private const val PHOTO_MAX_RESULTS = 10
private const val PHOTO_MAX_QUERY_CHARS = 200

/**
 * The Photos plugin. It is always listed in the full build, and its one tool is offered whenever the
 * plugin is on. The index is read through a lambda, so building the plugin never touches the container.
 */
class PhotoSearchPlugin(private val index: () -> PhotoSearchIndex?, private val cloudChatActive: () -> Boolean) :
    AgentPlugin {
    override val id: String = PHOTO_SEARCH_PLUGIN_ID
    override val title: String = "Photos"
    override val summary: String =
        "Finds photos you indexed in Photo search, by a caption the phone's own model wrote. Captions stay on this phone."
    override val enabledByDefault: Boolean = true
    override val settings: List<SettingDef<*>> = emptyList()

    override fun tools(): List<AgentTool> = listOf(PhotoSearchTool(index, cloudChatActive))
}

/** `photos.search`: the captions of the best-matching indexed photos. */
class PhotoSearchTool(
    private val index: () -> PhotoSearchIndex?,
    private val cloudChatActive: () -> Boolean,
) : AgentTool {

    override val definition: ToolDefinition =
        checkNotNull(V0ToolCatalogue.byName("photos.search")) { "photos.search missing from catalogue" }

    override suspend fun execute(args: ToolArgs, context: ToolContext): ToolResult {
        if (context.signal.isCancelled()) {
            return ToolResult(
                success = false,
                observation = "Cancelled before the photos were searched.",
                error = ToolError.Cancelled("cancelled before photos.search"),
            )
        }
        val query = when (val parsed = ArgCoerce.string(args, "query")) {
            is ArgResult.Present -> parsed.value.replace(Regex("\\s+"), " ").trim().take(PHOTO_MAX_QUERY_CHARS)
            is ArgResult.Missing -> return invalid("query is required: what the photo shows.")
            is ArgResult.Unusable -> return invalid("query must be a string, but was ${parsed.got}.")
        }
        if (query.isEmpty()) return invalid("query is empty: say what the photo shows.")
        val limit = when (val parsed = ArgCoerce.int(args, "max_results")) {
            is ArgResult.Present -> ArgCoerce.clamp(parsed.value, 1, PHOTO_MAX_RESULTS)
            else -> PHOTO_DEFAULT_RESULTS
        }

        // Captions would leave the phone inside this observation if a remote model is the one answering.
        if (cloudChatActive()) {
            return ToolResult(
                success = false,
                observation = "Photo search is off while a remote model answers, because captions would " +
                    "leave the phone. Use the phone's own model to search photos.",
                error = ToolError.Unavailable("remote model active"),
            )
        }
        val search = index() ?: return ToolResult(
            success = false,
            observation = "Photo search is not available in this build.",
            error = ToolError.Unavailable("photo search not present"),
        )
        // Cleared before the search, so a search that fails or finds nothing shows no old thumbnails.
        PhotoSearchHits.latest = emptyList()
        val found = try {
            if (search.count() == 0) {
                return ToolResult(
                    success = true,
                    observation = "No photos are indexed yet. Tell the user to open Settings, then Photo search, " +
                        "and tap Index my photos.",
                )
            }
            search.search(query, limit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return ToolResult(
                success = false,
                observation = "photos.search could not read the photo index. Try again later.",
                error = ToolError.Unavailable(e::class.java.simpleName),
            )
        }

        if (found.isEmpty()) {
            return ToolResult(
                success = true,
                observation = "No indexed photo matches \"$query\". Say so, and do not guess what the photos show.",
            )
        }
        PhotoSearchHits.latest = found.map { it.uri }
        return ToolResult(
            success = true,
            observation = "Indexed photos for \"$query\", best match first (captions only). " +
                "The user sees these photos as thumbnails under your answer:\n" +
                found.joinToString("\n") { "- ${it.caption.replace(Regex("\\s+"), " ").trim()}" },
        )
    }

    private fun invalid(reason: String) = ToolResult(
        success = false,
        observation = reason,
        error = ToolError.InvalidArguments(reason),
    )
}
