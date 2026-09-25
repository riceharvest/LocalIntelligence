package dev.localintelligence.core.hub

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A [HubTransport] that answers from a fixed table, with no socket anywhere.
 *
 * Every test in the suite uses this or [FailingTransport], so the whole
 * `:core:hub` suite runs offline and deterministically. That is the point: a
 * test that needs HF to be up is a test that gets skipped, and a skipped test
 * for the gated-repo message is a shipped bug.
 */
class FakeTransport(
    private val handler: (HubRequest) -> FakeResponse,
) : HubTransport {
    val requests = mutableListOf<HubRequest>()

    override fun open(request: HubRequest): HubResponse {
        requests += request
        val result = handler(request)
        return result.toHubResponse()
    }

    class FakeResponse(
        val status: Int,
        val body: String? = null,
        val headers: Map<String, String> = emptyMap(),
        val rangeFrom: Long? = null,
        val bodyOverride: ByteArray? = null,
    ) {
        fun toHubResponse(): HubResponse {
            val allHeaders = HashMap(headers)
            if (rangeFrom != null) {
                val declaredLength = bodyOverride?.size?.toLong() ?: body?.length?.toLong() ?: 0L
                val end = rangeFrom + declaredLength - 1
                allHeaders["Content-Range"] = "bytes $rangeFrom-$end/${rangeFrom + declaredLength}"
                allHeaders["Content-Length"] = declaredLength.toString()
            }
            val stream = if (bodyOverride != null) {
                bodyOverride.inputStream()
            } else {
                body?.byteInputStream()
            }
            return HubResponse(status, allHeaders, stream, stream?.available()?.toLong() ?: -1L)
        }
    }

    companion object {
        fun json(body: String, status: Int = 200, headers: Map<String, String> = emptyMap()) =
            FakeResponse(status, body, mapOf("Content-Type" to "application/json") + headers)
    }
}

/** Throws, the way a dead radio does. */
class FailingTransport(private val error: java.io.IOException) : HubTransport {
    override fun open(request: HubRequest): HubResponse = throw error
}

/** A fixed budget, so fit decisions are assertions and not host properties. */
class FakeBudget(
    private val availableRam: Long,
    private val freeDisk: Long,
    private val totalRam: Long = availableRam * 2,
) : DeviceBudget {
    override fun availableRamBytes(): Long = availableRam
    override fun freeDiskBytes(): Long = freeDisk
    override fun totalRamBytes(): Long = totalRam
}

/**
 * Recorded HF responses.
 *
 * WRITTEN BY HAND from the documented shape of `GET /api/models/{id}?blobs=true`.
 * They are deliberately not trimmed of realism: the `lfs` pointer beside a
 * plain `size`, the `gated` string variants, and a sibling with no size at all
 * are all shapes the real endpoint returns, and each one has a branch in the
 * client that would otherwise go untested.
 */
object Fixtures {

    /**
     * A realistic mixed-quant repo. Note:
     * - `lfs.size` is 1.9 GB while `size` is 134 (the pointer file). Reading
     *   `size` alone would pass a fit check for a 134-byte model.
     * - one file is sharded and must be filtered out of the loadable list
     * - one non-GGUF sibling must be ignored
     * - one GGUF has no size at all and must be dropped
     * - one GGUF is in a subfolder, as TheBloke repos really are
     */
    val MIXED_QUANT_REPO = """
    {
      "_id": "65f1a2b3c4d5e6f7a8b9c0d1",
      "id": "bartowski/Qwen2.5-3B-Instruct-GGUF",
      "gated": false,
      "private": false,
      "downloads": 184320,
      "likes": 412,
      "pipeline_tag": "text-generation",
      "tags": ["gguf", "qwen2", "text-generation"],
      "siblings": [
        {
          "rfilename": "README.md",
          "size": 4211
        },
        {
          "rfilename": "Qwen2.5-3B-Instruct-Q2_K.gguf",
          "size": 134,
          "blobId": "aa11bb22",
          "lfs": { "oid": "1111111111111111111111111111111111111111111111111111111111111111", "size": 1123021000, "pointerSize": 134 }
        },
        {
          "rfilename": "Qwen2.5-3B-Instruct-Q4_K_M.gguf",
          "size": 134,
          "blobId": "cc33dd44",
          "lfs": { "oid": "2222222222222222222222222222222222222222222222222222222222222222", "size": 1928000000, "pointerSize": 134 }
        },
        {
          "rfilename": "Qwen2.5-3B-Instruct-Q8_0.gguf",
          "size": 134,
          "blobId": "ee55ff66",
          "lfs": { "oid": "3333333333333333333333333333333333333333333333333333333333333333", "size": 3284000000, "pointerSize": 134 }
        },
        {
          "rfilename": "Qwen2.5-70B-Instruct-Q4_K_M-00001-of-00009.gguf",
          "size": 134,
          "lfs": { "oid": "4444444444444444444444444444444444444444444444444444444444444444", "size": 4300000000, "pointerSize": 134 }
        },
        {
          "rfilename": "q4_k_m/Qwen2.5-3B-Instruct-Q4_K_M.gguf",
          "size": 134,
          "lfs": { "oid": "5555555555555555555555555555555555555555555555555555555555555555", "size": 1928000000, "pointerSize": 134 }
        },
        {
          "rfilename": "Qwen2.5-3B-Instruct-F16.gguf",
          "size": 134
        },
        {
          "rfilename": "config.json",
          "size": 812
        }
      ]
    }
    """.trimIndent()

    /** A gated repo: HF's `gated` is a string ("auto" / "manual"), not a bool. */
    val GATED_REPO = """
    {
      "id": "meta-llama/Llama-3.2-1B-Instruct-GGUF",
      "gated": "manual",
      "private": false,
      "siblings": [
        { "rfilename": "Llama-3.2-1B-Instruct-Q4_K_M.gguf", "size": 134,
          "lfs": { "oid": "6666666666666666666666666666666666666666666666666666666666666666", "size": 1129000000 } }
      ]
    }
    """.trimIndent()

    /** A repo that exists but contains no GGUF at all. */
    val NO_GGUF_REPO = """
    { "id": "org/safetensors-only", "gated": false,
      "siblings": [ { "rfilename": "model.safetensors", "size": 4096 },
                    { "rfilename": "config.json", "size": 512 } ] }
    """.trimIndent()

    val SEARCH_RESULTS = """
    [
      { "id": "Qwen/Qwen3-0.6B-GGUF", "downloads": 402113, "likes": 903,
        "tags": ["gguf"], "_id": "x" },
      { "id": "unsorted--org/Model-GGUF", "downloads": 12, "likes": 1 },
      { "id": "gated-org/Model-GGUF", "gated": "manual", "downloads": 5 },
      { "id": "../escape/attempt", "downloads": 1 },
      { "downloads": 99 }
    ]
    """.trimIndent()

    val JSON: Json = HubSearchResponse.JSON
}
