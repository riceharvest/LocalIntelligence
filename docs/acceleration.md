## The thing that decides whether you can use any of this: the model format

**Read this before reading anything about NPU.** Acceleration is the second
problem. The first is that a LiteRT-LM model is not a GGUF, and this app cannot
convert one into the other.

A `.litertlm` file is a **FlatBuffer container** holding TFLite graphs, a
tokenizer, and metadata. Google builds it with a two-stage desktop toolchain:

```
HuggingFace / PyTorch checkpoint
  └─ litert-torch export_hf --model=<repo> --output_dir=…     # PyTorch → .tflite
       └─ litert-lm-builder … output --path model.litertlm     # package → container
```

Both stages are Python, both run on a **desktop**, and both start from the
original checkpoint. **There is no GGUF → `.litertlm` path**, in this app or in
Google's. A GGUF is a *derived* artifact — the output of quantising those same
PyTorch weights for llama.cpp — so the information the conversion needs has
already been discarded by the time a GGUF exists. The honest answer to "can this
app convert my GGUF?" is no, and it is worth saying so plainly rather than
implying a conversion is merely unwired.

**So what is actually reachable today:**

| Where the model came from | Format | Which backend runs it |
|---|---|---|
| This app's hub download | GGUF | `llamacpp` |
| Model-manager import (SAF) | GGUF | `llamacpp` |
| A pre-converted HF repo, e.g. `litert-community/gemma-4-E4B-it-litert-lm` | `.litertlm` | **nothing — see below** |

That is the whole table. Every model this app can currently *acquire* is a GGUF,
and a GGUF cannot be run by the LiteRT-LM backend at all. The LiteRT-LM backend
is therefore correct, complete and compiled into the APK, and has **no model to
run** until either the hub can fetch a `litert-community/*-litert-lm` repo, or a
user sideloads a `.litertlm` into `filesDir/models`.

This is the real gap, and it is larger than the NPU gap. Closing the NPU gap
would buy acceleration on a runtime that still cannot open a file the app can
obtain.

## The acquisition gap, item by item

This is the part that was previously one sentence, and it is the part a user
actually hits. Stated as a list of what a person with a `.litertlm` on their
laptop can and cannot do with this app **today**:

| Route | Result | Why |
|---|---|---|
| Type the repo into the Hub | Repository resolves, `.litertlm` files are **named and sized, not offered** | `HuggingFaceClient.toGgufFiles` keeps only `.gguf`. The Hub shows them in a separate block with no RAM figure, because there is no GGUF header to read and the FitGate arithmetic does not apply to a FlatBuffer. |
| Tap Download on a `.litertlm` | Not possible | It is not a row in the quant list. The download button and the RAM gate are both GGUF-only. |
| Pick it in the model manager's file picker | **Refused, with the reason** | The picker returns a `content://` document. `LiteRtLmModelSource.toFile` refuses every `content://` uri, because LiteRT-LM opens its model by filesystem path and has no descriptor entry point. The screen sniffs the container and says this rather than letting `ModelImporter` throw `NOT_A_GGUF_FILE` and report "it may be a format this app cannot read". |
| `adb push` it to `filesDir/models`, then Scan storage | **Not adopted** | `MainActivity`'s scan filters on `it.name.endsWith(".gguf")`. This is the one route that would actually work, and it is closed. Fix is in the PR description; the file belongs to another agent. |
| Convert a GGUF this app downloaded | Impossible | No converter exists, in this app or Google's. A `.litertlm` is built from the original PyTorch/HuggingFace checkpoint by a desktop toolchain (`litert-torch export_hf` → `litert-lm-builder`); a GGUF is downstream of quantising those same weights, so the information the conversion needs is already gone. |

**What the Hub shows for a `.litertlm`, and why it is not a row.** The Hub's
value is that its numbers can be believed — it reads the real GGUF header over
a bounded range request before quoting a RAM figure. A LiteRT-LM FlatBuffer has
no GGUF header, so `probeHeader` returns null and the estimate would fall back
to a name-derived guess. Printing that guess as this screen's primary metric
would be worse than printing nothing. So the file is named with its real
`lfs.size` (a fact about bytes on a server, not a memory estimate) and the
reason it cannot be fetched is spelled out.

## Reaching the backend at all

`AppContainer` constructed exactly one backend:

```kotlin
val modelBackend: ModelBackend by lazy { LlamaCppBackend(importer) }
```

so `LiteRtLmBackend` was unreachable — no user, no setting, no code path. It is
now selected by **`ModelBackendRouter`**, which routes on the model's first four
bytes rather than on a user toggle, because a GGUF and a `.litertlm` are not two
ways to run one model: they are two different formats, and the file already
contains the answer. A toggle would let a user pick an impossible pairing and
discover it as a native crash in `liblitertlm_jni.so`.

Routing is one line in `AppContainer`, which is outside this module:

```kotlin
val modelBackend: ModelBackend by lazy {
    ModelBackendRouter.forContext(context, importer).route(modelFile)
}
```

A second, quieter reachability bug: `LiteRtLmBackend`'s `capabilityProbe`
parameter defaults to `CpuOnlyAcceleratorProbe` and `nativeLibraryDir` defaults
to null. Both are right for a JVM harness and wrong for a phone, so any caller
that forgot them got a backend reporting every accelerator unavailable — an
honest-sounding "no device probe was supplied" on a device that has a perfectly
good GPU. The router constructs the backend in one place with both supplied.

## Hardware acceleration on Android

**Status: selection, probing and fallback are implemented and compile. No
accelerator has ever been run. There are no tests — the suite was deleted at the
owner's explicit instruction. There is no measurement in this document, and
there should not be one yet.**

Read the two sections above before quoting any of this. The short version: the
selection logic is implemented, the hardware is not, and the model format is a
bigger obstacle than either.

## The honest headline

`litertlm-android:0.13.1` ships exactly three native libraries, and **none of
them is an NPU delegate**. Verified by listing the AAR payload for `arm64-v8a`:

```
liblitertlm_jni.so
libLiteRt.so
libLiteRtClGlAccelerator.so
```

So on a stock build of this app, on **every** phone including a Snapdragon, the
NPU is unavailable. Not "usually unavailable" — unavailable, because there is no
library to load. A capability probe built from `Build.SOC_MODEL` would say
otherwise and would be wrong.

That is the single most important thing this document exists to say, and it is
why the implementation is a probe rather than a chip-name table.

## What the three backends actually are

From `javap` on the shipped 0.13.1 AAR, and from the strings and dynamic
sections of `liblitertlm_jni.so`:

| Tier | LiteRT-LM type | What it needs | Reachable today |
|---|---|---|---|
| CPU | `Backend.CPU(threadCount)` | nothing beyond `liblitertlm_jni.so` | **Yes, everywhere** |
| GPU | `Backend.GPU()` — no parameters at all | a platform OpenCL driver + `libvndksupport.so` | **Yes, on most real arm64 phones** |
| NPU | `Backend.NPU(nativeLibraryDir)` | a vendor dispatch delegate in that directory | **No, not on a stock build** |

Two details that are easy to get wrong and expensive to debug:

- **`Backend.GPU()` takes nothing.** No thread count, no flags, no properties at
  all. Anything passed there is silently discarded, so there is no knob to turn
  even if you wanted one.
- **`Backend.NPU("")` is a valid construction and a silent downgrade.** The
  runtime logs `Dispatch library directory is not set` and falls back internally
  with no exception. Passing the real directory is the only difference between an
  NPU run and a slow one.

`Backend.GOOGLE_TENSOR` exists in the LiteRT-LM sources but is **not in the
published 0.13.1 artifact** — `javap` lists only `Backend$CPU`, `Backend$GPU` and
`Backend$NPU`. It is therefore not selectable here. Wiring it would mean a build
that compiles on CI and fails on a Pixel.

## How the GPU path actually reaches the GPU

Not Vulkan, and not through a system-visible "GPU" service. LiteRT's GPU
accelerator on Android is **OpenCL**, and it `dlopen`s one of:

```
libOpenCL.so            generic ICD
libOpenCL-car.so         Adreno / Qualcomm
libOpenCL-pixel.so       Google Tensor
```

plus `libvndksupport.so` for the OpenGL↔CL shared context, plus
`libLiteRtClGlAccelerator.so` (shipped in the AAR, so always present).

Consequences that drove the manifest change:

1. **A Pixel with a Tensor GPU presents as `libOpenCL-pixel.so`, not
   `libOpenCL.so`.** A probe checking only the generic name would report a Pixel
   as GPU-less, which is backwards. The probe checks all three.
2. **Android's linker namespaces (API 24+) will not hand a vendor driver to an
   app that has not declared it.** Without the `uses-native-library` entries,
   `libOpenCL-car.so` is not merely missing — it is *invisible*, and LiteRT
   falls back internally and silently, so the app runs on CPU while the user
   believes it is using the GPU.
3. **Emulators and x86 images have no vendor OpenCL at all.** So does the
   `x86_64` build. An `AUTO` preference there correctly resolves to CPU, with a
   reason.

## The capability probe

`LiteRtLmCapabilityProbe` answers "can this phone do this" by looking, not by
guessing.

- **CPU** — reported available unconditionally. `liblitertlm_jni.so` ships for
  every ABI the app builds, so its absence is a broken APK, not a capability gap.
- **GPU** — attempts `System.loadLibrary` on the three OpenCL sonames and on
  `libvndksupport.so`. A load *attempt*, not a file existence test: a file being
  present says nothing about whether the linker will resolve it, and the linker
  namespace is precisely what is in question.
- **NPU** — lists `applicationInfo.nativeLibraryDir` and looks for a `.so` that
  is not part of the LiteRT-LM payload. On a stock build there is none, so it
  reports unavailable and names the directory it searched.

Every answer carries a human-readable `detail` and a `missingLibraries` list, and
`AcceleratorStatus` refuses to be constructed with a blank detail — a fallback
that renders as an empty string in the UI is indistinguishable from a crash.

The probe is **injected** everywhere it is used. `System.loadLibrary` is a
constructor parameter; the native library directory is a constructor parameter.
Nothing in the logic under test reads `Build.*`, which is why a phone with an NPU
and a phone without one are both reachable from a JVM test.

## The fallback table

`AUTO` is the default. Every candidate order ends at CPU, so selection is total:
no phone can fail to load a model because it lacks an accelerator.

| Preference | Device | Builds | Reported | Fallback reason |
|---|---|---|---|---|
| AUTO | NPU delegate + OpenCL | NPU | NPU | *none* — nothing was given up |
| AUTO | OpenCL only | GPU | GPU | `NPU unavailable: no NPU delegate library in <dir>…; using GPU instead` |
| AUTO | CPU only | CPU | CPU | `NPU unavailable: …; GPU unavailable: no OpenCL driver or vndksupport…; using CPU instead` |
| CPU | anything | CPU | CPU | *none* — explicit CPU is honoured even when GPU is available |
| GPU | OpenCL | GPU | GPU | *none* |
| GPU | CPU only | CPU | CPU | `GPU unavailable: …; using CPU instead` |
| NPU | NPU delegate | NPU | NPU | *none* |
| NPU | OpenCL only | GPU | GPU | `NPU unavailable: …; using GPU instead` |
| NPU | CPU only | CPU | CPU | `NPU unavailable: …; GPU unavailable: …; using CPU instead` |
| NPU, probe passed, `initialize()` throws | — | GPU, then CPU | whatever builds | `the NPU engine failed to initialise (NPU: QNN: unsupported SoC generation); using GPU instead` |
| any, every tier throws | — | — | load fails | `LiteRT-LM could not load <path> on any accelerator (NPU: …; GPU: …; CPU: …)` |

Two rules in that table are worth stating outright:

- **An explicit preference is honoured exactly when possible, and degraded
  otherwise.** A user who pinned CPU to get a comparable baseline is never moved
  to GPU. A user who pinned NPU on a phone without one still gets a working
  model.
- **Every degradation is explained, and names what was missing.** Both reasons
  are kept when both apply: a tier the probe skipped *and* a tier that then
  failed to initialise. Dropping either makes the report lie about what happened.

## Why there are two fallbacks

The probe is a strong signal, not a guarantee. A vendor delegate can be in the
APK and built for the wrong Hexagon generation; a driver can enumerate an OpenCL
device and still fail to compile the model's kernels. Both fail *after* the
2 GB model file has been opened. So `load()` walks the ladder a second time with
the engine factory as the oracle, keeping the runtime's own error text in the
report, and reports whichever tier actually built.

It also starts at the tier the probe cleared rather than at the top. Re-attempting
an accelerator a directory listing already proved absent means a doomed native
call on every single load.

## Reading the result

```kotlin
backend.acceleratorReport.active          // AcceleratorKind.NPU | GPU | CPU
backend.acceleratorReport.degraded        // true if not the requested tier
backend.acceleratorReport.fallbackReason  // null iff the ideal tier was delivered
backend.acceleratorReport.describe()      // one line for a UI footer
```

`ModelBackend.acceleratorReport` is an extension property, so it works on any
backend and returns `UNKNOWN` for one that does not report. Nothing was added to
`ModelCapabilities` — it is a frozen five-field contract about *model* abilities,
and accelerator hardware is a property of the *machine*. `AcceleratorAware` is
opt-in and additive.

This is the point of the whole change: a tok/s number without the hardware it
came from is not a measurement. A `CPU: <n> tok/s` row and an `NPU: <n> tok/s`
row have to be distinguishable rows — which means the values are hardware,
model, and context specific, and neither of those two numbers exists yet. Do not
read them as an expectation: **no tok/s figure has been measured on any device
in this project.**

## Which chips can reach which path, today

Distinguishing two things that are constantly conflated:

**Reachable by a normal app today**
- **Any arm64 phone with an OpenCL driver → GPU.** Adreno (`libOpenCL-car.so`),
  Mali/PowerVR (`libOpenCL.so`), and Google Tensor (`libOpenCL-pixel.so`).
  This is the real, working acceleration story today, and it is a GPU story.
- **NPU → nothing, on any chip**, until a vendor delegate is packaged into the
  APK. The hook exists; the library does not.

**Documented as EAP / not general**
- **Qualcomm AI Engine Direct** and **Intel OpenVINO** are the publicly
  documented LiteRT NPU delegate routes. Both require the integrator to obtain,
  license and package the vendor delegate — which this app does not do.
- **Google Tensor / LiteRT-Next NPU** on Pixel is a separate experimental-access
  path, not the public vendor-delegate route. A Pixel user with the EAP can
  reach an NPU; a Pixel user without it gets a GPU via `libOpenCL-pixel.so`.
- Google's own LiteRT NPU delegate page lists **Pixel, Samsung System LSI and
  MediaTek as "coming soon"** for general NPU delegates. That is their status,
  and this document does not improve on it.

So: **on a Pixel, the working accelerated path today is the GPU, not the Tensor
NPU.** Anyone telling you otherwise is describing the EAP, or describing
`Backend.GOOGLE_TENSOR`, which is not in the 0.13.1 artifact this app compiles
against.

## What is verified, and what is not

**Verified by running it:**

- The whole project assembles: `./gradlew :app:assembleDebug` →
  `BUILD SUCCESSFUL`, with llama.cpp at tag **b4661** and the JNI compiled to
  `liblocalintelligence_llama_jni.so` for both `arm64-v8a` and `x86_64`. The
  `.so` name is pinned in `CMakeLists.txt` via
  `OUTPUT_NAME "localintelligence_llama_jni"`; Android's loader is
  case-sensitive and the CamelCase default made every model load throw.
  Re-measured on this branch: the packaged `.so` sizes are

  | `.so` | arm64-v8a | x86_64 |
  |---|---:|---:|
  | `liblitertlm_jni.so` | 14,882,976 | 18,047,160 |
  | `libLiteRt.so` | 5,064,136 | 6,997,656 |
  | `liblocalintelligence_llama_jni.so` | 5,001,336 | 4,948,472 |
  | `libLiteRtClGlAccelerator.so` | 2,778,128 | 3,466,440 |

  Debug, unstripped. Not shipping figures. See docs/build.md.
- **There are no tests.** `ls core/src/` → `main`, `ls android/src/` → `main`.
  The conventional test suite was deliberately deleted at the owner's explicit
  instruction, so any earlier claim of a passing test count for this code is
  void. Verified: this repository contains **no** reference to the previously
  quoted "1959 passing tests" figure, in any document or workflow. If another
  document quotes a count, treat it as stale.
- `grep -rn "^import android" core/src/` — empty. `:core` is still pure JVM.
- The two `uses-native-library` entries are in the built APK, confirmed with
  `aapt2 dump xmltree` (see the PR body for the output).
- The LiteRT-LM Kotlin API surface (`Backend.CPU/GPU/NPU`, their exact
  constructors and defaults) was read out of the shipped AAR with `javap`, and
  the native library requirements were read out of the shipped `.so` files with
  `strings` and `readelf`. The claims above about which libraries get `dlopen`ed
  are not from documentation.
- Re-confirmed against the shipped 0.13.1 AAR for this change: `unzip` of the
  payload lists exactly `liblitertlm_jni.so`, `libLiteRt.so` and
  `libLiteRtClGlAccelerator.so` per ABI, and `javap` on `classes.jar` lists
  `Backend$CPU`, `Backend$GPU`, `Backend$NPU` and no `Backend$GOOGLE_TENSOR`.

**Not verified — no device and no model:**

There is no Pixel here, no emulator in the loop, and no `.litertlm` model
anywhere. Therefore:

- **Real NPU acceleration is UNVERIFIED; no device or model was available.**
  Nothing in this repository demonstrates that a `Backend.NPU` engine runs, or
  that it is faster than anything. The probe has no tests either — the
  `FakeAcceleratorProbe` in `LiteRtLmCapabilityProbe.kt` exists as a seam, but
  the suite that once exercised it was deleted, so nothing calls it.
- **Real GPU acceleration is likewise UNVERIFIED.** That the *manifest entries*
  are correct is verified (they are in the APK); that they cause a real OpenCL
  driver to be reachable on a real Pixel is not.
- **There is no tok/s figure in this document because there cannot be one.** The
  entire purpose of the second backend is to produce a comparison, and it has not
  been produced. Do not quote a number from this file.
- Whether `System.loadLibrary` on a vendor soname behaves identically inside and
  outside an app process, and whether any given phone's driver is in a namespace
  the app can reach at all.
- **The emulator cannot settle any of this.** An x86_64 emulator has no vendor
  OpenCL driver and no NPU delegate of any vendor, and llama.cpp on an emulated
  x86 core was observed at roughly 0.67 tok/s. **That figure describes emulated
  CPU and nothing else** — it is not a device number, not a baseline, and not
  evidence that a phone would be slow. Every NPU claim made here is therefore
  unverifiable on the available hardware *by construction*, not merely untested.
  `docs/measurements.md` is the authoritative list of what has and has not been
  measured.

To close the gap: obtain a pre-converted `.litertlm` model, install it on a real
arm64 phone, run the eval harness once per preference, and record
`acceleratorReport.describe()` beside each tok/s number. Until that is done,
treat the acceleration story as "GPU likely works, NPU definitely does not, and
neither can be reached with a model this app can download".

## What a user on a real Pixel 10 Pro gets, today

Stated plainly, because the table above invites a more optimistic reading than
the evidence supports.

**What works:**
- GGUF models from the hub or the model manager, on **llama.cpp, CPU only**.
  This is the entire shipped experience. No GPU, no NPU.
- LiteRT-LM is present, complete, correctly probing, and reachable — *if* the
  user gets a `.litertlm` into `filesDir/models` by other means. See the
  acquisition table above: as shipped, none of the app's own routes gets it
  there, so "by other means" means `adb push` and a source edit to the scan.

**What does not work:**
- **The NPU, on this Pixel and every other phone.** The Tensor NPU is not
  reachable with `litertlm-android:0.13.1`, on any chip, because the AAR ships
  no NPU library. Google Tensor's NPU access is a separate experimental-access
  path, not something an app can opt into from a public artifact.
- **Any model the app downloads.** The hub serves GGUF; a GGUF cannot be run by
  LiteRT-LM; there is no converter. So a user who has never sideloaded a
  `.litertlm` cannot reach the LiteRT-LM backend by any route the app offers.
- **Any measured speed comparison between the two backends.** Neither has been
  run against a model on a device.

**The realistic GPU path, when a `.litertlm` is in hand:** a Tensor GPU is
reachable through `libOpenCL-pixel.so` plus the manifest declarations, so
`AcceleratorPreference.GPU` on a Pixel should resolve to the GPU rather than
falling back to CPU — **unverified on hardware**, and reachable only with a
model the app cannot fetch for you.

## Relationship to the llama.cpp backend

Out of scope here, deliberately: `android/inference/LlamaCppBackend.kt` was not
touched, and its own GPU story (Vulkan) is a different piece of work with a
different risk profile. If someone asks which is the better path to phone
acceleration, the honest answer is that **neither is measured**, and that the
LiteRT-LM GPU route at least has a documented, probeable mechanism behind it
(OpenCL + a manifest declaration) rather than a driver-porting exercise.
