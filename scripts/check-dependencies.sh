#!/usr/bin/env bash
# Gate: no dependency gets added without an explicit, written justification.
#   (docs/architecture.md §19: "Do not add dependencies unless it is genuinely
#    necessary. Do not add cloud dependencies. Do not add telemetry.
#    Do not introduce MCP.")
#
# Two independent checks, because they fail differently:
#
#   A. ALLOWLIST — every coordinate in gradle/libs.versions.toml must appear in
#      ci/dependency-allowlist.txt with a non-empty justification. A new
#      dependency therefore cannot land quietly: either CI goes red, or the
#      author edits the allowlist, which is a diff a reviewer sees.
#
#   B. DENYLIST — coordinates that are forbidden outright, checked against the
#      whole build, not just the catalog. The allowlist is a file an author can
#      edit in the same PR; the denylist is the backstop that survives that.
#      Editing the allowlist to admit Firebase still gets you a red build.
#
# Usage: scripts/check-dependencies.sh

set -euo pipefail

cd "$(dirname "$0")/.."

ALLOWLIST="ci/dependency-allowlist.txt"
CATALOG="gradle/libs.versions.toml"
status=0

fail() { echo "::error::$1"; status=1; }

# ---------------------------------------------------------------- A. allowlist

# Coordinates declared in [libraries], e.g. module = "junit:junit"
declared=$(grep -oE 'module = "[^"]+"' "$CATALOG" | sed -e 's/module = "//' -e 's/"$//' | sort -u || true)

# Plugin ids declared in [plugins]. The id is NOT at line start — entries look
# like: android-application = { id = "com.android.application", version.ref = "agp" }
declared_plugins=$(grep -oE 'id = "[^"]+"' "$CATALOG" | sed -e 's/id = "//' -e 's/"$//' | sort -u || true)

# Approved entries = first whitespace-delimited field of each non-comment line.
approved=$(grep -vE '^\s*(#|$)' "$ALLOWLIST" | awk -F'\t|  +' '{print $1}' | sort -u)

for dep in $declared $declared_plugins; do
  if ! grep -qxF "$dep" <<<"$approved"; then
    fail "Unapproved dependency: $dep"
    echo "  Every dependency needs a written justification in $ALLOWLIST."
    echo "  If it is genuinely necessary, add:"
    echo "      $dep<TAB>why it is necessary"
    echo "  …and add it to $CATALOG, in the same PR so the reviewer sees both."
  fi
done

# An allowlist entry with no justification is not an approval, it is a shrug.
# Strip the coordinate, then require something to remain.
while IFS= read -r line; do
  case "$line" in \#*|"") continue ;; esac
  reason=$(printf '%s' "$line" | awk -F'\t|  +' '{$1=""; sub(/^[ \t]+/,""); print}')
  if [ -z "$reason" ]; then
    fail "Allowlist entry has an empty justification: $(printf '%s' "$line" | awk -F'\t|  +' '{print $1}')"
  fi
done < "$ALLOWLIST"

# Stale allowlist entries: approved but no longer used. Not fatal — an
# allowlist is a ceiling, and a ceiling may be lower than it once was — but
# worth surfacing so the list does not rot into a blanket permission.
for dep in $approved; do
  if ! grep -qxF "$dep" <<<"$(printf '%s\n%s\n' "$declared" "$declared_plugins")"; then
    echo "note: allowlist entry is no longer used by the build: $dep"
  fi
done

# ----------------------------------------------------------------- B. denylist
# Cloud, telemetry, crash reporting, MCP, and JitPack. Checked across the
# catalog AND the module build files, so a hardcoded coordinate is caught too.
# Kept as a readable list rather than a regex so a reviewer can audit it.
deny() {
  local what="$1"; shift
  local pattern="$1"; shift
  if hits=$(grep -rnE "$pattern" "$CATALOG" build.gradle.kts core/build.gradle.kts android/build.gradle.kts app/build.gradle.kts settings.gradle.kts 2>/dev/null || true); [ -n "$hits" ]; then
    fail "Forbidden $what found:"
    echo "$hits"
  fi
}

# Telemetry / analytics / crash reporting
deny "telemetry or crash-reporting SDK" \
  'com\.google\.firebase|com\.google\.android\.gms|firebase-crashlytics|crashlytics|io\.sentry|app\.crashlytics|io\.amplitude|com\.amplitude|segment\.|analytics|mixpanel|com\.segment|posthog|datadog|newrelic|bugsnag|appsflyer|adjust\.com|branch\.io'
# Cloud SDKs
deny "cloud SDK" \
  'com\.amazonaws|software\.amazon\.awssdk|com\.google\.cloud|io\.azure|azure-sdk|com\.oracle|oci-java-sdk|aliyun|tencentcloud|cloudflare|netlify|vercel'
# MCP (explicitly out of scope; docs/architecture §1 "Explicitly NOT in v0")
deny "MCP dependency" \
  'mcp|modelcontextprotocol|io\.modelcontextprotocol|anthropic|openai|langchain|llamaindex|haystack|embedchain|smolagents|crewai|autogen'
# JitPack: docs/swarm-plan.md calls it a CI reliability risk for a public repo.
deny "JitPack repository" 'jitpack'

# A repository added outside settings.gradle.kts is a supply-chain surprise.
if hits=$(grep -rnE 'maven\s*\{|maven\s*\(' settings.gradle.kts 2>/dev/null || true); [ -n "$hits" ]; then
  fail "Non-standard maven repository declared in settings.gradle.kts:"
  echo "$hits"
fi

if [ "$status" -eq 0 ]; then
  n=$(printf '%s\n%s\n' "$declared" "$declared_plugins" | grep -c . || true)
  echo "dependency-policy: OK — $n declared dependencies, all approved, none forbidden"
fi

exit "$status"
