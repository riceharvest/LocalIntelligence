#!/usr/bin/env bash
# Case 14: the same revoked permissions, but the person taps Don’t allow. The fallback happens (the calendar
# app opens, or the assistant asks for a city and says so), the answer is plain, and the next question in the
# same session does not nag with the sentence again.
source "$(dirname "$0")/lib.sh"
case_begin 14 "Permissions: Deny takes the fallback, no nagging"

install_and_ready
adb emu geo fix 4.6358 52.3874 >/dev/null 2>&1 || true

deny_one() {
  local name="$1" perm="$2" question="$3" sentence="$4" deny="$5"
  echo "--- $name"
  adb shell pm revoke "$PKG" "$perm" >/dev/null 2>&1
  restart_app || fail "$name: the app did not restart"
  wait_state_field modelState ready 120 || fail "$name: no model"
  send_q "$question"
  wait_text "$sentence" 150 || fail "$name: no access sentence"
  tap_text "Continue"
  wait_text "$deny" 30 || fail "$name: no system dialog with '$deny'"
  tap_text "$deny"
  sleep 2
  wait_run_end 240 || fail "$name: the run never ended after Don’t allow"
  perm_granted "$perm" && fail "$name: granted although denied"
  DENY_ANSWER="$(last_answer)"
  echo "  answer: $DENY_ANSWER"
  echo "  steps: $(tool_steps | tr '\n' ';' | head -c 500)"
  [[ -n "$DENY_ANSWER" && "$DENY_ANSWER" != null ]] || fail "$name: no answer after a denial"
  DENY_STEPS="$(tool_steps)"
}

# After the denial, the same question again: the sentence must not come back.
no_nag() {
  local name="$1" question="$2" sentence="$3"
  send_q "$question"
  local t0; t0="$(now)"
  while (( $(now) - t0 < 150 )); do
    if on_screen "$sentence"; then fail "$name: asked again after a denial"; fi
    [[ "$(state_field running)" == "false" ]] && break
    sleep 2
  done
  [[ "$(state_field running)" == "false" ]] || fail "$name: the second question never finished"
  echo "  second answer: $(last_answer)"
}

# Contacts: the fallback is to ask the person for the number or name.
deny_one contacts android.permission.READ_CONTACTS "Do I have a contact called Anna?" "read your contacts" "Don’t allow"
echo "$DENY_STEPS" | grep -qi "contacts" || fail "contacts: the contacts tool never ran"
no_nag contacts "Do I have a contact called Bob?" "read your contacts"

# Calendar: the calendar app opens on the day (Simple mode's fallback), and the run says so.
deny_one calendar android.permission.READ_CALENDAR "What is on my calendar tomorrow?" "permission to read it" "Don’t allow"
echo "$DENY_STEPS" | grep -qi "calendar" || fail "calendar: the calendar tool never ran"
echo "$DENY_STEPS" | grep -Eqi "calendar app is open|add it themselves" || fail "calendar: no fallback wording: $DENY_STEPS"
fg="$(adb shell dumpsys activity activities | tr -d '\r' | grep -E "mResumedActivity|topResumedActivity" | head -2)"
echo "  foreground after the denial: $fg"
no_nag calendar "What is on my calendar on Friday?" "permission to read it"

# Location: the assistant asks for a city, and says why.
deny_one location android.permission.ACCESS_COARSE_LOCATION "Where am I right now?" "approximate location" "Don’t allow"
echo "$DENY_STEPS" | grep -qi "location" || fail "location: the location tool never ran"
[[ "$DENY_ANSWER" =~ (city|place|where|which|town) ]] || fail "location: the answer does not ask for a place: $DENY_ANSWER"
no_nag location "Where am I?" "approximate location"
assert_no_banned_words
case_end "contacts, calendar, location: fallbacks taken, no second sentence"
