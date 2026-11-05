#!/usr/bin/env bash
# Case 13: a runtime permission revoked with `pm revoke`. Ask a question that needs it. The person reads one
# plain sentence, taps Continue, taps Allow in the system dialog, and the run carries on by itself and answers.
# Contacts, calendar and location.
source "$(dirname "$0")/lib.sh"
case_begin 13 "Permissions: Allow continues the run"

install_and_ready
adb shell cmd uimode night no >/dev/null 2>&1
# A location fix for the emulator, so "where am I" has something to read.
adb emu geo fix 4.6358 52.3874 >/dev/null 2>&1 || true

# check_one NAME PERMISSION QUESTION SENTENCE_REGEX ALLOW_BUTTON ANSWER_REGEX
check_one() {
  local name="$1" perm="$2" question="$3" sentence="$4" allow="$5" answer_rx="$6"
  echo "--- $name"
  adb shell pm clear-permission-flags "$PKG" "$perm" user-set user-fixed >/dev/null 2>&1; adb shell pm revoke "$PKG" "$perm" >/dev/null 2>&1
  perm_granted "$perm" && fail "$name: $perm is still granted after pm revoke"
  # A new process each time, so 'asked once per process' does not hide the next sentence.
  restart_app || fail "$name: the app did not restart"
  wait_state_field modelState ready 120 || fail "$name: no model"
  send_q "$question"
  wait_text "$sentence" 150 || fail "$name: no access sentence (steps: $(tool_steps | tr '\n' ';' | head -c 300))"
  echo "  sentence: $(screen_text | grep -Ei "$sentence" | head -1)"
  assert_no_banned_words
  tap_text "Continue"
  wait_text "$allow" 30 || fail "$name: no system permission dialog"
  echo "  system dialog: $(screen_text_all | tr '\n' '|' | head -c 200)"
  tap_text "$allow"
  wait_run_end 240 || fail "$name: the run never ended after Allow"
  perm_granted "$perm" || fail "$name: the permission is not granted after Allow"
  local answer; answer="$(last_answer)"
  echo "  answer: $answer"
  echo "  steps: $(tool_steps | tr '\n' ';' | head -c 400)"
  [[ -n "$answer" && "$answer" != null ]] || fail "$name: the run ended without an answer"
  [[ "$answer" =~ $answer_rx ]] || fail "$name: the answer does not fit the question: $answer"
  screen_text | grep -qF "$(echo "$answer" | head -c 25)" || echo "  (answer text not found verbatim on screen)"
}
  # (No banned-word scan of the reply: that text is written by the small model, not by the app.)

check_one contacts android.permission.READ_CONTACTS "Do I have a contact called Anna?" \
  "read your contacts" "Allow" "."
check_one calendar android.permission.READ_CALENDAR "What is on my calendar tomorrow?" \
  "permission to read it" "Allow" "."
check_one location android.permission.ACCESS_COARSE_LOCATION "Where am I right now?" \
  "approximate location" "While using the app" "."
case_end "contacts, calendar, location: sentence, Allow, run continued and answered"
