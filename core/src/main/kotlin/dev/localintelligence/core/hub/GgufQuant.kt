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
 * the parse is a closed enum rather than a string, because a typo'd or unknown
 * quant has to be a *refusal to estimate*, not a silent default that is wrong
 * in the direction that gets a phone OOM-killed.
 *
 * ## [bitsPerWeight]: measured, not published
 *
 * [bitsPerWeight] is the average number of bits one weight occupies in a real
 * file of that quant, and it is what turns a file size into a parameter count.
 * It used to be a "published effective figure" copied from convention, and for
 * the formats people actually download it was wrong in both directions:
 *
 * ```
 * label     was      measured        n     block-layout
 * Q4_1     4.5000      -            0        5.0000   <- the old value was the
 * Q5_1     5.5000      -            0        6.0000      wrong block, 32/20 vs
 * Q6_K     6.5900   6.5639           9        6.5625      32/24 bytes
 * Q4_K_M   4.8300   5.0090           9          n/a (mixed)
 * Q5_K_M   5.9300   5.8499           9          n/a (mixed)
 * Q2_K     2.6300   3.4051           7        2.6250   <- 29% low, the worst
 * Q8_0     8.5000   8.5013           9        8.5000
 * F16     16.0000  16.0009           3       16.0000
 * F32     32.0000  32.0000           4       32.0000
 * ```
 *
 * Every "measured" figure is `8 * weightBytes / parameterCount` computed from
 * the GGUF tensor table of real files, fetched by byte-range from
 * huggingface.co: 160 files across 9 architectures, every quant those repos
 * publish. The command, the per-file numbers and the full table are in
 * `docs/memory-model.md`. A figure with no sample (`n = 0`) is the
 * block-layout value and is marked as such in the entry below.
 *
 * ## Why a label's bits-per-weight is not a constant, and why that is not a
 * problem for the fit check
 *
 * A `_M`, `_S` or `_L` file is a *blend*: `Q4_K_M` stores most tensors as Q4_K
 * and promotes some to Q6_K, and how many depends on the model. The vocabulary
 * embedding is the tensor that moves the figure most, because it is a large
 * fraction of a small model's parameters (30% of gemma-3-1b's 1.0B) and
 * converters frequently keep it in a wider type. The measured spread across
 * repos for the same label reaches 1.61x (Q2_K, 2.96 to 5.47) and 1.43x
 * (IQ1_S).
 *
 * That spread does not make the estimate useless, because the *weights* term
 * of the fit check is the file size, which is exact and independent of
 * [bitsPerWeight]. The figure is used to recover a *parameter count* from a
 * file size when the file name does not declare one, and there an error of
 * 1.6x in the wrong direction is still 100x better than the previous
 * behaviour, which assumed every unnamed file was a 7B model.
 * [bitsPerWeightMax] is the measured maximum, and callers that want the
 * conservative end of the derivation have it.
 *
 * [rank] orders quality for auto-selection: higher is better and larger.
 */
enum class GgufQuant(
    val label: String,
    /**
     * Average bits per weight, from real files of this quant. See the class
     * KDoc for the sample sizes and the exact measurement.
     */
    val bitsPerWeight: Double,
    /**
     * The largest bits-per-weight measured for this label, or [bitsPerWeight]
     * when no larger sample exists.
     *
     * WHY it exists: a fit gate that guesses a parameter count too high charges
     * a model for a KV cache it will not allocate, and a user told "no" for a
     * model that loads stops trusting the gate. Callers that need a bound
     * rather than a point use this.
     */
    val bitsPerWeightMax: Double,
    /** How many real files the [bitsPerWeight] figure is the median of. */
    val measuredSamples: Int,
    /** Higher is better quality and larger. Used to pick the best that fits. */
    val rank: Int,
) {
    /** n = 2. Block layouts: IQ1_S 50 B/256, IQ1_M 56 B/256. */
    IQ1_S("IQ1_S", 3.0824, 4.4044, 2, 5),
    IQ1_M("IQ1_M", 3.1726, 4.4267, 2, 8),

    /** n = 2 / 1 / 1 / 4. Block layouts: 66, 74, 82 B/256. */
    IQ2_XXS("IQ2_XXS", 3.3231, 4.4641, 2, 11),
    IQ2_XS("IQ2_XS", 2.4125, 2.4125, 1, 14),
    IQ2_S("IQ2_S", 2.5438, 2.5438, 1, 17),
    IQ2_M("IQ2_M", 2.9349, 4.5725, 4, 20),

    /** n = 2 / 3 / 1 / 5. Block layouts: 98, 110, 110 B/256. */
    IQ3_XXS("IQ3_XXS", 3.9238, 4.6815, 2, 23),
    IQ3_XS("IQ3_XS", 3.4977, 3.5919, 3, 26),
    IQ3_S("IQ3_S", 3.5195, 3.5195, 1, 29),
    IQ3_M("IQ3_M", 3.8834, 4.2459, 5, 32),

    /**
     * n = 7. Block layout for the Q2_K block itself is 84 B/256 = 2.625, which
     * is *not* what the file costs: the shipped "Q2_K" presets store 58-87% of
     * their tensors as Q3_K. The measured 3.4051 is the number to use.
     */
    Q2_K("Q2_K", 3.4051, 5.4669, 7, 35),
    Q2_K_L("Q2_K_L", 3.6704, 5.4669, 3, 38),

    /** n = 1. Single sample (gemma-3-1b), which widens the figure with its
     *  Q8_0 embedding. Treated as a bound, not a typical value. */
    Q2_K_XL("Q2_K_XL", 5.4992, 5.4992, 1, 41),

    Q3_K_S("Q3_K_S", 3.6345, 5.4592, 6, 44),
    Q3_K_M("Q3_K_M", 4.0925, 5.7277, 7, 47),
    Q3_K_L("Q3_K_L", 4.3692, 4.7259, 7, 50),
    Q3_K_XL("Q3_K_XL", 4.9295, 5.7631, 4, 53),

    /** n = 6. Block layout Q4_0 is 18 B/32 = 4.5. */
    Q4_0("Q4_0", 4.8387, 5.7237, 6, 56),

    /**
     * n = 3. Repacked Q4_0 variants: 4_4 is 4.5 bits (18 B/32), 4_8 is 5.0
     * (20 B/32), 8_8 is 6.0 (24 B/32), matching the measurements.
     */
    Q4_0_4_4("Q4_0_4_4", 4.7093, 4.9399, 3, 58),
    Q4_0_4_8("Q4_0_4_8", 5.1589, 5.3336, 3, 60),
    Q4_0_8_8("Q4_0_8_8", 6.0580, 6.1210, 3, 62),

    /**
     * n = 0, so this is the block layout: `block_q4_1` is 20 bytes per 32
     * elements = 5.0 bits, not the 4.5 this entry used to carry. 4.5 is
     * `block_q4_0`, a different format with no minimum and a different error
     * profile. No released file of this quant was available to measure.
     */
    Q4_1("Q4_1", 5.0, 5.0, 0, 64),

    /** n = 6. Block layout IQ4_XS is 136 B/256 = 4.25. */
    IQ4_XS("IQ4_XS", 4.6261, 5.6639, 6, 66),

    /** n = 3. Block layout IQ4_NL is 18 B/32 = 4.5, same size as Q4_0. */
    IQ4_NL("IQ4_NL", 4.6526, 5.7233, 3, 68),

    Q4_K_S("Q4_K_S", 4.7041, 6.1964, 8, 70),

    /** n = 9. The most-shipped quant in the sample; 4.83 was 3.7% low. */
    Q4_K_M("Q4_K_M", 5.0090, 6.3969, 9, 72),

    /**
     * n = 3. These three were missing from this enum entirely, which is not a
     * cosmetic gap: [HuggingFaceClient.chooseBestFitting] sorts candidates by
     * `rank` and an unparsed name sorts as rank 0, so a repo offering Q4_K_L
     * next to Q8_0 could never have its Q4_K_L selected — the picker was
     * structurally blind to every quant label added after Q6_K.
     */
    Q4_K_L("Q4_K_L", 5.2828, 5.5897, 3, 74),
    Q4_K_XL("Q4_K_XL", 6.4047, 6.4047, 1, 76),

    /** n = 3. Block layout Q5_0 is 22 B/32 = 5.5. */
    Q5_0("Q5_0", 5.5655, 6.1500, 3, 78),

    /**
     * n = 0, block layout: `block_q5_1` is 24 bytes per 32 elements = 6.0 bits.
     * This entry previously carried 5.5, which is `block_q5_0`.
     */
    Q5_1("Q5_1", 6.0, 6.0, 0, 80),

    Q5_K_S("Q5_K_S", 5.5894, 6.6397, 8, 82),
    Q5_K_M("Q5_K_M", 5.8499, 6.7593, 9, 84),
    Q5_K_L("Q5_K_L", 6.0266, 6.2617, 3, 86),
    Q5_K_XL("Q5_K_XL", 6.9429, 6.9429, 1, 88),

    /** n = 9. Block layout 210 B/256 = 6.5625; the 6.59 here before was neither. */
    Q6_K("Q6_K", 6.5639, 8.1800, 9, 90),
    Q6_K_L("Q6_K_L", 6.8963, 7.0020, 4, 92),
    Q6_K_XL("Q6_K_XL", 8.1969, 8.1969, 1, 94),

    /** n = 9. Block layout 34 B/32 = 8.5. Measured 8.5013: 0.015% out. */
    Q8_0("Q8_0", 8.5013, 8.5032, 9, 96),
    Q8_K_XL("Q8_K_XL", 11.7771, 11.7771, 1, 97),

    /** n = 3 / 1. 2 bytes per element, unquantised. */
    F16("F16", 16.0009, 16.0011, 3, 100),
    BF16("BF16", 16.0021, 16.0021, 1, 100),
    F32("F32", 32.0, 32.0, 4, 110),
    ;

    /** True when the value came from real files rather than from a block size. */
    val isMeasured: Boolean get() = measuredSamples > 0

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
            "(?:^|[^A-Za-z0-9])((?:BF|F|IQ|Q)\\d(?:_[A-Z0-9]+)*)\\.gguf$",
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

            // Longest match wins so a prefix cannot shadow a longer real quant:
            // `Q4_0_4_4` must not be read as `Q4_0`, and `IQ2_M` must not be
            // read as `Q2_M`. Both are decided here, not by the pattern above.
            var best: GgufQuant? = null
            var bestLength = 0
            for (candidate in entries) {
                val token = candidate.label.uppercase()
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
