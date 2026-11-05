#!/usr/bin/env bash
# Case 6: too little storage. "Free up X", and no download starts. The emulator is the throwaway
# li-e2e AVD: its data partition is filled with a ballast file and the file is removed afterwards.
source "$(dirname "$0")/lib.sh"
case_begin 06 "Too little storage: Free up X, no download"
trap 'adb shell rm -f /data/local/tmp/ballast >/dev/null 2>&1' EXIT

fresh_install
free="$(data_free_bytes)"
want_free=$(( 300 * 1024 * 1024 ))
fill=$(( free - want_free ))
echo "  free before: $free, filling $fill"
adb shell "fallocate -l $fill /data/local/tmp/ballast || head -c $fill /dev/zero > /data/local/tmp/ballast"
echo "  free after: $(data_free_bytes)"
hub_log_clear
launch
wait_text "Get started" 40 || fail "no first-run panel"
tap_text "Get started"
if wait_text "^Allow$" 8; then tap_text "Allow"; fi
wait_text "free up|storage" 30 || fail "no 'free up' sentence for a full phone"
echo "  shown: $(screen_text | grep -i 'free up\|storage' | head -2 | tr '\n' '|')"
screen_text | grep -Eqi "[0-9.,]+ ?(MB|GB)" || fail "the sentence does not say how much to free up"
assert_no_banned_words
hub_log | grep -q "resolve" && fail "a download request reached the hub on a full phone"
[[ "$(app_file_size "models/$LFM3_FILE.part")" == "0" ]] || fail "bytes were written on a full phone"
case_end "refused before any download"
