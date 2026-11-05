#!/usr/bin/env bash
# Measured comparison of small starter models on a weak-phone AVD: the four everyday questions case 9 requires
# on the first try, REPEATS times each, per model file. Not a case (it prints a table); case 9 holds the pass bar.
#
#   E2E_SERIAL=emulator-5556 scripts/e2e/starter-compare.sh AVD PORT REPEATS file.gguf [file.gguf ...]
#
# Each file is copied into the app's model directory and loaded with the debug channel. A question counts as
# right only when it is answered at first try, with no approval card, and says what the question needs:
#   hi                        some reply, no tool, no refusal
#   Give me a sleep tip       a reply of 25+ characters, no tool, no refusal
#   What is 17 times 23?      391, no tool other than a calculator
#   How much battery do I have?  device.battery and a percentage
E2E_NO_GUARD=1 source "$(dirname "$0")/lib.sh"
AVD="$1"; PORT="$2"; REPEATS="$3"; shift 3
SERIAL="emulator-$PORT"; export SERIAL
emu_start "$AVD" "$PORT" || { echo "no emulator"; exit 1; }
e2e_guard
trap 'emu_stop' EXIT
hub_start >/dev/null 2>&1 || true
hubctl "reset=1"
fresh_install
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 || true
# The first run downloads the default starter (the 230M here); wait for it, then add the others.
first_run_to_ready 600
TSV="$OUT/starter-compare-$AVD.tsv"; : > "$TSV"
QUESTIONS=("hi" "Give me a sleep tip" "What is 17 times 23?" "How much battery do I have?")

judge() { # question line -> prints ok|why
  python3 -I - "$1" "$2" <<'PY'
import json, re, sys
q, raw = sys.argv[1], sys.argv[2]
try:
    j = json.loads(raw)
except Exception:
    print("no-json"); sys.exit()
out = j.get("outcome")
tools = [t.get("name") for t in j.get("tools", []) if t.get("name") and t.get("name") != "tools.find"]
ans = (j.get("answer") or "").strip()
refuse = re.search(r"\b(can't|cannot|can not|unable|not able|sorry|don't have|do not have|i am just)\b", ans, re.I)
if out == "awaiting_approval": print("approval-card"); sys.exit()
if out != "answer": print("outcome-" + str(out)); sys.exit()
if q == "hi":
    print("tool:" + ",".join(tools) if tools else ("refusal" if refuse else ("ok" if ans else "empty")))
elif q == "Give me a sleep tip":
    print("tool:" + ",".join(tools) if tools else ("refusal" if refuse else ("ok" if len(ans) >= 25 else "short")))
elif q == "What is 17 times 23?":
    bad = [t for t in tools if not t.startswith("calc") and not t.startswith("math")]
    print("tool:" + ",".join(bad) if bad else ("ok" if "391" in ans.replace(",", "") else "wrong"))
else:
    print("ok" if "device.battery" in tools and re.search(r"\d+ ?%", ans) else ("no-battery-tool:" + ",".join(tools)))
PY
}

for f in "$@"; do
  base="$(basename "$f")"
  if [[ "$f" != "starter" ]]; then
    adb push "$f" "/data/local/tmp/$base" >/dev/null
    adb shell "run-as $PKG sh -c 'cp /data/local/tmp/$base files/models/$base'" || fail "could not copy $base"
    adb shell rm -f "/data/local/tmp/$base"
    restart_app || fail "no restart"
    sleep 3
  else
    base="$(starter_on_disk | awk '{print $1}')"
  fi
  dctl usemodel "$base" >/dev/null
  wait_state_field modelState ready 240 || fail "$base: did not load: $(dctl state | head -c 300)"
  echo "== $base ($(dctl state | json_field model))"
  for q in "${QUESTIONS[@]}"; do
    okc=0
    for i in $(seq 1 "$REPEATS"); do
      dctl newchat >/dev/null; sleep 2
      t0="$(now)"
      line="$(ask_json "$q" 240)"
      v="$(judge "$q" "$line")"
      [[ "$(state_field runState)" == AwaitingApproval ]] && dctl approve deny 5 >/dev/null 2>&1
      wait_run_end 60 >/dev/null
      ans="$(echo "$line" | sed -n 's/.*"answer":"\([^"]*\)".*/\1/p' | head -c 90)"
      printf '%s\t%s\t%s\t%s\t%ss\t%s\n' "$base" "$q" "$i" "$v" "$(( $(now) - t0 ))" "$ans" | tee -a "$TSV"
      [[ "$v" == ok ]] && okc=$((okc + 1))
    done
    printf 'SUMMARY\t%s\t%s\t%s/%s\n' "$base" "$q" "$okc" "$REPEATS" | tee -a "$TSV"
  done
done
