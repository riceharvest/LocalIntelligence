#!/usr/bin/env bash
# Case 2: airplane mode before the first tap. A plain sentence, no crash; turning airplane mode off
# starts the download by itself, with no tap.
source "$(dirname "$0")/lib.sh"
case_begin 02 "Airplane mode at the first tap"
trap 'airplane_off >/dev/null 2>&1' EXIT

hubctl "rate=8000000"
fresh_install
launch
wait_text "Get started" 40 || fail "no first-run panel"
airplane_on
sleep 4
tap_text "Get started"
if wait_text "^Allow$" 8; then tap_text "Allow"; fi
wait_text "internet" 40 || fail "no plain sentence about the internet while offline"
assert_no_banned_words
[[ -n "$(app_pid)" ]] || fail "the app died while offline"
screen_text | grep -qi "Setting up" && fail "a download claims to run while offline"

airplane_off
wait_percent 1 90 >/dev/null || fail "turning airplane mode off did not start the download by itself"
echo "  progress: $(setup_percent)%"
case_end "sentence shown offline; download started by itself on reconnect"
