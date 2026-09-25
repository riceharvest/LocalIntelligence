package dev.localintelligence.core.permission

/**
 * Turns a caught platform failure into a permission decision.
 *
 * WHY this sits in :core even though nothing here mentions `android.*`: the
 * mapping from "a permission-shaped exception was thrown" to "what state is
 * this, and what do we tell the model" is a decision, and decisions live in
 * :core. The catching lives in :android. This keeps the interesting branch —
 * which denial is recoverable, and what the model is told — testable on a JVM
 * with no device, and keeps `:core` free of the platform.
 *
 * The input is a class name and a message *as strings* on purpose: that is
 * enough to classify, and it means this file needs no import that CI would
 * (correctly) reject.
 */
object PlatformDenial {

    /**
     * Platform exception class names that mean "the system refused this for
     * lack of a permission".
     *
     * WHY match on the simple name rather than the FQCN: the same exception
     * arrives as `java.lang.SecurityException` from the framework and from some
     * OEM wrappers, and the prefix is noise. Matching a short allowlist rather
     * than a substring also means a random exception whose message happens to
     * contain "permission" is NOT misread as a denial.
     */
    private val DENIAL_TYPES = setOf(
        "SecurityException",
        "PermissionDeniedException",
    )

    /**
     * A classified platform denial: the state it implies, plus whether it is
     * worth retrying. No raw message — the caller must not have one available
     * to leak by accident.
     */
    data class Classified(
        val isPermissionDenial: Boolean,
        val state: PermissionState,
    ) {
        companion object {
            /** Not a permission problem at all: a bug or an unrelated failure. */
            val NOT_A_DENIAL = Classified(false, PermissionState.NOT_APPLICABLE)
        }
    }

    /**
     * Classify a throwable by its simple class name.
     *
     * [askedAlready] carries the one fact the exception itself cannot: whether
     * the agent has already prompted for this permission this session. Two
     * `SecurityException`s mean very different things depending on that, and
     * getting it wrong is precisely the retry loop this package prevents — so
     * it is an explicit argument rather than something inferred.
     */
    fun classify(
        exceptionSimpleName: String?,
        askedAlready: Boolean = false,
    ): Classified {
        val name = exceptionSimpleName?.substringAfterLast('.').orEmpty()
        if (name !in DENIAL_TYPES) return Classified.NOT_A_DENIAL
        // A denial after a prompt, with no further prompt possible, is the
        // "never ask again" case. Before any prompt, it is the soft one.
        val state =
            if (askedAlready) PermissionState.DENIED_PERMANENTLY
            else PermissionState.DENIED
        return Classified(isPermissionDenial = true, state = state)
    }

    /**
     * Human phrasing for a denial, reused by callers that caught an exception
     * rather than consulting the broker.
     *
     * Goes through the same sanitiser as every other observation, so a caller
     * cannot accidentally skip the no-class-name rule by using this path.
     */
    fun observationFor(state: PermissionState, capabilityLabel: String): String =
        PermissionObservation.forState(state, capabilityLabel)
}
