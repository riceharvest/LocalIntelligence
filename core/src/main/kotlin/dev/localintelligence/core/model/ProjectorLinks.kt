package dev.localintelligence.core.model

/**
 * The vision files (mmproj) a person linked to models by hand, kept in one setting row
 * ([dev.localintelligence.core.settings.CoreSettings.MODEL_PROJECTORS]) as text: one line per model,
 * the model's id, a tab, the projector's file path.
 *
 * WHY TEXT IN A SETTING AND NOT A TABLE: the link is a small fact about a model, and rule 2 allows no
 * private store. A model id is a URI and a path has no tab or newline, so the two separators cannot
 * appear inside a value; a line that does not split in two is ignored.
 */
object ProjectorLinks {
    private const val SEP = '\t'

    /** model id to projector path. A later line for the same model wins. */
    fun parse(raw: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (line in raw.lineSequence()) {
            val parts = line.split(SEP)
            if (parts.size != 2 || parts[0].isBlank() || parts[1].isBlank()) continue
            out[parts[0]] = parts[1]
        }
        return out
    }

    /** The text for [links]. */
    fun format(links: Map<String, String>): String =
        links.entries.joinToString("\n") { "${it.key}$SEP${it.value}" }

    /** [raw] with [modelId] linked to [path], or unlinked when [path] is null. */
    fun with(raw: String, modelId: String, path: String?): String {
        val links = LinkedHashMap(parse(raw))
        if (path == null) links.remove(modelId) else links[modelId] = path
        return format(links)
    }

    /** True for a file name that is a vision file, not a model (`mmproj-X.gguf`, `X-mmproj.gguf`). */
    fun isProjectorName(fileName: String): Boolean =
        fileName.contains("mmproj", ignoreCase = true)
}
