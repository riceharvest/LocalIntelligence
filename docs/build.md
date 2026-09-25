# Building this project

Everything here was executed, not reasoned about. Anything not executed is
marked **UNVERIFIED** in the text.

The short version:

```bash
export JAVA_HOME=/path/to/jdk-21
export ANDROID_HOME=/path/to/android-sdk

git clone --depth 1 --branch b4661 https://github.com/ggml-org/llama.cpp /tmp/llama.cpp
PIDROID_LLAMA_DIR=/tmp/llama.cpp ./gradlew :app:assembleDebug
```

The APK lands at `app/build/outputs/apk/debug/app-debug.apk`.

---

## 1. What the build needs

| Requirement | Version | How it is set | Why |
|---|---|---|---|
| JDK | **21** | `JAVA_HOME`, and `actions/setup-java` in CI | every module declares `kotlin { jvmToolchain(21) }`. LiteRT-LM 0.13.1 ships Java 21 bytecode (class file major 65); a JDK 17 toolchain cannot read it at all. |
| Android platform | `android-36` | SDK | `compileSdk = 36` in both Android modules |
| NDK | **27.0.12077973** | AGP's built-in default | see §2 — this is the one that surprises people |
| CMake | `3.22.1` | SDK | declared in `android/build.gradle.kts` `externalNativeBuild.cmake.version` |
| build-tools | 35.0.x or 36.x | SDK | not pinned; AGP picks a default |
| llama.cpp | tag **b4661** | `PIDROID_LLAMA_DIR` or a submodule | see §3 |

---

## 2. The NDK is 27.0.12077973, not 27.1.12297006

`android/build.gradle.kts` declares **no `ndkVersion`**. AGP therefore falls back
to the default compiled into AGP itself.

**MEASURED**, two independent ways:

1. The generated CMake cache names it:
   ```
   $ grep -o 'ndk/[0-9.]*' android/.cxx/Debug/*/x86_64/CMakeCache.txt | sort -u
   ndk/27.0.12077973
   ```
2. Extracting `gradle-8.13.2.jar` and grepping the constant:
   ```
   $ unzip -q -o gradle-8.13.2.jar -d /tmp/agpx
   $ grep -rla "27.0.12077973" /tmp/agpx | head
   /tmp/agpx/com/android/build/gradle/internal/dsl/CommonExtensionImpl.class
   /tmp/agpx/com/android/build/gradle/internal/cxx/settings/Macro.class
   /tmp/agpx/com/android/build/gradle/internal/cxx/configure/NdkLocatorKt.class
   ```

So **27.1.12297006 is not what this project compiles against today**, even though
that version appears in older notes and in a previous CI workflow. CI installs
27.0.12077973, and has a step that fails the build loudly if the resolved NDK ever
stops matching.

The durable fix is one line in `android/build.gradle.kts`:

```kotlin
android { ndkVersion = "27.0.12077973" }
```

That file belongs to a different workstream, so it is reported here rather than
edited. Until it lands, **the NDK version is an AGP implementation detail, not a
project decision**, and it can change under you on an AGP upgrade.

---

## 3. Getting llama.cpp: use `PIDROID_LLAMA_DIR`, not `PIDROID_LLAMA_FETCH`

`android/src/main/cpp/CMakeLists.txt` offers three ways to find llama.cpp, in this
order:

1. `PIDROID_LLAMA_DIR` — a path you point at
2. `<repo>/llama.cpp` — a submodule at the repo root
3. `PIDROID_LLAMA_FETCH=1` — CMake `FetchContent`

**Route 3 is broken and cannot work as written.** `FetchContent_MakeAvailable()`
already calls `add_subdirectory` on the fetched tree, and then line 161 calls
`add_subdirectory` on that same directory a second time. CMake fails:

```
CMake Error at .../_deps/llama-src/src/CMakeLists.txt:9 (add_library):
  add_library cannot create target "llama" because another target with the
  same name already exists. The existing target is a shared library created
  in source directory ".../_deps/llama-src/src".
  See documentation for policy CMP0002 for more details.
```

**MEASURED** — that is the verbatim configure failure from
`./gradlew :app:assembleDebug` with `PIDROID_LLAMA_FETCH=1` and no
`PIDROID_LLAMA_DIR`.

This went unnoticed for a long time because route 1 short-circuits it locally.
Every local build found a checkout on disk, so the broken branch was never
entered. The previous CI workflow set `PIDROID_LLAMA_FETCH=1` and would have hit
it — see §5.

**The one-line fix**, for whoever owns the CMakeLists:

```cmake
if(_llama_src_dir)
    # already added by FetchContent_MakeAvailable, or resolved above
    if(NOT TARGET llama)
        add_subdirectory("${_llama_src_dir}" llama.cpp EXCLUDE_FROM_ALL)
    endif()
endif()
```

Until that lands, use route 1. This is what CI does:

```bash
git clone --depth 1 --branch b4661 https://github.com/ggml-org/llama.cpp "$RUNNER_TEMP/llama.cpp"
echo "PIDROID_LLAMA_DIR=$RUNNER_TEMP/llama.cpp" >> "$GITHUB_ENV"
```

### Why the tag is pinned

The JNI sets `use_mmap`, `logits_all` and `flash_attn` on
`llama_context_params`. A newer llama.cpp **removed all three**, so building
against `main` does not compile. `b4661` is a fixed point that works:

```
$ git clone --depth 1 --branch b4661 https://github.com/ggml-org/llama.cpp /tmp/ci-llama
$ git -C /tmp/ci-llama rev-parse HEAD
ec3bc8270bc67b58955748d40a3e558a05b2d8f2
```

CI asserts that exact commit, so a moved tag fails the build instead of silently
changing what is compiled.

---

## 4. Measured build output

**MEASURED**, from `./gradlew :app:assembleDebug` locally and independently on
a GitHub runner (run 36196368752, PR #43):

| | local bytes | runner bytes | |
|---|---:|---:|---|
| `app-debug.apk` | 83,920,165 | 83,920,725 | 80.0 MiB, **debug, unstripped** |

The 560-byte difference between the two builds is normal APK variance (build
timestamps, zip alignment) and is the reason to quote a size as approximate.
The per-library figures below are from the local build; the runner's
`liblitertlm_jni.so` and `libLiteRt*.so` sizes matched them exactly.

Native payload, as packaged in the APK:

| `.so` | arm64-v8a | x86_64 |
|---|---:|---:|
| `liblitertlm_jni.so` | 14,882,976 | 18,047,160 |
| `libLiteRt.so` | 5,064,136 | 6,997,656 |
| `liblocalintelligence_llama_jni.so` | 5,001,336 | 4,948,472 |
| `libLiteRtClGlAccelerator.so` | 2,778,128 | 3,466,440 |
| `libc++_shared.so` | 1,292,896 | 1,252,080 |
| `libandroidx.graphics.path.so` | 10,096 | 10,760 |
| **one ABI's total** | **29,029,568** | **34,722,568** |

arm64-v8a one-ABI total is **27.7 MiB**.

**These are debug numbers.** The `.so` files are unstripped, and the APK carries
both ABIs. A device installs one ABI, and release builds are smaller. Do not quote
27.7 MiB as a shipping figure, and do not quote 80 MiB as the app's download size.

Note `libLiteRtClGlAccelerator.so` — the LiteRT-LM **OpenCL** GPU path, not
Vulkan. See [`acceleration.md`](acceleration.md).

---

## 5. Why CI was red on every run

The workflow had been failing continuously, on `main` and on every PR, and nobody
had looked at the log. Two independent causes:

**Cause 1 — `android-actions/setup-android@v3` no longer works.** It runs
`sdkmanager tools`, and the deprecated `tools` package has been withdrawn from
Google's repository. The action dies in about 14 seconds, before the build starts:

```
Warning: Failed to find package 'tools'
Error: The process '/usr/local/lib/android/sdk/cmdline-tools/16.0/bin/sdkmanager'
failed with exit code 1
```

The `core` job was green the whole time, which made the workflow look functional.
Only the APK job was broken, and it was broken at the setup step.

Fixed by dropping the action and using the runner image's preinstalled SDK
through plain shell. That removes a third-party action rather than adding one.

**Cause 2 — the NDK version was wrong.** The workflow installed
`ndk;27.1.12297006`, but the build resolves `27.0.12077973` (§2). AGP would then
either fail to find an NDK or silently download its default, so the job was not
testing the toolchain it claimed to test.

---

## 6. What CI does now

Two jobs, both **assembly/compile only**. There are no tests in this repository
and no test job, by the owner's explicit decision.

**`core (JVM compile)`** — compiles `:core` and enforces that it stays pure JVM
(no `android.*` import, no Android plugin). It asserts nothing about behaviour.
It is a compile gate, not evidence the agent works.

**`android + app (APK)`** — installs the SDK components, clones the pinned
llama.cpp, verifies the resolved NDK and the resolved llama.cpp commit, builds
`:app:assembleDebug`, reports the real artifact sizes, and uploads the APK.

The APK is uploaded as a build artifact (`LocalIntelligence-debug`,
14-day retention) so a human can install and run what CI built.

**VERIFIED on a GitHub runner.** Run
[36196368752](https://github.com/riceharvest/LocalIntelligence/actions/runs/36196368752)
on PR #43 completed **success**: `core (JVM compile)` in 39s, and
`android + app (APK)` in 4m39s, with the `LocalIntelligence-debug` artifact
uploaded (45,901,152 bytes compressed). It resolved NDK 27.0.12077973 and
llama.cpp `b4661` -> `ec3bc82` on the runner itself.

---

## 7. There is no test suite

Stated here because it is the single most surprising fact about this repository:

```
$ ls core/src/        # -> main
$ ls android/src/     # -> main
$ find . -type d -name test
./app/src/androidTest      # DeviceModelProbe.kt only — a manual probe, not a suite
```

The conventional test suite, the fake eval harness, and all test-only CI jobs
were **deliberately deleted at the owner's explicit instruction**. `core/build.gradle.kts`
records why:

> The agent eval suite lived in `core/src/test` and ran against fakes, not the
> real loop and a real model. It reported 50/50 for a harness that could not
> fail, and the app it "verified" could not answer a single question.

**Do not propose restoring a test suite as a goal, and do not add a test job to
CI.** Verification here means running a real model on a real device. See
[`evals.md`](evals.md) for what replaced the harness and what is still missing.
