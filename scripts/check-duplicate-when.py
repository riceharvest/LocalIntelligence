#!/usr/bin/env python3
"""Finds `when` blocks that list the same branch condition twice (what a bad merge leaves).

Heuristic parser: blanks comments, keeps string text (with brackets neutralised), finds each `when` keyword, takes its
brace-balanced body, splits the body into branches at depth 0 by the `->` arrow, and reports a
condition (a comma-separated alternative, whitespace-normalised) that appears twice in one when.
`else` is ignored. Prints file:line of the second occurrence.
"""
import re
import sys
from pathlib import Path

ROOT = Path(sys.argv[1] if len(sys.argv) > 1 else ".")
SKIP = ("/build/", "/.cxx/", "/.gradle/")


NEUTRAL = str.maketrans("(){}[],>$", "\u2768\u2769\u2774\u2775\u298b\u298c\u201a\u203a\u00a4")


def blank_noise(src: str) -> str:
    """Replaces comments and string/char literal contents with spaces, keeping offsets and newlines."""
    out = list(src)
    i, n = 0, len(src)
    while i < n:
        c = src[i]
        two = src[i:i + 2]
        if two == "//":
            j = src.find("\n", i)
            j = n if j < 0 else j
            for k in range(i, j):
                out[k] = " "
            i = j
        elif two == "/*":
            depth, j = 1, i + 2
            while j < n and depth:
                if src[j:j + 2] == "/*":
                    depth += 1
                    j += 2
                elif src[j:j + 2] == "*/":
                    depth -= 1
                    j += 2
                else:
                    j += 1
            for k in range(i, min(j, n)):
                if src[k] != "\n":
                    out[k] = " "
            i = j
        elif src[i:i + 3] == '"""':
            j = src.find('"""', i + 3)
            j = n if j < 0 else j + 3
            for k in range(i + 3, max(i + 3, j - 3)):
                if src[k] != "\n":
                    out[k] = src[k].translate(NEUTRAL)
            i = j
        elif c == '"':
            j = i + 1
            while j < n and src[j] != '"' and src[j] != "\n":
                j += 2 if src[j] == "\\" else 1
            for k in range(i + 1, min(j, n)):
                out[k] = src[k].translate(NEUTRAL)
            i = j + 1
        elif c == "'":
            m = re.match(r"'(\\.[^']*|[^\\'])'", src[i:i + 12])
            if m:
                for k in range(i + 1, i + m.end() - 1):
                    out[k] = src[k].translate(NEUTRAL)
                i += m.end()
            else:
                i += 1
        else:
            i += 1
    return "".join(out)


def match(s: str, i: int, open_c: str, close_c: str) -> int:
    depth = 0
    for j in range(i, len(s)):
        if s[j] == open_c:
            depth += 1
        elif s[j] == close_c:
            depth -= 1
            if depth == 0:
                return j
    return -1


def branches(body: str):
    """Yields (offset, condition) for each branch of a when body (text between its braces)."""
    i, n = 0, len(body)
    start = 0
    depth = 0
    while i < n:
        c = body[i]
        if c in "([{":
            depth += 1
        elif c in ")]}":
            depth -= 1
        elif depth == 0 and body[i:i + 2] == "->":
            yield start, body[start:i]
            # Skip the branch's body.
            j = i + 2
            while j < n and body[j] in " \t":
                j += 1
            if j < n and body[j] == "{":
                e = match(body, j, "{", "}")
                j = n if e < 0 else e + 1
                start = j
                i = j
                continue
            d = 0
            while j < n:
                ch = body[j]
                if ch in "([{":
                    d += 1
                elif ch in ")]}":
                    d -= 1
                elif ch == "\n" and d == 0:
                    k = j + 1
                    while k < n and body[k] in " \t\n":
                        k += 1
                    nxt = body[k:k + 2]
                    prev = body[:j].rstrip()
                    cont = nxt[:1] in (".", "+", "*", "/", "%") or nxt in ("?.", "?:", "&&", "||", "->") \
                        or prev.endswith(("+", "-", "*", "/", "&&", "||", "?:", ",", "=", "->", "?.", "."))
                    if not cont:
                        break
                j += 1
            start = j
            i = j
            continue
        i += 1


found = 0
for path in sorted(ROOT.rglob("*.kt")):
    if any(s in str(path) for s in SKIP):
        continue
    raw = path.read_text(errors="replace")
    clean = blank_noise(raw)
    for m in re.finditer(r"\bwhen\b", clean):
        i = m.end()
        while i < len(clean) and clean[i] in " \t\n":
            i += 1
        if i < len(clean) and clean[i] == "(":
            e = match(clean, i, "(", ")")
            if e < 0:
                continue
            i = e + 1
            while i < len(clean) and clean[i] in " \t\n":
                i += 1
        if i >= len(clean) or clean[i] != "{":
            continue
        e = match(clean, i, "{", "}")
        if e < 0:
            continue
        body = clean[i + 1:e]
        seen = {}
        for off, raw_cond in branches(body):
            cond = raw_cond.strip()
            lead = len(raw_cond) - len(raw_cond.lstrip())
            if not cond or cond == "else":
                continue
            # Alternatives at depth 0 of the condition.
            alts, d, cur = [], 0, ""
            for ch in cond:
                if ch in "([{":
                    d += 1
                elif ch in ")]}":
                    d -= 1
                if ch == "," and d == 0:
                    alts.append(cur)
                    cur = ""
                else:
                    cur += ch
            alts.append(cur)
            for alt in alts:
                key = re.sub(r"\s+", " ", alt).strip()
                if not key or key == "else":
                    continue
                # A `when` without a subject has boolean conditions: a repeated one is still a duplicate.
                line = clean.count("\n", 0, i + 1 + off + lead) + 1
                if key in seen:
                    found += 1
                    print(f"{path.relative_to(ROOT)}:{line}: duplicate branch `{key}` (first at line {seen[key]})")
                else:
                    seen[key] = line
print(f"{found} duplicate branch condition(s)")
sys.exit(1 if found else 0)
