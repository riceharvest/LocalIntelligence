# The agent loop

One file, `AgentController.kt`, in `:core`, with one `while` loop. The 300-line
target in `docs/architecture.md` §6 is not met: the file is about 1,500 lines.

If this document and `AgentController.kt` ever disagree, the code is right and this
document is stale — fix the document.

## The shape

Condensed from `AgentController.loop()` and `handleCall()`:

```kotlin
suspend fun run(task: String): AgentResult {     // loop() runs while step < config.maxSteps
    val visible = toolSelector.select(task, sessions.currentKeywords(), tools.all(), config.maxVisibleTools)
    val reply   = model.generate(buildRequest(task, visible))  // grammar only if supportsGrammar
    if (reply.stopReason == CANCELLED) return Cancelled
    if (reply.stopReason == ERROR) return Stop("model failed: ...")

    when (val parsed = parser.parse(reply.text, visible.names)) {
        is Malformed -> {
            malformedStreak++
            if (malformedStreak >= config.maxMalformedRetries) return Stop("no valid action in N attempts")
            sessions.observe("That was not a valid action (${parsed.reason}). ${GrammarBuilder.hint(visible)}")
        }
        is Parsed -> when (val action = parsed.action) {
            is Respond  -> { sessions.appendAssistant(action.text); rememberTurn(); return Success(action.text) }
            is CallTool -> handleCall(action, visible)   // a non-null return ends the run
        }
    }
    compactIfNeeded()
}                                                 // step limit: StepLimitReached

// handleCall
//   validator.validate(name, args, visible)  Rejected -> observe the reason, next step
//   riskPolicy.evaluate(tool, args)          decision for this exact call
//   loopDetector.check(name, args)
//     BLOCK -> Stop("loop detected: ...") or Stop("... is unavailable after repeated failures")
//     WARN  -> observe("You already ran ... with those exact arguments ...")
//     ALLOW -> per PolicyOutcome:
//                EXECUTE / REQUIRE_PERMISSION -> execute the tool
//                REQUIRE_CONFIRMATION         -> return AwaitingConfirmation
//                BLOCK                        -> observe the policy's justification, next step
// execute: tool.execute(args, context) -> observation (truncated) -> no-progress check
```

## Why it looks like this

**Selection happens every step, not once.** The task text is the query, but after the
first tool result the relevant tools often change. `"find the PDF I downloaded"` needs
`files.search` on step 1 and `apps.share` on step 3. Selecting once at the start
means either a too-wide set forever or a wrong set later.

**Validation happens before the loop detector.** A call with unknown arguments is a
different bug from a call that is being repeated. Order matters: validating first
means the model gets told the argument was wrong rather than being told it is looping.

**The loop detector is checked before confirmation.** A user is never asked to approve
the same destructive action twice.

**`Respond` ends the task.** There is no "respond then keep going". If the model has an
answer, the task is done. This is the single biggest step-count reducer available.

## Failure paths

Every one of these is a *return*, not a crash and not a silent retry.

| Condition | Result |
|---|---|
| Model says `Respond` | `Success` |
| `maxSteps` exhausted | `StepLimitReached` |
| Model cancelled mid-generation | `Cancelled` |
| Output unparseable `maxMalformedRetries` (3) times in a row | `Stop(reason = "no valid action in 3 attempts")` |
| Same call 3x (the second identical call only gets a warning) | `Stop(reason = "loop detected: NAME repeated with identical arguments")` |
| The same observation returned three times in a row after the first | `Stop(reason = "no progress: NAME kept returning the same result")` |
| Risky tool, no confirmation yet | `AwaitingConfirmation`, resumed with `confirmAndResume(approved)` |
| Policy blocks a call | refusal and its justification go to the model; the run continues |
| Tool throws | caught, becomes a failed `ToolResult`, model observes the failure |

Malformed output is worth spelling out. A 1B model WILL emit prose where a tool call
belongs. The loop does not abort on the first malformed response — it tells the model
exactly what shape it wanted and lets it try again. It aborts when the streak reaches
`maxMalformedRetries`: three attempts and two corrections. A model that is still
malformed after two corrections is not going to be fixed by a third.

## Configuration

```kotlin
data class AgentConfig(
    val maxSteps: Int = 8,                 // 2-10 reliable actions is the v0 target
    val maxVisibleTools: Int = 6,          // hard ceiling 8
    val maxMalformedRetries: Int = 3,
    val workingTokenLimit: Int = 6000,     // upper bound of the compaction trigger
    val memoryResults: Int = 5,            // hard ceiling 5
    val observationBudgetChars: Int = 2048,
)
```

`maxSteps = 8` is a deliberate v0 choice. A 1-4B model reliably completes 2-10 actions;
past that, loop quality collapses and the extra steps mostly produce retries. Raise it
only with eval evidence.

## What this loop deliberately does not have

No planner node. No critic. No reflection pass. No subagent dispatch. No scratchpad.
No state machine object.

Each of those is a defensible thing to build and an indefensible thing to build before
the vertical slice works. If the loop is unreliable, more nodes will not fix it —
smaller context and better tool selection will.
