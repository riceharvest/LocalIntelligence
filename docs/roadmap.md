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
Status as of 2026-10-30. `[?]` = built and merged, compiles and packages, NOT
run on a device (that is the final test pass, see "Model test plan"). PR numbers
in brackets.

[?] llama.cpp b4661 -> b11988, JNI port                          [#68]
[?] settings schema, Simple/Technical modes, plugin seam, per-tier
      auto-accept; tool families as switchable plugins           [#70]
[?] llama.cpp sampler: top-k, penalty window, freq/presence      [#72]
[?] tool-call rows: verb titles, shape-coded status, folding
      Input/Output, "Used N tools" groups, live running row,
      inline Allow/Deny (dialog removed)                         [#77, #82]
[?] Google Tensor NPU via LiteRT-LM 0.17.1 (Gemma 4 E2B G5)      [#69]
[?] .litertlm download + import from the in-app hub              [#78]
[?] KV estimate for hybrid models (per-layer head_count_kv)      [#73]
[?] vision + audio input through libmtmd (mmproj next to model)  [#85]
[?] composer: attach photos, record voice / dictate              [#93]
[?] speech out in the model's own voice (LFM3 TTS via libmtmd),
      read replies aloud, phone-TTS fallback                     [#86, #87, #92]
[?] model's own chat format (llama_chat_apply_template)          [#90]
[?] default-assistant overlay + screen capture to vision         [#79, #93]
[?] text-selection toolbar, quick-settings tile, share entry     [#71]
[?] web.search (DuckDuckGo HTML, no key)                         [#74, #80]
[?] location.get, timer.set, message.draft, email.draft          [#75]
[?] skills (SKILL.md) + "save as skill"                          [#83]
[?] "About you" profile + history.search                         [#81]
[?] document chat on device (txt/md/csv/pdf, BM25)               [#89]
[?] chat export: Markdown, JSON, PDF                             [#76]
[?] OpenRouter (own key): image.generate, video.generate         [#84]
[?] MCP client, one remote server, off by default                [#88]
[?] Simple-mode model suggestion + hub prefill, speed test       [#80, #91]
[?] JNI UTF-8 both ways (emoji no longer abort CheckJNI)         [#92]

Open:
[ ] the vertical slice and every feature above on the Pixel 10 Pro (final pass)
[ ] llama.cpp CPU build targets plain armv8-a: no dotprod/fp16/i8mm, no repack.
      Likely the biggest speed win left. Options: GGML_CPU_ALL_VARIANTS with
      runtime-loaded backends, or one armv8.2+ build (drops armv8.0 phones).
[ ] OpenRouter as an optional remote chat model (same key)
[ ] external plugins as sandboxed scripts (DeepSeek Harness style); skills and
      MCP cover the declarative and remote cases today
[ ] document search with embeddings (EmbeddingGemma 2) next to BM25
[ ] hands-free voice mode / wake word
[ ] LFM3 on the Tensor NPU: needs a Tensor-compiled build (Google's Tensor SDK, beta)
[ ] .litertlm through the file picker (content:// is refused today)
[ ] MCP: more than one server; video.status for long video jobs
```

Not chased, even though a competitor has it: a Linux terminal, Accessibility or
Shizuku screen control, a browser agent, on-device image generation,
multi-character group chat, a visual workflow builder, a messaging gateway.

---

## Model test plan

**Run once the feature list above is complete.** Every model is tested on the
same phone with the same task list: the owner's Pixel 10 Pro (Tensor G5). For each model, record:

- task success, pass or fail, for each task in one fixed list of real tasks
  (tool calls, multi-step, memory, schedule);
- prefill and decode tokens/s from the app's own run metrics;
- peak RAM from `dumpsys meminfo`, following the procedure in
  [`memory-model.md`](memory-model.md);
- for multimodal models, whether the feature works: voice in/out, photo,
  screenshot.

That is the measurement the question at the bottom of this file asks for.

Sizes are the Hugging Face file sizes, read 2026-10-29. Q4_K_M unless noted;
`mmproj` is the separate vision/audio projector file. All load on llama.cpp
`b11988` (architectures `lfm2`, `lfm3`, `spark2_5`, `gemma4`, `qwen35`,
`granite`, `llama`, `minicpmv`, `gemma-embedding2`).

**From the owner's list:**

| Model | Params | Download | What it tests |
|---|---:|---:|---|
| [LFM3-1.7B](https://huggingface.co/LiquidAI/LFM3-1.7B-GGUF) | 1.73B | 1.10 GB (QAD Q4_0: 1.05 GB) | the main small text agent: tool calls, multi-step |
| [Spark-X2.6-4B](https://huggingface.co/XHToken/Spark-X2.6-4B-GGUF) | 4.11B | 3.54 GB | the upper bound: largest text model on the list |
| [LFM3-Omni-2B](https://huggingface.co/LiquidAI/LFM3-Omni-2B-GGUF) | 2.17B | 1.10 GB + 0.29 GB mmproj | voice in and out, vision, video, voice with tool calls |
| [LFM3-VL-2B](https://huggingface.co/LiquidAI/LFM3-VL-2B-GGUF) | 1.92B | 1.10 GB + 0.20 GB mmproj (Q8_0) | vision: photos, assistant screenshot |
| [LFM3-Audio-2B](https://huggingface.co/LiquidAI/LFM3-Audio-2B-GGUF) | 2.00B | 1.10 GB + 0.17 GB mmproj | speech in and out without vision |
| [Gemma 4 E2B-it](https://huggingface.co/google/gemma-4-E2B-it) | 5.12B total, 2B effective | 3.35 GB [GGUF QAT Q4_0](https://huggingface.co/google/gemma-4-E2B-it-qat-q4_0-gguf); 2.59 GB [`.litertlm`](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm); 3.11 GB `_Google_Tensor_G5.litertlm` | the only model on all three paths: llama.cpp CPU, LiteRT-LM CPU, and the Tensor NPU (Pixel 10) |

**Added: small and recent, picked from Hugging Face download counts on 2026-10-29:**

| Model | Params | Released | Download | What it tests |
|---|---:|---|---:|---|
| [LFM2.5-230M](https://huggingface.co/LiquidAI/LFM2.5-230M-GGUF) | 0.23B | 2026-06 | 0.15 GB | the floor: where the loop stops being reliable |
| [Spark-X2.6-1.7B](https://huggingface.co/XHToken/Spark-X2.6-1.7B-GGUF) | 1.71B | 2026-10 | 1.77 GB | same family as Spark 4B, one size down |
| [MiniCPM5-2B](https://huggingface.co/openbmb/MiniCPM5-2B-GGUF) | 2.52B | 2026-09 | 1.56 GB | most-downloaded new small text model (1.2M) |
| [Granite 4.2 3B](https://huggingface.co/ibm-granite/granite-4.2-3b-GGUF) | 3.66B | 2026-08 | 2.24 GB | a second text model around 3B |
| [Qwen3.5-2B](https://huggingface.co/unsloth/Qwen3.5-2B-GGUF) | 2.27B | 2026-02 | 1.28 GB (+0.67 GB mmproj) | the popular baseline (3.4M downloads); older than the rest |
| [MiniCPM-V-4.7-1B](https://huggingface.co/openbmb/MiniCPM-V-4.7-1B-gguf) | 1.55B | 2026-10 | 0.67 GB + 0.90 GB mmproj | the smallest vision model |
| [EmbeddingGemma 2](https://huggingface.co/ggml-org/embeddinggemma-2-GGUF) | 0.74B | 2026-09 | 0.31 GB (Q8_0) | embeddings for document chat; not a chat model |

Considered and left out: Liquid's d1 models (they answer in one forward pass
without generating tokens, so they are not chat models; perhaps for tool
selection later), FunctionGemma 270M (October 2025, not recent), and
Gemma 4 E4B (8B total, too large).

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
