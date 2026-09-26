# Evals

## What exists here now, and what still does not

This file used to open by saying there was no eval suite in this repository,
and that was true. It is now true of the AGENT loop and false of TOOL
SELECTION, and conflating the two would be the same error in the other
direction.

| Measurement | Status | Where |
|---|---|---|
| tool-selection recall (top-k, by width) | **MEASURED, reproducible** | `core/tool/eval/`, see below |
| agent loop task success on a real model | **unmeasured** | needs real arm64 hardware |
| argument correctness, step counts, tok/s | **unmeasured** | `core/metrics/RunMetrics`, read by hand |

### The selector harness

```bash
./gradlew :core:compileKotlin
./core/src/main/kotlin/dev/localintelligence/core/tool/eval/run-recall-harness.sh
```

`core/tool/eval/` holds a 176-case dataset of realistic utterances with the
tool set each should make reachable, a snapshot of the 25 tools `:android`
ships, and a `main()` harness that reports top-k recall for k in {3, 6, 10, 12,
all}. It needs no test task, no build change, and no device.

It is NOT the suite that was deleted, and the distinction matters:

- the old suite ran the **agent loop against fakes** and reported 50/50 for a
  harness that could not fail. This one runs the **real selector** against the
  **real tool definitions** and can genuinely report a bad number — it does,
  84.7% at k=3;
- the old suite claimed to verify the agent. This verifies one function,
  `LexicalToolSelector`, and says so on every run;
- there is no JUnit, no `FakeModelBackend`, and no `core/src/test`.

Two honesty properties it holds, both of which the previous numbers lacked:

1. **It is re-derivable.** Every recall figure quoted in `AgentConfig`,
   `ToolRegistry` and `docs/architecture.md` comes out of this harness. If the
   selector changes, re-run it and the comments are wrong in a way you can see.
2. **It checks its own inputs.** The tool snapshot is compared against
   `V0ToolCatalogue` (names, categories, risk tiers) and the harness's mirrored
   copy of the scoring formula is compared against the real
   `LexicalToolSelector` on every case. Both report on every run, pass or fail.

### What the selector harness does NOT measure

**Task success.** Recall is an upper bound on it, not a component of it. A case
counted as a hit has a *callable* tool; nothing here says the model then emits
a well-formed call with correct arguments.

**Whether a 1-3B model exploits a wider set.** There is no model in the
harness. Raising `maxVisibleTools` to 10 raises the ceiling; whether a small
model picks reliably from ten alternatives than from six is the unmeasured half
of that trade, and it stays unmeasured.

**Non-English phrasing.** The scorer splits on `[^a-z0-9]+`, so any non-Latin
utterance scores zero against every tool. That is a real and separate finding,
not covered by this dataset.

### The numbers

Measured on the committed dataset, 176 cases, 25 shipped tools:

| visible tools | tasks made possible | mean system-prompt tokens |
|---------------|---------------------:|-------------------------:|
| 3             |            149/176  |  141                     |
| 6             |            158/176  |  218                     |
| **10 (ships)**|    **167/176**      |  **321**                 |
| 12            |            169/176  |  370                     |
| all 25        |            176/176  |  705                     |

These replace an earlier set (61.6% at k=6 over 86 held-out cases) that could
not be re-derived because the utterances lived on an unmerged branch. The
absolute percentages are not comparable — different dataset — and are not
presented as one trend. The SHAPE is what carried over, and the shape is what
the constant was set from.

---

## The single-turn recall number is not the whole story, and the gap is measurable

The 176-utterance corpus above is **isolated one-shots**. Every case is scored
as if the user had never spoken before. Real use is not that, and the specific
way it is not is a silent product failure with a known mechanism:

- `AgentController.selectTools` scores the **current** task string plus
  `Session.currentKeywords()`;
- `Session.currentKeywords()` reads **only the latest user turn** — earlier user
  turns are excluded along with tool observations, deliberately, and
  `SessionKeywordTrustTest` pins the observation half as a security boundary
  against untrusted page text steering the grammar;
- meanwhile `DefaultContextBuilder` hands the **model** up to
  `ContextLimits.MAX_HISTORY_SCAN` (64) messages of retained history.

So on any follow-up, the model holds the referent and the selector does not.
The tool that would act on it scores zero, drops out of the visible set, and —
because `GrammarBuilder.forActions` makes an unselected tool *unspeakable* —
the correct call becomes unrepresentable rather than merely discouraged. Nothing
throws and nothing is logged.

`MultiTurnHarness` measures this over 13 hand-written conversations (43 turns),
half of which are a **control group** whose turns each stand alone. The control
group is not filler: a corpus of only referential turns would report a
catastrophic rate that says nothing, because every case would be unanswerable
by construction.

`AsymmetryProbe` turns the claim above into a check that can fail, by rendering
what the model sees with the real `DefaultContextBuilder` and asking the real
`Session` what the selector sees, then comparing. It currently reports 3/3
asymmetric. If that ever reads 0, the product changed and the *interpretation*
of the recall numbers is what needs re-deriving.

### Measured at the shipped k=10

| figure | value |
|---|---|
| turns where ≥1 expected tool was callable | 40/43 (93.0%) |
| **set-level recall (expected tool-slots reached)** | **83.0%** |
| control-group recall | 26/27 |
| referential recall | 14/16 |
| turns fully unreachable | 3 (2 referential, 1 control) |
| turns reachable only PARTIALLY | 5 |

The slot-recall figure is the honest one and the per-turn figure is not. A turn
expecting `{alarm.create, device.battery}` that got only the alarm scores 100%
on "turns-ok" while the specific thing the user asked to be reminded about is
uncallable. The harness prints both, plus the partial-failure list, precisely
because the flattering number is the one that hides this.

### What this still does not measure

No model, no device, no inference. Recall is an **upper bound** on task success,
not a success rate: it establishes that a tool was made callable, never that the
model then called it correctly. The half that needs a real model on a real phone
is still unmeasured, and this does not substitute for it.

### Where it lives, and the cost of that

Kept in `core/src/main` — see `tool/eval/MULTITURN.md`. Measured: the whole
`tool/eval/` package is 282 KB of compiled bytecode (12.15% of `:core`), and
**R8 strips all of it from the release APK**; not one corpus string survives
into the shipped dex, verified against the built release artifact. The debug
build and CI pay for it, users pay nothing.

---

## There is no AGENT-loop eval suite in this repository

That part of this file is unchanged. This file used to describe a 50-task
suite, an eval runner, and a
`./gradlew :core:evals` task. **None of that exists.** It was deleted, along
with the rest of the test sources, at the owner's explicit instruction.

The retrieval harnesses in `core/src/main/kotlin/.../tool/eval/` are NOT that
suite and do not resurrect it: they score SELECTOR BEHAVIOUR deterministically
against labelled definitions, with no model in the loop, no fakes standing in
for the loop, and no claim about end-to-end task success. They are runnable by
one command, and they print their own coverage — including tools no case
reaches — so a hole in the corpus cannot hide behind an aggregate.

Verified against the current tree:

```
$ ls core/src/
main
test
$ ls android/src/
main
$ find . -path ./.git -prune -o -type d -name test -print
./app/src/androidTest          # DeviceModelProbe.kt — a manual probe, not a suite
./core/src/test                # 3 files, added after the deletion — see below

$ grep -rn 'evals' core/build.gradle.kts build.gradle.kts
(no matches — there is no :core:evals task)

$ find . -name 'FakeModelBackend*' -o -name 'ScriptedTool*'
(no matches)
```

**Correction, and it predates the multi-turn harness.** `core/src/test` was
reintroduced on `main` after the deletion — three files, added deliberately,
restoring "pure, deterministic JUnit against the real production classes"
(`core/build.gradle.kts` says so in as many words). So the older
`./gradlew :core:test  # no test sources` line below is no longer true, and the
three tests that exist are unit tests, not the resurrected agent suite. What
has NOT come back is the fake-backed 50-task harness, a `:core:evals` task, or
any `FakeModelBackend`/`ScriptedTool`: the specific defects that caused the
deletion are still absent.

So these commands do not work and are not aspirational:

```bash
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

Verification of the **agent loop** is a **human running a real model on a real
device**. There is no automated proxy for that, and the project has decided not
to pretend otherwise.

The selector harness added above is not a counterexample to that decision,
because it never claimed to verify the loop. It measures one pure function over
committed inputs, and it is the narrowest thing that could honestly be called a
harness here.

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
