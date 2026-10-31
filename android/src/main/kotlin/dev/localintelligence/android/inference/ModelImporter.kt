package dev.localintelligence.android.inference

import android.content.ContentResolver
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import dev.localintelligence.android.hub.AndroidDeviceBudget
import dev.localintelligence.core.model.gguf.GgufByteSource
import dev.localintelligence.core.model.gguf.GgufHeader
import dev.localintelligence.core.model.gguf.GgufParser
import dev.localintelligence.core.model.gguf.GgufValue
import dev.localintelligence.core.model.gguf.GgufWarning
import dev.localintelligence.core.model.gguf.KvCacheType
import dev.localintelligence.core.model.gguf.MemoryEstimate
import dev.localintelligence.core.model.gguf.ModelMemoryEstimator
import dev.localintelligence.inference.litertlm.LiteRtLmBackend
import dev.localintelligence.inference.litertlm.LiteRtLmContainer
import java.io.FileNotFoundException

/**
 * Brings a user-picked GGUF file into the app as a *loadable reference*, and
 * decides up front whether the device can hold it.
 *
 * ## fd, not copy
 *
 * A 3B Q4_K_M is ~1.9 GB. Copying it into app storage on import would double
 * the device's storage cost, take minutes over USB, and leave the user with two
 * copies to delete. So the importer keeps the SAF `AssetFileDescriptor` open and
 * hands the raw `FileDescriptor` to the native loader, which memory-maps it.
 *
 * The catch, and it is a real one: `/proc/self/fd/N` is the only way to hand a
 * descriptor to llama.cpp, which takes a *path*, not a descriptor. That path is
 * only usable for the lifetime of the descriptor, so [LoadedModel] must be
 * closed after the model is unloaded. `unload()` failing to close it is a
 * descriptor leak, and on a SAF provider a leaked descriptor can pin the whole
 * document open. See [LoadedModel].
 *
 * mmap over a SAF descriptor is also not universally available — FUSE-backed
 * providers (most cloud drives) do not implement `mmap`, and `llama.cpp`'s
 * mmap path will fail on them. [LoadedModel.isMmapCapable] reports what the
 * platform said, so the backend can fall back to a read-into-RAM load rather
 * than pretending.
 *
 * ## There is exactly one GGUF parser, and it is not here
 *
 * The header is parsed by `dev.localintelligence.core.model.gguf.GgufParser`.
 * This file used to carry a second, independent implementation of the same
 * binary format; it was deleted, and the reasons are recorded in
 * `docs/architecture.md`. Briefly, the two disagreed about the one number the
 * product leads with:
 *
 *  * the deleted parser read the tensor table only on request, and by default
 *    it did not — so its weights figure was the *file size*, which for a real
 *    Q4_K_M includes a 5.13 MiB header and is not the weight section;
 *  * it derived the weights from `params * 4.83 / 8` whenever the file length
 *    was unavailable, which is the normal case for a stream-backed SAF
 *    provider, and which collapses to **0 bytes** for any model whose header
 *    carries no `general.parameter_count` — reporting a 1,056 MiB model as
 *    352 MiB, in the direction that says "it fits";
 *  * it hard-coded a 4.83 bits/weight figure for *every* quantisation, so a
 *    BF16 model's fallback estimate was wrong by 3.4x;
 *  * its `general.file_type` label table is misaligned with llama.cpp's from id
 *    22 upward, so a real IQ4_XS model (id 30) displayed as `UNKNOWN_30` and
 *    BF16 (id 32) as `UNKNOWN_32`.
 *
 * All four were measured against the same real files; see the table in
 * `docs/architecture.md`. None of them are reachable now. This module keeps
 * only what is genuinely Android-specific: the descriptor, the content URI, and
 * the device-budget arithmetic that has no JVM equivalent.
 */
class ModelImporter(
    private val contentResolver: ContentResolver,
    /**
     * How the KV cache is priced for a model whose header has been read: the decision made by
     * `ContextLength.decide`. Evaluated on every estimate so a model listed before a setting
     * change is priced the way it will load.
     */
    private val kvCacheType: (GgufHeader) -> KvCacheType = { KvCacheType.DEFAULT },
) {

    /**
     * Reads the header of [uri] and reports what it is and what it will cost.
     * Does not load the model, does not copy the file, does not keep the
     * descriptor open.
     */
    fun inspect(uri: Uri, contextLength: Int = DEFAULT_CONTEXT_LENGTH): ImportedModel {
        val afd = openAsset(uri)
        try {
            val size = lengthOf(afd, uri)
            // A LiteRT-LM file has no GGUF header to parse, so it is recognised
            // by its identifier and described from its size alone. The GGUF
            // branch below is unchanged and still the default.
            if (isLiteRtLm(uri)) return describeLiteRtLm(uri, size)
            afd.createInputStream().use { input ->
                val header = readHeader(input, size)
                return describe(header, uri, size, contextLength)
            }
        } finally {
            afd.close()
        }
    }

    /**
     * Opens [uri] for loading and keeps the descriptor alive. The caller owns
     * the result and must close it once the model is unloaded.
     */
    fun openForLoad(uri: Uri): LoadedModel {
        val afd = openAsset(uri)
        val size = lengthOf(afd, uri)
        val header = try {
            // WHY NOT `afd.createInputStream().use { }`: closing that stream
            // closes the AssetFileDescriptor underneath it, and THIS descriptor
            // has to stay open for the life of the model, because nativePath()
            // hands `/proc/self/fd/N` to llama_model_load_from_file. The `use`
            // closed it, and every load then died with
            //   java.lang.IllegalStateException: Already closed
            //   at LoadedModel.nativePath(ModelImporter.kt:194)
            // Read the header from a SEPARATE descriptor so the surviving one is
            // never handed to a closing stream.
            val header = openAsset(uri)
            try {
                readHeader(header.createInputStream(), size)
            } finally {
                header.close()
            }
        } catch (e: Throwable) {
            afd.close()
            throw e
        }
        return LoadedModel(afd, header, uri)
    }

    /**
     * The one place this module turns bytes into a header.
     *
     * WHY `size` is handed through: [GgufByteSource.ofStream] buffers a bounded
     * prefix, and a buffer length standing in for a file length makes
     * `GgufHeader.fileBytes` wrong. That field decides the `FILE_SIZE` weights
     * fallback and the "this file is truncated" warning, so passing the real
     * length when the platform gave us one is load-bearing, not tidiness.
     */
    private fun readHeader(input: java.io.InputStream, size: Long): GgufHeader =
        GgufParser.parse(
            GgufByteSource.ofStream(input, declaredSizeBytes = size),
        )

    /** Turns a parsed header into the record the model manager shows. */
    fun describe(
        header: GgufHeader,
        uri: Uri,
        fileSizeBytes: Long,
        contextLength: Int = DEFAULT_CONTEXT_LENGTH,
    ): ImportedModel {
        val estimate = RamEstimate.from(header, contextLength) { kvCacheType(header) }
        return ImportedModel(
            uri = uri,
            displayName = header.metadata.name ?: displayNameFromUri(uri),
            fileSizeBytes = fileSizeBytes,
            architecture = header.metadata.architecture,
            quantType = header.dominantQuantType?.label ?: header.metadata.fileType?.label,
            trainedContextLength = header.metadata.contextLength?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt(),
            parameterCount = header.tensorTableParameterCount ?: header.metadata.declaredParameterCount,
            hasChatTemplate = header.hasChatTemplate(),
            supportedBackends = setOf(BACKEND_LLAMA_CPP),
            estimate = estimate,
        )
    }

    /**
     * True when [uri] starts with the LiteRT-LM identifier.
     *
     * Reads through its own descriptor, as [openForLoad] does, so the caller's
     * descriptor is never handed to a stream that closes it.
     */
    private fun isLiteRtLm(uri: Uri): Boolean {
        val head = openAsset(uri).use { afd ->
            afd.createInputStream().use { input ->
                val buffer = ByteArray(LiteRtLmContainer.SNIFF_BYTES)
                var read = 0
                while (read < buffer.size) {
                    val n = input.read(buffer, read, buffer.size - read)
                    if (n < 0) break
                    read += n
                }
                buffer.copyOf(read)
            }
        }
        return LiteRtLmContainer.matches(head)
    }

    /**
     * Describes a LiteRT-LM file without parsing it.
     *
     * WHY no header: the KV-cache geometry and the tensor table that a GGUF
     * estimate needs are not in a `.litertlm` header this app can read, so the
     * only fact used for memory is the file size. [LiteRtLmFootprint] says so.
     *
     * WHY the name is the only source of quant and display name: the file carries
     * neither in a form this app reads, so a name that says `int4` is used and
     * anything else is left unknown rather than guessed.
     */
    private fun describeLiteRtLm(uri: Uri, fileSizeBytes: Long): ImportedModel {
        // A size of -1 means the provider would not say. Without a size there is
        // no weight figure, and a made-up one would tell the load gate the file
        // fits. Refusing the import is the safe outcome.
        check(fileSizeBytes > 0) { "the size of this LiteRT-LM file is not known, so it cannot be priced" }
        val fileName = displayNameFromUri(uri)
        return ImportedModel(
            uri = uri,
            displayName = fileName.substringBeforeLast('.').ifBlank { fileName },
            fileSizeBytes = fileSizeBytes,
            architecture = LITERTLM_ARCHITECTURE,
            quantType = quantFromName(fileName),
            trainedContextLength = null,
            parameterCount = null,
            hasChatTemplate = true,
            supportedBackends = setOf(LiteRtLmBackend.BACKEND_LITERTLM),
            estimate = LiteRtLmFootprint(fileSizeBytes),
        )
    }

    private fun quantFromName(fileName: String): String? =
        QUANT_IN_NAME.find(fileName)?.value?.uppercase()

    private fun GgufHeader.hasChatTemplate(): Boolean =
        metadata.fields["tokenizer.chat_template"]?.let {
            (it as? GgufValue.Text)?.value?.isNotBlank() == true
        } == true

    private fun openAsset(uri: Uri): AssetFileDescriptor =
        contentResolver.openAssetFileDescriptor(uri, "r")
            ?: throw FileNotFoundException("ContentResolver returned no descriptor for $uri")

    /**
     * The real byte length. `getLength()` is the *remaining* length from the
     * descriptor's start offset, and is -1 for stream-backed providers, so fall
     * back to `getDeclaredLength()` and then to a ContentResolver query.
     */
    private fun lengthOf(afd: AssetFileDescriptor, uri: Uri): Long {
        val remaining = afd.length
        if (remaining >= 0) return remaining
        val declared = afd.declaredLength
        if (declared >= 0) return declared
        return querySize(uri) ?: -1L
    }

    private fun querySize(uri: Uri): Long? {
        var cursor: Cursor? = null
        return try {
            cursor = contentResolver.query(uri, null, null, null, null)
            if (cursor != null && cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (idx >= 0 && !cursor.isNull(idx)) cursor.getLong(idx) else null
            } else {
                null
            }
        } catch (_: Exception) {
            // A provider is allowed to reject the projection. Unknown length is
            // a supported state, not a failure — and with the core parser it is
            // now a *degraded* state rather than a wrong one, because the tensor
            // table still yields the exact weight size.
            null
        } finally {
            cursor?.close()
        }
    }

    private fun displayNameFromUri(uri: Uri): String =
        uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "model.gguf"

    companion object {
        const val BACKEND_LLAMA_CPP = "llamacpp"
        const val DEFAULT_CONTEXT_LENGTH = 4096

        /** The architecture label for a LiteRT-LM file; there is no GGUF architecture key to read. */
        const val LITERTLM_ARCHITECTURE = "litertlm"

        /** A quant tag that appears as a whole word in the file name, e.g. `-int4.`. */
        private val QUANT_IN_NAME = Regex("""(?i)(?<![a-z0-9])(int4|int8|fp16|bf16|fp32)(?![a-z0-9])""")
    }
}

/**
 * A zero-logic view of the two header facts the llama.cpp backend branches on.
 *
 * WHY this exists at all: `LlamaCppBackend.deriveCapabilities` is typed against a
 * class named `GgufMetadata` in this package, and that file is owned by another
 * agent, so its signature cannot be changed from here. Rather than keep a 700-line
 * second parser alive to satisfy a type name, this is a projection: it holds a
 * core [GgufHeader] and forwards. It parses nothing, retains nothing, and cannot
 * disagree with the parser, because it has no arithmetic of its own.
 *
 * It should be deleted the moment `LlamaCppBackend` is next edited to take a
 * `GgufHeader` directly. That is a two-line change on that side.
 */
class GgufMetadata internal constructor(private val header: GgufHeader) {
    /** Trained context length, saturating at `Int.MAX_VALUE` for the native loader's ABI. */
    val contextLength: Int?
        get() = header.metadata.contextLength?.let {
            if (it > Int.MAX_VALUE) Int.MAX_VALUE else it.toInt()
        }

    /** True when the file ships a chat template, which tool calling depends on. */
    val hasChatTemplate: Boolean
        get() = (header.metadata.fields["tokenizer.chat_template"] as? GgufValue.Text)
            ?.value?.isNotBlank() == true
}

/** What the model manager needs to show one row, without loading anything. */
data class ImportedModel(
    val uri: Uri,
    val displayName: String,
    val fileSizeBytes: Long,
    val architecture: String?,
    val quantType: String?,
    val trainedContextLength: Int?,
    val parameterCount: Long?,
    val hasChatTemplate: Boolean,
    val supportedBackends: Set<String>,
    val estimate: ModelFootprint,
) {
    /** True when this device has enough headroom to load it at the given context. */
    fun fitsOnDevice(contextLength: Int = ModelImporter.DEFAULT_CONTEXT_LENGTH): Boolean =
        estimate.totalBytes(contextLength) <= RamEstimate.usableDeviceBytes()
}

/**
 * An open model file. Holds the SAF descriptor for as long as the native model
 * is resident, because `/proc/self/fd/N` stops resolving when it closes.
 *
 * Close exactly once, after `llama_model_free`. Leaking one keeps the backing
 * document pinned in the provider and counts against the per-process fd limit.
 */
class LoadedModel internal constructor(
    private val afd: AssetFileDescriptor,
    /** The authoritative header. There is no second `GgufMetadata` any more. */
    val header: GgufHeader,
    val uri: Uri,
) : AutoCloseable {

    /**
     * The two header facts the llama.cpp backend reads, projected for its
     * existing signature. Zero logic; see [GgufMetadata].
     */
    val metadata: GgufMetadata get() = GgufMetadata(header)

    /**
     * The trained context length, as an `Int` for the native loader.
     *
     * WHY a narrowing conversion rather than a second parser field: the loader
     * takes an `Int`, and a model declaring a context larger than `Int.MAX_VALUE`
     * is not loadable on this ABI regardless. Saturating is the honest answer and
     * keeps the clamp in one place.
     */
    val contextLength: Int?
        get() = header.metadata.contextLength?.let {
            if (it > Int.MAX_VALUE) Int.MAX_VALUE else it.toInt()
        }

    /** Whether the file's own metadata says it ships a chat template. */
    val hasChatTemplate: Boolean
        get() = (header.metadata.fields["tokenizer.chat_template"] as? GgufValue.Text)
            ?.value?.isNotBlank() == true

    /**
     * Whether the backing storage can plausibly be `mmap`-ed. FUSE-backed SAF
     * providers (most cloud drives) do not implement mmap, and llama.cpp cannot
     * memory-map a model from one.
     *
     * This is a *hint*, not a guarantee: no platform API reports mmap
     * support for a descriptor, and FUSE descriptors present as perfectly valid.
     * The native loader therefore still has to handle a failing mmap, and the
     * backend falls back to a read-into-RAM load on [LlamaBridge.LoadResult].
     */
    val isMmapCapable: Boolean by lazy {
        try {
            val fd = afd.fileDescriptor
            // An invalid or non-seekable descriptor cannot be mapped, and a
            // zero-length one has nothing to map.
            fd.valid() && afd.declaredLength != 0L && afd.startOffset >= 0
        } catch (_: Exception) {
            false
        }
    }

    /**
     * The path to hand `llama_model_load_from_file`. Valid only while this
     * [LoadedModel] is open.
     */
    fun nativePath(): String = "/proc/self/fd/${afd.parcelFileDescriptor.fd}"

    /** Byte length, or -1 when the provider would not say. */
    val length: Long get() = afd.declaredLength

    override fun close() {
        afd.close()
    }
}

/**
 * What a model is expected to cost in native memory, whatever its format.
 *
 * Two implementations exist, and they are not equally strong. [RamEstimate] is
 * computed from a GGUF tensor table and KV geometry. [LiteRtLmFootprint] has only
 * the file size, so it cannot say how much of the cost is KV cache.
 */
interface ModelFootprint {
    /** Bytes of weights. */
    val weightBytes: Long

    /** Total native bytes at [contextLength]. */
    fun totalBytes(contextLength: Int): Long

    /** KV-cache bytes at [contextLength], or null when the format does not expose them. */
    fun kvBytes(contextLength: Int): Long?
}

/**
 * Footprint of a LiteRT-LM file from its size alone.
 *
 * WHY weights = file size: a `.litertlm` is a bundle of graphs and weights with
 * little else in it, so the file size is a fair stand-in for the weights.
 *
 * WHY a fixed overhead: runtime buffers and the engine's own state exist, but the
 * file does not say how big they are. [OVERHEAD_BYTES] is a deliberately generous
 * constant, so the load gate errs toward refusing rather than toward an OOM kill.
 * It is NOT measured. No `.litertlm` has been loaded on a phone in this project.
 *
 * WHY no KV term: the KV cache depends on layer count, head geometry and context,
 * and none of those are read from a `.litertlm` here. Reporting a KV figure would
 * be a number with no source, so [kvBytes] is null.
 */
class LiteRtLmFootprint(private val fileSizeBytes: Long) : ModelFootprint {
    override val weightBytes: Long get() = fileSizeBytes

    override fun totalBytes(contextLength: Int): Long = fileSizeBytes + OVERHEAD_BYTES

    override fun kvBytes(contextLength: Int): Long? = null

    companion object {
        /** 512 MiB. Unmeasured; chosen to make the load gate conservative. */
        const val OVERHEAD_BYTES: Long = 512L * 1024 * 1024
    }
}

/**
 * Native-memory cost of holding a model, in bytes.
 *
 * The model is loaded into **native** memory, never onto the JVM heap, so this
 * does not come out of the app's heap budget and does not show up as Java heap
 * use. It comes out of the process's total footprint, which on Android is what
 * the low-memory killer actually watches.
 *
 * ## The arithmetic is not written here
 *
 * Every number below is produced by `ModelMemoryEstimator` in `:core`, from the
 * same tensor table the pre-download fit gate uses. This type is a thin adapter
 * that re-evaluates the estimate at whatever context length the UI is currently
 * showing, because a user moving a context slider needs a new number without a
 * re-parse. There is deliberately no second copy of the formula here — a second
 * copy is what produced a RAM estimate that disagreed with the one shown at
 * download time.
 *
 * ## What changed, and why the old numbers were wrong
 *
 * The previous implementation, which this replaces, computed:
 *
 * ```
 * weights   = fileSizeBytes                      (or params * 4.83 / 8)
 * kv/token  = layers * kvHeads * headDim * 2 (K,V) * 2 (f16)
 * logits    = guessedVocab * 4
 * compute   = 128 MiB, flat, always
 * ```
 *
 * Every one of those is wrong in a way that matters, and all of it was measured
 * against real files rather than argued:
 *
 *  * **`fileSizeBytes` is not the weight section.** It includes the header. On a
 *    real 1,056.15 MiB Spark-X2.5-1.7B Q4_K_M the tensor table sums to
 *    1,102,073,856 B against a 1,107,457,888 B file — a 5,384,032 B (5.13 MiB)
 *    overstatement on every model.
 *  * **The `params * 4.83 / 8` fallback collapses to zero.** It is taken when
 *    the file length is unknown, which is the normal case for a stream-backed
 *    SAF provider, and it multiplies a `general.parameter_count` that most
 *    modern converters do not write. On the same model the old code reported
 *    **0 bytes of weights** and a 352.58 MiB total, versus 1,339.02 MiB from
 *    the tensor table — a 3.8x under-report in the direction that says "it
 *    fits", on the exact figure that gates a load.
 *  * **4.83 bits/weight was applied to every quantisation.** It is the Q4_K_M
 *    effective width. Used as a fallback for the same model's BF16 sibling it
 *    is 3.4x too small.
 *  * **The 128 MiB compute buffer was flat and unmeasured.** The core estimator
 *    uses `max(64 MiB, 2% of weights)`, which on the same file is 67,108,864 B
 *    — half the old constant, and it still scales.
 *  * **The vocabulary was guessed from embedding width** (32k/151936/128256)
 *    because the token count was "not a header field". It is: the core parser
 *    retains the array's element count, so the real 131,072 is available for
 *    free, and a guessed 151,936 was inflating the logits term by 0.10 MiB.
 *
 * None of the old numbers were measurements. These are derived from the file,
 * and [MemoryEstimate.basis] says which of them are exact.
 */
data class RamEstimate(
    /** The header this estimate is derived from. Kept so a context change can re-evaluate. */
    val header: GgufHeader,
    private val estimator: ModelMemoryEstimator = ModelMemoryEstimator(),
    /** Where the cache type being priced is read from: the setting the load allocates by. */
    private val kvCacheTypeSource: () -> KvCacheType = { KvCacheType.DEFAULT },
) : ModelFootprint {
    private fun at(contextLength: Int): MemoryEstimate =
        estimator.estimate(header, contextLengthOverride = contextLength.toLong(), kvCacheType = kvCacheType)

    /**
     * Bytes of weights, at the measured default context.
     *
     * This is a property of the file, not of the context, so evaluating at any
     * context gives the same figure; the UI reads it as "Weights in RAM".
     */
    override val weightBytes: Long get() = at(ModelImporter.DEFAULT_CONTEXT_LENGTH).weightsBytes

    /** The estimate backing the last call, for callers that want the inputs and warnings. */
    fun estimate(contextLength: Int = ModelImporter.DEFAULT_CONTEXT_LENGTH): MemoryEstimate =
        at(contextLength)

    /** KV cache bytes at [contextLength]. Independent of the weights; scales linearly with context. */
    override fun kvBytes(contextLength: Int): Long = at(contextLength).kvCacheBytes

    /**
     * The KV cache type this estimate assumes.
     *
     * The KV-cache setting's type (q8_0 unless Technical mode chose another), the same one
     * the native load allocates. Exposed so a UI can say which one it priced.
     */
    val kvCacheType: KvCacheType get() = kvCacheTypeSource()

    /** Total native bytes at [contextLength]. */
    override fun totalBytes(contextLength: Int): Long = at(contextLength).totalBytes

    /**
     * Ceiling on the context length that fits in [available] bytes, 0 if none does.
     *
     * WHY this solves rather than scans: the KV term is exactly linear in
     * context, so the answer is `(available - fixed) / bytesPerToken`. A binary
     * search would arrive at the same number while hiding the arithmetic the user
     * is being asked to trust.
     */
    fun maxAffordableContext(available: Long): Int {
        // Two probes instead of one: sliding-window layers, recurrent state and the
        // compute buffer make the total affine in the context, not proportional to it.
        val lo = at(PROBE_LOW)
        val hi = at(PROBE_HIGH)
        val perToken = (hi.totalBytes - lo.totalBytes) / (PROBE_HIGH - PROBE_LOW)
        if (perToken <= 0L) return 0
        val fixed = lo.totalBytes - perToken * PROBE_LOW
        val forKv = available - fixed
        if (forKv <= 0L) return 0
        val n = forKv / perToken
        return if (n > Int.MAX_VALUE) Int.MAX_VALUE else n.toInt()
    }

    fun fitsIn(available: Long, contextLength: Int): Boolean =
        totalBytes(contextLength) <= available

    /**
     * The header warnings worth surfacing on an imported model.
     *
     * WHY re-derive rather than cache: the warnings that matter for an import
     * (truncated tensor table, declared data past the end of the file, an absent
     * context length) are the ones a user can act on, and they are produced by
     * the same parse as the number shown next to them.
     */
    fun warnings(): List<GgufWarning> = at(ModelImporter.DEFAULT_CONTEXT_LENGTH).warnings

    companion object {
        private const val PROBE_LOW = 1_024
        private const val PROBE_HIGH = 2_048

        /**
         * Usable memory, after the JVM heap, the app's own footprint, and the
         * headroom Android needs before it starts killing background processes.
         * Deliberately conservative: an app that gets OOM-killed mid-conversation
         * is worse than an app that declines to load a model.
         *
         * This is the *budget* side of the comparison and has no counterpart in
         * `:core`: it asks what this device can give, not what this file needs.
         * It reads `/proc/meminfo`, which is a Linux interface and therefore not
         * something a pure-JVM module may touch.
         */
        fun usableDeviceBytes(): Long {
            val physical = systemTotalBytes()
            if (physical <= 0L) return AndroidDeviceBudget.FALLBACK_RAM_BYTES
            return (physical * AndroidDeviceBudget.USABLE_FRACTION).toLong()
        }

        private fun systemTotalBytes(): Long = try {
            val memInfo = java.io.File("/proc/meminfo")
            if (memInfo.canRead()) {
                java.io.File("/proc/meminfo").useLines { lines ->
                    lines.firstOrNull { it.startsWith("MemTotal:") }
                        ?.split(Regex("\\s+"))?.getOrNull(1)
                        ?.toLongOrNull()?.times(1024)
                } ?: 0L
            } else {
                0L
            }
        } catch (_: Exception) {
            0L
        }

        /**
         * Estimates from a parsed header, at [contextLength].
         *
         * Every input is the file's own: the weight section from the tensor
         * table, the KV geometry from the `<arch>.*` keys, the overhead from the
         * estimator's documented constants. There is no file-size shortcut and no
         * bits-per-weight guess, because the tensor table is present in the same
         * parse and makes both unnecessary.
         */
        fun from(
            header: GgufHeader,
            contextLength: Int = ModelImporter.DEFAULT_CONTEXT_LENGTH,
            kvCacheType: () -> KvCacheType = { KvCacheType.DEFAULT },
        ): RamEstimate = RamEstimate(header, kvCacheTypeSource = kvCacheType)
    }
}
