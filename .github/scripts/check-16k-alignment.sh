#!/usr/bin/env bash
# Gate: every native library in the APK is aligned for 16 KB pages.
#
# Google Play requires it for apps targeting Android 15+, and a 16 KB-page phone
# refuses to load a library whose LOAD segments are aligned to 4 KB. CMakeLists
# links our own libraries with -z max-page-size=16384; this checks everything that
# actually landed in the APK, including prebuilt AARs (LiteRT, sherpa-onnx).
#
# Usage: check-16k-alignment.sh <path-to-apk>
set -euo pipefail
APK="${1:?usage: check-16k-alignment.sh <path-to-apk>}"
work=$(mktemp -d); trap 'rm -rf "$work"' EXIT
unzip -q -o "$APK" 'lib/*' -d "$work" 2>/dev/null || { echo "::warning::$APK has no native libraries"; exit 0; }
bad=0
while IFS= read -r so; do
  # Smallest alignment over all LOAD segments; 0x4000 (16 KB) or larger passes.
  min=$(readelf -lW "$so" | awk '/LOAD/{a=strtonum($NF); if (m==0 || a<m) m=a} END{print m+0}')
  if [ "$min" -lt 16384 ]; then
    printf '::error::%s LOAD alignment 0x%x < 16 KB\n' "${so#"$work"/}" "$min"; bad=1
  fi
done < <(find "$work/lib" -name '*.so')
[ "$bad" -eq 0 ] && echo "OK: every native library in $(basename "$APK") is 16 KB aligned."
exit "$bad"
