#!/usr/bin/env bash
# Drives a debug build of LocalIntelligence over adb: one text command in, one JSON answer out.
#
#   devicectl.sh [-s serial] <cmd> [arg]
#
# Commands: state | send <text> | ask <text> [timeout-seconds] | newchat | models |
#           usemodel <file name> | mode <simple|technical> | approve <allow|deny> | transcript |
#           searchblock <duckduckgo,bing,brave,wikipedia> (a 5-minute cooldown, to test the search chain)
#           searchroute <id=url,...> (send a search service to a test address; empty clears) |
#           script <entry|||entry> (the model's next replies; see ScriptedBackend; empty clears)
#
# Debug builds only. The receiver is in app/src/debug and is absent from release builds, so
# against a release install every command answers with an error. Override the package with
# LI_PKG=dev.localintelligence.app.full for the full flavor's debug build.
set -euo pipefail

PKG="${LI_PKG:-dev.localintelligence.app}"
ACTION="dev.localintelligence.debug.CMD"
SERIAL=()
if [[ "${1:-}" == "-s" ]]; then
  SERIAL=(-s "$2")
  shift 2
fi

if [[ $# -lt 1 ]]; then
  echo '{"ok":false,"error":"usage: devicectl.sh [-s serial] <state|send|ask|newchat|models|usemodel|mode|approve|transcript> [arg]"}' >&2
  exit 2
fi
CMD="$1"
ARG="${2:-}"

# Quotes one value for the device's shell, which adb runs the whole command line through.
squote() {
  local s=${1//\'/\'\\\'\'}
  printf "'%s'" "$s"
}

# Sends one command and prints the JSON answer. The answer comes back as data="..." in am's output.
broadcast() {
  local cmd="$1" arg="$2" out data
  out=$(adb "${SERIAL[@]}" shell "am broadcast -n $PKG/dev.localintelligence.app.debug.DebugControl -a $ACTION --es cmd $(squote "$cmd") --es arg $(squote "$arg")")
  data=$(printf '%s\n' "$out" | sed -n 's/.*data="\(.*\)".*/\1/p' | tail -n 1)
  if [[ -z "$data" ]]; then
    echo '{"ok":false,"error":"no answer from the app: is the debug build installed and running?"}'
    return 1
  fi
  printf '%s\n' "$data"
}

case "$CMD" in
  state|newchat|models|transcript|pinwidget)
    broadcast "$CMD" ""
    ;;
  send|usemodel|mode|searchblock|script|searchroute)
    broadcast "$CMD" "$ARG"
    ;;
  approve)
    # Answers the pending approval and waits for the run's next DONE line, as ask does.
    SECS="${3:-600}"
    adb "${SERIAL[@]}" logcat -c
    SENT=$(broadcast approve "$ARG" || true)
    if [[ "$SENT" != *'"ok":true'* ]]; then
      echo "$SENT"
      exit 1
    fi
    LINE=$(timeout "$SECS" adb "${SERIAL[@]}" logcat -m 1 -s LIControl:I -e '^DONE ' || true)
    if [[ -z "$LINE" ]]; then
      echo "{\"ok\":false,\"error\":\"no DONE line within ${SECS}s\",\"sent\":true}"
      exit 1
    fi
    echo "${LINE#*: DONE }"
    ;;
  ask)
    TEXT="$ARG"
    SECS="${3:-600}"
    # Logcat has no per-tag clear, so the buffer is cleared as a whole. The DONE line is then the only
    # LIControl line after this point that the blocking read below can match.
    adb "${SERIAL[@]}" logcat -c
    SENT=$(broadcast send "$TEXT" || true)
    if [[ "$SENT" != *'"started":true'* ]]; then
      echo "$SENT"
      exit 1
    fi
    # One blocking read: logcat stops after the first DONE line. No polling, no sleep.
    LINE=$(timeout "$SECS" adb "${SERIAL[@]}" logcat -m 1 -s LIControl:I -e '^DONE ' || true)
    if [[ -z "$LINE" ]]; then
      echo "{\"ok\":false,\"error\":\"no DONE line within ${SECS}s\",\"sent\":true}"
      exit 1
    fi
    # The line reads "I/LIControl(pid): DONE {json}"; strip up to the first ": DONE ".
    JSON=${LINE#*: DONE }
    echo "$JSON"
    ;;
  *)
    echo "{\"ok\":false,\"error\":\"unknown command '$CMD'\"}"
    exit 2
    ;;
esac
