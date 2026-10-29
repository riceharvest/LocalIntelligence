# Roadmap

Ordered by what unblocks the next thing, not by what sounds most impressive.

Every box below is checked against the source tree. Where work was deleted, the
box says so rather than staying green.

---

## Status in one paragraph

The agent loop, the tool registry, the llama.cpp backend, the LiteRT-LM backend,
Room persistence, the Hugging Face download, and the Compose UI are all written
and **compile**. The app assembles into an installable debug APK.

There are no tests, no CI, no eval suite, no measured performance or RAM number, and no
physical-device observation of any kind. See [`evals.md`](evals.md) and
[`build.md`](build.md).

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

**Status: NOT verified end to end.** The pieces exist; the whole path has not
been observed on a real device. A 1.1B model has loaded and generated text on an
x86_64 AVD, but the tool-call round trip has not been recorded.

---

## Wave 1 — substrate

```
[x] Frozen contracts: ModelBackend, AgentTool, AgentAction, ToolRisk
[x] Pure-JVM :core compiles with no Android dependencies
[x] Architecture + tool contract docs
[x] AgentController loop
[x] Action parser + grammar builder
[x] Context builder + compaction
[x] llama.cpp Android ModelBackend (JNI, GGUF, streaming, grammar, cancel)
[x] LiteRT-LM second ModelBackend behind the same interface
[x] Room session + memory store
[x] Minimal chat UI
```

### Done and then deliberately removed

```
[-] FakeModelBackend + ScriptedTool — the deterministic test harness
[-] Eval runner + 50-task suite
[-] The entire conventional test suite
```

**These were not lost. They were deleted at the owner's explicit instruction**,
together with CI. `core/build.gradle.kts` records the
reason: the harness ran against fakes rather than a real model, reported 50/50,
and "verified" an app that could not answer a single question.

**Do not propose restoring them as a goal, and do not add CI workflows.**
Anyone quoting a test count or pass rate from this repository is quoting a
deleted suite.

---

## Wave 2 — breadth

```
[x] Context compaction
[x] Loop detection wired into the loop
[x] Permission policy + confirmation UX
[x] 25 Android tools (see below)
[x] Model manager (import GGUF, RAM fit estimate, context size)
[x] Debug trace view
[ ] Lexical tool selection tuned against a real eval
[ ] Remaining tools
```

### Tools that exist

25, verified by the `name = "..."` fields in `:android`:

```
alarm.cancel  alarm.create  alarm.list
apps.list  apps.open  apps.share
calendar.create  calendar.search
clipboard.read  clipboard.write
contacts.get  contacts.search
device.battery  device.info  device.open_settings  device.vibrate
files.delete  files.list  files.read_text  files.search  files.write_text
notifications.dismiss  notifications.list  notifications.reply
web.fetch
```

Counted from V0ToolCatalogue, which `requireCatalogueAgreement` checks in BOTH
directions at composition time: every shipped tool must be catalogued, and every
catalogued tool must be shipped. That check is what makes this list trustworthy —
a count that can drift from the list is worse than no count.

The v0 freeze criteria ask for 15+; that threshold is met by count. Whether
they work correctly on a device is unmeasured — no tool has been run against
real Android APIs through the agent loop.

---

## Wave 3 — comparison

```
[x] LiteRT-LM as backend #2, behind the same ModelBackend
[ ] Benchmark both against the same task list
```

The second item is **not done and cannot currently be done**: there is no
task list runner, and LiteRT-LM has no model it can load. See
[`acceleration.md`](acceleration.md) — there is no GGUF-to-`.litertlm`
converter, so a user must hand-place a `.litertlm` file, and no such file has
been run on a device.

---

## Also shipped, and previously missing from this list

- **Hugging Face download** — repo search, ranged resume, SHA gate, gated-repo
  auth, `.part` file invariant. Live downloads are **UNVERIFIED**; see
  [`hf-hub-download.md`](hf-hub-download.md).
- **Background and scheduled execution** — a user can schedule a task and it
  fires. Lifecycle behaviour under Android background limits is unmeasured.
- **Streaming tokens to the chat** rather than one blob at the end.
- **Model-format routing** — the runtime picks llama.cpp or LiteRT-LM from the
  file's magic bytes, not a user toggle.

---

## v0 freeze criteria

Measured against the current tree. No box is green without a number or an
observation behind it.

```
[?] APK builds and installs        BUILDS: measured (see build.md).
                                    INSTALL: unverified, no device.
[?] imports a GGUF                 unverified on a device
[?] runs completely offline        by design; unverified on a device
[?] streams responses              implemented; unverified on a device
[?] constrained tool calls work    implemented; unverified end to end
[?] 15+ Android operations         25 exist; none verified on a device
[?] runtime selects relevant tools implemented; no eval to show it helps
[?] confirmation for risky actions implemented; unverified
[?] session persistence            implemented; unverified
[?] durable memory                 implemented; unverified
[?] context compaction             implemented; unverified
[?] deterministic loop detection   implemented; unverified
[?] model cancellation             implemented; unverified
[?] task traces in the debug UI    implemented; unverified
[ ] >=80% success on a basic eval suite     NO SUITE EXISTS
[ ] >=65% on the multi-step suite            NO SUITE EXISTS
[ ] no routine task needs >8K context        unmeasured
```

`[?]` means implemented-but-unverified. The last three have no instrument at all.

Those two success thresholds are engineering targets, not predictions, and they
are not currently measurable.

---

## Next: feature parity

**Goal: more features than every popular Android local-AI app, while staying
light.** A small core with capabilities added on demand, in the spirit of pi;
a companion that remembers and grows, in the spirit of Hermes Agent, without
Hermes's 64K-context harness.

Compared against, as of 2026-10-29:

| App | Why it is on the list |
|---|---|
| AnythingLLM Mobile | Closest positioning: "OpenClaw or Hermes, but on-device first". Default-assistant overlay, text-selection toolbar, on-device document chat, document generation, message/email drafts, scheduled jobs, bring-your-own provider. |
| Google AI Edge Gallery | `SKILL.md` agent skills, MCP client, FunctionGemma mobile actions, on-device speech in/out, LiteRT only. |
| PocketPal AI | The mainstream GGUF app: personas, web search, parallel tool calls, Whisper voice. |
| Off Grid | Vision, voice, image generation, knowledge base, per-phone model picks, backend speed test. |
| MNN Chat, ChatterUI, Maid | Plain model runners: the baseline for model load and speed. |
| Operit | The heavy end (terminal, screen control, browser agent). Mined for single features, not chased on count. |

OpenClaw for Android is a remote control for a server, not an on-device agent,
so it is not compared.

Grammar-constrained tool calls on any small GGUF model stay the differentiator.
Google bets on fine-tuning a 270M model for mobile actions instead; none of
the apps above constrains generation.

Build order. A box is ticked only with an observation behind it, as everywhere
in this file.

```
[?] llama.cpp b4661 -> b11988                   builds; not run on a device
[ ] the vertical slice on a real device          (see "Now" above)
[ ] voice: omni model through libmtmd            LFM3-Omni-2B: audio in, speech out
      fallback: a text model + PocketTTS/Soprano through the same libmtmd API
      last resort: Android SpeechRecognizer / TextToSpeech
[ ] vision input (photo, camera)                 libmtmd
[ ] default-assistant overlay + screenshot       AnythingLLM
[ ] text-selection toolbar (PROCESS_TEXT)        AnythingLLM
[?] share-sheet entry                            implemented; not seen on a device
[ ] quick-settings tile, "reply done" notification
[ ] OpenRouter key: image.generate, video.generate (async job + notification),
      speech, optional remote chat model         opt-in; no key = no network call
[ ] web.search                                   PocketPal, Off Grid, AnythingLLM
[ ] location, timers/reminders, message/email drafts
[ ] skills (SKILL.md, also personas) + "save as skill" after a successful run
[ ] user profile memory + past-chat search       Hermes
[ ] document chat on device                      embedding GGUF, vectors in Room
[ ] document generation (PDF, Markdown) + chat export
[ ] per-phone model picks + backend speed test   Off Grid
[ ] MCP client for remote servers                opt-in
[ ] LiteRT-LM 0.13.1 -> current
[ ] KV estimate for hybrid models                per-layer head_count_kv (LFM3)
```

Not chased, even though a competitor has it: a Linux terminal, Accessibility or
Shizuku screen control, a browser agent, on-device image generation,
multi-character group chat, a visual workflow builder, a messaging gateway.

---

## Explicitly deferred

**v0.2+** — only after the baseline works:

```
Shizuku             Accessibility automation    Home Assistant
```

Voice, vision, MCP, widgets, the share-sheet entry and LAN inference used to be
listed here. They are now part of the feature-parity plan below, or already
shipped (the share-sheet entry, model downloading, scheduled tasks).

**Considerably later, and only with a reason:**

```
Linux shell         coding environment    subagents
multi-agent         browser automation
```

**Not part of this project, and not planned:**

- **ADK (Agent Development Kit).** Deliberately excluded. The loop here is a
  `while` loop with constructor-injected dependencies. There is no planner node,
  no critic, no reflection pass, no subagent dispatch, and none is planned.
- **NPU / TPU acceleration.** Not a deferral — it is *unreachable*. LiteRT-LM
  0.13.1 ships no NPU library and no `Backend.GOOGLE_TENSOR`. The GPU path is
  OpenCL. See [`acceleration.md`](acceleration.md).

---

## The question the eval suite exists to answer

Not "which model scores highest?"

> **How small can the model get before the agent loop stops being reliable?**

That is the number that decides whether this architecture is right. A harness
that needs a 7B model to work is a harness with too much context, too many
tools, or too loose a protocol.

**The answer is currently unknown and this repository contains no evidence
about it.** Finishing the measurement work is the next real milestone.
