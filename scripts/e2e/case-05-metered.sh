#!/usr/bin/env bash
# Case 5: a metered network. The first tap tells the person the size and waits; the download starts
# only after Try again.
source "$(dirname "$0")/lib.sh"
case_begin 05 "Metered network: size question first"
trap 'adb shell cmd netpolicy set metered-network "AndroidWifi;none" false >/dev/null 2>&1' EXIT

adb shell cmd netpolicy set metered-network "AndroidWifi;none" true
sleep 4
adb shell dumpsys connectivity | grep -q "NOT_METERED" && fail "the emulator's network is still unmetered"
hubctl "rate=10000000"
fresh_install
launch
wait_text "Get started" 40 || fail "no first-run panel"
tap_text "Get started"
if wait_text "^Allow$" 8; then tap_text "Allow"; fi
wait_text "mobile data" 20 || fail "no mobile-data question after the first tap"
screen_text | grep -Eq "1\.0 GB|1,0 GB" || fail "the question does not say the size"
assert_no_banned_words
[[ "$(app_file_size "models/$LFM3_FILE.part")" == "0" ]] || fail "bytes were downloaded before the person agreed"
hub_log | grep -q resolve && fail "the hub saw a download request before consent"
tap_text "Try again"
wait_percent 1 60 >/dev/null || fail "Try again did not start the download"
case_end "asked first, downloaded after consent"
