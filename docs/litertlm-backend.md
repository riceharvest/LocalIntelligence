# LiteRT-LM as a second ModelBackend

**Status: implemented, unit-tested, not yet run on a device.**

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

**LiteRT-LM 0.13.1 is Java 21 bytecode (class file major version 65). This repo
compiles with `jvmToolchain(17)`, so the unit-test JVM is JDK 17 and cannot load
it:**

```
java.lang.UnsupportedClassVersionError:
  com/google/ai/edge/litertlm/Message$Companion has been compiled by a more
  recent version of the Java Runtime (class file version 65.0), this version of
  the Java Runtime only recognizes class file versions up to 61.0
```

Compiling against it is fine — Kotlin reads the higher class version without
complaint. *Loading* it on JDK 17 is not.

So every LiteRT-LM type is reached through the `LiteRtLmEngine` /
`LiteRtLmConversation` seam, and the whole test suite runs against
`FakeLiteRtLmEngine`. `NativeLiteRtLmEngine.kt` is the only file in the package
that imports `com.google.ai.edge.litertlm`, which also means it is the only file
that breaks when the LiteRT-LM API changes shape.

This is a genuine constraint, not a workaround: the backend's actual job —
mapping the frozen `GenerationRequest` onto a streaming runtime, deciding when to
stop, mapping failures onto `StopReason` — is pure logic and is now tested
without a 22 MB native engine attached.

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

Verified for real:

- `:android:assembleDebug` — succeeds.
- `:android:test` — 966 tests, 0 failures, 0 errors, 0 skipped, of which **67 are
  new** (42 backend, 15 model source, 10 delta tracker).
- `:core` has no `import android.*` and `git diff origin/main -- core/` is empty.
- The three LiteRT-LM native libraries package into the APK for both ABIs.

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

The LiteRT-LM Kotlin API was reverse-engineered from the shipped
`classes.jar` (`javap`) plus the v0.13.1 sources on GitHub, not from a running
device. Signatures are real; runtime behaviour is not yet observed.
