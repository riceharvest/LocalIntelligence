#!/usr/bin/env bash
# Gate: :core stays a pure Kotlin/JVM module.
#
# The one architectural claim this repo makes (docs/architecture.md §2) is that
# :core has ZERO Android dependencies. That claim is only worth anything if it is
# checked, because the moment an `import android.*` sneaks in, the whole point of
# the module — "the brain is testable on the JVM in seconds, no emulator" — is
# gone, and nothing else in the build notices.
#
# Deliberately dumb: a few greps, no framework. If you can grep it, you can read
# the gate, and a gate nobody can read is a gate nobody trusts.
#
# String literals and comments are stripped before matching. That matters: the
# eval fixtures legitimately carry the *string* "android.permission.READ_CONTACTS"
# as tool metadata. A permission NAME is data. An android.* TYPE is a violation.
# A gate that cannot tell those apart is a gate people learn to bypass.
#
# Usage: scripts/check-core-purity.sh
# Exits non-zero and prints every violation with file:line.

set -euo pipefail

cd "$(dirname "$0")/.."

status=0

fail() {
  echo "::error::$1"
  status=1
}

# 1. No android.* or androidx.* imports anywhere in :core.
#    `androidx?\.` matches BOTH: androidx is Android too, and a gate that only
#    catches `android.` while waving through `androidx.` is a half-gate.
if hits=$(find core/src -name '*.kt' -print0 \
  | xargs -0 grep -nE '^[[:space:]]*import[[:space:]]+androidx?\.' || true); [ -n "$hits" ]; then
  fail ":core must stay pure JVM — found android*/androidx* imports:"
  echo "$hits"
fi

# 2. A fully-qualified use without an import is the same mistake, so catch it
#    too. Comments and string literals are stripped first, per file, so the
#    reported line numbers still point at the real file.
while IFS= read -r -d '' f; do
  # Strip in this order, and the order matters:
  #   1. string literals  — otherwise "…*/*;q=0.5" opens a phantom block comment
  #   2. ONE-LINE block comments (/** … */) — these contain their own terminator.
  #      If they are left to the range delete below they open a range that never
  #      closes and silently swallow the rest of the file, which makes the gate
  #      pass vacuously. That bug is real; it was caught by the test matrix.
  #   3. multi-line block comments
  #   4. line comments
  # What survives is code, which is the only thing the rule is about.
  m=$(sed -e 's/"[^"]*"//g' \
          -e 's:/\*.*\*/::g' \
          -e '/\/\*/,/\*\//d' \
          -e 's://.*::' "$f" \
      | grep -nE '\bandroidx?(\.[a-z0-9_]+)*\.[A-Z][A-Za-z0-9_]*' || true)
  if [ -n "$m" ]; then
    fail ":core must stay pure JVM — fully-qualified android.* reference in $f"
    echo "$m" | sed "s|^|  $f:|"
  fi
done < <(find core/src -name '*.kt' -print0)

# 3. The module must not apply the Android plugin, in any form. Three spellings
#    to catch: the plugin id (com.android.library), the legacy hyphenated id,
#    and the version-catalog alias this repo actually uses
#    (alias(libs.plugins.android.library)) — which is dotted, not hyphenated.
if hits=$(grep -nE 'com\.android|android[-.]library|android[-.]application|plugins\.android' core/build.gradle.kts || true); [ -n "$hits" ]; then
  fail ":core must not apply the Android plugin:"
  echo "$hits"
fi

# 4. And it must not declare an Android dependency directly.
#    (androidx matches the `android` prefix.)
if hits=$(grep -nE '^[[:space:]]*(api|implementation|compileOnly|runtimeOnly)\(.*android' core/build.gradle.kts || true); [ -n "$hits" ]; then
  fail ":core must not depend on Android artifacts:"
  echo "$hits"
fi

if [ "$status" -eq 0 ]; then
  echo "core-purity: OK — no android.* types in :core"
fi

exit "$status"
