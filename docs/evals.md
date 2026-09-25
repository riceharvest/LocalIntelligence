# Evals

The eval suite is not a nice-to-have. It is the instrument that tells you whether the
architecture works. Without it, every change is a guess about whether small models got
better or worse.

> **How small can the model get before the agent loop stops being reliable?**

That is the question. Everything here serves it.

---

## The split

Two kinds of test, and conflating them is how agent projects end up lying to themselves.

**Deterministic harness tests** (`FakeModelBackend` + mock tools) test the *runtime*:
does the loop validate arguments, detect loops, respect risk policy, compact context,
stop at the step limit. These run on the JVM in seconds and must never be flaky.

**Model evals** (real GGUF + mock tools) test the *combination* of runtime and model:
does a 3B model actually pick the right tool. These are slower and are allowed to be
reported as a percentage rather than pass/fail.

The first tells you the plumbing is right. The second tells you the design is right.
A green harness with a 40% model eval means the plumbing is fine and the protocol is
too hard for the model — which is a design bug, not a model bug.

---

## Deterministic harness tests

```kotlin
@Test fun `loop detects a repeated identical call and blocks on the third`() { ... }

@Test fun `unknown tool is rejected before execution`() { ... }

@Test fun `destructive tool awaits confirmation instead of executing`() { ... }

@Test fun `read-only tool executes without confirmation`() { ... }

@Test fun `tool exception becomes a failed result, not a crash`() { ... }

@Test fun `observation is truncated to the budget`() { ... }

@Test fun `compaction triggers above the working limit`() { ... }

@Test fun `step limit terminates the loop`() { ... }

@Test fun `cancellation propagates to the tool`() { ... }
```

FakeModelBackend returns scripted actions in order. Mock tools return scripted
observations. Nothing is random, nothing sleeps, nothing touches a device.

---

## The 50-task suite

| Category | Count | What it proves |
|---|---:|---|
| single-tool | 10 | the model can emit one valid call |
| two-tool | 10 | it can chain and use a previous observation |
| 3-5 step | 10 | it can hold a plan across a window |
| memory | 5 | explicit remember, then recall in a later turn |
| ambiguity | 5 | it asks or picks rather than inventing |
| failure/recovery | 5 | permission denied does not become 14 retries |
| impossible | 5 | it says it cannot, rather than hallucinating success |

Example:

```
User:      "What is my next appointment with Alice?"
Tools:     calendar.search, contacts.search, alarm.create
Expected:  calendar.search  (first call)
Mock:      [{title: "Lunch with Alice", time: "2026-10-02T13:00"}]
Assert:    final answer mentions Oct 2 and 13:00
```

Failure case, and this one matters more than the happy path:

```
User:      "What's on my calendar tomorrow?"
Setup:     calendar permission DENIED
Expected:  ONE calendar.search attempt, then a plain explanation that the
           permission is required.
Failure:   retrying calendar.search 14 times.
```

A harness that cannot be denied permission is a harness that has never met an Android
user.

---

## What is asserted

Never exact wording. A model that says "43%" and a model that says "your battery is at
43 percent" are both correct.

```
correct tool?             the decisive signal
correct arguments?        schema-valid and semantically right
correct action sequence?  order and count
task completed?           did the user get what they asked for
unnecessary calls?        steps spent doing nothing
loops?                    repeated identical calls
```

Plus the efficiency metrics, because a 90%-correct agent that takes 14 steps and 9000
tokens is worse on a phone than a 85%-correct one that takes 4 steps and 3500:

```
success  steps  tool_calls  invalid_tool_calls  duplicate_calls
input_tokens  output_tokens  prefill_ms  decode_tok_s  total_ms  peak_ram
```

---

## Primary metric

```
task success
```

## Secondary metric

```
task success / model-generated token
```

That second one is the interesting one for a mobile agent. An agent that succeeds using
400 generated tokens is a 3B-class agent. One that needs 1500 is a 7B-class agent
wearing a 3B hat. The ratio is model-size-independent, which makes it comparable across
quantizations, architectures, and devices.

---

## Running it

```bash
./gradlew :core:test        # deterministic harness tests, seconds, no device
./gradlew :core:evals       # the task suite against fakes, seconds
./gradlew :core:evals -PLocalIntelligence.model=/path/to/model.gguf   # against a real model
```

Output is one line per task plus a summary:

```
[ok]   single/battery-level                     2 steps  38 tok   1.2s
[ok]   two/find-and-copy-contact               3 steps  91 tok   2.8s
[FAIL] multi/alarm-before-meeting              7 steps  412 tok  11.4s
         expected alarm.create, got calendar.search x4
[FAIL] failure/permission-denied               14 steps 380 tok  22.1s
         LOOP: calendar.search repeated 14x
...
50 tasks | 38 pass (76%) | mean 4.1 steps | mean 142 tok | mean 9.8s
primary: task success              0.76
secondary: success per 1k tokens   5.6 tasks
```

---

## The rule

> If a feature does not move a number here, it does not ship.

That is the whole reason this file exists. A harness feature that improves nothing
measurable is a feature that added a code path, a failure mode, and maintenance cost
in exchange for nothing.
