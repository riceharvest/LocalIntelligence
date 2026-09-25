package dev.localintelligence.core.model.gguf

import java.io.File
import java.io.RandomAccessFile

/**
 * Random-access byte source for header parsing.
 *
 * WHY not just an `InputStream`: the parser needs to know how many bytes are left
 * *before* it commits to reading a length, because every length in the file is
 * attacker-controlled. A stream cannot answer that, so a stream-based parser either
 * trusts the declared length (an OOM waiting for a 4-byte edit) or buffers the whole
 * file (which for a 4 GiB model is the thing we are trying to avoid). [sizeBytes] is
 * the load-bearing member: it is what makes "is there room for this?" answerable.
 *
 * The intended production path is [of], from a `File` the user picked: the caller never
 * has to think about this interface.
 */
interface GgufByteSource {
    /** Total bytes available. Must be exact; a wrong answer here is a security bug. */
    val sizeBytes: Long

    /**
     * Reads up to [length] bytes at [offset] into [dest].
     *
     * Returns the number of bytes actually read, which is short only at end of file.
     * Implementations must not allocate and must not block on data that is not there;
     * the parser's bounds checks are written against this contract.
     */
    fun readAt(offset: Long, dest: ByteArray, destOffset: Int, length: Int): Int

    companion object {
        /** In-memory source, for tests, for a small asset, and for a bounded read of a content URI. */
        fun of(bytes: ByteArray): GgufByteSource = ByteArraySource(bytes)

        /**
         * On-disk source. The [RandomAccessFile] is opened and closed per read, so a
         * caller cannot leak a descriptor by forgetting, and a parse of a 4 GiB model
         * costs one short-lived descriptor rather than a 4 GiB mapping.
         */
        fun of(file: File): GgufByteSource = FileSource(file)

        /**
         * Bounded read of an arbitrary stream into memory, for a content URI that
         * cannot be stat()ed for its length.
         *
         * WHY this exists and why it is bounded: on Android a `content://` URI has no
         * meaningful length, so the alternative is guessing. It reads at most
         * [maxBytes] and reports the true number of bytes read as [sizeBytes], so a
         * header near the end of a big stream still parses and a hostile stream still
         * cannot exhaust heap. It is a header reader, not a file reader.
         *
         * WHY pass [declaredSizeBytes] when the caller knows the real length — it is
         * not a detail. [sizeBytes] feeds `GgufHeader.fileBytes`, which decides two
         * user-visible things: the `FILE_SIZE` fallback for weights, and the
         * "declared data runs past the end of this file" warning. A 32 MiB buffer
         * standing in for a 1.1 GB file reports `fileBytes = 32 MiB`, and every one of
         * those is then wrong in the direction of *under*-reporting a model that
         * cannot load. A caller that already asked the platform for the length (an
         * `AssetFileDescriptor`, a `ContentResolver` size projection) should say so.
         */
        fun ofStream(
            stream: java.io.InputStream,
            maxBytes: Long = 32L * 1024 * 1024,
            declaredSizeBytes: Long = -1L,
        ): GgufByteSource {
            require(maxBytes > 0) { "maxBytes must be positive" }
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            var total = 0L
            while (total < maxBytes) {
                val want = minOf(buf.size.toLong(), maxBytes - total).toInt()
                val n = stream.read(buf, 0, want)
                if (n <= 0) break
                out.write(buf, 0, n)
                total += n
            }
            val bytes = out.toByteArray()
            // A declared length is a claim from the platform, not a measurement. It is
            // only worth believing when it is at least as large as what we actually
            // read; a smaller one is a lie (a stale projection, a pipe) and would make
            // every bounds check in the reader tighter than reality.
            return if (declaredSizeBytes >= bytes.size) DeclaredLengthSource(bytes, declaredSizeBytes)
            else ByteArraySource(bytes)
        }
    }
}

private class ByteArraySource(private val bytes: ByteArray) : GgufByteSource {
    override val sizeBytes: Long get() = bytes.size.toLong()

    override fun readAt(offset: Long, dest: ByteArray, destOffset: Int, length: Int): Int {
        if (offset < 0 || offset >= bytes.size) return 0
        val start = offset.toInt()
        val n = minOf(length, bytes.size - start)
        System.arraycopy(bytes, start, dest, destOffset, n)
        return n
    }
}

/**
 * A buffered prefix of a longer file, reporting the *file's* length.
 *
 * WHY this exists: the stream from a SAF descriptor is not the whole model, it is the
 * first few MiB of it. Reporting the buffer length as the file length makes every
 * downstream size judgement wrong — the "declared data exceeds the file" warning fires
 * on a perfectly intact 1.1 GB model, and the `FILE_SIZE` weights fallback reports the
 * 32 MiB we happened to buffer. So the buffered bytes and the true length are carried
 * separately, and a read past the end of the buffer is a short read, exactly as a
 * truncated file would be.
 */
private class DeclaredLengthSource(
    private val bytes: ByteArray,
    override val sizeBytes: Long,
) : GgufByteSource {
    override fun readAt(offset: Long, dest: ByteArray, destOffset: Int, length: Int): Int {
        if (offset < 0 || offset >= bytes.size) return 0
        val start = offset.toInt()
        val n = minOf(length, bytes.size - start)
        System.arraycopy(bytes, start, dest, destOffset, n)
        return n
    }
}

private class FileSource(private val file: File) : GgufByteSource {
    override val sizeBytes: Long get() = if (file.isFile) file.length() else 0L

    override fun readAt(offset: Long, dest: ByteArray, destOffset: Int, length: Int): Int {
        if (offset < 0 || !file.isFile) return 0
        return try {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(offset)
                var read = 0
                while (read < length) {
                    val n = raf.read(dest, destOffset + read, length - read)
                    if (n <= 0) break
                    read += n
                }
                read
            }
        } catch (_: java.io.IOException) {
            0
        }
    }
}
