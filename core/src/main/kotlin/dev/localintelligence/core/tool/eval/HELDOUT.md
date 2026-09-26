# Held-out selectability harness

The project's headline number lives here. Read this before quoting it.

```bash
export JAVA_HOME=$HOME/jdk21
./gradlew :core:compileKotlin
./core/src/main/kotlin/dev/localintelligence/core/tool/eval/run-heldout-harness.sh
```

A `main()` on a pure-JVM classpath. No JUnit, no `core/src/test`, no device, no
emulator, no model, no build-file change, no dependency. `:core` stays pure JVM
and `check-core-purity.sh` still passes.

## The number, and what it is not

**68.2% (116/170) at the shipped `maxVisibleTools = 6`.**

That is **selectability**: was at least one tool that could correctly serve the
request inside the visible 6-slot set? It is an **upper bound** on task success,
not a success rate, because `GrammarBuilder` makes an unselected tool unspeakable
— a retrieval miss is a task the agent cannot perform, not a worse answer.

It says nothing about whether the model then *chooses* that tool or calls it with
usable arguments. There is no model in this loop. A 100% score here is
compatible with an agent that never makes one correct call.

## Files

| File | What it is |
|---|---|
| `HeldOutDataset.kt` | The 170-turn corpus, and rules R1–R5 it is held to |
| `HeldOutCase.kt` | One turn: utterance, expected tool SET, intent, dialect |
| `HeldOutToolSnapshot.kt` | The 25 shipped tools as pure data, plus the drift check |
| `HeldOutHarness.kt` | Scoring, the mirrored formula, and the mirror proof |
| `HeldOutReport.kt` | `main()` — the report |
| `run-heldout-harness.sh` | One-command invocation |

## The rules that make the number mean something

**R1 — written without reading a single tag.** The 25 descriptions are the fair
basis for deciding which tool a request is *for*. The 185 tags are retrieval
fuel tuned against a different corpus. An utterance written while looking at a
tag list measures the list, not the selector.

**R2 — never tuned against.** The corpus was run once. No tag, description,
weight or constant was changed on the basis of a result from it. This is the
rule that decays quietly, so `HeldOutDataset.CONTAMINATION` holds the state as
*data* and the harness prints it in the header every run. Flip it to
`CONTAMINATED` in the same commit as any change motivated by a case here, and
treat every number as a training-set score from that point on.

**R3 — different order of operations.** The corpus was authored scenario-first
(a person's day, then the phrasing, then the tool), not tag-first. That ordering
is the reason the number differs from the 176/176 that came before it.

**R4 — hard on purpose, and honest about what is hard.** NEAR_MISS, ELLIPTICAL,
CHAINED and REFERENTIAL populations are reported separately. A corpus of clean
single-tool requests would flatter any selector.

**R5 — the languages the team actually speaks.** EN 82.3%, **NL 44.8%,
DE 35.7%**. The shipped scorer is ASCII-only, so a German request is mostly not
scored at all. The cliff is reported per dialect rather than averaged away.

## What the harness refuses to hide

The report's own blind-spot section (B1–B9) is the point of the file, not an
appendix. It reports, as counts with verdicts:

- tools never exercised by any case, and tools with too few cases to conclude
  anything
- **tautology**: turns reachable *only* through a tag, with no name or
  description overlap — the share of the headline carried by an artefact no user
  reads
- **zero-signal** turns, where the expected tool scores exactly 0 and no
  re-weighting can help
- **rank distribution** of the best expected tool, separating a width problem
  from a hopeless one
- **tie rate**: the share of turns decided by the alphabetical tie-break rather
  than by score
- an explicit list of what the corpus cannot measure (choice, argument quality,
  multi-hop, prose answers, real user phrasing, non-Latin scripts)

## Integrity checks that run every time

- the tool snapshot vs `V0ToolCatalogue` (names, categories, risk)
- the tool snapshot vs the `:android` **sources**, re-parsed at run time
  (names, categories, risk, **permissions and all 185 tags** — tags carry 3x the
  weight of description tokens, so drift here would move the headline)
- the mirrored scoring formula vs the shipped `LexicalToolSelector`, on every
  tool of every turn; if the selector changes, this fails loudly instead of the
  "scored zero" claims quietly becoming fiction

Each check reports **SKIPPED** rather than passing when it cannot run, because a
skipped check read as a pass is the failure mode they exist to prevent. Two of
them did skip or misreport on this harness's first run, which is the argument
for building them this way.
