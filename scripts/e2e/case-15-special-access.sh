#!/usr/bin/env bash
# Case 15: special access (Do Not Disturb, notification listener, exact alarms). The person reads one sentence,
# taps Open settings, switches the app on in the real Settings page (driven by uiautomator), comes back, and the
# run carries on and answers. And "Not now": the run ends plainly and nothing is switched on.
source "$(dirname "$0")/lib.sh"
case_begin 15 "Special access: Settings page round trip, and Not now"
COMPONENT="$PKG/dev.localintelligence.android.tools.notifications.LocalNotificationListenerService"

install_and_ready

foreground() { adb shell dumpsys window | tr -d '\r' | sed -n 's/.*mCurrentFocus=.* \([a-zA-Z0-9_.]*\)\/.*/\1/p' | head -1; }

# Presses Back until this app is in front again (at most 5 times).
back_to_app() {
  local i
  for i in 1 2 3 4 5; do
    [[ "$(foreground)" == "$PKG" ]] && return 0
    adb shell input keyevent KEYCODE_BACK
    sleep 1.5
  done
  [[ "$(foreground)" == "$PKG" ]]
}

# access_on KEY: true when the special access is on now.
access_on() {
  case "$1" in
    dnd) [[ "$(adb shell settings get secure enabled_notification_policy_access_packages | tr -d '\r')" == *"$PKG"* ]] ;;
    listener) [[ "$(adb shell settings get secure enabled_notification_listeners | tr -d '\r')" == *"$PKG"* ]] ;;
    alarm) [[ "$(adb shell cmd appops get "$PKG" SCHEDULE_EXACT_ALARM | tr -d '\r')" == *allow* ]] ;;
  esac
}

access_off() {
  case "$1" in
    dnd) adb shell cmd notification disallow_dnd "$PKG" >/dev/null 2>&1 ;;
    listener) adb shell cmd notification disallow_listener "$COMPONENT" >/dev/null 2>&1 ;;
    alarm) adb shell cmd appops set "$PKG" SCHEDULE_EXACT_ALARM deny >/dev/null 2>&1 ;;
  esac
}

# one_access KEY QUESTION SENTENCE_RX PAGE_RX
one_access() {
  local key="$1" question="$2" sentence="$3" page="$4"
  echo "--- $key: allow"
  access_off "$key"; access_on "$key" && fail "$key: still on after switching it off"
  restart_app || fail "$key: no restart"
  wait_state_field modelState ready 120 || fail "$key: no model"
  send_q "$question"
  wait_text "$sentence" 150 || fail "$key: no access sentence (steps: $(tool_steps | tr '\n' ';' | head -c 300))"
  echo "  sentence: $(screen_text | grep -Ei "$sentence" | head -1)"
  assert_no_banned_words
  tap_text "Open settings"
  wait_text "$page" 30 || fail "$key: the Settings page never opened ($(screen_text_all | tr '\n' '|' | head -c 200))"
  echo "  page: $(screen_text_all | head -3 | tr '\n' '|')"
  # Some pages list apps first; the app's row opens its own page with the switch.
  if ! ui_dump || ! ui switch "$OUT/ui.xml" >/dev/null 2>&1; then
    tap_text "LocalIntelligence" || true
    sleep 2
  fi
  tap_switch
  sleep 2
  # The confirmation Android adds for the sensitive ones.
  tap_text_soft "Allow" || tap_text_soft "OK" || true
  sleep 2
  access_on "$key" || fail "$key: the switch did not turn the access on ($(screen_text_all | tr '\n' '|' | head -c 300))"
  back_to_app || fail "$key: could not get back to the app"
  wait_run_end 240 || fail "$key: the run never ended after coming back"
  local answer; answer="$(last_answer)"
  echo "  answer: $answer"
  echo "  steps: $(tool_steps | tr '\n' ';' | head -c 400)"
  [[ -n "$answer" && "$answer" != null ]] || fail "$key: no answer after the round trip"
  tool_steps | grep -qi "| True |" || fail "$key: no tool step succeeded after the access was given: $(tool_steps)"
  assert_no_banned_words

  echo "--- $key: not now"
  access_off "$key"; access_on "$key" && fail "$key: still on (2)"
  restart_app || fail "$key: no restart (2)"
  wait_state_field modelState ready 120 || fail "$key: no model (2)"
  send_q "$question"
  wait_text "$sentence" 150 || fail "$key: no access sentence (2)"
  tap_text "Not now"
  wait_run_end 240 || fail "$key: the run never ended after Not now"
  answer="$(last_answer)"
  echo "  answer: $answer"
  [[ -n "$answer" && "$answer" != null ]] || fail "$key: no answer after Not now"
  access_on "$key" && fail "$key: switched on by itself after Not now"
  [[ "$(foreground)" == "$PKG" ]] || fail "$key: the app is not in front after Not now ($(foreground))"
  assert_no_banned_words
}

one_access dnd "Turn on Do Not Disturb" "Do Not Disturb access page" "Do Not Disturb"
one_access listener "Pause the music" "notification access page" "[Nn]otification"
one_access alarm "Set an alarm for 7:30 three days from now" "alarms and reminders" "[Aa]larms"
case_end "DND, notification listener, exact alarm: page round trip continues the run; Not now ends plainly"
