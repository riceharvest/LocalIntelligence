package dev.localintelligence.inference.litertlm

import dev.localintelligence.core.model.ModelSpec
import java.io.File
import java.io.IOException

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
        }
        return candidate
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
