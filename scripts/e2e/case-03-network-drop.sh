#!/usr/bin/env bash
# Case 3: the network drops at about 30%. The download resumes from the bytes it kept (a Range request
# starting at the kept size, not 0) and reaches 100%.
source "$(dirname "$0")/lib.sh"
case_begin 03 "Network drop at 30% resumes from kept bytes"
trap 'airplane_off >/dev/null 2>&1' EXIT

hubctl "rate=10000000"
fresh_install
launch
wait_text "Get started" 40 || fail "no first-run panel"
tap_text "Get started"
if wait_text "^Allow$" 10; then tap_text "Allow"; fi
wait_percent 30 150 >/dev/null || fail "never reached 30%"
airplane_on
sleep 5
kept="$(app_file_size "models/$LFM3_FILE.part")"
echo "  kept bytes at the drop: $kept"
(( kept > LFM3_BYTES / 5 )) || fail "too little kept ($kept)"
hub_log_clear
airplane_off
t0="$(now)"
until [[ "$(app_file_size "models/$LFM3_FILE")" == "$LFM3_BYTES" ]]; do
  (( $(now) - t0 < 240 )) || fail "never reached 100% after the drop (final file $(app_file_size "models/$LFM3_FILE"), part $(app_file_size "models/$LFM3_FILE.part"))"
  sleep 3
done
# The file request after the drop must start at or beyond what was kept.
start="$(hub_log | grep 'resolve' | grep -v 'bytes=0-' | sed -n 's/.*range=bytes=\([0-9]*\)-.*/\1/p' | head -1)"
echo "  resume offset in the hub log: ${start:-none}"
[[ -n "$start" ]] && (( start >= kept )) || fail "the download did not resume from the kept bytes (offset '${start:-none}', kept $kept)"
case_end "kept $kept, resumed at $start, finished"
