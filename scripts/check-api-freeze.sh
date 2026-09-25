#!/usr/bin/env bash
# Gate: the :core public API is FROZEN. (docs/architecture.md §19: "Do not modify
# interfaces owned by another workstream.")
#
# Twelve agents writing Kotlin will collide on the contracts. This makes a
# contract change impossible to do quietly: the public API is dumped to a
# committed file, and CI regenerates that dump and fails if it moved. The diff
# IS the review artifact — a reviewer sees exactly which signature moved.
#
# WHY NOT `./gradlew :core:apiCheck`
# It exists, and it looks like the obvious answer. It is also silently broken
# under this repo's `org.gradle.configuration-cache=true`: with the cache on,
# the check task's action never runs and apiCheck exits 0 even with a corrupted
# dump. Verified locally — the same tree fails correctly with
# --no-configuration-cache. A gate that dies the moment someone tidies a gradle
# property is worse than no gate, so this script does the comparison itself:
# regenerate with the plugin, diff with git. Five lines, no hidden behaviour.
#
# The dump is produced by binary-compatibility-validator, which understands
# Kotlin visibility. That matters: `internal object TokenEstimate` compiles to a
# public JVM class, so a naive javap dump would report internal refactors as
# broken contracts. BCV reads the Kotlin metadata and gets it right.
#
# Usage: scripts/check-api-freeze.sh
# To accept an intentional API change:
#     ./gradlew :core:apiDump && git add core/api/core.api && git commit

set -euo pipefail

cd "$(dirname "$0")/.."

API_FILE="core/api/core.api"

# 1. The frozen dump must be committed. An untracked "golden" file is not a
#    baseline; it is a suggestion.
if ! git ls-files --error-unmatch "$API_FILE" >/dev/null 2>&1; then
  echo "::error::$API_FILE is not tracked by git. The frozen API must be committed."
  echo "  Run: ./gradlew :core:apiDump && git add $API_FILE"
  exit 1
fi

# 2. Regenerate the dump from the current sources.
./gradlew :core:apiDump -q --console=plain

# 3. Did the public API move?
if ! git diff --quiet -- "$API_FILE"; then
  echo "::error::The :core public API changed. Interfaces in :core are FROZEN."
  echo "If this change is intended and reviewed, regenerate and commit the dump:"
  echo "  ./gradlew :core:apiDump && git add $API_FILE"
  echo
  echo "Diff of the frozen public API:"
  git --no-pager diff --unified=3 -- "$API_FILE"
  exit 1
fi

echo "api-freeze: OK — :core public API unchanged ($(wc -l < "$API_FILE") lines frozen)"
