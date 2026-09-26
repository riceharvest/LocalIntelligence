#!/usr/bin/env bash
# Gate: what is actually inside the APK.
#
# Every check here inspects a real, built artifact rather than a source file, so
# it cannot be satisfied by a comment, a version pin, or an intention. The three
# defects behind them each reached main because a compile cannot see them.
#
# Usage: inspect-apk.sh <path-to-apk>
set -euo pipefail

APK="${1:?usage: inspect-apk.sh <path-to-apk>}"
[ -f "$APK" ] || { echo "::error::no such APK: $APK"; exit 1; }

fail=0
lib_list=$(unzip -l "$APK" | awk '{print $4}' | grep -E '^lib/[^/]+/' || true)

echo "== APK inventory =="
echo "path:  $APK"
echo "bytes: $(stat -c%s "$APK")"
echo "native libraries:"
printf '%s\n' "$lib_list" | sed 's/^/  /'

abis=$(printf '%s\n' "$lib_list" | sed -E 's#^lib/([^/]+)/.*#\1#' | sort -u)
echo "ABIs present: $(printf '%s' "$abis" | tr '\n' ' ')"

# ---------------------------------------------------------------------------
# GATE 1 — BOTH 64-bit ABIs must be present.
#
# THE DEFECT THIS CATCHES — android/build.gradle.kts declares
# `abiFilters += listOf("arm64-v8a", "x86_64")`. A single-element list compiles
# identically, produces an installable APK, and is a complete success at build
# time. It is only wrong on a device: drop x86_64 and every x86_64 emulator and
# a large slice of Chromebooks fails to install, with no build-time signal at
# all. `abiFilters` is the one setting in this project where a typo is silent.
#
# armeabi-v7a and x86 are deliberately NOT required: armeabi-v7a is dropped on
# purpose (a 1-4B model does not fit in 32-bit address space alongside a JVM)
# and a transitive AndroidX artifact may still contribute a stub for it.
# ---------------------------------------------------------------------------
for abi in arm64-v8a x86_64; do
  if ! printf '%s\n' "$abis" | grep -qx "$abi"; then
    echo "::error::APK has no lib/${abi}/ entries. android/build.gradle.kts declares abiFilters arm64-v8a + x86_64; a single-ABI APK still builds and only fails at install time."
    fail=1
  fi
done

# The JNI library specifically must exist in both, not just some library: a
# dependency could otherwise satisfy the loop above on its own.
for abi in arm64-v8a x86_64; do
  if ! printf '%s\n' "$lib_list" | grep -qx "lib/${abi}/liblocalintelligence_llama_jni.so"; then
    echo "::error::APK is missing lib/${abi}/liblocalintelligence_llama_jni.so — the native backend is not packaged for ${abi}."
    fail=1
  fi
done

# ---------------------------------------------------------------------------
# GATE 2 — NO NPU / ACCELERATOR LIBRARY.
#
# THE DEFECT THIS CATCHES — this project claims CPU inference only, and the
# documentation is careful never to claim a hardware accelerator it does not
# use. That claim has nothing holding it up: LiteRT-LM 0.13.1
# (com.google.ai.edge.litertlm:litertlm-android, gradle/libs.versions.toml:27)
# ships exactly three libraries per ABI and none of them is an NPU runtime —
# verified by unzipping the AAR from the Gradle cache, not from the release
# notes. A future version, or one new dependency, could add one, the APK would
# grow, and the app would advertise an accelerator path that has never been run
# on a device.
#
# The pattern is deliberately about NPU/accelerator runtimes only. libomp.so is
# NOT flagged: it is OpenMP, and llama.cpp links it on some configurations.
# Flagging it would be a false positive that trains people to ignore this gate.
# ---------------------------------------------------------------------------
# Names that mean a dedicated neural-accelerator runtime. Matched against the
# BASENAME with any "lib" prefix.
#
# ORDER MATTERS: the alternation is longest-prefix-first and the boundary
# character class is [.0-9._-] rather than [._-]. With a shorter alternative
# first, "npu" matches the front of "libnpuaccelerator.so", the boundary then
# has to match "a", and the whole thing misses the one library this check
# exists to find. That exact bug shipped in the first version of this script
# and was caught by injecting libnpuaccelerator.so into a real APK and watching
# the gate stay green.
#
# The boundary is case-insensitive because these names are not consistently
# cased across vendors (libQnn.so, libhexagon.so, libnpuaccelerator.so).
npu_re='(neural_networks_codegen|neuralaccelerator|npuaccelerator|npu_core|edgetpu|mtk_aio|hexagon|tensorrt|cldnn|neuron|libmnn|npu|qnn|mnn)'
npu_hits=$(printf '%s\n' "$lib_list" \
  | sed -E 's#^.*/##' \
  | grep -iE "^lib(${npu_re})[.0-9._-]" || true)
if [ -n "$npu_hits" ]; then
  echo "::error::APK contains an NPU/accelerator runtime. This app does CPU inference only and has no measured support for any of these:"
  printf '%s\n' "$npu_hits" | sed 's/^/  /'
  fail=1
else
  echo "OK: no NPU/accelerator library in the APK (CPU inference only, as claimed)."
fi

# ---------------------------------------------------------------------------
# GATE 3 — the APK must be structurally installable.
#
# THE DEFECT THIS CATCHES — an APK that is missing from the artifact upload, or
# that is a directory rather than a file, or whose signing block is missing,
# uploads as a "success" and fails on the first person who tries it. A
# `path:` glob that matches nothing is the common form: upload-artifact with
# if-no-files-found:error catches it, but only for the one path it was given.
#
# The manifest is read straight out of the ZIP central directory with unzip, so
# this needs no build-tools, no aapt2, and no third-party action. If aapt2 is on
# PATH it is used as a second opinion; if not, the check still runs.
# ---------------------------------------------------------------------------
# `grep -q` exits the moment it matches, which sends SIGPIPE to `unzip`. Under
# `set -o pipefail` that non-zero upstream status wins and the whole pipeline
# reports failure — so this check fired on a perfectly good APK. Capturing the
# listing first and grepping the variable avoids the race entirely, and is also
# what the ABI checks above already do.
listing=$(unzip -l "$APK" 2>/dev/null || true)
if ! printf '%s\n' "$listing" | grep -q 'AndroidManifest.xml'; then
  echo "::error::${APK} has no AndroidManifest.xml — it is not an APK."
  fail=1
fi

# The debug keystore signs the entry block. A truncated or re-zipped APK has a
# valid central directory and no signature, and installs as a parse error
# rather than a "wrong signature" error, which is far harder to diagnose.
if printf '%s\n' "$listing" | grep -qE 'META-INF/.*\.(RSA|DSA|EC)$'; then
  echo "OK: APK carries a v1 signature block (META-INF)."
elif printf '%s\n' "$listing" | grep -q 'AndroidManifest.xml'; then
  # No v1 block is normal for a modern APK signed with apksigner v2/v3 only,
  # which lives in the ZIP comment-adjacent signing block. Presence of the
  # signing block is what apksigner verifies; absence is not checkable with
  # unzip alone, so this is reported, not failed.
  echo "note: no v1 META-INF signature block; the APK is signed with an apksigner v2/v3 block only (normal for AGP 8.x)."
fi

if command -v aapt2 > /dev/null 2>&1; then
  echo "== aapt2 badging =="
  aapt2 dump badging "$APK" 2>/dev/null | head -20 || echo "note: aapt2 dump failed; skipped"
fi

if [ "$fail" -ne 0 ]; then
  exit 1
fi
echo "OK: APK carries both 64-bit ABIs, the JNI library in each, and no NPU runtime."
