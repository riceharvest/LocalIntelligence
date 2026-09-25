# Security and Privacy Audit — LocalIntelligence

**Scope:** the agent harness that runs a GGUF model on-device and acts on the user's
contacts, calendar, notifications, files and clipboard.
**Date:** 2026-09-25 · **Baseline:** `origin/main` @ `c9c894c`
**Method:** read every file of the shipping source tree; every finding below cites
`file:line`. No finding is from a generic checklist.

## How this audit was run, and what that means for reading it

`main` moved while this audit was in progress: the tool workstreams were merged
into the integration line, and by the end `origin/main` carried 7 of the 9 tool
implementations (`web`, `files`, `notifications`, `device`, `apps`, `alarm`,
`clipboard`). `contacts` and `calendar` were still on `feat/tools-calendar` and
had **not** landed.

So I audited a merged tree (`main` + all `feat/tools-*` + `feat/chat-ui` +
`feat/evals-android`) rather than `main` alone — auditing `main` at the moment I
started would have meant auditing an app with no tools in it and reporting
nothing. I then **re-verified every critical and high finding directly against the
final `origin/main`**; all of them reproduce there, unchanged, at the line numbers
cited below.

Line numbers are identical between the merged tree and `origin/main` for every
file cited in C1–C2 and H1–H4, because those files are byte-identical. Findings
marked **[tool branch]** are in `contacts`/`calendar` only, which are merged but
not yet on `main`.

## Severity summary

| Severity | Count | IDs |
|---|---|---|
| Critical | 2 | C1, C2 |
| High | 4 | H1, H2, H3, H4 |
| Medium | 6 | M1–M6 |
| Low | 4 | L1–L4 |

---

## CRITICAL

### C1 — `web.fetch` has no SSRF protection: the model can read the local network and cloud metadata

**`android/.../tools/web/WebTools.kt:287-301` (tool branch)**

The host filter is:

```kotlin
if (!host.contains('.') && host != "localhost") { ... reject ... }
if (host.endsWith(".local") || host.endsWith(".internal")) { ... reject ... }
```

This rejects single-label hostnames and mDNS names. It does **not** reject IP
literals. Every private and link-local address contains dots, so every one of them
passes:

| URL | host | verdict |
|---|---|---|
| `http://127.0.0.1:8080/admin` | `127.0.0.1` | **ACCEPTED** |
| `http://169.254.169.254/latest/meta-data/` | `169.254.169.254` | **ACCEPTED** |
| `http://10.0.0.1/` | `10.0.0.1` | **ACCEPTED** |
| `http://192.168.1.1/admin` | `192.168.1.1` | **ACCEPTED** |
| `http://localhost:3000/health` | `localhost` | **ACCEPTED** (deliberate, see below) |

I extracted the exact predicate from `WebTools.kt:284-301` and ran the seven cases
above rather than eyeballing them. The KDoc at `WebTools.kt:287-290` states the
intent plainly — *"It is also the shape an SSRF attempt takes when it is probing
the local network"* — and the code does not deliver that.

**Why this is critical, not theoretical.** `web.fetch` is the only tool that turns
a string from a 1B model into a socket, and the model is not a trusted
principal — it is a small, prompt-injectable component whose output is attacker-
influenced whenever a fetched page says "fetch this URL next". Consequences:

- **Cloud metadata.** `169.254.169.254` is the AWS/GCP/Azure instance metadata
  service. A fetched page can steer the agent at it and read instance credentials
  and IAM role tokens into the model context, which then appears in the
  transcript and the Room database (`data/Entities.kt:57`).
- **LAN pivot.** `10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16` are reachable.
  Routers, NAS boxes, printers and Chromecast-style devices frequently have
  unauthenticated admin endpoints. This turns a summariser into a network scanner
  that reports back to the user (or to whoever is prompting).
- **Loopback.** Anything the device itself serves — a debug WebView, a local
  developer server, an unauthenticated local API — is fetchable.

The 256 KB cap (`WebTools.kt:59`) and 8s/10s timeouts (`WebTools.kt:67-68`) bound
the damage per call but do nothing to stop the request being made.

**Note on the existing test.** `android/src/test/.../WebToolsTest.kt:177-190`
lists `http://192.168.1.1/` (line 183) in a test named *"a local-network host is
refused"* — but the assertion is conditional (`if (url.endsWith(".local") || ...)`,
line 185), so the IP literal is silently never asserted. The test reads as coverage
that does not exist. That is how this survived.

**Fix.** Resolve-then-check, not string-matching: after DNS resolution, reject any
address in loopback / link-local / RFC1918 / CGNAT / ULA, and reject the literal
forms of those ranges in `validate()` so they never reach the resolver. Keep
`localhost` as an explicit, tested opt-in flag rather than an unconditional
allow — a dev server is a real case, but it is not a case a 1B model should be
able to select on its own. Add the unconditional assertions to the existing test
so the IP literals are actually covered.

### C2 — `ExecutionService` and the notification listener are not declared in any manifest; the agent cannot run and the notification tools can never work

**`app/src/main/AndroidManifest.xml:33-58` · `app/.../ExecutionService.kt:78` · `android/.../tools/notifications/NotificationTools.kt:283`**

`app/src/main/AndroidManifest.xml` declares exactly one component: `MainActivity`
(line 41). The library manifest `android/src/main/AndroidManifest.xml` is an empty
`<manifest/>` (2 lines, no children).

Consequences, all verified:

1. **`ExecutionService` is not registered.** `ExecutionService.kt:78` declares
   `class ExecutionService : Service()`, and `ServiceAgentGateway.start` (line 311)
   calls `ContextCompat.startForegroundService` against it. An undeclared service
   cannot be started — this throws at runtime. **The agent's entire execution
   path is dead on the shipped manifest.** Every tool, the whole run loop, the
   confirmation flow: none of it is reachable.
2. **The required foreground-service permissions are absent.** There is no
   `FOREGROUND_SERVICE` or `FOREGROUND_SERVICE_DATA_SYNC` permission anywhere in
   either manifest (grep: zero hits). `startTaskForeground`
   (`ExecutionService.kt:186-191`) passes
   `FOREGROUND_SERVICE_TYPE_DATA_SYNC`, which requires both.
3. **`LocalNotificationListenerService` is not registered.**
   `NotificationTools.kt:283` declares `class LocalNotificationListenerService :
   NotificationListenerService()`, and it needs
   `android:permission="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE"`
   plus an `<intent-filter>` for
   `android.service.notification.NotificationListenerService`. None of that
   exists, so the system will never bind it. `LocalNotificationListenerService.isConnected()`
   (checked at `NotificationTools.kt:529, 719, 908`) is therefore permanently
   false, and all three notification tools return
   `ToolError.Unavailable("NotificationListenerService is not bound")` forever.
   The permission is also absent from the manifest, and note that
   `NotificationTools.kt:515, 694, 883` declare
   `requiredPermission = "BIND_NOTIFICATION_LISTENER_SERVICE"` — a signature
   permission the app can never hold, because holding it is what the *system* does
   when the user grants listener access.

**Why critical.** Notification access is the highest-sensitivity capability this
app holds — it reads message bodies from every app on the device. Shipping a
manifest that neither registers the service nor the listener means either the app
is non-functional or, once someone "fixes" it by loosening the manifest, they do it
without knowing what they are turning on. And finding 2 is the dangerous
direction: the natural fix is to add the service and flip on backup/permissions
around it. The manifest is the one file where a security mistake is unrecoverable
in the field, because the user's grant happens before the app has shown anything.

**Fix.** In `app/src/main/AndroidManifest.xml`, add `FOREGROUND_SERVICE` and
`FOREGROUND_SERVICE_DATA_SYNC`; declare `<service android:name=".ExecutionService"
android:exported="false" android:foregroundServiceType="dataSync"/>`; declare
`LocalNotificationListenerService` with
`android:permission="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE"`,
`android:exported="true"` (required for listener binding — the permission is what
gates it) and the
`android.service.notification.NotificationListenerService` intent filter. Then
re-run the audit; the two components are the app's whole attack surface.

---

## HIGH

### H1 — `android:allowBackup="true"` ships the full conversation database to the cloud

**`app/src/main/AndroidManifest.xml:35`**

```xml
android:allowBackup="true"
```

There is no `android:dataExtractionRules` and no `android:fullBackupContent` (grep:
zero hits for both). The default therefore applies: Auto Backup copies the app's
files — including `localintelligence.db` (`data/PiDroidDatabase.kt:38`) — to the
user's Google account, and on Android 12+ `adb backup` / device-to-device
transfer carries it too.

**Why it matters more here than in a normal app.** `messages.text` and
`messages.observation` (`data/Entities.kt:57,60`) hold the *tool output* — contact
names and phone numbers, calendar event titles and attendee lists, notification
message bodies, file contents, clipboard contents. `memories.text`
(`data/Entities.kt:76`) is the distilled durable version. So "backup" here means
"a plaintext export of everything the agent ever read off the device, uploaded
off-device, indefinitely, outside any control the user can see or revoke." The
permission grant that put that data in the database did not imply consent to
upload it.

**Fix.** `android:allowBackup="false"`. If transcripts should be backed up, opt in
narrowly with an `android:dataExtractionRules` file that excludes the database,
and say so in the README. Do not ship the default here.

### H2 — The Room database is unencrypted and holds raw PII indefinitely

**`android/src/main/kotlin/dev/localintelligence/android/data/PiDroidDatabase.kt:49-51`**

```kotlin
Room.databaseBuilder(context.applicationContext, LocalIntelligenceDatabase::class.java, name)
    .addCallback(MemoryFtsCallback())
    .build()
```

Plain `Room`, no SQLCipher, no `SupportFactory`, no passphrase. The file lives in
the app's private data directory, so it is protected only by the Linux per-app
sandbox and by full-disk encryption when the user has it on.

**Why it matters.** This is a deliberate, correct trade for a local-first app
*provided the threat model says so out loud* — and right now it says nothing. The
combination with H1 is the problem: unencrypted local storage **plus** automatic
cloud backup means the data is readable both on a rooted device and in Google's
backups. And there is no retention limit anywhere: `MemoryStore`
(`core/.../agent/MemoryStore.kt:36-60`) has `remember`/`forget`/`all` but no
eviction, no TTL and no size cap, and `memories` grows monotonically. A user's
contact names and message text accumulate in perpetuity.

**Fix.** Document the threat model explicitly in the README ("your data is
unencrypted at rest in app-private storage; we do not encrypt because there is no
key to protect on a device whose threat model is a lost phone"). Add a bounded
retention policy to `MemoryStore` (cap on row count and/or age) and a
"delete everything" action wired to the existing `clear()`. Consider
`EncryptedSharedPreferences`/SQLCipher as a follow-up if the threat model
includes a lost or stolen device.

### H3 — The SEND intent filter is an unauthenticated input channel into the agent loop, with no confirmation

**`app/src/main/AndroidManifest.xml:51-55` · `app/.../MainActivity.kt:71-73, 114-122`**

```xml
<intent-filter>
    <action android:name="android.intent.action.SEND" />
    <category android:name="android.intent.category.DEFAULT" />
    <data android:mimeType="*/*" />
</intent-filter>
```

`MainActivity` is `android:exported="true"` (line 43) — correctly, it has a
LAUNCHER filter — and this filter means **any app on the device can send this app
an intent with arbitrary text**, which `MainActivity.kt:72` feeds straight into
the agent:

```kotlin
LaunchedEffect(Unit) {
    sharedText(intent)?.let { chat.send(it) }
}
```

`sharedText` (line 114-122) accepts `ACTION_SEND` text and `ACTION_PROCESS_TEXT`,
with no size cap and no origin check.

**Why it is a security problem, not a UX one.** This is an unauthenticated,
un-rate-limited command channel into an agent that holds contacts, calendar,
notifications and file write access. A malicious or merely compromised app can
issue tasks without the user ever seeing a share sheet. The failure mode is
compounded by prompt injection: a web page the user shares, or a text file, becomes
agent instructions. If any tool path can be steered to a write, this is remote
instruction-injection with no human in the loop.

The `*/*` MIME wildcard is the sharp end of it — a share from *any* content type
lands here, not just text.

**Fix.** (a) Do not auto-send on receipt. Route the shared payload into a
**pre-filled, unexecuted** composer that the user must press send on — this keeps
the feature and closes the channel. (b) Cap `EXTRA_TEXT` length. (c) Narrow the
MIME type to `text/*` unless file sharing is genuinely required. (d) Treat
imported text as untrusted: it should be clearly delimited in the prompt so a
model reads it as data, not as instructions.

### H4 — `AppContainer` registers zero tools, so the entire tool surface is dead code at runtime

**`app/src/main/kotlin/dev/localintelligence/app/AppContainer.kt:116`**

```kotlin
private fun androidTools(): List<...AgentTool> = emptyList()
```

with the KDoc at lines 108-116 stating the tool workstreams "have not landed on
this branch."

**Why this is in a security audit.** This is a privacy control that is
*unintentional*. As shipped, the app has an agent loop and no tools — the
strongest possible data-minimisation posture, achieved by accident. The moment
someone wires `androidTools()` to the real tools, every finding in this document
becomes live at once, including the H1 backup of the resulting data.

It also means the risk/confirmation and permission work owned by the other
parallel agents has nothing to attach to yet, so none of it can be validated
end-to-end. That is a sequencing risk the parent should know about.

**Fix.** Not mine to make — it is the integration point. But it should be treated
as a **release gate**: the day `androidTools()` stops returning `emptyList()`, this
audit's findings must be re-verified, C1 in particular.

---

## MEDIUM

### M1 — `NotificationTools` declares a permission signature the app can never hold

**`android/.../tools/notifications/NotificationTools.kt:515, 694, 883`**

All three notification tools declare
`requiredPermission = "BIND_NOTIFICATION_LISTENER_SERVICE (user grant in Settings)"`.
That is a `signature`-level permission held by the *system*, never by an app. As
a human-readable hint in the tool definition it is fine; if any broker compares
this string against the app's own granted permission set, it will never match and
either always-deny or always-allow depending on the comparison's direction. Worth
reconciling with the permission-broker owner so the two agree on what this field
means.

### M2 — `RECEIVE_BOOT_COMPLETED` is declared with no receiver to use it

**`app/src/main/AndroidManifest.xml:14`**

Declared, but no `<receiver>` exists in either manifest and no `BroadcastReceiver`
subclass exists in the tree. `AlarmTools.kt:866` schedules via
`setExactAndAllowWhileIdle`, which needs `RECEIVE_BOOT_COMPLETED` to re-register
after reboot — so the permission is aspirational rather than wired. A dangerous
permission with no caller is exactly the kind of thing a store reviewer flags.
Either add the receiver or drop the permission.

### M3 — `SCHEDULE_EXACT_ALARM` is a special-access permission with no user-facing rationale

**`app/src/main/AndroidManifest.xml:13` · `android/.../tools/alarm/AlarmTools.kt:618-625`**

Correctly gated at runtime by `canScheduleExactAlarms()` (`AlarmTools.kt:620`),
which is the right pattern. But `SCHEDULE_EXACT_ALARM` is Play-policy-restricted
and triggers a Settings redirect. Since the alarm tool is one of several and the
permission is app-wide, a user who never touches alarms still sees the prompt.
Consider `USE_EXACT_ALARM` (the narrower, self-certifying grant) or requesting
access lazily on first alarm use.

### M4 — Media permissions are broader than the file tools need

**`app/src/main/AndroidManifest.xml:18-20`**

`READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO` and `READ_MEDIA_AUDIO` are all declared.
`FileTools` is built on the Storage Access Framework and `content://` URIs
throughout (`FileTools.kt:277`, `WritePlanner.plan` at line 797-829), which needs
**no** media permission at all — SAF grants are per-document. A user picking a
folder grants it without a permission dialog. So on the code as written these
three are broader than necessary, and they are exactly the permissions that alarm
users on a contacts/calendar-reading app. `files.search` over shared media is the
only plausible consumer, and that can be done through SAF too. Drop all three
unless a specific caller appears.

### M5 — `files.write_text` creates files in the shared Downloads collection

**`android/.../tools/files/FileTools.kt:822, 1545`**

`WritePlanner.plan` returns `WritePlan.CreateInDownloads(...)` and the write goes
to `MediaStore.Downloads.EXTERNAL_CONTENT_URI`. Content written by the model —
which may be a summary of the user's contacts or notifications — lands in the
user's Downloads folder, visible to every other app with media access and backed
up by whatever syncs that folder. This is data crossing an app boundary. It is the
documented behaviour and the tool is presumably marked risky, but it deserves an
explicit callout: **model output derived from private device data can be written
to shared storage.** Consider writing agent artefacts to app-private storage by
default and requiring an explicit user choice to export.

### M6 — `device.info` returns a device fingerprint to the model

**`android/.../tools/device/DeviceTools.kt:1021-1024`**

`manufacturer`, `model`, `androidRelease`, `sdkInt` (plus storage stats at
line 1018). This is coarse hardware metadata, not a unique identifier — no
`ANDROID_ID`, no serial, no IMEI (verified: zero hits for those across the tree).
It is low-risk on its own, but combined with H3 (unauthenticated SEND input) a
prompt-injected agent can be asked to report a device profile into a page it
fetches. Recommend it be treated as `READ_ONLY` and excluded from any
externally-visible output.

---

## LOW

### L1 — `to_jstring` returns `nullptr` for empty strings, conflating "empty" with "OOM"

**`android/src/main/cpp/llama_jni.cpp:35-40`**

`to_jstring` returns `nullptr` when the input is empty. Every caller treats
`nullptr` as an error, so an empty error message becomes a generic failure. Not
security-relevant; a correctness wart on the JNI boundary. The JNI string handling
around it is otherwise correct: `GetStringUTFChars`/`ReleaseStringUTFChars` are
properly paired at lines 292-297, 423-428 and 517-522, with null checks before
each use.

### L2 — `split_utf8` indexes `buf[i-1]` after decrementing past zero

**`android/src/main/cpp/llama_jni.cpp:113-114`**

```cpp
i--;
if (buf.size() - (i - 1) > 4) { break; }
```

`i` is `size_t`; the decrement at line 113 can reach 0, making `i - 1` wrap to
`SIZE_MAX`. The comparison then underflows and the guard misfires. In practice the
`i > 0` loop condition at line 92 catches it on the next iteration, so this is not
currently reachable — but it is a latent out-of-bounds read that a future edit
could make reachable. Use a signed index type.

### L3 — `to_jstring` is used for a `const char*` return without a length bound

**`android/src/main/cpp/llama_jni.cpp:277-279`**

`nativeSystemInfo` wraps `llama_print_system_info()`'s raw `const char*` in a
`std::string` assuming NUL termination. That is the documented contract of the
llama.cpp function, so it is fine, but it is an unchecked assumption about a
third-party pointer.

### L4 — Eviction token sink leaks a local reference on the error path

**`android/src/main/cpp/llama_jni.cpp:538-544`**

If `sink.resolve` fails after `NewGlobalRef` has run but `GetMethodID` returned
null, the early return at line 543 does not call `sink.release(env)`. The global
ref is leaked. Bounded (one per failed generation) and not a memory-safety issue —
worth noting for completeness, not worth a rush.

---

## Areas checked and genuinely SAFE

These were audited, not assumed. Saying so is as important as the findings.

**Logcat / logging leakage: clean, and this is a real strength.**
Exhaustive grep for `Log.`, `android.util.Log`, `println`, `printStackTrace`,
`Timber.`, `System.out`, `System.err` across `core/src`, `android/src`, `app/src`
(non-test) returns **zero hits**. Not "no PII in logs" — *no logging calls at
all*. The classic Android privacy bug, where contact names and message bodies
land in logcat where any app with `READ_LOGS` (or an ADB session) can read them,
is structurally absent. Exception handling uses exception *class names* rather
than stack traces in model-facing strings (`WebTools.kt:925`, `906`), so no file
paths or internals leak through error text either.

**Network egress: exactly one path, and it is the one documented.**
The only outbound socket in the entire codebase is
`HttpUrlConnectionOpener.open` (`WebTools.kt:976-1024`). Grep for `okhttp`,
`Retrofit`, `WebView`, `Socket`, `DatagramSocket`, `Crashlytics`, `Firebase`,
`Sentry`, `Bugsnag`, `analytics`, `telemetry` across all source: **zero hits**
outside `WebTools.kt`. **No analytics, no crash reporting, no telemetry, no
WebView.** For an app whose entire premise is handling contact and message data,
this is the most important thing in the document, and it is clean. The one
finding in this area is C1 — the destination is uncontrolled — not the existence
of the egress.

**Outbound request hygiene on the fetch path: good.**
`instanceFollowRedirects = false` (`WebTools.kt:979`) so redirects are validated
by us rather than silently by the stack. No cookies, no auth, no referer
(`WebTools.kt:983-987`). Hard caps on body (256 KB, line 59), URL length (2048,
line 74), redirects (3, line 71) and connect/read timeouts (8s/10s, lines 67-68).
The scheme allow-list is a genuine allow-list of `http`/`https` with no
deny-list to rot (`WebTools.kt:160-174`), and embedded-credential URLs are
refused (`WebTools.kt:263-271`).

**Response sanitisation: correct and carefully ordered.**
`HtmlText.toPlainText` (`WebTools.kt:546-565`) strips `<script>`/`<style>` bodies
(not just tags), and decodes entities **after** tag removal so `&lt;script&gt;`
cannot be re-interpreted as markup. Control and bidi-override characters are
stripped (`CONTROL` regex, line 544). The body cap is enforced on the raw socket
bytes before any decoding. This is better than most production scrapers.

**GGUF parsing: bounds-checked, and genuinely careful.**
`GgufMetadata.kt` is the strongest-written file in the repo for hostile input.
Every length field from the file is checked against remaining bytes *before* the
read (`readBytes` line 451, `skipFully` line 482, `string` line 549) and against
explicit sanity caps (`MAX_STRING_BYTES` 1 MiB line 72, `MAX_ARRAY_COUNT` 2^26
line 69, `MAX_KV_COUNT` 2^20 line 77, `MAX_DEPTH` 8 line 74, `nDims` 1..8 line
361). Integer overflow in the array span computation is checked explicitly
(`count != 0L && span / count != width`, line 603 and 664). The version is
refused in both directions rather than guessed (lines 244-255). The tokenizer
array that would otherwise pull 128k strings onto the heap is skipped by size
arithmetic without ever being read (`skipValue`, line 627) — and `ModelImporter`
does pass the real declared length (`ModelImporter.kt:47, 63`), which is what
turns a corrupt length field into a clean error instead of a huge allocation.
A hostile or truncated GGUF produces `GgufFormatException`, never
`IndexOutOfBoundsException` and never an unbounded allocation.

**JNI lifecycle: correct.**
`nativeDestroy` (`llama_jni.cpp:255-273`) sets the cancel flag *then* takes
`h->state`, so an in-flight decode unwinds before the frees — the comment at
lines 260-262 is accurate and the ordering is right. `nativeGenerate` snapshots
the context/vocab pointers under the lock and releases it before decoding
(lines 498-515), so a long decode never blocks `cancel()` from the UI thread.
Token sink global refs are released on every exit path through the `publish()`
funnel (lines 551-556). The tokenizer sink uses a global ref precisely so a
local-frame GC cannot take the listener (line 131).

**File tool path handling: no filesystem traversal, by construction.**
`files.*` operates exclusively on `content://` URIs. `WritePlanner.sanitiseFileName`
(`FileTools.kt:840-847`) strips directory components from model-supplied names and
rejects `.`/`..`; `WritePlanner.plan` (line 797-807) refuses any non-`content://`
target outright with "This app has no filesystem access". `DeleteGuard`
(`FileTools.kt:867-938`) refuses directory/tree URIs, refuses any glob
metacharacter, and **refuses a multi-match search rather than picking one** — the
correct call, since taking the first of several matches is how the wrong document
gets deleted. There is no `canonicalFile`/`resolve()` traversal surface to audit
because raw filesystem paths never reach the model.

**No location, SMS, call log, camera, microphone, or device-identifier access.**
Grep for `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`, `LocationManager`,
`getLastKnownLocation`, `READ_CALL_LOG`, `READ_SMS`, `RECORD_AUDIO`, `CAMERA`,
`ANDROID_ID`, `getAccounts`, serial/IMEI across all source: the only hit is a
*string literal in a test fixture* (`core/src/test/.../ScriptedTool.kt:254`, a
mock `location.read` tool used by the eval suite). **None of these permissions is
declared and none of these APIs is called in production code.** The absence of
location is a strong privacy property for this product and should stay that way.

**Exported components: only the launcher activity.**
`<activity android:name=".MainActivity" android:exported="true">`
(`app/src/main/AndroidManifest.xml:41-43`) is correct and required for a LAUNCHER
filter. There are **no providers, no receivers, and — as C2 covers — no services**
declared. No `FileProvider` is configured, and `AppTools.kt:53-60, 321, 348`
correctly documents that and defaults to refusing `file://` URIs rather than
shipping an unconfigured intent path. No `android:debuggable`, no
`setWebContentsDebuggingEnabled`.

**Dependency posture: minimal, and nothing unexpected.**
Total third-party surface across all three modules: `kotlinx-coroutines`,
`kotlinx-serialization-json`, `androidx.core-ktx`, `androidx.room` (+ KSP
compiler), `androidx.activity-compose`, `androidx.lifecycle-*`,
`androidx.navigation-compose`, Compose (via BOM), JUnit, Espresso. **No analytics
SDK, no crash reporter, no ad SDK, no HTTP client, no JSON-with-reflect library, no
native dependency other than llama.cpp built from source.** `settings.gradle.kts`
pins repositories to `google()`/`mavenCentral()` and sets
`repositoriesMode = FAIL_ON_PROJECT_REPOS`. The manifest comment's claim that
`HttpURLConnection` was chosen to avoid adding OkHttp (`WebTools.kt:963-968`) is
accurate. `local.properties` is gitignored and untracked (verified). This is a
genuinely clean dependency graph.

**Model context is not written to any external artifact.**
No `File`/`FileWriter`/`writeText` outside the legitimate `content://` write paths
and the `ByteArrayOutputStream` in the web body reader. The eval suite
(`core/src/test/.../eval/`) renders reports to **stdout only** via `println`
(`AndroidEvalMain.kt:29-49`) — no file is written, and `evals/results/` is
gitignored. Fake/scripted backends are used for the non-Android evals
(`FakeModelBackend.kt`), so no real user data can enter a report. No crash
reporting SDK exists to receive it (see Network egress above).

---

## What I deliberately did NOT change

Three other agents own `:core` logic, tool implementations, the permission broker
and the risk policy right now. Findings C1, C2, H1, H2 and H3 all have fixes that
land in those files, so they are written up here for the parent to schedule
rather than applied. In particular I did **not**:

- add the missing manifest entries (C2) — `AndroidManifest.xml` is contested and
  the fix is three components plus two permissions that must be reviewed together
  with the permission broker;
- add an IP-literal check to `WebUrls.validate` (C1) — `WebTools.kt` is a tool
  implementation another agent owns, and the fix is a DNS-resolution step with real
  design choices (including what to do about the legitimate `localhost` case);
- change `android:allowBackup` (H1) or add encryption (H2) — the database is
  another workstream's, and the at-rest decision belongs in the README alongside
  the threat model, not in a drive-by patch;
- add a confirmation prompt or permission check (H3) — that is explicitly the
  broker's and the risk-policy agent's work;
- register the tools in `AppContainer` (H4) — integration point, not an audit fix.

**No dependencies were added.** No file outside `docs/security-audit.md` was
modified. No existing test was edited — including the misleading SSRF test
(`WebToolsTest.kt:177-190`), which the `WebTools` owner should fix alongside C1.

**A note for whoever fixes C1:** the existing test named *"a local-network host is
refused"* already lists `http://192.168.1.1/`, but its assertion is wrapped in a
conditional that skips IP literals. Adding the guard is not enough — the
assertion needs to become unconditional, or the same hole reopens silently.
