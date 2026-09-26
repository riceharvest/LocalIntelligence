package dev.localintelligence.core.execution

/**
 * The sentence a user reads when their message was not run because another run
 * already held the [RunGate].
 *
 * ## Why this is a constant and not built at the call site
 *
 * Two callers can refuse: a chat message the user typed, and a scheduled task
 * that fired. If each of them assembles the sentence, the two drift, and the
 * scheduled-task copy is the one nobody is looking at when it is wrong. The
 * architecture's rule that a number or a string a user sees must have exactly
 * one implementation (`docs/architecture.md` §16) is about numbers, but the
 * reason it states is the reason this is one constant.
 *
 * ## Why it reuses `RunOutcome.Failed` and not a new outcome
 *
 * A refusal IS a finished run that produced no answer. `RunOutcome` is a sealed
 * interface of four cases and the refusal belongs to the existing
 * `Failed(reason)` case, which is already terminal, already rendered by
 * `RunOutcome.notice`, already counted by `traceVerdict`, and already written
 * into a scheduled task's `lastResult` by `ScheduledRunReporter`. A fifth case
 * would mean a new branch in every `when` over that type across `:app`, and
 * this change is not allowed to touch `:app`.
 *
 * ## What the words have to survive
 *
 * The scheduled-task path writes this string into a row a user reads days
 * later, with no run behind it and no trace to consult. So it says three
 * things: what happened, that nothing was changed, and what happens next. A
 * message that only says "busy" reads, days later, like a failure of the task
 * rather than a decision not to start it — which is the "looks broken, is not
 * broken" lie this project treats as its worst class of bug.
 *
 * No user input, no model output and no exception text goes into it. It is a
 * fixed string, so it cannot leak anything into a durable row.
 *
 * ## Why it is public and not `internal`
 *
 * The site that turns a refusal into a `RunOutcome.Failed` is in `:app`, which
 * is a different Gradle module. An `internal const val` compiles to a public
 * static field but is still `internal` in Kotlin's metadata, so `:app` cannot
 * name it — the wiring below would simply not compile. The constant has to be
 * public for the one caller that needs it, and the alternative (each call site
 * writing its own sentence) is the drift this file exists to prevent.
 */
const val RUN_ALREADY_ACTIVE_REASON: String =
    "Another task was already running, so this one was not started. " +
        "Nothing was changed. It will not be retried automatically - send it again " +
        "once the other task finishes."
