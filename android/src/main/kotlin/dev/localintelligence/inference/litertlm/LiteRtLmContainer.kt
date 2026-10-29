package dev.localintelligence.inference.litertlm

/**
 * Recognises a LiteRT-LM (`.litertlm`) container from its first bytes.
 *
 * ## Why this is its own object
 *
 * Two places need the same answer: [ModelBackendRouter] picks the runtime from
 * it, and `ModelImporter.inspect` must decide whether to parse a GGUF header or
 * describe a LiteRT-LM file. If each kept its own magic constant, the importer
 * could list a file the router then refuses, or the reverse. One definition
 * prevents that.
 *
 * ## Why a window, not an offset
 *
 * A FlatBuffer is a 4-byte root offset followed by an optional 4-byte file
 * identifier, so `LITERTLM` sits at offset 0 or 4. Matching anywhere in a
 * 16-byte window covers both and does not depend on an alignment detail that
 * was not verified on a device. The GGUF check runs first at every call site,
 * so a GGUF whose later bytes happen to spell the word is still a GGUF.
 */
object LiteRtLmContainer {
    /** Bytes a caller must read before calling [matches]. */
    const val SNIFF_BYTES = 16

    /** From the shipped `liblitertlm_jni.so` (0.13.1): `Invalid magic number. Expected 'LITERTLM'`. */
    private val MAGIC = "LITERTLM".toByteArray(Charsets.US_ASCII)

    /** True when [head] (the first [SNIFF_BYTES] of a file) carries the LiteRT-LM identifier. */
    fun matches(head: ByteArray): Boolean {
        if (head.size < MAGIC.size) return false
        for (start in 0..(head.size - MAGIC.size)) {
            var match = true
            for (i in MAGIC.indices) {
                if (head[start + i] != MAGIC[i]) {
                    match = false
                    break
                }
            }
            if (match) return true
        }
        return false
    }
}
