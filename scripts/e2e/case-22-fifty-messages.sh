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
  launch   # a tool that opens another app would leave the chat in the background, where a run cannot start
  started=0
  for try in $(seq 1 20); do
    dctl send "Question $i: please answer with only the word ok." | grep -q '"started":true' && { started=1; break; }
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
# Scrolling: the newest message is at the bottom; swipe up until the first one is reached; and back.
ui_dump; ui has "$OUT/ui.xml" "Question 50:" || fail "the newest message is not on screen at the bottom"
found=0
for s in $(seq 1 60); do
  adb shell input swipe 540 700 540 1900 150
  sleep 0.4
  if on_screen "Question 1:"; then found=1; break; fi
done
(( found )) || fail "scrolling up never reached the first message (trimmed from the chat?)"
echo "  reached the first message after $s swipes"
for s in $(seq 1 80); do
  adb shell input swipe 540 1900 540 700 150
  sleep 0.3
  if on_screen "Question 50:"; then break; fi
done
on_screen "Question 50:" || fail "scrolling down never returned to the newest message"
echo "  scrolled back to the newest message"

# After compaction the assistant must still answer, and about the latest turn.
for try in $(seq 1 20); do dctl send "Which question number did I ask just before this one? Answer with the number only." | grep -q '"started":true' && break; sleep 1; done
wait_run_end 120 || fail "the last question never got a reply"
echo "  recall answer: $(last_answer)"
last_answer | grep -Eq "50|fifty" || fail "after compaction the assistant forgot the latest turn: $(last_answer)"
assert_no_banned_words
case_end "50 messages: $compactions compactions, every reply arrived, the latest turn mentioned after compaction (weak: a 1.7B model), scrolled to the first message and back"
