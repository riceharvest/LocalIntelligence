#!/usr/bin/env bash
# Case 16: a run that needs access while no screen is on. Nobody can be asked, so the tool takes its
# fallback at once and the run ends with an answer. It must not hang for the 120 s the dialog would wait.
source "$(dirname "$0")/lib.sh"
case_begin 16 "Background run with no screen: fallback, no 120 s hang"
trap 'adb shell input keyevent KEYCODE_WAKEUP; adb shell wm dismiss-keyguard >/dev/null 2>&1' EXIT

install_and_ready

bg_run() {
  local name="$1" perm="$2" question="$3" how="$4" limit=100
  echo "--- $name ($how)"
  adb shell pm clear-permission-flags "$PKG" "$perm" user-set user-fixed >/dev/null 2>&1; adb shell pm revoke "$PKG" "$perm" >/dev/null 2>&1
  restart_app || fail "$name: the app did not restart"
  wait_state_field modelState ready 120 || fail "$name: no model"
  dctl newchat >/dev/null; sleep 2
  # Put the screen away BEFORE the run starts, so no activity is resumed when the tool asks.
  if [[ "$how" == screen-off ]]; then
    adb shell input keyevent KEYCODE_SLEEP
  else
    adb shell input keyevent KEYCODE_HOME
  fi
  sleep 3
  adb shell dumpsys window | tr -d '\r' | grep -E "mCurrentFocus" | head -1
  local t0; t0="$(now)"
  dctl send "$question" | grep -q '"started":true' || fail "$name: not started with the screen away"
  until [[ "$(state_field running)" == "false" ]]; do
    (( $(now) - t0 < 110 )) || fail "$name: still running after 110 s with no screen (hung waiting for an answer nobody can give?)"
    sleep 2
  done
  local took=$(( $(now) - t0 ))
  echo "  run ended after ${took}s"
  (( took < limit )) || fail "$name: took ${took}s, close to the 120 s wait"
  perm_granted "$perm" && fail "$name: the permission was granted by itself"
  local answer; answer="$(last_answer)"
  echo "  answer: $answer"
  echo "  steps: $(tool_steps | tr '\n' ';' | head -c 400)"
  [[ -n "$answer" && "$answer" != null ]] || fail "$name: no answer"
  tool_steps | grep -qi "${5:-.}" || fail "$name: the tool did not run"
  adb shell input keyevent KEYCODE_WAKEUP; adb shell wm dismiss-keyguard >/dev/null 2>&1
  launch; sleep 2
  on_screen "I need|to look at your calendar|approximate location|read your contacts" && fail "$name: the access sentence appeared after the fact"
}

bg_run contacts android.permission.READ_CONTACTS "Do I have a contact called Anna?" screen-off contacts
bg_run calendar android.permission.READ_CALENDAR "What is on my calendar tomorrow?" screen-off calendar
bg_run location android.permission.ACCESS_COARSE_LOCATION "Where am I right now?" home location
assert_no_banned_words
case_end "no screen: fallback within the limit for contacts, calendar, location"
