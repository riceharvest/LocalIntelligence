#!/usr/bin/env bash
# Case 9: phones with 2, 3, 4 and 6 GB. The right starter is chosen and really answers a question.
# 2 and 3 GB report under 3.2 GiB, so they get the 230M; 4 and 6 GB get LFM3. Each AVD is a fresh
# throwaway on its own port. Reports the load time (tap to ready) and the first answer's time.
source "$(dirname "$0")/lib.sh"
case_begin 09 "Weak phones: starter by RAM answers"

summary=""
SWAPPED=0
# A failure while another AVD is up still hands the main one back for the next case.
trap '(( SWAPPED )) && emu_back >/dev/null 2>&1' EXIT
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
  # The floor tier (230M) is not asked general knowledge: it answers with a made-up tool call or refuses
  # (docs/roadmap.md calls it the floor). The battery question is within its reach and checks the whole
  # loop: load, prompt, a real tool call on the phone, the sentence back.
  # The 230M samples its answer: on the same question it sometimes reaches for an unrelated tool (the approval
  # gate holds that back; a test denies it). So the floor tier gets up to 3 tries and must answer once; the
  # LFM3 tier must answer on the first. The tries are reported either way.
  local tries=1 t1 line outcome answer secs ok=0 n=0
  [[ "$want" == "$SMALL_FILE" ]] && tries=3
  while (( n < tries )); do
    n=$((n + 1))
    dctl newchat >/dev/null; sleep 2
    t1="$(now)"
    line="$(ask_json "What is my battery level?" 400)" || fail "$label: no answer: $line"
    local k=0
    while echo "$line" | grep -q '"outcome":"awaiting_approval"' && (( k < 5 )); do
      k=$((k + 1))
      echo "  try $n: asked to approve an unrelated tool; denied ($k)"
      line="$(dctl approve deny 120)"
    done
    wait_run_end 60 >/dev/null
    secs=$(( $(now) - t1 ))
    outcome="$(echo "$line" | json_field outcome)"
    answer="$(echo "$line" | sed -n 's/.*"answer":"\([^"]*\)".*/\1/p')"
    echo "  try $n: outcome=$outcome answer='$answer' (${secs}s)"
    if [[ "$outcome" == answer && "$answer" =~ [0-9]+% ]] && echo "$line" | grep -q '"name":"device.battery"'; then ok=1; break; fi
  done
  (( ok )) || fail "$label: no correct battery answer in $tries tries"
  screen_text | grep -qi "battery" || fail "$label: the question is not on screen"
  tryinfo="try $n of $tries"
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
emu_back; SWAPPED=0
run_one "4GB" li-e2e "$LFM3_FILE" "$SMALL_FILE" "$LFM3_BYTES"
case_end "$summary"
