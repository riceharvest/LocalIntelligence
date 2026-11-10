#!/usr/bin/env python3
"""Judges one first-try answer from a DONE line (the debug channel's json) for the four everyday questions.

    judge.py QUESTION JSON      prints "ok", or why not (approval-card, refusal, tool:NAME, wrong, short, ...)

A question counts as right only when it is answered on the first try (no approval card, no stop) and says what the
question needs: hi / a sleep tip: a reply with no tool and no refusal; 17 times 23: 391; battery: device.battery and
a percentage. A "refusal" is the assistant saying it cannot do something that needs no tool at all.
"""
import json
import re
import sys

q, raw = sys.argv[1], sys.argv[2]
try:
    j = json.loads(raw)
except Exception:
    print("no-json")
    sys.exit()
out = j.get("outcome")
tools = [t.get("name") for t in j.get("tools", []) if t.get("name") and t.get("name") != "tools.find"]
ans = (j.get("answer") or "").strip()
refuse = re.search(r"\b(can't|cannot|can not|unable|not able|sorry|don't have|do not have|i am just)\b", ans, re.I)
if out == "awaiting_approval":
    print("approval-card")
elif out != "answer":
    print("outcome-" + str(out))
elif q == "hi":
    print("tool:" + ",".join(tools) if tools else ("refusal" if refuse else ("ok" if ans else "empty")))
elif q == "Give me a sleep tip":
    print("tool:" + ",".join(tools) if tools else ("refusal" if refuse else ("ok" if len(ans) >= 25 else "short")))
elif q == "What is 17 times 23?":
    bad = [t for t in tools if not t.startswith("calc") and not t.startswith("math")]
    print("tool:" + ",".join(bad) if bad else ("ok" if "391" in ans.replace(",", "") else "wrong"))
else:
    print("ok" if "device.battery" in tools and re.search(r"\d+ ?%", ans) else "no-battery-tool:" + ",".join(tools))
