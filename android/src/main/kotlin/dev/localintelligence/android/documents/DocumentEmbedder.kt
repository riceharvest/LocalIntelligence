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
class DocumentEmbedder(private val modelsDir: File, private val setting: () -> String) {

    private var loaded: EmbeddingModel? = null
    private var failedName: String? = null

    /** The embedding model, or null when the file is missing, blank or unloadable. */
    @Synchronized
    fun get(): EmbeddingModel? {
        val file = resolve(modelsDir, setting()) ?: return null
        loaded?.let { if (it.fileName == file.name) return it }
        if (failedName == file.name) return null
        loaded?.close()
        loaded = null
        val model = EmbeddingModel.load(file)
        loaded = model
        failedName = if (model == null) file.name else null
        return model
    }

    companion object {
        /**
         * The file in [modelsDir] that [setting] names, or null. Only the file name is used,
         * so a setting cannot point outside the models folder.
         *
         * The hub saves a download as `owner__repo__file` (see `GgufQuant.localFileName`),
         * so a name also matches a file that ends with `__<name>`. Without that the hub
         * download of the default model would never be found.
         */
        fun resolve(modelsDir: File, setting: String): File? {
            val name = File(setting.trim()).name
            if (name.isEmpty()) return null
            val exact = File(modelsDir, name)
            if (exact.isFile) return exact
            return modelsDir.listFiles()?.firstOrNull { it.isFile && it.name.endsWith("__$name") }
        }
    }
}
