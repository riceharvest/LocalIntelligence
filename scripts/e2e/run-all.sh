#!/usr/bin/env bash
# Runs the emulator cases and prints one table. Exits non-zero when any case fails.
#
#   scripts/e2e/run-all.sh [--keep] [--avd NAME] [case-number ...]
#
# With no case numbers it runs every case-NN-*.sh script in this directory, in order. It boots the
# AVD itself (default li-e2e, a throwaway: destructive cases wipe it) and starts the fake hub, and
# stops both at the end unless --keep is given. It only ever talks to an emulator (see lib.sh).
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
AVD="li-e2e"
KEEP=0
WANT=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    --keep) KEEP=1 ;;
    --avd) AVD="$2"; shift ;;
    *) WANT+=("$1") ;;
  esac
  shift
done

export E2E_NO_GUARD=1
# shellcheck source=lib.sh
source "$HERE/lib.sh"
unset E2E_NO_GUARD

rm -f "$OUT"/case-*.result
emu_start "$AVD" "${E2E_PORT:-5554}" || { echo "no emulator"; exit 1; }
hub_start || { echo "no fake hub"; emu_stop; exit 1; }
cleanup() {
  hub_stop
  (( KEEP )) || emu_stop
}
trap cleanup EXIT

shopt -s nullglob
for script in "$HERE"/case-*.sh; do
  num="$(basename "$script" | sed -n 's/^case-\([0-9]*\)-.*/\1/p')"
  if (( ${#WANT[@]} )); then
    keep=0
    for w in "${WANT[@]}"; do [[ "$((10#$w))" == "$((10#$num))" ]] && keep=1; done
    (( keep )) || continue
  fi
  hubctl "reset=1"
  hub_log_clear
  bash "$script" > "$OUT/case-$num.log" 2>&1
  code=$?
  if [[ ! -f "$OUT/case-$num.result" ]]; then
    printf '%s\tFAIL\t%s\t-\tscript ended (exit %s) without a result; see case-%s.log\n' "$num" "$(basename "$script")" "$code" "$num" > "$OUT/case-$num.result"
  fi
done

echo
printf '%-5s %-5s %-44s %-7s %s\n' CASE RESULT NAME TIME DETAIL
bad=0
for f in $(ls "$OUT"/case-*.result 2>/dev/null | sort); do
  IFS=$'\t' read -r id verdict name secs detail < "$f"
  printf '%-5s %-5s %-44s %-7s %s\n' "$id" "$verdict" "$name" "$secs" "$detail"
  [[ "$verdict" == PASS ]] || bad=1
done
exit $bad
