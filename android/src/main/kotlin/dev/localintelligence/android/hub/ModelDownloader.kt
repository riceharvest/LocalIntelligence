package dev.localintelligence.android.hub

import dev.localintelligence.core.hub.DownloadPlan
import dev.localintelligence.core.hub.DownloadProgress
import dev.localintelligence.core.hub.HubError
import dev.localintelligence.core.hub.HubGgufFile
import dev.localintelligence.core.hub.HubRequest
import dev.localintelligence.core.hub.HubResponse
import dev.localintelligence.core.hub.HubTokenSource
import dev.localintelligence.core.hub.HubTransport
import dev.localintelligence.core.hub.HuggingFaceClient
import dev.localintelligence.core.hub.PartialFile
import dev.localintelligence.core.hub.ProgressThrottle
import dev.localintelligence.core.hub.ResumeDecision
import dev.localintelligence.core.hub.ResumePolicy
import dev.localintelligence.core.hub.Sha256
import dev.localintelligence.core.hub.TransferRate
import dev.localintelligence.core.model.token.ContextCeiling
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Downloads a GGUF from HuggingFace into app-private storage, resumably,
 * cancellably, and without ever leaving a partial file where a valid one is
 * expected.
 *
 * ## The one invariant
 *
 * > Bytes are written to `<name>.gguf.part` and appear at `<name>.gguf` only
 * > after the transfer completes AND the SHA matches.
 *
 * There is no code path that puts incomplete bytes at the final path. That
 * matters more than any other property here, because the consumer of the final
 * path is `llama_model_load_from_file`, which memory-maps it: a truncated GGUF
 * produces a native crash with no Java stack and no file name, and on some
 * devices it is a SIGBUS rather than a clean error. Cancellation, a dropped
 * connection, a checksum failure and a crash mid-copy all leave the same safe
 * state — a `.part` file and nothing at the final path.
 *
 * ## Why the pieces are split the way they are
 *
 * The *decisions* (should this resume, is this a fit, what does 429 mean, when
 * do we emit progress) all live in `:core` and are covered by tests that never
 * open a socket. This class is the mechanical loop that applies those
 * decisions to a real [HubTransport] and a real filesystem. That split is why
 * the test suite can cover resume-from-partial, cancel-safety and
 * checksum-mismatch without a device and without a network.
 *
 * ## Threading
 *
 * The whole body runs on [ioDispatcher] via [flowOn], because every operation
 * in it blocks: `HttpURLConnection` reads, `RandomAccessFile` writes and the
 * SHA pass over a 2 GB file. `Flow` collection is sequential, so
 * [currentCoroutineContext]'s `ensureActive` is checked per buffer — that is
 * what makes cancellation prompt (the contract in `docs/tool-contract.md`
 * requires a long read to observe cancellation, and a 2 GB copy is the longest
 * read in this app).
 */
class ModelDownloader(
    private val client: HuggingFaceClient,
    private val transport: HubTransport,
    private val modelsDir: File,
    private val tokenSource: HubTokenSource = HubTokenSource.NONE,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val throttle: ProgressThrottle = ProgressThrottle(),
    private val rate: TransferRate = TransferRate(),
) {

    /**
     * Downloads [file], emitting throttled progress.
     *
     * The returned flow always terminates with exactly one [DownloadProgress.Done]
     * or one [DownloadProgress.Stopped]. It never throws for an expected
     * failure, so a UI collector does not need a try/catch around a 2 GB
     * transfer.
     *
     * @param plan the pre-flight decision from [HuggingFaceClient.plan]. A plan
     *   with a non-null [DownloadPlan.reject] does not start a transfer: this
     *   is the enforcement point for the RAM and disk gates, not the UI.
     */
    fun download(file: HubGgufFile, plan: DownloadPlan): Flow<DownloadProgress> = flow {
        val rejection = plan.reject
        if (rejection != null) {
            emit(DownloadProgress.Stopped(rejection, partialBytesKept = 0L))
            return@flow
        }

        val partial = partialFileFor(file)
        val url = client.downloadUrl(file.repo, file.path)
        val token = tokenSource.token()?.takeIf { it.isNotBlank() }

        // Re-open the plan against what is actually on disk. A `.part` left by
        // a previous attempt changes the disk requirement, and deciding that
        // before the request rather than after is what keeps a resumed
        // download from failing its own space check.
        val localBytes = partial.localBytes()
        val effectivePlan = client.plan(
            file = file,
            contextLength = PreDownloadContextLength,
            budget = budgetForPlan(plan),
            alreadyOnDiskBytes = localBytes,
        )
        effectivePlan.reject?.let {
            emit(DownloadProgress.Stopped(it, partialBytesKept = localBytes))
            return@flow
        }

        val totalBytes = file.sizeBytes
        val startOffset = when (val decision = ResumePolicy.decide(localBytes, totalBytes)) {
            is ResumeDecision.Restart -> {
                partial.discard()
                0L
            }
            is ResumeDecision.Resume -> decision.fromByte
            is ResumeDecision.Discard -> {
                partial.discard()
                0L
            }
        }

        var response: HubResponse? = null
        // WHY a cancellation handler on the response body: `ensureActive()`
        // inside the copy loop is only reached BETWEEN buffer reads. A download
        // parked in a blocked read on a dead link would not observe a cancel
        // until that read returned, which on a real socket is up to the full
        // read timeout. Closing the stream shuts the socket, which unblocks the
        // read immediately and turns the cancel into a prompt one.
        //
        // This is the same reason `docs/tool-contract.md` requires a long read
        // to check `context.signal`.
        var cancelHandle: DisposableHandle? = null
        try {
            val request = HubRequest(
                url = url,
                rangeFrom = startOffset.takeIf { it > 0 },
                authToken = token,
            )
            response = transport.open(request)

            if (response.status !in 200..299) {
                val error = HubError.fromStatus(
                    status = response.status,
                    repo = file.repo.id,
                    file = file.fileName,
                    errorCode = response.header("X-Error-Code"),
                    retryAfterSeconds = response.header("Retry-After")?.toLongOrNull(),
                )
                emit(DownloadProgress.Stopped(error, partialBytesKept = localBytes))
                return@flow
            }

            val writeFrom = resolveWriteOffset(response, startOffset, partial)
            if (writeFrom == null) {
                // The server answered with a body that does not line up with
                // what is on disk. Appending would produce a file of exactly
                // the right length that is entirely wrong, so the only safe
                // move is to start over and tell the user.
                partial.discard()
                emit(
                    DownloadProgress.Stopped(
                        HubError.ConnectionLost("server returned an unusable range; restarted"),
                        partialBytesKept = 0L,
                    ),
                )
                return@flow
            }

            val body = response.body
            if (body != null) {
                cancelHandle = currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
                    if (cause is CancellationException) runCatching { body.close() }
                }
            }
            val copyOutcome = copyToFile(response, partial, writeFrom, totalBytes, file, emitProgress = { emit(it) })

            when (copyOutcome) {
                is CopyOutcome.Cancelled -> {
                    emit(DownloadProgress.Stopped(HubError.Cancelled, partialBytesKept = writeFrom))
                    return@flow
                }
                is CopyOutcome.Failed -> {
                    emit(DownloadProgress.Stopped(copyOutcome.error, partialBytesKept = writeFrom))
                    return@flow
                }
                CopyOutcome.Complete -> Unit
            }

            val expectedBytes = totalBytes
            val actualBytes = partial.localBytes()
            if (expectedBytes > 0 && actualBytes != expectedBytes) {
                emit(
                    DownloadProgress.Stopped(
                        HubError.ConnectionLost("got $actualBytes of $expectedBytes bytes"),
                        partialBytesKept = actualBytes,
                    ),
                )
                return@flow
            }

            val digest = Sha256.ofFile(partial.partFile)
            val expected = file.sha256
            if (expected != null && !Sha256.matches(expected, digest)) {
                // Discard rather than keep: a GGUF with a wrong checksum fails
                // deep inside the native loader with an error that names no
                // file, and the user cannot connect that back to this download.
                partial.discard()
                emit(DownloadProgress.Stopped(HubError.ChecksumMismatch(file.fileName), partialBytesKept = 0L))
                return@flow
            }

            val promoteProblem = partial.promote(expectedBytes)
            if (promoteProblem != null) {
                emit(DownloadProgress.Stopped(HubError.NoNetwork, partialBytesKept = partial.localBytes()))
                return@flow
            }

            emit(DownloadProgress.Done(file, partial.finalFile.absolutePath))
        } catch (e: IOException) {
            val kept = partial.localBytes()
            emit(DownloadProgress.Stopped(mapIoException(e), partialBytesKept = kept))
        } finally {
            cancelHandle?.dispose()
            response?.body?.close()
        }
    }.flowOn(ioDispatcher)

    /**
     * Decides the byte offset the response body starts at, and whether the
     * local prefix is still usable.
     *
     * The dangerous case this exists for: a client asks for `Range: bytes=1G-`,
     * the server ignores it and answers **200 with the whole entity**. The
     * body then starts at byte 0. Appending it to a 1 GB partial file yields a
     * file of exactly the declared length containing the first gigabyte twice.
     * The length check at the end passes and the SHA catches it — but only
     * after a full 2 GB transfer that was thrown away.
     *
     * So the body is checked against the local prefix *before* any of it is
     * written. `Content-Range` is the only trustworthy source of the start
     * offset; a 200 with no `Content-Range` means byte 0.
     *
     * @return the offset to write at, or null when the partial must be
     *   discarded.
     */
    internal fun resolveWriteOffset(
        response: HubResponse,
        requestedFrom: Long,
        partial: PartialFile,
    ): Long? {
        val bodyStart = if (response.isPartial) response.contentRangeStart() else 0L
        val actualStart = bodyStart ?: 0L
        if (requestedFrom == 0L) return 0L
        if (actualStart == requestedFrom) return requestedFrom
        // The server's idea of where the body starts disagrees with ours.
        return null
    }

    private sealed interface CopyOutcome {
        data object Complete : CopyOutcome
        data class Failed(val error: HubError) : CopyOutcome
        data class Cancelled(val bytesWritten: Long) : CopyOutcome
    }

    /**
     * The copy loop.
     *
     * Reads through a fixed 64 KiB buffer. WHY not larger: a bigger buffer does
     * not measurably speed up a TLS stream, and this buffer's residency is the
     * per-download memory cost on a device whose whole premise is not wasting
     * RAM. WHY not `File.copyTo`: it offers no cancellation check, no progress
     * hook, and no offset control, and all three are required here.
     */
    private suspend inline fun copyToFile(
        response: HubResponse,
        partial: PartialFile,
        writeFrom: Long,
        totalBytes: Long,
        file: HubGgufFile,
        emitProgress: (DownloadProgress) -> Unit,
    ): CopyOutcome {
        val body = response.body ?: return CopyOutcome.Failed(HubError.NoNetwork)
        var raf: RandomAccessFile? = null
        var written = writeFrom
        return try {
            raf = partial.openAppend(writeFrom)
            val buffer = ByteArray(BUFFER_SIZE)
            throttle.flush()
            emitProgress(DownloadProgress.InProgress(written, totalBytes, 0L))
            while (true) {
                // Checked per buffer so a cancel on a FAST connection is
                // observed immediately. A blocked read is a different problem
                // and is handled by the cancellation handler on the response
                // body in [download]; this line is the fast path, not the
                // guarantee.
                currentCoroutineContext().ensureActive()
                val read = body.read(buffer)
                if (read < 0) break
                raf.write(buffer, 0, read)
                written += read
                if (throttle.shouldEmit(written)) {
                    emitProgress(DownloadProgress.InProgress(written, totalBytes, rate.sample(written)))
                }
            }
            // WHY a final emission: the throttle gates the loop, so on a small
            // file the last gated event is some way short of the total. Without
            // this the progress bar freezes at 33% and is then replaced by the
            // completion state, which reads as a bug. A terminal emission is
            // never a wasted frame — it happens exactly once per download.
            throttle.flush()
            emitProgress(DownloadProgress.InProgress(written, totalBytes, rate.sample(written)))
            CopyOutcome.Complete
        } catch (e: kotlinx.coroutines.CancellationException) {
            CopyOutcome.Cancelled(written)
        } catch (e: IOException) {
            CopyOutcome.Failed(mapIoException(e))
        } finally {
            runCatching { raf?.close() }
        }
    }

    private fun mapIoException(e: IOException): HubError {
        val message = (e.message ?: "").lowercase()
        return when {
            message.contains("cancel") -> HubError.Cancelled
            message.contains("timeout") || message.contains("timed out") ->
                HubError.ConnectionLost(e.message ?: "timeout")
            message.contains("enospc") || message.contains("no space") ->
                HubError.InsufficientStorage(0L, 0L)
            else -> HubError.ConnectionLost(e.message ?: "transfer failed")
        }
    }

    /**
     * The final path this file will occupy in [modelsDir] — the same directory
     * `MainActivity`'s Scan-storage button lists.
     *
     * WHY it is public: after a download finishes, something has to hand THAT
     * file to `ModelImporter` for the app to be able to load it, and
     * reconstructing the name from the repo id at the call site would be a second
     * implementation of a naming rule that exists precisely so there is only one.
     */
    fun localFileFor(file: HubGgufFile): File = partialFileFor(file).finalFile

    /**
     * True when this exact file is already on disk, complete and correct.
     *
     * ## Why the digest, and not just the length
     *
     * Because a length match is not identity. The final name is derived from
     * `owner__name__file`, so re-uploading a file under the same name in the same
     * repo produces the same path with different bytes, and a user who already
     * has it should not spend 668 MB to get a second copy.
     *
     * `HubGgufFile.sha256` is HF's own published digest, so when it is present the
     * check is exact: a 668 MB hash on a file that is already there, which is a
     * second or two of I/O against a download that would be minutes and
     * megabytes. When it is absent (a non-LFS blob, or a mirror that omits it)
     * the length check is the strongest thing available and is used alone —
     * which still catches the case that matters most, the model being here
     * already.
     */
    fun isDownloaded(file: HubGgufFile): Boolean {
        val partial = partialFileFor(file)
        if (!partial.isComplete(file.sizeBytes)) return false
        val expected = file.sha256 ?: return true
        val actual = Sha256.ofFile(partial.finalFile) ?: return false
        if (!Sha256.matches(expected, actual)) {
            // Wrong bytes under the right name. Discard so the next download is a
            // real one rather than a resume from a partial that matches nothing.
            partial.finalFile.delete()
            return false
        }
        return true
    }

    /**
     * The `.part` and final paths for [file].
     *
     * WHY the local name is derived and flattened: two repos can contain the
     * same file name, and a flat name would make the second download silently
     * overwrite the first user's model. Flattening the path with `__` keeps
     * them distinct without creating directories, which keeps
     * [modelsDir] a single directory the storage UI can show and delete from.
     */
    internal fun partialFileFor(file: HubGgufFile): PartialFile {
        val localName = file.localFileName()
        // Defence in depth: HubRepoId and HubFilePath already restricted every
        // character, but this is the last point before a File is constructed,
        // and a name with a separator here would escape modelsDir.
        val safeName = localName.replace('/', '_').replace('\\', '_')
        return PartialFile(
            partFile = File(modelsDir, "$safeName.part"),
            finalFile = File(modelsDir, safeName),
        )
    }

    /**
     * Recovers a [DeviceBudget] for the re-plan.
     *
     * WHY this is derived from the plan rather than injected: the plan already
     * carries the figures the gate used, and holding a second live budget
     * object would mean the two could disagree (a disk check against a volume
     * that changed). A fixed view keeps the re-plan consistent with the
     * pre-flight the user actually saw and approved.
     */
    private fun budgetForPlan(plan: DownloadPlan): dev.localintelligence.core.hub.DeviceBudget =
        object : dev.localintelligence.core.hub.DeviceBudget {
            override fun availableRamBytes(): Long = plan.ram.availableBytes
            override fun freeDiskBytes(): Long = plan.freeDiskBytes
            override fun totalRamBytes(): Long = plan.ram.availableBytes
        }

    companion object {
        /**
         * WHY 64 KiB: big enough that syscall overhead is negligible on a
         * TLS stream, small enough that the per-download residency is invisible
         * next to a model.
         */
        const val BUFFER_SIZE = 64 * 1024

        /**
         * The context length used for the on-disk re-check.
         *
         * WHY it matches the allocation rather than the user's setting: the
         * disk re-check exists to catch a full volume, and RAM does not change
         * between the pre-flight and the transfer. Re-asking for a context
         * length would mean the download function has a parameter the UI has to
         * keep in sync with the plan it already passed in.
         *
         * WHY IT IS NO LONGER 2048: this was the last context literal in the
         * product that disagreed with what the loader allocates. At 2048 every
         * pre-download plan priced a KV cache half the real one, so the app
         * offered downloads on devices that would then OOM at load — the same
         * defect `PreDownloadMemoryModel.DEFAULT_CONTEXT_LENGTH` had, in the
         * direction that looks like generosity. Both now read
         * [ContextCeiling.ALLOCATED_CONTEXT_TOKENS].
         *
         * `docs/memory-model.md` §5.3 measured the size of that error over the
         * nine measured architectures: worst case **-19.8%**
         * (Phi-3-mini-4k-instruct), eight of nine under-statements. That is
         * larger than the 15% `FitGate.DECISION_FACTOR` provides, and
         * under-statement is the direction that gets a phone OOM-killed after
         * the user has spent the download.
         */
        const val PreDownloadContextLength = ContextCeiling.ALLOCATED_CONTEXT_TOKENS

    }
}
