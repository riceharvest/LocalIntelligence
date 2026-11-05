#!/usr/bin/env python3
"""Reads a uiautomator dump for the e2e scripts: what the person sees, and where to tap.

    ui.py texts DUMP [--pkg PKG]          every visible text and content description, one per line
    ui.py find DUMP TEXT [--pkg PKG]      "x y" of the best node whose text or description matches TEXT
                                          (exact match beats a substring; case-insensitive; exit 1 if none)
    ui.py has DUMP TEXT [--pkg PKG]       exit 0 when some node matches TEXT (a regular expression)
    ui.py banned DUMP --pkg PKG           the banned Simple-mode words that appear in the app's own text
    ui.py clipped DUMP --pkg PKG          text nodes whose bounds leave the screen
    ui.py small DUMP --pkg PKG            clickable nodes under 48 dp (needs --density)
"""
import re
import sys
import xml.etree.ElementTree as ET

BANNED = r"\b(model|llm|token|context|gguf|quant|prefill|inference|ram|tool|plugin|mmproj|provider)s?\b"


def nodes(path, pkg=None):
    try:
        root = ET.parse(path).getroot()
    except ET.ParseError:
        return
    for n in root.iter("node"):
        if pkg and n.get("package") != pkg:
            continue
        m = re.match(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]", n.get("bounds", ""))
        if not m:
            continue
        x1, y1, x2, y2 = map(int, m.groups())
        yield n, (x1, y1, x2, y2)


def labels(n):
    out = []
    for key in ("text", "content-desc"):
        v = (n.get(key) or "").strip()
        if v:
            out.append(v)
    return out


def main():
    cmd, dump = sys.argv[1], sys.argv[2]
    args = sys.argv[3:]
    pkg = None
    density = 420
    rest = []
    i = 0
    while i < len(args):
        if args[i] == "--pkg":
            pkg = args[i + 1]
            i += 2
        elif args[i] == "--density":
            density = int(args[i + 1])
            i += 2
        else:
            rest.append(args[i])
            i += 1
    if cmd == "texts":
        seen = []
        for n, _ in nodes(dump, pkg):
            for t in labels(n):
                if t not in seen:
                    seen.append(t)
        print("\n".join(seen))
    elif cmd == "find":
        want = rest[0].lower()
        best = None
        for n, (x1, y1, x2, y2) in nodes(dump, pkg):
            for t in labels(n):
                tl = t.lower()
                if tl == want:
                    score = 2
                elif want in tl:
                    score = 1
                else:
                    continue
                if best is None or score > best[0]:
                    best = (score, (x1 + x2) // 2, (y1 + y2) // 2)
        if best is None:
            sys.exit(1)
        print(best[1], best[2])
    elif cmd == "has":
        rx = re.compile(rest[0], re.I)
        for n, _ in nodes(dump, pkg):
            if any(rx.search(t) for t in labels(n)):
                return
        sys.exit(1)
    elif cmd == "banned":
        rx = re.compile(BANNED, re.I)
        hits = set()
        for n, _ in nodes(dump, pkg):
            for t in labels(n):
                for m in rx.finditer(t):
                    hits.add(f"{m.group(0).lower()}: {t}")
        print("\n".join(sorted(hits)))
        sys.exit(1 if hits else 0)
    elif cmd == "clipped":
        # The screen is the root node's bounds.
        root = list(nodes(dump))
        if not root:
            sys.exit(0)
        sw, sh = root[0][1][2], root[0][1][3]
        bad = []
        for n, (x1, y1, x2, y2) in nodes(dump, pkg):
            if (n.get("text") or "").strip() and (x1 < 0 or y1 < 0 or x2 > sw or y2 > sh):
                bad.append(f"{n.get('text')} {x1},{y1},{x2},{y2}")
        print("\n".join(bad))
        sys.exit(1 if bad else 0)
    elif cmd == "small":
        dp = density / 160.0
        bad = []
        for n, (x1, y1, x2, y2) in nodes(dump, pkg):
            if n.get("clickable") == "true" and ((x2 - x1) < 48 * dp - 1 or (y2 - y1) < 48 * dp - 1):
                bad.append(f"{labels(n) or n.get('class')} {x2-x1}x{y2-y1}px")
        print("\n".join(bad))
        sys.exit(1 if bad else 0)
    else:
        sys.exit(2)


if __name__ == "__main__":
    main()
