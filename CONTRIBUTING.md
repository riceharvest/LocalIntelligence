# Contributing

This repo was built by a swarm of parallel agents working from a shared specification. That
history is the most useful thing in it, because the mistakes are instructive and the rules below
are mostly scar tissue. Read [docs/architecture.md](docs/architecture.md) and
[docs/tool-contract.md](docs/tool-contract.md) before writing code — both are binding.

## Repo layout

```
core/       pure Kotlin/JVM. NO android.* imports, ever. The agent loop, all decision
            logic, and the entire eval suite. CI fails the build if this leaks.
android/    the only module that touches the Android framework. Room stores, llama.cpp
            JNI, GGUF import, and the AgentTool implementations under tools/<category>/.
app/        Compose. Screens, AppContainer, ExecutionService. No business logic.
docs/       architecture and contracts. Architecture, tool contract and the loop are
            binding; the rest is design history.
```

Three modules, deliberately. Do not add a module per tool, per screen, or per workstream. Package
boundaries give the separation without the build cost.

## The frozen contracts

These are frozen because parallel agents were compiling against them without being able to talk to
each other. A change breaks work that has not landed yet.

| Type | File | Notes |
|---|---|---|
| `ModelBackend`, `GenerationRequest`, `ChatMessage` | `core/.../model/ModelBackend.kt` | No llama.cpp types, no OpenAI-shaped types. Backend-neutral. |
| `AgentAction`, `ActionParser` | `core/.../agent/AgentAction.kt` | Two actions only: `Respond`, `CallTool`. |
| `AgentTool`, `ToolDefinition`, `ToolResult`, `ToolContext`, `ToolRisk` | `core/.../tool/AgentTool.kt` | The tool contract. |
| `ToolRegistry`, `ToolSelector`, `ToolCallValidator` | `core/.../tool/ToolRegistry.kt` | |
| `AgentController`, `AgentResult`, `StepTrace`, `AgentConfig` | `core/.../agent/` | The loop's entire observable surface. |
| `MemoryStore`, `SessionStore` | `core/.../agent/MemoryStore.kt` | |

`docs/wave1-contract.md` records the exact signatures that were pinned. If an interface looks
wrong, open an issue. Do not change it in a PR — the next agent to land against the old signature
gets a compile error they cannot resolve.

## The rules

```
Keep :core pure JVM. No android.* imports, no Android plugin, not transitively.
No cloud dependencies. No telemetry. No MCP.
Do not redesign the architecture.
Do not add dependencies unless genuinely necessary.
Do not create abstractions for hypothetical future use.
Do not modify interfaces you do not own.
All functionality requires tests.
Prefer Android/Kotlin platform APIs over wrappers.
Keep model-visible outputs extremely compact.
Every feature needs a number, not a claim.
```

If you believe an interface is wrong, open an issue and say why. Do not fix it in your PR.

## Running the tests

```bash
export JAVA_HOME=$HOME/jdk17
export ANDROID_HOME=$HOME/Android/Sdk

./gradlew :core:test       # the whole agent, on the JVM, ~5s, no emulator
./gradlew :core:evals      # 50-task suite against fakes, ~1s
./gradlew :android:test    # tools, Room, GGUF metadata
./gradlew :app:test        # ChatViewModel
./gradlew :app:assembleDebug   # the APK; compiles llama.cpp, slow
```

`:core:test` is the loop you should run constantly. It is seconds. If your change makes it slow,
or makes it need an emulator, you have put a platform dependency in the wrong module.

`:core:evals` runs a scripted model, not a real one. `--model` is accepted and ignored today.

## Adding a tool

Tools live in `android/src/main/kotlin/dev/localintelligence/android/tools/<category>/`. The full
contract is in [docs/tool-contract.md](docs/tool-contract.md); the short version:

1. Pick the category, and match the package you are writing in.
2. Name it `verb.noun`, lowercase, dotted: `files.search`, `alarm.cancel`.
3. One-sentence description, starting with a verb, saying what it **returns**.
4. A real JSON Schema with `properties` and `required`.
5. Pick the risk honestly. If you are unsure, it is `DESTRUCTIVE`. `requiresConfirmation` is
   derived from the risk enum — do not try to declare it.
6. Add 4-8 retrieval tags: the words a user would actually type, including synonyms and
   colloquialisms. This is the highest-leverage part of the whole definition.
7. Implement `execute` with argument coercion, cancellation checks, and no escaping throws. Take
   a `Context` as a constructor parameter and put the platform calls behind an interface so the
   logic is testable on the JVM.
8. Write the `observation` by hand. Never dump the data object. Budget is 2048 characters.
9. Test all nine cases: granted, denied, empty result, success, invalid args, large result, API
   failure, cancellation, observation size.

Step 9 is not a rubber stamp. The permission-denied case is the one that matters most: a
permission denial must become `ToolError.PermissionDenied`, never a crash and never a retry
storm. "Should I charge before leaving" on a denied battery permission should cost one attempt.

Then register it in `app/.../AppContainer.androidTools()` — which currently returns
`emptyList()`. That empty list is a known gap, not an oversight by you.

## What a good PR looks like

- One concern. A tool PR is a tool PR; it does not also refactor the loop.
- Tests that fail before the change and pass after. If a test passes without the change, it is
  not testing the change.
- Real numbers in the description: the eval delta, the retrieval hit rate, the test count.
  "Improves reliability" is not a number.
- Worst-case RAM if you added a cache, buffer, or index. "It is small" is not a number.
- Notes on what you verified and what you did not. Unverified claims belong in the description
  as unverified claims.
- No reformatting unrelated files. A diff that touches 40 files is 40 files to review.

## Anti-patterns this repo has already been hit by

These are real. They are the reason several seams look more defensive than a normal codebase
needs them to be.

**Independent agents inventing duplicate abstractions.** During the swarm build, two agents wrote
their own `GateOutcome` and their own validation seam so neither had to wait for the other. Both
compiled, both passed their own tests, and the result was a mapping function in
`RealAgentControllerRunner.kt` that exists solely to bridge them. Before writing an interface,
check whether one already exists. Extending a frozen type is almost always cheaper than
introducing a parallel one.

**A harness graded by its own tests.** The eval suite originally ran a reference
`ReferenceAgentLoop` — a faithful double written before `AgentController` existed. It scored
100% for a long time, and that number meant almost nothing: it was the double agreeing with
itself. The suite now drives the real `AgentController`, and the double is kept only so
`HarnessSelfTest` can prove the two layers still agree. When a test passes, ask what it would
take for it to fail. A benchmark that cannot fail is not a benchmark. `HarnessCanFailTest` exists
to keep the suite honest about this.

**A harness that measured a double instead of the real thing.** The tool retrieval benchmark
tests real `LexicalToolSelector` behaviour against real tool definitions, but it cannot import
the real tools — `:core` may not depend on `:android`. So it declares 25 tool specs as stubs. When
you add a real tool, add its spec there too, or the benchmark is quietly measuring a set that
does not exist.

**A validator that rejected every legitimate argument.** `ToolCallValidator` shipped rejecting
essentially all real calls, because its test suite tested the validator against fakes that were
serialized the same wrong way as the validator itself. Both agreed, and both were wrong about
JSON. Tests that only exercise a type's own idea of its input are decoration.

**Flags that are accepted and ignored.** `:core:evals --model <path>` parses the argument and
does nothing with it, because the suite has no real backend. This is a trap for anyone reading
CI output: a 100% line that looks like a model result. If you add a flag, either implement it or
make it fail loudly. Do not add more no-op flags.

**An empty list posing as a seam.** `AppContainer.androidTools()` returns `emptyList()` with a
comment explaining why. That was the right call at the time — the tool workstreams were landing
in other PRs and touching their files would have been wrong. The result is that the app builds,
the README implied a working assistant, and the registry is empty. A placeholder that
type-checks is still a placeholder; document it as one.

**Documentation that describes an aspiration.** The previous README claimed grammar-constrained
generation meant the model "physically cannot emit a malformed action", and listed building and
evals commands that were never all run together. `AgentController` passes `grammar = null`. If
you write a capability down, verify it first. If you cannot verify it, write that you could not.

## Working on this repo

Branch from `main`, one branch per change, never push to `main`. If you are working in parallel
with other agents, use your own `git worktree` so you cannot collide with them.

Verify before you push. `./gradlew :core:test` is seconds and catches most mistakes. Push and
open a PR with the real output in the description.

The eval suite is the project's measuring instrument. If a change does not move a number, it is
either neutral or it is a code path that nobody will test.
