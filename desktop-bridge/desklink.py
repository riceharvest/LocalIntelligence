#!/usr/bin/env python3
"""desklink: a small bridge from the phone to the coding agents on this desktop.

WHY A BRIDGE AND NOT THE AGENTS' OWN APIS: the phone should not need to know
whether an agent runs in herdr or tmux. Four operations (list, read, send, wait)
cover what the phone needs, and each terminal multiplexer implements them behind
the Backend class below. herdr is the first backend; tmux is the second.

WHY 127.0.0.1 ONLY: the process is meant to be published with `tailscale serve`,
which forwards tailnet traffic to a loopback port. Nothing else can reach it.

WHY THE LOGIN CHECK: Tailscale serve adds a Tailscale-User-Login header that
names the tailnet user. A request without that header, or with another login, is
refused. Known gap: a process on this desktop can send the header itself, so the
check defends against other tailnet users and devices, not against local code.

stdlib only. Nothing starts by itself: run it by hand (see README.md).
"""
import argparse
import hmac
import json
import re
import subprocess
import sys
import threading
import time
import urllib.parse
import urllib.request
import zlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

MAX_TEXT = 32 * 1024          # characters per message; the phone splits longer text
MAX_BODY = 64 * 1024          # bytes per request body
COMMAND_TIMEOUT = 15          # seconds for one herdr or tmux command
TEXT_LINES = 200              # lines of terminal text read per request; fixed so the key guard compares like with like
STATES = ("idle", "working", "blocked", "done", "unknown")
# Keys the phone may send. Anything else is refused, so a crafted request cannot send ctrl+c or a shell line.
KEY_ALLOWLIST = {"1", "2", "3", "y", "n", "enter", "esc", "up", "down"}
TMUX_KEYS = {"enter": "Enter", "esc": "Escape", "up": "Up", "down": "Down"}
TMUX_TARGET = re.compile(r"^%\d{1,6}$")
SAFE_BIN = re.compile(r"^[A-Za-z0-9_./-]{1,200}$")


class BridgeError(Exception):
    """A failure with a short code the phone can show. The message never includes terminal text."""

    def __init__(self, status, code, message):
        super().__init__(message)
        self.status = status
        self.code = code


class Backend:
    name = "base"
    notifies = False  # only herdr reports status, so only herdr sends done/needs-you events

    def list_agents(self):
        raise NotImplementedError

    def read(self, target, lines):
        raise NotImplementedError

    def send_text(self, target, text):
        raise NotImplementedError

    def send_keys(self, target, keys):
        raise NotImplementedError

    def wait(self, target, until):
        """Block until the agent is in one of `until`. Returns that state, or None when unknown."""
        raise NotImplementedError

    def seq_for(self, agent, text):
        """A number the phone sends back with a key press. A change means the screen the user saw is stale."""
        raise NotImplementedError


def run_command(argv, stdin_text=None, timeout=COMMAND_TIMEOUT):
    """Run a command with an argument list, never a shell. Returns (returncode, stdout, stderr)."""
    try:
        done = subprocess.run(
            argv, input=stdin_text, capture_output=True, text=True, timeout=timeout, check=False,
        )
    except FileNotFoundError:
        raise BridgeError(503, "backend_missing", f"{argv[0]} is not installed or not on PATH")
    except subprocess.TimeoutExpired:
        raise BridgeError(504, "timeout", "the desktop command timed out")
    return done.returncode, done.stdout, done.stderr


def herdr_error_code(err):
    """The error code herdr printed on stderr, such as agent_not_found, or an empty string."""
    match = re.search(r'"code"\s*:\s*"([a-z_]+)"', err or "")
    return match.group(1) if match else ""


class HerdrBackend(Backend):
    """Talks to the local `herdr` CLI, which speaks JSON. Field names come from `herdr api schema`."""

    name = "herdr"
    notifies = True

    def __init__(self, binary):
        self.bin = binary

    def _json(self, args):
        code, out, err = run_command([self.bin] + args)
        if code != 0:
            raise _herdr_failure(err)
        try:
            return json.loads(out)
        except ValueError:
            raise BridgeError(502, "backend_failed", "herdr returned something unexpected")

    def list_agents(self):
        data = self._json(["agent", "list"])
        agents = []
        for item in data.get("result", {}).get("agents", []):
            agents.append({
                "target": item["pane_id"],
                "name": item.get("terminal_title_stripped") or item.get("agent") or item["pane_id"],
                "status": item.get("agent_status", "unknown"),
                "state_seq": int(item.get("state_change_seq", 0)),
                "cwd": item.get("cwd", ""),
            })
        return agents

    def read(self, target, lines):
        args = ["agent", "read", target, "--source", "recent", "--format", "text"]
        if lines:
            args += ["--lines", str(lines)]
        code, out, err = run_command([self.bin] + args)
        if code != 0:
            raise _herdr_failure(err)
        try:
            # The CLI prints the JSON envelope: a pane_read result carries the text under result.read.
            wrapped = json.loads(out)
            if isinstance(wrapped, dict) and isinstance(wrapped.get("result"), dict):
                read = wrapped["result"].get("read", wrapped["result"])
                if isinstance(read, dict) and "text" in read:
                    return str(read["text"])
        except ValueError:
            pass
        return out

    def send_text(self, target, text):
        # Target first, then text: herdr accepts text that starts with a dash in this position (checked
        # against a nonexistent target). It does not accept `--` here.
        code, _, err = run_command([self.bin, "agent", "prompt", target, text])
        if code != 0:
            raise _herdr_failure(err, default="herdr did not accept the message")

    def send_keys(self, target, keys):
        code, _, _ = run_command([self.bin, "agent", "send-keys", target] + keys)
        if code != 0:
            raise BridgeError(502, "backend_failed", "herdr did not accept the key")

    def wait(self, target, until):
        args = [self.bin, "agent", "wait", target]
        for state in until:
            args += ["--until", state]
        code, _, err = run_command(args, timeout=None)
        if code != 0:
            raise _herdr_failure(err)
        # The caller reads the status again from list_agents, the shape the schema confirms.
        return None

    def seq_for(self, agent, text):
        return agent["state_seq"]


def _herdr_failure(err, default="herdr failed"):
    """Maps a herdr error code to a bridge error. Unknown codes become 502 with a short message."""
    code = herdr_error_code(err)
    if code.endswith("not_found"):
        return BridgeError(404, "not_found", "no such agent")
    if code == "agent_blocked":
        return BridgeError(409, "agent_blocked", "the agent is waiting for an answer; reply on the desktop")
    return BridgeError(502, "backend_failed", default)


class TmuxBackend(Backend):
    """Talks to tmux panes. tmux has no agent status, so every agent reports `unknown` and sends no events."""

    name = "tmux"

    def __init__(self, binary):
        self.bin = binary

    def _tmux(self, args, stdin_text=None):
        code, out, err = run_command([self.bin] + args, stdin_text=stdin_text)
        if code != 0:
            raise BridgeError(502, "backend_failed", "tmux failed")
        return out

    def _check_target(self, target):
        if not TMUX_TARGET.match(target):
            raise BridgeError(400, "bad_target", "that is not a pane id")

    def list_agents(self):
        out = self._tmux([
            "list-panes", "-a", "-F",
            "#{pane_id}\t#{session_name}:#{window_index}.#{pane_index}\t#{pane_current_command}\t#{pane_current_path}",
        ])
        agents = []
        for line in out.splitlines():
            parts = line.split("\t")
            if len(parts) != 4:
                continue
            pane, location, command, path = parts
            agents.append({
                "target": pane, "name": f"{command} ({location})", "status": "unknown",
                "state_seq": 0, "cwd": path,
            })
        return agents

    def read(self, target, lines):
        self._check_target(target)
        count = str(lines or 200)
        return self._tmux(["capture-pane", "-p", "-J", "-t", target, "-S", f"-{count}"])

    def send_text(self, target, text):
        self._check_target(target)
        # A paste buffer keeps long text out of the command line, and -p sends it as a bracketed paste.
        self._tmux(["load-buffer", "-b", "desklink", "-"], stdin_text=text)
        self._tmux(["paste-buffer", "-d", "-p", "-b", "desklink", "-t", target])
        self._tmux(["send-keys", "-t", target, "Enter"])

    def send_keys(self, target, keys):
        self._check_target(target)
        mapped = [TMUX_KEYS.get(key, key) for key in keys]
        self._tmux(["send-keys", "-t", target] + mapped)

    def seq_for(self, agent, text):
        # A checksum of the text the phone saw, so a key press based on old text is refused.
        return zlib.crc32(text.encode("utf-8", "replace"))


class Notifier:
    """Sends done and needs-you events for herdr agents. One thread per agent; each waits on the agent, not on a timer."""

    def __init__(self, backend, ntfy_url):
        self.backend = backend
        self.ntfy_url = ntfy_url
        self.watched = set()
        self.lock = threading.Lock()

    def watch_all(self, agents):
        for agent in agents:
            with self.lock:
                if agent["target"] in self.watched:
                    continue
                self.watched.add(agent["target"])
            threading.Thread(
                target=self._watch, args=(agent["target"], agent["name"], agent["status"]), daemon=True,
            ).start()

    def _watch(self, target, name, last):
        failures = 0
        while True:
            # Wait for any state other than the last one seen. The call returns at once if the state already moved on,
            # so no status change is missed, and it cannot spin, because the returned state always differs from `last`.
            try:
                self.backend.wait(target, [s for s in STATES if s != last])
                state = self._status_of(target)
                failures = 0
            except BridgeError as err:
                if err.code == "not_found":
                    with self.lock:
                        self.watched.discard(target)
                    return
                failures += 1
                if failures >= 5:
                    with self.lock:
                        self.watched.discard(target)
                    return
                time.sleep(10)
                continue
            if state == "blocked" and last != "blocked":
                self._send("Coding agent: needs you", "Open My desktop to see it.")
            elif state == "done" and last == "working":
                self._send("Coding agent: done", "Open My desktop to see it.")
            last = state

    def _status_of(self, target):
        for agent in self.backend.list_agents():
            if agent["target"] == target:
                return agent["status"]
        raise BridgeError(404, "not_found", "no such agent")

    def _send(self, title, body):
        request = urllib.request.Request(
            self.ntfy_url, data=body.encode("utf-8"), method="POST",
            headers={"Title": title, "Priority": "default", "Tags": "robot"},
        )
        try:
            urllib.request.urlopen(request, timeout=10).close()
        except OSError:
            # The URL is a topic name, so it is never printed.
            print("desklink: could not send a notification", file=sys.stderr)


def valid_ntfy_url(url):
    parsed = urllib.parse.urlsplit(url)
    if parsed.scheme == "https" and parsed.hostname:
        return True
    return parsed.scheme == "http" and parsed.hostname in ("127.0.0.1", "localhost")


class Handler(BaseHTTPRequestHandler):
    server_version = "desklink"
    protocol_version = "HTTP/1.1"

    # Set on the class by main().
    backend = None
    notifier = None
    login = b""
    allowed_hosts = set()

    def log_message(self, fmt, *args):
        # The default log prints paths, which can contain agent names. Keep stderr quiet.
        return

    def _guard(self):
        """Refuse anything that is not the configured tailnet user on an expected host. Returns nothing; raises."""
        host = (self.headers.get("Host") or "").lower()
        if host not in self.allowed_hosts:
            raise BridgeError(403, "forbidden", "unexpected host")
        if self.headers.get("Origin") is not None:
            # Browsers always send Origin on cross-site requests. The phone app never sends it.
            raise BridgeError(403, "forbidden", "browser requests are not accepted")
        presented = (self.headers.get("Tailscale-User-Login") or "").encode("utf-8")
        if not presented or not hmac.compare_digest(presented, self.login):
            raise BridgeError(403, "forbidden", "not the configured Tailscale user")

    def _send_json(self, status, payload):
        body = json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def _find(self, target):
        for agent in self.backend.list_agents():
            if agent["target"] == target:
                if self.notifier is not None:
                    self.notifier.watch_all([agent])
                return agent
        raise BridgeError(404, "not_found", "no such agent")

    def _body(self):
        length = int(self.headers.get("Content-Length") or 0)
        if length <= 0 or length > MAX_BODY:
            raise BridgeError(413, "too_large", "request body is missing or too large")
        try:
            data = json.loads(self.rfile.read(length))
        except ValueError:
            raise BridgeError(400, "bad_json", "body is not JSON")
        if not isinstance(data, dict):
            raise BridgeError(400, "bad_json", "body must be an object")
        return data

    def _route(self, method):
        parsed = urllib.parse.urlsplit(self.path)
        parts = [urllib.parse.unquote(p) for p in parsed.path.split("/") if p]
        if parts[:2] != ["v1", "agents"] and parts != ["v1", "health"]:
            raise BridgeError(404, "not_found", "no such route")
        if parts == ["v1", "health"] and method == "GET":
            return self._send_json(200, {"ok": True, "backend": self.backend.name})
        if len(parts) == 2 and method == "GET":
            agents = self.backend.list_agents()
            if self.notifier is not None:
                self.notifier.watch_all(agents)
            return self._send_json(200, {"agents": agents})
        if len(parts) == 4 and parts[3] in ("text", "stream", "prompt", "keys"):
            target = parts[2]
            if method == "GET" and parts[3] == "text":
                agent = self._find(target)
                text = self.backend.read(target, TEXT_LINES)
                return self._send_json(200, {"target": target, "text": text,
                                             "state_seq": self.backend.seq_for(agent, text)})
            if method == "GET" and parts[3] == "stream":
                return self._stream(target)
            if method == "POST" and parts[3] == "prompt":
                data = self._body()
                text = data.get("text")
                if not isinstance(text, str) or not text.strip() or len(text) > MAX_TEXT or "\x00" in text:
                    raise BridgeError(400, "bad_text", "message is empty or too long")
                self._find(target)
                self.backend.send_text(target, text)
                return self._send_json(200, {"ok": True})
            if method == "POST" and parts[3] == "keys":
                data = self._body()
                keys = data.get("keys")
                if not isinstance(keys, list) or not 1 <= len(keys) <= 3 or not all(
                    isinstance(k, str) and k in KEY_ALLOWLIST for k in keys
                ):
                    raise BridgeError(400, "bad_keys", "that key is not allowed")
                agent = self._find(target)
                # The phone sends the seq it showed the user. If the agent has moved on, the question on screen
                # may not be the one being answered, so refuse.
                text_seq = data.get("expect_seq")
                current = self.backend.seq_for(agent, self.backend.read(target, TEXT_LINES))
                if str(text_seq) != str(current):
                    raise BridgeError(409, "stale", "the agent changed; read it again before answering")
                self.backend.send_keys(target, keys)
                return self._send_json(200, {"ok": True})
        raise BridgeError(405, "method_not_allowed", "not allowed")

    def _stream(self, target):
        """Server-sent events with the pane text and its sequence number. Sends an event when either changes."""
        self._find(target)
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Connection", "close")
        self.close_connection = True
        self.end_headers()
        last = None
        deadline = time.monotonic() + 1800
        try:
            while time.monotonic() < deadline:
                # Re-reads the pane while this stream is open (about every 1.5 s). This is the only polling in the bridge.
                agent = self._find(target)
                text = self.backend.read(target, TEXT_LINES)
                seq = self.backend.seq_for(agent, text)
                if (text, seq) != last:
                    last = (text, seq)
                    event = json.dumps({"text": text, "state_seq": seq})
                    self.wfile.write(f"event: text\ndata: {event}\n\n".encode("utf-8"))
                else:
                    self.wfile.write(b": keep-alive\n\n")
                self.wfile.flush()
                time.sleep(1.5)
        except BridgeError as err:
            self.wfile.write(f"event: error\ndata: {json.dumps({'error': err.code})}\n\n".encode("utf-8"))
            self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError):
            return

    def _handle(self, method):
        try:
            self._guard()
            self._route(method)
        except BridgeError as err:
            if err.status == 403:
                # Says why, so a wrong --host or --login can be fixed. The presented login is never printed.
                print(f"desklink: refused ({err}); Host was {self.headers.get('Host')!r}", file=sys.stderr)
            self._send_json(err.status, {"error": err.code, "message": str(err)})

    def do_GET(self):
        self._handle("GET")

    def do_POST(self):
        self._handle("POST")


def build_parser():
    parser = argparse.ArgumentParser(description="Bridge the phone to this desktop's coding agents.")
    parser.add_argument("--backend", choices=["herdr", "tmux"], default="herdr")
    parser.add_argument("--login", required=True,
                        help="Tailscale login of the phone's user, for example you@example.com")
    parser.add_argument("--port", type=int, default=8787, help="loopback port that tailscale serve forwards to")
    parser.add_argument("--host", default="",
                        help="the MagicDNS name tailscale serve answers on, if the proxied Host header carries it")
    parser.add_argument("--ntfy-url", default="",
                        help="ntfy topic URL for done and needs-you events (https, or http on loopback)")
    parser.add_argument("--herdr-bin", default="herdr")
    parser.add_argument("--tmux-bin", default="tmux")
    return parser


def main(argv=None):
    args = build_parser().parse_args(argv)
    for binary in (args.herdr_bin, args.tmux_bin):
        if not SAFE_BIN.match(binary):
            print("desklink: refusing a binary name with unusual characters", file=sys.stderr)
            return 2
    if not args.login.strip():
        print("desklink: --login is required", file=sys.stderr)
        return 2
    if args.ntfy_url and not valid_ntfy_url(args.ntfy_url):
        print("desklink: --ntfy-url must be https, or http on 127.0.0.1 or localhost", file=sys.stderr)
        return 2

    backend = HerdrBackend(args.herdr_bin) if args.backend == "herdr" else TmuxBackend(args.tmux_bin)
    notifier = Notifier(backend, args.ntfy_url) if (args.ntfy_url and backend.notifies) else None
    if args.ntfy_url and not backend.notifies:
        print("desklink: the tmux backend has no agent status, so no notifications are sent", file=sys.stderr)

    allowed = {f"127.0.0.1:{args.port}", f"localhost:{args.port}"}
    if args.host:
        name = args.host.strip().lower()
        allowed.update({name, f"{name}:443"})

    Handler.backend = backend
    Handler.notifier = notifier
    Handler.login = args.login.strip().encode("utf-8")
    Handler.allowed_hosts = allowed

    # 127.0.0.1 only, never 0.0.0.0: tailscale serve is the only way in.
    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    server.daemon_threads = True
    if notifier is not None:
        try:
            notifier.watch_all(backend.list_agents())
        except BridgeError as err:
            print(f"desklink: could not list agents for notifications: {err}", file=sys.stderr)
    print(f"desklink: {backend.name} backend on 127.0.0.1:{args.port}", file=sys.stderr)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
