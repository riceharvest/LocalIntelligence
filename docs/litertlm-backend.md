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

### 1. A conversation per request, not one long-lived context

`GenerationRequest` carries the whole transcript, and `AgentController` compacts
it between turns. A persistent server-side conversation would mean the engine
believed in a transcript the core may already have rewritten. Rebuilding from
the supplied transcript is the only way the backend cannot be wrong about what
the model has seen. The cost is that the KV cache is not reused across turns.

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
than silently ignoring it.

Sampling fields that LiteRT-LM has no equivalent for (`minP`, `repeatPenalty`)
are dropped and documented rather than folded into a neighbouring parameter.
`maxOutputTokens` is **not** dropped: `SamplerConfig` has no max-tokens field, so
the backend enforces the budget itself and cancels when it is exceeded.

## What is verified, and what is not

**MEASURED, re-run on this branch:**

- `:app:assembleDebug` succeeds from a clean checkout with llama.cpp pinned at
  tag b4661.
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
   that compiles on CI and fails on a Pixel.
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
