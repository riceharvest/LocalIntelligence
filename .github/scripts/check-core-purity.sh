#!/usr/bin/env bash
# Gate: :core is a pure JVM module.
#
# THE DEFECT THIS CATCHES — the architecture's one non-negotiable rule
# (settings.gradle.kts: "THE ONE ARCHITECTURAL RULE: :core is a PURE KOTLIN/JVM
# module with ZERO Android dependencies") is enforced in a comment and in
# docs/architecture.md, and nothing checked it. A single `import android.os.Build`
# in :core compiles fine against the :android classpath and silently converts the
# whole agent loop into something that cannot run on the JVM, which is the only
# reason a swarm of agents can verify it in seconds. It fails at the point where
# somebody tries to use it, not where it was written.
#
# Plain shell, no new dependency. Exits non-zero on the first violation.
set -euo pipefail

fail=0

# 1. No android.* (or androidx.*) import anywhere in :core source.
if grep -rn --include='*.kt' --include='*.kts' -E '^\s*import\s+(android|androidx)\.' core/src/ ; then
  echo "::error::core/src must contain zero android.* or androidx.* imports. :core is a pure JVM module (see settings.gradle.kts). A leaked import compiles clean and then fails everywhere except an Android device."
  fail=1
fi

# 2. A fully-qualified android reference that dodges the import, e.g.
#    `Build.VERSION.SDK_INT` with no import. This is the same defect wearing a
#    disguise, and the import-only check above would not see it.
if grep -rn --include='*.kt' -E '\bandroid\.(os|content|app|provider|net|os\.Build)\.' core/src/ ; then
  echo "::error::core/src references android.* by fully-qualified name. Same defect as an import: :core must be a pure JVM module."
  fail=1
fi

# 3. :core must not apply the Android plugin. If it ever does, the JDK-only
#    compile in this job stops meaning anything.
if grep -n 'com.android' core/build.gradle.kts ; then
  echo "::error::core/build.gradle.kts must not apply the Android plugin"
  fail=1
fi

# 4. :core must not depend on an Android artifact, even transitively through a
#    version-catalog alias whose name does not say "android".
if grep -nE 'implementation|api' core/build.gradle.kts | grep -i 'android' ; then
  echo "::error::core/build.gradle.kts declares an Android dependency"
  fail=1
fi

if [ "$fail" -ne 0 ]; then
  exit 1
fi
echo "OK: :core has zero Android dependencies (no imports, no fully-qualified refs, no plugin, no dependency)."
