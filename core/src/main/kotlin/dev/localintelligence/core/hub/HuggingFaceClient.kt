package dev.localintelligence.core.hub

import dev.localintelligence.core.model.gguf.GgufHeader
import dev.localintelligence.core.model.gguf.GgufLimits
import dev.localintelligence.core.model.gguf.GgufParser
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.IOException
import java.io.InputStream

/**
 * Reads at most [limit] bytes from this stream and stops.
 *
 * WHY it exists: the header probe asks for `Range: bytes=0-4194303`, but a server
 * that ignores the range answers 200 and streams the whole 668 MB model. Without
 * this cap the "cheap probe" would silently become the most expensive request the
 * app makes — the exact outcome the probe exists to prevent.
 */
private fun InputStream.readBounded(limit: Long): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(64 * 1024)
    var total = 0L
    while (total < limit) {
        val want = minOf(buffer.size.toLong(), limit - total).toInt()
        val read = read(buffer, 0, want)
        if (read <= 0) break
        out.write(buffer, 0, read)
        total += read
    }
    return out.toByteArray()
}

/**
 * Talks to the HuggingFace REST API: list a repo's GGUF files, size them, and
 * decide which one this device should get.
 *
 * ## Why the client is pure and takes a [HubTransport]
 *
 * No `HttpURLConnection` import appears in this file. The client builds URLs,
 * parses JSON, and maps statuses; all of that is logic worth testing and none
 * of it needs a socket. The socket lives in [UrlConnectionTransport]. A suite
 * of ~20 cases covering gated repos, mixed quant listings, shard sets and 429s
 * runs in milliseconds and cannot flake, which is what makes it worth having.
 *
 * ## Why `resolve/main` and not the API host for downloads
 *
 * HF's API is at `huggingface.co/api/...` but file bytes come from
 * `huggingface.co/{repo}/resolve/{rev}/{path}`, which 302s to a CDN. Putting
 * the CDN in a [HubTransport] that follows redirects is a transport concern;
 * the client only ever builds the first URL.
 */
class HuggingFaceClient(
    private val transport: HubTransport,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val json: Json = HubSearchResponse.JSON,
    /**
     * The optional read token for gated repos.
     *
     * WHY the client holds the source instead of every call site passing a
     * token: forgetting the argument is silent. The request goes out with no
     * `Authorization` header, HF answers 401, and the user is told the repo is
     * gated — which is true, and useless, because they already added a token
     * and it never got sent. One source, read at request time, cannot be
     * forgotten.
     *
     * The per-call `token` parameter still exists and wins, because that is
     * what the tests use to assert header behaviour.
     */
    private val tokenSource: HubTokenSource = HubTokenSource.NONE,
) {

    private fun tokenOrNull(explicit: String?): String? =
        (explicit ?: tokenSource.token())?.takeIf { it.isNotBlank() }

    /**
     * Fetches the GGUF files in a repo, sorted smallest first.
     *
     * @param onAuthError distinguishes "the repo needs a token or a licence
     *   click" from "the repo does not exist", which HF's 401/403/404 split
     *   makes ambiguous without the `X-Error-Code` header.
     */
    fun listGgufFiles(repo: HubRepoId, token: String? = null): List<HubGgufFile> {
        val request = HubRequest(
            url = "$baseUrl/api/models/${repo.id}?blobs=true",
            authToken = tokenOrNull(token),
        )
        val response = try {
            transport.open(request)
        } catch (e: IOException) {
            throw mapIoException(e)
        }

        if (response.status !in 200..299) {
            response.body?.close()
            throw HubError.fromStatus(
                status = response.status,
                repo = repo.id,
                file = null,
                errorCode = response.header("X-Error-Code"),
                retryAfterSeconds = response.header("Retry-After")?.toLongOrNull(),
            )
        }
        // WHY the body is consumed inside a `use` and the close is NOT hoisted
        // above the status check: reading a closed HttpURLConnection stream
        // throws `IOException: stream is closed`, so a `close()` before the read
        // makes every successful listing fail. Verified against the live API by
        // running this class on a desktop JVM: before this change, all four of
        // A/C/D/E in the probe threw that exact exception and no repo ever
        // listed a single file, so the Hub screen's Download button could never
        // be reached for ANY repo. The status check only needs headers, so the
        // body is closed on that path and never read.
        val text = response.body?.use { it.readBytes() }?.decodeToString()
            ?: throw HubError.RepoNotFound(repo.id)
        val parsed = runCatching { json.decodeFromString(HubModelResponse.serializer(), text) }
            .getOrElse { throw HubError.UnexpectedStatus(response.status) }
        // A gated repo answers 200 with `"gated":"manual"` and a full sibling
        // list, so without this the picker offers a download that cannot
        // succeed: the resolve endpoint answers 401 + X-Error-Code: GatedRepo
        // (verified live for google/gemma-7b and meta-llama/Llama-3.2-1B-Instruct).
        // Refusing here names the real fix — accept the licence, add a token —
        // instead of letting the user start a 34 GB download that dies at byte 0.
        if (parsed.isGated) throw HubError.GatedRepo(repo.id)
        return toGgufFiles(repo, parsed)
    }

    /**
     * Best-effort search. Never throws: a search that fails must leave the
     * browse list empty, not take the screen down, because the primary flow is
     * "user knows the repo id".
     */
    fun search(query: String, limit: Int = 20, token: String? = null): List<HubSearchHitPublic> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()
        // WHY filter on the gguf tag: an unfiltered search for "qwen3 0.6b"
        // returns safetensors repos the app cannot use at all, and every one of
        // those is a dead row for a user who cannot read safetensors.
        val url = "$baseUrl/api/models?search=${encodeQuery(trimmed)}&filter=gguf&limit=$limit&sort=downloads&direction=-1"
        return try {
            val response = transport.open(
                HubRequest(url, authToken = tokenOrNull(token)),
            )
            if (response.status !in 200..299) {
                response.body?.close()
                return emptyList()
            }
            val body = response.body ?: return emptyList()
            val parsed = json.decodeFromString(
                ListSerializer(HubSearchHit.serializer()),
                body.readBytes().decodeToString(),
            )
            parsed.mapNotNull { hit ->
                val id = hit.id ?: return@mapNotNull null
                val parsedId = HubRepoId.parse(id)
                if (parsedId is HubRepoId.Result.Valid) {
                    HubSearchHitPublic(parsedId.repoId, hit.downloads ?: 0, hit.likes ?: 0, hit.pipeline_tag)
                } else {
                    null
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Fetches a bounded prefix of [file] and parses the GGUF header out of it.
     *
     * ## Why this exists when the downloader is 30 lines away
     *
     * Because the whole product decision this feature makes — "will this model
     * work on this phone" — is made *before* the bytes arrive, and a decision
     * made from a file name is a guess. [PreDownloadMemoryModel] has to invent
     * `block_count`, `attention.head_count_kv` and `key_length` from a
     * parameter-count proxy because a GGUF file name contains none of them.
     * [ModelMemoryEstimator] computes the same terms from the actual tensor
     * table and is exact.
     *
     * A GGUF header is a few hundred KiB to a couple of MiB at the front of the
     * file, and HF honours a closed byte range (verified live: `Range: bytes=0-N`
     * answers 206 with exactly N bytes and a `Content-Range` naming the total).
     * So the exact answer is available for the price of a 4 MiB range request —
     * 0.6% of the 668 MB the user is about to spend, and the alternative is
     * guessing about a decision that costs them the whole download.
     *
     * Returns null on ANY failure — offline, 401, a truncated prefix, a
     * non-GGUF body. A null is not an error: the caller falls back to
     * [PreDownloadMemoryModel], which is conservative in the safe direction.
     * Making the probe best-effort is what keeps a flaky network from blocking a
     * download the user is entitled to.
     */
    fun probeHeader(
        file: HubGgufFile,
        token: String? = null,
        prefixBytes: Long = HEADER_PROBE_BYTES,
    ): GgufHeader? {
        val response = try {
            transport.open(
                HubRequest(
                    url = downloadUrl(file.repo, file.path),
                    rangeFrom = 0L,
                    rangeTo = (prefixBytes - 1).coerceAtLeast(0L),
                    authToken = tokenOrNull(token),
                ),
            )
        } catch (_: IOException) {
            return null
        }
        if (response.status !in 200..299) {
            response.body?.close()
            return null
        }
        val bytes = try {
            response.body?.use { it.readBounded(prefixBytes) }
        } catch (_: IOException) {
            null
        } ?: return null
        // A 200 for a range request means the server ignored the Range and is
        // streaming the entire model. `readBounded` caps it, so this stays a
        // 4 MiB read rather than a 668 MB one, and the parse below then sees a
        // valid header anyway.
        return runCatching {
            GgufParser.parse(bytes, GgufLimits(), GgufParser.TruncationPolicy.PARTIAL)
        }.getOrNull()
    }

    /**
     * Builds the byte URL for a file.
     *
     * WHY `?download=true`: without it HF serves the file with
     * `Content-Disposition: inline` and a text/plain content type for some
     * paths, which some Android HTTP stacks will happily hand to a text decoder.
     * The flag also pins the behaviour to "raw bytes".
     */
    fun downloadUrl(repo: HubRepoId, path: HubFilePath, revision: String = "main"): String {
        // The revision is interpolated, so it must be a plain token or it is a
        // path-injection vector into a URL that also carries a validated path.
        require(revision.isNotBlank() && revision.all { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' }) {
            "revision must be a plain token"
        }
        val encodedPath = path.segments.joinToString("/") { encodePathSegment(it) }
        return "$baseUrl/${repo.id}/resolve/$revision/$encodedPath?download=true"
    }

    /**
     * Filters a listing down to loadable, non-sharded GGUFs, sorted smallest
     * first.
     *
     * WHY shards are filtered out rather than offered: `model-00001-of-00003.gguf`
     * is not a model. Offering it produces a download that completes and then
     * fails inside `llama_model_load_from_file` with no indication that the other
     * two files are missing. The set of sharded single-model repos is small
     * enough that declining them is the honest behaviour; a sharded download is
     * a future feature with its own size and resume story.
     */
    fun loadableFiles(all: List<HubGgufFile>): List<HubGgufFile> =
        all.filterNot { it.isSharded }.sortedBy { it.sizeBytes }

    /**
     * Picks the largest quant that fits the RAM budget.
     *
     * WHY "largest that fits" rather than "smallest that fits": quantisation is
     * a quality ladder and every rung is usable, so the user's interest is
     * maximum quality inside their budget. Returning the smallest would make
     * this app systematically deliver worse models than the hardware allows.
     *
     * Returns null when nothing fits, which the caller turns into the
     * rejection path.
     *
     * @param candidates defaults to every non-sharded file.
     */
    fun chooseBestFitting(
        candidates: List<HubGgufFile>,
        contextLength: Int,
        budget: DeviceBudget,
        model: MemoryModel = PreDownloadMemoryModel,
    ): HubGgufFile? {
        val loadable = candidates.filterNot { it.isSharded }
        if (loadable.isEmpty()) return null
        // Best first, so the first that fits is the answer.
        val ordered = loadable.sortedWith(
            compareByDescending<HubGgufFile> { it.quant?.rank ?: 0 }.thenByDescending { it.sizeBytes },
        )
        for (candidate in ordered) {
            val fit = FitGate.ramFit(
                fileBytes = candidate.sizeBytes,
                quant = candidate.quant,
                contextLength = contextLength,
                budget = budget,
                model = model,
                parameterCount = parseParameterCount(candidate.fileName),
            )
            if (fit.fits) return candidate
        }
        return null
    }

    /**
     * Builds the plan shown to the user before they commit to a download.
     *
     * This is the single place the RAM gate, the disk gate and the size label
     * come together, so a test can assert all three from one call and the UI
     * has exactly one function to render.
     */
    fun plan(
        file: HubGgufFile,
        contextLength: Int = PreDownloadMemoryModel.DEFAULT_CONTEXT_LENGTH,
        budget: DeviceBudget,
        alreadyOnDiskBytes: Long = 0L,
        model: MemoryModel = PreDownloadMemoryModel,
        /**
         * The real header, when one could be fetched.
         *
         * WHY this is a parameter rather than something [plan] fetches itself:
         * [plan] is called on every selection change, and a network round trip
         * inside a function that is also called from the download path would make
         * the pre-flight decision non-deterministic and slow. The caller
         * ([HuggingFaceClient.probeHeader]) fetches once per file and hands the
         * result in, so this stays a pure function of its arguments.
         *
         * A null header means "estimate from the name", which is
         * [PreDownloadMemoryModel] and is conservative in the safe direction.
         */
        header: GgufHeader? = null,
    ): DownloadPlan {
        val effectiveModel = header?.let { GgufMemoryModel.from(it) } ?: model
        val ram = FitGate.ramFit(
            fileBytes = file.sizeBytes,
            quant = file.quant,
            contextLength = contextLength,
            budget = budget,
            model = effectiveModel,
            parameterCount = header?.let { null } ?: parseParameterCount(file.fileName),
        )
        val stillNeeded = (file.sizeBytes - alreadyOnDiskBytes).coerceAtLeast(0L)
        val headroom = maxOf(
            (file.sizeBytes * FitGate.DISK_HEADROOM_RATIO).toLong(),
            FitGate.DISK_HEADROOM_FLOOR,
        )
        val required = stillNeeded + headroom
        val free = budget.freeDiskBytes()
        // RAM first: telling a user to free 3 GB of storage for a model their
        // 4 GB of RAM cannot load is a worse answer than the RAM one.
        val reject = when {
            !ram.fits -> HubError.InvalidInput(ram.explanation)
            required > free -> HubError.InsufficientStorage(required, free)
            else -> null
        }
        return DownloadPlan(
            file = file,
            ram = ram,
            requiredDiskBytes = required,
            freeDiskBytes = free,
            reject = reject,
        )
    }

    /**
     * Maps a transport failure to a user-facing error.
     *
     * WHY the message set is so small: [HubTransport] throws only when no
     * response was obtained, so every case here is "we never got an answer".
     * The user-facing decision is binary — retry, or check the network — and a
     * long message would only be feeding noise to a 1B model.
     */
    private fun mapIoException(e: IOException): HubError {
        val message = (e.message ?: "").lowercase()
        return when {
            message.contains("cancel") -> HubError.Cancelled
            message.contains("timeout") || message.contains("timed out") -> HubError.ConnectionLost(e.message ?: "timeout")
            else -> HubError.NoNetwork
        }
    }

    /**
     * Percent-encodes one path segment.
     *
     * WHY encode at all when [HubFilePath] already restricts the charset: the
     * charset allows `+`, spaces, `(` and `)`, all of which some proxy stacks
     * decode differently from HF. Encoding makes the URL mean one thing.
     */
    internal fun encodePathSegment(segment: String): String {
        val out = StringBuilder(segment.length)
        for (byte in segment.toByteArray(Charsets.UTF_8)) {
            val c = byte.toInt().toChar()
            if (c.isLetterOrDigit() && c.code < 128) {
                out.append(c)
            } else if (c == '-' || c == '_' || c == '.' || c == '~') {
                out.append(c)
            } else {
                out.append('%').append("%02X".format(byte.toInt() and 0xFF))
            }
        }
        return out.toString()
    }

    private fun encodeQuery(value: String): String =
        encodePathSegment(value).replace("+", "%2B")

    private fun toGgufFiles(repo: HubRepoId, response: HubModelResponse): List<HubGgufFile> {
        val out = ArrayList<HubGgufFile>(response.siblings.size)
        for (sibling in response.siblings) {
            if (!sibling.rfilename.endsWith(".gguf", ignoreCase = true)) continue
            val path = (HubFilePath.parse(sibling.rfilename) as? HubFilePath.Result.Valid)?.filePath ?: continue
            val size = sibling.trueSize
            // A file with no declared size cannot be shown to a user before
            // downloading, and "download it and find out" is exactly the failure
            // this feature exists to prevent. It is dropped, not defaulted to 0.
            if (size == null || size <= 0L) continue
            val (shardIndex, shardCount) = parseShard(path.fileName)
            out += HubGgufFile(
                repo = repo,
                path = path,
                sizeBytes = size,
                sha256 = sibling.sha256,
                quant = GgufQuant.fromFileName(path.fileName),
                shardIndex = shardIndex,
                shardCount = shardCount,
            )
        }
        return out.sortedBy { it.sizeBytes }
    }

    /** A search hit the UI can render, with the id already validated. */
    data class HubSearchHitPublic(
        val repoId: HubRepoId,
        val downloads: Int,
        val likes: Int,
        val pipelineTag: String?,
    )

    companion object {
        const val DEFAULT_BASE_URL = "https://huggingface.co"

        /**
         * How many leading bytes the header probe asks for.
         *
         * WHY 4 MiB, and the measurement behind it: parsing the real header of a
         * locally cached TinyLlama-1.1B-Chat Q4_K_M shows it spans **1,709,436
         * bytes** — 0.26% of its own 668,788,096-byte file. Probing 64 KiB, 256 KiB
         * and 1 MiB all truncate inside the tokenizer's vocabulary and return a
         * header with **0 tensors**, which is the failure mode that matters: the
         * estimator then falls back to `FILE_SIZE` basis and its weights figure
         * becomes the size of the probe, not the size of the model. 4 MiB clears
         * the real 1.7 MB header with room for a 151936-token vocabulary, which
         * is the largest any current release ships.
         */
        const val HEADER_PROBE_BYTES: Long = 4L * 1024 * 1024

        /**
         * Reads a parameter count out of a file name, e.g. `Qwen3-4B-Instruct`.
         *
         * WHY bother: the KV-cache term in [PreDownloadMemoryModel] is driven by
         * parameter count, and the count is in the name for essentially every
         * published GGUF. Getting it right moves the KV estimate from a 7B guess
         * to the actual model.
         */
        fun parseParameterCount(fileName: String): Long? {
            val match = Regex("(?i)(\\d+(?:\\.\\d+)?)\\s*([BM])\\b").find(fileName) ?: return null
            val value = match.groupValues[1].toDoubleOrNull() ?: return null
            val multiplier = if (match.groupValues[2].uppercase() == "B") 1e9 else 1e6
            return (value * multiplier).toLong()
        }
    }
}
