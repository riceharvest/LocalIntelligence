# Evals

## The eval suite and its runner

The suite is the task list in `evals/tasks.jsonl` (one JSON task per line, 72 tasks). Its runner is
`scripts/run-evals.py`. It writes one result file per model, device and app build, under
`docs/scores/results/`.

Each task has `turns`, `needs`, a `fixture` flag and `checks`. The check types are `call`, `any_call`,
`only`, `forbid`, `no_calls`, `answer` and `answer_min_chars`. The runner computes every verdict itself: `pass`, `fail` or `unverified`.
A check the runner cannot decide from what it observed is `unverified`, never a pass.

The runner drives the real app on a phone through `scripts/devicectl.sh` (debug build only). There is no
desktop runner: the app's loop is built in `app/AppContainer.kt` with the on-device model backend, the
Android tool set and `android.util.Log`, so a desktop run would measure a different loop.

Usage, as the runner's header gives it:

```bash
scripts/run-evals.py --target device --serial SERIAL --model-id LFM3-1.7B-QAD-Q4_0 \
    --model-repo LiquidAI/LFM3-1.7B-GGUF --model-file LFM3-1.7B-QAD-Q4_0.gguf --mode simple --repeats 3
scripts/run-evals.py --target device --model-id LFM3-1.7B-QAD-Q4_0 --dry-run   # validate and print the plan
```

Results are keyed by (task id, repeat). A rerun with the same output file skips every run
already recorded. `--fresh` discards the file first. `--only` takes comma-separated task ids.

The older "no suite" text was about a fake-backed harness that was deleted. It does not describe the
current `evals/` and `scripts/run-evals.py`.

---

## What replaced it

Verification is a **human running a real model on a real device**. The runner above
automates the task list, but the verdict that counts for the phone is a run on real hardware,
and the project has decided not to pretend that an emulator or a fake is one.

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

**No run on real hardware is recorded yet.** `docs/scores/results/` is empty, so every
metric above is unmeasured. The runner exists; until someone runs the task list on a
device, the project cannot answer whether a given change helped.

That is the honest state. It is also an argument for finishing the measurement
work, not for inventing numbers or for restoring a fake-backed harness that
would report 50/50 again.

---

## Related

- [`build.md`](build.md) — the no-test-suite fact, and no-CI policy
- [`memory-model.md`](memory-model.md) — what *is* measured, and the RAM procedure
- [`roadmap.md`](roadmap.md) — what remains to be built and measured
