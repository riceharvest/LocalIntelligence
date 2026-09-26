#!/usr/bin/env bash
# Gate: Android Lint introduces no NEW errors.
#
# THE SITUATION — Android Lint has never run in CI. It does not currently pass:
# `:android:lintDebug` reports 3 errors and 19 warnings, `:app:lintDebug` reports
# 0 errors, 21 warnings and 2 hints. One of those three errors is a real bug that
# would silently fail on API 26-28 devices. The other two are a manifest-merge
# false positive. None of the three can be fixed from this branch: they live in
# DeviceTools.kt and FileTools.kt, which belong to another workstream. The exact
# source edits that clear them are written up in docs/ci/lint.md.
#
# WHAT THIS SCRIPT IS, PRECISELY — a ratchet. It fails on any error-severity
# finding that is not one of the three named below. So:
#   * a NEW lint error breaks the build immediately;
#   * the three known errors stay visible, counted, and reported every run;
#   * when someone fixes one, this script starts failing to find it, which is
#     the signal to delete its entry here.
#
# WHAT THIS SCRIPT IS NOT — it is not `abortOnError = false`, and it is not a
# baseline file. A baseline accepts whatever it is handed, including the next
# hundred regressions; a ratchet with three named lines does not. It is also not
# scoped down to "ignore lint entirely": lint runs, produces its full report,
# and that report is uploaded as an artifact every run.
#
# KNOWN_ERRORS is file:line plus a short reason. The line numbers are the ones
# lint reports today; a moved line is not a new error, because the match is on
# file + issue id, and a *changed* set is reported below rather than silently
# accepted.
set -euo pipefail

REPORTS=(
  android/build/reports/lint-results-debug.txt
  app/build/reports/lint-results-debug.txt
)

# The three errors that exist right now, each with why it is here.
# Format: <file-basename>:<line>:<IssueId>  — reason
KNOWN_ERRORS=(
  "DeviceTools.kt:1089:MissingPermission"
  "DeviceTools.kt:1097:MissingPermission"
  "FileTools.kt:1580:NewApi"
)

existing=0
for r in "${REPORTS[@]}"; do
  [ -f "$r" ] && existing=1
done

if [ "$existing" -eq 0 ]; then
  echo "::error::no lint report found. Expected one of:"
  printf '  %s\n' "${REPORTS[@]}"
  echo "If lint did not run, that is a CI problem, not a pass."
  exit 1
fi

fail=0
total_new=0

for r in "${REPORTS[@]}"; do
  [ -f "$r" ] || continue
  # .../android/build/reports/lint-results-debug.txt -> "android", NOT "build".
  # Two `dirname`s lands on the module directory, but the report path starts at
  # the module root, so the module name is the first path component of the
  # report path relative to the repo root. Taking it from the report path
  # itself is what keeps this correct if a module is ever renamed.
  module=${r%%/*}
  echo "== $module =="

  # Each line of a lint text report that carries a severity looks like:
  #   /abs/path/File.kt:1089: Error: message [IssueId]
  # Take the basename, the line, and the bracketed id.
  mapfile -t found < <(grep -E ':[0-9]+: Error: ' "$r" \
    | sed -E 's#^.*/([^/]+\.kt):([0-9]+): Error: .*\[([^]]+)\]$#\1:\2:\3#' \
    | sort -u || true)

  if [ "${#found[@]}" -eq 0 ]; then
    echo "no errors"
    continue
  fi

  for f in "${found[@]}"; do
    known=0
    for k in "${KNOWN_ERRORS[@]}"; do
      [ "$f" = "$k" ] && known=1 && break
    done
    if [ "$known" -eq 1 ]; then
      echo "  known: $f"
    else
      echo "  NEW:   $f"
      total_new=$((total_new + 1))
      fail=1
    fi
  done
done

# Report the summary lines lint prints, so a run's numbers are in the log.
echo
echo "== lint summary =="
for r in "${REPORTS[@]}"; do
  [ -f "$r" ] || continue
  printf '%s: ' "$r"
  grep -E '^[0-9]+ errors?, [0-9]+ warnings?' "$r" | tail -1 || echo "(no summary line)"
done

# If a known error disappeared, say so. It is not a failure — the source was
# fixed and that is good — but the entry here is now dead weight and the
# ratchet should be tightened.
#
# The match is on "<basename>:<line>:<IssueId>" against the same normalised
# strings built above, rather than a second regex. The earlier version tried to
# rebuild the key with ${k##*:} and a hand-rolled grep pattern, which compared
# the ISSUE ID against the LINE NUMBER and so reported every known error as
# missing, every single run.
all_present=1
for k in "${KNOWN_ERRORS[@]}"; do
  found_it=0
  for r in "${REPORTS[@]}"; do
    [ -f "$r" ] || continue
    if grep -E ':[0-9]+: Error: ' "$r" \
        | sed -E 's#^.*/([^/]+\.kt):([0-9]+): Error: .*\[([^]]+)\]$#\1:\2:\3#' \
        | grep -qxF "$k"; then
      found_it=1
      break
    fi
  done
  if [ "$found_it" -eq 0 ]; then
    echo "note: no longer reported: $k"
    all_present=0
  fi
done
if [ "$all_present" -eq 0 ]; then
  echo
  echo "note: at least one documented error is no longer reported. If that is because"
  echo "      it was fixed, delete its line from KNOWN_ERRORS in this script so the"
  echo "      ratchet tightens. If it is because the file moved, update the line number."
fi

if [ "$fail" -ne 0 ]; then
  echo
  echo "::error::Android Lint reported $total_new error(s) that are not in the documented list in docs/ci/lint.md. New lint errors are build failures by design: lint ran for years without being run, and this is the check that stops it drifting further."
  exit 1
fi

echo
echo "OK: no new Android Lint errors. The 3 documented ones are unchanged."
echo "    See docs/ci/lint.md for why each is still open and what would clear it."
