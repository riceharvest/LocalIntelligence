# LiteRT-LM as a second ModelBackend

**Status: implemented and compiled. Not run on a device, and not tested — this
repository has no test suite.**

`dev.localintelligence.inference.litertlm` in `:android` is a second
`ModelBackend` on Google's LiteRT-LM runtime. It exists so "which runtime is
faster on a phone" becomes a measurement in the eval suite rather than an
argument. Nothing outside the new package changed: `ModelBackend`, `ModelSpec`,
`GenerationRequest`, `GenerationResult` and `ModelCapabilities` in `:core` are
untouched, and `tools/` is untouched.

## The dependency

```
com.google.ai.edge.litertlm:litertlm-android:0.13.1
```

**Google Maven, not Maven Central.** The `google()` repository in
`settings.gradle.kts` already covers the group; no repository was added.
Searching Maven Central for this artifact returns NOT FOUND. That is a stale
Central index, **not** a missing artifact — verified by an actual Gradle resolve,
and by:

```bash
curl -s -o /dev/null -w "%{http_code}" \
  https://dl.google.com/dl/android/maven2/com/google/ai/edge/litertlm/litertlm-android/0.13.1/litertlm-android-0.13.1.pom
# 200
```

Do not "fix" a NOT FOUND on Central by changing the version. That trap is
recorded in `gradle/libs.versions.toml` next to the coordinate.

Resolved tree — 3 direct transitives, and the coroutines version conflict
resolves upward to the one the repo already uses:

```
com.google.ai.edge.litertlm:litertlm-android:0.13.1
  com.google.code.gson:gson:2.13.2
  org.jetbrains.kotlin:kotlin-reflect:2.2.21
  org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0 -> 1.10.2
```

All three `.so` files (`liblitertlm_jni.so`, `libLiteRt.so`,
`libLiteRtClGlAccelerator.so`) package for `arm64-v8a` and `x86_64`, confirmed in
`app-debug.apk`.

## The constraint that shaped the design

**LiteRT-LM 0.13.1 is Java 21 bytecode (class file major version 65). A JDK 17
JVM cannot load it:**

```
java.lang.UnsupportedClassVersionError:
  com/google/ai/edge/litertlm/Message$Companion has been compiled by a more
  recent version of the Java Runtime (class file version 65.0), this version of
  the Java Runtime only recognizes class file versions up to 61.0
```

Compiling against it is fine — Kotlin reads the higher class version without
complaint. *Loading* it on JDK 17 is not.

**This project actually builds on JDK 21**, and that is deliberate rather than
accidental: every module declares `kotlin { jvmToolchain(21) }`, and
`litertlm-android:0.13.1` requires class file 65. See docs/build.md.

So every LiteRT-LM type is reached through the `LiteRtLmEngine` /
`LiteRtLmConversation` seam, which keeps the LiteRT-LM import confined to a
single file. `NativeLiteRtLmEngine.kt` is the only file in the package that
imports `com.google.ai.edge.litertlm`, which also means it is the only file
that breaks when the LiteRT-LM API changes shape.

**There is no `FakeLiteRtLmEngine` test suite.** The tests that once exercised
this seam were deleted with the rest of the test sources, so the backend's
mapping logic — `GenerationRequest` onto a streaming runtime, stop decisions,
failures onto `StopReason` — is **unverified**. No claims are made about it
beyond "it compiles".

## Three decisions worth knowing about

### 1. One conversation kept across requests, rebuilt only when the transcript is rewritten

**Before:** a new LiteRT-LM `Conversation` per request, seeded with the whole
transcript, so every turn prefilled the whole prompt again. The reason was that
the core could rewrite the transcript between turns, and a kept conversation
would then hold a transcript the core no longer believed in.

**Why that reason is gone.** Since PRs #114 and #117, the agent's prompt only grows
at its end within an epoch:
- the system message is stable: name, rules, profile, then the session's tools;
- each user turn is rendered once and replayed with the same text
  (`ChatMessage.User.rendered`);
- the prompt is rewritten only at a compaction (the working-state summary
  changes and older messages are folded away) or when the tool list changes at
  the start of a message.

A rewrite is therefore rare, and it is detectable: the request is no longer the
previous request's turns plus new ones at the end.

**Now.** `LiteRtLmBackend` keeps the conversation after a request that completed
cleanly, together with the system instruction, the sampler and the exact turns
(`LiteRtLmTurn`: role, text, tool name) it was given, plus the reply the model
produced. For the next request (`planReuse`):

- **Same turns plus one new user or tool turn at the end:** only that turn is sent
  to the kept conversation, and LiteRT-LM prefills just it on top of the KV
  cache it holds.
- **The model's reply.** The reply is already in the conversation. How the next
  request carries it back depends on the kind of turn:
  - *After an answer*, the agent stores `Assistant(X)`, where X is the
    `<respond>` body. That MODEL turn is skipped as already held when it equals
    the raw reply (ignoring surrounding whitespace) or the `Respond` text that
    `ActionParserImpl` gets from the raw reply. Any other MODEL turn, such as a
    runtime correction or a changed answer, is a mismatch and starts over.
  - *After a tool call*, the agent stores no assistant turn. The next request is
    the previous one plus the observation, which is appended. The model's own
    `<tool …>` line stays in the conversation ahead of the result.

    **This is a judgment call.** A rebuilt conversation does not show the
    call, so on the reuse path the model sees slightly more than the core's
    transcript: which call produced the result, a few dozen tokens per step
    that the core does not count. The token check below covers that gap.
- **Start over** with a new conversation from the full transcript, as before,
  when:
  - there is no kept conversation (first request, model loaded or unloaded);
  - the last send did not complete cleanly (cancelled, cut at the output budget,
    failed). The runtime does not roll a cancel back (b/450903294), and a
    cancelled conversation treats every later send as cancelled;
  - `reusePrefix` is false (the speed test measures a full prefill);
  - the request carries media;
  - the system instruction changed (a compaction changes the summary, which is
    hoisted into it; a new tool list changes the system prompt);
  - the sampling settings changed (fixed when a conversation is created);
  - the transcript is not the kept turns plus new ones;
  - the stored reply is not the one the model produced;
  - more than one new user or tool turn was added;
  - the kept conversation's own token count, plus the new turn and the output
    budget, would exceed the context length.
- **A reused send that fails before producing any output** is retried once on a
  new conversation within the same request. Two examples: a full context, or
  LiteRT-LM's own "the new rendered template string does not start with the
  previous" check. Nothing reached the caller's token sink, so the retry is
  invisible, and reuse cannot turn a request that works today into an error.

**One turn per send is a limit of the 0.17.1 API, not a choice.** These
facts were checked against the 0.17.1 Kotlin API, which `gradle/libs.versions.toml`
pins (the dependency section above still describes 0.13.1), and against
`runtime/conversation/conversation.cc` at tag v0.17.1:
- `Conversation.sendMessageAsync` takes one `Message`. It always prefills it
  and then decodes.
- The C++ runtime can append a message without decoding (`has_pending_message`),
  but the Kotlin and JNI surface does not expose it.

**Media.** Neither path attaches media to a LiteRT-LM turn today
(`supportsVision` is false). A request carrying media starts over, so media never
goes through the append path.

**Grammar.** LiteRT-LM requests still carry no grammar. A request with one is
refused as before (see section 3), and a refused request leaves the kept
conversation untouched.

**Logging.** Each request logs one line under the tag `LocalLiteRt`: either
`reused conversation, sent 1 new message` or `new conversation (<reason>)`.
The reason is one of the start-over cases above.

**Not verified on a device**, like the rest of this backend. The prefill saving,
the runtime's real behaviour after a completed send, and `getTokenCount()` are
all read from the source, not measured.

The transcript must end on a user or tool turn. A transcript that ends on the
model's own turn is refused before the engine is called, because it would ask the
model to continue its own answer.

### 2. The model must be a real file

llama.cpp takes `/proc/self/fd/N` for a SAF document. LiteRT-LM takes a
filesystem path and has no descriptor entry point, so a SAF-picked model will not
load here — `LiteRtLmModelSource` refuses `content://` URIs and says why, rather
than reporting them as missing files. LiteRT-LM models are expected to be
downloaded into the app's own files dir, where they already have a real path.

### 3. `supportsGrammar` is `false`, and that is deliberate

LiteRT-LM's constrained decoding is a process-global `ExperimentalFlags` switch,
read when a conversation is created, aimed at forcing valid tool-call JSON. There
is no per-request GBNF entry point. Reporting `true` would make the runtime build
a grammar and believe it was enforced when it was not — the exact lie the frozen
interface warns about. `generate()` refuses a request carrying a grammar rather
than silently ignoring it. The agent loop never sends one to this backend: it passes
a grammar only to a backend whose capabilities say `supportsGrammar = true`.

Sampling fields that LiteRT-LM has no equivalent for (`minP`, `repeatPenalty`)
are dropped and documented rather than folded into a neighbouring parameter.
`maxOutputTokens` is **not** dropped: `SamplerConfig` has no max-tokens field, so
the backend enforces the budget itself and cancels when it is exceeded.

## What is verified, and what is not

**MEASURED, re-run on this branch:**

- `:app:assembleDebug` succeeds from a clean checkout with llama.cpp pinned at
  tag b4661 (the pin at the time).
- The three LiteRT-LM native libraries package into the APK for both ABIs:

  | `.so` | arm64-v8a | x86_64 |
  |---|---:|---:|
  | `liblitertlm_jni.so` | 14,882,976 | 18,047,160 |
  | `libLiteRt.so` | 5,064,136 | 6,997,656 |
  | `libLiteRtClGlAccelerator.so` | 2,778,128 | 3,466,440 |

- `grep -rn "import android\." core/src/` is empty. `:core` is still pure JVM.
- There is **no `:android:test` task with results to report.** The figure of 966
  tests, 0 failures quoted here previously described a suite that no longer
  exists; it was deleted at the owner's explicit instruction. Any document
  quoting a test count for this repository is quoting a deleted suite.

**Not verified — no device has been used.** The real engine has never been
constructed: there is no `.litertlm` model on any machine here and no emulator in
the loop. So these are unproven:

- that `NativeLiteRtLmEngine` actually loads a model and decodes,
- LiteRT-LM's real streaming semantics (delta or cumulative) — the backend handles
  both via `LiteRtLmDeltaTracker`, but which one 0.13.1 does is a guess,
- real cancellation latency, and whether `cancelProcess()` interrupts a decode
  mid-stream,
- whether `ExperimentalFlags.enableBenchmark` actually populates
  `BenchmarkInfo` in 0.13.1, and therefore whether prefill/decode timings are
  usable in a tok/s comparison,
- measured tok/s against llama.cpp. **This is the entire point of the backend and
  it is still unmeasured.**

There is also no model to measure. LiteRT-LM 0.13.1 reads `.litertlm`, there is
no GGUF-to-`.litertlm` converter in this app or in Google's, and no `.litertlm`
file has been placed on a device.

## The acquisition gap: what a user can actually do with a `.litertlm`

The backend being reachable and a model being obtainable are two different
problems, and only the first is solved. As shipped:

- **The Hub cannot fetch one.** `HuggingFaceClient.toGgufFiles` keeps only
  `.gguf`. A `litert-community/*-litert-lm` repository is public and ungated
  and hosts a real model — `litert-community/gemma-4-E4B-it-litert-lm` publishes
  three `.litertlm` bundles (2.77, 2.77 and 3.41 GiB) and a `.task` part — and
  the Hub now **names and sizes them without pricing them**. A LiteRT-LM
  FlatBuffer has no GGUF header, so `probeHeader` returns null and the FitGate
  estimate would fall back to a name-derived guess. Printing that guess on a
  screen whose whole purpose is a number the user can believe would be worse
  than printing nothing, so no RAM figure is shown for them at all.
- **The file picker cannot deliver one.** The picker returns a `content://`
  document, and `LiteRtLmModelSource.toFile` refuses every `content://` uri —
  LiteRT-LM opens its model by filesystem path and has no descriptor entry
  point. `ModelManagerScreen` sniffs the container and refuses a `.litertlm` with
  that reason named, rather than handing it to `ModelImporter`, which would
  throw `NOT_A_GGUF_FILE` and surface as "it may be a format this app cannot
  read, or it may be damaged".
- **Scan storage does not adopt one.** `MainActivity` filters the scan on
  `endsWith(".gguf")`. This is the route that *would* work — a real file at a
  real path is exactly what `LiteRtLmModelSource.resolve` accepts — and it is
  closed. The one-line fix is in the PR description for this branch; the file
  belongs to another agent.

So the honest statement of reachability is: **the LiteRT-LM backend is wired,
reachable and routed to, and there is no in-app path that puts a model in front
of it.** A user who wants to exercise it today needs `adb push` plus that one
source change.

### What the failure messages say, and why

A LiteRT-LM failure must name which of the known constraints applies, never
invent a capability. The three that exist in this build:

1. **No NPU library.** `litertlm-android:0.13.1` ships exactly three `.so`
   files — `liblitertlm_jni.so`, `libLiteRt.so`,
   `libLiteRtClGlAccelerator.so` — and none is a vendor delegate.
   `LiteRtLmCapabilityProbe.npuStatus` reports the NPU unavailable and names
   the directory it searched, on every phone, including Snapdragons.
2. **No `GOOGLE_TENSOR`.** The backend type is not in the published artifact;
   `javap` on `classes.jar` lists only `Backend$CPU`, `Backend$GPU` and
   `Backend$NPU`. It is not selectable, and wiring it would produce a build
   that compiles but fails on a Pixel.
3. **GPU is OpenCL, not Vulkan.** The GPU path `dlopen`s `libOpenCL.so`,
   `libOpenCL-car.so` or `libOpenCL-pixel.so` plus `libvndksupport.so`. Do not
   describe it as a Vulkan path.

And the fourth, which is not a capability but a fact about this project's
history: **no `.litertlm` has ever been initialised here.** There is no device,
no model file, and no measurement. The user-facing refusals say so, because a
user deciding whether to spend 3.4 GiB of their data plan deserves to know that
nobody, including this app's author, has measured the thing they are about to
run.

The LiteRT-LM Kotlin API was reverse-engineered from the shipped
`classes.jar` (`javap`) plus the v0.13.1 sources on GitHub, not from a running
device. Signatures are real; runtime behaviour is not yet observed.
