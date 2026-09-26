# What is actually true about this repository right now

Everything below was **run**, not reasoned about. Commit `b9defbf` (`origin/main`).
No code was changed to produce any of it. The verification harness lives in
`verify/`, is not wired into the Gradle build, and is not part of CI.

The emulator was not started (it SIGSEGVs on this host). There are **zero**
measured RAM figures and **zero** measured tok/s figures in this report, because
none were produced. Nothing below is inferred from reading a comment.

---

## 1. Fresh clone: does a stranger get a working APK?

**A stranger gets a repo that FAILS to build with no local convenience.**

```
$ git clone https://github.com/riceharvest/LocalIntelligence.git stranger-clone
$ cd stranger-clone
$ export JAVA_HOME=$HOME/jdk21 ANDROID_HOME=$HOME/Android/Sdk
$ ./gradlew :android:assembleDebug
```

Result: **BUILD FAILED in 21s**. Exact first failure:

```
CMake Error at CMakeLists.txt:112 (message):
  llama.cpp sources not found.

  Run one of:

    git submodule update --init --recursive
    PIDROID_LLAMA_FETCH=1 ./gradlew :android:assembleDebug

  or point CMake at a checkout:

    PIDROID_LLAMA_DIR=/path/to/llama.cpp ./gradlew :android:assembleDebug

See android/src/main/cpp/CMakeLists.txt for details.
```

The build reached CMake, so the Gradle/AGP/Kotlin half is fine. It is the
native half that needs an out-of-band input.

**There is no submodule.** The error message's first suggestion is impossible:

```
$ ls .gitmodules        -> No such file or directory
$ git submodule status  -> (empty)
```

`android/src/main/cpp/CMakeLists.txt` documents resolution route 2 as
"`<repo>/llama.cpp` — a submodule checkout at the repo root" (line 47) and
`git submodule update --init --recursive` as instruction #1 (line 11). Neither
exists. **The comment is wrong; the code is not.** Route 2 is dead text.

### The documented workaround that actually works

`PIDROID_LLAMA_FETCH=1`, on a fresh clone with no other setup:

```
$ PIDROID_LLAMA_FETCH=1 ./gradlew :android:assembleDebug
BUILD SUCCESSFUL in 1m 4s
```

It fetched llama.cpp `b4661` -> `ec3bc8270bc67b58955748d40a3e558a05b2d8f2`
(1.1 GB in `android/.cxx/`) and produced both ABI object trees. **A stranger
can get a working build in one command.** Nothing about the build requires a
local absolute path; `gradle.properties` has no machine-specific value, and
`local.properties` is absent from the clone and is not required (`ANDROID_HOME`
is enough).

`*.so` and `*.bin` are in `.gitignore` and **0** such files are tracked, so
nothing the build needs is being excluded by gitignore.

### With the pinned llama.cpp checkout

```
$ export PIDROID_LLAMA_DIR=/tmp/llama.cpp   # b4661 -> ec3bc82
$ ./gradlew :core:assemble :android:assemble :app:assembleDebug
BUILD SUCCESSFUL in 17s
```

---

## 2. The APK is real and complete

`app/build/outputs/apk/debug/app-debug.apk`, **83,936,581 bytes**.
`unzip -t`: `No errors detected in compressed data`.
`aapt2 dump badging`: package `dev.localintelligence.app`, versionCode 1,
versionName 0.1.0, minSdk 26, targetSdk 36, compileSdk 36. 23 dex files.

### Native libraries, actual sizes (uncompressed, as packaged)

| `.so` | arm64-v8a | x86_64 |
|---|---:|---:|
| `liblitertlm_jni.so` | 14,882,976 | 18,047,160 |
| `libLiteRt.so` | 5,064,136 | 6,997,656 |
| `liblocalintelligence_llama_jni.so` | 5,001,368 | 4,948,504 |
| `libLiteRtClGlAccelerator.so` | 2,778,128 | 3,466,440 |
| `libc++_shared.so` | 1,292,904 | 1,252,080 |
| `libandroidx.graphics.path.so` | 10,096 | 10,760 |
| **one ABI total** | **29,029,608** | **34,722,600** |

- **Loader name is correct.** `LlamaBridge.kt:26` asks for
  `localintelligence_llama_jni`; the packaged library is
  `liblocalintelligence_llama_jni.so`. The CMake `OUTPUT_NAME` pin works.
- **Both required ABIs present for the JNI**: arm64-v8a and x86_64.
  armeabi-v7a and x86 also appear, carrying **only**
  `libandroidx.graphics.path.so` (7,252 and 9,284 bytes) — a transitive
  androidx dependency, not llama.cpp. Full ABI list in the APK:
  `arm64-v8a, armeabi-v7a, x86, x86_64`.
- **No NPU library.** Complete `.so` inventory of the APK:
  `libandroidx.graphics.path.so`, `libc++_shared.so`,
  `libLiteRtClGlAccelerator.so`, `liblitertlm_jni.so`, `libLiteRt.so`,
  `liblocalintelligence_llama_jni.so`. Nothing matching
  `npu|tensor|nnapi|neuraltensors`.
- **The NPU claim in `docs/acceleration.md` is TRUE.** Verified against the
  artifact, not the doc: `litertlm-android-0.13.1.aar` ships exactly
  `libLiteRtClGlAccelerator.so`, `liblitertlm_jni.so`, `libLiteRt.so` for
  arm64-v8a and x86_64 — OpenCL, nothing else. And
  `javap com.google.ai.edge.litertlm.Backend` shows **no** `GOOGLE_TENSOR`.

---

## 3. Pure-core boundary

**`android.*` / `androidx.*` / `dalvik.*` imports in `core/src/main` = 0.**

```
$ grep -rn "import android\|import androidx\|import dalvik" core/src/ | wc -l
0
```

`:core` is genuinely pure JVM. `core/build.gradle.kts` applies
`libs.plugins.kotlin.jvm`, not the Android plugin, and declares no Android
dependency. No finding here.

---

## 4. False claims in comments and docs

Each was checked against the code, and the mechanical ones against the compiled
bytecode or by executing it.

### 4.1 `Session.compact()` is dead code, but three comments name it as the bound

`core/src/main/kotlin/dev/localintelligence/core/agent/Session.kt`

| line | claim | reality |
|---|---|---|
| 12 | "the model never sees more than the bounded window kept here" | never true for the code named |
| 14 | "Everything in this class is bounded on purpose" | `messages` is not bounded by this class |
| 15 | "`messages` is trimmed by `[compact]` to 1 task + 1 summary + `[keepRecent]` turns" | `compact()` is never called |
| 40 | "The list is bounded by the session's own compaction (`[keepRecent]` and `[compact]`), so this cannot grow without limit." | `compact()` is never called |

**Proof it is dead code — bytecode, not grep:**

```
$ git grep -n "compact()" -- '*.kt' | grep -v "compactor.compact\|fun compact"
core/.../AgentController.kt:1129:  * Compaction used to end at `sessions.compact()`,
                                   ^ that one line is a comment explaining it is no longer used

$ javap -p -c -cp core.jar dev.localintelligence.core.agent.AgentController \
    | grep -c "Session.compact"
0
```

**Executed against the real `core.jar`:**

```
seeded messages.size = 401, tokens = 4278 (limit 512)
Session declared fields = [id, keepRecent, messages, workingSummary,
                           SUMMARY_BUDGET_CHARS, LINE_CHARS, KEYWORD_LIMIT]
```

There is no `maxMessages`, no cap, no limit member. The real bound is
`AgentController.compactIfNeeded() -> foldWindow(keep)`, which is a **private
method of a different class** and fires only when `tokens(model) > limit`.
`Session` itself bounds nothing on its own.

To be precise about which side is wrong: **the comments are wrong, the code is
right.** `foldWindow` genuinely bounds the list, and `AgentController.kt:1172`
documents the replacement correctly. `Session.kt` was never updated to match, so
its KDoc describes a function that stopped being the bound.

### 4.2 A comment names a test class that does not exist

`core/src/main/kotlin/dev/localintelligence/core/tool/catalogue/V0ToolCatalogue.kt:72`

> "The budget is asserted in `V0ToolCatalogueConsistencyTest`; adding a tool
> spends from the same pool."

```
$ git grep -ln "V0ToolCatalogueConsistencyTest"
core/src/main/kotlin/dev/localintelligence/core/tool/catalogue/V0ToolCatalogue.kt
```

The only hit is the comment itself. The class does not exist — it was deleted
with the rest of the suite. **The comment is wrong.** A reader is told a budget
is enforced by a build gate; nothing enforces it.

### 4.3 "a measured 738" is 729

`V0ToolCatalogue.kt:97-101`

> "800 against a measured 738 at 26 tools."

Measured by running the real catalogue, using the unit the comment itself
defines ("at 4 chars/token"):

```
tool count                   = 26   (correct)
total description chars      = 2917
descriptions @ 4 chars/token = 729
-> 738 claimed vs 729 measured: MISMATCH (delta -9)
```

Tags check out exactly: `total tags = 193` against a claimed "measured 193", and
per-tool tag counts are 6-8, inside the claimed "4-8 per tool".

**The 9-token gap is small and the direction is benign** (real usage is under
budget), so this is a stale figure rather than a safety problem. Reported for
accuracy, not severity.

### 4.4 `docs/build.md` §2 and `.github/workflows/ci.yml` both deny an `ndkVersion` that exists

`docs/build.md:33-35`:

> "## 2. The NDK is 27.0.12077973, not 27.1.12297006
> `android/build.gradle.kts` declares **no `ndkVersion`**."

`android/build.gradle.kts:22`:

```kotlin
ndkVersion = "27.1.12297006"
```

`.github/workflows/ci.yml:20-33` repeats the same denial and sets
`NDK_VERSION: '27.0.12077973'`.

**What the build actually used, from the generated CMake cache:**

```
$ grep -rho 'ndk/[0-9.]*' android/.cxx/ | sort -u
ndk/27.1.12297006
```

The build compiles against **27.1.12297006**. Both documents assert the
opposite, at length, with a confident "MEASURED" framing.

The "Verify the resolved NDK" step in CI does not catch this, because it
verifies that the wrong version is *installed*, not that it is *used*:

```
$ echo "$RESOLVED" | grep -qx "27.0.12077973"   # installed NDKs: 27.0.12077973 + 27.1.12297006
STEP PASSES
```

The step passes while the build uses the other NDK. **Both documents are wrong;
the build file is right.**

### 4.5 `docs/build.md` §3 and `ci.yml` say `PIDROID_LLAMA_FETCH=1` cannot work. It can.

`docs/build.md` §3:

> "**Route 3 is broken and cannot work as written.** ... CMake fails:
> `add_library cannot create target "llama"`"

`ci.yml:158-166`:

> "CMakeLists.txt has a FetchContent branch (PIDROID_LLAMA_FETCH=1) that CANNOT
> WORK ... That branch has therefore never built anything."

**Run on a fresh clone with nothing else set up:**

```
$ PIDROID_LLAMA_FETCH=1 ./gradlew :android:assembleDebug
BUILD SUCCESSFUL in 1m 4s
```

No `add_library cannot create target` error. The CMakeLists fix for it already
landed — the file carries an explicit comment at the `add_subdirectory` guard
saying the unconditional call "as used to" broke the fetch path and was fixed.

**Both documents are wrong, and the direction matters:** they tell a stranger
that the one route which needs no local setup is broken, and steer them to
manually clone 200 MB. This is the most costly stale comment in the repo,
because it is the one a first-time builder hits first.

---

## 5. Credentials, secrets, personal paths

**None found.** Nothing to report, and nothing fixed.

| scan | result |
|---|---|
| `sk-`, `ghp_`, `AKIA`, `xox*`, `hf_`, PEM private key blocks | 0 |
| `api_key/secret/token/password/bearer = "..."` assignments | 0 |
| `.env`, `.pem`, `.key`, credential files tracked | 0 |
| `/home/<user>/`, `/Users/<user>/`, `C:\Users\` in tracked files | 0 |
| non-public hostnames or private IPs | 0 |

Two hits are illustrative, not real infrastructure:
`HeuristicTokenCounter.kt:43` and `docs/architecture.md:44` both use
`192.168.1.20` as a tokenizer / prompt example.

Hugging Face auth is handled correctly by design: the token is read at runtime
from the **Android Keystore** (`KeystoreTokenStore.kt`, aliases
`localintelligence_hf_token`), sent only as an `Authorization` header, never in
a URL, and the transport strips the header on redirect.

---

## 6. Linter

**No detekt, no ktlint, no spotless** — zero references anywhere in the build
files, `libs.versions.toml`, or the workflow. Kotlin is compiled with `-nowarn`
in effect and no static analysis runs in CI. The only configured analyser is
**Android Lint**, which is not invoked by `ci.yml` at all.

Run manually:

```
$ ./gradlew :app:lintDebug :android:lintDebug
BUILD FAILED
Lint found 3 errors, 19 warnings.
```

### The 3 errors

| location | issue |
|---|---|
| `DeviceTools.kt:1089` | `MissingPermission` — `Vibrator.vibrate` needs `VIBRATE` |
| `DeviceTools.kt:1097` | same, the pre-API-33 branch |
| `FileTools.kt:1580` | `NewApi` — `MediaStore.Downloads.EXTERNAL_CONTENT_URI` is API 29, minSdk is 26 |

On the two `VIBRATE` errors: `VIBRATE` **is** declared, in
`app/src/main/AndroidManifest.xml:15`. The call site is in `:android`, a library
module whose own manifest does not declare it, so lint evaluates it without the
app's manifest. At runtime the merged manifest grants it, so this will not
crash — it is a manifest-merge false positive that fails the build.

`FileTools.kt:1580` is different. Lines 1574-1577 guard the *other* API-29
fields behind `SDK_INT >= Q`, but `EXTERNAL_CONTENT_URI` on line 1580 sits
**outside** that guard, on a module with `minSdk = 26`. It is inside a
`try { } catch (t: Exception)`, so the failure mode is a caught throw rather
than a crash — but on an API 26-28 device `files.write_text` to Downloads
would silently return false. Real, not a false positive.

### Warnings (19)

`AnnotateVersionCheck` (AndroidPlatformGrant.kt:115), `DefaultLocale`
(FileTools.kt:535, x2), `InlinedApi` (FileTools.kt:1439, 1498 — API 29 fields
against minSdk 26), plus the rest in the report.

The report is at
`android/build/intermediates/lint_intermediate_text_report/debug/lintReportDebug/lint-results-debug.txt`.

CI never runs lint, so a red lint has never blocked anything.

---

## 7. Roadmap vs the code

`docs/roadmap.md` is mostly honest — the `[?]` marks are accurate and the
"UNVERIFIED" framing is correct. Three items do not match the code.

### 7.1 "22 Android tools" is 25, and the list is wrong in both directions

`docs/roadmap.md:81,90-102`:

```
[x] 22 Android tools (see below)
22, verified by the `name = "..."` fields in `:android`:
apps.list  apps.open  apps.share
calendar.create  calendar.search
clipboard.read  clipboard.write
contacts.get  contacts.search
device.battery  device.info  device.vibrate
files.delete  files.list  files.search
notifications.dismiss  notifications.list  notifications.reply
alarm.cancel  alarm.create  alarm.list
web.fetch
```

Counted by `name = "..."` in `:android`, per family file:

```
3  alarm  3  apps  2  calendar  2  clipboard  2  contacts
4  device  5  files  3  notifications  1  web   = 25
```

Diffed against the roadmap's list of 22:

- **Shipped but missing from the roadmap list (3):**
  `device.open_settings`, `files.read_text`, `files.write_text`
- **In the roadmap but not shipped: none.**

So the list is simultaneously missing 3 real tools and the headline number is
22 instead of 25. **The roadmap is wrong.** The v0 "15+" threshold is still met
with room to spare, so nothing downstream is at risk.

### 7.2 `calendar.delete` is catalogued but has no implementation

`V0ToolCatalogue` has **26** definitions; `:android` ships **25**.

```
in CATALOGUE but NOT shipped: ['calendar.delete']
SHIPPED but NOT in catalogue:  []
```

`CalendarTools.kt:945-951` returns exactly two tools:

```kotlin
fun calendarTools(context: Context, grant: PlatformGrant): List<AgentTool> {
    return listOf(
        CalendarSearchTool(...),
        CalendarCreateTool(...),
    )
}
```

`V0ToolCatalogue.kt:698` defines `calendar.delete`. Nothing implements it.

This is more interesting than a count error, because
`AndroidTools.kt:requireCatalogueAgreement()` throws on a tool that ships
without a catalogue entry — the check runs in the *other* direction, so a
catalogue entry with no tool passes silently. The safety net has a hole in the
direction that lets a tool the model is told exists turn out not to.

The `V0ToolCatalogue` KDoc that says the budget is test-asserted (§4.2) is on
this same object, which suggests the consistency test that would have caught
this is the one that was deleted.

### 7.3 Everything marked `[x]` in Wave 1 and Wave 2 is genuinely present

Checked by locating the named types: `ModelBackend`, `AgentTool`,
`AgentAction`, `ToolRisk`, `AgentController`, `ActionParserImpl`/`GrammarBuilder`,
`DefaultContextBuilder`/`ContextCompactor`, `LlamaBridge`, `LiteRtLmEngine`,
Room `PiDroidDatabase`, `ChatScreen`, `TraceView`/`TraceScreen`,
`ModelManagerScreen`, `RiskPolicy`, `LoopDetector` (wired: `loopDetector.check`
appears in `AgentController`). All present. The "nine tool families" claim is
correct — 9 directories, 9 `addAll` calls in `androidTools()`.

### 7.4 The screen-honesty agent's `[?]` marking is correct

All 14 `[?]` v0-freeze items are implemented-but-unverified, exactly as marked.
The last three (`>=80%` eval, `>=65%` multi-step, `no task needs >8K context`)
correctly have no instrument at all. The `[?]` sweep is accurate; I have no
correction to add.

### 7.5 The empty-registry bug is genuinely fixed

`AppContainer.kt:61-77` documents that it used to be
`SimpleToolRegistry(emptyList())` and now is:

```kotlin
val tools: ToolRegistry by lazy { SimpleToolRegistry(androidTools(context)) }
```

Wired through at `AppContainer.kt:350` and `:352`. That claim holds.

---

## Summary of what survives and what does not

**True:** the core boundary is clean (0 android imports); the APK is complete
and installable with both JNI ABIs and the correct lowercase soname; the loader
name matches; there is genuinely no NPU library and LiteRT-LM really does ship
no `GOOGLE_TENSOR`; there are no credentials or personal paths anywhere; the
empty-tool-registry bug is really fixed; all 22 `[?]` roadmap marks are honest;
and Wave 1 / Wave 2 `[x]` items are really there.

**False:** five comment and doc claims (§4.1-4.5), three roadmap items (§7.1,
§7.2, and the count behind them), and lint is both failing and not run in CI
(§6). A stranger needs one specific env var to build, and the repository's own
documentation tells them the route that supplies it is broken when it is not.

The pattern is the same one this project has hit before: **the code moved on and
a comment did not.** §4.1 is a bound that moved from one class to another;
§4.5 is a fix that landed in CMakeLists while two documents still describe the
bug it fixed; §4.2 is a test that was deleted while a comment kept its name.
