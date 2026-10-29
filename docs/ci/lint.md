# Android Lint

Lint is not wired into anything. Run it by hand:

```sh
./gradlew :android:lintDebug :app:lintDebug --continue
cat android/build/reports/lint-results-debug.txt
```

The last lint run on record (historical, before the fixes below) reported **3
errors** in `:android` and 0 in `:app`. This page has not been re-measured since;
the current counts are unmeasured.

## The three historical errors

### 1 and 2. `DeviceTools.kt` `MissingPermission` (`Vibrator.vibrate`) — fixed

`:android` calls `Vibrator.vibrate` but its manifest declared no permission, so
lint analysing the library alone flagged both overloads. `android/src/main/AndroidManifest.xml`
now declares `android.permission.VIBRATE`.

### 3. `FileTools.kt` `NewApi` (`MediaStore.Downloads.EXTERNAL_CONTENT_URI`, API 29) — fixed

`minSdk` is 26. `createInDownloads` in `FileTools.kt` now returns `false` up
front when `Build.VERSION.SDK_INT < Build.VERSION_CODES.Q`, so the API 29 class
is never touched on API 26-28.

## Ratchet list

`.github/scripts/check-no-new-lint-errors.sh` has an empty `KNOWN_ERRORS` list now that the three errors above are fixed.

## Warnings

19 warnings were reported in `:android` and 21 warnings plus 2 hints in `:app` at
the time of that run. Not triaged. Two were named then:

- **`Aligned16KB` on `libggml-base.so`.** Android is moving to 16 KB memory
  pages; the library comes from the llama.cpp build.
- **`SelectedPhotoAccess`** in `app/src/main/AndroidManifest.xml`. The app
  declares `READ_MEDIA_IMAGES` / `READ_MEDIA_VIDEO` without handling Android
  14+ partial photo access.
