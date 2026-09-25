package dev.localintelligence.core.hub

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * What a partially-downloaded file is allowed to resume from.
 *
 * ## Why this is not just "the file length"
 *
 * A length is not evidence. A partial file on disk is the residue of a process
 * that may have been killed mid-write, a phone that lost signal, or a user who
 * cleared the app's data directory halfway through. Three things can make the
 * on-disk length wrong in a way that produces a *corrupt final file* rather
 * than a visible error:
 *
 * 1. The file is longer than the server's current entity — the repo was
 *    re-uploaded, or a resume was attempted against a different file.
 * 2. The file is the wrong file entirely — a previous download of the same
 *    local name from a different repo, because the local name is derived and
 *    two repos can collide after flattening.
 * 3. The bytes on disk are a *prefix* of the wrong revision.
 *
 * (3) is not detectable without re-hashing the whole prefix, which on a 2 GB
 * file costs more than the resume saves. The cheap, correct guard is (1):
 * trust the length only when the server's declared total is at least that
 * long, and drop the partial otherwise. So [ResumeDecision] is the *policy*,
 * and it is a pure function of (local bytes, server total) so it can be tested
 * exhaustively without touching a filesystem.
 */
sealed interface ResumeDecision {
    /** Start from byte 0, discarding whatever is on disk. */
    data object Restart : ResumeDecision

    /** Append from [fromByte]; the local prefix is trusted. */
    data class Resume(val fromByte: Long) : ResumeDecision

    /** The partial file cannot be trusted. Delete it and start over. */
    data class Discard(val reason: String) : ResumeDecision
}

/**
 * Decides whether a partial download may be resumed.
 *
 * @param localBytes bytes present in the `.part` file, 0 if none.
 * @param serverTotal the entity size the server just declared, or -1 if unknown.
 */
object ResumePolicy {

    /**
     * Why a local file is longer than the server's entity is a hard restart.
     *
     * Appending to it would produce a file of the right length containing a
     * duplicated chunk, and the SHA check would fail it *after* the whole
     * transfer. Restarting costs the partial bytes but always converges.
     */
    fun decide(localBytes: Long, serverTotal: Long): ResumeDecision = when {
        localBytes < 0 -> ResumeDecision.Discard("negative length")
        localBytes == 0L -> ResumeDecision.Restart
        // -1 means the server did not declare a size. The local length is all
        // the evidence there is, and appending is still the right call: a
        // 200-with-unknown-length resume that is wrong gets caught by the
        // checksum, whereas restarting throws away a gigabyte on every server
        // that omits Content-Range.
        serverTotal < 0 -> ResumeDecision.Resume(localBytes)
        localBytes > serverTotal -> ResumeDecision.Discard("local file is longer than the remote file")
        localBytes == serverTotal -> ResumeDecision.Discard("already complete")
        else -> ResumeDecision.Resume(localBytes)
    }
}

/**
 * A file being downloaded, with the temp-file discipline that makes a cancelled
 * download harmless.
 *
 * ## The invariant this class exists to protect
 *
 * > A partially downloaded model NEVER appears at the path a valid model is
 * > expected at.
 *
 * Bytes are written to `<name>.part` and only moved to `<name>` after the
 * transfer completes *and* the SHA matches. There is no window in which the
 * final path holds an incomplete file, which means the loader can never
 * memory-map half a GGUF — the failure mode that produces a native crash with
 * no Java stack and no file name in the message.
 *
 * The rename is `File.renameTo` (a POSIX `rename(2)` under it) and then a
 * `File.length()` check, because on some filesystems the rename is a copy. If
 * the length does not match after the rename the file is deleted again, so the
 * "partial file at the final path" state is unreachable even on a copy-based
 * rename that ran out of space halfway.
 *
 * This class is not testable without a filesystem, so it lives in :core as pure
 * decision logic ([ResumePolicy], [DownloadPlan]) and the filesystem half lives
 * in the Android downloader. The seam is deliberate: the policy is what can be
 * wrong silently, and policy is what belongs in the fast test suite.
 */
class PartialFile(
    /** The `.part` path. Bytes live here and only here until [promote]. */
    val partFile: File,
    /** The final path a loader will look for. */
    val finalFile: File,
) {
    /** Bytes already on disk in the `.part` file, 0 if absent. */
    fun localBytes(): Long = if (partFile.isFile) partFile.length() else 0L

    /** The final file exists and is the expected length. */
    fun isComplete(expectedBytes: Long): Boolean =
        finalFile.isFile && expectedBytes >= 0 && finalFile.length() == expectedBytes

    /**
     * Deletes the partial file. Called on cancel, on checksum failure, and
     * whenever [ResumePolicy] decides the prefix cannot be trusted.
     */
    fun discard() {
        if (partFile.exists()) partFile.delete()
    }

    /**
     * Moves a verified `.part` to its final name.
     *
     * @return null on success, or the reason it could not be completed.
     */
    fun promote(expectedBytes: Long): String? {
        if (!partFile.isFile) return "partial file is missing"
        if (expectedBytes >= 0 && partFile.length() != expectedBytes) {
            return "partial file is ${partFile.length()} bytes, expected $expectedBytes"
        }
        if (finalFile.exists() && !finalFile.delete()) {
            return "could not replace the existing model file"
        }
        if (!partFile.renameTo(finalFile)) {
            return "could not move the download into place"
        }
        // Guards a copy-based rename that ran out of space. Without this the
        // app would happily hand a truncated file to the loader.
        if (expectedBytes >= 0 && finalFile.length() != expectedBytes) {
            finalFile.delete()
            return "moved file is ${finalFile.length()} bytes, expected $expectedBytes"
        }
        return null
    }

    /**
     * Appends to the partial file at the resume offset, creating it if needed.
     *
     * [RandomAccessFile] in `"rw"` mode because the download runs on a
     * background dispatcher and a plain `FileOutputStream` would truncate on
     * open — which is exactly what the resume logic is trying not to do.
     */
    @Throws(IOException::class)
    fun openAppend(fromByte: Long): RandomAccessFile {
        partFile.parentFile?.mkdirs()
        val raf = RandomAccessFile(partFile, "rw")
        raf.setLength(fromByte)
        raf.seek(fromByte)
        return raf
    }
}

/**
 * A download that has been fully validated and is ready to be opened.
 *
 * WHY the local path is in a sealed type rather than a nullable `File`: the
 * compiler then makes every caller handle the "could not promote" case, which
 * is the case where a file must NOT be handed to the loader.
 */
sealed interface DownloadOutcome {
    data class Complete(val file: File, val bytes: Long, val sha256: String?) : DownloadOutcome
    data class Failed(val error: HubError) : DownloadOutcome
    data class Cancelled(val partialBytesKept: Long) : DownloadOutcome
}

/**
 * The full set of decisions made before a byte moves, so the UI can show them
 * and a test can assert them without a network.
 *
 * [reject] is the whole feature in one field: when it is non-null the download
 * does not start, and the reason is already phrased for a human.
 */
data class DownloadPlan(
    val file: HubGgufFile,
    val ram: RamFit,
    val requiredDiskBytes: Long,
    val freeDiskBytes: Long,
    /** Null when the download may proceed. Non-null means it must not start. */
    val reject: HubError?,
) {
    val isAllowed: Boolean get() = reject == null
    val sizeLabel: String get() = formatBytes(file.sizeBytes)
}
