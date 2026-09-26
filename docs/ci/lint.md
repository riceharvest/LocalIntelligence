# Android Lint

Lint had never run in CI. It does not currently pass. This is the honest
account of that, and the exact edits that would clear it.

## The situation, measured

```sh
./gradlew :android:lintDebug     # BUILD FAILED
./gradlew :app:lintDebug         # BUILD SUCCESSFUL
```

| Module | Result |
| --- | --- |
| `:android` | **3 errors**, 19 warnings |
| `:app` | 0 errors, 21 warnings, 2 hints |

All three errors are in `:android`. `:app` is clean today.

## The three errors

### 1 and 2. `DeviceTools.kt:1089` and `DeviceTools.kt:1097` — `MissingPermission`

```
Error: Missing permissions required by Vibrator.vibrate: android.permission.VIBRATE
```

Both are the two `Vibrator.vibrate(...)` overloads in `vibrate(durationMs: Int)`.

**This is a false positive**, and it is worth being precise about why.
`android.permission.VIBRATE` *is* declared — in
`app/src/main/AndroidManifest.xml:15`. It is declared in the **application**
module, while the call site lives in the **`:android` library** module, whose own
manifest (`android/src/main/AndroidManifest.xml`) is a single empty `<manifest>`
element. Lint analyses `:android` in isolation, sees no `VIBRATE` permission
anywhere in that module, and reports it.

The permission is genuinely present in the shipped artifact. Verified against
the merged manifest that AGP actually produces:

```sh
grep -c VIBRATE app/build/intermediates/merged_manifest/debug/processDebugMainManifest/AndroidManifest.xml
# -> 1
```

So the app will not crash. The build failure is an artifact of analysing a
library module without the application's manifest.

**What would clear it.** Two options, and the first is the better one.

**(a) Declare the permission in the library manifest.** This is the correct
fix and it is a one-line change to a file that is not owned by this branch:

```xml
<!-- android/src/main/AndroidManifest.xml -->
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.VIBRATE" />
</manifest>
```

A library that calls `Vibrator.vibrate` should declare the permission it needs;
the app-level declaration then becomes redundant rather than load-bearing. This
makes the manifest honest at the level of the module that actually needs it, and
it fixes the lint error rather than hiding it.

**(b) Suppress at the call site.** Works, and is worse — it documents the
false positive instead of the real dependency:

```kotlin
// DeviceTools.kt, on the vibrate() function
@Suppress("MissingPermission")
override fun vibrate(durationMs: Int) { ... }
```

Option (a) is recommended. Option (b) is acceptable if the manifest is
genuinely owned elsewhere.

### 3. `FileTools.kt:1580` — `NewApi` — **THIS ONE IS REAL**

```
Error: Field requires API level 29 (current min is 26):
       android.provider.MediaStore.Downloads#EXTERNAL_CONTENT_URI
```

**This is not a false positive. It is a genuine bug that would fail silently on
every device running API 26, 27 or 28.**

`MediaStore.Downloads.EXTERNAL_CONTENT_URI` was added in API 29 (Android 10).
`minSdk` is 26. The field is a static field on a class that does not exist
before API 29, so reading it on an older device throws `NoSuchFieldError` — or,
more precisely, resolving the class throws — at the moment `createInDownloads`
runs.

The damage is made worse by the local error handling. The call is:

```kotlin
// FileTools.kt:1579-1583
val uri = try {
    resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
} catch (t: Exception) {
    null
} ?: return false
```

`NoSuchFieldError` is an `Error`, not an `Exception` — verified, not assumed:

```
$ jshell
jshell> Exception.class.isAssignableFrom(NoSuchFieldError.class)
false
jshell> NoSuchFieldError.class.getSuperclass()
class java.lang.IncompatibleClassChangeError
```

`catch (t: Exception)` does **not** catch it. The `try` block does not make
this safe; it makes it *look* safe. The function would throw, not return `false`.

And the guard that protects the lines directly above it does not cover this one.
`MediaStore.Downloads.RELATIVE_PATH` and `.IS_PENDING` are both API 29 and both
sit inside a `SDK_INT >= Q` check (FileTools.kt:1574-1577).
`EXTERNAL_CONTENT_URI` on line 1580 sits *outside* it. The author guarded the two
fields they were thinking about and missed the third in the same statement
group. Line 1600 shows the same author guarding `IS_PENDING` correctly on the way
out, so the omission is a slip rather than a misunderstanding.

**Why lint flags this line and not the ones above it** is worth stating, because
it is the reason the bug is easy to miss on a read-through. `DISPLAY_NAME` and
`MIME_TYPE` (lines 1572-1573) are `String` *constants*, which the Kotlin compiler
copies into the class file, so referencing them on an old device is harmless —
lint classifies those as `InlinedApi` warnings. `EXTERNAL_CONTENT_URI` is a
`static final Uri` object reference, which cannot be inlined and requires a real
field lookup at runtime:

```
$ javap -cp $ANDROID_HOME/platforms/android-36/android.jar -constants \
    'android.provider.MediaStore$Downloads'
  public static final android.net.Uri EXTERNAL_CONTENT_URI;
```

That is the distinction between a warning and a crash, and it is invisible
without knowing it.

**What would clear it.** Move the whole `MediaStore.Downloads` reference behind
the version check, so the API-29 class is never loaded on an older device. The
minimal correct edit is to guard the insert itself:

```kotlin
private fun createInDownloads(context: Context, plan: WritePlan.CreateInDownloads, content: String): Boolean {
    // MediaStore.Downloads is API 29. On API 26-28 the class does not exist and
    // touching EXTERNAL_CONTENT_URI throws NoSuchFieldError, which is an Error
    // and is NOT caught by the catch(Exception) below. Fail explicitly instead.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false

    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, plan.displayName)
        put(MediaStore.Downloads.MIME_TYPE, plan.mimeType)
        // The Q guard is now redundant: the whole function is behind it, and
        // keeping it is harmless but the comment should say why it is there.
        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        put(MediaStore.Downloads.IS_PENDING, 1)
    }
    val uri = try {
        resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
    } catch (t: Exception) {
        null
    } ?: return false
    ...
```

The early return means "create a file in Downloads" is a no-op below API 29,
which is a behaviour change and should be a deliberate decision — the
alternative is to route to `Environment.getExternalStoragePublicDirectory()` on
older devices, which needs `WRITE_EXTERNAL_STORAGE` and its own runtime
permission flow. Either is defensible; the current code is not, because it
crashes rather than doing either.

**This file is not owned by the CI workstream and was not modified.** The edit
above is the recommendation, not an applied change.

## The decision, stated explicitly

**Lint is not enabled wholesale, and it is not disabled wholesale.**

The `lint` job:

1. **runs** `:app:lintDebug` and `:android:lintDebug` with `--continue`, so both
   modules are analysed regardless of the other's result;
2. **archives** lint's own text/HTML/XML reports as an artifact on every run,
   including failing ones;
3. **fails the build** on any error-severity finding beyond the three above,
   via `.github/scripts/check-no-new-lint-errors.sh`.

That last step is a ratchet, not a baseline. The difference matters:

| | baseline file | this ratchet |
| --- | --- | --- |
| new error | accepted | **build fails** |
| existing error | accepted silently | listed by file:line, every run |
| existing error fixed | still listed | reported as "no longer reported", entry should be deleted |
| reviewable | opaque diff of hundreds of lines | three lines |

`abortOnError = false` in the Gradle config was rejected on two grounds: it
lives in `android/build.gradle.kts`, which this branch does not own, and it
suppresses the very report the gate depends on. A blanket `lint-off` is the
wrong answer and would hide the `NewApi` bug indefinitely.

## The 19 warnings

Not blocking, and not individually triaged here. Two are worth naming because
they will matter:

- **`Aligned16KB` on `libggml-base.so`** (3 occurrences). Android is moving to
  16 KB memory pages. A 4 KB-aligned native library may not work on devices
  requiring 16 KB alignment. This comes from the llama.cpp build, and the fix
  is upstream rather than here.
- **`SelectedPhotoAccess`** on `app/src/main/AndroidManifest.xml:25-26`. The
  app declares `READ_MEDIA_IMAGES` / `READ_MEDIA_VIDEO` without handling Android
  14+'s partial ("selected photos") access.

The rest are `InlinedApi`, `UseKtx`, `ObsoleteSdkInt` and similar style-level
findings.

## Reproducing

```sh
./gradlew :android:lintDebug        # 3 errors
./gradlew :app:lintDebug            # 0 errors
cat android/build/reports/lint-results-debug.txt
```

To see the gate work, add any error to the text report and re-run
`.github/scripts/check-no-new-lint-errors.sh` — it exits 1 and names the new
finding. This was verified, including the case where a known error moves to a
different line, which the ratchet reports as new *and* flags the stale entry.
