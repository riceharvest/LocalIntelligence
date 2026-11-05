#!/usr/bin/env bash
# Release builds: the Play bundle and the full APK, checked and copied to dist/.
#
# Play gets a signed AAB for Google Play. Full gets a signed APK for GitHub Releases
# (docs/adr/0002-play-and-full-builds.md). The signing keys live outside the repository,
# in the file named by liSigningProps in ~/.gradle/gradle.properties (docs/build.md).
#
# This script never uploads, never tags and never creates a GitHub release. Those steps
# are done by a person, after reading the output.
set -euo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
cd "$ROOT"

# --- Keys: read from the properties file; values are never printed. ---------------------
PROPS_FILE=$(sed -n 's/^liSigningProps=//p' "$HOME/.gradle/gradle.properties" 2>/dev/null | head -1)
if [ -z "$PROPS_FILE" ]; then
  echo "error: liSigningProps is not set in ~/.gradle/gradle.properties; release builds need the signing keys (docs/build.md)." >&2
  exit 1
fi
[ -f "$PROPS_FILE" ] || { echo "error: signing properties file $PROPS_FILE does not exist." >&2; exit 1; }
prop() { sed -n "s/^$1=//p" "$PROPS_FILE" | head -1; }

PLAY_STORE=$(prop play.storeFile)
PLAY_ALIAS=$(prop play.keyAlias)
FULL_STORE=$(prop full.storeFile)
FULL_ALIAS=$(prop full.keyAlias)
# keytool reads the store password from the environment, so it never appears in argv.
export PLAY_STORE_PASS FULL_STORE_PASS
PLAY_STORE_PASS=$(prop play.storePassword)
FULL_STORE_PASS=$(prop full.storePassword)

# --- Versions: the single source is app/build.gradle.kts. -------------------------------
VERSION_NAME=$(sed -n 's/.*versionName = "\([^"]*\)".*/\1/p' app/build.gradle.kts | head -1)
VERSION_CODE=$(sed -n 's/.*versionCode = \([0-9][0-9]*\).*/\1/p' app/build.gradle.kts | head -1)
[ -n "$VERSION_NAME" ] && [ -n "$VERSION_CODE" ] || { echo "error: could not read versionName/versionCode from app/build.gradle.kts" >&2; exit 1; }

# --- Build: the same flock and resource wrapper as every other build on this machine. ---
export JAVA_HOME=${JAVA_HOME:-$HOME/.local/share/jdks/jdk-21.0.13+9}
export ANDROID_HOME=${ANDROID_HOME:-$HOME/Android/Sdk}
# Two steps, one at a time, each under the 6 GB limit. The llama.cpp Release native compile is the
# memory peak; the R8 step after it reuses those native outputs. A single combined run was OOM-killed.
# One Gradle build at a time on this machine: every build takes the same lock (override with LI_GRADLE_LOCK).
GRADLE_WRAP=(flock "${LI_GRADLE_LOCK:-/tmp/li-gradle.lock}" systemd-run --user --scope -q -p MemoryMax=6G -p CPUQuota=400% nice -n 10)
# A cold run can still hit the 6 GB limit (exit 137 or 143). Gradle keeps what it finished, so running it again goes further.
gradle_step() {
  for attempt in 1 2 3 4; do
    "${GRADLE_WRAP[@]}" ./gradlew "$@" --max-workers=2 --no-daemon -q && return 0
    echo "Gradle stopped (attempt $attempt of 4): $*" >&2
  done
  return 1
}
gradle_step :android:externalNativeBuildRelease
# One R8 run per Gradle invocation: two R8 runs side by side were OOM-killed in the 6 GB scope.
# Only the Play AAB is built for Play (Play installs from the bundle; no Play APK is needed).
gradle_step :app:bundlePlayRelease
gradle_step :app:assembleFullRelease

AAB=app/build/outputs/bundle/playRelease/app-play-release.aab
APK=app/build/outputs/apk/full/release/app-full-release.apk
UNSIGNED=app/build/outputs/apk/full/release/app-full-release-unsigned.apk
[ -f "$UNSIGNED" ] && { echo "error: $UNSIGNED exists: the full APK was not signed." >&2; exit 1; }
[ -f "$AAB" ] || { echo "error: missing $AAB" >&2; exit 1; }
[ -f "$APK" ] || { echo "error: missing $APK" >&2; exit 1; }

# --- 16 KB page alignment, on both outputs. ----------------------------------------------
.github/scripts/check-16k-alignment.sh "$AAB"
.github/scripts/check-16k-alignment.sh "$APK"

# --- Signatures: each output must be signed by the key it is meant for. -------------------
BUILD_TOOLS=$(ls -d "$ANDROID_HOME"/build-tools/* | sort -V | tail -1)
APKSIGNER="$BUILD_TOOLS/apksigner"

# SHA-256 of the signing certificate, lowercase hex, no separators.
# Every check below reads a captured string, never a pipe that exits early: with pipefail, grep -q or
# awk's exit closing the pipe makes the writer die of SIGPIPE, and the check fails at random.
first_sha256() { awk '/SHA256:/ && !seen {print $2; seen=1}' <<<"$1" | tr -d ':' | tr 'A-F' 'a-f'; }

keystore_sha256() { # <store> <alias> <password-variable>
  first_sha256 "$(keytool -list -v -keystore "$1" -storetype PKCS12 -alias "$2" -storepass:env "$3")"
}

apk_out=$("$APKSIGNER" verify --print-certs "$APK")
apk_digest=$(awk -F': ' '/Signer #1 certificate SHA-256 digest/ && !seen {print $2; seen=1}' <<<"$apk_out")
aab_out=$(jarsigner -verify "$AAB")
grep -q '^jar verified' <<<"$aab_out" || { echo "error: $AAB did not verify as a signed JAR" >&2; exit 1; }
aab_digest=$(first_sha256 "$(keytool -printcert -jarfile "$AAB")")

play_expected=$(keystore_sha256 "$PLAY_STORE" "$PLAY_ALIAS" PLAY_STORE_PASS)
full_expected=$(keystore_sha256 "$FULL_STORE" "$FULL_ALIAS" FULL_STORE_PASS)

[ "$aab_digest" = "$play_expected" ] || { echo "error: $AAB is not signed with the play key" >&2; exit 1; }
[ "$apk_digest" = "$full_expected" ] || { echo "error: $APK is not signed with the full key" >&2; exit 1; }
"$APKSIGNER" verify "$APK" >/dev/null

# --- Copy to dist/ with checksums. -------------------------------------------------------
mkdir -p dist
DIST_AAB="LocalIntelligence-$VERSION_NAME.aab"
DIST_APK="LocalIntelligence-Full-$VERSION_NAME-full.apk"
cp "$AAB" "dist/$DIST_AAB"
cp "$APK" "dist/$DIST_APK"
(cd dist && sha256sum "$DIST_APK" > "$DIST_APK.sha256")

echo
echo "versionCode=$VERSION_CODE versionName=$VERSION_NAME"
echo "play  AAB  signer SHA-256 $aab_digest"
echo "full  APK  signer SHA-256 $apk_digest"
echo "sizes: $(stat -c %s "dist/$DIST_AAB") bytes $DIST_AAB"
echo "       $(stat -c %s "dist/$DIST_APK") bytes $DIST_APK"
echo "wrote: $ROOT/dist/$DIST_AAB"
echo "       $ROOT/dist/$DIST_APK"
echo "       $ROOT/dist/$DIST_APK.sha256"
echo
echo "Reminder: Google Play needs a higher versionCode than the last upload."
echo "This script did not upload, tag or publish anything."
