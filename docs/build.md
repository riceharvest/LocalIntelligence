# Building this project

Everything here was executed, not reasoned about. Anything not executed is
marked **UNVERIFIED** in the text.

The short version:

```bash
export JAVA_HOME=/path/to/jdk-21
export ANDROID_HOME=/path/to/android-sdk

git clone --depth 1 --branch b11988 https://github.com/ggml-org/llama.cpp /tmp/llama.cpp
PIDROID_LLAMA_DIR=/tmp/llama.cpp ./gradlew :app:assembleDebug
```

The APK lands at `app/build/outputs/apk/debug/app-debug.apk`.

---

## 1. What the build needs

| Requirement | Version | How it is set | Why |
|---|---|---|---|
| JDK | **21** | `JAVA_HOME` | every module declares `kotlin { jvmToolchain(21) }`. LiteRT-LM ships Java 21 bytecode (class file major 65); a JDK 17 toolchain cannot read it at all. LiteRT-LM 0.17.1 is also compiled with Kotlin 2.4, which is why the project is on Kotlin 2.4.21. |
| Android platform | `android-36` | SDK | `compileSdk = 36` in both Android modules |
| NDK | **27.1.12297006** | declared in `android/build.gradle.kts` | pinned via `ndkVersion` (see §2) |
| CMake | `3.22.1` | SDK | declared in `android/build.gradle.kts` `externalNativeBuild.cmake.version` |
| build-tools | 35.0.x or 36.x | SDK | not pinned; AGP picks a default |
| llama.cpp | tag **b11988** | `PIDROID_LLAMA_DIR` or CMake FetchContent | see §3 |

---

## 2. The NDK is 27.1.12297006

`android/build.gradle.kts` explicitly declares:

```kotlin
ndkVersion = "27.1.12297006"
```

Install `ndk;27.1.12297006` via the SDK manager.

---

## 3. Getting llama.cpp

`android/src/main/cpp/CMakeLists.txt` offers three ways to find llama.cpp, in this order:

1. `PIDROID_LLAMA_DIR` — an explicit path you point at
2. `<repo>/llama.cpp` — a checkout placed at the repo root. It is not a git submodule; the repository has none
3. `PIDROID_LLAMA_FETCH` — CMake `FetchContent` (default is `ON`)

`FetchContent` fetches the pinned tag `b11988` when no local path or checkout is found. The second `add_subdirectory` is guarded by `if(NOT _llama_src_dir_from_fetchcontent)`, so `FetchContent` works out of the box on a fresh clone.

For local development or faster builds, use route 1:

```bash
git clone --depth 1 --branch b11988 https://github.com/ggml-org/llama.cpp /tmp/llama.cpp
export PIDROID_LLAMA_DIR=/tmp/llama.cpp
```

### Why the tag is pinned

llama.cpp's C API moves, so the JNI is written against one tag. It uses
`load_mode` on `llama_model_params`, `flash_attn_type` on
`llama_context_params`, and `llama_memory_clear()`. The previous pin, `b4661`
(February 2025), predates all three, and it predates the models people run
now: it has no Gemma 3 or 4, no Qwen 3, and no LFM3. `b11988` is the fixed
point the JNI builds against:

```
$ git clone --depth 1 --branch b11988 https://github.com/ggml-org/llama.cpp /tmp/llama-b11988
$ git -C /tmp/llama-b11988 rev-parse HEAD
abb76ab380c7f3f731e365a859fb4cc64e939bb8
```

The tag resolves to that exact commit.

---

## 4. Measured build output

**MEASURED** 2026-10-30, from `./gradlew :app:assembleDebug` locally at
llama.cpp `b11988` and LiteRT-LM `0.17.1`:

| | bytes | |
|---|---:|---|
| `app-debug.apk` | 51,343,857 | 49.0 MiB, **debug**, both ABIs |

Native libraries are now **extracted at install** (`useLegacyPackaging = true`,
needed by the Tensor NPU path; see [`acceleration.md`](acceleration.md)), so
they are stored compressed in the APK. That is why the APK shrank from
127,485,666 bytes (2026-10-29, libraries stored uncompressed); the installed
footprint is the APK plus the extracted libraries of one ABI.

Native payload, uncompressed (stripped):

| `.so` | arm64-v8a | x86_64 |
|---|---:|---:|
| `liblitertlm_jni.so` | 21,802,952 | 25,968,000 |
| `liblocalintelligence_llama_jni.so` | 12,752,696 | 12,918,376 |
| `libc++_shared.so` | 1,292,904 | 1,252,080 |
| `libLiteRtDispatch_GoogleTensor.so` | 579,016 | — |
| `libandroidx.graphics.path.so` | 10,096 | 10,760 |
| **one ABI's total** | **36,437,664** | **40,149,216** |

From LiteRT-LM 0.17.1 the runtime and its GPU accelerator are one library,
`liblitertlm_jni.so` (0.13.1 shipped `libLiteRt.so` and
`libLiteRtClGlAccelerator.so` separately). The Tensor dispatch library is
arm64 only.

**The native libraries are stripped only because `:app` pins the same NDK as
`:android`.** Packaging strips with the app module's NDK. Without `ndkVersion`
in `app/build.gradle.kts`, AGP falls back to its default NDK (27.0.12077973),
which is not installed. It then logs "Unable to strip" and ships every `.so` with
its debug info.

**These are debug numbers.** The APK carries both ABIs and unminified dex. A
device installs one ABI, and release builds are smaller.

---

## 5. There is no CI

CI workflows previously existed but were removed in commit `5b159bb`. There is
no CI. The verification scripts in `.github/scripts/` can be run manually by
hand; see [docs/ci/README.md](ci/README.md).

---

## 6. There is no test suite

Stated here because it is the single most surprising fact about this repository:

```
$ ls core/src/        # -> main
$ ls android/src/     # -> main
```

The conventional test suite, the fake eval harness, and all test-only / CI jobs
were **deliberately deleted at the owner's explicit instruction**. `core/build.gradle.kts`
records why:

> The agent eval suite lived in `core/src/test` and ran against fakes, not the
> real loop and a real model. It reported 50/50 for a harness that could not
> fail, and the app it "verified" could not answer a single question.

**Do not propose restoring a test suite as a goal, and do not add CI workflows.**
Verification here means running a real model on a real device. See
[`evals.md`](evals.md) for what replaced the harness and what is still missing.
