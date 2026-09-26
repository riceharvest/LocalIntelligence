package dev.localintelligence.inference.litertlm

import android.content.Context
import dev.localintelligence.android.inference.LlamaCppBackend
import dev.localintelligence.android.inference.ModelImporter
import dev.localintelligence.core.model.AcceleratorPreference
import dev.localintelligence.core.model.ModelBackend
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Picks the `ModelBackend` that can actually run a given model file.
 *
 * ## Why this exists
 *
 * `AppContainer` constructs one backend and never chooses between two:
 *
 * ```kotlin
 * val modelBackend: ModelBackend by lazy { LlamaCppBackend(importer) }
 * ```
 *
 * So `LiteRtLmBackend` was complete, compiled into the APK, and unreachable —
 * a second runtime that no user could ever select. This class is the missing
 * decision: it routes a model file to the backend whose format it is in.
 *
 * It lives in `:android` rather than in `AppContainer` because the choice is a
 * *format* decision, and `:core` is forbidden from knowing any runtime's types.
 * `AppContainer` needs one line to call it (see the KDoc on [forContext]).
 *
 * ## Why format sniffing, not a setting
 *
 * The obvious design is a user-facing "which backend" toggle. That is wrong
 * here, for a reason worth stating: **a GGUF cannot be run by LiteRT-LM and a
 * `.litertlm` cannot be run by llama.cpp.** They are not two ways to run one
 * model; they are two different weight formats, and the file on disk already
 * contains the answer. A toggle would let the user pick an impossible pairing,
 * and the failure would surface as a native crash in `liblitertlm_jni.so` or a
 * llama.cpp "unknown model format" — neither of which is actionable.
 *
 * So the format decides, the first four bytes decide it, and the user's only
 * real choice — *which hardware* — is [acceleratorPreference], which is passed
 * through to whichever backend is selected.
 *
 * ## The two formats
 *
 * - **GGUF** — the container llama.cpp reads. What this app's hub downloads and
 *   its model manager imports, so in practice this is every model a user has.
 * - **`.litertlm`** — a FlatBuffer wrapping TFLite graphs, tokenizer and
 *   metadata. Read by `liblitertlm_jni.so`, which checks a FlatBuffer
 *   identifier (`Invalid magic number. Expected 'LITERTLM'`) and throws from
 *   C++ when it is wrong.
 *
 * There is no converter between them, in this app or in Google's. See
 * [refusal] for what that means and what the user is told.
 */
class ModelBackendRouter(
    private val importer: ModelImporter,
    /** Where downloaded models live; the root for LiteRT-LM model ids. */
    private val modelsDir: File,
    /**
     * `context.applicationInfo.nativeLibraryDir` — where a vendor NPU delegate
     * would live. Null disables the NPU tier with a stated reason rather than
     * constructing `Backend.NPU("")`.
     */
    private val nativeLibraryDir: String?,
    /**
     * Which hardware the user asked for, handed to the LiteRT-LM backend.
     *
     * Only LiteRT-LM takes one: llama.cpp's backend is CPU-only, so there is
     * nothing for it to apply. A user who pins `GPU` and loads a GGUF therefore
     * gets CPU, and the only way to know that is that the *model* is a GGUF —
     * which is precisely the distinction [route] exists to make visible.
     */
    private val acceleratorPreference: AcceleratorPreference = AcceleratorPreference.DEFAULT,
    /** Passed to the LiteRT-LM engine so compiled state has somewhere to live. */
    private val cacheDir: String? = null,
    /**
     * How much work the LiteRT-LM CPU backend may use. 0 means "let the runtime
     * choose", which is what `Backend.CPU` documents and what its own strings
     * call "kDefault" — it is not "zero threads".
     */
    private val numThreads: Int = 0,
) {

    /**
     * Forces a backend regardless of the file's content.
     *
     * A deliberate override for a benchmark that needs to compare runtimes on
     * one model, and the escape hatch that lets a caller surface the
     * "impossible pairing" as a *Kotlin* error rather than discovering it in
     * native code. Null — the default — means decide from the bytes.
     */
    enum class Override(val backendId: String?) {
        /** Route by file content. The default. */
        AUTO(null),
        LLAVA_CPP(ModelImporter.BACKEND_LLAMA_CPP),
        LITERTLM(LiteRtLmBackend.BACKEND_LITERTLM),
    }

    /**
     * The backend for [file], decided by its first bytes.
     *
     * @throws BackendRoutingException if the file is neither GGUF nor a LiteRT-LM
     *     container. That is a real user outcome — a `.bin` safetensors file, a
     *     half-finished download, a TensorFlow Lite `.tflite` — so it is named
     *     rather than guessed at.
     */
    fun route(file: File, override: Override = Override.AUTO): ModelBackend =
        when (override) {
            Override.LLAVA_CPP -> llamaBackend()
            Override.LITERTLM -> liteRtLmBackend()
            Override.AUTO -> when (sniff(file)) {
                Container.GGUF -> llamaBackend()
                Container.LITERTLM -> liteRtLmBackend()
                null -> throw BackendRoutingException(refusal(file))
            }
        }

    private fun llamaBackend(): ModelBackend = LlamaCppBackend(importer)

    /**
     * A LiteRT-LM backend wired to a **real device probe**.
     *
     * This is the second reachability bug, and it is the quieter one. The
     * backend's `capabilityProbe` parameter defaults to `CpuOnlyAcceleratorProbe`
     * and its `nativeLibraryDir` defaults to null — both correct for a JVM
     * harness, both wrong for a phone. Any caller that did not remember to pass
     * `LiteRtLmCapabilityProbe(...)` got a backend that reports every accelerator
     * unavailable and runs on CPU, with the honest-but-useless reason "no device
     * probe was supplied". It would look like a slow phone rather than a
     * misconfigured constructor.
     *
     * Constructing the backend in exactly one place, with both supplied, is what
     * makes that impossible.
     */
    private fun liteRtLmBackend(): ModelBackend = LiteRtLmBackend(
        modelSource = LiteRtLmModelSource(modelsDir),
        capabilityProbe = LiteRtLmCapabilityProbe(nativeLibraryDir),
        nativeLibraryDir = nativeLibraryDir,
        acceleratorPreference = acceleratorPreference,
        cacheDir = cacheDir,
        numThreads = numThreads,
    )

    /**
     * Identifies the container from its first bytes.
     *
     * GGUF is matched at offset 0, which is certain: the GGUF spec puts its
     * four ASCII magic bytes before the version field, which is why
     * `GgufParser` in `:core` checks the same position.
     *
     * `LITERTLM` is matched anywhere in the first 8 bytes rather than at a
     * hard-coded offset. A FlatBuffer header is a 4-byte root offset followed
     * by an optional 4-byte file identifier, and `liblitertlm_jni.so` reports
     * the failure as `Invalid magic number. Expected 'LITERTLM', got '`. Reading
     * a window instead of one fixed position costs nothing and does not depend
     * on an alignment detail that was not verified here. A false negative would
     * mean a real `.litertlm` is reported as unrecognised — recoverable and
     * safe, since the runtime would still open it.
     *
     * Null means "not one of ours", never "empty file": an unreadable or empty
     * file is [route]'s problem to report with a better message than a format
     * verdict it cannot justify.
     */
    private fun sniff(file: File): Container? {
        if (!file.isFile || file.length() < MAGIC_LEN) return null
        val head = try {
            RandomAccessFile(file, "r").use { raf ->
                ByteArray(MAGIC_LEN).also { raf.readFully(it) }
            }
        } catch (_: IOException) {
            return null
        }
        return when {
            head.startsWith(GGUF_MAGIC) -> Container.GGUF
            head.contains(LITERTLM_MAGIC) -> Container.LITERTLM
            else -> null
        }
    }

    /**
     * What the user is told when a file is neither format.
     *
     * The GGUF half matters more than it looks. A user who downloads a GGUF and
     * asks for LiteRT-LM will hit this, and the honest answer is that **no
     * conversion exists** — a `.litertlm` wraps TFLite graphs produced from the
     * original PyTorch/HuggingFace checkpoint, so the GGUF they hold is
     * downstream of a step that cannot be reversed. Saying "convert it" would
     * be inventing a path that does not exist.
     */
    private fun refusal(file: File): String {
        val exists = when {
            !file.exists() -> "it does not exist"
            file.isDirectory -> "it is a directory"
            file.length() == 0L -> "it is empty, so the download did not finish"
            else -> "its first bytes are neither GGUF nor a LiteRT-LM container"
        }
        return "cannot run \"$file\": $exists. This app runs two formats and " +
            "converts between neither: a GGUF is llama.cpp's format, and a " +
            ".litertlm is a FlatBuffer of TFLite graphs built from the original " +
            "PyTorch/HuggingFace checkpoint. There is no GGUF-to-.litertlm " +
            "converter, so a GGUF cannot become a .litertlm. Use the llama.cpp " +
            "backend, or download a pre-converted model such as " +
            "litert-community/gemma-4-E4B-it-litert-lm"
    }

    private enum class Container { GGUF, LITERTLM }

    companion object {
        /**
         * How much of the file is read to identify it: 16 bytes.
         *
         * Both magics are ASCII and the longer one is 8 bytes, so a 16-byte
         * window is what lets the LITERTLM check use `contains` safely. A
         * FlatBuffer is a 4-byte root offset optionally followed by a 4-byte
         * identifier, so the identifier sits at offset 0 or offset 4 — the
         * offset-4 form needs 12 bytes to contain the full magic, which is why
         * an earlier 8-byte window silently failed on every .litertlm bundle
         * that carries a root offset, which is most of them.
         *
         * Order matters and is deliberate: GGUF is tested first, so a file whose
         * later bytes happen to spell LITERTLM inside its version/count header
         * still routes as GGUF rather than being misrouted to LiteRT-LM.
         */
        private const val MAGIC_LEN = 16

        private val GGUF_MAGIC = "GGUF".toByteArray(Charsets.US_ASCII)

        /**
         * From the shipped `liblitertlm_jni.so` (0.13.1):
         * `Invalid magic number. Expected 'LITERTLM', got '`.
         */
        private val LITERTLM_MAGIC = "LITERTLM".toByteArray(Charsets.US_ASCII)

        /**
         * A router wired to a live [Context].
         *
         * The one line `AppContainer` needs:
         *
         * ```kotlin
         * val modelBackend: ModelBackend by lazy {
         *     ModelBackendRouter.forContext(context, importer).route(model)
         * }
         * ```
         *
         * Built here rather than at the call site because the two device facts
         * it needs — `nativeLibraryDir` and the models directory — both come
         * from `Context`, and getting either wrong produces a backend that
         * loads and runs slower than it should rather than an error.
         */
        fun forContext(
            context: Context,
            importer: ModelImporter,
            /** Accelerator preference, e.g. from a user setting. */
            acceleratorPreference: AcceleratorPreference = AcceleratorPreference.DEFAULT,
        ): ModelBackendRouter = ModelBackendRouter(
            importer = importer,
            modelsDir = File(context.filesDir, MODELS_SUBDIR).apply { mkdirs() },
            nativeLibraryDir = context.applicationInfo.nativeLibraryDir,
            acceleratorPreference = acceleratorPreference,
            cacheDir = File(context.cacheDir, CACHE_SUBDIR).absolutePath,
        )
    }
}

/** No runtime can open this file. Named so the UI can say which one and why. */
class BackendRoutingException(message: String) : RuntimeException(message)

private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
    if (size < prefix.size) return false
    for (i in prefix.indices) if (this[i] != prefix[i]) return false
    return true
}

/** Whether [needle] appears anywhere in this buffer. See [ModelBackendRouter.sniff]. */
private fun ByteArray.contains(needle: ByteArray): Boolean {
    if (needle.isEmpty() || size < needle.size) return false
    for (start in 0..(size - needle.size)) {
        var match = true
        for (i in needle.indices) if (this[start + i] != needle[i]) { match = false; break }
        if (match) return true
    }
    return false
}

/** Matches `AppContainer.modelsDir`, so both backends see the same directory. */
private const val MODELS_SUBDIR = "models"
private const val CACHE_SUBDIR = "litertlm-cache"
