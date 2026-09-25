package dev.localintelligence.core.hub

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * The subset of HuggingFace's `GET /api/models/{id}?blobs=true` response this
 * app needs.
 *
 * ## Why the DTOs are hand-written and `ignoreUnknownKeys`
 *
 * HF's model payload is large and keeps growing: `downloads`, `tags`,
 * `cardData`, `safetensors`, `transformersInfo`, `gguf` and more. Serialising it
 * with a permissive [Json] and reading two fields is what makes this resilient
 * to HF shipping a new field; decoding into a strict DTO would turn every
 * unrelated schema change into a parse failure on a user's phone.
 *
 * Every field the hub actually needs is a nullable with a defined default, so a
 * repo that omits `siblings` yields "no GGUF files", not an exception.
 */
@Serializable
internal data class HubModelResponse(
    val id: String? = null,
    val private: Boolean? = null,
    /**
     * HF types this as a string (`"auto"` / `"manual"`) on the models API but as
     * a boolean on some older endpoints and mirrors, so it is read as a
     * [JsonElement] and interpreted in [isGated] rather than pinned to one type.
     * Declaring it `Boolean?` would make every `"manual"` repo fail to decode,
     * which is precisely the set of repos a phone user most wants to try.
     */
    val gated: JsonElement? = null,
    val disabled: Boolean? = null,
    val downloads: Int? = null,
    val likes: Int? = null,
    val pipeline_tag: String? = null,
    val tags: List<String> = emptyList(),
    val siblings: List<HubSibling> = emptyList(),
) {
    /**
     * HF reports a gate as `gated: false`, `gated: "auto"`, `gated: "manual"`,
     * or the older boolean. A repo that is merely *public* can still be gated
     * under `auto`, which means "accept the licence, no review". Both are the
     * same problem for a phone user: they have to visit the site.
     */
    val isGated: Boolean
        get() {
            val primitive = gated as? JsonPrimitive ?: return false
            val text = primitive.content.trim()
            if (text.equals("true", ignoreCase = true)) return true
            if (text.equals("false", ignoreCase = true)) return false
            return text.isNotEmpty()
        }
}

/** One file in a repo listing. */
@Serializable
internal data class HubSibling(
    val rfilename: String,
    val size: Long? = null,
    val blobId: String? = null,
    val lfs: HubLfsPointer? = null,
) {
    /**
     * The real byte size, preferring the LFS pointer's.
     *
     * WHY prefer LFS: a small GGUF (<10 MB) is a plain git blob and `size` is
     * exact, but every real model file is LFS-backed, and for those `lfs.size`
     * is the payload size while `size` is the size of the *pointer file* —
     * typically 130 bytes. Reading `size` alone would tell a user a 1.9 GB
     * model is 130 bytes and pass the fit check.
     *
     * WHY a size with no `lfs` block is not trusted for a GGUF either: HF
     * routes every real model through LFS, so a non-LFS sibling in a model
     * repo is a blob whose bytes happen to end in `.gguf`.
     */
    val trueSize: Long?
        get() {
            lfs?.size?.let { return it }
            val declared = size ?: return null
            return if (declared < MAX_PLAIN_GIT_BLOB_BYTES) null else declared
        }

    /** HF's LFS OID is a sha256 over the payload. */
    val sha256: String? get() = lfs?.oid

    companion object {
        /**
         * LFS pointer files are ~130 bytes. Anything at or above this without
         * an `lfs` block is a real payload whose `size` is usable; anything
         * below is a plain git blob, not a model.
         */
        const val MAX_PLAIN_GIT_BLOB_BYTES = 1024L
    }
}

/** The LFS pointer embedded in a git blob for a large file. */
@Serializable
internal data class HubLfsPointer(
    val oid: String? = null,
    val size: Long? = null,
    @SerialName("pointerSize") val pointerSize: Long? = null,
)

/**
 * The subset of `GET /api/models?search=...` this app needs.
 *
 * Search is best-effort and NOT part of the required user flow — a user who
 * knows the repo id is faster, and search on a phone over a metered connection
 * is a feature that wants pagination, ranking and debouncing. The endpoint is
 * modelled because the DTO cost is 20 lines and a list screen needs it, not
 * because it is load-bearing.
 */
@Serializable
internal data class HubSearchResponse(
    val models: List<HubSearchHit> = emptyList(),
) {
    companion object {
        /**
         * `ignoreUnknownKeys` is load-bearing here: the search payload embeds a
         * full model object per hit, and those grow every few months.
         *
         * NOTE: HF's `/api/models?search=` returns a BARE JSON ARRAY, not this
         * object. That endpoint is decoded with `ListSerializer` in
         * [HuggingFaceClient.search] against [HubSearchHit] directly; this
         * object DTO describes the `/api/models/{id}` shape only. Decoding the
         * array through an object DTO works only until HF adds a wrapper key,
         * and then it fails as "no results" rather than as a parse error.
         */
        val JSON: Json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
            explicitNulls = false
        }
    }
}


@Serializable
internal data class HubSearchHit(
    val id: String? = null,
    val downloads: Int? = null,
    val likes: Int? = null,
    val pipeline_tag: String? = null,
    val tags: List<String> = emptyList(),
)
