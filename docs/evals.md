# Evals

## The headline number: 68.2% held-out selectability (116/170)

**Read the per-tool table before quoting this.** A single percentage is not the
deliverable; the breakdown is. This section is the summary, and the harness
prints the rest.

At the shipped `AgentConfig.maxVisibleTools = 6`, on a 170-turn corpus authored
without reading a single tool tag:

| k | turns reachable | selectability | slot-recall | mean system-prompt tokens |
|---|---|---|---|---|
| 3 | 101/170 | 59.4% | 57.9% | 322 |
| **6 (shipped)** | **116/170** | **68.2%** | **66.3%** | **398** |
| 10 | 133/170 | 78.2% | 75.8% | 494 |
| 12 | 138/170 | 81.2% | 79.2% | 539 |

```bash
export JAVA_HOME=$HOME/jdk21
./gradlew :core:compileKotlin
./core/src/main/kotlin/dev/localintelligence/core/tool/eval/run-heldout-harness.sh
```

### What that number is, and is not

It is **selectability**: was at least one tool that could correctly serve the
request inside the visible 6-slot set? It is **not** task success and **not**
"accuracy". `AgentController` hands the same selected list to the system prompt
and to `GrammarBuilder`, and the grammar makes an unselected tool *unspeakable* —
so a retrieval miss is not a worse answer, it is a task the agent cannot perform
at all. Selectability is therefore an **upper bound** on task success.

**It does not tell you the model will choose the tool.** No model, no device and
no inference are involved. A 100% selectability score is compatible with an agent
that never makes one correct call. Closing that gap needs a real GGUF on real
hardware and a scorer that checks the emitted call — see the unmeasured table
below, which is unchanged by any of this.

### The findings that matter more than the percentage

- **Language is a capability cliff, not a gradient.** EN 82.3% (93/113),
  **NL 44.8%** (13/29), **DE 35.7%** (10/28). `LexicalToolSelector` tokenises on
  `[^a-z0-9]+` with a length>2 floor, so an accented or compound word contributes
  nothing at all. A German request is not scored badly; it is mostly not scored.
  The tool tags are English. For a team that speaks Dutch and German this is the
  single largest gap in the system, and it is a property of the shipped
  selector, not of the corpus.
- **85% of misses are unwinnable by any width.** Splitting every miss by cause:
  56 tool-misses = **48 SCORER-BLIND** (the expected tool scored exactly 0, so it
  shares no word with its own name, description or tags) + **8 LOST-AT-CUT**
  (scored, then fell outside the 6 slots). Only 8 of 56 misses are a width
  problem. Raising `maxVisibleTools` cannot fix the other 48.
- **The cut is mostly decided alphabetically.** At k=6, **84.1%** of turns have
  the 6th and 7th tool scoring *identically*, so the outcome is settled by the
  `thenBy { name }` tie-break rather than by relevance.
- **Referential turns are not measurably worse here** (control 68.2% vs
  referential 68.2%, gap 0.1 points) — which is *not* the predicted result and is
  reported as such. `Session.currentKeywords()` reads only the latest user turn,
  so the asymmetry is real in production; n=22 is simply too small to resolve it.
  Do not read "multi-turn is fine" off this row.
- **Statistical resolution.** At n=170 a 95% interval is roughly +/-7 points, so
  a few points of difference between two variants is not a result.

### The contamination rule

This corpus was written without reading a tag (rule R1) and run **once** (rule
R2). It was not tuned against, and nothing in `:android`, `V0ToolCatalogue`,
`LexicalToolSelector` or `AgentConfig` was changed on the basis of a result here.
`HeldOutDataset.CONTAMINATION` records that as data and the harness prints it in
the header on every run. **If someone changes a tag, description, weight or
constant because of a case in this corpus, it must be flipped to `CONTAMINATED`
in the same commit** — at that point every number here becomes a training-set
score and the honest corpus is a new, smaller, freshly authored one.

The harness also prints a **tautology check**: 26/170 turns are reachable only
through a tag, with no name or description overlap. That is not evidence of
overfitting here (the corpus never saw a tag), but it does mean part of the
headline is carried by an artefact no user ever reads.

### Coverage and blind spots

All 25 shipped tools are exercised by at least 4 cases; 2 tools
(`clipboard.read`, `device.vibrate`) have only 4 and are flagged `THIN` in the
table — a cell that small cannot support a claim about the selector. The harness
fails loudly on its own blind spots (B1–B9): tools never exercised, tools never
selected, tautological turns, zero-signal turns, rank distribution, tie rate, and
an explicit list of what the corpus cannot measure.

---

## There is no task-level eval suite in this repository

This file used to describe a 50-task suite, an eval runner, and a
`./gradlew :core:evals` task. **None of that exists.** It was deleted, along
with the rest of the test sources, at the owner's explicit instruction.

The held-out corpus above is not that suite. It measures one component — which
tools the *selector* makes reachable — with no model in the loop. It does not
measure whether a task completes.

Verified against the current tree:

```
$ ls core/src/
main
test    # 20 pure-JVM JUnit tests — deterministic, in-process, no fakes
$ ls android/src/
main
$ find . -path ./.git -prune -o -type d -name test -print
./core/src/test               # 20 tests
./app/src/androidTest          # DeviceModelProbe.kt — a manual probe, not a suite

$ grep -rn 'evals' core/build.gradle.kts build.gradle.kts
(no matches — there is no :core:evals task)

$ find . -name 'FakeModelBackend*' -o -name 'ScriptedTool*'
(no matches)
```

So these commands do not work and are not aspirational:

```bash
./gradlew :core:evals    # the task does not exist
```

`./gradlew :core:test` **does** work and runs 20 tests, but it is not an eval:
every test is pure deterministic logic against the real production classes, and
none of them involves a model, a device, or a task completing.

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

- A real **668,788,096-byte** TinyLlama 1.1B Q4_K_M loaded in roughly **600–720 ms**
  and generated real text.
- Decode ran at **~0.67 tok/s** on emulated CPU.

**Neither figure is a performance number for this app.** They describe an
emulated CPU with no hardware acceleration. The decode rate reads as a hang.
Never quote either as a device figure, and never derive a "slow model"
conclusion from them. `docs/measurements.md` is the authoritative list of what
has and has not been measured.

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

**This rule has exactly one instrument, and it measures one component.**
`run-heldout-harness.sh` gives a real, re-runnable, non-cached number for tool
**selectability** — the ceiling on which tools the model is even allowed to
call. That is more than there was, and it is not the whole rule.

Everything a reader would want from "does it ship" is still unmeasured: task
success, argument quality, loop behaviour, tokens per completed task. Until
someone runs the task list on real hardware, the project still cannot answer
whether a given change helped *end to end*.

The honest position: a change can now be checked against a held-out selection
number in seconds, and that number is a ceiling rather than a result. It is an
argument for finishing the measurement work, not for treating 68.2% as a
success rate.

---

## Related

- [`build.md`](build.md) — the no-test-suite fact, and why there is no test CI job
- [`memory-model.md`](memory-model.md) — what *is* measured, and the RAM procedure
- [`roadmap.md`](roadmap.md) — what remains to be built and measured
