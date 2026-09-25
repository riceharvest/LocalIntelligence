package dev.localintelligence.core.hub

/**
 * A GGUF file that exists in a repo, with everything needed to choose it
 * *before* committing bandwidth.
 *
 * [sizeBytes] and [sha256] come from HF's `?blobs=true` sibling listing, which
 * reports the LFS pointer's declared size and OID. That is the whole point of
 * this type existing: the user is shown a number and a fit verdict *before* the
 * first byte moves, because a 2 GB download that was never going to fit is the
 * most expensive mistake this app can let a user make on mobile data.
 */
data class HubGgufFile(
    val repo: HubRepoId,
    val path: HubFilePath,
    val sizeBytes: Long,
    /** HF LFS OID, lowercase hex. Null for a non-LFS file or when unlisted. */
    val sha256: String?,
    /** Parsed out of the file name. Null when the name carries no recognisable quant. */
    val quant: GgufQuant?,
    /** Shard ordinal, 1-based, when this is part of a split file. */
    val shardIndex: Int?,
    /** Shard count, when the name declares one. */
    val shardCount: Int?,
) {
    val fileName: String get() = path.fileName

    /** True when this file needs its siblings downloaded to be loadable. */
    val isSharded: Boolean get() = shardCount != null && shardCount > 1

    /** Stable local name: `owner__name__path_with_slashes_replaced.gguf`. */
    fun localFileName(): String {
        val flat = path.segments.joinToString("__")
        return "${repo.owner}__${repo.name}__$flat"
    }
}

/**
 * A quantization level recovered from a GGUF file name.
 *
 * ## Why the file name, and why an enum
 *
 * The quant suffix is the only place the information exists before the bytes
 * arrive: the HF API reports a name and a size, not a tensor table. A user
 * browsing a repo sees `Q4_K_M`, and the whole product decision — "will this
 * fit in RAM" — turns on telling Q4_K_M from Q8_0. So the name is parsed, and
 * the parse is a closed enum rather than a string, because the fit check
 * multiplies a file size by a bits-per-weight figure and a typo'd or unknown
 * quant has to be a *refusal to estimate*, not a silent default that is wrong
 * by 2x in the dangerous direction.
 *
 * [bitsPerWeight] is the published effective figure including the K-quant
 * superblock scales. It is what makes `Q4_K_M.gguf` -> `Q8_0.gguf` -> a RAM
 * estimate without opening either file. [rank] orders quality for auto-selection.
 */
enum class GgufQuant(
    val label: String,
    val bitsPerWeight: Double,
    /** Higher is better quality and larger. Used to pick the best that fits. */
    val rank: Int,
) {
    Q2_K("Q2_K", 2.63, 10),
    Q3_K_S("Q3_K_S", 3.31, 20),
    Q3_K_M("Q3_K_M", 3.61, 25),
    Q3_K_L("Q3_K_L", 3.91, 30),
    Q4_0("Q4_0", 4.5, 40),
    Q4_1("Q4_1", 4.5, 41),
    IQ4_XS("IQ4_XS", 4.25, 42),
    Q4_K_S("Q4_K_S", 4.58, 43),
    Q4_K_M("Q4_K_M", 4.83, 44),
    Q5_0("Q5_0", 5.5, 50),
    Q5_1("Q5_1", 5.5, 51),
    Q5_K_S("Q5_K_S", 5.69, 52),
    Q5_K_M("Q5_K_M", 5.93, 53),
    Q6_K("Q6_K", 6.59, 60),
    Q8_0("Q8_0", 8.5, 70),
    F16("F16", 16.0, 80),
    BF16("BF16", 16.0, 80),
    F32("F32", 32.0, 90),
    ;

    companion object {
        /**
         * Matches the quant token inside a file name.
         *
         * WHY anchored on digit boundaries: `Q4_K_M` must not be found inside
         * `IQ4_XS` (a different, finer format), and `Q8_0` must not match a
         * hypothetical `Q80_K`. The leading `(?:IQ|Q|F|BF)` plus the
         * digit-or-end boundary handles both.
         *
         * Ordered longest-token-first at match time by preferring the longest
         * candidate that matches at the same position, so `Q4_K_M` wins over
         * `Q4_K` (which is not in the enum anyway) and `BF16` over `F16`.
         */
        private val PATTERN = Regex(
            "(?:^|[^A-Za-z0-9])((?:BF|F|IQ|Q)\\d(?:_[A-Z])*_?(?:K|XS|S|M|L|KM|KS)?(?:_\\d)?)\\.gguf$",
            RegexOption.IGNORE_CASE,
        )

        /**
         * Extracts the quant from a file name, case-insensitively.
         *
         * Falls back to a looser scan when the strict pattern misses, because
         * repos really do contain names like `model.Q4_K_M.GGUF`,
         * `qwen2.5-0.5b-instruct-q4_k_m.gguf` and
         * `Mistral-7B-Instruct-v0.2.Q4_K_S.gguf`, and refusing to estimate for
         * the single most popular quant in the ecosystem would be worse than a
         * slightly looser match.
         */
        fun fromFileName(fileName: String): GgufQuant? {
            val upper = fileName.uppercase()
            if (!upper.endsWith(".GGUF")) return null

            // Longest match wins so a prefix cannot shadow a longer real quant.
            var best: GgufQuant? = null
            var bestLength = 0
            for (candidate in entries) {
                val token = candidate.label.uppercase()
                if (candidate === F32 && token == "F32") continue
                if (containsQuantToken(upper, token) && token.length > bestLength) {
                    best = candidate
                    bestLength = token.length
                }
            }
            return best
        }

        /**
         * True when [token] appears in [upper] as a standalone quant token.
         *
         * The boundaries are what keep `IQ4_XS` from being read as `Q4_XS` and
         * a model called `Falcon-180B` from being read as containing `Q8`.
         */
        private fun containsQuantToken(upper: String, token: String): Boolean {
            var from = 0
            while (true) {
                val at = upper.indexOf(token, from)
                if (at < 0) return false
                val before = if (at == 0) ' ' else upper[at - 1]
                val afterIndex = at + token.length
                val after = if (afterIndex >= upper.length) ' ' else upper[afterIndex]
                if (!before.isLetterOrDigit() && !after.isLetterOrDigit()) return true
                from = at + 1
            }
        }
    }
}

/**
 * Reads the `-00001-of-00003` shard marker out of a file name.
 *
 * WHY sharded files need explicit handling: `model-00001-of-00003.gguf` is not
 * a loadable model, it is one third of one. Treating it as a complete model
 * produces a download that succeeds and then fails in `llama_model_load_from_file`
 * with no indication which of the three files is missing, so [HubGgufFile.isSharded]
 * exists and the picker refuses to offer a shard set as a single-file model.
 *
 * Returns `null` for both values when the marker is absent, which is the
 * single-file case and by far the common one.
 */
internal fun parseShard(fileName: String): Pair<Int?, Int?> {
    val match = Regex("-0*(\\d+)-of-0*(\\d+)", RegexOption.IGNORE_CASE).find(fileName) ?: return null to null
    val index = match.groupValues[1].toIntOrNull() ?: return null to null
    val total = match.groupValues[2].toIntOrNull() ?: return null to null
    if (total < 1 || index < 1 || index > total) return null to null
    return index to total
}
