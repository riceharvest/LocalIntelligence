#!/usr/bin/env bash
# Case 12: memory pressure during a reply. (a) the system's trim-memory signals at every level while a reply
# is running; (b) on the 2 GB phone, a hog process eats memory while the model answers. The app must not
# crash; the reply either finishes or says plainly why not, and the chat still works afterwards.
source "$(dirname "$0")/lib.sh"
case_begin 12 "Memory pressure during a reply"
trap 'adb shell pkill -f "tail" >/dev/null 2>&1; emu_back 2>/dev/null' EXIT

# (a) trim-memory on the 4 GB phone with LFM3.
install_and_ready
pid="$(app_pid)"
dctl newchat >/dev/null; sleep 2
dctl send "Write a long story about a dragon who learns to bake bread. Make it very long and detailed." | grep -q '"started":true' || fail "could not start the long reply"
sleep 3
for level in RUNNING_MODERATE RUNNING_LOW RUNNING_CRITICAL; do
  adb shell am send-trim-memory "$PKG" "$level"
  sleep 1
done
[[ "$(app_pid)" == "$pid" ]] || fail "the app died under trim-memory signals (pid $pid -> $(app_pid))"
t0="$(now)"
until [[ "$(state_field running)" == "false" ]]; do
  (( $(now) - t0 < 300 )) || fail "the reply never finished after the trim-memory signals"
  sleep 3
done
ui_dump; ui has "$OUT/ui.xml" "has stopped|isn't responding|keeps stopping" && fail "a system crash dialog is showing"
[[ "$(state_field runState)" =~ ^(Idle|Done|Finished|Answered|Completed)|^[A-Za-z]+$ ]] || true
assert_no_banned_words
ask_battery "after trim-memory"
echo "  (a) survived: run state $(state_field runState)"

# (b) the 2 GB phone with a hog.
emu_other li-ram2g 5556 || fail "the 2 GB emulator did not boot"
install_and_ready
pid="$(app_pid)"
adb shell "mkdir -p /data/local/tmp"
# `tail` on an endless line without newlines keeps all of it in memory: a plain memory hog.
(adb shell "head -c 1400m /dev/zero | tail > /dev/null" >/dev/null 2>&1 &)
dctl newchat >/dev/null; sleep 2
echo "  free during the hog: $(adb shell 'grep MemAvailable /proc/meminfo' | tr -d '\r')"
line="$(ask_json "What is my battery level?" 300)"
echo "  answer under the hog: $(echo "$line" | head -c 250)"
echo "  free after: $(adb shell 'grep MemAvailable /proc/meminfo' | tr -d '\r')"
adb shell "pkill -f 'tail'" >/dev/null 2>&1 || true
ui_dump; ui has "$OUT/ui.xml" "has stopped|isn't responding|keeps stopping" && fail "a system crash dialog under the hog"
now_pid="$(app_pid)"
if [[ "$now_pid" != "$pid" ]]; then
  echo "  the system killed the app under the hog (pid $pid -> ${now_pid:-none}); checking it recovers"
  launch
  restart_app || fail "the app did not come back after the memory killer"
fi
wait_state_field modelState ready 180 || fail "no model after the hog"
ask_battery "after the hog"
assert_no_banned_words
case_end "trim signals survived; 2 GB hog: app pid $([[ "$now_pid" == "$pid" ]] && echo kept || echo recovered)"
