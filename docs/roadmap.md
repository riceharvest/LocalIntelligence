# Roadmap

Ordered by what unblocks the next thing, not by what sounds most impressive.

Every box below is checked against the source tree. Where work was deleted, the
box says so rather than staying green.

---

## Status in one paragraph

**Update 2026-11-04 (later):** the v1 list and most of "After v1" were built in one day and merged as #274 to #320, compile only. None of it has run on a device since #275. `[?]` below means written and merged, not verified. The owner's plan: finish everything, including "Beyond parity" below, then one full pass on the emulator. See "Before public release (v1.0)", "After v1" and "Beyond parity". The paragraph that follows is the older status and still holds: no device observation exists.

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
[x] 32 built-in Android tools (see below)
[x] Model manager (import GGUF, RAM fit estimate, context size)
[x] Debug trace view
[-] Lexical tool selection tuned against a real eval. Replaced: the model now loads
    tools from a listing in `tools.find` and every enabled tool is callable [#255].
    Whether that helps is still unmeasured.
[ ] Remaining tools
```

### Tools that exist

32 built-in, each with a `V0ToolCatalogue` entry of the same name, risk tier,
origin and category (checked once at startup over everything shipped):

```
alarm.cancel  alarm.create  alarm.list  timer.set
apps.list  apps.open  apps.share
calendar.create  calendar.search
clipboard.read  clipboard.write
contacts.get  contacts.search
device.battery  device.info  device.open_settings  device.vibrate
documents.search
email.draft  message.draft
files.delete  files.list  files.read_text  files.search  files.write_text
history.search
location.get
notifications.dismiss  notifications.list  notifications.reply
web.fetch  web.search
```

Plus `image.generate` and `video.generate` (OpenRouter plugin, off by default,
need your key) and `mcp.*` tools from a remote MCP server (off by default; the
one prefix exempt from the catalogue, since the server defines them).

The v0 freeze criteria ask for 15+; that threshold is met by count. Whether
they work correctly on a device is unmeasured — no tool has been run against
real Android APIs through the agent loop.

---

## Wave 3 — comparison

```
[x] LiteRT-LM as backend #2, behind the same ModelBackend
[ ] Benchmark both against the same task list
```

The second item is **not done**: there is no task list runner. Since this
section was written the app can load a `.litertlm` file (download from the in-app
hub [#78], file picker [#96]) and has a Tensor G5 NPU path for Gemma 4 E2B [#69],
but no such file has been run on a device. There is still no GGUF-to-`.litertlm`
converter; see [`acceleration.md`](acceleration.md).

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
[?] 15+ Android operations         32 exist; none verified on a device
[?] runtime selects relevant tools implemented (tools.find listing, #255); no eval to show it helps
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

Compared against, as of 2026-10-29 (re-checked with sources 2026-11-04, see "Before public release"):

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
[?] MCP client, up to five remote servers, off by default       [#88, #247]
[?] Simple-mode model suggestion + hub prefill, speed test       [#80, #91]
[?] JNI UTF-8 both ways (emoji no longer abort CheckJNI)         [#92]
[?] one-tap first run in the chat empty state (Simple mode)     [#237]
[?] KV cache Automatic (f16 unless memory limits the window); context
      length scales with RAM and CPU speed                      [#238, #241]
[?] phone tools: flashlight, volume, media, dial, maps, add contact [#239]
[?] Continue with ChatGPT as the remote chat model (Plus/Pro plan) [#240]
[?] OpenAI-compatible chat providers: Groq, Cerebras, DeepSeek, Together,
      Mistral, Google Gemini, own server                        [#242]
[?] search providers (Brave, Tavily, Exa, SearXNG); free weather, Wikipedia,
      places tools                                              [#243]
[?] texts, call log, calls, Do Not Disturb, brightness, screen time [#244]
[?] Home Assistant: devices, control, lock and cover with confirmation [#245]
[?] cloud sandbox: Daytona [#246]; Vercel, SSH server, Termux [#251]
[?] MCP: per-server token header name (Composio, Zapier presets) [#247]
[?] cloud browser on the user's Browser Use key (browser.task/status) [#248]
[?] email over IMAP/SMTP with an app password (email.search/read/send) [#249]
[?] Telegram gateway: linked private chats only, unattended runs [#250]

Open:
[ ] the vertical slice and every feature above on the Pixel 10 Pro (final pass)
[?] llama.cpp CPU variants for every ARM level, picked at runtime
      (GGML_BACKEND_DL + GGML_CPU_ALL_VARIANTS), plus Vulkan and OpenCL      [#102]
[?] OpenRouter as an optional remote chat model (same key)
[?] external plugins as sandboxed scripts (DeepSeek Harness style); see
      architecture.md §18b "Script plugins"                                 [#97]
[?] document search with embeddings (EmbeddingGemma 2) next to BM25, hybrid ranking  [#100]
[?] Simple mode: one-tap EmbeddingGemma 2 download (0.31 GB) without the hub                 [#262]
[?] wake word: "Hey <assistant name>" with sherpa-onnx open-vocabulary keyword spotting,
      off by default. The keyword encoding must run in the app for any name; if it can't,
      use one fixed phrase. Android 14+ won't start a microphone service from the
      background, so after a reboot listening resumes when the app is next opened   [#263]
[?] `play` and `full` product flavors (full: own package id `.full`, side by side)    [#259]
[?] all-files access in the full build (`MANAGE_EXTERNAL_STORAGE`)                   [#261]
[?] full build: screen control through Accessibility (docs/adr/0002, 0003)            [#265]
[?] privacy policy draft in `docs/privacy.html`                                      [#260]
[ ] privacy policy hosted at a public URL (GitHub Pages is not enabled yet, checked
      2026-11-04) with the developer name and contact filled in
[?] .litertlm through the file picker, copied into app storage                       [#96]
[?] video.status for long OpenRouter video jobs                                      [#96]
```

Extras: one Play subscription (`extras_monthly`) that unlocks the cloud features in Simple
mode. BUILT, NOT DEPLOYED, and it ships after v1. Before it ships, rename the switch
"Use the cloud model for chat" to say remote model (GLOSSARY.md). The Extras row stays hidden until `EXTRAS_BACKEND_URL` is set
(`-PextrasBackendUrl=https://...`).

```
[?] Extras Worker (backend/extras-worker): checks the Play subscription, gives each
    customer one capped OpenRouter key and an optional Exa key                 [#252]
[?] Extras in the app: Play Billing, one credential source, Extras screen      [#253]

Owner's to-dos, before it goes live:
[ ] accounts and secrets: Cloudflare account id, D1 database id (a placeholder
    today), Worker secrets
[ ] OpenRouter's written OK under its resale clause, and EU data terms for
    OpenRouter and Exa
[ ] Play product `extras_monthly` and its price
[ ] privacy policy page (Play needs one; the Extras paragraph goes there)
[ ] Pub/Sub push: add the OIDC check (the secret path is the only check today)
[ ] default model id and monthly limits ($3.50 OpenRouter, $1.50 Exa) are placeholders
[ ] Exa: the create-key response documents no secret field. Confirm with Exa, or
    Exa stays off and search keeps the free provider
```

Dropped 2026-11-04: LFM3 on the Tensor NPU. Google's converter does not support LFM3's
architecture, so it would need a rewrite in the Torch Generative API. The NPU path stays
Gemma 4 E2B, which has a Tensor G5 build.

Not chased, even though a competitor has it: a Linux terminal on the phone, Shizuku, a browser agent on the phone, on-device image generation,
multi-character group chat, a visual workflow builder. Cloud sandboxes (shell commands in a
remote machine) and a cloud browser are built; see the build list.

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

## Before public release (v1.0)

Written 2026-11-04. Nothing in this section has been run on a device. Items marked
must block the release or would get it rejected; should clearly hurts it. Every
rule quoted from Google was read on the page named in brackets on that date.

### Where we stand against the competitors

Features below are from each project's own page. A competitor feature is listed only
where a page says it.

| App | Features that matter for v1 | Source |
|---|---|---|
| AnythingLLM Mobile | default assistant; asking about the screen; cited answers from web and files; recurring tasks; a "fit badge" per model; fork and retry a reply; automatic thread names; reasoning shown; export PDF, Markdown, JSON, text; pairs with its desktop app; GPLv3 | https://anythingllm.com/mobile |
| Google AI Edge Gallery | Agent Skills loaded from a URL; speech in and out; Ask Image; Audio Scribe (transcribe recordings); search-as-you-type over photos; Prompt Lab; benchmark; FunctionGemma "Mobile Actions" | https://github.com/google-ai-edge/gallery |
| Google AI Edge Gallery releases | 1.0.21 (28 Oct): speech in and out, longer chats with context management, live inference metrics; 1.0.20: EmbeddingGemma 2, media search; 1.0.19: session restore after interruption | https://github.com/google-ai-edge/gallery/releases |
| Google AI Edge Gallery, older notes (APKMirror mirror, not Google) | 1.0.13 Gemma 4 multi-token prediction for faster decoding; 1.0.15 experimental MCP, reminders, calendar | https://www.apkmirror.com/apk/research-at-google/google-ai-edge-gallery/google-ai-edge-gallery-1-0-15-release/ and https://www.apkmirror.com/apk/research-at-google/google-ai-edge-gallery/google-ai-edge-gallery-1-0-13-release/ |
| PocketPal AI | personas ("Pals") and a Pals hub; Whisper dictation; neural voices; calculator, date/time, opt-in web search; Hugging Face search incl. gated models; benchmark; CPU, GPU and Qualcomm Hexagon NPU paths; 16 languages; paid one-time "Plus" for hands-free voice | https://github.com/a-ghorbani/pocketpal-ai |
| Off Grid | on-device Stable Diffusion; vision; Whisper; tool calling (web search, calculator, device info, knowledge base); PDF/CSV knowledge base; LAN model servers; per-model eject and three memory policies; 3 parallel pausable downloads; Pro tier $119 once or $7.99 a month (voice, personas with memory, draft-then-approve calendar, email and MCP actions, phone-Mac sync) | https://github.com/alichherawalla/off-grid-mobile |
| Operit | screen control by Accessibility, Shizuku or root; Ubuntu terminal; visual workflows triggered by schedule, Tasker, intents or voice; per-tool Allow / Ask / Deny; MNN and llama.cpp local models; v1.12.0 on 2026-07-01 | https://github.com/AAswordman/Operit |
| MNN Chat | text, image and audio input; diffusion image generation; MNN engine claimed faster than llama.cpp on Android CPUs (their claim, not measured by us) | https://github.com/alibaba/MNN/blob/master/apps/Android/MnnLlmChat/README.md |
| ToolNeuron (MIT) | OpenAI-style HTTP server on the phone with bearer auth; RAG over PDF, DOCX, EPUB, CSV, Markdown; PIN lock; plugin store; on-device image generation; says it has no tool calling | https://github.com/Siddhesh2377/ToolNeuron |

What we have that none of these pages claim together: grammar-constrained tool calls
on any GGUF, 32 built-in tools plus Home Assistant, email, Telegram and MCP, per-tier
approval with unattended runs, and no telemetry. All of it is unrun on a device.

Gaps a user hits in the first five minutes, found by reading our chat source (no
regenerate, retry, edit or fork of a reply, and no reasoning display, in `ChatScreen`
or `ChatViewModel`; a search of the source, not a device check):

```
[?] retry / regenerate the last reply, and edit the last message   (AnythingLLM, PocketPal)  [#287]
[?] show the model's reasoning, folded                             (AnythingLLM, Off Grid)  [#287]
[?] per-model "will it fit" badge in the model list                (AnythingLLM)  [#287]
```

### Must

```
[ ] Device pass. The vertical slice, then the Model test plan, on the Pixel 10 Pro,
      using the RELEASE builds (minified), not debug. R8 breaking JNI or reflection is a
      classic crash that a debug run never shows. Both flavors.
[?] Release signing, built [#271, #272]; owner: back up ~/LI-keys. Old text: no signing config exists in app/build.gradle.kts today. Make an
      upload key for the Play build (Play App Signing) and a separate key for the
      full build (ADR 0002). Back both up; losing the full key strands every installed copy.
      Produce an AAB for Play. versionCode is 1 and versionName 0.1.0 today.
[?] scripts/release.sh runs it [#271]. Run .github/scripts/check-16k-alignment.sh on the release AAB/APK before each upload
      (Play needs 16 KB alignment for apps targeting Android 15+; the script exists
      (commit 1861abf) but no workflow runs it, and the roadmap rule is no CI workflows, so
      it is a manual step in the release script).
[x] Target API: already 36. New apps and updates must target API 36 from 2026-08-31
      [https://developer.android.com/google/play/requirements/target-sdk].
[?] Privacy policy at a public URL (enable GitHub Pages for docs/), developer name and
      contact filled in, the same name as the Play listing. Link it from Settings, About
      (no link exists in the app today; grep of app/src found none) and from the Play listing.
      Play needs it for the Data safety form even when nothing is collected
      [https://support.google.com/googleplay/android-developer/answer/10787469].
      Done 2026-11-04: live at https://riceharvest.github.io/LocalIntelligence/privacy.html
      (developer darioo, contact by GitHub issue) [#267]; Settings row "Privacy policy" [#268].
[ ] Play developer account and closed testing. For a personal account created after
      2023-11-13: at least 12 testers opted in continuously for 14 days, then "Apply for
      production" with a description of the test; review takes about 7 days. The
      owner's account is personal (confirmed 2026-11-04), so this applies
      [https://support.google.com/googleplay/android-developer/answer/14151465].
      This is the longest wait (about 3 weeks). Start the closed test as soon as one
      release build passes the device pass; it can run beside the Model test plan.
[?] Data safety form: draft in docs/play/data-safety.md, with items marked OPEN [#281, #296]; the owner submits. Required even for an app that collects nothing. On-device
      processing is not collection; data sent to a provider the user turned on is
      (declare it, with the user-initiated exemptions read from the same page). The
      form covers the Play build as shipped, so decide first whether Extras and the
      Billing library count while Extras is hidden.
[?] SMS and call log declaration: draft in docs/play/declarations.md; the Play build keeps these tools out of unattended runs and has a -PplayNoSms fallback [#281, #296]; the owner submits. READ_SMS, SEND_SMS, READ_CALL_LOG are restricted; the
      default Assistant handler is a permitted use, subject to Play review, and needs the
      Permissions Declaration Form
      [https://support.google.com/googleplay/android-developer/answer/10208820].
      The app asks for them only while it holds the assistant role (ADR 0002). Give the
      reviewers steps to set it as default assistant. Keep a fallback: a Play build
      without these permissions, if the form is refused.
[?] Foreground service declarations: draft and video scripts in docs/play/declarations.md [#281]; the owner films and submits. (Android 14+): one per type used (dataSync,
      microphone; check shortService), each with the functionality, the impact of being
      interrupted, and a video of the user starting it in the app. Automatic or
      background behavior does not count as the video
      [https://support.google.com/googleplay/android-developer/answer/13392821].
      The wake word service is the one to film: switch it on in Settings, then show the
      notification.
[?] Store listing text: draft in docs/play/listing.md [#281]; graphics are the owner's. Icon 512x512 PNG, feature graphic 1024x500, at least 2 screenshots
      (4 at 1080 px or more are needed for large-format recommendations), short description
      80 characters at most
      [https://support.google.com/googleplay/android-developer/answer/9866151].
      Also: content rating questionnaire, target audience (not directed at children, as
      the privacy page says), ads: none, contact email, category.
[?] Generative AI policy. Apps with a chatbot as a central feature must not generate
      offensive content [https://support.google.com/googleplay/android-developer/answer/14094294].
      The app downloads any Hugging Face GGUF, including uncensored ones. Owner's decision
      (2026-11-04): in the Play build, Simple mode offers only the recommended models; the
      open Hub search stays in Technical mode. The full build is unchanged. Built, enforced in
      HubViewModel [#269]; the local-file import is not restricted.
[?] In-app report for AI replies. "Apps that generate content using AI must contain
      in-app user reporting or flagging features", reaching the developer without leaving
      the app [https://support.google.com/googleplay/android-developer/answer/13985936].
      There is no backend before Extras, so a "Report this reply" action on each reply
      opens a prefilled email or GitHub issue (reply text only with the user's OK).
      Built differently [#268]: a mail or browser hand-off leaves the app, so a Flag action
      on each reply POSTs the report in-app to the owner's Google Form (no backend). Data
      safety must declare it: user-initiated, optional reports (reason, note, model name,
      app version, reply text when ticked), kept at most 12 months.
[ ] Full build release. Signed APK on GitHub Releases, a tag, a SHA-256 file and release
      notes. There are no GitHub Releases yet (checked 2026-11-04).
[ ] Android developer verification for the full build. Apps installed outside Play must
      be registered to a verified developer: user protection started 2026-09-30 in Brazil,
      Indonesia, Singapore and Thailand and goes global in 2027. A developer who
      distributes only outside Play registers in the Android Developer Console; one who
      also uses Play can register through Play Console. Register the `.full` package id
      with the full signing key. Limited-distribution accounts cover up to 20 devices
      without ID or fee
      [https://developer.android.com/developer-verification]. Fees and documents are not
      on that page; the owner has to read the Guides and FAQ.
[?] Screen control for the full build with the ADR 0003 guards                        [#265]
[?] [#278] A plain disclosure and consent screen before the Accessibility service is switched
      on (what it reads, that it acts only after approval, never unattended).
      Play's accessibility rule bans autonomous action, so this stays out of the Play build
      [https://support.google.com/googleplay/android-developer/answer/10964491].
```

### Should

```
[ ] First-run check on a clean install, both flavors: Simple mode, one tap, first
      answer, with no key. Time it from install; record it as the first number in
      docs/measurements.md.
[?] [#282] Crash and ANR handling without third-party analytics. On Play installs Android
      vitals already reports crashes to the owner with no SDK. For the full build, and
      for hangs: an uncaught-exception handler that writes a local file, and a Settings
      button "Share last crash report" through the Android share sheet, filled from
      ApplicationExitInfo (API 30+). Nothing is sent unless the user shares it. No such
      handler exists today (grep).
[?] [#283] Update path for the full build. Document Obtainium or manual download in the README for
      v1. An in-app "Check for updates" button, asking first, is v1.1.
[?] [#293, by reading the code; no TalkBack run] Accessibility of the app's own UI: TalkBack pass on Chat, Models, Settings and the
      approval card; 17 of 68 `contentDescription` in app/src are null, check each is
      decorative; touch targets of 48 dp; large font scale. Play's pre-launch report
      (runs on the closed-test upload) will also flag these.
[?] [#297, the rest in progress] Move user-visible strings out of Kotlin into resources. strings.xml has 4 entries
      and the source has 178 `Text("...")` calls. English-only is acceptable for v1; doing
      the move now is cheap and every later string makes it dearer.
[?] All major languages at release (owner, 2026-11-04) [#309, #315 to #320]: 21 with English: es, pt-BR, fr, de, it, nl, pl,
      ru, uk, tr, ar, hi, bn, id, vi, th, ja, ko, zh-CN, zh-TW. Includes settings labels, right-to-left
      layout, per-app language and the Play listing. Translations are AI-written, so testers review
      them. How well LFM3 answers in each language is unmeasured: add tasks to the Model test plan.
[?] Retry/regenerate, edit last message, folded reasoning, fit badge [#287].
[ ] Confirm which Android permissions need a Play declaration: SCHEDULE_EXACT_ALARM,
      PACKAGE_USAGE_STATS, WRITE_SETTINGS, ACCESS_NOTIFICATION_POLICY and notification
      access. Google's list
      [https://support.google.com/googleplay/android-developer/answer/9888170] does not
      name the last four, so check Policy status after the first upload.
[?] [#278; the steps are unverified on a device] Full build onboarding for sideloaders: Android may block enabling an Accessibility
      service for an app installed from outside a store until "Allow restricted
      settings" is turned on in App info. Not verified here; check on the Pixel and put
      the steps in the screen-control setup screen.
```

### What the owner has to do for v1.0

```
[x] Play Console developer account (personal, "darioo", 2026-11-04); still to do in the
      Console: identity, phone and Android device verification
[ ] recruit 12 closed testers and keep them opted in 14 days
[x] privacy page: developer name and contact (GitHub issues) [#267]
[x] decide the Play-build model policy (see Generative AI policy)
[ ] Android Developer Console account for the full build; read the fee and ID rules
[?] signing keys created in ~/LI-keys; the owner backs them up offline
[ ] screenshots, icon and feature graphic approval; content rating answers
[ ] run the device pass and film the two declaration videos (wake word, assistant role)
```

---

## After v1

Ordered by user value per engineering effort, within each phase. Engineering and
owner to-dos are kept apart. Effort is a guess, not a measurement.

### v1.1: the first weeks of feedback

Engineering:

```
[-] Dutch UI: replaced by all major languages, see "Should"
[?] [#283] "Check for updates" in the full build: a button, asks first, reads the GitHub
      Releases API; no background check
[?] [#282] Local crash report share
[?] [#288] Model eject and a Lean or Balanced memory policy
[ ] Fixes from the Play pre-launch report and the closed test
[?] [#285, token-gated; unverified with Tasker] Intent and Tasker triggers for scheduled tasks, from Operit's workflows. Small:
      a broadcast receiver that starts an unattended run
[?] [#286; runs as a foreground service while it works] Audio file transcription, from Edge Gallery's Audio Scribe, with LFM3-Audio from the
      Model test plan if it passes
```

Needs from the owner: which fixes first.

### v1.2: Extras go-live

The to-dos are already listed under "Extras" above; they stay the source of truth.
Added here:

```
[?] [#279] Pub/Sub OIDC check and the switch rename; the owner sets the two new variables
[ ] the default model id and monthly limits
[?] [#279, privacy page only] Data safety form update: Extras sends the Play purchase token and an install id to
      our server. Update privacy.html and the form together.
```

Needs from the owner: the accounts and secrets, OpenRouter's and Exa's written answers,
the Play product and price, and a payments profile. Price anchor: Off Grid Pro is $7.99
a month or $119 once (page above). Do not set the price before the cost per customer
is measured.

### v1.3: speed and depth

```
[ ] Built on w/specdecode, NOT merged: it reworks the decode loop every reply uses and needs a run first. Speculative or multi-token-prediction decoding where llama.cpp supports it. Edge
      Gallery 1.0.13 added Gemma 4 MTP for faster decoding (APKMirror notes). Measure
      decode tokens/s before and after with the in-app speed test; keep it only if it wins.
[?] [#290, full build, captions from a loaded vision model] Image search by text over the photo library (Edge Gallery "Instant Media Search"
      with EmbeddingGemma 2 is the same family; image embeddings are a different
      model, so this is medium effort)
[?] [#284] Personas ("Pals" in PocketPal, personas in Off Grid Pro): an About-you variant per chat
[?] [#280] More document types (DOCX, EPUB) next to PDF/txt/md/csv, from ToolNeuron
[?] [#292, #294] App lock with a PIN or biometrics (ToolNeuron)
```

Needs from the owner: a second phone class (Snapdragon) before any Hexagon work.

### v2: larger bets

```
[ ] Qualcomm Hexagon NPU path (PocketPal and Off Grid ship one; Off Grid calls it
      experimental, Q4_0 and Q8_0 only). Needs a Snapdragon test phone.
[?] [#289, threat-model T12] A local OpenAI-style server so other apps on the phone use the loaded model (ToolNeuron)
[-] Pairing and sync: replaced by "Your own private cloud" in "Beyond parity"
[ ] Screen control hardening in the full build, from the first real-world reports
```

Still not chased, unchanged from above: Linux terminal, Shizuku, on-device image
generation, multi-character group chat, visual workflow builder.

---

## Beyond parity

Written 2026-11-04. The owner: "we need to do better than the competitors, not just copy". So parity is a short list
and the bets come first. Each bet says why others cannot quickly match it. Nothing here is measured yet.

### Bets

```
[?] B1. The agent that finishes. [#302, #304, #308] Small models lose the thread over 5 to 10 steps. A set of runtime aids, built now and measured
      together in the final test pass; any that does not help is removed then:
      - todo list owned by the runtime, updated by one tool, re-injected each step (Hermes todo_tool)
      - text repetition guard: abort a looping reply early (Hermes repetition_guard)
      - deterministic prune: dedupe identical tool results, shrink old ones (Hermes context_compressor_prune)
      - argument coercion in one place before rejecting (today per tool)
      - answer check: a number or name in the answer must exist in a tool result, or the model is told once
      - a resumable run: a killed run continues from its last step
      - fresh-context subtask that returns only a summary (owner allowed it 2026-11-04; architecture section 2 updated in #304)
      Moat: built on our grammar, tiers and observation store; chat-first apps have none of those.
[?] B2. Your whole phone, answerable. [#301, #311; opening the original from an answer row is not built] A private on-device index of notifications, messages, calendar, contacts, files
      and photo captions, with answers that cite the source and open it. Incremental, on charger, deletable per source.
      The Play build indexes only what its permissions allow.  Moat: cloud assistants would need this data on their servers.
[?] B3. Your own private cloud. [#306; never run against live herdr or tmux; "hard questions to your desktop" is only a shortcut to Settings today] Over Tailscale only, checked by Tailscale identity:
      - attach to the agents already running on your desktop: same live thread, native chat view, approvals as one keystroke
      - a thin bridge with four operations (list, read, send, wait), herdr first, tmux as a second backend, so herdr is not a lock-in
      - done and needs-you notifications through a self-hosted push (ntfy), no Firebase, no polling
      - hard questions routed to your own desktop model, with your permission
      - later, an owner decision: desktop agents using the phone's tools
      Offline: read the cached thread and queue messages. Full build first; Play rules unchecked.
      Moat: nobody we checked connects both ways with live threads.
[?] B4. Published proof. [#303; the suite and picker are written, no score has been measured] A public score per model and phone from the eval suite, and the app picks your model from
      measured results.  Moat: nobody publishes this. It holds us to the same standard. Needs real devices for the phone half.
[?] B5. It learns you, locally. [#305, #311; the owner allowed the memory block, visible and editable; Undo does not survive a restart] A task you repeat becomes a saved skill, with your approval. Skills can scope the tools the
      model sees while they run (2 instead of 6). An always-visible memory block the agent edits, with a size limit: section 14 bans
      model-based extraction, allowed on that condition.  Moat: needs loop, skills and memory in one app, on the phone.
```

### Parity, quick

Checked against the code on 2026-11-04. Already present, so not listed: scheduled tasks, chat export (Markdown, JSON, PDF), sharing into the app, document search, reasoning control.

```
[?] find LM Studio, Ollama and llama.cpp on the LAN, and offer them in Simple mode (custom endpoint exists in Technical)  AnythingLLM, Off Grid #211
[?] keep the screen awake while generating, optional                                     Off Grid #378
[?] live speed and memory per reply, folded, Technical mode                              Edge Gallery, MNN
[x] audio transcription as a real foreground run: TranscriptionService (dataSync), started and stopped around one run in ChatViewModel.transcribe   follow-up to #286
[?] photo results open the photo from chat                                               follow-up to #290
[ ] hands-free loop: talk-to-interrupt not built; wake word and voice exist    Operit, MNN, PocketPal #945
[x] pick a vision model's mmproj by hand; check the import path                          PocketPal #841
```

### Runtime and platform

From each project's own release notes, read 2026-11-04. All speedups are unmeasured on our hardware.

```
[?] llama.cpp v0.9.0 (b12110) over our b11988 [#313]: Gemma 4 prefill graph work, Gemma 4 speculative decoding, LFM3 tensor split,
      Nemotron 3.5 Transcribe (offline speech, 40 languages). JNI rebase; mtmd.h changed. w/specdecode stays held: LFM3 hybrid gets nothing from it.
[ ] LiteRT-LM 0.19.0 over our 0.17.1: GPU logit masking for constrained decoding, multi-token decoding. Breaking C API 1.0.
      Check the Tensor NPU runtime pair before bumping.
[?] Android 17, before target API 37 [#299]: declare FEATURE_NEURAL_PROCESSING_UNIT or the NPU path is blocked; handle the new per-app
      memory limit (exit reason MemoryLimiter:AnonSwap) with a clear message; re-check background audio for spoken replies.
[?] Gemini Nano, Technical mode only, cannot be tested on an emulator [#310], through ML Kit's Prompt API as a no-download option on supported phones. Beta, and the model is out of our control.
[ ] AppFunctions (parked, owner decision on approving outside callers): expose our tools to system agents. Experimental, and the caller side is restricted, so read the rules first.
[?] Read the 2026 Play AI policy pages [#300, docs/play/ai-policy-2026.md]. The research found only third-party coverage.
```

### Known gaps from the build

Not done or not checked, carried to the final pass or later:

```
[ ] Still English: Telegram fixed replies, scheduled-result prefixes, some Hub and run-receiver messages; DeviceSelfCheck on purpose
[ ] Translations are machine-written: no native reader yet, no check of clipping, no right-to-left review
[ ] Repetition guard: measure false positives at the 400-character threshold; the stop shows the generic error
[ ] Answer check: measure false positives per language. Prune: measure its effect on prefix reuse
[ ] Eval suite: desktop harness contract not implemented; no failure or permission-denied tasks
[ ] Upstream b12110 jinja and reasoning changes unverified with LFM3
[ ] Desktop live view re-reads every 1.5 s; could use herdr events
```

### Watching, not planned

KV800 (the owner's research, about 800 bytes per token against about 12.5 KB here today) is a base model only and may never ship post-RL (owner, 2026-11-04).
Nothing depends on it. Revisit only if a chat model exists, and even then a phone CPU run decides.

### Order, and the first real test

B1 and the parity list go first, then the runtime bumps, then B2, B3, B5 and B4. The owner's rule: no testing until everything is built.
When everything is built: one full pass on the emulator with scripts/devicectl.sh. What an emulator cannot show stays on the real-phone list: NPU, heat, microphone,
biometrics, right-to-left review by a reader, and real speed.

---

## Explicitly deferred

**v0.2+** — only after the baseline works:

```
Shizuku
```

Voice, vision, MCP, widgets, the share-sheet entry and LAN inference used to be
listed here. They are now part of the feature-parity plan below, or already
shipped (the share-sheet entry, model downloading, scheduled tasks).

**Considerably later, and only with a reason:**

```
Linux shell         coding environment    subagents
multi-agent         browser automation on the phone
```

**Not part of this project, and not planned:**

- **ADK (Agent Development Kit).** Deliberately excluded. The loop here is a
  `while` loop with constructor-injected dependencies. There is no planner node,
  no critic, no reflection pass, no subagent dispatch, and none is planned.
- **NPU acceleration beyond Gemma 4 E2B on Tensor.** The Tensor G5 path exists
  [#69] (LiteRT-LM 0.17.1) but is unrun on a device, and LFM3 on the NPU was dropped
  2026-11-04. The older text here (LiteRT-LM 0.13.1, "unreachable") is superseded.
  See [`acceleration.md`](acceleration.md).

---

## The question the eval suite exists to answer

Not "which model scores highest?"

> **How small can the model get before the agent loop stops being reliable?**

That is the number that decides whether this architecture is right. A harness
that needs a 7B model to work is a harness with too much context, too many
tools, or too loose a protocol.

**The answer is currently unknown and this repository contains no evidence
about it.** Finishing the measurement work is the next real milestone.
