# Architecture

> **LocalIntelligence is not Hermes for Android.**
>
> LocalIntelligence is a minimal agent loop designed to make small local language models useful on Android.
>
> **Complexity is considered a regression unless it measurably improves agent task success,
> inference efficiency, Android capability, or reliability.**
>
> The model should see as little context and as few tools as necessary.
>
> Deterministic software should solve deterministic problems. The LLM is used only for
> decisions that require language understanding or reasoning.
>
> Every major feature should be measurable. Today it is not: the eval suite was
> deleted, so nothing in this repository is measured except the build and the
> GGUF-derived RAM model. See docs/evals.md.

Read this before writing any code. If your change cannot be justified against one of the four
criteria above, do not make it.

---

## 1. Product definition

**LocalIntelligence** — a minimal Android-native agent harness designed specifically for small local LLMs.

The v0 flow:

```
install APK
  -> download/import local model
  -> chat with agent
  -> agent remembers useful information
  -> agent performs Android-native actions
  -> agent chains 2-10 actions reliably
```

Target tasks:

```
"What's on my calendar tomorrow?"
"Find Dario in my contacts and copy his number."
"Remember that my Home Assistant server is 192.168.1.20."
"Find the PDF I downloaded yesterday and share it."
"Set an alarm for 8:30."
"Check battery level and tell me whether I should charge before leaving."
"Look up X on the web and save the answer to a note."
```

### Explicitly NOT in v0

Autonomous coding, browser computer-use, multi-agent, long-running autonomous agents,
Linux environment, Python, Git, cron, MCP, skills marketplace, voice assistant,
Accessibility automation, root/Shizuku, cloud sync.

---

## 2. The one architectural decision that matters

**:core is a pure Kotlin/JVM module with ZERO Android dependencies.**

No Android plugin. No `android.*` imports. Ever. Not even transitively through a dependency.

Everything that decides anything lives there:

- the agent loop
- tool registry, selection, validation
- loop detection
- context building, budgeting, compaction
- memory search and session summary

Consequences:

1. `./gradlew :core:assemble` runs in seconds. No emulator, no device, no SDK, no NDK.
2. A swarm of 20 agents can work in parallel without stepping on each other's build state.
3. The parts most likely to be wrong (the loop, the selection, the budget) would be the
   easiest to test — **if this project had tests. It does not.** The test suite and the
   fake-backed eval harness were deleted at the owner's explicit instruction, because the
   harness proved a fake could satisfy a fake. Verification is now a human running a real
   model on a real device. See docs/evals.md.

**:android** is the only module allowed to touch the Android framework.
**:app** is Compose and nothing else.

If you find yourself wanting to `import android.*` in `:core`, the design is wrong.
Push the platform out to a function type or an interface owned by `:core` instead.

---

## 3. Dependency graph

```
+---------------------------+
|        :app  Compose UI    |
+-------------+-------------+
              |
       AgentController  (dev.LocalIntelligence.core.agent)
              |
    +---------+---------+---------+
    |         |         |         |
 Context    Tools     Memory    Action
 Builder   Registry   Store     Parser
    |         |         |         |
    +---------+---------+---------+
              |
        ModelBackend
        /           \
  LlamaCppBackend  LiteRtBackend
```

No event bus. No DI framework. No microservices. No generic workflow engine.
No separate "agent framework" module with 70 interfaces.

If you need a dependency injected, it is a constructor parameter. That is the whole
DI system.

---

## 4. Module map

| Module | Type | Owns | May NOT |
|---|---|---|---|
| `:core` | Kotlin JVM | everything that decides anything | import `android.*` |
| `:android` | Android library | Android tool impls, Room, llama.cpp JNI | contain agent logic |
| `:app` | Android application | Compose UI, navigation, permissions UX | contain business logic |

Coarse on purpose. Do NOT add a Gradle module for a single tool, a single screen, or a
single workstream. A Gradle module per workstream would be the single most expensive
decision in this repo: it turns every cross-team change into a version bump and every
agent into a build-config author.

Package boundaries inside `:core` and `:android` give the same separation with none of
the cost.

---

## 5. The output protocol

The model produces exactly one of two things:

```kotlin
sealed interface AgentAction {
    data class Respond(val text: String) : AgentAction
    data class CallTool(val name: String, val arguments: JsonObject) : AgentAction
}
```

Not `thought`. Not `plan`. Not `critique`. Not `confidence`. Not `reflection`.
Not `subagent_request`. Not `state_mutation`.

Reasoning stays inside the model. Everything the runtime must reason about is handled
deterministically in Kotlin.

This is not minimalism for its own sake. Every extra output channel is a token the
model can spend wrong, a parse path that can fail, and a thing a 1B model must learn
before it can do anything useful.

---

## 6. The agent loop

Target: under 300 meaningful lines, in `:core`, with the platform injected.

```
session = sessions.current()

repeat(config.maxSteps) {
    tools  = toolSelector.select(task, session, maxTools = config.maxVisibleTools)
    prompt = contextBuilder.build(task, session, memory.search(task), tools)

    action = parse(model.generate(prompt))

    when (action) {
        Respond  -> { session.append(text); memory.onTaskComplete(); return Success(text) }
        CallTool -> {
            when (val v = validator.validate(action, tools)) {
                Rejected -> session.observe(v.observation)
                Valid    -> {
                    val verdict = loopDetector.check(action)
                    when (verdict) {
                        BLOCK -> return Stop(reason = "loop detected")
                        WARN  -> session.observe("You already tried exactly this. Change your approach.")
                        ALLOW  -> {
                            val result = tools.execute(action, context)
                            loopDetector.record(result)
                            session.observe(result.observation)
                        }
                    }
                }
            }
        }
    }

    contextManager.compactIfNeeded(session)
}

return StepLimitReached
```

No graph. No DAG. No LangChain-style executor. No planner node.

The loop is a `while` loop. If a change turns it into a graph, the change is wrong.

---

## 7. The tool contract

```kotlin
interface AgentTool {
    val definition: ToolDefinition
    suspend fun execute(args: JsonObject, context: ToolContext): ToolResult
}
```

### The one rule that matters

> `ToolResult.observation` is what gets fed back to the LLM.

Not raw Android objects. Not stack traces. Not 80KB of JSON. Not an exception
`toString()`. Not a cursor. The observation is a short, plain, already-truncated
string written for a model that has a 4K context window.

`ObservationTruncator.DEFAULT_BUDGET_CHARS = 2048` is the ceiling. Structured `data`
is for the UI and the database only; the model never sees it.

If your tool needs 10KB to describe its result, your tool is doing too much. Split it
or summarize it.

---

## 8. Execution policy

The **runtime** decides what needs confirmation. Never the model. Never the tool.

```
READ_ONLY               -> execute
REVERSIBLE              -> execute
EXTERNAL_COMMUNICATION  -> confirm
DESTRUCTIVE             -> confirm
PRIVILEGED              -> disabled in v0
```

Classification lives in `ToolDefinition.risk`. `ToolRisk.requiresConfirmation` is
derived, not declared, so a tool cannot lie about needing permission.

Do not ask the model whether something needs confirmation. A model that decides
whether to ask permission is a model that decides not to.

---

## 9. Context budget

Optimized explicitly for small models. Normal working target: **3-6K tokens**.

| Component       | Budget (tokens) |
| --------------- | --------------: |
| System prompt   |         300-600 |
| Current task    |          50-300 |
| Working summary |         300-800 |
| Memories        |         300-800 |
| Recent turns    |       500-2,000 |
| Tool definitions|       300-1,200 |
| Observations    |       500-2,000 |

Hard objective:

> A routine Android action should never require a 20K prefill.

On a phone, prefill is latency, prefill is battery, and prefill is the thing that makes
an app feel broken. Every token not sent is a token saved.

---

## 10. System prompt

Tiny, on purpose. See `SystemPrompts.BASE` in `ContextBuilder.kt`.

```
You operate this Android device on behalf of the user.

Use the available tools when required.
Never claim an action succeeded unless its tool result says it succeeded.
Do not repeat an action that already failed unless something relevant changed.
When the task is complete, answer concisely.
```

Then the currently selected tools are appended. That is all.

No 5,000-token philosophy document. A 1B model has no attention budget to spare and will
follow the first instruction it sees over the last one.

---

## 11. Tool selection

Lexical only in v0. No LLM, no embeddings, no vector database.

Score each tool on token overlap across `name`, `description`, `tags`, `category`,
plus an exact-substring hit on the full tool name. Return **3-6** tools.

Hard maximum **8** unless explicitly requested.

This is the single biggest context saving in the entire system, and therefore the
highest-value thing to benchmark. If you can improve the score function with a
deterministic change, do it and show the eval delta.

---

## 12. Context compaction

Trigger:

```
activeTokens > min(model.context * 0.65, config.workingLimit)
```

Compact into explicit slots, not a prose summary:

```
Task: ...
Progress: ...
Known facts: ...
Actions already taken: ...
Failures: ...
Remaining work: ...
```

A 3B model stays on task far better with labelled slots than with a fluent paragraph
about what happened. Prose summaries lose the failure list; slots do not.

Raw history stays in the database forever. Compaction only removes messages from the
*inference context*.

---

## 13. Loop detection

Entirely deterministic. No LLM involvement.

Identity of a call is `toolName + canonicalized(arguments)`, with object keys sorted
recursively so key order cannot create a false difference.

```
same call twice, same result -> warn the model
same call three times        -> block
same error twice             -> mark unavailable, annotate, stop re-prompting
N consecutive calls with an identical observation -> terminate
```

This exists so a confused small model burns four seconds and stops, instead of ten
minutes of battery.

---

## 14. Memory

Room only. No embeddings in v0. Search with SQLite FTS5.

Tables: `sessions`, `messages`, `memories`, `session_summaries`.

Return **3-5** memories maximum.

Two write mechanisms only:

1. **Explicit** — the user says "remember X", the runtime writes one memory row.
2. **Session summary** — old messages collapse into a summary when the window fills.

**Do not build automatic memory extraction.** No per-interaction classifier, no
deduplication model, no consolidation pass. That is a lot of inference for an
unmeasured benefit. Benchmark it later or never.

---

## 15. Constrained generation

P0, not a nice-to-have.

Build a grammar for exactly this shape:

```
action  := respond | tool_call
tool    := "calendar.search" | "calendar.create" | "contacts.search" | ...
```

Tool names are constrained to the **currently visible** set, so the model physically
cannot hallucinate a tool it was not offered. Arguments should be constrained as
tightly as practical.

"Please output valid JSON" is not a constraint. It is a hope.

Backends that cannot honour a grammar must report `supportsGrammar = false` so the
runtime knows to fall back rather than silently ignoring the request.

---

## 16. RAM budget

This is a phone. RAM is the scarcest resource on the device, more than CPU.

**No RAM figure in this document or anywhere else in this repository has been
measured on a device.** An attempt to boot an AVD for the measurement failed
(SIGSEGV in the emulator) and was refused rather than replaced with an estimate.
The fit model is derived from real GGUF headers and 160 measured GGUFs — see
docs/memory-model.md — but the app's own resident cost is **unmeasured**. The
procedure to measure it is written down there, in section 6.1.

- The model is loaded into native memory, never onto the JVM heap.
- One model resident at a time. Loading a second unloads the first.
- The tool registry, the session store, and the context builder must be able to run in
  a few megabytes. No unbounded in-memory caches, ever.
- `unload()` is not optional. It is the difference between an app the user keeps and an
  app that gets killed.

When you add a cache, a buffer, or a precomputed header, state its worst-case size in
the PR description. "It is small" is not a number.

### There is exactly one GGUF parser

`dev.localintelligence.core.model.gguf.GgufParser` is the only implementation of the
GGUF container format in this repository. `:android` calls it; nothing else parses
GGUF. This section records why, because a second parser existed and was deleted, and
the reasoning needs to outlive the diff.

A header parse is a small, total function of a byte range, so it does not look like
something that needs a boundary. It did, and the boundary cost the product its
primary metric.

**What the deleted parser got wrong.** The `:android` copy
(`dev.localintelligence.android.inference.GgufMetadata`, 701 lines) parsed the same
format and produced a different RAM estimate than the one shown at download time. All
figures below were produced by running both parsers, in one JVM, against the same
real files. Nothing here is estimated or argued.

Same model, same context (4096), `Spark-X2.5-1.7B-Q4_K_M.gguf`, 1,107,457,888 B:

| quantity | deleted `:android` parser | `:core` (survives) | delta |
|---|---|---|---|
| weights | 1,107,457,888 B | 1,102,073,856 B | +5,384,032 B (the header) |
| weights, when file length is unknown | **0 B** | 1,102,073,856 B | −1,102,073,856 B |
| runtime buffer | 134,217,728 B flat | 67,108,864 B | −67,108,864 B |
| total @ ctx 4096 | 1,477,164,384 B | 1,404,063,744 B | +73,100,640 B |

The zero is the one that mattered. `RamEstimate.from` fell back to
`parameterCount * 4.83 / 8` when the file length was unavailable — the *normal* case
for a stream-backed SAF provider — and that `parameterCount` came from
`general.parameter_count`, which this model's converter did not write. A 1,056 MiB
model was reported as **352.58 MiB**, and it is 1,339.02 MiB. The error is a factor of
3.8, and it points at "this fits", which is the direction that gets a user to tap
load on a model that cannot be loaded.

Three more, same cause:

- **`fileSizeBytes` is not the weight section.** It includes the header. Every model
  was overstated by its header size (5.13 MiB here).
- **4.83 bits/weight was applied to every quantisation.** It is the Q4_K_M effective
  width. Used as a fallback for the same model's BF16 sibling — 3,420,931,680 B on
  disk — it is 3.4x too small.
- **The `general.file_type` label table is misaligned from id 22 upward.** A real
  IQ4_XS model (id 30) displayed as `UNKNOWN_30`; BF16 (id 32) as `UNKNOWN_32`.
  `:core`'s table is correct on both.

The vocabulary was also guessed from embedding width (32k / 151,936 / 128,256)
because the old comment asserted the token count "is not a header field". It is: the
surviving parser retains the array's element count, so the real 131,072 is available
without decoding a single token.

**Why the merge was possible.** The concern with moving parsing into `:core` was that
the Android side holds things `:core` cannot be handed. It holds an
`AssetFileDescriptor`. That turns out not to bind:

- `GgufByteSource.ofStream(InputStream)` already exists in `:core` and is pure JVM.
  The `AssetFileDescriptor.createInputStream()` the importer already opens satisfies
  it directly. No Android type crosses the boundary; `:core` still imports zero
  `android.*`.
- Running `GgufParser.parse(GgufByteSource.ofStream(...))` against a real
  1,107,457,888 B file produced **byte-identical tensors and an identical
  `declaredWeightBytes`** to parsing the same file through the `File` path. The only
  difference was `fileBytes`, because a 32 MiB buffer was standing in for a 1.1 GB
  file — which is why `ofStream` now takes `declaredSizeBytes` and the importer
  passes the length it already asked the platform for.

**What survives on the Android side, and why.** `:android` keeps only what has no
JVM equivalent: the descriptor and its `/proc/self/fd/N` path, the `ContentResolver`
length fallback, and `RamEstimate.usableDeviceBytes()`, which reads
`/proc/meminfo` and so cannot live in a pure-JVM module. `RamEstimate` itself is now
a thin adapter that re-evaluates `ModelMemoryEstimator` at whatever context the UI is
showing — it holds no arithmetic, which is the point.

`dev.localintelligence.android.inference.GgufMetadata` survives as a 12-line
projection with no logic, only because `LlamaCppBackend.deriveCapabilities` is typed
against that name and that file belongs to another agent. It parses nothing and
cannot disagree with the parser. It should be deleted when `LlamaCppBackend` is next
touched; that is a two-line change on that side.

**The rule this establishes.** A number the user sees must have exactly one
implementation. When a second one appears, it is not a layering decision, it is a bug
with a review comment on it.

---

## 17. Execution lifecycle

```
user starts a task -> execution alive -> task finishes -> stops
```

Not an immortal daemon. Android deliberately restricts background foreground-service
starts, and Android 15+ adds time limits on some foreground-service types. Design
around the lifecycle you are allowed, not the one you want.

For deferred scheduled work later, use WorkManager.

---

## 18. Definition of done

A PR is not done because it compiles. It is done when:

```
permission granted      -> correct result
permission denied       -> correct ToolError.PermissionDenied, not a crash
empty result            -> correct empty observation
successful result       -> correct observation
invalid arguments       -> correct ToolError.InvalidArguments
large result            -> observation still under budget
Android API failure     -> no exception escapes
cancellation            -> returns promptly, and CancellationException is
                           rethrown rather than swallowed
```

Every row above is a manual check on a real device. `CancellationException`
remains a hard invariant: structured-concurrency cancellation is rethrown, never
converted into a failed `ToolResult`.

`observation.length <= ObservationTruncator.DEFAULT_BUDGET_CHARS`, or the tool carries
an explicit documented exemption.

---

## 19. Rules for every change

```
Do not redesign architecture.
Do not add dependencies unless it is genuinely necessary.
Do not create abstractions for hypothetical future use.
Do not modify interfaces owned by another workstream.
Do not add cloud dependencies.
Do not add telemetry.
Do not introduce MCP.
Do not implement features outside the assigned issue.
Prefer Android/Kotlin platform APIs over wrappers.
Keep model-visible outputs extremely compact.
```

"Do not restore the deleted test suite" replaced "All functionality requires
tests". That rule described a suite that no longer exists, and left in place it
reads as an instruction to rebuild one. The deletion was deliberate; see
docs/evals.md. A pure-JVM `:core` unit-test suite is being restored separately.

If you believe an interface is wrong, open an issue. Do not change it in your PR.
A swarm that can redesign the contracts is not a swarm, it is 20 authors.
