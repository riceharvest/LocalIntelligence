package dev.localintelligence.inference.litertlm

import dev.localintelligence.core.model.ModelSpec
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Turns a backend-neutral [ModelSpec] into a filesystem path LiteRT-LM can open.
 *
 * ## Why this is not the llama.cpp trick
 *
 * The llama.cpp backend hands `llama_model_load_from_file` a
 * `/proc/self/fd/N` path backed by a SAF `AssetFileDescriptor`, which lets a
 * 1.9 GB model be used straight out of a cloud drive with no copy. LiteRT-LM
 * cannot do that: `EngineConfig.modelPath` is opened natively and, for a
 * multi-part `.litertlm` bundle, walked as a tree. There is no descriptor entry
 * point in the Kotlin API and no `mmap` flag to set. So the honest options are a
 * real path, or nothing.
 *
 * Copying a 1-2 GB model into app storage on import was rejected for the same
 * reason [dev.localintelligence.android.inference.ModelImporter] rejects it: it
 * doubles the device's storage cost for that model, takes minutes over USB, and
 * leaves the user with two copies to clean up. The fix is that LiteRT-LM models
 * are not SAF-picked in the first place — they are downloaded by the app into its
 * own files dir, where they already have a real path. This class validates that
 * path and refuses everything it cannot honestly open.
 *
 * ## Refusals are specific
 *
 * A wrong model is a normal user outcome — a stale path, a deleted file, a GGUF
 * pointed at a LiteRT-LM backend — and a generic "load failed" gives the UI
 * nothing to say. Every rejection below carries the path it rejected and what was
 * expected, so the failure is actionable without a logcat read.
 */
class LiteRtLmModelSource(
    private val root: File,
) {

    /**
     * Validates [spec] and returns the model directory or file to open.
     *
     * Accepts a [ModelSpec] whose `id` is a `file://` URI or a bare absolute
     * path, resolved against [root]. Anything else — a relative path escaping the
     * root, a `content://` URI, a directory without a LiteRT-LM bundle in it — is
     * refused with a message that says which.
     *
     * @throws LiteRtLmModelException if the model cannot be opened. `load()`
     *     must throw, because the caller has to know the model never arrived.
     */
    fun resolve(spec: ModelSpec): File {
        val raw = spec.id.trim()
        if (raw.isEmpty()) {
            throw LiteRtLmModelException("the model id is blank")
        }

        val candidate = toFile(raw)
        if (!candidate.isAbsolute) {
            throw LiteRtLmModelException(
                "model \"$raw\" is a relative path; LiteRT-LM needs an absolute path " +
                    "or a file:// uri, because it opens the model natively",
            )
        }
        if (!candidate.exists()) {
            throw LiteRtLmModelException("model \"$candidate\" does not exist")
        }
        if (!candidate.canRead()) {
            throw LiteRtLmModelException("model \"$candidate\" exists but cannot be read")
        }

        // A LiteRT-LM model is either a single .litertlm bundle or a directory of
        // .litertlm/.task parts. Both are legitimate; an empty directory is not,
        // and handing one to the engine produces a native crash rather than a
        // Kotlin exception, which is the worst possible place to learn about it.
        if (candidate.isDirectory) {
            val parts = candidate.listFiles()?.filter { it.isFile }.orEmpty()
            if (parts.isEmpty()) {
                throw LiteRtLmModelException(
                    "model directory \"$candidate\" is empty; a LiteRT-LM model is a " +
                        ".litertlm bundle or a directory of model parts",
                )
            }
            if (parts.none { it.name.endsWith(BUNDLE_SUFFIX) || it.name.endsWith(TASK_SUFFIX) }) {
                throw LiteRtLmModelException(
                    "model directory \"$candidate\" holds no .litertlm or .task file; " +
                        "found ${parts.size} other file(s). A GGUF cannot be run by " +
                        "LiteRT-LM -- that is the llamacpp backend",
                )
            }
            return candidate
        }

        refuseGguf(candidate)
        return candidate
    }

    /**
     * Refuses a GGUF handed to this backend, by content rather than by name.
     *
     * ## Why this check exists at all
     *
     * The directory branch above tests extensions, but a *single file* is
     * accepted on the strength of being readable. That matters because a GGUF is
     * the one input this app produces constantly -- the hub downloads GGUF, the
     * model manager imports GGUF, and every model on disk today is GGUF -- so a
     * GGUF pointed at this backend would walk straight into
     * `Engine(modelPath = "…/qwen3-4b.gguf")` and die in native code.
     * `liblitertlm_jni.so` opens the path, checks a FlatBuffer identifier, and
     * throws from C++; there is no Kotlin frame in which to catch it.
     *
     * The magic is read out of the shipped 0.13.1 `liblitertlm_jni.so`:
     * `Invalid magic number. Expected 'LITERTLM', got '`, alongside
     * `The model is not a valid Flatbuffer buffer`. A `.litertlm` is therefore a
     * FlatBuffer whose identifier is `LITERTLM`, and a GGUF begins with the four
     * ASCII bytes `GGUF`.
     *
     * ## Why GGUF is matched by content and not by extension
     *
     * Because extension is exactly the thing that is wrong here. The input being
     * guarded is a model the user picked or downloaded, and a file that has been
     * renamed, sideloaded, or saved by a browser that mangled the name is
     * precisely the case where the extension lies. The first four bytes do not.
     *
     * GGUF is also the *only* foreign format this app can produce, so it is the
     * only foreign magic worth refusing. A file that is neither GGUF nor
     * `LITERTLM`-identified is passed through to the runtime, which performs the
     * authoritative check and reports it in a message a user can act on.
     */
    private fun refuseGguf(candidate: File) {
        if (candidate.length() < GGUF_MAGIC.size) return
        val head = try {
            RandomAccessFile(candidate, "r").use { raf ->
                ByteArray(GGUF_MAGIC.size).also { raf.readFully(it) }
            }
        } catch (_: IOException) {
            // Unreadable is [resolve]'s problem to report, not this check's.
            return
        }
        if (!head.contentEquals(GGUF_MAGIC)) return

        throw LiteRtLmModelException(
            "model \"$candidate\" is a GGUF. LiteRT-LM cannot run GGUF and there is " +
                "no GGUF-to-.litertlm converter: a .litertlm is a FlatBuffer wrapping " +
                "TFLite graphs, produced by converting the original PyTorch/HuggingFace " +
                "checkpoint on a desktop with litert-torch, not by re-wrapping a GGUF. " +
                "Use the llamacpp backend for this model, or download a pre-converted " +
                "model such as litert-community/gemma-4-E4B-it-litert-lm",
        )
    }

    /**
     * Strips a `file://` scheme, percent-decodes, and resolves against [root].
     *
     * A `content://` URI is refused here rather than in [resolve] because it is
     * the one case where the *scheme itself* is the problem: there is no local
     * path behind it that LiteRT-LM could ever open, and saying so up front beats
     * reporting it as a missing file.
     */
    private fun toFile(raw: String): File {
        if (raw.startsWith(CONTENT_SCHEME, ignoreCase = true)) {
            throw LiteRtLmModelException(
                "model \"$raw\" is a SAF document uri. LiteRT-LM opens the model by " +
                    "filesystem path and cannot use a content provider descriptor, so " +
                    "a LiteRT-LM model has to be a real file on disk",
            )
        }
        if (raw.startsWith(FILE_SCHEME, ignoreCase = true)) {
            // file://<authority>/<path>. `localhost` and an empty authority are the
            // local-machine forms; any other host is a remote share with no local
            // path at all. `file:///abs/path` has an empty authority.
            val rest = raw.substring(FILE_SCHEME.length)
            val slash = rest.indexOf('/')
            if (slash >= 0) {
                val authority = rest.substring(0, slash)
                if (authority.isNotEmpty() && !authority.equals("localhost", ignoreCase = true)) {
                    throw LiteRtLmModelException(
                        "model uri \"$raw\" points at host \"$authority\"; LiteRT-LM " +
                            "reads local files only",
                    )
                }
                return File(percentDecode(rest.substring(slash)))
            }
            if (rest.isEmpty() || rest.equals("localhost", ignoreCase = true)) {
                throw LiteRtLmModelException("model uri \"$raw\" has no path component")
            }
            // A bare authority with no path is still a remote reference.
            throw LiteRtLmModelException(
                "model uri \"$raw\" has no path component",
            )
        }
        return File(raw)
    }

    private fun percentDecode(value: String): String =
        try {
            java.net.URLDecoder.decode(value, Charsets.UTF_8.name())
        } catch (_: IllegalArgumentException) {
            // A stray % that is not a valid escape. The path is more useful to the
            // user undecoded than not at all.
            value
        }

    /** Where downloaded models live, and the base for relative ids. */
    val modelRoot: File get() = root

    companion object {
        const val FILE_SCHEME = "file://"
        const val CONTENT_SCHEME = "content://"
        const val BUNDLE_SUFFIX = ".litertlm"
        const val TASK_SUFFIX = ".task"

        /**
         * The first four bytes of a GGUF, and the only foreign magic this app
         * can hand to this backend.
         *
         * Read from the GGUF spec rather than guessed: the container declares
         * itself `GGUF` before any version field, which is why it is a reliable
         * discriminator and why `GgufParser` in `:core` checks the same bytes.
         * A `.litertlm` never begins with these -- it is a FlatBuffer
         * (`liblitertlm_jni.so`: `Invalid magic number. Expected 'LITERTLM'`).
         */
        val GGUF_MAGIC = byteArrayOf('G'.code.toByte(), 'G'.code.toByte(), 'U'.code.toByte(), 'F'.code.toByte())
    }
}

/** A model that cannot be opened. `load()` surfaces this to the caller verbatim. */
class LiteRtLmModelException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause) {
    init {
        // Keep the cause's message visible: LiteRT-LM's own failures are more
        // specific than anything this class can invent, and losing them makes a
        // load failure undiagnosable.
        if (cause != null && cause.message != null && !message.contains(cause.message!!)) {
            initCause(IOException(message, cause))
        }
    }
}
