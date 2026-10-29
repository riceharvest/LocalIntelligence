# CI

**There is no CI.** The workflows in `.github/workflows` were deleted on purpose
and are not coming back. Nothing runs on push or on a pull request.

The scripts in `.github/scripts/` remain and can be run by hand from the repo
root:

| Script | What it checks | Run |
| --- | --- | --- |
| `check-core-purity.sh` | `:core` has no `android.*`/`androidx.*` import, no fully-qualified Android reference, no Android plugin and no Android dependency. | `bash .github/scripts/check-core-purity.sh` |
| `check-no-secrets.sh` | No credential-like string (known token formats, long literals assigned to credential-named identifiers) in any tracked file. | `bash .github/scripts/check-no-secrets.sh` |
| `check-native-lib-name.sh` | CMake `OUTPUT_NAME`, the Kotlin `System.loadLibrary` constant and the library inside the APK are all `localintelligence_llama_jni`. | `bash .github/scripts/check-native-lib-name.sh <apk>` |
| `inspect-apk.sh` | The APK carries both `arm64-v8a` and `x86_64`, contains no NPU/accelerator runtime library, and has a manifest. | `bash .github/scripts/inspect-apk.sh <apk>` |
| `check-no-new-lint-errors.sh` | After `./gradlew :android:lintDebug :app:lintDebug`, no lint error outside the script's `KNOWN_ERRORS` list. The list is empty; see [lint.md](lint.md). | `bash .github/scripts/check-no-new-lint-errors.sh` |

`<apk>` is `app/build/outputs/apk/debug/app-debug.apk` after
`./gradlew :app:assembleDebug`.

Related: [lint.md](lint.md), and an open native-build defect in
[open-defect-shared-libs.md](open-defect-shared-libs.md).
