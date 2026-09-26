# The agent loop

One file. Under 300 meaningful lines. In `:core`.

If this document and `AgentController.kt` ever disagree, the code is right and this
document is stale — fix the document.

## The shape

```kotlin
suspend fun run(task: String): AgentResult {
    val session = sessions.current()

    repeat(config.maxSteps) {
        val tools  = toolSelector.select(task, session, maxTools = config.maxVisibleTools)
        val prompt = contextBuilder.build(task, session, memory.search(task), tools)

        val result = model.generate(prompt)
        if (result.stopReason == CANCELLED) return Cancelled

        when (val action = parser.parse(result.text, tools.map { it.name }.toSet())) {
            is Malformed -> {
                session.observe(
                    "Your last message was not a valid action. " +
                        "Reply with either a plain answer, or ${grammarHint(tools)}."
                )
            }

            is Parsed -> when (val a = action.action) {
                is Respond -> {
                    session.appendAssistant(a.text)
                    memoryProcessor.onTaskComplete(session)
                    return Success(a.text, session.trace)
                }

                is CallTool -> {
                    when (val v = validator.validate(a.name, a.arguments, tools, registry)) {
                        is Rejected -> session.observe(v.observation)

                        is Valid -> when (loopDetector.check(a.name, a.arguments)) {
                            BLOCK -> return Stop(reason = "Repeated identical call: ${a.name}")
                            WARN  -> session.observe(
                                "You already ran $${a.name} with those exact arguments. " +
                                    "Do something different, or answer the user."
                            )
                            ALLOW -> {
                                if (needsConfirmation(v.tool) && !alreadyConfirmed(a, session)) {
                                    return AwaitingConfirmation(a, v.tool)
                                }
                                val toolResult = v.tool.execute(a.arguments, context)
                                loopDetector.recordResult(a.name, toolResult.observation, toolResult.success)
                                session.appendToolObservation(v.tool, toolResult, durationMs)
                                if (needsConfirmation(v.tool)) session.markConfirmed(a)
                            }
                        }
                    }
                }
            }
        }

        contextManager.compactIfNeeded(session)
    }

    return StepLimitReached
}
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
| Output unparseable N times in a row | `Stop(reason = "model could not produce a valid action")` |
| Same call 3x | `Stop(reason = "loop detected")` |
| N observations with no state change | `Stop(reason = "no progress")` |
| Risky tool, no confirmation yet | `AwaitingConfirmation` (resumes via `confirm()`) |
| Tool throws | caught, becomes a failed `ToolResult`, model observes the failure |

Malformed output is worth spelling out. A 1B model WILL emit prose where a tool call
belongs. The loop does not abort on the first malformed response — it tells the model
exactly what shape it wanted and lets it try again. It aborts only after N consecutive
malformeds, because a model that cannot produce valid output after three corrections
will not produce it on the fourth attempt either.

## Configuration

```kotlin
data class AgentConfig(
    val maxSteps: Int = 8,                 // 2-10 reliable actions is the v0 target
    val maxVisibleTools: Int = 6,          // hard ceiling 8
    val maxMalformedRetries: Int = 3,
    val prefillCostCapTokens: Int = 6000,  // prefill COST cap, not the ceiling
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
