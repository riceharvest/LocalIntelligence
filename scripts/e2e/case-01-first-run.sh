#!/usr/bin/env bash
# Case 1: a fresh install of the full flavor. Get started, the notification prompt, progress with an
# estimate, leaving the app and coming back, the download finishing, the first answer. A message typed
# during setup sends by itself once the assistant is ready.
source "$(dirname "$0")/lib.sh"
case_begin 01 "First run: install to first answer"

hubctl "rate=14000000"
fresh_install
launch
wait_text "Get started" 40 || fail "the first-run panel never showed Get started"
assert_no_banned_words
screen_text | grep -qi "1.0 GB" || fail "the panel does not say how big the download is"

tap_text "Get started"
# The notification prompt is the system's; the app asks for it on this tap.
wait_text "^Allow$" 15 || fail "no notification prompt after Get started"
tap_text "Allow"

wait_percent 5 90 >/dev/null || fail "no progress shown after Allow"
screen_text | grep -qi "Setting up" || fail "no 'Setting up' line"
screen_text | grep -q "You can leave the app" || fail "no 'you can leave' line"
assert_no_banned_words
# An estimate shows once the pace is measured.
wait_text "left" 40 || fail "no time estimate ('... left') during the download"
echo "  estimate: $(screen_text | grep -i left | head -1)"

# Type while setting up: the message is queued and sent by itself.
tap_text "Ask anything"
type_text "What is two plus two"
sleep 1
tap_text "Send"
wait_text "send your message as soon as" 10 || fail "typing during setup gave no 'I'll send your message' notice"

before="$(setup_percent)"
leave_and_return
sleep 3
wait_percent "${before:-1}" 30 >/dev/null || fail "after leaving and returning the progress was lost (was ${before}%)"

# The download finishes and the queued question is answered.
t0="$(now)"
until [[ "$(app_file_size "models/$LFM3_FILE")" == "$LFM3_BYTES" ]]; do
  (( $(now) - t0 < 300 )) && { sleep 3; continue; }
  fail "the model file never reached $LFM3_BYTES bytes (is $(app_file_size "models/$LFM3_FILE"))"
done
echo "  download complete"
wait_text "(four|4)" 240 || fail "the queued question 'What is two plus two' got no answer containing 4 / four"
screen_text | grep -qi "two plus two" || fail "the question is not on screen"
assert_no_banned_words
case_end "answered after $(( $(now) - t0 ))s from file complete; estimate shown"
