# LocalIntelligence

An Android agent harness sized for small local models: a deterministic agent loop in pure Kotlin,
with a llama.cpp backend and a set of Android tools, so a 1-4B model running entirely on the phone
can complete multi-step tasks.

Pre-1.0 and experimental. Read [Status](#status--honest-limitations) before you rely on anything
here — two specific pieces of the system are not wired up yet, and one measurement is much worse
than the headline number suggests.

**Who this is for:** engineers evaluating whether a small on-device model can be made to do
reliable multi-step work on a phone, and engineers who want to read an agent loop with no
framework in it. If you want a working phone assistant today, this is not it — the app ships zero
registered tools, so there is nothing for the model to call.

## What works right now

Verified on `main` at `c9c894c`, JDK 17, Android SDK 36.

| | Status |
|---|---|
| Agent loop (`AgentController`, 387 lines, pure JVM) | Implemented, 24 direct tests |
| Action parser + GBNF grammar builder | Implemented, 56 tests |
| Loop detection, validation, risk gating, compaction | Implemented, covered by the loop tests |
| 50-task eval suite against a scripted model | **50/50 pass, mean 2.4 steps, mean 85 tokens** |
| Android tool set | **20 tools implemented**, 343 unit tests |
| Tool coherence + retrieval benchmark | 34/34 = 100% tuned; **12/25 = 48% on unseen phrasing** |
| Room session + memory stores (FTS5) | Implemented, 23 tests |
| llama.cpp backend, GGUF import, streaming, cancel | Built into the APK; **never run on a device** |
| Compose UI (chat, trace, model manager, permissions) | Builds; **not exercised on a device** |

Test totals, all green: **229** in `:core`, **416** in `:android` (343 tools + 73
inference/data), **16** in `:app`.

The 50-task result is real but narrow, and the distinction matters more than the number:
**it runs the real `AgentController` and the real `DefaultContextBuilder` against a
`FakeModelBackend` that replays a scripted answer per task.** It proves the loop, the parser, the
validator, the loop detector and the context builder are correct. It proves nothing about whether
a 1-4B model can produce those actions. No real model has been run through this harness.

## Status / honest limitations

**Not wired up:**

1. **The app registers zero tools.** `AppContainer.androidTools()` returns `emptyList()`. The 20
   tools exist in `:android` and are tested, but the shipping app hands the model an empty
   registry, so a real run has nothing to call. This is a one-line-per-tool wiring change in
   `AppContainer`, deliberately left as a seam rather than a fabricated registration.
2. **Constrained generation is not enabled.** `AgentController` passes `grammar = null` in its
   `GenerationRequest`. `GrammarBuilder` produces correct GBNF and `LlamaCppBackend` forwards
   `request.grammar` to llama.cpp, but the loop never populates it. So the strongest claim in
   `docs/architecture.md` §15 — that a hallucinated tool name is unspeakable — does not currently
   hold. Output is unconstrained; `ActionParserImpl` parses it leniently instead. Grammar
   support is verified by unit tests over the builder, not end-to-end.

**Unverified on this machine** — no Android device or emulator was available:

- That llama.cpp loads a GGUF and generates. The JNI library compiles and is present in the APK
  (`lib/arm64-v8a/libLocalIntelligence_llama_jni.so`, 5.0 MB; `x86_64`, 4.9 MB), and
  `LlamaBridge` returns typed failures instead of throwing when the library is absent — but no
  model has been loaded and no token has been generated on hardware.
- Streaming, cooperative cancellation mid-decode, and `unload()` behaviour under real memory
  pressure.
- That any of the 20 tools work against the real Android framework. Their tests use platform
  seams and fakes; the platform implementations (`AndroidDevicePlatform`, `AndroidAlarmPlatform`,
  `AndroidClipboardPlatform`) are untested.
- The end-to-end path: model emits a tool call, tool runs, result returns. This has never
  happened.
- Whether a 1-4B model can drive the `Respond` / `CallTool` protocol at all.

**Known weak spot, measured:** the lexical tool selector scores 100% on its tuned benchmark and
**48% on phrasings that were not used to pick the tags.** "what handset is this", "jot down that
the code is 1234", "nuke the screenshot" all retrieve the wrong tool. This is the single biggest
gap between the benchmark and real use, and it is a selector problem (stemming, synonyms,
embeddings), not a tagging problem.

**Not attempted:** streaming responses in the UI, model manager persistence, RAM/throughput
measurement, LiteRT-LM as a second backend, anything in the v0 freeze criteria beyond the above.

## Quick start

Requires JDK 17 and, for the APK, the Android SDK with NDK and CMake.

```bash
git clone https://github.com/riceharvest/LocalIntelligence.git
cd LocalIntelligence

export JAVA_HOME=/path/to/jdk17
export ANDROID_HOME=$HOME/Android/Sdk
```

If you are on a case-insensitive filesystem, a path containing a space can break the Gradle
configuration cache. A space-free path is strongly preferred.

```bash
# The whole agent, on the JVM, no emulator. This is the fast feedback loop.
./gradlew :core:test

# The 50-task eval suite against a scripted model.
./gradlew :core:evals

# Tools, Room stores, GGUF metadata parsing.
./gradlew :android:test

# The APK. Needs the NDK; the first build compiles llama.cpp and is slow.
./gradlew :app:assembleDebug
```

`:core:test` needs no Android SDK at all. That is the point of the module layout: if it takes more
than a few seconds, something has leaked a platform dependency into `:core`.

Building the native library pulls llama.cpp, which is fetched at build time from a pinned tag
(`b4661`) and is not vendored in this repository. `PIDROID_LLAMA_FETCH=1 ./gradlew :app:assembleDebug`
fetches it; `PIDROID_LLAMA_DIR=/path/to/llama.cpp` points at a checkout you already have. A cold
build compiles llama.cpp for two ABIs and takes several minutes and ~1-2 GB in `android/.cxx`.

**Note on `:core:evals`:** it accepts `--model <path>` and currently **ignores it** — the suite
has fakes only. That flag is a placeholder, and the 50/50 result above is a fakes result.

## Architecture

Three modules, coarse on purpose. Do not add a module per tool or per screen.

```
  :app      Compose UI, navigation, AppContainer, ExecutionService
    |
    |  AgentController (dev.localintelligence.core.agent)
    v
  :core     PURE KOTLIN/JVM — no android.* imports, ever
    |         agent loop, tool registry + lexical selection, loop detection,
    |         argument validation, context building + compaction, memory,
    |         action parser, GBNF grammar, the entire eval suite
    |
    |  ModelBackend (interface), AgentTool (interface)
    v
  :android  Room stores, llama.cpp JNI, GGUF import, 20 AgentTool impls
```

`:core` is the load-bearing decision. The agent loop, all decision logic and the whole eval suite
are plain JVM code, so the parts most likely to be wrong are the parts tested in seconds without
an emulator. CI enforces this — it greps `core/src/` for `import android.` and fails the build.

Dependency injection is constructor parameters. There is no DI framework, no event bus, and no
workflow engine.

The model may emit exactly two things:

```kotlin
sealed interface AgentAction {
    data class Respond(val text: String) : AgentAction
    data class CallTool(val name: String, val arguments: ToolArgs) : AgentAction
}
```

Everything the runtime must reason about — validation, loop detection, permission policy,
observation size, context budget — is deterministic Kotlin. The model is never asked whether
something needs confirmation. Full detail in [docs/architecture.md](docs/architecture.md).

## Tools

20 `AgentTool` implementations in `:android`, all with a real JSON Schema, a declared risk, and
a nine-case test matrix (granted / denied / empty / success / invalid args / large result / API
failure / cancellation / observation size).

| Tool | Risk | Permission |
|---|---|---|
| `device.battery` | READ_ONLY | — |
| `device.info` | READ_ONLY | — |
| `device.vibrate` | REVERSIBLE | `VIBRATE` |
| `device.open_settings` | REVERSIBLE | — |
| `clipboard.read` | READ_ONLY | — |
| `clipboard.write` | REVERSIBLE | — |
| `alarm.create` | REVERSIBLE | `SCHEDULE_EXACT_ALARM` |
| `alarm.list` | READ_ONLY | — |
| `alarm.cancel` | REVERSIBLE | `SCHEDULE_EXACT_ALARM` |
| `apps.list` | READ_ONLY | package visibility, API 30+ |
| `apps.open` | REVERSIBLE | — |
| `apps.share` | EXTERNAL_COMMUNICATION | — |
| `files.list` | READ_ONLY | `READ_EXTERNAL_STORAGE` / SAF grant |
| `files.search` | READ_ONLY | `READ_EXTERNAL_STORAGE` / SAF grant |
| `files.read_text` | READ_ONLY | `READ_EXTERNAL_STORAGE` / SAF grant |
| `files.write_text` | REVERSIBLE | `WRITE_EXTERNAL_STORAGE` (API <= 28) |
| `files.delete` | **DESTRUCTIVE** | SAF grant |
| `notifications.list` | READ_ONLY | notification access |
| `notifications.reply` | EXTERNAL_COMMUNICATION | notification access |
| `notifications.dismiss` | REVERSIBLE | notification access |
| `web.fetch` | READ_ONLY | `INTERNET` |

`requiresConfirmation` is **derived** from the risk enum (`DESTRUCTIVE` or
`EXTERNAL_COMMUNICATION`), not declared, so a tool cannot lie about needing permission. The
runtime decides — never the model, never the tool.

`calendar.*` and `contacts.*` appear in the eval benchmark's tool set but are **not implemented**
in `:android`; the benchmark declares their definitions to test the selector's behaviour at 25
tools. Anything the model observes is what the tool's `observation` string says, capped at
2048 characters. Never a raw Android object, never a stack trace.

## Evals

```bash
./gradlew :core:evals                        # 50 tasks, fakes, seconds
./gradlew :core:test                         # includes the tool retrieval benchmark
```

Real output from the 50-task run:

```
50 tasks | 50 pass (100%) | mean 2.4 steps | mean 85 tok | mean 0.0s
primary:   task success            1.00
secondary: success per 1k tokens   11.7 tasks

category counts: as specified
  single     10/10  mean 2.0 steps
  two        10/10  mean 2.7 steps
  multi      10/10  mean 3.3 steps
  memory      5/ 5  mean 2.2 steps
  ambiguity   5/ 5  mean 2.2 steps
  failure     5/ 5  mean 2.0 steps
  impossible  5/ 5  mean 1.4 steps
```

**These are scripted models, not a 1-4B model.** The suite asserts the loop behaves correctly
given the actions it is fed. It does not measure model capability. A real-model run is not
available; `--model` is accepted and ignored.

The tool retrieval benchmark runs as part of `:core:test` (`AndroidEvalTest`). To see its full
report:

```bash
./gradlew :core:test --tests 'dev.localintelligence.core.eval.AndroidEvalTest' -i
```

```
    COHERENCE
    all 25 tools satisfy every structural rule

    RETRIEVAL  (top-6, real LexicalToolSelector)
    hit rate:      34/34 = 100.0%   (floor 85%)
    plain cases:   100.0%
    hard cases:    100.0%

    GENERALIZATION PROBE  (same tools, utterances never used to pick a tag)
    hit rate:      12/25 = 48.0%   (tuned suite above: 100.0%)

    PROMPT BUDGET
    tools declared:      25
    full set prompt:     2280 chars (91 per tool)
    typical selection:   789 chars (6 tools)
    saving from selecting: 1491 chars (65%)

    VERDICT: pass  (retrieval 100.0% >= 85%, 0 coherence violations)
```

Note the probe: 48% on unseen phrasing. The benchmark's own comment is the honest reading — the
tuned suite is a regression guard, the probe is what a user who did not read the tool definitions
will experience.

`AndroidEvalMain.kt` exists and prints the same report, but **no Gradle task is registered for
it**, so `./gradlew :core:androidEvals` does not exist. Use the JUnit test.

## Documentation

| Document | What it answers |
|---|---|
| [docs/architecture.md](docs/architecture.md) | Why the project is shaped this way. The one architectural rule, module boundaries, budgets, and the rules for every change. Read first. |
| [docs/roadmap.md](docs/roadmap.md) | What is built, what is missing, and what is deliberately out of scope. |
| [docs/tool-contract.md](docs/tool-contract.md) | The frozen tool interface. What to implement when you add a tool, and the definition of done. |
| [docs/agent-loop.md](docs/agent-loop.md) | The loop step by step, with every failure path and why the ordering is what it is. |
| [docs/evals.md](docs/evals.md) | What the eval suite measures, what it deliberately does not, and how to read its output. |
| [docs/swarm-plan.md](docs/swarm-plan.md) | Why the work was partitioned by file rather than by feature. |
| [docs/wave1-contract.md](docs/wave1-contract.md) | The cross-agent signatures frozen so parallel work could not collide. |
| [CONTRIBUTING.md](CONTRIBUTING.md) | How to add a tool or a test without breaking a contract, and the anti-patterns this repo has already been bitten by. |

## Contributing

The rules, in full, in [CONTRIBUTING.md](CONTRIBUTING.md). The short version:

`:core` is pure JVM and stays that way. No cloud dependencies, no telemetry, no MCP. The tool
and backend interfaces are frozen — implement them, and if one looks wrong, open an issue rather
than changing it in a PR. Every feature needs a test and a number. Complexity is a regression
unless it measurably improves task success, inference efficiency, Android capability, or
reliability.

## License

Apache-2.0. See [LICENSE](LICENSE).
