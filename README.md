# LocalIntelligence

**A minimal Android-native agent harness designed for small local LLMs.**

LocalIntelligence is not a framework. It is a small, auditable agent loop plus a set of Android
tools, sized so that a 1-4B parameter model running entirely on the phone can still
complete real multi-step tasks.

```
install APK
  -> import a local model
  -> ask for something
  -> the agent calls Android APIs, remembers what matters, and answers
```

**Runs fully on-device. No account, no server, no telemetry.** Inference is
entirely local, and the app has no analytics or crash reporting. Two features do
use the network, both of them opt-in and neither silent:

- **Model downloads** go to the Hugging Face Hub and only fetch files. Nothing
  is ever uploaded. See [docs/hf-hub-download.md](docs/hf-hub-download.md).
- **`web.fetch`** is a tool the model can call, and it can reach any public
  HTTP(S) address — which means any text it puts in a URL is sent to that host.
  It refuses loopback, private, and link-local addresses, but a model can still
  fetch a hostile page, and that page's contents re-enter the conversation as
  untrusted data. See [docs/threat-model.md](docs/threat-model.md) for what
  that does and does not protect against.

Nothing else in the app opens a socket, and no conversation content, memory,
file, contact, or calendar entry is transmitted by the app itself. If you do
not want the model making outbound requests at all, do not install a model that
has the `web.fetch` tool available.

---

## Why this exists

On-device models got small enough to be useful and are still too small to be trusted
with a large context, many tools, or a complicated protocol. Most agent frameworks
assume a frontier model on a server. LocalIntelligence assumes the opposite and optimizes
everything for that assumption:

- **Constrained output.** Grammar-constrained generation means the model physically
  cannot emit a malformed action or hallucinate a tool it was not offered.
- **Small tool surface.** 3-6 tools visible per turn out of a registry of 25, chosen
  by lexical scoring. Not a second LLM call.
- **Deterministic reliability.** Loop detection, argument validation, risk gating and
  context budgeting are plain Kotlin. The model is used only where language
  understanding is actually required.
- **No cloud, no telemetry, no account.** The app is on-device end to end.

> Complexity is considered a regression unless it measurably improves agent task
> success, inference efficiency, Android capability, or reliability.

## Status

**It builds. Almost nothing about it is measured.**

`:app:assembleDebug` produces an installable debug APK from a clean checkout,
and CI proves it on every push — green as of run
[36196368752](https://github.com/riceharvest/LocalIntelligence/actions/runs/36196368752).
See [docs/build.md](docs/build.md). Beyond that:

- **There are no tests.** The test suite and the fake-backed eval harness were
  deleted at the owner's explicit instruction, along with all test-only CI jobs.
  Do not propose restoring them as a goal.
- **There is no eval runner**, so the project's central question — how small
  can the model get before the loop stops being reliable — is unanswered.
- **RAM is unmeasured.** The fit model is derived from real GGUF headers and
  measured quant tables, but no `dumpsys meminfo` figure has been taken on any
  device. The procedure is written down; the number is not.
- **Performance is unmeasured.** The only generation rate ever observed is
  ~0.67 on an x86_64 *emulator*, which says nothing about a phone and is not a
  device number. [docs/measurements.md](docs/measurements.md) is the
  authoritative list of what has and has not been measured.
- **NPU acceleration is unreachable** with the pinned LiteRT-LM 0.13.1. The GPU
  path is OpenCL. See [docs/acceleration.md](docs/acceleration.md).

See [docs/roadmap.md](docs/roadmap.md) for the item-by-item state.

## Architecture in one paragraph

`:core` is a pure Kotlin/JVM module with zero Android dependencies — the agent loop,
tool registry, selection, loop detection, context building, compaction and memory
search. `:android` is the only module allowed to touch the Android framework.
`:app` is Compose. There is no DI framework, no event bus, and no workflow engine;
a dependency is a constructor parameter. The loop is a `while` loop, deliberately:
no planner node, no critic, no reflection pass, and no ADK.

Full detail: [docs/architecture.md](docs/architecture.md).

## Documentation

| Document | What it is |
|---|---|
| [docs/build.md](docs/build.md) | **Start here.** How to build, what the native build needs, why CI was red, what is measured. |
| [docs/architecture.md](docs/architecture.md) | North star, module map, loop, budgets. |
| [docs/tool-contract.md](docs/tool-contract.md) | The frozen tool contract. Implement, do not redesign. |
| [docs/agent-loop.md](docs/agent-loop.md) | The loop, step by step, with failure paths. |
| [docs/roadmap.md](docs/roadmap.md) | What is built, what is verified, what is deliberately not. |
| [docs/evals.md](docs/evals.md) | Why there is no eval suite, and what is unmeasured. |
| [docs/memory-model.md](docs/memory-model.md) | The RAM fit model: derivation, what is measured, and the device procedure. |
| [docs/acceleration.md](docs/acceleration.md) | GPU/NPU reality on LiteRT-LM 0.13.1. |
| [docs/litertlm-backend.md](docs/litertlm-backend.md) | The second backend and why it cannot load a model yet. |
| [docs/hf-hub-download.md](docs/hf-hub-download.md) | Hugging Face download, resume, gating, auth. |

## Building

Requires **JDK 21** and an Android SDK (platform 36, an NDK, CMake 3.22.1).

```bash
export JAVA_HOME=/path/to/jdk-21
export ANDROID_HOME=/path/to/android-sdk

# llama.cpp is pinned to tag b4661 and is not vendored.
git clone --depth 1 --branch b4661 https://github.com/ggml-org/llama.cpp /tmp/llama.cpp

PIDROID_LLAMA_DIR=/tmp/llama.cpp ./gradlew :app:assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk` — **MEASURED** at
~83.9 MB (80.0 MiB, debug, unstripped, both ABIs). CI builds this on every push
and uploads it as an artifact, so you can install exactly what CI built.

Full instructions, the SDK components CI installs, and two traps that will
otherwise cost you an hour: [docs/build.md](docs/build.md).

## Contributing

Read [docs/architecture.md](docs/architecture.md) and
[docs/tool-contract.md](docs/tool-contract.md) first. Both are binding.

The short version:

```
Do not redesign architecture.
Do not add dependencies unless it is genuinely necessary.
Do not create abstractions for hypothetical future use.
Do not modify interfaces owned by another workstream.
Do not add cloud dependencies or telemetry.
Do not introduce MCP.
Prefer Android/Kotlin platform APIs over wrappers.
Keep model-visible outputs extremely compact.
```

Two rules that are easy to get wrong here:

- **Do not restore the test suite or add a test CI job.** The deletion was
  deliberate. Verification means running a real model on a real device.
- **Do not quote a performance or RAM number that was not measured.** There are
  none. Write "unmeasured" and say how to measure it.

## License

Apache-2.0. See [LICENSE](LICENSE).
