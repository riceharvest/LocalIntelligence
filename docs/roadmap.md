# Roadmap

Ordered by what unblocks the next thing, not by what sounds most impressive.

Last reconciled against `main` at `c9c894c` by reading the code, not the plan.

---

## Where this actually is

The substrate is built. The vertical slice is not.

```
[x] Frozen contracts: ModelBackend, AgentTool, AgentAction, ToolRisk
[x] Pure-JVM :core that builds and tests in ~6s warm
[x] Architecture + tool contract docs
[x] AgentController loop (387 lines, 24 direct tests)
[x] Loop detection, argument validation, risk gating, context compaction
[x] Action parser + GBNF grammar builder
[x] Eval runner + 50-task suite, wired to the real loop (50/50, fakes)
[x] Room session + memory stores with FTS5 (23 tests)
[x] 20 Android tools across 7 categories (343 tests)
[x] llama.cpp JNI backend, GGUF import, streaming, cancel, model unload
[x] Compose UI: chat, trace, model manager, permission screen
[x] Tool coherence + retrieval benchmark (with a generalization probe)
```

229 tests in `:core`, 416 in `:android`, 16 in `:app`. All green. `:app:assembleDebug` produces
an APK with the native JNI library for `arm64-v8a` and `x86_64`.

**None of it has run on a device.** Everything above is verified by JVM unit tests and by a
successful build.

---

## The blocking gap

Two things stand between the current tree and a working assistant, and both are small:

```
[ ] Register the 20 tools in AppContainer.androidTools()   (returns emptyList())
[ ] Pass the real grammar: AgentController sets grammar = null
```

Until the first is done, a real run has an empty tool registry. Until the second is done, output
is unconstrained and the parser is doing tolerant recovery instead of the grammar making errors
unspeakable. `GrammarBuilder` and `LlamaBridge` already do their halves; this is wiring.

**Then, and only then:** load a real GGUF on a real device and answer one question end to end.

```
user: "how much battery do I have?"
  -> model emits a tool call
  -> device.battery executes against real BatteryManager
  -> observation: "Battery 43%, unplugged, ~4h 20m remaining"
  -> "You have 43%, about 4 hours left."
```

That transcript has not happened yet. Everything above it is preparation.

---

## Next

Ordered by what removes the most uncertainty per unit of work.

```
[ ] Register the tools, load a real model, prove the vertical slice
[ ] Fix tool retrieval on unseen phrasing (currently 48%)
[ ] Wire the grammar and measure whether it changes the failure modes
[ ] Verify streaming, cancellation and unload() on device
[ ] Exercise the platform tool impls (AndroidDevicePlatform and friends are untested)
[ ] RAM and throughput measurement on a real handset
[ ] Model manager that persists the imported model list
```

### Tool retrieval: the measured weak spot

The lexical selector scores 100% on the tuned benchmark and **48% on phrasings that were not used
to choose the tags**.

```
what handset is this           -> device.info          (score 0)
jot down that the code is 1234 -> files.write_text     (score 0)
nuke the screenshot            -> files.delete         (score 0)
get rid of the banners         -> notifications.dismiss (score 2)
```

This is the single largest gap between the benchmark and real use, and it is a **selector**
problem — stemming, synonyms, embeddings — not a tagging problem. More tags will make the tuned
number look better and the real number no better. `docs/architecture.md` §11 calls this the
highest-value thing to benchmark; the probe is why that is still true.

---

## Later

```
Second backend behind ModelBackend (LiteRT-LM)
Benchmark both against the same 50-task suite
Streaming responses surfaced in the UI
Debug trace view
```

---

## v0 freeze criteria

```
[ ] APK installs and runs on a physical device
[ ] imports a GGUF
[ ] loads it and generates
[ ] runs completely offline
[ ] streams responses
[ ] constrained tool calls work end to end
[ ] 15+ Android operations, registered and reachable
[ ] runtime selects relevant tools on unseen phrasing
[ ] confirmation for risky actions
[ ] session persistence
[ ] durable memory
[ ] context compaction
[ ] deterministic loop detection
[ ] model cancellation mid-decode
[ ] task traces in the debug UI
[ ] >=80% success on the basic eval suite with a ~3-4B model
[ ] >=65% on the multi-step suite
[ ] no routine task needs >8K context
```

The two success thresholds are engineering targets, not predictions. Everything above them is a
checkbox; the thresholds are the actual goal, and neither has been measured, because no real
model has been run through the suite.

---

## Explicitly deferred

**v0.2+** — only after the baseline works:

```
MCP client          Shizuku              Accessibility automation
Home Assistant      model downloading    vision
scheduled tasks     widgets              share-sheet entry
voice               LAN inference
```

**Considerably later, and only with a reason:**

```
Linux shell         coding environment    subagents
multi-agent         browser automation
```

---

## The question the eval suite exists to answer

Not "which model scores highest?"

> **How small can the model get before the agent loop stops being reliable?**

That is the number that decides whether this architecture is right. A harness that needs a 7B
model to work is a harness with too much context, too many tools, or too loose a protocol.

The honest current answer: **we do not know yet.** The 50-task suite runs scripted models, so it
measures the loop and not the model. The number that decides the architecture has not been taken.
