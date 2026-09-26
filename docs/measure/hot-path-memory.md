# Hot-path memory accounting

What the agent loop retains per run and per conversation, what is garbage, and
what was changed. Written from the code on `perf/verify-hot-paths`; no number
here was measured on a device, and the one place a number is needed the
document says so instead of supplying one.

**The product's primary metric is RAM, and the model plus its KV cache dominate
it. Every byte the agent's own code retains comes straight out of the model's
budget.** That is why the accounting below is in *retained* and *discarded*, not
in "objects allocated" — allocation churn is a GC problem, retention is a model
problem, and only one of them costs the model its weights.

---

## 1. The per-conversation structures

`Session` is one instance per app process (`AppContainer.session`), shared by
chat and by every scheduled run. It is the only long-lived agent-owned
structure on the path. It holds four things.

| Structure | Bound | Verdict |
|---|---|---|
| `messages` | `RetainedHistory.MAX_RETAINED_MESSAGES` = 32, enforced in `run()` after every run | Bounded, correctly. Left alone. |
| `workingSummary: CompactedState?` | five lists, replaced wholesale on compaction | Bounded by the compactor. Left alone. |
| `ids: IdentityHashMap<ChatMessage, Long>` | **was unbounded** | **Fixed.** |
| `writtenIds: MutableSet<Long>` | **was unbounded** | **Fixed.** |

### 1.1 `ids` — the real leak

```kotlin
private val ids = java.util.IdentityHashMap<ChatMessage, Long>()
fun idOf(message: ChatMessage): Long = ids.getOrPut(message) { nextIdentity++ }
```

`idOf` is called by the durable writer in `:app` for every message in the
window on every persist. `messages` is trimmed to 32 after each run, but
**nothing ever removed entries from `ids`** — a strong reference to every
`ChatMessage` the process had ever assigned an id to, for the life of the
process.

The cost is not the map overhead, it is what the keys hold. A
`ChatMessage.ToolObservation` carries up to `observationBudgetChars` = 2048
characters of tool output. In Java's UTF-16 that is up to ~4 KB of `char[]`
data. So each entry pins up to 4 KB of conversation text that nothing can
reach and nothing can free.

Sizing it from the code, not from a measurement: a run appends on the order of
`2 + 3 × maxSteps` messages (task, assistant turns, observations) — at
`maxSteps` = 8, roughly 20. A day of 30 scheduled runs is ~600 messages, of
which the observations dominate. **Order of a couple of MB retained that a
long-lived process can never release**, growing linearly with use and never
falling back. On a device where the model wants the RAM, that is the model
losing megabytes to a bookkeeping map.

`IdentityHashMap` also cannot shrink its backing array: `remove` nulls the slot
but leaves the table at its high-water capacity. So a process that once held a
large window keeps the large table — the churn is unavoidable, the capacity is
not. This is why the fix removes entries *incrementally* rather than rebuilding
the map, and why `nextIdentity` is left alone (see the KDoc on
`releaseUnreachableIdentities`).

### 1.2 `writtenIds` — smaller, same shape

`writtenIds` is a set of `Long`s, one per message ever written. The values are
16-byte objects, not 4 KB strings, so it is orders of magnitude cheaper than
`ids` — the same unbounded shape for about 1/250th of the cost. It is trimmed
in the same pass, on a threshold, because for this set the trim is hygiene and
for `ids` it is the fix.

### 1.3 `appendAssistant` is NOT truncated

Worth stating because it looks like a bug and is not. `appendAssistant` stores
the model's full reply while `appendToolObservation` requires the caller to
pre-truncate. An assistant turn is bounded by `maxTokens` at generation, a
few hundred characters to a couple of KB, and truncating it would lose content
the user is shown in the transcript. **Left alone deliberately**: the user sees
this text, so trimming it would trade correctness for a few KB.

---

## 2. Per-step garbage (not retention)

These are allocated and become collectable within the step. They are worth
fixing because the fix is free and local, but none of them was costing the
model memory between steps.

| Site | What it allocated, per step | Status |
|---|---|---|
| `Session.tokens` | the entire window rendered into a `StringBuilder`, then `toString()` — the whole conversation, every step | Early-out added |
| `memory.search` | a full `delegate.all(500)` table scan + `MemoryIndex.rank`, per step | Memoised per run |
| `Session.currentKeywords` | `filterIsInstance` materialised every user turn, a `StringBuilder` for one append, a `lowercase()` copy | Rewritten, identical output |
| `Regex("[^a-z0-9]+")` | a fresh **compiled** `Regex` per call — `Regex(x)` is a constructor, not a lookup | Hoisted to constants |
| `visible.map { it.definition }` ×3 | the same projection built three times per step in `buildRequest` | Derived once |
| `compactArgs(call.args)` ×2 | the same string built twice, one line apart | Built once |

### 2.1 `Session.tokens` — the one that was actually large

`compactIfNeeded()` runs at the bottom of every step and asked the session for
its token count. That built a `StringBuilder`, appended every message's
rendered text plus the working summary, called `toString()` on it, and handed
the result to `model.countTokens`. With a model resident that last call is a
real `llama_tokenize` over every character in the conversation — so each step
paid a full-window string build plus a full-window tokenisation, on a window
capped at 32 messages and ~2048 characters per observation.

**What was NOT done, deliberately:** `tokens` still builds the string and still
calls the real counter. Replacing it with `length / 4` would be cheaper and
would be a behaviour change — compaction would fire on a different step and the
model would be shown a different context. The trigger's firing point is
behaviour, so it stays exact.

Instead `cannotReachTokenLimit(limit)` runs first and skips the whole thing
when the window *provably* cannot reach the limit. The bound is a hard
inequality, not an estimate:

- a BPE tokenizer never emits more tokens than the text has characters, because
  every token consumes at least one character;
- `llama_bridge.cpp` tokenizes with `add_special=true`, so the slack is
  `SPECIAL_TOKENS` = 2 (a leading BOS and a trailing EOS);
- the resident-free approximation is `length / 4` plus one per newline, also far
  below the character count.

If the character count plus that slack is under the limit, the token count is
under it, `tokens` would have returned at or under the limit, and the caller
would have returned without compacting. **Same answer, not a near miss** — so
no conversation content, ordering, or tool result changes.

Two details that are load-bearing:

- the method **bails out when a working summary exists**, because
  `CompactedState.render()` walks five unbounded lists and builds a `String` —
  the exact allocation being avoided, and `oneLine` collapses newlines without
  truncating, so entry count does not bound length. Guessing a ceiling here
  would make the bound optimistic on exactly the steps that decide compaction.
- `render` and the bound share `OBSERVATION_SEPARATOR`, so the counted format
  and the rendered format cannot drift apart.

On a 32-message window of ordinary turns the early-out holds on most steps; on
the step where compaction genuinely fires it costs one pass over the window
reading `String.length`, and the exact path runs as before.

### 2.2 `memory.search` — the per-step re-query

`buildRequest` called `memory.search(task, config.memoryResults)` on every step.
The wired store is `LexicalMemoryStore`, whose `search` deliberately calls
`delegate.all(500)` — a **full table scan of up to 500 rows** plus a
`MemoryIndex.rank` across all of them. So one step cost one SQLite scan of the
entire memory table, and a run paid it up to `maxSteps` times to retrieve a
result keyed on a string that does not change for the whole run.

Memoised per run. This is not an approximation: the query is `task`, `task` is
assigned once in `run()` and never reassigned, and the only writer of the
memory table during a run is `rememberTurn()` — which runs on the
`AgentAction.Respond` branch, the branch that returns from the loop immediately
afterwards. Between the first step and the last, the table cannot change. A run
that never reaches `Respond` never writes at all, so there is no
invalidate-on-write case to handle. The memo is **reset in `run()`**, so a
controller that is somehow run twice serves the second task's memories.

A failed search is memoised too, on purpose: a store that throws on step 1 and
would succeed on step 5 is not worth retrying eight times inside one run, and
retrying turns a transient failure into eight database round-trips on the
critical path.

---

## 3. The Room read path during a run — checked, and already fine

Specifically asked, so stated: **the session does not re-query Room per step.**
`RoomSessionStore` is touched at three points only — `restore` on app start,
and the `appendMessage` batch at the end of a persist. The persist is driven by
`onWindowChanged` and by the run boundaries, not by the step loop, and it
appends only what `writtenIds` says is new. The per-step query that did exist
was the *memory* one in §2.2, not the session one.

`LexicalMemoryStore.all()` returning 500 rows into a list is transient garbage
held for the duration of one `search` call, not retention.

---

## 4. The JSON / tool-definition snapshots

`GrammarBuilder.forActions(definitions)` is called per step, and it builds a
GBNF grammar string by walking each tool's `JsonObject` schema. It is rebuilt
every step because the **visible tool set is re-selected every step** — that is
deliberate and load-bearing, not an oversight: the grammar decides which tools
are *callable*, and a small model routes differently as the conversation turns.

Consequence: caching the grammar per step would be wrong, and caching it across
steps would change which tools the model can call. **Left alone.** The
`visible.map { it.definition }` projections *were* duplicated inside
`buildRequest` and are now derived once; the grammar rebuild itself is
behaviour.

The `ToolDefinition` objects themselves are held by the registry, which is
built once at construction from a fixed list. They are shared, not copied, and
there is no per-run snapshot of them.

---

## 5. The compaction path

`ContextCompactor.compact` writes into six labelled slots and is bounded by
construction. `RetainedHistory.bound` is the post-run trim to 32. `foldWindow`
is the mid-run fold. All three are bounded. The one change here is
`releaseUnreachableIdentities()`, which runs after the post-run trim and drops
identity-map entries for messages the trim removed.

**Ordering matters and is asserted by the code:** the trim runs first, then the
prune, then the memory sample. Sampling between them would report a state that
never exists.

---

## 6. Instrumentation

`core/metrics/MemoryProbe.kt` (`:core`, pure JVM) +
`android/inference/AndroidMemoryReader.kt` (`:android`, the only file that
names `android.os.Debug`).

`MemorySample` holds either a measured value or `UNKNOWN_BYTES`. There is no
field that can hold an estimate, on purpose: a struct that can hold an estimate
is a struct that will eventually render one as a measurement.

Zero cost when disabled: `AgentController.memoryProbe` is nullable, and
`sampleMemory` returns on a null check **before** computing any argument — the
window walk only runs when a probe is attached. A probe can never fail a run;
every failure path is swallowed, because instrumentation that can end the run it
is measuring is instrumentation nobody will enable.

### Where the numbers surface

| Point | Label | Where it is read |
|---|---|---|
| After a model load, before any run | `model.loaded` | Self-check → "How much memory is this actually using?" |
| Before the first step | `run.start` | same card |
| After each step, after that step's messages are in the window | `step.N` | same card, as the peak-window row |
| After the post-run trim and prune | `run.end` | same card |
| Anywhere | — | `adb logcat -s PidroidMemory` |

`ProcessMemoryCheck` in `Diagnostics.kt` renders these at
`Provenance.MEASURED`. On a fresh install the journal is empty and the card
reports `CANNOT_TELL` with the procedure — an empty journal rendered as "0 bytes"
is a false-clean reading in the direction this project keeps failing in.

**No RAM figure appears anywhere in this document, because none has been
measured.** Every number above is a size derived by reading the code. The
release gate still needs a threshold agreed before any of these readings means
anything, and it needs to be taken on a physical phone — the emulator on the
development host is not a device.

### What it does not cover

- RSS is read via `Debug.getMemoryInfo.getMemoryStat("total_rss")`, which is
  absent or unparsable on some devices; that path reports `UNKNOWN_BYTES`
  rather than deriving an approximation from the dirty+clean getters, which
  would not be RSS under that name.
- Readings are taken at run and step boundaries, so a spike *between* them
  needs `docs/measure/measure_ram.sh` from a host.
- Nothing here is comparable across devices or models. A Q4 and a Q8 model are
  not the same product.
