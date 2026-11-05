#!/usr/bin/env bash
# Shared helpers for the emulator tests. Source this; do not run it.
#
#   source "$(dirname "$0")/lib.sh"
#   case_begin 01 "First run"
#   ... drive the app, check what is on screen ...
#   case_end
#
# THE RULE THIS FILE ENFORCES: these tests only ever touch an emulator. `adb` is replaced here by a
# function that adds `-s <serial>` itself and refuses any serial that is not emulator-NNNN, and the
# guard below refuses to continue unless that device reports ro.kernel.qemu=1. A physical phone that
# auto-connects over Wi-Fi is disconnected first, and ANDROID_SERIAL is dropped so it can never be
# picked by default.
#
# Settings (environment):
#   E2E_SERIAL   the emulator to drive (default emulator-5554)
#   E2E_APK      the APK to install (default: this worktree's full debug build)
#   E2E_OUT      where screenshots, dumps and results.tsv go
#   E2E_HUB_PORT the fake hub's port on the host (default 18765)

set -uo pipefail

E2E_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$E2E_DIR/../.." && pwd)"
PKG="${LI_PKG:-dev.localintelligence.app.full}"
SERIAL="${E2E_SERIAL:-emulator-5554}"
APK="${E2E_APK:-$REPO_DIR/app/build/outputs/apk/full/debug/app-full-debug.apk}"
# A build restricted to one ABI (-Pandroid.injected.build.abi) leaves its APK under intermediates.
[[ -f "$APK" ]] || APK="$REPO_DIR/app/build/intermediates/apk/full/debug/app-full-debug.apk"
OUT="${E2E_OUT:-/tmp/claude-1000/e2e/out}"
HUB_PORT="${E2E_HUB_PORT:-18765}"
SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
mkdir -p "$OUT"

CASE_ID="--"
CASE_NAME=""
CASE_START=0

# ---------------------------------------------------------------------------------------------
# Safety: emulator only
# ---------------------------------------------------------------------------------------------

REAL_ADB="$(type -P adb)"

# Every adb call in every script goes through here. It adds the serial and refuses a device that is
# not an emulator. The few commands that are not about one device pass through untouched.
adb() {
  case "${1:-}" in
    devices | disconnect | start-server | kill-server | version | mdns) "$REAL_ADB" "$@"; return ;;
  esac
  local serial="$SERIAL"
  if [[ "${1:-}" == "-s" ]]; then
    serial="$2"
    shift 2
  fi
  if [[ "$serial" != emulator-[0-9]* ]]; then
    echo "REFUSED: '$serial' is not an emulator. These tests never touch a physical phone." >&2
    return 99
  fi
  "$REAL_ADB" -s "$serial" "$@"
}
export -f adb 2>/dev/null || true
export REAL_ADB SERIAL

# Disconnects network devices (the owner's phone can auto-connect), then proves the target is an
# emulator by its own property. Exits the whole script when it is not.
e2e_guard() {
  unset ANDROID_SERIAL
  "$REAL_ADB" disconnect >/dev/null 2>&1 || true
  if [[ "$SERIAL" != emulator-[0-9]* ]]; then
    echo "REFUSED: E2E_SERIAL='$SERIAL' is not an emulator serial." >&2
    exit 99
  fi
  local tries=0
  # A freshly booted emulator drops off adb once or twice while it settles: wait for it, briefly.
  until "$REAL_ADB" devices | awk 'NR>1 && $2=="device" {print $1}' | grep -qx "$SERIAL"; do
    (( ++tries > 30 )) && { echo "REFUSED: no running device '$SERIAL'. Start one with emu_start." >&2; exit 99; }
    sleep 1
  done
  local qemu
  qemu="$(adb shell getprop ro.kernel.qemu 2>/dev/null | tr -d '\r')"
  if [[ "$qemu" != "1" ]]; then
    echo "REFUSED: $SERIAL reports ro.kernel.qemu='$qemu', not 1. This is not an emulator." >&2
    exit 99
  fi
  export E2E_GUARDED=1
}

# ---------------------------------------------------------------------------------------------
# Results
# ---------------------------------------------------------------------------------------------

now() { date +%s; }

case_begin() {
  CASE_ID="$1"
  CASE_NAME="$2"
  CASE_START="$(now)"
  rm -f "$OUT/case-$CASE_ID.result"
  echo "=== case $CASE_ID: $CASE_NAME"
}

# Records a result line and, for a failure, a screenshot and the UI dump at that moment.
_result() {
  local verdict="$1" detail="$2"
  local secs=$(( $(now) - CASE_START ))
  printf '%s\t%s\t%s\t%ss\t%s\n' "$CASE_ID" "$verdict" "$CASE_NAME" "$secs" "$detail" > "$OUT/case-$CASE_ID.result"
  echo "$verdict $CASE_ID $CASE_NAME (${secs}s) $detail"
}

case_end() {
  _result PASS "${1:-}"
  exit 0
}

fail() {
  local why="$*"
  adb exec-out screencap -p > "$OUT/case-$CASE_ID-fail.png" 2>/dev/null || true
  ui_dump >/dev/null 2>&1 || true
  cp "$OUT/ui.xml" "$OUT/case-$CASE_ID-fail.xml" 2>/dev/null || true
  _result FAIL "$why"
  exit 1
}

# check "what" <command...>: fails the case unless the command succeeds.
check() {
  local what="$1"
  shift
  "$@" >/dev/null 2>&1 || fail "$what"
  echo "  ok: $what"
}

# ---------------------------------------------------------------------------------------------
# Emulator lifecycle
# ---------------------------------------------------------------------------------------------

# The guest gets the host CPU's instruction set (-qemu -cpu host). Without it the emulator's default CPU
# has no AVX2, the app loads its slowest CPU backend, and a first answer takes 11 minutes instead of one.
# emu_start AVD [PORT]: boots the AVD headless under the project's resource limits and waits
# (at most 240 s) until it is ready, then turns animations off (the spinner starves the guest).
emu_start() {
  local avd="$1" port="${2:-5554}"
  SERIAL="emulator-$port"
  export SERIAL
  "$REAL_ADB" disconnect >/dev/null 2>&1 || true
  if "$REAL_ADB" devices | awk 'NR>1 && $2=="device" {print $1}' | grep -qx "$SERIAL"; then
    echo "emulator $SERIAL is already running"
  else
    (systemd-run --user --scope -q -p MemoryMax=6G -p CPUQuota=400% nice -n 10 \
      "$SDK/emulator/emulator" -avd "$avd" -port "$port" -no-window -no-audio -no-boot-anim \
      -gpu swangle_indirect -no-metrics -no-snapshot-save ${E2E_EMU_ARGS:--qemu -cpu host} \
      > "$OUT/emulator-$avd.log" 2>&1 &)
  fi
  local i
  for i in $(seq 1 120); do
    if "$REAL_ADB" -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | grep -q 1; then break; fi
    sleep 2
  done
  "$REAL_ADB" -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | grep -q 1 || {
    echo "emulator did not boot in 240 s" >&2
    return 1
  }
  e2e_guard
  adb shell settings put global window_animation_scale 0
  adb shell settings put global transition_animation_scale 0
  adb shell settings put global animator_duration_scale 0
  adb shell settings put global stay_on_while_plugged_in 7
  adb shell input keyevent KEYCODE_WAKEUP
  adb shell wm dismiss-keyguard >/dev/null 2>&1 || true
}

emu_stop() {
  [[ "$SERIAL" == emulator-[0-9]* ]] || return 0
  "$REAL_ADB" -s "$SERIAL" emu kill >/dev/null 2>&1 || true
}

# ---------------------------------------------------------------------------------------------
# The app
# ---------------------------------------------------------------------------------------------

app_pid() { adb shell pidof "$PKG" 2>/dev/null | tr -d '\r' | awk '{print $1}'; }

# Installs the APK fresh: removes the package (and its data), installs, and points the app at the
# fake hub when one is wanted. Runtime permissions stay ungranted, as on a real first run.
fresh_install() {
  adb shell am force-stop "$PKG" >/dev/null 2>&1 || true
  adb uninstall "$PKG" >/dev/null 2>&1 || true
  adb install -r -t "$APK" >/dev/null 2>&1 || adb install -r -t "$APK" 2>&1 | tail -3
  adb shell pm path "$PKG" | grep -q package: || fail "the app did not install"
  if [[ "${1:-}" != "--real-hub" ]]; then
    point_at_hub
  fi
}

# Writes the hub address where the debug build reads it (run-as: the build is debuggable).
point_at_hub() {
  adb shell "run-as $PKG sh -c 'mkdir -p files && echo http://10.0.2.2:$HUB_PORT > files/debug-hub-base-url'"
}

launch() {
  adb shell am start -W -n "$PKG/dev.localintelligence.app.MainActivity" >/dev/null 2>&1
}

# Home, then back to the app by its icon intent, as a person leaving and returning would.
leave_and_return() {
  adb shell input keyevent KEYCODE_HOME
  sleep 3
  launch
}

# The size of a file inside the app's files directory, in bytes (0 when absent).
app_file_size() {
  local n
  n="$(adb shell "run-as $PKG sh -c 'stat -c %s files/$1 2>/dev/null || echo 0'" | tr -d '\r')"
  echo "${n:-0}"
}

app_files() { adb shell "run-as $PKG ls -l files/models" 2>/dev/null | tr -d '\r'; }

# The model file's local name for a starter (owner__repo__file, as the downloader names it).
LFM3_FILE="LiquidAI__LFM3-1.7B-GGUF__LFM3-1.7B-QAD-Q4_0.gguf"
LFM3_BYTES=1049615680
SMALL_FILE="LiquidAI__LFM2.5-230M-GGUF__LFM2.5-230M-QAD-Q4_0.gguf"
SMALL_BYTES=149081056

# devicectl.sh against this emulator and the full-flavor package.
dctl() { LI_PKG="$PKG" "$REPO_DIR/scripts/devicectl.sh" -s "$SERIAL" "$@"; }

# ---------------------------------------------------------------------------------------------
# What is on screen
# ---------------------------------------------------------------------------------------------

# Dumps the UI into $OUT/ui.xml. uiautomator refuses while the screen is animating, so it retries.
ui_dump() {
  local i
  for i in 1 2 3 4 5 6; do
    if adb shell uiautomator dump /sdcard/e2e-ui.xml 2>&1 | grep -q "dumped to"; then
      adb exec-out cat /sdcard/e2e-ui.xml > "$OUT/ui.xml"
      return 0
    fi
    sleep 1
  done
  return 1
}

ui() { python3 -I "$E2E_DIR/ui.py" "$@"; }

# Every visible text of the app (and, with --all, of any package such as a system dialog).
screen_text() {
  ui_dump || return 1
  if [[ "${1:-}" == "--all" ]]; then ui texts "$OUT/ui.xml"; else ui texts "$OUT/ui.xml" --pkg "$PKG"; fi
}

# Everything on screen, whichever package (system dialogs included).
screen_text_all() { ui_dump || return 1; ui texts "$OUT/ui.xml"; }

# on_screen REGEX: true when some visible text matches (case-insensitive).
on_screen() {
  ui_dump || return 1
  ui has "$OUT/ui.xml" "$1"
}

# wait_text REGEX [SECONDS]: polls the screen until a text matches. A bounded loop, not a timer.
wait_text() {
  local rx="$1" secs="${2:-60}" t0
  t0="$(now)"
  while (( $(now) - t0 < secs )); do
    if ui_dump && ui has "$OUT/ui.xml" "$rx"; then return 0; fi
    sleep 1
  done
  return 1
}

wait_gone() {
  local rx="$1" secs="${2:-60}" t0
  t0="$(now)"
  while (( $(now) - t0 < secs )); do
    if ui_dump && ! ui has "$OUT/ui.xml" "$rx"; then return 0; fi
    sleep 1
  done
  return 1
}

# tap_text TEXT: taps the node whose text or description is TEXT (exact match first, any package,
# so system dialogs work too). Fails the case when there is none.
tap_text() {
  local xy
  ui_dump || fail "could not read the screen to tap '$1'"
  xy="$(ui find "$OUT/ui.xml" "$1")" || fail "nothing on screen reads '$1'"
  # shellcheck disable=SC2086
  adb shell input tap $xy
  echo "  tap: $1 at $xy"
}

# tap_text_soft TEXT: like tap_text, returns 1 instead of failing.
tap_text_soft() {
  local xy
  ui_dump || return 1
  xy="$(ui find "$OUT/ui.xml" "$1")" || return 1
  # shellcheck disable=SC2086
  adb shell input tap $xy
  echo "  tap: $1 at $xy"
}

# type_text TEXT: types into the focused field (spaces become %s for `input text`).
type_text() {
  local t="${1// /%s}"
  adb shell input text "$t"
}

# Fails the case when the app's own text uses a word Simple mode never shows a person.
assert_no_banned_words() {
  ui_dump || fail "could not read the screen for the word check"
  local hits
  if ! hits="$(ui banned "$OUT/ui.xml" --pkg "$PKG")"; then
    fail "banned words on screen: $(echo "$hits" | tr '\n' ';')"
  fi
}

# ---------------------------------------------------------------------------------------------
# The fake hub (a stand-in for huggingface.co on the host)
# ---------------------------------------------------------------------------------------------

HUB_PID_FILE="$OUT/hub.pid"

hub_start() {
  hub_stop
  python3 -I "$E2E_DIR/fakehub.py" --port "$HUB_PORT" > "$OUT/hub.log" 2>&1 &
  echo $! > "$HUB_PID_FILE"
  local i
  for i in $(seq 1 90); do
    if curl -s "http://127.0.0.1:$HUB_PORT/_ctl" >/dev/null 2>&1; then return 0; fi
    sleep 1
  done
  echo "the fake hub did not start" >&2
  return 1
}

hub_stop() {
  [[ -f "$HUB_PID_FILE" ]] && kill "$(cat "$HUB_PID_FILE")" 2>/dev/null
  rm -f "$HUB_PID_FILE"
  return 0
}

# hubctl "rate=2000000&status=0": changes how the hub behaves from now on.
hubctl() { curl -s "http://127.0.0.1:$HUB_PORT/_ctl?$1" >/dev/null; }
hub_log() { curl -s "http://127.0.0.1:$HUB_PORT/_log"; }
hub_log_clear() { curl -s "http://127.0.0.1:$HUB_PORT/_log?clear=1" >/dev/null; }

# ---------------------------------------------------------------------------------------------
# Network and storage on the emulator
# ---------------------------------------------------------------------------------------------

airplane_on() { adb shell cmd connectivity airplane-mode enable; }
airplane_off() { adb shell cmd connectivity airplane-mode disable; }

# wifi_metered on|off: reconnects the emulator's Wi-Fi marked metered (or not). `netpolicy set
# metered-network` does not take on the emulator; joining the network again with -m does.
wifi_metered() {
  local flag=""
  [[ "$1" == on ]] && flag="-m"
  adb shell "cmd wifi forget-network 0" >/dev/null 2>&1
  adb shell "cmd wifi connect-network AndroidWifi open $flag" >/dev/null 2>&1
  local i
  for i in $(seq 1 30); do
    if [[ "$1" == on ]]; then
      adb shell cmd netpolicy list wifi-networks true | grep -q "AndroidWifi;true" && break
    else
      ! adb shell cmd netpolicy list wifi-networks true | grep -q "AndroidWifi;true" && break
    fi
    sleep 1
  done
  # Wait for the network to be usable again.
  for i in $(seq 1 30); do
    adb shell dumpsys connectivity | grep -q "WIFI CONNECTED" && return 0
    sleep 1
  done
  return 1
}

# The percentage the first-run panel shows ("Setting up · 37%"), or empty.
setup_percent() {
  ui_dump || return 1
  ui texts "$OUT/ui.xml" --pkg "$PKG" | sed -n 's/.*Setting up · \([0-9]*\)%.*/\1/p' | head -1
}

# wait_percent MIN [SECONDS]: waits until the panel shows at least MIN percent.
wait_percent() {
  local min="$1" secs="${2:-120}" t0 p
  t0="$(now)"
  while (( $(now) - t0 < secs )); do
    p="$(setup_percent)"
    if [[ -n "$p" ]] && (( p >= min )); then echo "$p"; return 0; fi
    sleep 1
  done
  return 1
}

# Free bytes on /data.
data_free_bytes() { adb shell "df -k /data | tail -1" | awk '{print $4 * 1024}'; }

[[ "${E2E_NO_GUARD:-}" == 1 ]] || e2e_guard
