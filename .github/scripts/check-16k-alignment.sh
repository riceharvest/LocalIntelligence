#!/usr/bin/env bash
# Gate: every native library in the APK or AAB is aligned for 16 KB pages.
#
# Google Play requires it for apps targeting Android 15+, and a 16 KB-page phone
# refuses to load a library whose LOAD segments are aligned to 4 KB. CMakeLists
# links our own libraries with -z max-page-size=16384; this checks everything that
# actually landed in the package, including prebuilt AARs (LiteRT, sherpa-onnx).
#
# An APK keeps its libraries under lib/<abi>/; an AAB, which is a zip of modules,
# keeps the base module's under base/lib/<abi>/.
#
# Usage: check-16k-alignment.sh <path-to-apk-or-aab>
set -euo pipefail
PKG="${1:?usage: check-16k-alignment.sh <path-to-apk-or-aab>}"
case "$PKG" in
  *.aab) pattern='base/lib/*' ;;
  *)     pattern='lib/*' ;;
esac
work=$(mktemp -d); trap 'rm -rf "$work"' EXIT
# unzip exits 11 when the pattern matches nothing (no native libraries: a warning). Any other
# failure, such as an unreadable or corrupt archive, must fail the gate, not pass it.
rc=0
unzip -q -o "$PKG" "$pattern" -d "$work" 2>/dev/null || rc=$?
if [ "$rc" -eq 11 ]; then echo "::warning::$PKG has no native libraries"; exit 0; fi
[ "$rc" -eq 0 ] || { echo "::error::cannot read $PKG (unzip exit $rc)"; exit 1; }
bad=0
while IFS= read -r so; do
  # Smallest alignment over all LOAD segments; 0x4000 (16 KB) or larger passes.
  min=$(readelf -lW "$so" | awk '/LOAD/{a=strtonum($NF); if (m==0 || a<m) m=a} END{print m+0}')
  if [ "$min" -lt 16384 ]; then
    printf '::error::%s LOAD alignment 0x%x < 16 KB\n' "${so#"$work"/}" "$min"; bad=1
  fi
done < <(find "$work" -name '*.so')
[ "$bad" -eq 0 ] && echo "OK: every native library in $(basename "$PKG") is 16 KB aligned."
exit "$bad"
