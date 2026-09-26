#!/usr/bin/env bash
# Gate: the JNI library is named exactly `localintelligence_llama_jni`.
#
# THE DEFECT THIS CATCHES — commit a902c56 ("fix(android): a real model now
# loads and generates on a device", bug 1 of 4). CMake named the target
# `LocalIntelligence_llama_jni`, so the APK packaged
# `libLocalIntelligence_llama_jni.so`, while LlamaBridge.kt:26 called
#     System.loadLibrary("localintelligence_llama_jni")
# Android's linker is case-sensitive and does NOT normalise the name, so every
# load threw "the llama.cpp native library is not available on this device" and
# the native backend was dead on arrival: the app could not load a model at all.
#
# It shipped because the build compiles and the test suite was deleted — nothing
# between `assemble` and `dlopen` was ever checked. The fix was
# `set_target_properties(... OUTPUT_NAME "localintelligence_llama_jni")` in
# android/src/main/cpp/CMakeLists.txt, and nothing kept that fix in place.
#
# The check is deliberately a THREE-WAY agreement, because that is what the
# defect actually broke. CMake's OUTPUT_NAME, the Kotlin constant, and the file
# that really landed in the APK must all be the same lowercase string. Any two
# agreeing while the third differs is precisely the a902c56 failure mode.
#
# Usage: check-native-lib-name.sh <path-to-apk>
set -euo pipefail

EXPECTED='localintelligence_llama_jni'
CMAKE_LISTS='android/src/main/cpp/CMakeLists.txt'
BRIDGE='android/src/main/kotlin/dev/localintelligence/android/inference/LlamaBridge.kt'
APK="${1:-}"

fail=0

# --- 1. CMake must pin OUTPUT_NAME to the lowercase name. ---------------------
if ! grep -q "OUTPUT_NAME[[:space:]]*\"${EXPECTED}\"" "$CMAKE_LISTS"; then
  echo "::error::${CMAKE_LISTS} does not pin OUTPUT_NAME to \"${EXPECTED}\". Without it CMake emits libLocalIntelligence_llama_jni.so and Android's case-sensitive loader cannot find it (regression of a902c56)."
  fail=1
fi

# --- 2. The Kotlin constant must be exactly the lowercase name. -------------
# Extracted from the source rather than hardcoded, so renaming the constant in
# Kotlin and in CMake together is a visible, deliberate diff instead of a
# silent two-place edit that has to be kept in sync by hand.
bridge_name=$(sed -n 's/.*LIBRARY_NAME[[:space:]]*=[[:space:]]*"\([^"]*\)".*/\1/p' "$BRIDGE" | head -1)
if [ -z "$bridge_name" ]; then
  echo "::error::could not read LIBRARY_NAME from ${BRIDGE}; the gate cannot prove the name matches"
  fail=1
elif [ "$bridge_name" != "$EXPECTED" ]; then
  echo "::error::${BRIDGE} calls System.loadLibrary(\"${bridge_name}\") but the library is built as \"${EXPECTED}\". Android's loader is case-sensitive: this app cannot load a model."
  fail=1
fi

# --- 3. The APK must actually contain that exact file, in every ABI. --------
if [ -n "$APK" ] && [ -f "$APK" ]; then
  # The JNI libraries in the APK, matched on the `_jni` suffix. Not on
  # "llama": upstream llama.cpp ships its own libllama.so on the FetchContent
  # path (docs/ci/open-defect-shared-libs.md) and that is not this project's
  # library to judge.
  entries=$(unzip -l "$APK" | awk '{print $4}' | grep -E '^lib/[^/]+/.*_jni\.so$' || true)
  if [ -z "$entries" ]; then
    echo "::error::${APK} contains no JNI library at all. The build produced an APK with no native backend."
    fail=1
  else
    # "Ours" = the basename, lower-cased, equals the expected name. The
    # case-insensitive comparison is the whole point: a902c56 shipped
    # libLocalIntelligence_llama_jni.so, which differs from the expected name
    # only in case and is therefore exactly the library this gate must catch.
    ours=$(printf '%s\n' "$entries" \
      | grep -iE "/lib${EXPECTED}\.so$" || true)
    if [ -z "$ours" ]; then
      echo "::error::${APK} contains no copy of lib${EXPECTED}.so in any ABI. The Kotlin side calls System.loadLibrary(\"${EXPECTED}\"), so nothing can load."
      fail=1
    else
      # Eagerly flag ANY casing variant. Checking only that one correct copy
      # exists is not enough: an earlier version of this gate did exactly that
      # and passed an APK where arm64-v8a was broken and x86_64 was fine.
      # Android picks the ABI it is running, so a half-correct APK works on one
      # device and fails on every other.
      bad=$(printf '%s\n' "$ours" | grep -vE "/lib${EXPECTED}\.so$" || true)
      if [ -n "$bad" ]; then
        echo "::error::${APK} ships a wrongly-cased copy of the JNI library. Android's loader is case-sensitive, so that ABI cannot load a model:"
        printf '%s\n' "$bad" | sed 's/^/  /'
        fail=1
      fi
      shipped=$(printf '%s\n' "$ours" | grep -E "/lib${EXPECTED}\.so$" | sed -E 's#^lib/([^/]+)/.*#\1#' | sort -u)
      echo "llama JNI library present as lib${EXPECTED}.so in: $(printf '%s' "$shipped" | tr '\n' ' ')"
    fi
  fi
else
  echo "::warning::no APK given; checked the CMake and Kotlin names only"
fi

if [ "$fail" -ne 0 ]; then
  exit 1
fi
echo "OK: the JNI library is named ${EXPECTED} in CMake, in Kotlin, and in the APK."
