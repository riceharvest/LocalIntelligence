#!/usr/bin/env bash
# Case 9: phones with 2, 3, 4 and 6 GB. The right starter is chosen and really answers a question.
# 2 and 3 GB report under 3.2 GiB, so they get the 230M; 4 and 6 GB get LFM3. Each AVD is a fresh
# throwaway on its own port. Reports the load time (tap to ready) and the first answer's time.
source "$(dirname "$0")/lib.sh"
case_begin 09 "Weak phones: starter by RAM answers"

summary=""
SWAPPED=0
# A failure while another AVD is up still hands the main one back for the next case.
trap '(( SWAPPED )) && [[ -z "${E2E_ONLY:-}" ]] && emu_back >/dev/null 2>&1' EXIT
run_one() {
  local label="$1" avd="$2" want="$3" other="$4" wantbytes="$5"
  [[ -z "${E2E_ONLY:-}" || " $E2E_ONLY " == *" $label "* ]] || { echo "--- $label skipped (E2E_ONLY)"; return; }
  echo "--- $label ($avd)"
  # A 6 GB guest cannot live inside the usual 6 GB cgroup limit (the host killed it), so that one AVD gets 9 GB.
  local cap=6G; [[ "$avd" == li-ram6g ]] && cap=9G
  [[ "$avd" == li-e2e ]] || { SWAPPED=1; E2E_EMU_MEM=$cap emu_other "$avd" || fail "$label: emulator did not boot"; }
  local mem
  mem="$(adb shell "grep MemTotal /proc/meminfo" | tr -d '\r' | awk '{print $2}')"
  echo "  guest MemTotal: ${mem} kB"
  hubctl "reset=1"; hub_log_clear
  fresh_install
  adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 || true
  first_run_to_ready 600
  local ready="$READY_SECS"
  local files; files="$(starter_on_disk)"
  echo "  on disk: $files"
  [[ "$files" == *"$want"* ]] || fail "$label: expected $want on disk, found '$files'"
  [[ "$files" != *"$other"* ]] || fail "$label: the other starter was downloaded too ('$files')"
  [[ "$(app_file_size "models/$want")" == "$wantbytes" ]] || fail "$label: file size $(app_file_size "models/$want") != $wantbytes"
  assert_no_banned_words
  # What a person with this phone gets, on the FIRST try, no retries and no approval cards: a greeting, a tip, a sum,
  # and the battery. (Measured before the fix on the 2 GB phone: the 230M answered 1/3, 0/3, 1/3 of the first three
  # and refused with "I don't have a tool for that"; see scripts/e2e/starter-compare.sh.) Each is asked in a fresh chat.
  local q line v t1 secs
  for q in "hi" "Give me a sleep tip" "What is 17 times 23?" "How much battery do I have?"; do
    dctl newchat >/dev/null; sleep 2
    t1="$(now)"
    line="$(ask_json "$q" 400)" || fail "$label: no answer to '$q': $line"
    secs=$(( $(now) - t1 ))
    v="$(python3 -I "$E2E_DIR/judge.py" "$q" "$line")"
    echo "  '$q': $v (${secs}s) $(echo "$line" | sed -n 's/.*"answer":"\([^"]*\)".*/\1/p' | head -c 100)"
    [[ "$v" == ok ]] || fail "$label: first try at '$q' was not a sensible answer ($v): $line"
    wait_run_end 60 >/dev/null
  done
  screen_text | grep -qi "battery" || fail "$label: the question is not on screen"
  tryinfo="first try x4"
  if [[ "$want" == "$LFM3_FILE" ]]; then
    dctl newchat >/dev/null; sleep 2
    line="$(ask_json "What is two plus two?" 400)"
    answer="$(echo "$line" | sed -n 's/.*"answer":"\([^"]*\)".*/\1/p')"
    echo "  arithmetic: '$answer'"
    [[ "$answer" =~ (four|4) ]] || fail "$label: LFM3 answered '$answer' to two plus two"
  fi
  summary="$summary $label: ready ${ready}s, answer ${secs}s ($tryinfo);"
}

run_one "2GB" li-ram2g "$SMALL_FILE" "$LFM3_FILE" "$SMALL_BYTES"
run_one "3GB" li-ram3g "$SMALL_FILE" "$LFM3_FILE" "$SMALL_BYTES"
run_one "6GB" li-ram6g "$LFM3_FILE" "$SMALL_FILE" "$LFM3_BYTES"
[[ -z "${E2E_ONLY:-}" ]] && emu_back; SWAPPED=0
run_one "4GB" li-e2e "$LFM3_FILE" "$SMALL_FILE" "$LFM3_BYTES"
case_end "$summary"
