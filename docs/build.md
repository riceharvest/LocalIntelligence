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
| JDK | **21** | `JAVA_HOME` | every module declares `kotlin { jvmToolchain(21) }`. LiteRT-LM 0.13.1 ships Java 21 bytecode (class file major 65); a JDK 17 toolchain cannot read it at all. |
| Android platform | `android-36` | SDK | `compileSdk = 36` in both Android modules |
| NDK | **27.1.12297006** | declared in `android/build.gradle.kts` | pinned via `ndkVersion` (see §2) |
| CMake | `3.22.1` | SDK | declared in `android/build.gradle.kts` `externalNativeBuild.cmake.version` |
| build-tools | 35.0.x or 36.x | SDK | not pinned; AGP picks a default |
| llama.cpp | tag **b4661** | `PIDROID_LLAMA_DIR` or CMake FetchContent | see §3 |

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

`FetchContent` fetches the pinned tag `b4661` when no local path or checkout is found. The second `add_subdirectory` is guarded by `if(NOT _llama_src_dir_from_fetchcontent)`, so `FetchContent` works out of the box on a fresh clone.

For local development or faster builds, use route 1:

```bash
git clone --depth 1 --branch b4661 https://github.com/ggml-org/llama.cpp /tmp/llama.cpp
export PIDROID_LLAMA_DIR=/tmp/llama.cpp
```

### Why the tag is pinned

The JNI sets `use_mmap` on `llama_model_params`, and `logits_all` and `flash_attn`
on `llama_context_params`. A newer llama.cpp **removed all three**, so building
against `main` does not compile. `b4661` is a fixed point that works:

```
$ git clone --depth 1 --branch b4661 https://github.com/ggml-org/llama.cpp /tmp/llama-b4661
$ git -C /tmp/llama-b4661 rev-parse HEAD
ec3bc8270bc67b58955748d40a3e558a05b2d8f2
```

The tag resolves to that exact commit.

---

## 4. Measured build output

**MEASURED**, from `./gradlew :app:assembleDebug` locally (and historically on a
GitHub runner in run 36196368752, PR #43):

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
