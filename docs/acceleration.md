# Hardware acceleration on Android

**Status: selection, probing and fallback are implemented and unit-tested. No
accelerator has ever been run. There is no measurement in this document, and
there should not be one yet.**

Read the last section before you quote any of this. The short version: the
selection logic is verified, the hardware is not.

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
came from is not a measurement. `CPU: 8.2 tok/s` and `NPU: 21.4 tok/s` have to be
distinguishable rows.

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

- `:android:test` — 1274 tests, 0 failures, 0 errors, 0 skipped.
- `:core:test` — 685 tests, 0 failures, 0 errors, 0 skipped.
- 79 of those are new: 33 in `:core` (selection rules, parsing, reporting), 26 on
  the probe (which sonames, what is reported, what is cached), 20 on the backend
  (ladder order, degradation reasons, the two fallbacks).
- `:app:assembleDebug` — succeeds.
- `grep -rn "^import android" core/src/` — empty. `:core` is still pure JVM.
- The two `uses-native-library` entries are in the built APK, confirmed with
  `aapt2 dump xmltree` (see the PR body for the output).
- The LiteRT-LM Kotlin API surface (`Backend.CPU/GPU/NPU`, their exact
  constructors and defaults) was read out of the shipped AAR with `javap`, and
  the native library requirements were read out of the shipped `.so` files with
  `strings` and `readelf`. The claims above about which libraries get `dlopen`ed
  are not from documentation.

**Not verified — no device and no model:**

There is no Pixel here, no emulator in the loop, and no `.litertlm` model
anywhere. Therefore:

- **Real NPU acceleration is UNVERIFIED; no device or model was available. The
  capability probe and fallback logic are tested against a fake.** The tests
  inject the library sets. Nothing in this repository demonstrates that a
  `Backend.NPU` engine runs, or that it is faster than anything.
- **Real GPU acceleration is likewise UNVERIFIED.** The probe is tested against
  a fake `System.loadLibrary`. That the *manifest entries* are correct is
  verified (they are in the APK); that they cause a real OpenCL driver to be
  reachable on a real Pixel is not.
- **There is no tok/s figure in this document because there cannot be one.** The
  entire purpose of the second backend is to produce a comparison, and it has not
  been produced. Do not quote a number from this file.
- Whether `System.loadLibrary` on a vendor soname behaves identically inside and
  outside an app process, and whether any given phone's driver is in a namespace
  the app can reach at all.

To close the gap: install a `.litertlm` model on a real arm64 phone, run the eval
harness once per preference, and record `acceleratorReport.describe()` beside each
tok/s number. Until that is done, treat the acceleration story as "GPU likely
works, NPU definitely does not".

## Relationship to the llama.cpp backend

Out of scope here, deliberately: `android/inference/LlamaCppBackend.kt` was not
touched, and its own GPU story (Vulkan) is a different piece of work with a
different risk profile. If someone asks which is the better path to phone
acceleration, the honest answer is that **neither is measured**, and that the
LiteRT-LM GPU route at least has a documented, probeable mechanism behind it
(OpenCL + a manifest declaration) rather than a driver-porting exercise.
