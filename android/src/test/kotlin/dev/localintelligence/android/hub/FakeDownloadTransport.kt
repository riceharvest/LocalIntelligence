package dev.localintelligence.android.hub

import dev.localintelligence.core.hub.DeviceBudget
import dev.localintelligence.core.hub.HubGgufFile
import dev.localintelligence.core.hub.HubRepoId
import dev.localintelligence.core.hub.HubFilePath
import dev.localintelligence.core.hub.HubRequest
import dev.localintelligence.core.hub.HubResponse
import dev.localintelligence.core.hub.HubTransport
import dev.localintelligence.core.hub.HuggingFaceClient
import dev.localintelligence.core.hub.Sha256
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * A [HubTransport] that serves a fixed payload and honours `Range`, with no
 * socket and no network.
 *
 * This is the piece that makes the downloader testable at all. Real-socket
 * behaviour is explicitly unverified — see the PR body — but every *decision*
 * the downloader makes is exercised here against a server that behaves the way
 * HF's CDN does, including the part that matters most: answering a Range
 * request with 206 and a `Content-Range`, and answering a request for a
 * complete file with 200 from byte zero.
 */
class FakeDownloadTransport(
    private val payload: ByteArray,
    private val sha256: String? = Sha256.toHex(java.security.MessageDigest.getInstance("SHA-256").digest(payload)),
) : HubTransport {

    val requests = mutableListOf<HubRequest>()

    /** When set, the next `open` throws this instead of answering. */
    var failNextWith: IOException? = null

    /** When true, the server IGNORES the Range and answers 200 from byte 0. */
    var ignoreRange: Boolean = false

    /** Bytes to serve before cutting the stream, to simulate a dropped link. */
    var truncateAfterBytes: Int? = null

    /** Force a specific status on the next open. */
    var forceStatus: Int? = null
    var forceHeaders: Map<String, String> = emptyMap()

    override fun open(request: HubRequest): HubResponse {
        requests += request
        failNextWith?.let {
            failNextWith = null
            throw it
        }
        forceStatus?.let { status ->
            forceStatus = null
            return HubResponse(status, forceHeaders, ByteArrayInputStream(ByteArray(0)), 0L)
        }

        val requestedFrom = request.rangeFrom
        val honourRange = requestedFrom != null && requestedFrom < payload.size && !ignoreRange
        val from = if (honourRange) requestedFrom!! else 0L
        val slice = payload.copyOfRange(from.toInt(), payload.size)
        val cut = truncateAfterBytes?.let { slice.copyOfRange(0, minOf(it, slice.size)) } ?: slice
        truncateAfterBytes = null

        val headers = HashMap<String, String>()
        headers["Content-Type"] = "application/octet-stream"
        headers["X-Repo-Commit"] = "abc123"
        if (honourRange) {
            headers["Content-Range"] = "bytes $from-${payload.size - 1}/${payload.size}"
            headers["Content-Length"] = cut.size.toString()
            return HubResponse(206, headers, ByteArrayInputStream(cut), cut.size.toLong())
        }
        headers["Content-Length"] = cut.size.toString()
        return HubResponse(200, headers, ByteArrayInputStream(cut), cut.size.toLong())
    }

    val expectedSha: String? get() = sha256
}

/**
 * A transport that delivers [payload] and then blocks forever.
 *
 * WHY this exists: the only cancellation worth testing is one that lands while
 * bytes are still moving. A cancelled download that had already finished
 * proves nothing, and a truncated stream is a *failure*, not a cancellation —
 * the two go through different branches in the copy loop.
 */
class StallingTransport(
    private val payload: ByteArray,
    private val stallAfterBytes: Int,
) : HubTransport {
    @Volatile var reachedStall: Boolean = false

    override fun open(request: HubRequest): HubResponse {
        val lock = Object()
        val stream = object : InputStream() {
            private var served = 0

            /**
             * Blocks the reading thread until the download is cancelled.
             *
             * WHY it cannot return -1 (that is a clean end of stream, which is
             * a different code path) and cannot return a byte (that is
             * progress, which would let the download finish): the only honest
             * simulation of a dead link is to stop responding and let the
             * cancel interrupt arrive. The InterruptedIOException is what makes
             * a stalled read terminate rather than hang the suite.
             */
            @Volatile
            private var closed = false

            override fun close() {
                // Mirrors a real socket: closing the stream from another
                // thread unblocks a reader parked in read().
                closed = true
                synchronized(lock) { lock.notifyAll() }
            }

            private fun block(): Nothing {
                reachedStall = true
                synchronized(lock) {
                    while (!closed) {
                        try {
                            lock.wait(50)
                        } catch (interrupted: InterruptedException) {
                            throw java.io.InterruptedIOException("cancelled")
                        }
                    }
                }
                throw java.io.IOException("stream closed")
            }

            override fun read(): Int {
                if (served >= stallAfterBytes) block()
                val b = payload[served].toInt() and 0xFF
                served++
                return b
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (served >= stallAfterBytes) block()
                val n = minOf(len, stallAfterBytes - served, payload.size - served)
                if (n <= 0) block()
                System.arraycopy(payload, served, b, off, n)
                served += n
                return n
            }
        }
        val headers = mapOf("Content-Length" to payload.size.toString())
        return HubResponse(200, headers, stream, payload.size.toLong())
    }
}

/** A budget with generous limits, so a fit check is never what fails a test. */
class TestBudget(
    private val ram: Long = 8L * 1024 * 1024 * 1024,
    private val disk: Long = 64L * 1024 * 1024 * 1024,
) : DeviceBudget {
    override fun availableRamBytes(): Long = ram
    override fun freeDiskBytes(): Long = disk
    override fun totalRamBytes(): Long = ram
}

fun testRepo(id: String = "bartowski/Qwen2.5-3B-Instruct-GGUF"): HubRepoId =
    (HubRepoId.parse(id) as HubRepoId.Result.Valid).repoId

fun testFile(
    name: String = "Qwen2.5-3B-Instruct-Q4_K_M.gguf",
    bytes: Long = 1_000_000L,
    sha: String? = null,
): HubGgufFile = HubGgufFile(
    repo = testRepo(),
    path = (HubFilePath.parse(name) as HubFilePath.Result.Valid).filePath,
    sizeBytes = bytes,
    sha256 = sha,
    quant = dev.localintelligence.core.hub.GgufQuant.fromFileName(name),
    shardIndex = null,
    shardCount = null,
)

fun tempDir(prefix: String = "li-dl"): File =
    java.nio.file.Files.createTempDirectory(prefix).toFile()

/** The listing JSON the fake client sees; the transport is per-request. */
fun listingJson(vararg entries: Triple<String, Long, String?>): String {
    val siblings = entries.joinToString(",") { (name, size, oid) ->
        if (oid == null) {
            """{"rfilename":"$name","size":$size}"""
        } else {
            """{"rfilename":"$name","size":134,"lfs":{"oid":"$oid","size":$size}}"""
        }
    }
    return """{"id":"bartowski/Qwen2.5-3B-Instruct-GGUF","gated":false,"siblings":[$siblings]}"""
}

/** A transport that answers a listing request and then delegates byte requests. */
class RoutingTransport(
    private val listing: String,
    private val bytes: HubTransport,
) : HubTransport {
    override fun open(request: HubRequest): HubResponse =
        if (request.url.contains("/api/models")) {
            HubResponse(200, mapOf("Content-Type" to "application/json"), ByteArrayInputStream(listing.toByteArray()), listing.length.toLong())
        } else {
            bytes.open(request)
        }
}
