#!/usr/bin/env bash
# Case 20: a reply in flight survives what a person does to the phone. The reply is scripted and streams slowly
# (one character every 0.25 s, 40 s in all) so there is always a reply to interrupt; everything around it is the
# app's own. (a) Stop in the middle, then send again: works. (b) Screen off and on in the middle: the reply goes on
# and arrives whole. (c) Home in the middle: it finishes while the app is away, and the answer is there on return.
source "$(dirname "$0")/lib.sh"
case_begin 20 "Stop then send again; screen off and on; app in the background"
trap 'script_set ""; adb shell input keyevent KEYCODE_WAKEUP; adb shell wm dismiss-keyguard >/dev/null 2>&1' EXIT
install_and_ready

STORY="alpha bravo charlie delta echo foxtrot golf hotel india juliet kilo lima mike november oscar papa quebec romeo sierra tango uniform victor whiskey xray yankee zulu finale"
SLOW="@slow:250:<respond>$STORY</respond>"

start_slow() { # question
  dctl newchat >/dev/null; sleep 2
  script_set "$SLOW"
  dctl send "$1" | grep -q '"started":true' || fail "could not start: $1"
  # The scripted reply does not feed the streamed-characters counter, so give it a few seconds to be mid-way.
  sleep 6
  [[ "$(state_field running)" == "true" ]] || fail "the reply ended before the test could act"
}

echo "--- (a) Stop in the middle, then send again"
start_slow "Tell me the alphabet words"
tap_exact_soft "Stop" || fail "no Stop button while the reply streams: $(screen_text | tr '\n' '|' | head -c 300)"
wait_run_end 30 || fail "the run did not stop after Stop"
sleep 2
echo "  after Stop: $(screen_text | tr '\n' '|' | head -c 300)"
screen_text | grep -Eqi "stopped" || fail "Stop left no plain 'Stopped' line"
[[ "$(state_field modelState)" == ready ]] || fail "the assistant is not ready after Stop (state $(state_field modelState))"
assert_no_banned_words
script_set '<respond>Second answer works.</respond>'
dctl send "And now a second question" | grep -q '"started":true' || fail "could not send again after Stop"
wait_text "Second answer works" 60 || fail "no answer to the question sent after Stop: $(screen_text | tr '\n' '|' | head -c 300)"
echo "  second question answered"

echo "--- (b) screen off and on in the middle"
start_slow "Tell me the alphabet words again"
adb shell input keyevent KEYCODE_SLEEP
sleep 8
mid="$(state_field streamedChars)"
echo "  chars streamed with the screen off for 8 s: $mid"
adb shell input keyevent KEYCODE_WAKEUP; adb shell wm dismiss-keyguard >/dev/null 2>&1
sleep 1
wait_run_end 90 || fail "the run did not finish after the screen came back"
wait_text "finale" 30 || fail "the reply is not whole after screen off/on: $(screen_text | tr '\n' '|' | head -c 400)"
[[ "$(screen_text | grep -c 'alpha bravo')" == 1 ]] || fail "the reply shows twice or not at all"
echo "  whole reply on screen after screen off/on"

echo "--- (c) Home in the middle: it finishes, the answer is there on return"
start_slow "Tell me the alphabet words one more time"
adb shell input keyevent KEYCODE_HOME
sleep 3
adb shell dumpsys window | tr -d '\r' | grep -E "mCurrentFocus" | head -1
t0="$(now)"
until [[ "$(state_field running)" == "false" ]]; do
  (( $(now) - t0 < 90 )) || fail "the run never finished while the app was in the background"
  sleep 3
done
echo "  finished in the background after $(( $(now) - t0 )) s"
echo "  notifications: $(adb shell dumpsys notification --noredact | tr -d '\r' | grep -A12 "pkg=$PKG" | grep -E "android.title|android.text|channel" | head -6 | tr '\n' '|')"
launch
wait_text "finale" 30 || fail "the answer is not on screen on return: $(screen_text | tr '\n' '|' | head -c 400)"
case_end "Stop then send works; screen off/on keeps the reply whole; Home mid-run finishes and the answer is there on return"
