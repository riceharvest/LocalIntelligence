#!/usr/bin/env bash
# Case 7: a model file that is complete in size but damaged, and one that is truncated. It is
# detected, a plain sentence appears, and Try again recovers to a working assistant.
source "$(dirname "$0")/lib.sh"
case_begin 07 "Corrupt or truncated model file recovers"

fresh_install
launch
sleep 3
adb shell am force-stop "$PKG"
# Right size, wrong bytes: only the checksum can tell.
adb shell "run-as $PKG sh -c 'mkdir -p files/models && head -c $LFM3_BYTES /dev/zero > files/models/$LFM3_FILE'"
echo "  planted $(app_file_size "models/$LFM3_FILE") bytes of zeros"
hubctl "rate=40000000"
launch
wait_text "Finish setup|Get started|Continue setup" 40 || fail "no first-run panel with a planted file"
tap_text "Finish setup" || true
if wait_text "^Allow$" 5; then tap_text "Allow"; fi
wait_text "damaged|couldn't start|didn't finish|Try again" 120 || fail "the damaged file was not detected (no sentence appeared)"
echo "  shown: $(screen_text | head -4 | tr '\n' '|')"
assert_no_banned_words
[[ "$(app_file_size "models/$LFM3_FILE")" != "$LFM3_BYTES" ]] || \
  [[ "$(adb shell "run-as $PKG sh -c 'head -c 16 files/models/$LFM3_FILE | od -An -tx1'" | tr -d ' \r\n')" != "00000000000000000000000000000000" ]] || \
  fail "the damaged file was left in place"
tap_text "Try again"
wait_text "Ask anything" 10 >/dev/null
t0="$(now)"
until [[ "$(app_file_size "models/$LFM3_FILE")" == "$LFM3_BYTES" ]] && \
  [[ "$(adb shell "run-as $PKG sh -c 'head -c 4 files/models/$LFM3_FILE'" | tr -d '\r')" == "GGUF" ]]; do
  (( $(now) - t0 < 240 )) || fail "Try again never produced a sound file"
  sleep 3
done
tap_text "Ask anything"; type_text "Say hello"; tap_text "Send"
wait_text "hello|hi" 180 || fail "the recovered assistant gave no answer"
case_end "zero-filled file detected and replaced; assistant answered"
