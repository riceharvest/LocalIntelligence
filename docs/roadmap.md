# Roadmap

Ordered by what unblocks the next thing, not by what sounds most impressive.

---

## Now: the vertical slice

**A real local model decides to call `device.battery`, receives the real Android
result, and answers correctly.**

```
user: "how much battery do I have?"
  -> grammar-constrained tool call
  -> device.battery
  -> real BatteryManager reading
  -> observation: "Battery 43%, unplugged, ~4h 20m remaining"
  -> "You have 43%, about 4 hours left."
```

Nothing else ships until this works reliably on a real device. Every additional workstream
before this point multiplies integration work instead of reducing it.

## Wave 1 — substrate (in progress)

```
[x] Frozen contracts: ModelBackend, AgentTool, AgentAction, ToolRisk
[x] Pure-JVM :core that builds and tests in seconds
[x] Architecture + tool contract docs
[x] AgentController loop
[x] FakeModelBackend + ScriptedTool — the deterministic test harness
[x] Eval runner + 50-task suite
[ ] llama.cpp Android ModelBackend (JNI, GGUF import, streaming, grammar, cancel)
[ ] Room session + memory store (FTS5)
[ ] First 3 Android tools: device.battery, device.info, clipboard.write
[ ] Minimal chat UI
```

## Wave 2 — breadth

Only after the slice is green:

```
Lexical tool selection tuned against the eval suite
Context compaction
Loop detection wired into the loop
Permission policy + confirmation UX
Remaining ~17 tools
Model manager (import GGUF, RAM estimate, context size)
Debug trace view
```

## Wave 3 — comparison

```
LiteRT-LM as backend #2, behind the same ModelBackend
Benchmark both against the same 50-task suite
```

## v0 freeze criteria

```
[ ] APK installs normally
[ ] imports a GGUF
[ ] runs completely offline
[ ] streams responses
[ ] constrained tool calls work
[ ] 15+ Android operations
[ ] runtime selects relevant tools
[ ] confirmation for risky actions
[ ] session persistence
[ ] durable memory
[ ] context compaction
[ ] deterministic loop detection
[ ] model cancellation
[ ] task traces in the debug UI
[ ] >=80% success on the basic eval suite with a ~3-4B model
[ ] >=65% on the multi-step suite
[ ] no routine task needs >8K context
```

Those two success thresholds are engineering targets, not predictions.

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

That is the number that decides whether this architecture is right. A harness that
needs a 7B model to work is a harness with too much context, too many tools, or too
loose a protocol — and the eval suite is how you find out which.
