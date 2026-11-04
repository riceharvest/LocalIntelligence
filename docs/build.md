# Building this project

Everything here was executed, not reasoned about. Anything not executed is
marked **UNVERIFIED** in the text.

The short version:

```bash
export JAVA_HOME=/path/to/jdk-21
export ANDROID_HOME=/path/to/android-sdk

git clone --depth 1 --branch b11988 https://github.com/ggml-org/llama.cpp /tmp/llama.cpp
PIDROID_LLAMA_DIR=/tmp/llama.cpp ./gradlew :app:assemblePlayDebug
```

The APK lands at `app/build/outputs/apk/play/debug/app-play-debug.apk` (or `:app:assembleFullDebug` for `app/build/outputs/apk/full/debug/app-full-debug.apk`).

### Two builds

`:app` has two product flavors on the `dist` dimension (see [docs/adr/0002-play-and-full-builds.md](adr/0002-play-and-full-builds.md)). The `play` flavor (`:app:assemblePlayDebug`, default, package id `dev.localintelligence.app`) ships everything Google Play allows. The `full` flavor (`:app:assembleFullDebug`, package id `dev.localintelligence.app.full`, label "LocalIntelligence Full") is installed outside Google Play alongside the Play app and adds capabilities Play forbids, such as screen control through Accessibility.

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
llama.cpp `b11988` (with libmtmd) and LiteRT-LM `0.17.1`, after the
feature-parity PRs (#68-#94):

| | bytes | |
|---|---:|---|
| `app-debug.apk` | 68,025,501 | 64.9 MiB, **debug**, both ABIs |

Native libraries are extracted at install (`useLegacyPackaging = true`, needed
by the Tensor NPU path), so they are stored compressed in the APK. The rest of
the growth since the previous figure is Kotlin code and pdfbox-android
(document import).

Native payload, uncompressed (stripped):

| `.so` | arm64-v8a | x86_64 |
|---|---:|---:|
| `liblitertlm_jni.so` | 21,802,952 | 25,968,000 |
| `liblocalintelligence_llama_jni.so` (llama.cpp + libmtmd) | 16,007,264 | 16,353,128 |
| `libc++_shared.so` | 1,292,904 | 1,252,080 |
| `libLiteRtDispatch_GoogleTensor.so` | 579,016 | — |
| `libandroidx.graphics.path.so` | 10,096 | 10,760 |

**The native libraries are stripped only because `:app` pins the same NDK as
`:android`.** Without `ndkVersion` in `app/build.gradle.kts`, AGP falls back to
its default NDK (27.0.12077973), which is not installed, logs "Unable to strip"
and ships every `.so` with its debug info.

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

---

## 7. Release builds

Release builds are signed only on the machine that holds the keys. Without the key
setup below, `assembleRelease` and `bundleRelease` still configure and produce
**unsigned** outputs, so every other build is unchanged.

**Where the keys live.** Outside the repository, in `/home/dario/LI-keys/`:

| File | What it is |
|---|---|
| `play-upload.jks` | PKCS12 keystore, Play upload key (`play.*` keys in the properties file) |
| `full-release.jks` | PKCS12 keystore, full-build key (`full.*` keys) |
| `keystore.properties` | store paths, aliases and passwords for both keystores |

`~/.gradle/gradle.properties` holds one line that points at the properties file:
`liSigningProps=/home/dario/LI-keys/keystore.properties`. The passwords are only in that
file. Do not copy them into the repository, a shell history or a chat.

**What may be lost, and what may not.**

- The **Play upload key** may be lost. Play App Signing lets the owner reset the upload
  key in Play Console, so a lost upload key costs a reset, not the app.
- The **full key** may not be lost. Every installed copy of the full build was signed with
  it, and Android installs an update only when the signature matches. A lost full key means
  users can never update in place; they would have to uninstall, which deletes their chats,
  models and settings.

**What to back up.** Copy `play-upload.jks`, `full-release.jks` and `keystore.properties`
together, to at least two places that are not this machine. Keep the copy with the full key
somewhere that survives this desktop's disk failing.

**Building.** From the repository root:

```bash
scripts/release.sh
```

It refuses to run without `liSigningProps`, builds `:app:bundlePlayRelease` and
`:app:assembleFullRelease` (with the same resource wrapper as other builds), checks 16 KB
page alignment on both outputs, verifies each output is signed with the key it is meant for
and prints the signing certificate's SHA-256, then copies the results to `dist/`:

- `dist/LocalIntelligence-<versionName>.aab` for Google Play
- `dist/LocalIntelligence-Full-<versionName>-full.apk` for GitHub Releases, with a `.sha256` file

It never uploads, tags or creates a release. Google Play needs a higher `versionCode` than
the last upload; bump `versionCode` in `app/build.gradle.kts` first. `dist/` is git-ignored.

**R8.** Release builds minify and shrink. The keep rules are in `app/proguard-rules.pro`
and `android/consumer-rules.pro`, each with the reason it exists. Anything the native code or
a library reaches by name needs one, so a new by-name lookup needs a new rule.

**Status.** UNVERIFIED on a phone: the signed outputs have not been installed or run on a
device, and the Play upload has not been made.

---

## 8. The Play build without texts and call log (`-PplayNoSms=true`)

The `play` flavor declares `READ_SMS`, `SEND_SMS` and `READ_CALL_LOG` (the default
assistant declaration in [docs/roadmap.md](roadmap.md), "Before public release"). A fallback
build leaves them out, for the case where Google refuses that declaration:

```bash
./gradlew :app:bundlePlayRelease -PplayNoSms=true
```

- Default is `false`. Without the property the play build is unchanged.
- It applies only to the `play` flavor. `full` never reads the property.
- The overlay `app/src/playNoSms/AndroidManifest.xml` removes `READ_SMS`, `SEND_SMS`,
  `RECEIVE_SMS` and `READ_CALL_LOG` from the merged manifest with `tools:node="remove"`.
  `RECEIVE_SMS` is not declared by the app today, so its line changes nothing.
- The `sms.search`, `sms.send` and `calls.recent` tools stay registered, and each one returns
  "not in this build" before it asks for the default-assistant role or a permission. The
  Permissions screen hides the three texts and call-log rows.
- `sms.send` still asks for confirmation before it runs, so in this build the person can
  confirm a send that then reports that the build lacks it.

**Status.** Compiled only, as the rest of this file. UNVERIFIED on a phone, and the merged
manifest has not been inspected on a device.
