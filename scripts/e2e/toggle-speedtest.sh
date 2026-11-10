#!/usr/bin/env bash
# Runs the in-app Speed test with one Settings switch off or on, and records the result.
# It is the driver behind the "Speculative decoding" section of docs/measurements.md.
#
#   toggle-speedtest.sh off 1     one run with the switch off, as run number 1
#   toggle-speedtest.sh on 1      one run with the switch on, as run number 1
#   toggle-speedtest.sh loop 8    8 pairs, interleaved: off 1, on 1, off 2, on 2, ...
#
# One run does these steps and changes nothing else in the app:
#   1. Cold-starts the app and opens Settings.
#   2. Sets the switch to what the arm asks for, and reads it back from the screen.
#   3. Reloads the model, because the setting is read at load and not during a reply.
#   4. Taps Speed test, waits for both passes, and reads the numbers off the screen.
#
# Results: one TSV row per run is appended to $E2E_RESULTS. The columns are
#   arm  run  decode  prefill  total_pss  status
# Decode and prefill are read off the on-screen result line ("Prefill N tok/s · Decode N tok/s"),
# not from a log. The app logs neither number. total_pss is the app's TOTAL PSS in kB after the
# reload. status is the result line, or the error text the screen showed. A run that gave no
# number is still recorded, with FAIL in both number columns. Read the table with
# toggle-speedtest-stats.py.
#
# Exit codes: 0 the run is recorded; 1 a step failed (the sentence on stderr says which);
# 2 bad arguments; 3 the run finished but the screen gave no number (the FAIL row is written).
# The loop skips a run that exits 3 and stops on any other failure.
#
# Settings (environment):
#   E2E_SERIAL         the emulator to drive (default emulator-5554)
#   E2E_OUT            where ui.xml and the results go (default /tmp/li-e2e-spec)
#   E2E_RESULTS        the TSV file (default $E2E_OUT/results.tsv)
#   LI_PKG (or PKG)    the app package (default: lib.sh's full flavor)
#   E2E_MODEL          the model file to reload (default MiniCPM5-1B-Q4_K_M.gguf)
#   E2E_TOGGLE_LABEL   the label of the Settings switch (default "Faster replies (experimental)")
#
# The model must already be on the emulator, and the app must have finished its first run.
# This script never starts the emulator. Start it with emu_start from lib.sh.
# The swipe and tap coordinates assume the li-e2e display, 1080x2400. Nothing here is tuned for
# another screen size.
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT_DEFAULT="${E2E_OUT:-/tmp/li-e2e-spec}"

usage() {
  # Prints the comment block at the top of this file.
  sed -n '/^set -uo/q; /^#!/d; /^#/s/^# \{0,1\}//p' "${BASH_SOURCE[0]}" >&2
  exit 2
}

# The loop only calls this script again for each run. It needs no emulator access of its own.
if [[ "${1:-}" == loop ]]; then
  PAIRS="${2:-}"
  [[ "$PAIRS" =~ ^[1-9][0-9]*$ ]] || { echo "toggle-speedtest: 'loop' needs a number of pairs, for example: loop 8" >&2; exit 2; }
  for i in $(seq 1 "$PAIRS"); do
    for arm in off on; do
      "$HERE/toggle-speedtest.sh" "$arm" "$i"
      rc=$?
      if (( rc == 3 )); then
        echo "toggle-speedtest: $arm run $i gave no number. Its FAIL row is recorded. The loop goes on." >&2
      elif (( rc != 0 )); then
        echo "toggle-speedtest: $arm run $i failed (exit $rc). The loop stops here." >&2
        exit "$rc"
      fi
    done
  done
  exit 0
fi

ARM="${1:-}"
RUN="${2:-}"
[[ "$ARM" == on || "$ARM" == off ]] || usage
[[ "$RUN" =~ ^[0-9]+$ ]] || usage

# The guard is off, as in the other entry points. The adb wrapper in lib.sh still refuses any
# serial that is not emulator-NNNN.
export E2E_NO_GUARD=1 E2E_OUT="$OUT_DEFAULT" E2E_SERIAL="${E2E_SERIAL:-emulator-5554}"
export LI_PKG="${LI_PKG:-${PKG:-}}"
# shellcheck source=lib.sh
source "$HERE/lib.sh" || { echo "toggle-speedtest: lib.sh did not load." >&2; exit 1; }
unset E2E_NO_GUARD

MODEL="${E2E_MODEL:-MiniCPM5-1B-Q4_K_M.gguf}"
TOGGLE="${E2E_TOGGLE_LABEL:-Faster replies (experimental)}"
RESULTS="${E2E_RESULTS:-$OUT/results.tsv}"
WANT=false
[[ "$ARM" == on ]] && WANT=true

die() { echo "toggle-speedtest: $*" >&2; exit 1; }

uiv() { python3 -I "$HERE/toggle-speedtest-ui.py" "$@"; }

# The switch in the toggle's row, read off the screen: "x y checked=STATE".
switch_row() {
  ui_dump || return 1
  uiv switch "$OUT/ui.xml" "$PKG" "$TOGGLE" 2>&1 | tail -1
}

# Rolls the Settings page back to its top, whatever position it was left in.
scroll_to_top() {
  local i
  for i in $(seq 1 8); do adb shell input swipe 540 800 540 1800 250; sleep 0.35; done
}

# Puts the row that says LABEL in the middle of the screen, and leaves its dump in $OUT/ui.xml.
# A tap near the bottom edge lands on the bottom navigation bar and does nothing. Near the top,
# the row can sit half under the app bar.
center_row() {
  local want="$1" y i
  for i in $(seq 1 24); do
    ui_dump || true
    y="$(uiv row "$OUT/ui.xml" "$PKG" "$want" 2>/dev/null)" || y=""
    if [[ -n "$y" ]]; then
      if (( y > 1750 )); then adb shell input swipe 540 1500 540 900 300; sleep 0.6; continue; fi
      if (( y < 300 )); then adb shell input swipe 540 900 540 1500 300; sleep 0.6; continue; fi
      return 0
    fi
    adb shell input swipe 540 1900 540 1000 300
    sleep 0.5
  done
  return 1
}

# Opens the Settings page. A cold start answers on the debug channel before the screen has any
# nodes, and a dump taken then has nothing to tap. So this waits for the nav node to appear.
open_settings() {
  local attempt i xy x y
  for attempt in 1 2 3 4; do
    on_screen 'Skills' && return 0
    xy=""
    for i in $(seq 1 20); do
      ui_dump || true
      xy="$(uiv nav "$OUT/ui.xml" "$PKG" 2>/dev/null)" && break
      sleep 1.5
    done
    [[ -n "$xy" ]] || { echo "  no Settings item in the dump (attempt $attempt)"; continue; }
    read -r x y <<<"$xy"
    adb shell input tap "$x" "$y"
    sleep 2
    # 'Skills' is a section row near the top of the Settings page. It is on no other screen.
    on_screen 'Skills' && return 0
    echo "  tapped $xy but Settings did not open (attempt $attempt)"
  done
  return 1
}

echo "== $ARM run $RUN"

# Step 1. Start cold, so the Settings page opens at its top every time. A warm start opens it
# where the last run left it, and the search for the row then fails.
adb shell am force-stop "$PKG" >/dev/null 2>&1
sleep 1
launch
sleep 5
t0="$(now)"
until dctl state 2>/dev/null | grep -q '"ok":true'; do
  (( $(now) - t0 < 120 )) || die "The app did not answer on its debug channel within 120 s."
  sleep 3
done
open_settings || die "Settings did not open."

# Step 2. Put the switch where the arm wants it, and read it back from the screen.
scroll_to_top
center_row "$TOGGLE" || die "The row '$TOGGLE' is not on the Settings page of this build."
row="$(switch_row)"
[[ "$row" =~ ^[0-9]+\ [0-9]+\ checked= ]] || die "The switch in the '$TOGGLE' row is not readable: $row"
cur="${row##*checked=}"
echo "  setting row: $row"
if [[ "$cur" != "$WANT" ]]; then
  for try in 1 2; do
    read -r x y _ <<<"$row"
    adb shell input tap "$x" "$y"
    sleep 1
    center_row "$TOGGLE"
    row="$(switch_row)"
    cur="${row##*checked=}"
    echo "  tapped the switch ($try) -> $row"
    [[ "$cur" == "$WANT" ]] && break
  done
  [[ "$cur" == "$WANT" ]] || die "The switch did not move to $WANT after two taps."
fi

# Step 3. Reload the model, so the app reads the setting. The app logs under the tag LIControl,
# and its LOADED line means the load finished. The log is cleared first, so a LOADED line from an
# earlier load cannot count.
adb logcat -c
dctl usemodel "$MODEL" >/dev/null 2>&1
t0="$(now)"
until adb logcat -d -s LIControl 2>/dev/null | grep -q 'LOADED'; do
  (( $(now) - t0 < 180 )) || die "No LOADED line in 180 s. The model '$MODEL' did not reload."
  sleep 2
done
t0="$(now)"
while [[ "$(state_field modelState)" != ready ]]; do
  (( $(now) - t0 < 180 )) || die "The model did not reach the ready state in 180 s."
  sleep 2
done
# adb ends its lines with carriage returns. Strip them, or the number is not a number.
# Memory is recorded for reference only. In the 2026-11-10 run it did not follow the switch in a
# useful way (lower with the switch on), so it is not a sign that the draft path ran.
PSS="$(adb shell dumpsys meminfo "$PKG" | tr -d '\r' | awk '/TOTAL PSS:/{print $3; exit}')"
echo "  reloaded (LOADED seen); TOTAL PSS ${PSS:-?} kB; state=$(dctl state)"

# Step 4. The Speed test button is further down the same page.
center_row 'Speed test' || die "There is no Speed test row on the Settings page."
xy="$(uiv tap "$OUT/ui.xml" "$PKG" 'Speed test' 2>/dev/null)"
[[ -n "$xy" ]] || die "There is no clickable Speed test button on screen."
read -r x y <<<"$xy"
adb shell input tap "$x" "$y"
echo "  tapped Speed test at $xy"

# The screen shows "Speed test, pass N" while a pass runs. Wait for it to appear, then to go.
t0="$(now)"
until ui_dump && ui has "$OUT/ui.xml" 'Speed test, pass' --pkg "$PKG" 2>/dev/null; do
  if (( $(now) - t0 < 40 )); then sleep 2; continue; fi
  # Nothing started. Print what the screen says, so a stale result is never read.
  ui_dump || true
  ui texts "$OUT/ui.xml" --pkg "$PKG" | grep -E 'Speed test|An agent run|Load a model' | head -3
  die "The Speed test did not start within 40 s of the tap."
done
t1="$(now)"
while ui_dump && ui has "$OUT/ui.xml" 'Speed test, pass' --pkg "$PKG" 2>/dev/null; do
  (( $(now) - t1 < 900 )) || die "The Speed test was still running after 900 s."
  sleep 5
done

ui_dump || die "The finished screen could not be dumped."
row="$(uiv result "$OUT/ui.xml" "$PKG" "$ARM" "$RUN" "$PSS")" || die "The result screen could not be read."
printf '%s\n' "$row" >> "$RESULTS"
if [[ "$(cut -f3 <<<"$row")" == FAIL ]]; then
  echo "toggle-speedtest: $ARM run $RUN gave no number. The FAIL row is in $RESULTS." >&2
  exit 3
fi
echo "  row appended to $RESULTS"
