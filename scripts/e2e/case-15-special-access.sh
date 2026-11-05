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
    # Do Not Disturb access has no query from the shell: the run's own tool result is the proof.
    dnd) return 1 ;;
    listener) [[ "$(adb shell settings get secure enabled_notification_listeners | tr -d '\r')" == *"$PKG"* ]] ;;
    alarm) [[ "$(adb shell cmd appops get "$PKG" SCHEDULE_EXACT_ALARM | tr -d '\r')" == *allow* ]] ;;
  esac
}

access_off() {
  case "$1" in
    dnd) adb shell cmd notification disallow_dnd "$PKG" >/dev/null 2>&1 ;;
    listener) adb shell cmd notification disallow_listener "$COMPONENT" >/dev/null 2>&1 ;;
    alarm) adb shell cmd appops set --uid "$PKG" SCHEDULE_EXACT_ALARM deny >/dev/null 2>&1; adb shell cmd appops set "$PKG" SCHEDULE_EXACT_ALARM deny >/dev/null 2>&1 ;;
  esac
  sleep 3   # the system applies these asynchronously
}

# ask_until_sentence QUESTION SENTENCE_RX: the small model does not always pick the tool that needs the access,
# so a question is tried up to 3 times in a fresh chat; the sentence must show on one of them.
ask_until_sentence() {
  local question="$1" sentence="$2" n
  for n in 1 2 3; do
    send_q "$question"
    if wait_text "$sentence" 60; then return 0; fi
    echo "  try $n: no access sentence (steps: $(tool_steps | tr '\n' ';' | head -c 200))"
    [[ "$(state_field runState)" == "AwaitingApproval" ]] && dctl approve deny 5 >/dev/null 2>&1
    finish_run 150 >/dev/null
  done
  return 1
}

# one_access KEY QUESTION SENTENCE_RX PAGE_RX
one_access() {
  local key="$1" question="$2" sentence="$3" page="$4"
  echo "--- $key: allow"
  access_off "$key"; access_on "$key" && fail "$key: still on after switching it off"
  restart_app || fail "$key: no restart"
  wait_state_field modelState ready 120 || fail "$key: no model"
  ask_until_sentence "$question" "$sentence" || fail "$key: no access sentence in 3 tries"
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
  tap_exact_soft "Allow" || tap_exact_soft "OK" || true
  sleep 2
  [[ "$key" == dnd ]] || access_on "$key" || fail "$key: the switch did not turn the access on ($(screen_text_all | tr '\n' '|' | head -c 300))"
  back_to_app || fail "$key: could not get back to the app"
  finish_run 240 || fail "$key: the run never ended after coming back"
  local answer; answer="$(last_answer)"
  echo "  answer: $answer"
  echo "  steps: $(tool_steps | tr '\n' ';' | head -c 400)"
  [[ -n "$answer" && "$answer" != null ]] || fail "$key: no answer after the round trip"
  # Proof the run went past the gate: some tool step ran, and none of them says the access is still off. (A step
  # may still fail for another reason, e.g. no music is playing for the media tool.)
  [[ -n "$(tool_steps)" ]] || fail "$key: no tool ran after the access was given"
  tool_steps | grep -Eqi "access is (still )?off|is off for this app|not on for this app|must be switched on" && fail "$key: a tool still reports the access as off after the round trip: $(tool_steps)"

  echo "--- $key: not now"
  access_off "$key"; access_on "$key" && fail "$key: still on (2)"
  restart_app || fail "$key: no restart (2)"
  wait_state_field modelState ready 120 || fail "$key: no model (2)"
  ask_until_sentence "$question" "$sentence" || fail "$key: no access sentence in 3 tries (2)"
  tap_text "Not now"
  finish_run 240 || fail "$key: the run never ended after Not now"
  answer="$(last_answer)"
  echo "  answer: $answer"
  if [[ -z "$answer" || "$answer" == null ]]; then
    # The small model sometimes wanders to unrelated tools after a refusal; the run must then end with the app's
    # own plain stop sentence and a Retry, never silence.
    on_screen "I got stuck|Try asking again|Done so far|stopped" && on_screen "^Retry$" || fail "$key: no answer and no plain stop sentence after Not now"
    echo "  ended with the app's stop sentence and Retry"
  fi
  [[ "$key" != dnd || "$answer" != *"?"* ]] || fail "dnd: after Not now the assistant asked a question instead of saying it was not changed: $answer"
  access_on "$key" && fail "$key: switched on by itself after Not now"
  tool_steps | grep -q "| True |" && fail "$key: a tool succeeded after Not now: $(tool_steps)"
  [[ "$(foreground)" == "$PKG" ]] || fail "$key: the app is not in front after Not now ($(foreground))"
}
  # (No banned-word scan of the reply: that text is written by the small model, not by the app.)

[[ -z "${E2E_KEYS:-}" || " $E2E_KEYS " == *" dnd "* ]] && one_access dnd "Turn on Do Not Disturb" "Do Not Disturb access page" "Do Not Disturb"
[[ -z "${E2E_KEYS:-}" || " $E2E_KEYS " == *" listener "* ]] && one_access listener "Pause the music" "notification access page" "[Nn]otification"
[[ -z "${E2E_KEYS:-}" || " $E2E_KEYS " == *" alarm "* ]] && one_access alarm "Set an alarm for 7:30 three days from now" "alarms and reminders" "[Aa]larms"
case_end "DND, notification listener, exact alarm: page round trip continues the run; Not now ends plainly"
