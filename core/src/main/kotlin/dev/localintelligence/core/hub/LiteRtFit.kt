package dev.localintelligence.core.hub

/**
 * Whether a LiteRT-LM file in a repository is worth offering on this phone.
 *
 * - [Hidden]: this app has no backend that can run the file, so it is not listed
 *   unless the user asks for every file.
 * - [Offered]: listed, not recommended.
 * - [Recommended]: the one file the Hub suggests for this phone.
 */
enum class LiteRtFit { Hidden, Offered, Recommended }

/**
 * The fit of each LiteRT-LM file name in one repository, aligned with [fileNames].
 *
 * WHY the name is read here and not the file's bytes: the Hub lists a repository
 * before any file is downloaded, and the names are the only thing the repository
 * publishes about what each build is for. Google's naming is stable: a
 * `_Google_Tensor_G<n>` build runs only on that Tensor generation, `_NPU_Uncompiled`
 * is raw NPU input, `-web` builds are for the browser, and the Qualcomm, Intel and
 * MediaTek builds are NPU bytecode for other vendors. This app has no backend for any
 * of those, so they are [LiteRtFit.Hidden].
 *
 * [deviceGeneration] is this phone's Tensor generation (5 for a Pixel 10), or null
 * when it has no Tensor chip. With null, every Tensor build is hidden.
 *
 * The recommendation is the Tensor build for this generation; when there is none,
 * the plain generic `<name>.litertlm`; when there is neither, nothing is recommended.
 * A `-gpu` build is offered but never recommended in its place: it is a variant, and
 * the generic build is the one that runs on every phone.
 */
fun liteRtFits(fileNames: List<String>, deviceGeneration: Int?): List<LiteRtFit> {
    val kinds = fileNames.map { classify(it.lowercase(), deviceGeneration) }
    val recommended = kinds.indexOf(Kind.TensorMatch).takeIf { it >= 0 }
        ?: kinds.indexOf(Kind.Generic).takeIf { it >= 0 }
    return kinds.mapIndexed { index, kind ->
        when {
            kind == Kind.Hidden -> LiteRtFit.Hidden
            index == recommended -> LiteRtFit.Recommended
            else -> LiteRtFit.Offered
        }
    }
}

private enum class Kind { Hidden, TensorMatch, Generic, Other }

// Each pattern ends on a token boundary, so `intel` does not match inside `intelligence`.
private val WEB_BUILD = Regex("""[-_]web(?=[_.]|$)""")
private val UNCOMPILED = Regex("""_npu_uncompiled(?=[_.]|$)""")
private val OTHER_VENDOR_NPU = Regex("""_(qualcomm|intel|mediatek|sm\d+|mt\d+)(?=[_.]|$)""")
private val TENSOR_BUILD = Regex("""_google_tensor_g(\d+)(?=[_.]|$)""")

private fun classify(lower: String, deviceGeneration: Int?): Kind {
    if (WEB_BUILD.containsMatchIn(lower) || UNCOMPILED.containsMatchIn(lower) || OTHER_VENDOR_NPU.containsMatchIn(lower)) {
        return Kind.Hidden
    }
    val compiledFor = TENSOR_BUILD.find(lower)?.groupValues?.get(1)?.toIntOrNull()
    if (compiledFor != null) return if (compiledFor == deviceGeneration) Kind.TensorMatch else Kind.Hidden
    // A plain generic build has no variant marker: no underscore suffix, and not the -gpu variant.
    if (lower.endsWith(".litertlm") && !lower.contains('_') && !lower.endsWith("-gpu.litertlm")) return Kind.Generic
    return Kind.Other
}
