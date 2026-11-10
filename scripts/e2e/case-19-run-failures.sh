#!/usr/bin/env bash
# Case 19: a run that goes wrong ends plainly. The model's replies are scripted (a failing tool, the same call
# over and over, more steps than the limit, empty replies) so each failure happens every time; the loop, the
# policy, the tools, the stop sentences, "Done so far" and Retry are the app's own. For each: the run ends, the
# person sees an answer or a plain sentence (never nothing, never a raw error), Retry really sends the message
# again and it is answered, and Clock's real state holds exactly the one timer (or alarm) the run made: a
# repeated or retried run must not set another.
source "$(dirname "$0")/lib.sh"
case_begin 19 "Failing tool, step limit, loop, empty reply: answer or Done so far + Retry"
trap 'script_set ""' EXIT
install_and_ready

# Timers that are really running: pending alarms of the Clock app tagged TIMER_EXPIRED. (The dump also lists
# alarms that were cancelled, under the same tag; those have no "Alarm{" line.)
timers() { adb shell "dumpsys alarm" | tr -d '\r' | grep -A1 'Alarm{.* com.google.android.deskclock}' | grep -c 'TIMER_EXPIRED'; }
clear_clock() { adb shell pm clear com.google.android.deskclock >/dev/null 2>&1; sleep 2; }

TIMER="$(tool_call timer.set '{"seconds":900,"label":"e2e"}')"
ASK="Set a timer for 15 minutes called e2e"

# After a scripted run: the run ended, the person sees something plain, then Retry (when offered) works.
# $1 name, $2 expected timers, $3 "needs-stop" when the script must end in a stop sentence with Retry.
check_ended() {
  local name="$1" want_timers="$2" mode="${3:-}"
  wait_run_end 150 || fail "$name: the run never ended"
  sleep 2
  local answer text; answer="$(last_answer)"; text="$(screen_text)"
  echo "  answer: ${answer:0:200}"
  echo "  on screen: $(echo "$text" | tr '\n' '|' | head -c 500)"
  echo "  steps: $(tool_steps | cut -c1-120 | tr '\n' ';' | head -c 400)"
  local retry=0; ui has "$OUT/ui.xml" "^Retry$" && retry=1
  [[ -n "$answer" && "$answer" != null ]] || [[ $retry == 1 ]] || fail "$name: no answer and no Retry; the person sees nothing useful"
  [[ "$mode" != needs-stop* || $retry == 1 ]] || fail "$name: expected a stop sentence with Retry"
  [[ "$mode" != needs-stop-done ]] || echo "$text" | grep -q "Done so far" || fail "$name: the sentence does not say what was done so far"
  echo "$text" | grep -Eq "Exception|Error:|null|\{\"|<tool|<respond" && fail "$name: raw text on screen: $(echo "$text" | grep -E 'Exception|Error:|null|<tool|<respond' | head -3)"
  assert_no_banned_words
  local n; n="$(timers)"
  echo "  timers in Clock: $n"
  [[ "$n" == "$want_timers" ]] || fail "$name: Clock holds $n timers, expected $want_timers"
  if (( retry )); then
    script_set '<respond>Retried fine.</respond>'
    local before; before="$(dctl state | json_field messages)"
    tap_exact_soft "Retry" || fail "$name: Retry could not be tapped"
    wait_text "Retried fine" 90 || fail "$name: Retry did not resend the message (screen: $(screen_text | tr '\n' '|' | head -c 300))"
    wait_run_end 60
    [[ "$(timers)" == "$want_timers" ]] || fail "$name: Retry changed the timers ($(timers))"
    echo "  Retry resent and was answered"
  fi
}

echo "--- 1 loop: the same call again and again"
clear_clock
scripted_ask "$ASK" "$TIMER|||$TIMER|||$TIMER|||$TIMER|||$TIMER|||$TIMER" 150 >/dev/null
check_ended loop 1

echo "--- 2 empty replies after one step worked"
clear_clock
scripted_ask "$ASK" "$TIMER|||@empty|||@empty|||@empty|||@empty|||@empty" 150 >/dev/null
check_ended empty 1 needs-stop-done

echo "--- 3 more steps than the limit"
clear_clock
CALLS="$TIMER"
for t in device.battery device.info apps.list alarm.list history.search files.list notifications.list device.screen_time clipboard.read; do
  CALLS="$CALLS|||$(tool_call "$t" '{}')"
done
scripted_ask "$ASK" "$CALLS" 200 >/dev/null
check_ended steps 1

echo "--- 4 a tool that fails, again and again"
clear_clock
FAIL="$(tool_call web.fetch '{"url":"https://no-such-host.e2e.invalid/page"}')"
scripted_ask "Open the web page no-such-host.e2e.invalid" "$FAIL|||$FAIL|||$FAIL|||$FAIL|||$FAIL" 150 >/dev/null
check_ended failing-tool 0

echo "--- 5 only empty replies"
clear_clock
scripted_ask "What is two plus two?" "@empty|||@empty|||@empty|||@empty|||@empty|||@empty" 150 >/dev/null
check_ended all-empty 0 needs-stop

echo "--- 6 an alarm set twice in one run is one alarm"
adb shell cmd appops set --uid "$PKG" SCHEDULE_EXACT_ALARM allow >/dev/null 2>&1; adb shell cmd appops set "$PKG" SCHEDULE_EXACT_ALARM allow >/dev/null 2>&1
ALARM="$(tool_call alarm.create '{"hour":6,"minute":15,"label":"e2e alarm"}')"
scripted_ask "Set an alarm for 6:15" "$ALARM|||$ALARM|||$ALARM|||$(tool_call alarm.list '{}')|||@echo" 150 >/dev/null
wait_run_end 120
steps="$(tool_steps)"
echo "  steps: $(echo "$steps" | cut -c1-200 | tr '\n' ';')"
n="$(echo "$steps" | grep -c '^alarm.create | True')"
[[ "$n" == 1 ]] || fail "alarm: alarm.create ran $n times for one request"
listed="$(echo "$steps" | grep '^alarm.list' | grep -o 'e2e alarm' | wc -l)"
[[ "$listed" == 1 ]] || fail "alarm: the alarm list holds the alarm $listed times: $(echo "$steps" | grep '^alarm.list')"
dctl newchat >/dev/null
case_end "loop, empty replies, step limit, failing tool, all-empty: each ends in an answer or a plain sentence; Retry resends; one timer, one alarm in the real state"
