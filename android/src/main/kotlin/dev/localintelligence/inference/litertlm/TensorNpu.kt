package dev.localintelligence.inference.litertlm

import android.os.Build
import dev.localintelligence.core.model.AcceleratorKind
import java.io.File

/**
 * The Google Tensor NPU: which generation this phone has, and which model files
 * were compiled for it.
 *
 * The NPU runs only models compiled ahead of time for one Tensor generation
 * (`gemma-4-E2B-it_Google_Tensor_G5.litertlm`). Such a file is NPU bytecode: no
 * CPU or GPU can run it, and no other Tensor generation can either. That is why
 * the generation is matched exactly and why a compiled file never falls back.
 *
 * The gate mirrors the one Off Grid ran on a Pixel 10 Pro (off-grid-ai/OGAM#702),
 * which in turn mirrors LiteRT's own NPU check.
 */
internal object TensorNpu {
    /** LiteRT's Tensor dispatch library, packaged from litert-npu-runtime-google-tensor. */
    const val DISPATCH_LIB = "libLiteRtDispatch_GoogleTensor.so"

    /** The Pixel vendor library the dispatch library opens (a `System.loadLibrary` name). */
    const val VENDOR_LIB = "edgetpu_litert"

    /** LiteRT supports the Tensor NPU from Android 16. */
    const val MIN_SDK = 36

    /**
     * The context window compiled into Google's Tensor builds. The KV cache is
     * fixed in the file, and LiteRT-LM refuses a turn once the history passes
     * about 3968 tokens, so a larger request is clamped rather than passed on.
     */
    const val COMPILED_CONTEXT = 4096

    /** An Android 16 build family that shipped without NPU support; LiteRT rejects it too. */
    private const val UNSUPPORTED_BUILD_PREFIX = "BP2A"

    private val SOC_MODEL = Regex("""^tensor\s*g(\d+)$""")
    private val COMPILED_FOR = Regex("""_google_tensor_g(\d+)""")

    /**
     * Tensor generation (5 for a Pixel 10's "Tensor G5"), or null when this is not
     * a Google Tensor chip. Tensor G3 and later Pixels report SOC_MODEL
     * "Tensor G<n>" with SOC_MANUFACTURER "Google"; device codenames are not used,
     * because other vendors reuse them.
     */
    fun generation(socModel: String?, socManufacturer: String?): Int? {
        if (!socManufacturer.equals("Google", ignoreCase = true)) return null
        return SOC_MODEL.find(socModel.orEmpty().trim().lowercase())
            ?.groupValues?.get(1)?.toIntOrNull()
    }

    /** This phone's Tensor generation. `Build.SOC_MODEL` exists from API 31. */
    fun deviceGeneration(): Int? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            generation(Build.SOC_MODEL, Build.SOC_MANUFACTURER)
        } else {
            null
        }

    /** The Tensor generation a model file was compiled for, read from its name; null for any other file. */
    fun compiledFor(fileName: String): Int? =
        COMPILED_FOR.find(fileName.lowercase())?.groupValues?.get(1)?.toIntOrNull()

    /** Why this phone cannot use the Tensor NPU, or null when it can. */
    fun unsupportedReason(
        generation: Int?,
        sdkInt: Int,
        buildId: String,
        vendorLibLoads: Boolean,
    ): String? = when {
        generation == null -> "this phone has no Google Tensor chip"
        sdkInt < MIN_SDK -> "the Tensor NPU needs Android 16 or later (this is API $sdkInt)"
        buildId.startsWith(UNSUPPORTED_BUILD_PREFIX) ->
            "Android build $buildId shipped without NPU support; update the phone"
        !vendorLibLoads -> "the Pixel NPU library lib$VENDOR_LIB.so could not be loaded"
        else -> null
    }
}

/**
 * Remembers engine loads that never returned.
 *
 * A broken NPU or GPU runtime can abort the process (SIGABRT, SIGSEGV) instead of
 * throwing, and the saved model loads at app start. Without this, one bad
 * runtime is a crash on every launch. A marker is written before the engine is
 * built and removed when the build returns, whether it succeeded or threw; only
 * a process that died mid-load leaves it behind. After [LIMIT] such deaths in a
 * row that accelerator is skipped. A force-close or a low-memory kill during a
 * load counts too, which is why one is not enough.
 *
 * Kept in the cache dir, so clearing the app's cache resets it.
 */
internal class InitCrashGuard(private val dir: String?) {

    fun crashedRepeatedly(kind: AcceleratorKind): Boolean = count(kind) >= LIMIT

    fun <T> around(kind: AcceleratorKind, block: () -> T): T {
        write(kind, count(kind) + 1)
        try {
            return block()
        } finally {
            write(kind, 0)
        }
    }

    private fun file(kind: AcceleratorKind): File? =
        dir?.let { File(it, "litertlm-init-${kind.name.lowercase()}.pending") }

    private fun count(kind: AcceleratorKind): Int =
        runCatching { file(kind)?.takeIf { it.isFile }?.readText()?.trim()?.toInt() }
            .getOrNull() ?: 0

    private fun write(kind: AcceleratorKind, value: Int) {
        val f = file(kind) ?: return
        runCatching { if (value == 0) f.delete() else f.writeText(value.toString()) }
    }

    companion object {
        const val LIMIT = 2
    }
}
