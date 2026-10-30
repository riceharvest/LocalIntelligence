package dev.localintelligence.android.inference

import java.io.File

/**
 * An embedding GGUF resident in its own native handle, next to the chat model.
 *
 * Vectors come back unit length, so cosine similarity is a dot product. The
 * [fileName] is kept because the document index stores it with each set of vectors:
 * vectors from a different model are not comparable and must be recomputed.
 *
 * ## WHY THE PROMPTS ARE CHOSEN BY FILE NAME
 *
 * EmbeddingGemma was trained with task prefixes, and its quality drops without them.
 * Other embedding models were not, so the prefixes are applied only to the EmbeddingGemma
 * family, identified by name. The file name is the only identity this app has for a model.
 */
class EmbeddingModel private constructor(
    val fileName: String,
    private val handle: LlamaBridge.Handle,
    val dimension: Int,
) : AutoCloseable {

    /** The prompt format from the google/embeddinggemma-2 model card (Web / document search). */
    private val gemma: Boolean = fileName.contains("embeddinggemma", ignoreCase = true)

    /**
     * Vectors for document passages, in input order. Null when embedding fails.
     * [title] is the document's name: the card asks for `title: {title} | text: ...`
     * and `title: none` only when there is no title.
     */
    @Synchronized
    fun embedPassages(passages: List<String>, title: String? = null): List<FloatArray>? {
        val head = if (gemma) "title: ${title?.takeIf { it.isNotBlank() } ?: "none"} | text: " else ""
        return embed(passages.map { head + it })
    }

    /** The vector for a search query. Null when embedding fails. */
    @Synchronized
    fun embedQuery(query: String): FloatArray? =
        embed(listOf((if (gemma) "task: search result | query: " else "") + query))?.firstOrNull()

    private fun embed(texts: List<String>): List<FloatArray>? {
        if (texts.isEmpty()) return emptyList()
        val flat = LlamaBridge.embed(handle, texts) ?: return null
        if (flat.size != texts.size * dimension) return null
        return List(texts.size) { i -> flat.copyOfRange(i * dimension, (i + 1) * dimension) }
    }

    override fun close() = handle.close()

    companion object {
        /**
         * Loads [file] into a new handle. Null when the native library is absent or the
         * file is not an embedding model. The handle is closed on every failure path.
         */
        fun load(file: File, threads: Int = 0): EmbeddingModel? {
            val handle = LlamaBridge.create() ?: return null
            if (LlamaBridge.loadEmbeddingModel(handle, file.absolutePath, threads) != null) {
                handle.close()
                return null
            }
            val dimension = LlamaBridge.embeddingSize(handle)
            if (dimension <= 0) {
                handle.close()
                return null
            }
            return EmbeddingModel(file.name, handle, dimension)
        }
    }
}
