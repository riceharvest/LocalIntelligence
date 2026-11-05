#!/usr/bin/env python3
"""A stand-in for the four search services (DuckDuckGo, Bing, Brave, Wikipedia) that the emulator reaches at
10.0.2.2. The debug build's `searchroute` command points each service at /ddg, /bing, /brave or /wiki here, so a
test decides, per service, whether it answers, shows a bot check, errors, answers nothing, or hangs.

    searchhub.py --port 18770

    /_ctl?ddg=ok|block|503|empty|hang   (same for bing, brave, wiki); /_ctl?reset=1 sets every service to ok
    /_log                               one line per request: service mode query
    /_log?clear=1

Every result links to example.com with ?r=<service>, so the page that opens is a public address that web.fetch
accepts, and the service that answered can be read from the link. Standard library only.
"""
import argparse
import json
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, quote, urlparse

LOCK = threading.Lock()
MODE = {}
LOG = []
HANG_SECONDS = 90


def reset():
    MODE.clear()
    MODE.update(ddg="ok", bing="ok", brave="ok", wiki="ok")


def page(service, q):
    town = {"ddg": "Ddgville", "bing": "Bingtown", "brave": "Bravehaven", "wiki": "Wikipolis"}[service]
    link = "https://example.com/?r=" + service
    title = "Capital of Testland is " + town
    snippet = "The capital city of Testland is " + town + "."
    if service == "ddg":
        return "text/html", (
            '<html><body><div class="result"><a class="result__a" href="//duckduckgo.com/l/?uddg=%s">%s</a>'
            '<a class="result__snippet">%s</a></div></body></html>' % (quote(link, safe=""), title, snippet)
        )
    if service == "bing":
        return "text/html", (
            '<html><body><ol><li class="b_algo"><a href="%s"><h2>%s</h2></a>'
            '<div class="b_caption"><p>%s</p></div></li></ol></body></html>' % (link, title, snippet)
        )
    if service == "brave":
        return "text/html", (
            '<html><body><div data-type="web"><a href="%s"><div class="title svelte-x">%s</div></a>'
            '<div class="content desktop svelte-x">%s</div></div></body></html>' % (link, title, snippet)
        )
    return "application/json", json.dumps({"query": {"search": [{"title": "Testland " + town, "snippet": snippet}]}})


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def send(self, code, ctype, body):
        data = body.encode()
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        u = urlparse(self.path)
        qs = parse_qs(u.query)
        if u.path == "/_ctl":
            with LOCK:
                if "reset" in qs:
                    reset()
                for k, v in qs.items():
                    if k in MODE:
                        MODE[k] = v[0]
                self.send(200, "application/json", json.dumps(MODE))
            return
        if u.path == "/_log":
            with LOCK:
                out = "\n".join(LOG)
                if "clear" in qs:
                    LOG.clear()
            self.send(200, "text/plain", out)
            return
        service = u.path.strip("/")
        if service not in MODE:
            self.send(404, "text/plain", "no such service")
            return
        q = (qs.get("q") or qs.get("srsearch") or [""])[0]
        with LOCK:
            mode = MODE[service]
            LOG.append("%.1f %s %s %s" % (time.time(), service, mode, q))
        if mode == "hang":
            # Accept the request and never answer inside the app's time budget.
            time.sleep(HANG_SECONDS)
            return
        if mode == "503":
            self.send(503, "text/plain", "busy")
        elif mode == "block":
            ctype, _ = page(service, q)
            self.send(200, ctype, "<html><body>Please complete the captcha to continue.</body></html>"
                      if ctype == "text/html" else '{"error":"captcha"}')
        elif mode == "empty":
            ctype, _ = page(service, q)
            self.send(200, ctype, "<html><body>No results.</body></html>" if ctype == "text/html"
                      else '{"query":{"search":[]}}')
        else:
            ctype, body = page(service, q)
            self.send(200, ctype, body)


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=18770)
    a = ap.parse_args()
    reset()
    ThreadingHTTPServer(("0.0.0.0", a.port), Handler).serve_forever()
