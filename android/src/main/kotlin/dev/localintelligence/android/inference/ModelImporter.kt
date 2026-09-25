package dev.localintelligence.android.inference

import android.content.ContentResolver
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import java.io.FileNotFoundException
import java.io.InputStream

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
 */
class ModelImporter(private val contentResolver: ContentResolver) {

    /**
     * Reads the header of [uri] and reports what it is and what it will cost.
     * Does not load the model, does not copy the file, does not keep the
     * descriptor open.
     */
    fun inspect(uri: Uri, contextLength: Int = DEFAULT_CONTEXT_LENGTH): ImportedModel {
        val afd = openAsset(uri)
        try {
            val size = lengthOf(afd, uri)
            afd.createInputStream().use { input ->
                val meta = GgufReader.parse(input, size)
                return describe(meta, uri, size, contextLength)
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
        val meta = try {
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
                GgufReader.parse(header.createInputStream(), size)
            } finally {
                header.close()
            }
        } catch (e: Throwable) {
            afd.close()
            throw e
        }
        return LoadedModel(afd, meta, uri)
    }

    /** Turns a parsed header into the record the model manager shows. */
    fun describe(
        meta: GgufMetadata,
        uri: Uri,
        fileSizeBytes: Long,
        contextLength: Int = DEFAULT_CONTEXT_LENGTH,
    ): ImportedModel {
        val estimate = RamEstimate.from(meta, contextLength)
        return ImportedModel(
            uri = uri,
            displayName = meta.name ?: displayNameFromUri(uri),
            fileSizeBytes = fileSizeBytes,
            architecture = meta.architecture,
            quantType = meta.quantType,
            trainedContextLength = meta.contextLength,
            parameterCount = meta.parameterCount,
            hasChatTemplate = meta.hasChatTemplate,
            supportedBackends = setOf(BACKEND_LLAMA_CPP),
            estimate = estimate,
        )
    }

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
            // a supported state, not a failure.
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
    }
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
    val estimate: RamEstimate,
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
    val metadata: GgufMetadata,
    val uri: Uri,
) : AutoCloseable {

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
 * Native-memory cost of holding a model, in bytes.
 *
 * The model is loaded into **native** memory, never onto the JVM heap, so this
 * does not come out of the app's heap budget and does not show up as Java heap
 * use. It comes out of the process's total footprint, which on Android is what
 * the low-memory killer actually watches.
 *
 * ### Worked example: 3B Q4_K_M at 4K context
 *
 * Using real llama-3.2-3B architecture numbers (28 layers, 3072 embedding,
 * 24 heads, 8 KV heads, head_dim 128) and 3,211,758,208 parameters:
 *
 * ```
 * weights   = params * 4.83 bits / 8
 *           = 3,211,758,208 * 4.83 / 8
 *           = 1,939,099,018 B          (1.81 GiB)
 * kv/token  = n_layer * n_head_kv * head_dim * 2 (K and V) * 2 (f16)
 *           = 28 * 8 * 128 * 2 * 2
 *           = 4,096 B per token per layer  -> 114,688 B per token
 * kv @ 4K   = 28 * 4,096 * 4,096
 *           = 469,762,048 B          (448 MiB, 0.44 GiB)
 * logits    = n_vocab * 4 B         = 128,256 * 4 = 513,024 B (0.5 MiB)
 * compute   = 128 MiB               (ubatch scratch + graph)
 * ---------------------------------------------
 * TOTAL     = 2,543,591,818 B       (2.37 GiB)
 * ```
 *
 * 4.83 bits/weight for Q4_K_M is the effective figure including the K-quant
 * superblock scales; it reproduces the published 1.93 GB file size to within 1%.
 *
 * **The KV cache is the part that scales with context**, and it is the reason
 * context length has to be a deliberate choice on a phone:
 *
 * ```
 *  ctx  1K -> kv  0.11 GiB   total  2.04 GiB
 *  ctx  4K -> kv  0.44 GiB   total  2.37 GiB
 *  ctx  8K -> kv  0.88 GiB   total  2.81 GiB
 *  ctx 32K -> kv  3.50 GiB   total  5.43 GiB
 * ctx 128K -> kv 14.00 GiB   total 15.93 GiB   (a 3B model cannot do this on a phone)
 * ```
 *
 * It is strictly linear: every doubling of context adds 448 MiB at this model
 * size. Weights are constant, so past ~16K the KV cache is the majority of the
 * footprint.
 */
data class RamEstimate(
    val weightBytes: Long,
    val layerCount: Int,
    val kvHeads: Int,
    val headDim: Int,
    val kvBytesPerElement: Int,
    val vocabSize: Int,
    val computeBufferBytes: Long,
) {
    /** KV cache bytes per token. Independent of context length; scales with it. */
    val kvBytesPerToken: Long
        get() = layerCount.toLong() * kvHeads * headDim * 2L * kvBytesPerElement

    fun kvBytes(contextLength: Int): Long = kvBytesPerToken * contextLength

    fun logitsBytes(): Long = vocabSize.toLong() * 4L

    fun totalBytes(contextLength: Int): Long =
        weightBytes + kvBytes(contextLength) + logitsBytes() + computeBufferBytes

    /** Ceiling on the context length that fits in [available] bytes, 0 if none does. */
    fun maxAffordableContext(available: Long): Int {
        val fixed = weightBytes + logitsBytes() + computeBufferBytes
        val forKv = available - fixed
        if (forKv <= 0L || kvBytesPerToken <= 0L) return 0
        val n = forKv / kvBytesPerToken
        return if (n > Int.MAX_VALUE) Int.MAX_VALUE else n.toInt()
    }

    fun fitsIn(available: Long, contextLength: Int): Boolean =
        totalBytes(contextLength) <= available

    companion object {
        /**
         * Q4_K_M effective bits per weight, including the K-quant superblock
         * scales and the 6-bit scales on the half-quantized attention tensors.
         */
        const val Q4_K_M_BITS_PER_WEIGHT = 4.83

        /**
         * llama.cpp's peak working set for a ubatch of a few hundred tokens on
         * a 3B. Measured in practice, not derived; it is flat in context length
         * and only moves with n_ubatch.
         */
        const val DEFAULT_COMPUTE_BUFFER_BYTES = 128L * 1024 * 1024

        /**
         * Usable memory, after the JVM heap, the app's own footprint, and the
         * headroom Android needs before it starts killing background processes.
         * Deliberately conservative: an app that gets OOM-killed mid-conversation
         * is worse than an app that declines to load a model.
         */
        fun usableDeviceBytes(): Long {
            val runtime = Runtime.getRuntime()
            val systemMax = runtime.maxMemory()          // the JVM heap ceiling
            val systemTotal = systemTotalBytes()          // the device's RAM
            // 55% of physical RAM, and never more than 6x the heap ceiling, so
            // the estimate does not go absurd on a 16 GB tablet.
            val physical = (systemTotal * 0.55).toLong()
            val heapCeiling = systemMax * 6
            return if (physical > 0 && physical < heapCeiling) physical else heapCeiling
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
         * Estimates from a parsed header. Falls back to a per-parameter
         * estimate when the header omits the architecture fields, because a
         * wrong-but-plausible number is more useful to the UI than none.
         */
        fun from(meta: GgufMetadata, contextLength: Int = ModelImporter.DEFAULT_CONTEXT_LENGTH): RamEstimate {
            val layers = meta.blockCount ?: 0
            val embedding = meta.embeddingLength ?: 0
            val heads = meta.attentionHeadCount ?: 0
            val kvHeads = meta.attentionHeadCountKv ?: heads
            // head_dim is the one llama.cpp derives rather than stores; honour
            // the explicit overrides when the header carries them.
            val headDim = meta.keyLength ?: meta.ropeDimensionCount
                ?: if (heads > 0 && embedding > 0) embedding / heads else 0

            // The file size is the ground truth for the weights -- but only if
            // it is actually a file size. When the caller did not know the
            // length, the parser falls back to the bytes it consumed, which is
            // the header alone (a few hundred bytes). Treating that as the
            // weight size would understate a 2 GB model as a few KB, so fall
            // through to the parameter count instead. A GGUF is always larger
            // than its own header, hence the `> kvSectionBytes` test.
            val fileBytes = meta.fileSizeBytes
            val weightBytes = if (fileBytes > meta.kvSectionBytes && fileBytes > 0) {
                fileBytes
            } else {
                val params = meta.parameterCount ?: 0
                (params * Q4_K_M_BITS_PER_WEIGHT / 8.0).toLong()
            }

            val vocab = estimateVocab(meta)

            return RamEstimate(
                weightBytes = weightBytes,
                layerCount = layers,
                kvHeads = kvHeads,
                headDim = headDim,
                // f16 KV cache: the default, and quantising it to q8_0 is a
                // deliberate future change, not the default behaviour.
                kvBytesPerElement = 2,
                vocabSize = vocab,
                computeBufferBytes = DEFAULT_COMPUTE_BUFFER_BYTES,
            )
        }

        /**
         * Vocabulary size. The token *count* is not a header field (counting
         * 128k tokens would mean reading them all), so this is an estimate
         * sized to the model's embedding width, which tracks vocab closely for
         * every family in the 1-4B range. It only feeds the logits buffer,
         * which is 0.5 MiB either way.
         */
        private fun estimateVocab(meta: GgufMetadata): Int {
            val embedding = meta.embeddingLength ?: 0
            return when {
                embedding <= 0 -> DEFAULT_VOCAB
                embedding >= 4096 -> 128_256   // llama-3 family
                embedding >= 2048 -> 151_936   // qwen2 family
                else -> 32_000                 // small models
            }
        }

        const val DEFAULT_VOCAB = 32_000
    }
}
