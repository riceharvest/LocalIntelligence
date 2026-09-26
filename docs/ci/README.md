# CI

What this pipeline checks, why each check exists, and how to reproduce any of it
by hand. Everything here is observable: every number was measured, and the
command that produced it is given.

For the Android Lint situation specifically, see [lint.md](lint.md).

**One real defect was found while building this and is not fixed**, because the
file that needs changing is owned by another workstream: the two llama.cpp
resolution paths produce different APKs. See
[open-defect-shared-libs.md](open-defect-shared-libs.md).

## The short version

There are three jobs. One of them is the reason this pipeline exists.

| Job | What it proves | Observed duration |
| --- | --- | --- |
| `core (JVM compile)` | `:core` compiles on a JDK 21, and has no Android in it | **1m27s** (run 36208616566) |
| `fresh clone (no PIDROID_LLAMA_DIR)` | **A stranger can clone this repository and build it** | **4m52s** total, of which the build is **253s** (run 36208616566) |
| `android lint (ratcheted...)` | Lint runs, and no *new* error appears | **3m04s** (run 36208616566) |

**All three durations were observed on a GitHub `ubuntu-latest` runner**, in run
`36208616566`, on this branch. A developer machine is a different machine with a
different filesystem cache, a different CPU and a different network. Treat
these as CI numbers, not as "how long a build takes".

The fresh-clone **build step** is 253s on the runner against 102s on the
developer machine — the same `--no-build-cache` cold build, 111/111 tasks
executed in both cases. The gap is hardware and cold page cache, not a
different amount of work. That is the single most useful thing to know when
reading these numbers: the difference between "fast laptop" and "CI" here is
about 2.5x, and the *work* is identical.

The job's 4m52s wall-clock includes ~30s of SDK/NDK discovery and install
before the build starts, which is why it is longer than the build itself.

The earlier note that the APK job takes "4m39s" is close to today's 4m52s, but
for a different reason: that run was installing and downloading an NDK the build
then did not use, plus a cold NDK fetch. Today's number installs the NDK the
build actually declares.

## The fresh-clone job

This is the check that was missing, and it is the one that matters.

### What was wrong

CI used to clone llama.cpp itself and set `PIDROID_LLAMA_DIR` to point at the
clone. `PIDROID_LLAMA_DIR` is the *first* thing
`android/src/main/cpp/CMakeLists.txt` checks, and it short-circuits the
FetchContent branch entirely. So CI was testing the one path that was known to
work, and never testing the path every new contributor takes.

That path was broken twice, and CI was green through both:

1. **Fetch was off by default.** A fresh clone failed with
   `llama.cpp sources not found`, and the error message told the user to run
   `git submodule update --init --recursive` — for a submodule that does not
   exist. There is no `.gitmodules` in this repository. A first-time builder
   was sent down a dead end by our own error message.
2. **Fetch could not have worked anyway.** `FetchContent_MakeAvailable(llama)`
   already calls `add_subdirectory` on the fetched tree, and the file then
   called `add_subdirectory` on the same directory a second time, so CMake
   failed with `add_library cannot create target "llama"`. That branch had never
   built anything. It stayed invisible for the same reason: locally,
   `PIDROID_LLAMA_DIR` short-circuits it.

Both are fixed. This job is what keeps them fixed.

### What the job actually does

1. **Asserts its own preconditions.** If `PIDROID_LLAMA_DIR` or
   `PIDROID_LLAMA_FETCH` is set in the environment, or a `.gitmodules` has
   appeared, the job fails immediately. Without this the job could silently
   degrade into the old test.
2. **Asserts FetchContent is still the default**, by reading the `option(...)`
   line out of `CMakeLists.txt`. Flipping it back to `OFF` fails in seconds
   rather than 100 seconds later inside CMake.
3. **Scans the repository for credential-shaped strings** (public repo).
4. **Verifies the NDK the build declares is the NDK installed** — see
   [the note below](#the-ndk-check).
5. **Builds with `--no-build-cache`**, timing the result.
6. **Proves llama.cpp was really fetched** by finding the FetchContent source
   tree on disk and resolving its commit. "It built" cannot quietly mean "it
   found something".
7. **Runs the artifact gates** on the resulting APK.

### Why `--no-build-cache`

Because the build cache can satisfy a task from a *previous commit*. If the
cache is on, this job can report success for a build that never ran — which is
exactly the failure mode it exists to prevent.

It also changes what the number means. Measured on a developer machine, same
fresh clone, same command:

| Run | Result |
| --- | --- |
| First run (cache on) | `BUILD SUCCESSFUL in 47s`, `111 actionable tasks: 59 executed, 52 from cache` |
| `--no-build-cache`, cold | `BUILD SUCCESSFUL in 1m 40s`, `111 actionable tasks: 111 executed` |

The 47-second figure is real but it is a *cache-hit* number. The honest cold
number on the same machine is **102 seconds, 111 of 111 tasks executed**; on a
GitHub runner it is **253 seconds, still 111 of 111 tasks executed** (run
`36208616566`). Both are quoted in the workflow's own step comment, labelled by
where they were measured.

### The runner run, in full

Run `36208616566`, on this branch, all three jobs green:

```
core (JVM compile)                              success  1m27s
fresh clone (no PIDROID_LLAMA_DIR)              success  4m52s
android lint (ratcheted, 3 known errors tracked) success  3m04s
```

From the fresh-clone log:

```
BUILD SUCCESSFUL in 4m 12s
111 actionable tasks: 111 executed
=== fresh-clone build: 253s (no PIDROID_LLAMA_DIR, no build cache) ===

Fetched llama.cpp at android/.cxx/RelWithDebInfo/294v4e6u/x86_64/_deps/llama-src
resolved commit: ec3bc8270bc67b58955748d40a3e558a05b2d8f2 (tag b4661, expected ec3bc82...)
OK: PIDROID_LLAMA_DIR is unset
OK: PIDROID_LLAMA_FETCH is unset
OK: no .gitmodules, so nothing but FetchContent can supply llama.cpp
OK: PIDROID_LLAMA_FETCH defaults to ON, so a fresh clone needs no setup
OK: the build declares 27.1.12297006 and that is what is installed
OK: the JNI library is named localintelligence_llama_jni in CMake, in Kotlin, and in the APK.
OK: no NPU/accelerator library in the APK (CPU inference only, as claimed).
OK: APK carries both 64-bit ABIs, the JNI library in each, and no NPU runtime.
```

Note what the `Fetched llama.cpp at ...` line is for: the build would succeed
just as well if a checkout had appeared by some other route. That line is what
makes "it built" mean "the FetchContent default worked".

The runner's APK was 126,857,675 bytes with 51,015,960 bytes of arm64-v8a
payload. Both are **debug** figures: unstripped `.so` files, one APK carrying
both ABIs, while a device installs one ABI. Not shipping sizes.

### Reproducing it by hand

```sh
git clone <this repo> fresh && cd fresh
export JAVA_HOME=$HOME/jdk21
export ANDROID_HOME=$HOME/Android/Sdk
unset PIDROID_LLAMA_DIR PIDROID_LLAMA_FETCH
time ./gradlew --no-build-cache :core:assemble :android:assemble :app:assembleDebug
```

The two `unset` calls are not optional. Leaving `PIDROID_LLAMA_DIR` set
reproduces the old CI job, not this one.

## The artifact gates

These read the built APK rather than the source, so a comment or an intention
cannot satisfy them.

### The JNI library name

`.github/scripts/check-native-lib-name.sh`

Commit `a902c56` ("fix(android): a real model now loads and generates on a
device", bug 1 of 4). CMake named the target `LocalIntelligence_llama_jni`, so
the APK packaged `libLocalIntelligence_llama_jni.so`, while
`LlamaBridge.kt:26` called:

```kotlin
System.loadLibrary("localintelligence_llama_jni")
```

Android's linker is case-sensitive and does **not** normalise the name, so
every load threw *"the llama.cpp native library is not available on this
device"*. The native backend was dead on arrival: the app could not load a model
at all. It shipped because the build compiles and the test suite is gone —
nothing between `assemble` and `dlopen` was ever checked.

The gate is a three-way agreement — CMake's `OUTPUT_NAME`, the Kotlin constant,
and the file actually in the APK — because that is precisely what broke. Any
two agreeing while the third differs is the `a902c56` failure mode.

### `:core` purity

`.github/scripts/check-core-purity.sh`

The project's one non-negotiable rule, from `settings.gradle.kts`:

> THE ONE ARCHITECTURAL RULE: `:core` is a PURE KOTLIN/JVM module with ZERO
> Android dependencies.

It was enforced by a comment and by `docs/architecture.md`, and nothing checked
it. A single `import android.os.Build` compiles fine and silently converts the
agent loop into something that cannot run on the JVM — which is the only reason
a swarm of agents can verify it in seconds. The gate also catches a
fully-qualified `android.os.Build` reference with no import, which an
import-only check would miss.

### Both 64-bit ABIs, and no NPU library

`.github/scripts/inspect-apk.sh`

`android/build.gradle.kts` declares `abiFilters += listOf("arm64-v8a", "x86_64")`.
A single-element list compiles identically, produces an installable APK, and is
a complete success at build time. It is only wrong on a device: drop `x86_64`
and every x86_64 emulator, and a slice of Chromebooks, fails to install with no
build-time signal at all.

The NPU check exists because this app does CPU inference only and the
documentation is careful never to claim an accelerator it does not use. That
claim has nothing holding it up. LiteRT-LM 0.13.1
(`gradle/libs.versions.toml:27`) ships exactly three libraries per ABI and none
is an NPU runtime — verified by unzipping the AAR out of the Gradle cache, not
from release notes:

```
arm64-v8a/libLiteRt.so
arm64-v8a/libLiteRtClGlAccelerator.so
arm64-v8a/liblitertlm_jni.so
```

A future version, or one new dependency, could add an NPU runtime. The APK would
grow, and the app would advertise a path that has never been run on a device.

`libomp.so` is deliberately **not** flagged: it is OpenMP, and flagging it would
train people to ignore this gate.

### No credentials in the repository

`.github/scripts/check-no-secrets.sh`

This repository is public. A key pushed to a public remote is compromised the
moment it lands, and the commit that adds it is green and stays green.

Scope, stated plainly, because a secret scanner that cries wolf gets deleted:
it matches known credential formats against their real shapes, and long literals
assigned to credential-named identifiers. It is a tripwire, not entropy
analysis. It will not find a secret under an unusual variable name, split
across lines, or base64'd inside something else. It also rejects hardcoded
personal absolute paths, which leak a username and a disk layout in a public
repo.

It has **no baseline file**, deliberately: a baseline is where secret scanners
go to be switched off.

## The NDK check

This one used to be theatre, which is worth explaining.

The old workflow pinned `NDK_VERSION: 27.0.12077973` and then checked that
`27.0.12077973` was installed. But `android/build.gradle.kts` declared
`27.1.12297006`, and AGP honours the *declaration*. So the check confirmed an
NDK the build never used, and AGP quietly downloaded the real one during the
build. In run `36207317459` the log shows
`Installing NDK (Side by side) 27.1.12297006` during the `Build debug APK` step
— i.e. after the check had already passed. The check was green and meaningless
at the same time.

The replacement reads the declaration out of `android/build.gradle.kts` and
fails if the two disagree, or if the build file declares nothing at all. Both
cases are verified to fail correctly.

## Things CI deliberately does not do

- **No tests.** The test suite was deleted at the owner's explicit instruction.
  There is no `:core:test`, no `connectedCheck`, no `testImplementation`. The
  pipeline compiles; it does not assert behaviour. It is not evidence the agent
  works.
- **No emulator.** It SIGSEGVs on the maintainer's host (exit 139). CI never
  started one either.
- **No performance or memory claim.** There are zero measured RAM numbers in
  this app. Nothing here measures performance, and no gate implies otherwise.
- **No new GitHub Actions.** Everything is plain shell against what the runner
  image already ships. `android-actions/setup-android@v3` was removed precisely
  because a third-party action rotted unnoticed: it runs `sdkmanager tools`,
  Google withdrew the deprecated `tools` package, and it died in 14 seconds on
  every run.
- **No accelerator claim.** Nothing in CI asserts NPU, TPU or Tensor support,
  because nothing has measured any.

## Scripts

All in `.github/scripts/`, all plain POSIX-ish bash, no dependencies.

| Script | Runs in | Fails on |
| --- | --- | --- |
| `check-core-purity.sh` | `core` | any `android.*`/`androidx.*` in `core/src`, the AGP plugin, or an Android dependency |
| `check-native-lib-name.sh <apk>` | `fresh-clone` | the JNI name differing in CMake, Kotlin, or the APK |
| `inspect-apk.sh <apk>` | `fresh-clone` | a missing 64-bit ABI, a missing JNI library, an NPU runtime, a malformed APK |
| `check-no-secrets.sh` | `fresh-clone` | a credential-shaped string, or a personal absolute path |
| `check-no-new-lint-errors.sh` | `lint` | any lint error beyond the three in `lint.md` |

Each gate was fault-injected before being committed: the real defect was
reintroduced into a scratch clone and the gate was confirmed to fail. A gate
that has never been seen to fail is not known to work.
