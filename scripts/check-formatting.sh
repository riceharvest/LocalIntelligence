#!/usr/bin/env bash
# Gate: twelve agents writing Kotlin produce one style, not twelve.
#
# No ktlint, no detekt, no editor plugin. Two reasons:
#   1. A linter ecosystem is a supply chain and a config file nobody reads. The
#      brief for this repo is explicit: prefer a few lines of grep.
#   2. The failure mode this actually prevents is not "misindented continuation"
#      — it is CRLF from a Windows agent, tabs from a shell heredoc, and merge
#      conflict noise from inconsistent whitespace. Those are greppable, and a
#      grep gate is instant.
#
# If a real style linter is ever wanted, ktlint is the obvious candidate and
# this script is the thing it would replace.
#
# Usage: scripts/check-formatting.sh

set -euo pipefail

cd "$(dirname "$0")/.."

status=0
fail() { echo "::error::$1"; status=1; }

mapfile -t KTS < <(git ls-files '*.kt' '*.kts')

report() {  # <label> <file-list-name> <hits>
  local label="$1" name="$2" hits="$3"
  [ -n "$hits" ] || return 0
  fail "$label"
  echo "$hits" | head -20 | sed 's/^/  /'
  local n; n=$(echo "$hits" | wc -l)
  [ "$n" -gt 20 ] && echo "  … and $((n - 20)) more"
  return 0
}

# 1. No tabs. Spaces only. (Shell heredocs and pasted snippets are the source.)
report "Tab characters in Kotlin sources:" tabs \
  "$(grep -lP '\t' -- "${KTS[@]}" 2>/dev/null || true)"

# 2. No CRLF. A Windows agent committing CRLF makes every future diff on that
#    file show every line as changed, which is how merge conflicts get invented.
report "CRLF line endings (file has \\r):" crlf \
  "$(grep -lU $'\r' -- "${KTS[@]}" 2>/dev/null || true)"

# 3. No trailing whitespace. Invisible in review, permanent in the diff.
report "Trailing whitespace:" trailing \
  "$(grep -lE '[[:space:]]+$' -- "${KTS[@]}" 2>/dev/null || true)"

# 4. File ends with a newline, so the last line shows up in every diff.
#
#    This one is a WARNING, not a failure, and the distinction is deliberate.
#    Tabs and CRLF are hard failures because they are never intentional and they
#    actively break merges — a CRLF file shows every line as changed to the next
#    agent that touches it. A missing final newline is cosmetic, and there are
#    already six of them on main in files owned by other workstreams. Failing
#    the build on pre-existing debt that this workstream does not own trains
#    people to reach for the exclusion list, and an exclusion list is how a gate
#    dies. So: report it, do not block on it.
missing=""
for f in "${KTS[@]}"; do
  [ -s "$f" ] || continue
  [ -n "$(tail -c1 "$f")" ] && missing+="$f"$'\n'
done
missing=$(printf '%s' "$missing" | sed '/^$/d' || true)
if [ -n "$missing" ]; then
  echo "note: $(echo "$missing" | wc -l) file(s) do not end in a newline (not blocking):"
  echo "$missing" | head -10 | sed 's/^/  /'
fi

# 5. No println() in :core. A library that prints is a library that corrupts
#    whatever the host app prints. This is the one "style" rule here that is
#    actually a correctness rule.
if hits=$(grep -rnE '(^|[^.\w])println\(' core/src/main --include='*.kt' 2>/dev/null || true); [ -n "$hits" ]; then
  fail "println() in :core — a library must not write to stdout:"
  echo "$hits" | head -10 | sed 's/^/  /'
fi

if [ "$status" -eq 0 ]; then
  echo "formatting: OK — ${#KTS[@]} Kotlin files consistent (no tabs, CRLF, trailing whitespace, or println in :core)"
fi

exit "$status"
