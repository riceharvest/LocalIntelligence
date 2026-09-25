# Evals

## There is no eval suite in this repository

This file used to describe a 50-task suite, an eval runner, and a
`./gradlew :core:evals` task. **None of that exists.** It was deleted, along
with the rest of the test sources, at the owner's explicit instruction.

Verified against the current tree:

```
$ ls core/src/
main
$ ls android/src/
main
$ find . -path ./.git -prune -o -type d -name test -print
./app/src/androidTest          # DeviceModelProbe.kt — a manual probe, not a suite

$ grep -rn 'evals' core/build.gradle.kts build.gradle.kts
(no matches — there is no :core:evals task)

$ find . -name 'FakeModelBackend*' -o -name 'ScriptedTool*'
(no matches)
```

So these commands do not work and are not aspirational:

```bash
./gradlew :core:test     # no test sources; there is nothing to run
./gradlew :core:evals    # the task does not exist
```

If you find a document quoting a test count, a pass rate, or a coverage figure,
it is stale. The reason for the deletion is recorded in `core/build.gradle.kts`:

> The agent eval suite lived in `core/src/test` and ran against fakes, not the
> real loop and a real model. It reported 50/50 for a harness that could not
> fail, and the app it "verified" could not answer a single question.

That is a fair criticism of the old harness. It is not an argument for
reinstating it.

---

## What replaced it

Verification is a **human running a real model on a real device**. There is no
automated proxy for that, and the project has decided not to pretend otherwise.

The instrumentation that makes a manual run measurable *does* exist, in
`core/metrics/`: `RunMetrics`, `RunAggregate`, `RunComparison`, `MetricsJson`.
Those record per-run step counts, tool calls, token counts and timings, so two
runs can be compared by hand.

---

## What has actually been run

Stated plainly, because everything else in this file used to imply more than was
true.

**MEASURED** — on a **Pixel-class x86_64 AVD** (Android 36, profile `pixel_7`).
This is **not real Pixel hardware**; it is an emulator.

- A real **668,788,096-byte** TinyLlama 1.1B Q4_K_M loads in **~600 ms** and
  generates real text.
- Decode runs at **~0.66 tok/s** on emulated CPU.

**The 0.66 tok/s figure is not a performance number for this app.** It describes
an emulated CPU with no hardware acceleration. It reads as a hang. Never quote
it as a device figure, and never derive a "slow model" conclusion from it.

**UNMEASURED — everything else:**

| Metric | Status | How to measure it |
|---|---|---|
| Task success rate | **unmeasured** | run the task list below on real arm64 hardware, count completions |
| Tokens per completed task | **unmeasured** | read the counters in `core/metrics/RunMetrics` after a run |
| Time to first token | **unmeasured** | timestamp the first streamed token against request start |
| Decode tok/s on a device | **unmeasured** | `RunMetrics` timings, on real hardware, per backend |
| Prefill time | **unmeasured** | same, recorded separately from decode |
| Peak RAM, model resident | **unmeasured** | `adb shell dumpsys meminfo <pkg>` — procedure in [`memory-model.md`](memory-model.md) §6.1 |
| Battery cost | **unmeasured** | `adb shell dumpsys batterystats <pkg>` before and after a fixed task |
| llama.cpp vs LiteRT-LM comparison | **unmeasured** | run the same task list on both backends, same model size |

None of these may be estimated. A number nobody measured is worse than no
number, because it will be quoted.

---

## The question a real eval would answer

> **How small can the model get before the agent loop stops being reliable?**

That is still the right question. It is unanswered, and this repository contains
no evidence either way.

The task list the old harness was going to run, kept here as the specification
for a manual or future harness. It is a *specification*, not a result:

| Category | Count | What it would prove |
|---|---:|---|
| single-tool | 10 | the model can emit one valid call |
| two-tool | 10 | it can chain and use a previous observation |
| 3-5 step | 10 | it can hold a plan across a window |
| memory | 5 | explicit remember, then recall in a later turn |
| ambiguity | 5 | it asks or picks rather than inventing |
| failure/recovery | 5 | permission denied does not become 14 retries |
| impossible | 5 | it says it cannot, rather than hallucinating success |
| **total** | **50** | |

Two worked examples, with expected outcomes and nothing measured:

```
User:      "What is my next appointment with Alice?"
Tools:     calendar.search, contacts.search, alarm.create
Expected:  calendar.search  (first call)
Mock:      [{title: "Lunch with Alice", time: "2026-10-02T13:00"}]
Assert:    final answer mentions Oct 2 and 13:00

User:      "What's on my calendar tomorrow?"
Setup:     calendar permission DENIED
Expected:  ONE calendar.search attempt, then a plain explanation that the
           permission is required.
Failure:   retrying calendar.search 14 times.
```

A harness that cannot be denied permission is a harness that has never met an
Android user.

---

## What should be asserted

Never exact wording. A model that says "43%" and a model that says "your battery
is at 43 percent" are both correct.

```
correct tool?             the decisive signal
correct arguments?        schema-valid and semantically right
correct action sequence?  order and count
task completed?           did the user get what they asked for
unnecessary calls?        steps spent doing nothing
loops?                    repeated identical calls
```

Plus the efficiency metrics, because a 90%-correct agent that takes 14 steps and
9000 tokens is worse on a phone than an 85%-correct one that takes 4 steps and
3500:

```
success  steps  tool_calls  invalid_tool_calls  duplicate_calls
input_tokens  output_tokens  prefill_ms  decode_tok_s  total_ms  peak_ram
```

---

## The rule, and its current state

> If a feature does not move a number here, it does not ship.

**This rule currently has no instrument, and that is a known, accepted gap.**
Every metric above is unmeasured. Until someone runs the task list on real
hardware, the project cannot answer whether a given change helped.

That is the honest state. It is also an argument for finishing the measurement
work, not for inventing numbers or for restoring a fake-backed harness that
would report 50/50 again.

---

## Related

- [`build.md`](build.md) — the no-test-suite fact, and why there is no test CI job
- [`memory-model.md`](memory-model.md) — what *is* measured, and the RAM procedure
- [`roadmap.md`](roadmap.md) — what remains to be built and measured
