package dev.localintelligence.core.hub

/**
 * Where an optional HuggingFace access token comes from.
 *
 * ## Why this is a function and not a stored string
 *
 * A token is only needed for gated repos, and a phone user who has never
 * downloaded a gated model must be able to use every other feature with no
 * account, no network identity and no stored secret. So the token is a
 * *capability the host app may or may not have*, queried at the moment a
 * request is made, and the hub works identically when it returns null.
 *
 * Keeping the type in :core (pure) is what lets the whole no-auth path be
 * tested on the JVM: the fake in the test suite returns null and every
 * assertion about unauthenticated behaviour still runs.
 *
 * ## The contract implementers must honour
 *
 * - Return null, never an empty string, when there is no token. The client
 *   treats blank as absent, but a store that returns "" is a bug.
 * - Never log the returned value. Not at debug level, not in an error.
 * - Never put the value in a URL. It goes in the `Authorization` header only.
 * - The returned value is a *secret*. Do not copy it into a data class, a
 *   crash report, or a `toString`.
 */
fun interface HubTokenSource {
    /** The current token, or null when the app has none. */
    fun token(): String?

    companion object {
        /** The default: this app has no token and downloads public models. */
        val NONE: HubTokenSource = HubTokenSource { null }

        /** Wraps a fixed value. For tests and for a host app that injected one. */
        fun of(value: String?): HubTokenSource = HubTokenSource { value?.takeIf { it.isNotBlank() } }
    }
}
