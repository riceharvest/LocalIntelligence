package dev.localintelligence.android.documents

import dev.localintelligence.android.inference.EmbeddingModel
import java.io.File

/**
 * Keeps the document embedding model resident once something needs it, and swaps it
 * when the "Document search model" setting names another file.
 *
 * WHY LAZY: nothing loads until a document exists and is added or searched. A user who
 * never adds a document never pays for the model's memory.
 *
 * A file that fails to load is remembered by name, so a broken GGUF is not reread on
 * every search. Changing the setting clears that memory.
 */
class DocumentEmbedder(private val modelsDir: File, private val fileName: () -> String) {

    private var loaded: EmbeddingModel? = null
    private var failedName: String? = null

    /** The embedding model, or null when the file is missing, blank or unloadable. */
    @Synchronized
    fun get(): EmbeddingModel? {
        // Only the file name is used: a setting cannot point outside the models folder.
        val name = File(fileName().trim()).name
        loaded?.let { if (it.fileName == name) return it }
        if (failedName == name) return null
        loaded?.close()
        loaded = null
        if (name.isEmpty()) return null
        val file = File(modelsDir, name)
        if (!file.isFile) return null
        val model = EmbeddingModel.load(file)
        loaded = model
        failedName = if (model == null) name else null
        return model
    }
}
