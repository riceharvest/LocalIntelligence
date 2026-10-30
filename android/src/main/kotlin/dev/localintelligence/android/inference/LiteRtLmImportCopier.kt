package dev.localintelligence.android.inference

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Copies a LiteRT-LM (`.litertlm`) document picked from Files into the app's own
 * models directory, so LiteRT-LM can open it by path.
 *
 * ## Why a copy, when GGUF is never copied
 *
 * GGUF is memory-mapped through a SAF descriptor, so a 2 GB model is used where it
 * sits. LiteRT-LM has no descriptor entry point (see
 * `dev.localintelligence.inference.litertlm.LiteRtLmModelSource`), so the only way
 * to run a picked bundle is a real file. The copy costs the file's size in app
 * storage, which is why it is announced with progress and a free-space check
 * before a byte is written, rather than done silently.
 *
 * ## Shape of the write
 *
 * Bytes go to `<name>.part` and are renamed into place only when the copy is
 * complete, the same rule the hub downloader keeps. A cancelled or failed copy
 * leaves no file that LiteRT-LM could be pointed at half-written.
 */
class LiteRtLmImportCopier(
    private val contentResolver: ContentResolver,
    private val modelsDir: File,
) {

    /**
     * Copies [uri] into the models directory as [displayName] (sanitised, and given
     * the `.litertlm` suffix if it lacks one) and returns the copy.
     *
     * [onProgress] receives `(copiedBytes, totalBytes)`; `totalBytes` is -1 when the
     * provider does not report a size. It is called from the copy thread.
     *
     * @throws IOException with a sentence for the user when there is not enough
     *   space or the document cannot be read.
     */
    suspend fun copy(
        uri: Uri,
        displayName: String,
        onProgress: (copied: Long, total: Long) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        modelsDir.mkdirs()
        val name = safeName(displayName)
        val dest = File(modelsDir, name)
        val partial = File(modelsDir, "$name.part")
        val total = sizeOf(uri)

        // Refuse before the first byte when the size is known. The margin keeps
        // the rest of the app (the database, the downloads, the logs) writable.
        if (total > 0 && modelsDir.usableSpace < total + SPACE_MARGIN_BYTES) {
            throw IOException(
                "Not enough free space for \"$name\": it is ${formatGb(total)} and this phone has " +
                    "${formatGb(modelsDir.usableSpace)} free in app storage. Free some space and try again.",
            )
        }

        val input = contentResolver.openInputStream(uri)
            ?: throw IOException("\"$name\" could not be opened from Files.")
        try {
            input.use { source ->
                partial.outputStream().use { sink ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    var copied = 0L
                    var sinceCheck = 0L
                    onProgress(0L, total)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = source.read(buffer)
                        if (n < 0) break
                        sink.write(buffer, 0, n)
                        copied += n
                        sinceCheck += n
                        if (sinceCheck >= CHECK_EVERY_BYTES) {
                            sinceCheck = 0L
                            // A provider can be larger than its reported size, or the
                            // phone can fill up mid-copy. Stop before the disk is full.
                            if (modelsDir.usableSpace < SPACE_MARGIN_BYTES) {
                                throw IOException(
                                    "Ran out of space while copying \"$name\" after ${formatGb(copied)}. " +
                                        "Free some space and try again.",
                                )
                            }
                            onProgress(copied, total)
                        }
                    }
                    sink.fd.sync()
                    onProgress(copied, if (total > 0) total else copied)
                }
            }
            dest.delete()
            if (!partial.renameTo(dest)) {
                throw IOException("The copy of \"$name\" finished but could not be moved into place.")
            }
            dest
        } catch (e: IOException) {
            partial.delete()
            throw e
        } catch (e: RuntimeException) {
            partial.delete()
            throw e
        }
    }

    /** The provider's size, or -1 when it will not say. */
    private fun sizeOf(uri: Uri): Long {
        var cursor: android.database.Cursor? = null
        return try {
            cursor = contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            if (cursor != null && cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index >= 0 && !cursor.isNull(index)) cursor.getLong(index) else -1L
            } else {
                -1L
            }
        } catch (_: Exception) {
            // A provider may reject the projection. Unknown size is a supported state.
            -1L
        } finally {
            cursor?.close()
        }
    }

    companion object {
        /** Free space kept back after the copy, so the rest of the app can still write. */
        const val SPACE_MARGIN_BYTES: Long = 256L * 1024 * 1024

        /** How often the free space is re-checked and progress is reported, in bytes. */
        private const val CHECK_EVERY_BYTES: Long = 8L * 1024 * 1024

        private const val BUFFER_BYTES = 1024 * 1024

        private const val SUFFIX = ".litertlm"

        /**
         * A file name that is safe under the models directory: no path separators,
         * no control characters, and the LiteRT-LM suffix. The name is what the
         * model list shows, so a readable name is kept rather than replaced.
         */
        internal fun safeName(displayName: String): String {
            val cleaned = displayName.trim()
                .replace(Regex("[^A-Za-z0-9._ -]+"), "_")
                .trim('.', ' ', '_')
                .take(120)
                .ifEmpty { "model" }
            return if (cleaned.endsWith(SUFFIX, ignoreCase = true)) cleaned else cleaned + SUFFIX
        }

        private fun formatGb(bytes: Long): String = "%.1f GB".format(bytes / 1_000_000_000.0)
    }
}
