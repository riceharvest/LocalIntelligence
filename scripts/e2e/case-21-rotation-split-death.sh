#!/usr/bin/env bash
# Case 21: the screen changes shape or the process dies with a reply in flight. Same slow scripted reply as case 20.
# (a) rotate to landscape and back: the reply goes on, once, whole. (b) split screen with another app: the chat
# keeps running and stays usable. (c) the system kills the app mid-reply: the next launch offers to continue,
# and Continue gets an answer.
source "$(dirname "$0")/lib.sh"
case_begin 21 "Rotation, split screen, process death mid-run"
trap 'script_set ""; adb shell settings put system accelerometer_rotation 1; adb shell settings put system user_rotation 0; adb shell wm size reset >/dev/null 2>&1' EXIT
install_and_ready

STORY="alpha bravo charlie delta echo foxtrot golf hotel india juliet kilo lima mike november oscar papa quebec romeo sierra tango uniform victor whiskey xray yankee zulu finale"
SLOW="@slow:250:<respond>$STORY</respond>"
start_slow() {
  dctl newchat >/dev/null; sleep 2
  script_set "$SLOW"
  dctl send "$1" | grep -q '"started":true' || fail "could not start: $1"
  # The scripted reply does not feed the streamed-characters counter, so give it a few seconds to be mid-way.
  sleep 6
}

echo "--- (a) rotation"
start_slow "Tell me the alphabet words"
pid="$(app_pid)"
adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 1
sleep 4
sz="$(adb shell wm size | tr -d '\r' | tail -1)"
rot="$(adb shell dumpsys window | tr -d '\r' | grep -m1 -o 'mRotation=[0-9]\|rotation=[0-9]')"
echo "  landscape: $rot"
[[ "$(app_pid)" == "$pid" ]] || fail "the app restarted on rotation"
[[ "$(state_field running)" == true ]] || fail "the run stopped on rotation"
ui_dump; ui has "$OUT/ui.xml" "alpha|Stop" || fail "the chat is not on screen in landscape"
echo "  landscape screen: $(screen_text | tr '\n' '|' | head -c 200)"
adb shell settings put system user_rotation 0
sleep 3
wait_run_end 90 || fail "the run did not finish after rotating back"
wait_text "finale" 30 || fail "the reply is not whole after rotating: $(screen_text | tr '\n' '|' | head -c 300)"
[[ "$(screen_text | grep -c 'alpha bravo')" == 1 ]] || fail "the reply shows twice after rotation"
[[ "$(state_field messages)" == 2 ]] || fail "expected 2 messages, found $(state_field messages)"
echo "  whole reply, once, after rotating there and back"

echo "--- (b) split screen (a half-height window)"
# NOT a real split: this emulator image offers none. Tried: am start --windowingMode 3, 4 and 6 (the app goes
# full screen or into a single-window mode), and the launcher's recents menu (only "App info"; no "Split screen").
# What a split does to an app is a smaller window and a configuration change, so the display is made half as
# tall (wm size) while a reply streams, and then restored. This proves the chat survives a resize; it does not
# prove the system's split-screen mechanics.
start_slow "Tell me the alphabet words again"
pid="$(app_pid)"
adb shell wm size 1080x1200
sleep 4
[[ "$(app_pid)" == "$pid" ]] || fail "the app restarted when the window got half as tall"
[[ "$(state_field running)" == true ]] || fail "the run stopped when the window got half as tall"
ui_dump; ui has "$OUT/ui.xml" "Ask anything|Message" || fail "the message box is not on screen in a half-height window: $(screen_text | tr '\n' '|' | head -c 300)"
echo "  half-height: $(screen_text | tr '\n' '|' | head -c 200)"
ui small "$OUT/ui.xml" --pkg "$PKG" --density 420 >/dev/null || echo "  (small targets in the half-height window: $(ui small "$OUT/ui.xml" --pkg "$PKG" --density 420 | tr '\n' ';'))"
adb shell wm size reset
sleep 3
wait_run_end 90 || fail "the run did not finish after the window came back"
wait_text "finale" 30 || fail "the reply is not whole after the resize: $(screen_text | tr '\n' '|' | head -c 300)"
[[ "$(screen_text | grep -c 'alpha bravo')" == 1 ]] || fail "the reply shows twice after the resize"

echo "--- (c) the process dies mid-reply"
# The process dies after a tool step finished (that is when the run's progress is written) and while the reply
# streams. A bare streaming reply is covered by case 11 with the real model.
dctl newchat >/dev/null; sleep 2
script_set "$(tool_call device.battery '{}')|||$SLOW"
dctl send "How much battery do I have? Then say the alphabet words" | grep -q '"started":true' || fail "could not start"
sleep 10
pid="$(app_pid)"
adb shell "run-as $PKG kill -9 $pid"
sleep 2
[[ -z "$(app_pid)" ]] || fail "the app survived kill -9"
launch
wait_text "stopped before it finished|Continue" 60 || fail "no offer to continue after the process died: $(screen_text | tr '\n' '|' | head -c 400)"
echo "  offer: $(screen_text | grep -Ei 'stopped before it finished|Continue' | head -2 | tr '\n' '|')"
assert_no_banned_words
wait_state_field modelState ready 180 || fail "no model after the process died"
before="$(last_answer)"
tap_exact_soft "Continue" || tap_text "Continue"
sleep 3
wait_run_end 240 || fail "Continue never finished"
after="$(last_answer)"
echo "  answer after Continue: ${after:0:200}"
[[ -n "$after" && "$after" != null ]] || fail "Continue gave no answer"
on_screen "stopped before it finished" && fail "the offer is still on screen after Continue"
case_end "rotation keeps the reply whole and once; half-height window keeps the run (not a real split, see the case); process death offers Continue and Continue answers"
