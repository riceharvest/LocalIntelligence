package dev.localintelligence.core.hub

import java.security.MessageDigest

/**
 * SHA-256 over a file, using the JDK's own [MessageDigest].
 *
 * ## Why not the HF-provided ETag alone
 *
 * HF's ETag for an LFS file *is* the sha256, but only on a full GET. A resumed
 * download assembled from a 206 has no single request whose ETag covers the
 * result, so the final hash has to be computed over what actually landed on
 * disk. That is the only check that can catch a truncated or mis-ordered
 * transfer, and it costs one linear read of a file the user just downloaded.
 *
 * ## Why the whole-file hash and not a per-chunk hash
 *
 * Per-chunk hashing would catch a bad transfer earlier, and it is the
 * standard approach for large downloads. It is not worth it here: the failure
 * it would catch (a corrupt transfer) is already caught by the length check
 * that [PartialFile.promote] performs, and a 2 GB re-read on a phone is
 * seconds of battery for a case that essentially does not occur on a verified
 * TLS connection. The whole-file hash against a *server-published* digest is
 * the check with actual value, and it also catches a resume that appended to
 * the wrong offset.
 *
 * ## Memory
 *
 * Streams through an 8 KiB buffer. A 2 GB file must not be read into a
 * `ByteArray` on a RAM-constrained app, which is the whole premise of this
 * project.
 */
object Sha256 {

    private const val BUFFER_SIZE = 8 * 1024

    /**
     * @return lowercase hex, or null when the file could not be read (it was
     *   deleted mid-hash, or the storage went away). Null means "could not
     *   verify", which the caller must treat as a failure, never as a pass.
     */
    fun ofFile(path: java.io.File): String? = try {
        path.inputStream().buffered(BUFFER_SIZE).use { stream ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
            toHex(digest.digest())
        }
    } catch (_: Exception) {
        null
    }

    /**
     * Compares two hex digests case-insensitively, tolerating whitespace.
     *
     * WHY not a plain `==`: HF's OID is lowercase, the value a user or an
     * intermediate proxy hands us may not be, and a false checksum failure
     * deletes a 2 GB file that was fine. The asymmetry matters — a case
     * difference must never destroy a good download.
     */
    fun matches(expected: String, actual: String?): Boolean {
        if (actual == null) return false
        val a = expected.trim().lowercase()
        val b = actual.trim().lowercase()
        if (a.length != b.length || a.length != 64) return false
        return a.indices.all { a[it] == b[it] }
    }

    /**
     * Lowercase hex of a digest.
     *
     * WHY this is public: a caller checking a digest needs to compute the
     * expected value from a synthetic payload, and duplicating the hex
     * conversion per call site is how two implementations drift apart.
     */
    fun toHex(bytes: ByteArray): String {
        val out = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            out.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return out.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
