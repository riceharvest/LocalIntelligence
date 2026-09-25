package dev.localintelligence.core.permission

/**
 * The four states a permission can be in, as the agent must reason about them.
 *
 * WHY four and not two: Android collapses "the user tapped Deny" and "the user
 * tapped Deny and then ticked Don't ask again" into the same answer to
 * `checkSelfPermission`. The difference decides the agent's entire next move.
 * A soft [DENIED] is resolved by showing one more dialog. A [DENIED_PERMANENTLY]
 * is resolved by the user opening system settings — no in-app prompt will ever
 * appear again. An agent that cannot tell them apart retries until the loop
 * detector or the battery gives out, which is the exact failure this package
 * exists to remove.
 *
 * This enum lives in :core because the *decision* is agent logic and must be
 * testable on the JVM. Only the raw reads of that state need Android.
 */
enum class PermissionState {
    /** The user granted it. Execute. */
    GRANTED,

    /**
     * Not granted, but asking again can still change the answer: either the
     * prompt has not been shown yet, or the user denied once and a rationale is
     * appropriate. Asking again is legitimate, and asking a bounded number of
     * times is how this state is resolved.
     */
    DENIED,

    /**
     * Not granted and the system will no longer show a prompt — the user chose
     * "Don't ask again", or the app was background-restricted. Only system
     * settings can recover it.
     *
     * NEVER retryable. An agent that treats this as [DENIED] is the loop bug.
     */
    DENIED_PERMANENTLY,

    /**
     * The permission does not apply to this app or this device: not declared in
     * the manifest, or a grant that does not exist on this API level.
     *
     * Distinct from [DENIED] because asking changes nothing. A tool that needs
     * it is unavailable forever, and no dialog will ever help.
     */
    NOT_APPLICABLE,
    ;

    /**
     * Whether a tool guarded by this permission may run.
     *
     * [NOT_APPLICABLE] blocks the tool but is not the user's fault and is not
     * retryable, so it is grouped with the blocking states rather than with
     * [GRANTED] — a tool whose permission is inapplicable cannot do its job.
     */
    val allowsExecution: Boolean
        get() = this == GRANTED

    /**
     * Whether re-requesting can plausibly change the outcome.
     *
     * Only [DENIED] qualifies. This single property is the whole point of the
     * enum: a caller that gates on it cannot loop on a permission the system has
     * stopped asking about.
     */
    val isRetryable: Boolean
        get() = this == DENIED

    /**
     * Whether recovery requires the user to leave the app and change a system
     * setting. The observation must then say so, because the model cannot fix
     * this itself and will otherwise keep asking the agent to try.
     */
    val requiresUserInSettings: Boolean
        get() = this == DENIED_PERMANENTLY || this == NOT_APPLICABLE

    /** Whether the state, on its own, should stop the tool from running. */
    val blocksExecution: Boolean
        get() = !allowsExecution
}
