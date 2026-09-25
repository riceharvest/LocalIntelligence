package dev.localintelligence.core.eval.harness

import java.io.EOFException
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

// ===========================================================================
// GgufModelDiscovery.kt
//
// WHY THIS FILE EXISTS
// ====================
//
// A benchmark that cannot be pointed at a model is a benchmark that measures
// nothing. The runner needs three things before it may load a 2 GB file into
// RAM on a phone:
//
//   1. which .gguf files are actually there
//   2. what each one is (architecture, context length, quantisation)
//   3. whether it can run THIS suite at the requested context size
//
// Point 3 is the one people skip, and skipping it is how a benchmark produces a
// confident zero: llama.cpp clamps the context to whatever the model was trained
// with, a 2048-token model silently truncates a 4096-token prompt, every
// multi-step task fails, and the report says "task success 0.00" as though the
// MODEL were at fault. Refusing to start is the honest answer.
//
// WHY A LOCAL PARSER AND NOT THE ONE IN :android
// ==============================================
//
// `dev.localintelligence.android.inference.GgufReader` is the full parser and
// it is the better one. It cannot be used from here for two reasons, both
// structural rather than stylistic:
//
//   - it lives in the `:android` module, and this harness is a pure-JVM test
//     source set inside `:core`. `:core` must never depend on `:android`; the
//     architecture's central claim is that :core has no Android dependency, and
//     CI enforces it with a grep.
//   - it is not on this branch. A GGUF parser is being developed separately; if
//     this file reached for it, this harness would break the moment that work
//     merged, and would break differently depending on which side won.
//
// So this is a deliberately SMALL, dependency-free reader that extracts only the
// three fields the gate needs, and nothing else. It is not a replacement for
// GgufReader and does not try to be. When the :core parser lands, this file
// should collapse to a call into it; the gate below is the contract worth
// keeping either way.
//
// MEMORY DISCIPLINE
// =================
//
// A real GGUF KV section contains `tokenizer.ggml.tokens` — 128k strings, tens
// of megabytes — and `merges`. This reader never materialises a value it was
// not asked for. Unknown keys are skipped by size arithmetic, and the one key
// whose *presence* matters (`tokenizer.chat_template`) is detected without ever
// reading its bytes. A header probe that allocates 10 MB to answer "does this
// model have a chat template" is how the model manager OOMs on a low-end phone.
// ===========================================================================

/**
 * What a GGUF header says about a model, reduced to the fields that decide
 * whether a benchmark run can start.
 *
 * Every field is nullable because a header that omits one is legal, and a
 * missing context length must never be read as "unlimited". [fits] treats
 * unknown as "cannot prove it runs", which is the only safe reading.
 */
data class GgufModelInfo(
    val path: String,
    val fileName: String,
    val sizeBytes: Long,
    /** `general.architecture`, e.g. "llama", "qwen2". Null when absent. */
    val architecture: String? = null,
    /** `<arch>.context_length` — the trained maximum, in tokens. */
    val contextLength: Int? = null,
    /** Human-readable quantisation, e.g. "Q4_K_M". Null when the file omits it. */
    val quantType: String? = null,
    /** Whether `tokenizer.chat_template` exists, WITHOUT retaining the template. */
    val hasChatTemplate: Boolean = false,
    /** `general.name` when the converter recorded one. */
    val name: String? = null,
    /** GGUF container version, for the error message when something is off. */
    val version: Int = 0,
) {
    /** A label for logs: prefer the converter's name, fall back to the file. */
    val displayName: String get() = name ?: fileName
}

/**
 * The verdict on whether a model may be used for a given run.
 *
 * [runs] false means the benchmark MUST NOT start. It is a gate, not a warning,
 * because the alternative is a number that looks like a measurement and is not.
 */
data class ModelFitVerdict(
    val model: GgufModelInfo,
    /** The context the suite needs to have a chance of completing a task. */
    val requiredContextTokens: Int,
    val runs: Boolean,
    /** Human-readable reasons, empty when [runs] is true. */
    val reasons: List<String> = emptyList(),
) {
    /** One block of copy-pasteable guidance for a failing gate. */
    fun explain(): String = buildString {
        appendLine("model: ${model.displayName}  (${model.path})")
        appendLine("  architecture : ${model.architecture ?: "unknown"}")
        appendLine("  quantisation : ${model.quantType ?: "unknown"}")
        appendLine("  context      : ${model.contextLength ?: "unknown"} tokens trained")
        appendLine("  required     : $requiredContextTokens tokens")
        if (reasons.isEmpty()) {
            appendLine("  verdict      : OK")
        } else {
            appendLine("  verdict      : REFUSED")
            reasons.forEach { appendLine("    - $it") }
        }
    }.trimEnd()
}

/** Thrown when a file is present but is not a GGUF this reader can understand. */
class GgufFormatException(message: String) : IOException(message)

// -- the GGUF wire format ----------------------------------------------------
//
// `gguf_metadata_value_type`, from the container spec. These are the on-disk
// type tags, not something a caller should ever branch on, so they are
// file-private and shared by the object and the reader below.
private const val TYPE_UINT8 = 0
private const val TYPE_INT8 = 1
private const val TYPE_UINT16 = 2
private const val TYPE_INT16 = 3
private const val TYPE_UINT32 = 4
private const val TYPE_INT32 = 5
private const val TYPE_FLOAT32 = 6
private const val TYPE_BOOL = 7
private const val TYPE_STRING = 8
private const val TYPE_ARRAY = 9
private const val TYPE_UINT64 = 10
private const val TYPE_INT64 = 11
private const val TYPE_FLOAT64 = 12
private const val TYPE_MAX = 12

/** On-disk width in bytes per scalar type; -1 marks the variable-length types. */
private val FIXED_WIDTH = intArrayOf(1, 1, 2, 2, 4, 4, 4, 1, -1, -1, 8, 8, 8)

/** A string longer than this is corruption; real chat templates are 1-20 KB. */
private const val MAX_STRING_BYTES = 1 shl 22

/** An array with more elements than this is a length field misread as a count. */
private const val MAX_ARRAY_ELEMENTS = 1 shl 26

/**
 * Finds candidate models on disk and reports whether they can run the suite.
 *
 * Object rather than class: it holds no state, and a stateful "model manager"
 * here would be something a benchmark has no business being.
 */
object GgufModelDiscovery {

    private const val MAGIC = 0x46554747 // "GGUF" read as a little-endian u32
    private const val MIN_VERSION = 2
    private const val MAX_VERSION = 3

    private const val EXTENSION = ".gguf"

    /**
     * A kv_count above this is a corrupt length field, not a model. A real
     * 7B has ~30 entries; a 128k-vocab tokenizer is ONE array value, not 128k
     * keys, so there is no legitimate case near this bound.
     */
    private const val MAX_KV_COUNT = 1 shl 16

    /**
     * Lists `.gguf` files under [directory], shallowest first then by name.
     *
     * Returns an empty list for a directory that does not exist rather than
     * throwing, because "no models installed" is a normal state that the CLI
     * has to turn into a good message, and an exception here would make the
     * message path the exceptional one.
     */
    fun discover(directory: Path, recursive: Boolean = false, maxDepth: Int = 4): List<Path> {
        if (!Files.isDirectory(directory)) return emptyList()
        val out = ArrayList<Path>()
        collect(directory, recursive, maxDepth, out)
        // Deterministic order: a benchmark that reports models in filesystem
        // order is not reproducible across machines.
        return out.sortedWith(compareBy({ it.nameCount }, { it.toString() }))
    }

    private fun collect(dir: Path, recursive: Boolean, depthLeft: Int, out: MutableList<Path>) {
        val entries = try {
            Files.newDirectoryStream(dir)
        } catch (e: IOException) {
            return // unreadable directory: skip it rather than abort discovery
        }
        entries.use { stream ->
            for (path in stream) {
                if (Files.isDirectory(path)) {
                    if (recursive && depthLeft > 0) collect(path, recursive, depthLeft - 1, out)
                } else if (path.fileName.toString().endsWith(EXTENSION, ignoreCase = true)) {
                    out.add(path)
                }
            }
        }
    }

    /**
     * Reads the header of the GGUF at [path].
     *
     * Throws [GgufFormatException] rather than returning a half-populated
     * [GgufModelInfo]: a file that claims to be GGUF and is not is a broken
     * download or the wrong file, and the caller must be told which rather than
     * handed a model with a null architecture to "try anyway".
     */
    fun read(path: Path): GgufModelInfo {
        val file = try {
            RandomAccessFile(path.toFile(), "r")
        } catch (e: IOException) {
            throw GgufFormatException("cannot open ${path}: ${e.message}")
        }
        file.use { raw ->
            val raf = GgufReader(raw)
            val magic = raf.readU32("magic")
            if (magic.toInt() != MAGIC) {
                throw GgufFormatException(
                    "${path.fileName} is not a GGUF file: magic is " +
                        "0x${magic.toString(16)}, expected 0x${MAGIC.toString(16)} (\"GGUF\")",
                )
            }
            val version = raf.readU32("version").toInt()
            if (version < MIN_VERSION || version > MAX_VERSION) {
                throw GgufFormatException(
                    "${path.fileName} declares GGUF v$version; this reader understands " +
                        "v$MIN_VERSION..v$MAX_VERSION",
                )
            }
            raf.skipFully(8, "tensor_count")
            val kvCount = raf.readU64("kv_count")
            if (kvCount > MAX_KV_COUNT) {
                throw GgufFormatException(
                    "${path.fileName} declares $kvCount metadata entries, which is past the " +
                        "sanity limit of $MAX_KV_COUNT — the file is corrupt or is not GGUF",
                )
            }

            var architecture: String? = null
            var name: String? = null
            var fileType: Long? = null
            var contextLength: Long? = null
            var hasChatTemplate = false

            repeat(kvCount.toInt()) { index ->
                val key = raf.readString("metadata key #$index")
                val type = raf.readU32("value type of '$key'").toInt()
                when {
                    key == "general.architecture" && type == TYPE_STRING ->
                        architecture = raf.readString("value of '$key'")

                    key == "general.name" && type == TYPE_STRING ->
                        name = raf.readString("value of '$key'")

                    // The template is up to 20 KB of Jinja that this reader does
                    // not need. Presence is the whole question, so the bytes are
                    // skipped, never read.
                    key == "tokenizer.chat_template" && type == TYPE_STRING -> {
                        hasChatTemplate = true
                        raf.skipString("value of '$key'")
                    }

                    key == "general.file_type" -> {
                        fileType = raf.readScalar(type, "value of '$key'")
                    }

                    // Architecture-prefixed, and the architecture is not known
                    // until its own key is read — which may come later. So the
                    // key is matched on its SUFFIX and the prefix is checked
                    // afterwards. A single pass, no rewind, no ordering
                    // assumption.
                    key.endsWith(".context_length") -> {
                        contextLength = raf.readScalar(type, "value of '$key'")
                    }

                    else -> raf.skipValue(type, "value of '$key'")
                }
            }

            return GgufModelInfo(
                path = path.toAbsolutePath().toString(),
                fileName = path.fileName.toString(),
                sizeBytes = raf.length(),
                architecture = architecture,
                contextLength = contextLength?.let { if (it in 1..Int.MAX_VALUE.toLong()) it.toInt() else null },
                quantType = fileType?.let { quantNameForFileType(it.toInt()) },
                hasChatTemplate = hasChatTemplate,
                name = name,
                version = version,
            )
        }
    }

    /**
     * Reads every model in [directory], reporting per-file failures instead of
     * aborting. A truncated download in the models folder must not stop the
     * benchmark from finding the three good files next to it.
     */
    fun readAll(paths: List<Path>): List<Result<GgufModelInfo>> =
        paths.map { runCatching { read(it) } }

    /**
     * Decides whether [model] may be used for a run that needs
     * [requiredContextTokens].
     *
     * Three refusals, and every one of them is a case where running anyway
     * produces a number that reads like evidence:
     *
     *  - an unknown context length, because "clamp it and see" is how a
     *    truncated prompt becomes a 0.00 success rate attributed to the model
     *  - a trained context below the requirement, for the same reason
     *  - no chat template, when [requireChatTemplate] is set, because a model
     *    without one cannot be prompted reliably into the action protocol and
     *    the failure will look like a tool-calling defect
     */
    fun assessFit(
        model: GgufModelInfo,
        requiredContextTokens: Int,
        requireChatTemplate: Boolean = true,
    ): ModelFitVerdict {
        val reasons = mutableListOf<String>()

        val context = model.contextLength
        if (context == null) {
            reasons += "the header declares no context length, so this harness cannot prove " +
                "the model can hold a $requiredContextTokens-token prompt; re-export it with " +
                "<arch>.context_length present"
        } else if (context < requiredContextTokens) {
            reasons += "trained context is $context tokens but the suite needs " +
                "$requiredContextTokens; every multi-step task would be truncated. " +
                "Re-run with --context ${context} (if the tasks still fit) or use a larger model."
        }

        if (requireChatTemplate && !model.hasChatTemplate) {
            reasons += "the model carries no chat template, so it cannot be prompted reliably " +
                "into the action protocol; pick an instruct-tuned conversion"
        }

        return ModelFitVerdict(
            model = model,
            requiredContextTokens = requiredContextTokens,
            runs = reasons.isEmpty(),
            reasons = reasons,
        )
    }

    /**
     * `general.file_type` to the name llama.cpp prints. A data table, not logic.
     *
     * Unrecognised ids map to `UNKNOWN_<n>` rather than a guess: a wrong quant
     * name in a report is a small lie that propagates into every comparison
     * made with it.
     */
    fun quantNameForFileType(fileType: Int): String = when (fileType) {
        0 -> "F32"
        1 -> "F16"
        2 -> "Q4_0"
        3 -> "Q4_1"
        7 -> "Q8_0"
        8 -> "Q5_0"
        9 -> "Q5_1"
        10 -> "Q2_K"
        11 -> "Q3_K_S"
        12 -> "Q3_K_M"
        13 -> "Q3_K_L"
        14 -> "Q4_K_S"
        15 -> "Q4_K_M"
        16 -> "Q5_K_S"
        17 -> "Q5_K_M"
        18 -> "Q6_K"
        19 -> "IQ2_XXS"
        20 -> "IQ2_XS"
        21 -> "IQ2_S"
        22 -> "IQ1_S"
        23 -> "IQ1_M"
        24 -> "IQ4_XS"
        25 -> "IQ4_NL"
        26 -> "TQ1_0"
        27 -> "TQ2_0"
        28 -> "MXFP4"
        else -> "UNKNOWN_$fileType"
    }
}

/**
 * Bounds-checked forward reader over one GGUF file.
 *
 * Every read validates against [length] BEFORE touching the file, so a corrupt
 * length field produces a clean [GgufFormatException] naming the offset rather
 * than an EOFException, a negative array size, or a multi-gigabyte allocation.
 * That matters more here than in the app: this runs against files a benchmark
 * operator picked off a disk, not files the user chose through SAF.
 */
private class GgufReader(val file: RandomAccessFile) {

    fun length(): Long = file.length()

    fun readU32(what: String): Long {
        val b = readBytes(4, what)
        return (b[0].toLong() and 0xFF) or
            ((b[1].toLong() and 0xFF) shl 8) or
            ((b[2].toLong() and 0xFF) shl 16) or
            ((b[3].toLong() and 0xFF) shl 24)
    }

    fun readU64(what: String): Long {
        val b = readBytes(8, what)
        var v = 0L
        for (i in 7 downTo 0) v = (v shl 8) or (b[i].toLong() and 0xFF)
        return v
    }

    fun skipFully(n: Long, what: String) {
        if (n < 0) throw GgufFormatException("negative length $n for $what")
        val here = file.filePointer
        val target = here + n
        if (n > file.length() || target < here || target > file.length()) {
            throw GgufFormatException(
                "truncated GGUF: $what needs $n bytes at offset $here but the file is " +
                    "${file.length()} bytes",
            )
        }
        file.seek(target)
    }

    /**
     * Reads a `gguf_string`: a u64 byte length followed by exactly that many
     * bytes. NOT NUL-terminated, and the declared length is a BYTE count, which
     * is not the character count once UTF-8 is involved.
     */
    fun readString(what: String): String {
        val declared = readU64("length of $what")
        if (declared < 0 || declared > MAX_STRING_BYTES) {
            throw GgufFormatException(
                "GGUF string '$what' declares $declared bytes; the limit is $MAX_STRING_BYTES",
            )
        }
        if (declared == 0L) return ""
        return String(readBytes(declared.toInt(), what), StandardCharsets.UTF_8)
    }

    fun skipString(what: String) {
        val declared = readU64("length of $what")
        if (declared < 0 || declared > MAX_STRING_BYTES) {
            throw GgufFormatException(
                "GGUF string '$what' declares $declared bytes; the limit is $MAX_STRING_BYTES",
            )
        }
        skipFully(declared, what)
    }

    /**
     * Reads a scalar and returns it as a long, or null when the value cannot be
     * expressed as one. Used for file_type and context_length, both of which are
     * integers in every real file.
     */
    fun readScalar(type: Int, what: String): Long? = when (type) {
        TYPE_UINT8, TYPE_INT8, TYPE_BOOL -> readU32(what).toLong()
        TYPE_UINT16, TYPE_INT16 -> readU32(what).toLong()
        TYPE_UINT32 -> readU32(what)
        TYPE_INT32 -> readU32(what).toLong()
        TYPE_FLOAT32 -> Float.fromBits(readU32(what).toInt()).toLong()
        TYPE_UINT64, TYPE_INT64 -> readU64(what)
        TYPE_FLOAT64 -> Double.fromBits(readU64(what)).toLong()
        else -> {
            skipValue(type, what)
            null
        }
    }

    /**
     * Advances past a value without materialising it.
     *
     * This is the method that keeps the probe off the heap. A fixed-width array
     * is one seek regardless of length — `tokenizer.ggml.tokens` costs 8 bytes
     * of arithmetic, not 128k Strings.
     */
    fun skipValue(type: Int, what: String) {
        if (type > TYPE_MAX || type < 0) {
            throw GgufFormatException("unknown GGUF value type $type for '$what'")
        }
        if (type == TYPE_STRING) {
            skipString(what)
            return
        }
        if (type == TYPE_ARRAY) {
            val elementType = readU32("element type of '$what'").toInt()
            if (elementType > TYPE_MAX) {
                throw GgufFormatException("unknown GGUF array element type $elementType in '$what'")
            }
            val count = readU64("length of '$what'")
            if (count < 0 || count > MAX_ARRAY_ELEMENTS) {
                throw GgufFormatException(
                    "GGUF array '$what' declares $count elements; the limit is $MAX_ARRAY_ELEMENTS",
                )
            }
            val width = FIXED_WIDTH[elementType].toLong()
            if (width > 0) {
                // Check the whole span before seeking, so a bogus count cannot
                // turn into a seek past EOF that later reads from garbage.
                if (count != 0L && width > Long.MAX_VALUE / count) {
                    throw GgufFormatException("GGUF array '$what' length overflows")
                }
                skipFully(width * count, what)
            } else {
                // Variable-width elements: no arithmetic shortcut, so walk them.
                repeat(count.toInt()) { skipValue(elementType, "$what[$it]") }
            }
            return
        }
        val width = FIXED_WIDTH[type].toLong()
        if (width > 0) skipFully(width, what)
    }

    private fun readBytes(n: Int, what: String): ByteArray {
        if (n < 0) throw GgufFormatException("negative length $n for '$what'")
        val here = file.filePointer
        if (here + n > file.length()) {
            throw GgufFormatException(
                "truncated GGUF: '$what' needs $n bytes at offset $here but the file is " +
                    "${file.length()} bytes",
            )
        }
        val out = ByteArray(n)
        file.readFully(out)
        return out
    }
}
