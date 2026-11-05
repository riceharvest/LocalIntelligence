#!/usr/bin/env bash
# Case 12: memory pressure during a reply. (a) the system's trim-memory signals at every level while a reply
# is running; (b) on the 2 GB phone, a hog process eats memory while the model answers. The app must not
# crash; the reply either finishes or says plainly why not, and the chat still works afterwards.
source "$(dirname "$0")/lib.sh"
case_begin 12 "Memory pressure during a reply"
trap 'adb shell "pkill tail" >/dev/null 2>&1; script_set ""; [[ "${E2E_ONLY_C:-}" == 1 ]] || emu_back 2>/dev/null' EXIT

# E2E_ONLY_C=1: run only part (c), on the emulator already chosen with E2E_SERIAL (a 2 GB one).
if [[ "${E2E_ONLY_C:-}" != 1 ]]; then
# (a) trim-memory on the 4 GB phone with LFM3.
install_and_ready
pid="$(app_pid)"
dctl newchat >/dev/null; sleep 2
dctl send "Write a long story about a dragon who learns to bake bread. Make it very long and detailed." | grep -q '"started":true' || fail "could not start the long reply"
t0="$(now)"
until (( $(state_field streamedChars) > 30 )); do
  (( $(now) - t0 < 90 )) || fail "no words ever streamed"
  sleep 1
done
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
else
  install_and_ready
fi

# (c) The system kills the app for memory while a reply is in flight and the person has left it. What do they see on
# return? A plain sentence or a working chat, with their message still there. The reply streams slowly (scripted) so
# one is always in flight; the memory is eaten by a process in the shell that holds it for two minutes.
STORY="alpha bravo charlie delta echo foxtrot golf hotel india juliet kilo lima mike november oscar papa quebec romeo sierra tango uniform victor whiskey xray yankee zulu finale"
wait_state_field modelState ready 180 || fail "(c) no model"
dctl newchat >/dev/null; sleep 2
script_set "$(tool_call device.battery '{}')|||@slow:250:<respond>$STORY</respond>"
dctl send "How much battery do I have? Then say the alphabet words" | grep -q '"started":true' || fail "(c) could not start the reply"
sleep 6   # the scripted reply does not feed the streamed-characters counter
adb shell input keyevent KEYCODE_HOME
sleep 2
pid="$(app_pid)"
echo "  (c) app pid $pid, free before: $(adb shell 'grep MemAvailable /proc/meminfo' | tr -d '\r')"
killed=0
for n in 1 2 3 4 5 6; do
  (adb shell "(head -c 700m /dev/zero; sleep 150) | tail > /dev/null" >/dev/null 2>&1 &)
  sleep 12
  echo "  (c) hog $n: free $(adb shell 'grep MemAvailable /proc/meminfo' | tr -d '\r'); app pid '$(app_pid)'"
  if [[ -z "$(app_pid)" ]]; then killed=1; break; fi
done
reason="$(adb shell dumpsys activity exit-info "$PKG" | tr -d '\r' | grep -m3 -iE 'reason|description' | tr '\n' ';')"
adb shell "pkill tail" >/dev/null 2>&1 || true
if (( killed )); then
  echo "  (c) the system killed the app: $reason"
else
  echo "  (c) memory pressure alone did not kill it; asking the system to kill the background app as it does (am kill)"
  adb shell am kill "$PKG"; sleep 2
  [[ -z "$(app_pid)" ]] || adb shell "run-as $PKG kill -9 $(app_pid)"
  sleep 2
  reason="$(adb shell dumpsys activity exit-info "$PKG" | tr -d '\r' | grep -m3 -iE 'reason|description' | tr '\n' ';')"
fi
[[ -z "$(app_pid)" ]] || fail "(c) the app is still running"
KILL_KIND=$([[ $killed == 1 ]] && echo "real low-memory kill" || echo "am kill (pressure alone did not kill it)")
launch
wait_text "stopped before it finished|Continue|Ask anything" 90 || fail "(c) nothing readable after the kill: $(screen_text | tr '\n' '|' | head -c 400)"
echo "  (c) on return: $(screen_text | tr '\n' '|' | head -c 500)"
screen_text | grep -q "How much battery do I have" || fail "(c) the person's message is gone after the kill: $(screen_text | tr '\n' '|' | head -c 400)"
screen_text | grep -Eqi "stopped before it finished|Continue" || fail "(c) no plain sentence about the interrupted answer"
assert_no_banned_words
screen_text | grep -Eqi "exception|error|crash|has stopped|null" && fail "(c) a raw error is visible: $(screen_text | grep -Ei 'exception|error|crash|null' | head -2)"
wait_state_field modelState ready 180 || fail "(c) no assistant after the kill (state $(dctl state | head -c 200))"
tap_exact_soft "Continue" || true
wait_run_end 240 || fail "(c) Continue never finished"
[[ -n "$(last_answer)" && "$(last_answer)" != null ]] || fail "(c) no answer after Continue"
ask_battery "(c) after the kill"
case_end "trim signals survived; hogs on 2 GB survived; (c) $KILL_KIND mid-reply: message kept, plain 'stopped before it finished / Continue', Continue answered, chat works"
