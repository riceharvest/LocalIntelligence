#!/usr/bin/env python3
"""A stand-in for huggingface.co that the emulator tests point the app at (10.0.2.2 from the guest).

It serves the two starter repositories from real files on the host, answers the same two kinds of
request the app makes (the repo listing, and file bytes with Range), and can be told to misbehave:
slow, cut the connection, answer 5xx or 404, or serve a damaged file. A test changes its behaviour
with GET /_ctl?... and reads what the app asked for with GET /_log.

    fakehub.py --port 8765 [--file REPO FILENAME PATH]...

    /_ctl?reset=1
    /_ctl?rate=BYTES_PER_SECOND        slow every file response (0 = full speed)
    /_ctl?cut_after=N                  the next file response ends after N body bytes, connection reset
    /_ctl?status=503&where=api|file|both   answer that status instead (0 = off)
    /_ctl?truncate=N                   serve only the first N bytes while still claiming the full size
    /_log                              one line per request: method path range status bytes
    /_log?clear=1

Only the standard library, so it runs anywhere the tests do.
"""
import argparse
import hashlib
import json
import os
import socket
import struct
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, unquote, urlparse

DEFAULT_FILES = [
    ("LiquidAI/LFM3-1.7B-GGUF", "LFM3-1.7B-QAD-Q4_0.gguf", "/mnt/ssd/localintelligence-models/LFM3-1.7B-QAD-Q4_0.gguf"),
    ("LiquidAI/LFM2.5-230M-GGUF", "LFM2.5-230M-QAD-Q4_0.gguf", "/mnt/ssd/localintelligence-models/LFM2.5-230M-QAD-Q4_0.gguf"),
]

STATE_LOCK = threading.Lock()
STATE = {}
LOG = []
FILES = {}  # repo -> {filename: path}
SHA_CACHE = {}


def reset_state():
    STATE.clear()
    STATE.update(rate=0, cut_after=0, status=0, where="both", truncate=0)


def sha256_of(path):
    st = os.stat(path)
    key = (path, st.st_size, int(st.st_mtime))
    if key not in SHA_CACHE:
        h = hashlib.sha256()
        with open(path, "rb") as f:
            while True:
                b = f.read(1 << 20)
                if not b:
                    break
                h.update(b)
        SHA_CACHE[key] = h.hexdigest()
    return SHA_CACHE[key]


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *args):
        pass

    def _record(self, status, sent):
        line = f"{self.command} {self.path} range={self.headers.get('Range', '-')} status={status} bytes={sent}"
        with STATE_LOCK:
            LOG.append(line)

    def _text(self, status, body, ctype="application/json"):
        data = body.encode() if isinstance(body, str) else body
        self.send_response(status)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        url = urlparse(self.path)
        q = parse_qs(url.query)
        path = unquote(url.path)
        if path == "/_ctl":
            with STATE_LOCK:
                if "reset" in q:
                    reset_state()
                for k in ("rate", "cut_after", "status", "truncate"):
                    if k in q:
                        STATE[k] = int(q[k][0])
                if "where" in q:
                    STATE["where"] = q["where"][0]
                snap = dict(STATE)
            return self._text(200, json.dumps(snap))
        if path == "/_log":
            with STATE_LOCK:
                out = "\n".join(LOG)
                if "clear" in q:
                    LOG.clear()
            return self._text(200, out, "text/plain")
        if path.startswith("/api/models/"):
            return self._api(path[len("/api/models/"):])
        parts = path.strip("/").split("/")
        if len(parts) >= 5 and parts[2] == "resolve":
            return self._file("/".join(parts[:2]), "/".join(parts[4:]))
        self._record(404, 0)
        self._text(404, "{}")

    def _status_override(self, where):
        with STATE_LOCK:
            s, w = STATE["status"], STATE["where"]
        return s if s and w in (where, "both") else 0

    def _api(self, repo):
        forced = self._status_override("api")
        if forced:
            self._record(forced, 0)
            return self._text(forced, json.dumps({"error": "forced"}))
        files = FILES.get(repo)
        if not files:
            self._record(404, 0)
            return self._text(404, json.dumps({"error": "Repository not found"}))
        siblings = []
        for name, path in files.items():
            size = os.path.getsize(path)
            siblings.append({"rfilename": name, "size": 134,
                             "lfs": {"sha256": sha256_of(path), "size": size, "pointerSize": 134}})
        self._record(200, 0)
        self._text(200, json.dumps({"id": repo, "private": False, "gated": False, "downloads": 1,
                                    "tags": ["gguf"], "siblings": siblings}))

    def _file(self, repo, name):
        forced = self._status_override("file")
        if forced:
            self._record(forced, 0)
            return self._text(forced, json.dumps({"error": "forced"}))
        path = FILES.get(repo, {}).get(name)
        if not path:
            self._record(404, 0)
            return self._text(404, json.dumps({"error": "Entry not found"}))
        size = os.path.getsize(path)
        start, end = 0, size - 1
        rng = self.headers.get("Range")
        partial = False
        if rng and rng.startswith("bytes="):
            a, _, b = rng[6:].partition("-")
            start = int(a) if a else 0
            end = int(b) if b else size - 1
            end = min(end, size - 1)
            partial = True
        with STATE_LOCK:
            rate, cut, trunc = STATE["rate"], STATE["cut_after"], STATE["truncate"]
            # A cut applies once, to the first full-length response after it was set.
            if cut and end - start > 16 * 1024 * 1024:
                STATE["cut_after"] = 0
            else:
                cut = 0
        length = end - start + 1
        self.send_response(206 if partial else 200)
        self.send_header("Content-Type", "application/octet-stream")
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Content-Length", str(length))
        if partial:
            self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
        self.end_headers()
        sent = 0
        limit = length
        if cut:
            limit = min(limit, cut)
        if trunc:
            limit = min(limit, max(0, trunc - start))
        try:
            with open(path, "rb") as f:
                f.seek(start)
                block = 256 * 1024
                t0 = time.time()
                while sent < limit:
                    chunk = f.read(min(block, limit - sent))
                    if not chunk:
                        break
                    self.wfile.write(chunk)
                    sent += len(chunk)
                    if rate:
                        ahead = sent / rate - (time.time() - t0)
                        if ahead > 0:
                            time.sleep(ahead)
            if cut or trunc:
                # Reset, not a clean close, so the app sees a dropped connection and not a short body.
                self.connection.setsockopt(socket.SOL_SOCKET, socket.SO_LINGER, struct.pack("ii", 1, 0))
                self.close_connection = True
                self.connection.close()
        except (BrokenPipeError, ConnectionResetError):
            pass
        finally:
            self._record(206 if partial else 200, sent)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8765)
    ap.add_argument("--file", nargs=3, action="append", metavar=("REPO", "NAME", "PATH"))
    a = ap.parse_args()
    reset_state()
    for repo, name, path in (a.file or DEFAULT_FILES):
        if not os.path.exists(path):
            print(f"missing {path}", file=sys.stderr)
            continue
        FILES.setdefault(repo, {})[name] = path
        sha256_of(path)
    srv = ThreadingHTTPServer(("127.0.0.1", a.port), Handler)
    srv.daemon_threads = True
    print(f"fakehub on 127.0.0.1:{a.port}", flush=True)
    srv.serve_forever()


if __name__ == "__main__":
    main()
