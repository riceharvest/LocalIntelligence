#!/usr/bin/env bash
# Case 22: fifty messages in one chat. Compaction must kick in and replies must keep arriving; the chat must still
# scroll (to the first message and back to the last), and the assistant must still know the recent turns.
source "$(dirname "$0")/lib.sh"
case_begin 22 "50 messages in one chat: compaction, replies, scrolling"
install_and_ready
dctl newchat >/dev/null; sleep 2
adb logcat -c
compactions=0
t_start="$(now)"
for i in $(seq 1 50); do
  # The chat takes a moment after a reply before it accepts the next message (the idle work after an answer).
  started=0
  for try in $(seq 1 20); do
    dctl send "Message number $i. Reply with just the word ok and my number." | grep -q '"started":true' && { started=1; break; }
    sleep 1
  done
  (( started )) || fail "message $i was not started in 20 s"
  wait_run_end 120 || fail "message $i never got a reply"
  a="$(last_answer)"
  [[ -n "$a" && "$a" != null ]] || fail "message $i: empty reply"
  if (( i % 5 == 0 )); then
    c="$(adb logcat -d -s LocalAgent:I | grep -c 'compacted')"
    (( c > compactions )) && compactions=$c
    echo "  message $i answered (${a:0:40}); compactions so far: $compactions; $(( $(now) - t_start )) s"
  fi
done
c="$(adb logcat -d -s LocalAgent:I | grep -c 'compacted')"; (( c > compactions )) && compactions=$c
echo "  compactions in 50 messages: $compactions"
(( compactions >= 1 )) || fail "50 messages and the conversation was never compacted"
for try in $(seq 1 20); do dctl send "What number was my latest message before this one?" | grep -q '"started":true' && break; sleep 1; done
[[ "$(state_field running)" == true ]] || fail "last question not started"
wait_run_end 120 || fail "the last question never got a reply"
echo "  recall answer: $(last_answer)"
last_answer | grep -Eq "50|fifty" || fail "after compaction the assistant forgot the latest turn: $(last_answer)"
assert_no_banned_words
# Scrolling: the newest message is at the bottom; swipe up until the first one is reached; and back.
ui_dump; ui has "$OUT/ui.xml" "number 50|recall|latest message" || fail "the newest turn is not on screen at the bottom"
found=0
for s in $(seq 1 60); do
  adb shell input swipe 540 700 540 1900 150
  sleep 0.4
  if on_screen "Message number 1\\."; then found=1; break; fi
done
(( found )) || fail "scrolling up never reached the first message (trimmed from the chat?)"
echo "  reached the first message after $s swipes"
for s in $(seq 1 80); do
  adb shell input swipe 540 1900 540 700 150
  sleep 0.3
  if on_screen "latest message before this one"; then break; fi
done
on_screen "latest message before this one" || fail "scrolling down never returned to the newest message"
case_end "50 messages: $compactions compactions, every reply arrived, recall right, scrolled to the first message and back"
