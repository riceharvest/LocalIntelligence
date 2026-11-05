#!/usr/bin/env bash
# Case 11: the process is killed with SIGKILL (a native crash or the system's memory killer gives no
# exception). (a) inside the model load: the next launch starts one rung lower and does not loop.
# (b) in the middle of a reply: the next launch shows the interrupted sentence in plain words, steps
# down, and the chat works again.
source "$(dirname "$0")/lib.sh"
case_begin 11 "Crash marker: kill -9 in load and in a reply"
trap 'debug_file debug-load-hold-ms ""' EXIT

install_and_ready
[[ "$(state_field loadRung)" == "0" ]] || fail "start rung is not 0"

# --- (a) kill inside the load. The hold keeps the load open after the marker is written.
debug_file debug-load-hold-ms 60000
adb shell am force-stop "$PKG"; sleep 1
launch
t0="$(now)"
until adb shell "run-as $PKG ls files/model-load-attempt" 2>/dev/null | grep -q model-load-attempt; do
  (( $(now) - t0 < 60 )) || fail "the load marker was never written"
  sleep 1
done
echo "  marker: $(adb shell "run-as $PKG cat files/model-load-attempt" | tr -d '\r')"
pid="$(app_pid)"
adb shell "run-as $PKG kill -9 $pid"
sleep 2
[[ -z "$(app_pid)" ]] || fail "the app survived kill -9"
debug_file debug-load-hold-ms ""
launches=0
restart_app || fail "the app did not come back after the kill"
wait_state_field modelState ready 120 || fail "no model after a crash during load (state: $(dctl state | head -c 300))"
rung="$(state_field loadRung)"
echo "  rung after the crash in load: $rung"
[[ "$rung" == "1" ]] || fail "expected one rung lower (1), got '$rung'"
pid2="$(app_pid)"
sleep 15
[[ "$(app_pid)" == "$pid2" ]] || fail "the app restarted by itself: a launch loop"
assert_no_banned_words
ask_battery "after the load crash"

# --- (b) kill in the middle of a reply.
dctl newchat >/dev/null; sleep 2
dctl send "Write a long story about a dragon who learns to bake bread. Make it very long and detailed." | grep -q '"started":true' || fail "could not start the long reply"
t0="$(now)"
until [[ "$(state_field running)" == "true" ]]; do
  (( $(now) - t0 < 20 )) || fail "the reply never started"
  sleep 0.5
done
sleep 4   # inside generation
[[ "$(state_field running)" == "true" ]] || fail "the reply ended before the kill: use a longer one"
pid="$(app_pid)"
adb shell "run-as $PKG kill -9 $pid"
sleep 2
[[ -z "$(app_pid)" ]] || fail "the app survived kill -9 in the reply"
launch
wait_text "stopped before it finished|was interrupted|Continue" 60 || fail "no interrupted sentence after a kill in a reply: $(screen_text | tr '\n' '|' | head -c 400)"
shown="$(screen_text | grep -Ei 'stopped before it finished|interrupted' | head -1)"
echo "  shown: $shown"
assert_no_banned_words
wait_state_field modelState ready 120 || fail "no model after the reply crash"
rung="$(state_field loadRung)"
echo "  rung after the crash in a reply: $rung"
[[ "$rung" == "2" ]] || fail "expected a step down to rung 2 after the reply crash, got '$rung'"
pid3="$(app_pid)"
sleep 10
[[ "$(app_pid)" == "$pid3" ]] || fail "launch loop after the reply crash"
# Dismiss the offer if it is a separate card, then the chat must work.
tap_text_soft "Dismiss" || true
ask_battery "after the reply crash"
case_end "load crash -> rung 1, reply crash -> rung 2; sentence: $shown"
