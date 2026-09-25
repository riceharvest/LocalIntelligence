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

Runs fully offline. No account, no server, no telemetry.

---

## Why this exists

On-device models got small enough to be useful and are still too small to be trusted
with a large context, many tools, or a complicated protocol. Most agent frameworks
assume a frontier model on a server. LocalIntelligence assumes the opposite and optimizes
everything for that assumption:

- **Constrained output.** Grammar-constrained generation means the model physically
  cannot emit a malformed action or hallucinate a tool it was not offered.
- **Small tool surface.** 3-6 tools visible per turn out of the full registry, chosen
  by lexical scoring. Not a second LLM call.
- **Deterministic reliability.** Loop detection, argument validation, risk gating and
  context budgeting are plain Kotlin. The model is used only where language
  understanding is actually required.
- **Measured, not vibes.** Every feature has to move a number in the eval suite.

> Complexity is considered a regression unless it measurably improves agent task
> success, inference efficiency, Android capability, or reliability.

## Status

Early. The agent loop, contracts, and eval harness are the current focus; see
[docs/roadmap.md](docs/roadmap.md).

## Architecture in one paragraph

`:core` is a pure Kotlin/JVM module with zero Android dependencies — the agent loop,
tool registry, selection, loop detection, context building, compaction, memory search,
and the entire eval suite, all unit-testable on the JVM in seconds with no emulator.
`:android` is the only module allowed to touch the Android framework. `:app` is
Compose. There is no DI framework, no event bus, and no workflow engine; a dependency
is a constructor parameter.

Full detail: [docs/architecture.md](docs/architecture.md).

## Documentation

| Document | What it is |
|---|---|
| [docs/architecture.md](docs/architecture.md) | North star, module map, loop, budgets. Read first. |
| [docs/tool-contract.md](docs/tool-contract.md) | The frozen tool contract. Implement, do not redesign. |
| [docs/agent-loop.md](docs/agent-loop.md) | The loop, step by step, with failure paths. |
| [docs/roadmap.md](docs/roadmap.md) | What is being built now, and what is deliberately not. |
| [docs/evals.md](docs/evals.md) | The eval suite and how to read its output. |
| [docs/memory-model.md](docs/memory-model.md) | **The RAM fit model**: how the "will this fit" number is derived, what is measured and what is still an estimate, and how to measure the app's real resident cost on a device. |
| [docs/hf-hub-download.md](docs/hf-hub-download.md) | Downloading from the Hugging Face Hub, resuming, gating, auth. |

## Building

```bash
./gradlew :core:test          # the whole brain, on the JVM, in seconds
./gradlew :app:assembleDebug  # the APK (needs Android SDK + NDK)
./gradlew :core:evals         # run the agent eval suite
```

`:core` deliberately requires no Android SDK. If you cannot run `:core:test` in a few
seconds, something has leaked a platform dependency into the core.

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
All functionality requires tests.
Prefer Android/Kotlin platform APIs over wrappers.
Keep model-visible outputs extremely compact.
```

## License

Apache-2.0. See [LICENSE](LICENSE).
