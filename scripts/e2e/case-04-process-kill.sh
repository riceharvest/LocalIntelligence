#!/usr/bin/env bash
# Case 4: the process is killed mid-download. Reopening offers to continue, and it completes.
source "$(dirname "$0")/lib.sh"
case_begin 04 "Process kill mid-download: continue offered"

hubctl "rate=10000000"
fresh_install
launch
wait_text "Get started" 40 || fail "no first-run panel"
tap_text "Get started"
if wait_text "^Allow$" 10; then tap_text "Allow"; fi
wait_percent 25 150 >/dev/null || fail "never reached 25%"
adb shell am force-stop "$PKG"
sleep 3
kept="$(app_file_size "models/$LFM3_FILE.part")"
echo "  kept bytes after the kill: $kept"
(( kept > 0 )) || fail "nothing kept after the kill"
hub_log_clear
launch
wait_text "Continue setup" 40 || fail "reopening did not offer 'Continue setup'"
assert_no_banned_words
tap_text "Continue setup"
t0="$(now)"
until [[ "$(app_file_size "models/$LFM3_FILE")" == "$LFM3_BYTES" ]]; do
  (( $(now) - t0 < 240 )) || fail "never completed after continuing"
  sleep 3
done
start="$(hub_log | grep 'resolve' | grep -v 'bytes=0-' | sed -n 's/.*range=bytes=\([0-9]*\)-.*/\1/p' | head -1)"
[[ -n "$start" ]] && (( start >= kept )) || fail "continue did not resume from the kept bytes ('${start:-none}' vs $kept)"
wait_text "Ask anything" 120 || fail "no composer after completion"
case_end "kept $kept, resumed at $start"
