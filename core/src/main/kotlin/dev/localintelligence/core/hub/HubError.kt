package dev.localintelligence.core.hub

/**
 * Every way a HuggingFace download can fail, each carrying a message a human
 * can act on.
 *
 * ## Why the message is a field and not a `Throwable`
 *
 * Per `docs/tool-contract.md`, whatever is handed back to a small local LLM
 * becomes its next prompt. A raw `HttpURLConnection` message on a 401 from
 * `huggingface.co` is a 200-character URL plus a stack trace, and a 1B model
 * given that will either loop on it or start inventing follow-up downloads. The
 * message here is a *decision already made*: at the point an [HubError] exists,
 * the hub already knows that "gated repo" means "you need to accept the licence
 * on the website, or add a token", and that is the only useful thing to say.
 *
 * Nothing in this file carries a cause, a stack trace, or a URL. That is
 * deliberate: the type system makes it impossible to accidentally format one
 * into an observation.
 */
sealed class HubError(override val message: String) : Exception(message) {

    /**
     * The repo id does not exist, or exists and is private.
     *
     * HF returns 404 for both, deliberately, so a private repo is not
     * discoverable by probing. The message therefore mentions both, because
     * telling a user their private repo "does not exist" is technically true
     * and practically useless.
     */
    data class RepoNotFound(val repo: String) : HubError(
        "No repository $repo. Check the owner and name, and note that private repos look missing too.",
    )

    /** The repo exists but no GGUF file matched, or the file is gone from it. */
    data class FileNotFound(val repo: String, val file: String) : HubError(
        "No file $file in $repo. The repo may have renamed it, or it is a different revision.",
    )

    /**
     * HF gates Llama, Mistral and Gemma-adjacent repos behind a licence click.
     *
     * HF answers a gated repo with 401 (or 403 when the token lacks the
     * accepted licence) and an `X-Error-Code: GatedRepo` header. Both statuses
     * land here, because from the user's side they are one problem with two
     * fixes and listing them separately would just be noise.
     */
    data class GatedRepo(val repo: String) : HubError(
        "$repo is gated. Open it on huggingface.co, accept the licence, then add a read token in settings.",
    )

    /** A token was supplied but HF rejected it, or none was supplied where one is needed. */
    data class Unauthorized(val repo: String) : HubError(
        "HuggingFace rejected the access token for $repo. Create a read token at huggingface.co/settings/tokens.",
    )

    /** HTTP 429. [retryAfterSeconds] comes from the `Retry-After` header when present. */
    data class RateLimited(val retryAfterSeconds: Long?) : HubError(
        buildString {
            append("HuggingFace is rate limiting requests.")
            if (retryAfterSeconds != null) append(" Try again in ${retryAfterSeconds}s.")
            else append(" Try again in a minute.")
        },
    )

    /** HTTP 5xx. */
    data class ServerUnavailable(val status: Int) : HubError(
        "HuggingFace returned server error $status. Try again shortly.",
    )

    /** Any other unexpected status. */
    data class UnexpectedStatus(val status: Int) : HubError(
        "HuggingFace returned unexpected status $status.",
    )

    /** The device has no usable network path. */
    data object NoNetwork : HubError(
        "No network connection. Connect to Wi-Fi or mobile data and try again.",
    )

    /** The transfer dropped mid-stream. Retryable: the partial file is kept for resume. */
    data class ConnectionLost(val detail: String) : HubError(
        "Connection dropped while downloading. Tap retry to resume where it stopped.",
    )

    /** Not enough free space on the volume holding the models directory. */
    data class InsufficientStorage(val neededBytes: Long, val availableBytes: Long) : HubError(
        "Needs ${formatBytes(neededBytes)} of free storage, ${formatBytes(availableBytes)} available. " +
            "Delete a model and try again.",
    )

    /**
     * The bytes on disk do not match the SHA HF published in its LFS pointer.
     *
     * The file is discarded rather than kept, because a GGUF with a wrong
     * checksum fails deep inside `llama_model_load_from_file` with a native
     * error that names no file, and the user has no way to connect that back to
     * this download.
     */
    data class ChecksumMismatch(val file: String) : HubError(
        "Checksum mismatch for $file. The download was corrupted and has been deleted. Try again.",
    )

    /**
     * The user typed something that is not a repo id or not a file path.
     *
     * This is the one error that is *not* about the network, and it is the one
     * a small model is most likely to be able to fix on its own, so its message
     * is the most concrete of the set.
     */
    data class InvalidInput(override val message: String) : HubError(message)

    /** The user cancelled. Not a failure, and the message says so plainly. */
    data object Cancelled : HubError("Download cancelled.")

    /**
     * True when retrying the same request unchanged could plausibly succeed.
     *
     * Lives on the type rather than in a companion so a UI can ask any error
     * whether to show a retry button without knowing its variant.
     */
    val isRetryable: Boolean
        get() = when (this) {
            is RateLimited, is ServerUnavailable, is ConnectionLost, is NoNetwork, is UnexpectedStatus -> true
            else -> false
        }

    companion object {
        /**
         * Maps an HTTP status plus optional `X-Error-Code` to an [HubError].
         *
         * Lives here rather than in the Android layer so the mapping is covered
         * by the pure-JVM suite, which is the only suite that runs in CI
         * without a device.
         *
         * The status-to-cause order is the tricky part. HF answers a gated repo
         * 401, so a naive `401 -> unauthorized` mapping would tell a user to
         * mint a token for a repo that a token cannot open until they have
         * clicked a licence button. Checking the `X-Error-Code` header first is
         * what keeps those two apart.
         */
        fun fromStatus(
            status: Int,
            repo: String,
            file: String?,
            errorCode: String? = null,
            retryAfterSeconds: Long? = null,
        ): HubError {
            val code = errorCode?.trim()?.uppercase()
            if (code == "GATEDREPO" || code == "GATED" || code == "REPOGATED") {
                return GatedRepo(repo)
            }
            return when {
                // A bare 401/403 is NOT evidence of gating. Verified against the
                // live API: a repo that does not exist answers 401 with the body
                // {"error":"Invalid username or password."} and NO X-Error-Code,
                // while a genuinely gated repo answers the *metadata* endpoint
                // 200 with "gated":"manual". Mapping 401 -> GatedRepo therefore
                // told a user who mistyped a repo name to go accept a licence
                // page that does not exist. Only an explicit X-Error-Code is
                // trusted; otherwise 401/403 means we could not authenticate,
                // which for an anonymous user is indistinguishable from absent.
                status == 401 || status == 403 ->
                    if (file != null) FileNotFound(repo, file) else RepoNotFound(repo)
                status == 404 -> if (file != null) FileNotFound(repo, file) else RepoNotFound(repo)
                status == 429 -> RateLimited(retryAfterSeconds)
                status in 500..599 -> ServerUnavailable(status)
                else -> UnexpectedStatus(status)
            }
        }

    }
}

/**
 * Formats a byte count the way a storage picker shows it.
 *
 * WHY decimal units, not binary: this string goes to a user who is comparing it
 * against what their phone's storage screen claims, and every phone storage
 * screen uses decimal. A "1.9 GB" that means 1024-based bytes is a number the
 * user will notice is wrong, and then they will not trust the fit check either.
 */
fun formatBytes(bytes: Long): String {
    if (bytes < 1000) return "$bytes B"
    val units = listOf("kB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unitIndex = -1
    while (value >= 1000 && unitIndex < units.lastIndex) {
        value /= 1000.0
        unitIndex++
    }
    // One decimal below 10, none above: "1.9 GB" is useful, "132.47 GB" is noise
    // for a number that is itself approximate.
    val rounded = if (value < 10.0) String.format("%.1f", value) else String.format("%.0f", value)
    return "$rounded ${units[unitIndex]}"
}
