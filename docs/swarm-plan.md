# Swarm plan

How the work is actually divided, and why the division is different from a naive
"one agent per team" split.

---

## The rule that shapes everything

> **Partition by FILE, not by feature.**

Twenty agents given twenty features will collide on `settings.gradle.kts`,
`libs.versions.toml`, `build.gradle.kts`, `AgentController.kt`, and the manifest.
Every one of those is a merge conflict with a 50% chance of a semantic break that
compiles fine.

Twenty agents given twenty disjoint file sets cannot collide at all. Work that shares
no file merges with `git merge` and no human judgement.

---

## Wave 1: six agents, zero file overlap

Verified with `comm -12` on the file lists before dispatch, not eyeballed.

| Agent | Owns (new files only) | Module |
|---|---|---|
| **A — agent loop** | `core/.../agent/AgentController.kt`, `AgentConfig.kt`, `AgentResult.kt`, `Session.kt` | `:core` |
| **B — eval harness** | `core/src/test/.../eval/*` (FakeModelBackend, ScriptedTool, 50 tasks, EvalMain) — **all deleted**; see note below | `:core` test |
| **C — action parsing + grammar** | `core/.../agent/ActionParserImpl.kt`, `core/.../model/GrammarBuilder.kt` | `:core` |
| **D — context + compaction** | `core/.../context/DefaultContextBuilder.kt`, `ContextCompactor.kt` | `:core` |
| **E — Room stores** | `android/.../data/*` (entities, DAOs, DB, converters) | `:android` |
| **F — llama.cpp JNI** | `android/.../inference/*`, `android/src/main/cpp/*` | `:android` |

Shared files nobody touches in wave 1: `settings.gradle.kts`, `libs.versions.toml`,
all `build.gradle.kts`, `AndroidManifest.xml`. If an agent needs a change there, it
reports it and the parent makes it. That is the parent's job, not the leaf's.

### Why E and F are the only `:android` agents in wave 1

They touch disjoint packages (`data` vs `inference`) and nothing else. F is the
long pole — llama.cpp is a large native build — so it starts first and gets the whole
wave to finish in.

### Agent B no longer exists

Wave 1 was planned with six agents. **Agent B's entire output — the
FakeModelBackend, ScriptedTool, the 50-task dataset and the eval runner — was
deleted at the owner's explicit instruction**, along with the rest of the test
sources and every test-only CI job. `core/build.gradle.kts` records why: the
harness ran against fakes rather than a real loop and a real model, reported
50/50, and the app it "verified" could not answer a single question.

This document is kept as the historical record of how the work was partitioned.
Read agent B's row as something that happened and was then reversed.

### Why the eval harness (B) shipped in wave 1, not wave 0

B needs `AgentController` to exist to test it. Sequencing B first means writing a
spec for a class that does not exist yet, and the spec will be wrong. B and A build
against the same frozen contracts and integrate at the end of the wave.

The plan this replaces put "50-task eval dataset" at P0-070, gated behind a working
model backend. That ordering guarantees the eval suite is written twice: once as
speculation, once for real.

---

## Wave 2: the Android tools, one agent per category

Only after wave 1 is green. Twelve tool categories, four agents, three categories each,
disjoint packages:

```
tools/files/       tools/apps/        tools/clipboard/
tools/device/      tools/calendar/    tools/contacts/
tools/notifications/   tools/web/
```

Each is a handful of `AgentTool` implementations. These agents never touch
`AgentController`. **25 tools exist today** across the categories above; none
has been verified against real Android APIs through the agent loop.

**droid-mcp is not a dependency.** The plan it replaces proposed adapting it to skip
weeks of Android boilerplate. That is a trap:

- it is 53 modules and 145 tools, most of which v0 will never expose
- it drags JitPack into the build, which is a CI reliability risk for a public repo
- its tool surface is designed for MCP, so the adapter is not free
- a swarm agent debugging a JitPack resolution failure is a swarm agent burning its
  whole run on someone else's release cadence

For ~20 operations, each 30-80 lines against platform APIs, the boilerplate is about a
day. A vendored dependency that shapes the architecture forever is not worth it. If
it becomes painful, it can be added later behind `AgentTool` without touching the core.

---

## Wave 3: integration and the vertical slice

One agent, or the parent. Takes wave 1 + wave 2 branches, merges them, fixes the
integration, and proves:

```
real GGUF -> "how much battery do I have?" -> device.battery -> real result -> correct answer
```

Nobody adds features until this works.

---

## The rules embedded in every brief

```
Do not redesign architecture.
Do not add dependencies unless it is genuinely necessary.
Do not create abstractions for hypothetical future use.
Do not modify files outside your assignment — report the need instead.
Do not add cloud dependencies. Do not add telemetry. Do not introduce MCP.
Prefer Android/Kotlin platform APIs over wrappers.
Keep model-visible outputs extremely compact.
Never import android.* in :core.
```

"All functionality requires tests" was removed. It described a suite that was
deleted on purpose; leaving it in place reads as an instruction to rebuild one.

Plus, per agent:

```
work in your OWN git worktree; never touch the main checkout
verify with ./gradlew :core:assemble (seconds) — if it takes minutes, something leaked
commit, push, open ONE PR with "Fixes #N"
never merge; never push to main
```

`:core:test` became `:core:assemble`. There are no test sources to run; what
remains is a compile gate.

---

## Why not more agents

Six is not a round number, it is what the file partition supports. A seventh agent
would need a seventh disjoint package, and in wave 1 there are exactly six.

Wave 2 went to twelve because tool categories are genuinely independent and each is a
self-contained package. That is the point where the swarm
actually pays off: many small, identical-shaped, independently verifiable pieces.

Wave 3 collapses back to one. Integration is not parallelizable, and the instinct to
"keep the swarm going" during integration is how 20 agents produce 20 slightly different
answers to the same architectural question.
