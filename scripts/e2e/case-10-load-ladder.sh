#!/usr/bin/env bash
# Case 10: the load ladder. A debug control fails the first rungs of the native load. The app steps down,
# remembers the rung that worked (the next launch starts there), and answers. When every rung fails it says
# so in one plain sentence and does not crash.
source "$(dirname "$0")/lib.sh"
case_begin 10 "Load ladder: step down, remember, answer"
trap 'debug_file debug-fail-load-rungs ""' EXIT

install_and_ready
[[ "$(state_field loadRung)" == "0" ]] || fail "a healthy load did not stay on rung 0 (loadRung=$(state_field loadRung))"
ask_battery "healthy"

# Two rungs fail: the third loads, and is remembered.
debug_file debug-fail-load-rungs 2
restart_app || fail "the app did not come back"
wait_state_field modelState ready 120 || fail "no model after two failed rungs (state: $(dctl state | head -c 300))"
rung="$(state_field loadRung)"
echo "  rung after two forced failures: $rung"
[[ "$rung" == "2" ]] || fail "expected to land on rung 2, got '$rung'"
ask_battery "after step-down"
assert_no_banned_words

# The failures are gone, and the next launch still starts at the rung that worked (no climbing back).
debug_file debug-fail-load-rungs ""
restart_app || fail "the app did not come back (2)"
wait_state_field modelState ready 120 || fail "no model on the remembered rung"
[[ "$(state_field loadRung)" == "2" ]] || fail "the remembered rung was lost (loadRung=$(state_field loadRung))"
ask_battery "remembered rung"

# Every rung fails: one plain sentence, no crash, no loop.
debug_file debug-fail-load-rungs 9
pid_before="$(app_pid)"
restart_app || fail "the app did not come back (3)"
sleep 20
[[ -n "$(app_pid)" ]] || fail "the app died when every load rung failed"
[[ "$(state_field modelState)" == "failed" ]] || fail "expected a failed model state, got '$(state_field modelState)'"
text="$(screen_text)"
echo "$text" | sed 's/^/    | /' | head -20
echo "$text" | grep -Eqi "wouldn't start|would not start|couldn't start|could not start|not start|try again" || fail "no plain sentence about the assistant not starting"
assert_no_banned_words
# The cause goes away; Try again loads the model and the chat answers.
debug_file debug-fail-load-rungs ""
tap_text "Try again"
wait_state_field modelState ready 120 || fail "Try again did not load the model (state: $(dctl state | head -c 300))"
ask_battery "after Try again"
case_end "rung 2 kept across launches; all-fail shows a sentence"
