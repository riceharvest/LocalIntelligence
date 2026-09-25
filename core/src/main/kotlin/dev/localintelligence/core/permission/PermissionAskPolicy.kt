package dev.localintelligence.core.permission

/**
 * Decides whether the agent may prompt the user again for a permission.
 *
 * WHY a separate object: every tool in the repo wants to ask for its own
 * permission, and left alone each one invents a rule. Four tools, four dialog
 * policies, and a user who taps Allow four times. The policy is the thing that
 * stops the loop, so it has to be owned by one place and be answerable in a
 * unit test.
 *
 * WHY counting attempts and not time: a wall-clock cooldown is untestable (a
 * test either sleeps or fakes a clock), non-deterministic across the two
 * places that would have to agree on it, and wrong here anyway — the loop we
 * are preventing takes seconds, not minutes. The resource being protected is
 * the user's attention, and attention is spent per prompt, so the budget is
 * counted in prompts.
 */
class PermissionAskPolicy(
    /** How many times one permission may be requested per session. */
    private val maxAsksPerPermission: Int = DEFAULT_MAX_ASKS,
) {

    init {
        // A zero or negative budget would deadlock the agent: it could never ask
        // even once. Fail loudly at construction instead of silently degrading.
        require(maxAsksPerPermission >= 1) { "maxAsksPerPermission must be >= 1" }
    }

    /** What the caller should do about a missing permission right now. */
    sealed interface Decision {
        /** Run the tool: the permission is granted. */
        data object Allowed : Decision

        /**
         * Show a system prompt now. The count has not run out and the state is
         * recoverable by asking.
         */
        data class Ask(val attempt: Int) : Decision

        /**
         * Do not prompt. Either the state is unrecoverable by asking, or the
         * prompt budget is spent. The observation must say which, because the
         * user-facing advice differs: "try again" versus "open Settings".
         */
        data class Refuse(val reason: RefusalReason) : Decision
    }

    /** Why a prompt was withheld. Both are terminal for this session. */
    enum class RefusalReason {
        /** The system will not show a prompt again. Only Settings can fix it. */
        PERMANENTLY_DENIED,

        /** The permission does not exist for this app or device. Asking is absurd. */
        NOT_APPLICABLE,

        /** Prompts remain possible but the budget for this session is spent. */
        ASK_BUDGET_SPENT,
    }

    /**
     * Prompt counts per permission, per session.
     *
     * Bounded by the number of distinct permissions the app declares (single
     * digits), so this map cannot grow. That is stated here because
     * docs/architecture.md §16 requires a worst case for every cache.
     */
    private val askCounts = HashMap<Permission, Int>()

    /** Prompts issued for [permission] so far. Exposed for the UI and tests. */
    fun asksFor(permission: Permission): Int = askCounts[permission] ?: 0

    /**
     * Spends one prompt for [permission] if [state] allows it, and reports
     * whether one was spent.
     *
     * WHY the state is a parameter instead of being assumed: an earlier version
     * hardcoded "the state is DENIED" and would happily record a prompt for a
     * permanently denied permission. That is precisely the loop this package
     * exists to stop, and a test caught it. The caller now states the state it
     * is acting on, and the policy is the single place that decides.
     *
     * Returns false when no prompt is owed — the caller must not show a dialog
     * in that case.
     */
    fun tryAsk(permission: Permission, state: PermissionState): Boolean {
        if (decide(permission, state) !is Decision.Ask) return false
        askCounts[permission] = asksFor(permission) + 1
        return true
    }

    /**
     * Whether the agent may prompt for [permission] given [state].
     *
     * Pure: it does not mutate. The caller must call [nextStateAfterAsk] to
     * actually spend a prompt.
     */
    fun decide(permission: Permission, state: PermissionState): Decision = when {
        state.allowsExecution -> Decision.Allowed

        // Order matters: the state decides first, the budget second. A
        // permanently denied permission is refused even if the budget has room,
        // because spending a prompt on it would show a dialog that does not
        // appear — the failure mode users read as "this app is broken".
        !state.isRetryable ->
            if (state == PermissionState.NOT_APPLICABLE) {
                Decision.Refuse(RefusalReason.NOT_APPLICABLE)
            } else {
                Decision.Refuse(RefusalReason.PERMANENTLY_DENIED)
            }

        asksFor(permission) >= maxAsksPerPermission ->
            Decision.Refuse(RefusalReason.ASK_BUDGET_SPENT)

        else -> Decision.Ask(asksFor(permission) + 1)
    }

    /** Total prompts issued across all permissions. Bounded; for tests and UI. */
    fun totalAsks(): Int = askCounts.values.sum()

    /**
     * Forgets the counts. Call when the user grants or revokes something from
     * system settings, which is a real state change and a legitimate reason to
     * start over. Not called on a timer — see the class KDoc.
     */
    fun reset() = askCounts.clear()

    companion object {
        /**
         * Two prompts: enough that a user who missed the first one gets a real
         * second chance, few enough that a looping agent cannot hold the screen
         * hostage.
         */
        const val DEFAULT_MAX_ASKS = 2
    }
}
