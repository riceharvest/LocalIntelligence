#!/usr/bin/env python3
"""UI helpers for toggle-speedtest.sh. Each subcommand reads one uiautomator dump.

    toggle-speedtest-ui.py nav    DUMP PKG               "x y" of the lowest node named "Settings"
    toggle-speedtest-ui.py row    DUMP PKG LABEL         "y": the vertical centre of the row that says LABEL
    toggle-speedtest-ui.py switch DUMP PKG LABEL         "x y checked=STATE": the switch in LABEL's row
    toggle-speedtest-ui.py tap    DUMP PKG LABEL         "x y": the clickable node that carries LABEL
    toggle-speedtest-ui.py result DUMP PKG ARM RUN PSS   one TSV row on stdout, a short report on stderr

Every subcommand ignores nodes of other packages. It matches the text and the content
description of a node, ignores case and space at the ends, and prefers an exact match to a
substring match. It exits 1 with one sentence on stderr when it finds nothing.
"""
import re
import sys
import xml.etree.ElementTree as ET

BOUNDS = re.compile(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]")


def die(message, code=1):
    print(message, file=sys.stderr)
    sys.exit(code)


def load(path):
    try:
        return ET.parse(path).getroot()
    except (ET.ParseError, OSError):
        die("The UI dump is not readable.")


def bounds(node):
    m = BOUNDS.match(node.get("bounds", ""))
    return tuple(map(int, m.groups())) if m else None


def centre(box):
    x1, y1, x2, y2 = box
    return (x1 + x2) // 2, (y1 + y2) // 2


def app_nodes(root, pkg):
    """Every node of the app that has bounds, as (node, (x1, y1, x2, y2))."""
    for node in root.iter("node"):
        if node.get("package") != pkg:
            continue
        box = bounds(node)
        if box:
            yield node, box


def labels(node):
    """The lower-case text and content description of a node, empty ones left out."""
    out = []
    for key in ("text", "content-desc"):
        value = (node.get(key) or "").strip().lower()
        if value:
            out.append(value)
    return out


def find_label(nodes, want):
    """The first node whose label is WANT: an exact match first, then a substring match."""
    want = want.strip().lower()
    for node, box in nodes:
        if want in labels(node):
            return node, box
    for node, box in nodes:
        if any(want in value for value in labels(node)):
            return node, box
    return None


def is_switch(node):
    cls = node.get("class") or ""
    return node.get("checkable") == "true" or "Switch" in cls or "Toggle" in cls


def cmd_nav(root, pkg):
    # The chat screen has a Settings icon in the top bar and a Settings item in the bottom bar.
    # The measurement needs the page that the bottom item opens, so the lowest match wins.
    best = None
    for node, box in app_nodes(root, pkg):
        if "settings" in labels(node) and (best is None or box[1] > best[1]):
            best = box
    if best is None:
        die("No node named Settings is on screen.")
    print(*centre(best))


def cmd_row(root, pkg, want):
    # The shell script uses this y to keep the row out of the bottom navigation bar, where a tap
    # does nothing. Exact matches count before substring matches.
    nodes = list(app_nodes(root, pkg))
    exact, loose = [], []
    target = want.strip().lower()
    for node, box in nodes:
        low = labels(node)
        if not low:
            continue
        y = centre(box)[1]
        if target in low:
            exact.append(y)
        elif any(target in value for value in low):
            loose.append(y)
    found = exact or loose
    if not found:
        die(f"No row on screen says '{want}'.")
    print(found[0])


def cmd_switch(root, pkg, want):
    # Compose puts the label and its Switch in one row. The switch is the checkable node at the
    # height of the label, to the right of it.
    nodes = list(app_nodes(root, pkg))
    label = find_label(nodes, want)
    if label is None:
        die(f"No label '{want}' is on screen.")
    _, (_, ly1, lx2, ly2) = label
    label_cy = (ly1 + ly2) // 2
    best = None
    for node, box in nodes:
        if not is_switch(node):
            continue
        cx, cy = centre(box)
        if abs(cy - label_cy) > 220:  # same row band: Compose rows are tall
            continue
        if box[0] < lx2 - 100:  # the switch sits to the right of the label
            continue
        if best is None or cy == label_cy:
            best = (node, cx, cy)
            if cy == label_cy:
                break
    if best is None:
        die(f"The label '{want}' is on screen but its row has no switch.")
    node, cx, cy = best
    print(cx, cy, f"checked={node.get('checked') or '?'}")


def cmd_tap(root, pkg, want):
    # Compose draws a button as a clickable View around the text. A tap on the centre of the text
    # node hits the row, not the button. So the target is the nearest clickable ancestor of the
    # text node. A node of class Button wins over any other candidate.
    want = want.strip().lower()
    parent = {child: p for p in root.iter() for child in p}
    candidates = []
    for node, _ in app_nodes(root, pkg):
        if (node.get("text") or "").strip().lower() != want:
            continue
        target = node
        if node.get("clickable") != "true":
            walk = node
            while walk in parent:
                walk = parent[walk]
                if walk.get("clickable") == "true":
                    target = walk
                    break
            else:
                if "Button" not in (node.get("class") or ""):
                    continue
        if target.get("clickable") != "true" and "Button" not in (target.get("class") or ""):
            continue
        box = bounds(target)
        if box:
            candidates.append((target.get("class", ""), centre(box)))
    if not candidates:
        die(f"No clickable node says '{want}'.")
    buttons = [c for cls, c in candidates if "Button" in cls]
    print(*(buttons[0] if buttons else candidates[0][1]))


def cmd_result(root, pkg, arm, run, pss):
    """Read the finished Speed test screen. Print one TSV row: arm, run, decode, prefill, pss, status."""
    texts = []
    for node, _ in app_nodes(root, pkg):
        value = (node.get("text") or "").strip()
        if value:
            texts.append(value)
    summary = next((t for t in texts if "tok/s" in t), "NO RESULT LINE")
    footer = next((t for t in texts if "Median of" in t), "")
    failed = next((t for t in texts if t.startswith("Speed test failed")), "")
    refused = next((t for t in texts if "speed test can run" in t.lower() or "Load a model first" in t), "")
    decode = re.search(r"Decode ([\d.]+) tok/s", summary)
    prefill = re.search(r"Prefill ([\d.]+) tok/s", summary)
    status = (failed or refused or summary)[:200]
    print("\t".join([arm, run, decode.group(1) if decode else "FAIL",
                     prefill.group(1) if prefill else "FAIL", pss or "?", status]))
    print(f"  result: arm={arm} run={run} decode={decode.group(1) if decode else '-'} "
          f"prefill={prefill.group(1) if prefill else '-'}", file=sys.stderr)
    print(f"  line: {summary[:220]}", file=sys.stderr)
    if footer:
        print(f"  footer: {footer[:200]}", file=sys.stderr)
    if failed:
        print(f"  FAILED: {failed[:200]}", file=sys.stderr)
    if refused:
        print(f"  REFUSED: {refused[:200]}", file=sys.stderr)


COMMANDS = {
    "nav": (cmd_nav, 0),
    "row": (cmd_row, 1),
    "switch": (cmd_switch, 1),
    "tap": (cmd_tap, 1),
    "result": (cmd_result, 3),
}


def main():
    if len(sys.argv) < 4 or sys.argv[1] not in COMMANDS:
        die(__doc__, 2)
    func, extra = COMMANDS[sys.argv[1]]
    dump, pkg, rest = sys.argv[2], sys.argv[3], sys.argv[4:]
    if len(rest) != extra:
        die(__doc__, 2)
    func(load(dump), pkg, *rest)


if __name__ == "__main__":
    main()
