#!/usr/bin/env bash
# Repeats the desklink tmux test. Safe to run any time: it makes its own private tmux server (socket
# "desklink-selftest") with one session "desklink-test" running `cat`, binds the bridge to 127.0.0.1 on a free
# port, and removes both at the end. It never touches herdr and never prints terminal contents.
set -u
cd "$(dirname "$0")"
LOGIN="tester@example.com"
SOCK="desklink-selftest"
SESSION="desklink-test"
TMUXC="tmux -L $SOCK"
PORT=$(python3 -c 'import socket;s=socket.socket();s.bind(("127.0.0.1",0));print(s.getsockname()[1])')
BASE="http://127.0.0.1:$PORT"
fail=0; pass=0
cleanup() { [ -n "${BRIDGE_PID:-}" ] && kill "$BRIDGE_PID" 2>/dev/null; $TMUXC kill-server 2>/dev/null; rm -f "${TMPF:-}"; }
trap cleanup EXIT
TMPF=$(mktemp)

check() { # name expected actual
  if [ "$2" = "$3" ]; then pass=$((pass+1)); echo "PASS $1"; else fail=$((fail+1)); echo "FAIL $1 (expected $2, got $3)"; fi
}
code() { curl -s -o "$TMPF" -w '%{http_code}' --max-time 10 "$@"; }
auth=(-H "Tailscale-User-Login: $LOGIN")
json() { python3 -c "import sys,json;d=json.load(open('$TMPF'));print($1)" 2>/dev/null; }

$TMUXC kill-server 2>/dev/null
$TMUXC new-session -d -s "$SESSION" -x 120 -y 30 cat || { echo "FAIL tmux session"; exit 1; }
python3 desklink.py --backend tmux --tmux-socket "$SOCK" --login "$LOGIN" --port "$PORT" 2>/dev/null &
BRIDGE_PID=$!
for _ in $(seq 1 50); do curl -s -o /dev/null "$BASE/v1/health" && break; sleep 0.1; done

# auth: every one of these must be refused
check "auth: no identity header" 403 "$(code "$BASE/v1/agents")"
check "auth: wrong login" 403 "$(code -H 'Tailscale-User-Login: other@example.com' "$BASE/v1/agents")"
check "auth: empty login" 403 "$(code -H 'Tailscale-User-Login:' "$BASE/v1/agents")"
check "auth: browser Origin" 403 "$(code "${auth[@]}" -H 'Origin: https://evil.example' "$BASE/v1/agents")"
check "auth: wrong Host" 403 "$(code "${auth[@]}" -H 'Host: evil.example' "$BASE/v1/agents")"
check "auth: no identity on POST" 403 "$(code -X POST -H 'Content-Type: application/json' -d '{"text":"x"}' "$BASE/v1/agents/%251/prompt")"
check "auth: health also guarded" 403 "$(code "$BASE/v1/health")"
check "auth: right login" 200 "$(code "${auth[@]}" "$BASE/v1/agents")"

# list
PANE=$(json "d['agents'][0]['target']")
check "list: one agent, tmux pane id" 1 "$(json "int(len(d['agents'])==1 and d['agents'][0]['target'].startswith('%'))")"
check "list: fields" "cwd name state_seq status target" "$(json "' '.join(sorted(d['agents'][0]))")"
ENC=$(python3 -c "import urllib.parse,sys;print(urllib.parse.quote(sys.argv[1],safe=''))" "$PANE")

# send text: cat echoes it back into the pane
check "prompt: ok" 200 "$(code "${auth[@]}" -X POST -H 'Content-Type: application/json' -d '{"text":"hello-from-selftest"}' "$BASE/v1/agents/$ENC/prompt")"
sleep 0.5
check "read: shows the sent text" 200 "$(code "${auth[@]}" "$BASE/v1/agents/$ENC/text")"
check "read: text contains it" 1 "$(json "int('hello-from-selftest' in d['text'])")"
SEQ=$(json "d['state_seq']")
check "prompt: empty refused" 400 "$(code "${auth[@]}" -X POST -H 'Content-Type: application/json' -d '{"text":"  "}' "$BASE/v1/agents/$ENC/prompt")"
check "prompt: no body refused" 413 "$(code "${auth[@]}" -X POST "$BASE/v1/agents/$ENC/prompt")"
check "prompt: unknown pane" 404 "$(code "${auth[@]}" -X POST -H 'Content-Type: application/json' -d '{"text":"x"}' "$BASE/v1/agents/%2599999/prompt")"
check "prompt: bad pane id" 404 "$(code "${auth[@]}" "$BASE/v1/agents/%3Bls/text")"

# unicode and a dash-leading message
check "prompt: unicode and leading dash" 200 "$(code "${auth[@]}" -X POST -H 'Content-Type: application/json' -d '{"text":"-héllo ✓ unicode"}' "$BASE/v1/agents/$ENC/prompt")"
sleep 0.4
code "${auth[@]}" "$BASE/v1/agents/$ENC/text" >/dev/null
check "read: unicode intact" 1 "$(json "int('-héllo ✓ unicode' in d['text'])")"
SEQ=$(json "d['state_seq']")

# keys
check "keys: stale seq refused" 409 "$(code "${auth[@]}" -X POST -H 'Content-Type: application/json' -d '{"keys":["y"],"expect_seq":1}' "$BASE/v1/agents/$ENC/keys")"
check "keys: ctrl-c refused" 400 "$(code "${auth[@]}" -X POST -H 'Content-Type: application/json' -d '{"keys":["ctrl+c"],"expect_seq":'"$SEQ"'}' "$BASE/v1/agents/$ENC/keys")"
check "keys: y accepted" 200 "$(code "${auth[@]}" -X POST -H 'Content-Type: application/json' -d '{"keys":["y"],"expect_seq":'"$SEQ"'}' "$BASE/v1/agents/$ENC/keys")"
sleep 0.3
code "${auth[@]}" "$BASE/v1/agents/$ENC/text" >/dev/null
SEQ=$(json "d['state_seq']")
check "keys: enter accepted" 200 "$(code "${auth[@]}" -X POST -H 'Content-Type: application/json' -d '{"keys":["enter"],"expect_seq":'"$SEQ"'}' "$BASE/v1/agents/$ENC/keys")"
sleep 0.3
code "${auth[@]}" "$BASE/v1/agents/$ENC/text" >/dev/null
check "keys: y reached the pane" 1 "$(json "int(any(l.strip()=='y' for l in d['text'].splitlines()))")"

# wait: tmux has no status
check "wait: not supported on tmux" 501 "$(code "${auth[@]}" "$BASE/v1/agents/$ENC/wait?until=idle&timeout_ms=500")"

# stream: the first event arrives, then new text arrives as a second event
OUT=$(mktemp)
(curl -s -N --max-time 6 "${auth[@]}" "$BASE/v1/agents/$ENC/stream" > "$OUT") &
SP=$!
sleep 1.5
curl -s -o /dev/null "${auth[@]}" -X POST -H 'Content-Type: application/json' -d '{"text":"second-line"}' "$BASE/v1/agents/$ENC/prompt"
wait $SP
check "stream: text events" 1 "$(python3 - "$OUT" <<'EOF'
import sys,json
ev=[json.loads(l[6:]) for l in open(sys.argv[1]) if l.startswith("data: ")]
print(int(len(ev)>=2 and "second-line" not in ev[0]["text"] and "second-line" in ev[-1]["text"] and set(ev[0])=={"text","state_seq","status"}))
EOF
)"
rm -f "$OUT"

# the bridge binds loopback only
check "bind: loopback only" 1 "$(ss -ltn 2>/dev/null | grep -c "127.0.0.1:$PORT ")"
check "bind: not on any other address" 0 "$(ss -ltn 2>/dev/null | grep -E "(0.0.0.0|\\[::\\]|\\*):$PORT " | wc -l)"

echo "passed $pass, failed $fail"
[ "$fail" -eq 0 ]
