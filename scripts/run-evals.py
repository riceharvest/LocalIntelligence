#!/usr/bin/env python3
"""Runs the task list in evals/tasks.jsonl and writes one result file per model, device and app build.

Two runners, and the result file says which one made it (`runner`):

  device-app          the real app on a phone, driven by scripts/devicectl.sh (debug build only).
                      This is the measurement that counts for the phone. Device rows are the only
                      ones bundled into the app (docs/scores/build_scores.py --bundle).

  desktop-app-loop    the app's own agent loop on a desktop llama-server, through a harness the
                      user supplies with --desktop-cmd. This runner does NOT drive a model itself:
                      a Python loop is not the app's loop, so it would measure something else.

The harness command (desktop) is run once per seed with two placeholders:
  {tasks}    the tasks file path
  {seed}     the seed number
  {task_ids} comma-separated task ids still missing for this seed (resume)
and must print one JSON object per line, one per run:
  {"task_id", "seed", "calls": [{"name", "args"}], "answer": str, "steps", "input_tokens",
   "output_tokens", "prefill_ms", "decode_ms", "first_token_ms", "total_ms", "invalid_tool_calls",
   "error": null | str}
Nulls mean "not measured". The verdicts are computed here, never by the harness.

Verdicts: pass | fail | unverified. A check that cannot be decided from what the runner could
observe is `unverified`, never a pass. Device answer checks on fixture tasks are unverified, because
the phone's tools return real data, not the fixtures the desktop harness uses.

Usage examples:
  scripts/run-evals.py --target device --serial SERIAL --model-repo LiquidAI/LFM3-1.7B-GGUF \
      --model-file LFM3-1.7B-QAD-Q4_0.gguf --repeats 3
  scripts/run-evals.py --target desktop --model-id LFM3-1.7B-QAD-Q4_0 --desktop-cmd \
      "python3 /path/to/harness.py --tasks {tasks} --seed {seed} --only {task_ids}" --seeds 1,2,3 \
      --server-pid 1234

Resume: results are keyed by (task id, repeat or seed). A rerun with the same output file skips
every run already recorded. --fresh discards the file first.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import statistics
import subprocess
import sys
import time
import unicodedata
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TASKS_DEFAULT = ROOT / "evals" / "tasks.jsonl"
RESULTS_DEFAULT = ROOT / "docs" / "scores" / "results"
DEVICECTL = ROOT / "scripts" / "devicectl.sh"
PACKAGE_DEFAULT = "dev.localintelligence.app"
SCHEMA_VERSION = 1
GIB = 1024 ** 3

# Tools a phone run may approve. Anything else that asks for approval is denied, so a run can
# never place a call or send a message. Keep in step with the Technical approval tiers.
APPROVE_ON_DEVICE = {
    "timer.set", "alarm.create", "alarm.list", "message.draft", "email.draft", "phone.volume",
    "phone.dnd", "phone.flashlight", "maps.navigate", "device.battery", "device.info",
    "calendar.search", "contacts.search", "location.get", "web.search", "web.fetch",
    "weather.get", "tools.find", "observations.read", "history.search",
}


# ---------- normalisation and checks ----------

def normalise(text) -> str:
    """Compatibility-fold, casefold, and map every Unicode decimal digit to ASCII.

    Some languages write digits in their own script (Arabic-Indic, Devanagari, Bengali) and
    CJK text may use full-width digits. A check on "37" must match all of them.
    """
    s = unicodedata.normalize("NFKC", str(text)).casefold()
    return "".join(str(unicodedata.decimal(ch)) if unicodedata.decimal(ch, None) is not None else ch for ch in s)


def args_match(actual, expected: dict):
    """True / False, or None when the call's arguments are not known."""
    if actual is None:
        return None
    return all(k in actual and actual[k] == v for k, v in expected.items())


def args_text_match(actual, expected: dict):
    if actual is None:
        return None
    for key, options in expected.items():
        if key not in actual:
            return False
        value = normalise(json.dumps(actual[key], ensure_ascii=False) if not isinstance(actual[key], str) else actual[key])
        if not any(normalise(opt) in value for opt in options):
            return False
    return True


def args_not_match(actual, expected: dict):
    if actual is None:
        return None
    # A missing key means the tool's default, which is not the value named here.
    return all(k not in actual or actual[k] != v for k, v in expected.items())


def check_one(check: dict, calls: list, answer: str, fixture_on_device: bool):
    """Returns (status, reason). status is pass, fail or unverified."""
    kind = check["type"]
    names = [c["name"] for c in calls]
    if kind == "no_calls":
        return ("fail", f"called {names}") if calls else ("pass", "")
    if kind == "forbid":
        hit = [n for n in names if n in check["names"]]
        return ("fail", f"called forbidden {hit}") if hit else ("pass", "")
    if kind == "only":
        stray = [n for n in names if n not in check["names"]]
        return ("fail", f"stray calls {stray}") if stray else ("pass", "")
    if kind == "any_call":
        return ("pass", "") if any(n in check["names"] for n in names) else ("fail", f"none of {check['names']} in {names}")
    if kind == "call":
        matches = [c for c in calls if c["name"] == check["name"]]
        if not matches:
            return "fail", f"no {check['name']} in {names}"
        results = []
        for c in matches:
            if "args" in check:
                results.append(args_match(c.get("args"), check["args"]))
            elif "args_text" in check:
                results.append(args_text_match(c.get("args"), check["args_text"]))
            elif "args_not" in check:
                results.append(args_not_match(c.get("args"), check["args_not"]))
            else:
                results.append(True)
        if any(r is True for r in results):
            return "pass", ""
        if any(r is None for r in results):
            return "unverified", f"{check['name']} arguments not reported"
        return "fail", f"{check['name']} arguments wrong: {[c.get('args') for c in matches]}"
    if kind == "answer_min_chars":
        ok = len(answer.strip()) >= check["n"]
        return ("pass", "") if ok else ("fail", f"answer shorter than {check['n']} chars")
    if kind == "answer":
        when = check.get("when_call")
        if when and not any(n == when for n in names):
            return "pass", "skipped: " + when + " not called"
        if fixture_on_device:
            return "unverified", "fixture answer on a phone: its tools return real data"
        text = normalise(answer)
        if "all" in check:
            missing = [s for s in check["all"] if normalise(s) not in text]
            return ("fail", f"answer lacks {missing}") if missing else ("pass", "")
        return ("pass", "") if any(normalise(s) in text for s in check["any"]) else ("fail", f"answer lacks any of {check['any']}")
    return "unverified", f"unknown check type {kind}"


def grade(task: dict, calls: list, answer: str, target: str) -> tuple[str, list]:
    fixture_on_device = bool(task.get("fixture")) and target == "device-app"
    statuses, reasons = [], []
    for check in task["checks"]:
        status, reason = check_one(check, calls, answer, fixture_on_device)
        statuses.append(status)
        if reason:
            reasons.append(reason)
    if "fail" in statuses:
        return "fail", reasons
    if "unverified" in statuses:
        return "unverified", reasons
    return "pass", reasons


# ---------- device access ----------

def adb(serial, *args, timeout=60) -> str:
    cmd = ["adb"] + (["-s", serial] if serial else []) + list(args)
    return subprocess.run(cmd, capture_output=True, text=True, timeout=timeout).stdout


def devicectl(serial, *args, timeout=700) -> dict:
    cmd = [str(DEVICECTL)] + (["-s", serial] if serial else []) + list(args)
    proc = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
    line = next((l for l in reversed(proc.stdout.splitlines()) if l.strip()), "")
    try:
        return json.loads(line)
    except json.JSONDecodeError:
        return {"ok": False, "error": f"no JSON from devicectl {args[0] if args else ''}: {line[:200]}"}


def speed_class(features: set) -> str:
    """Same rule as SimpleSetup.readSpeedClass: i8mm FAST, asimddp MID, neither SLOW, unreadable MID."""
    if not features:
        return "MID"
    if "i8mm" in features:
        return "FAST"
    if "asimddp" in features:
        return "MID"
    return "SLOW"


def device_class(ram_bytes: int | None, speed: str) -> str | None:
    """The string SimpleSetup.deviceClass returns. Keep the thresholds in step with the app."""
    if ram_bytes is None:
        return None
    if ram_bytes < 3.2 * GIB:
        band = "ram<3.2"
    elif ram_bytes < 4.6 * GIB:
        band = "ram3.2-4.6"
    elif ram_bytes < 9.5 * GIB:
        band = "ram4.6-9.5"
    else:
        band = "ram9.5+"
    return f"{band}/{speed}"


def read_device(serial) -> dict:
    model = adb(serial, "shell", "getprop", "ro.product.model").strip()
    android = adb(serial, "shell", "getprop", "ro.build.version.release").strip()
    ram_bytes = None
    for line in adb(serial, "shell", "cat", "/proc/meminfo").splitlines():
        if line.startswith("MemTotal:"):
            ram_bytes = int(line.split()[1]) * 1024
    features = set()
    for line in adb(serial, "shell", "cat", "/proc/cpuinfo").splitlines():
        if line.startswith("Features"):
            features = set(line.split(":", 1)[1].split())
            break
    speed = speed_class(features)
    return {
        "serial": serial or "",
        "model": model,
        "android": android,
        "ram_bytes": ram_bytes,
        "speed_class": speed,
        "device_class": device_class(ram_bytes, speed),
    }


def vm_hwm_kb(pid) -> int | None:
    """Peak resident set of a process, from /proc/<pid>/status. None when unreadable."""
    if pid is None:
        return None
    try:
        text = open(f"/proc/{pid}/status").read()
    except OSError:
        return None
    m = re.search(r"VmHWM:\s+(\d+) kB", text)
    return int(m.group(1)) if m else None


def device_pid(serial, package) -> int | None:
    out = adb(serial, "shell", "pidof", "-s", package).strip()
    if not out.isdigit():
        return None
    status = adb(serial, "shell", "cat", f"/proc/{out}/status")
    m = re.search(r"VmHWM:\s+(\d+) kB", status)
    return int(m.group(1)) if m else None


# ---------- records ----------

def record_from_done(d: dict) -> dict:
    """A DONE line from the debug receiver (DebugControl.kt) as the runner's per-turn record."""
    calls = []
    for t in d.get("tools") or []:
        args = t.get("args")
        calls.append({
            "name": t.get("name"),
            "args": args if isinstance(args, dict) else None,
            "success": t.get("success"),
            "refused": t.get("ms") is None and t.get("success") is False,
        })
    gens = d.get("generations") if isinstance(d.get("generations"), list) else None
    return {
        "outcome": d.get("outcome"),
        "answer": d.get("answer") or "",
        "calls": calls,
        "steps": len(gens) if gens is not None else None,
        "input_tokens": sum(g.get("promptTokens") or 0 for g in gens) if gens else None,
        "output_tokens": sum(g.get("decodeTokens") or 0 for g in gens) if gens else None,
        "prefill_ms": sum(g.get("prefillMs") or 0 for g in gens) if gens and any(g.get("prefillMs") for g in gens) else None,
        "decode_tok_s": _mean([g.get("decodeTokPerS") for g in gens or []]),
        "first_token_ms": None,  # DebugControl always sends null: no time-to-first-token in the metrics.
        "total_ms": d.get("total_ms"),
        "error": None,
        # Refused before execution: the call has no time and success is false.
        "invalid_tool_calls": sum(1 for t in d.get("tools") or [] if t.get("success") is False and t.get("ms") is None),
    }


def record_from_harness(d: dict) -> dict:
    calls = [{"name": c.get("name"), "args": c.get("args"), "success": None, "refused": False}
             for c in d.get("calls") or []]
    return {
        "outcome": "answer" if not d.get("error") else "error",
        "answer": d.get("answer") or "",
        "calls": calls,
        "steps": d.get("steps"),
        "input_tokens": d.get("input_tokens"),
        "output_tokens": d.get("output_tokens"),
        "prefill_ms": d.get("prefill_ms"),
        "decode_tok_s": _rate(d.get("output_tokens"), d.get("decode_ms")),
        "first_token_ms": d.get("first_token_ms"),
        "total_ms": d.get("total_ms"),
        "error": d.get("error"),
        "invalid_tool_calls": d.get("invalid_tool_calls"),
    }


def _mean(values):
    vals = [v for v in values if isinstance(v, (int, float))]
    return round(sum(vals) / len(vals), 2) if vals else None


def _rate(tokens, ms):
    if not tokens or not ms:
        return None
    return round(tokens * 1000.0 / ms, 2)


def aggregate_turns(turns: list[dict]) -> dict:
    """Sums one run's turns. Checks use only the last turn; metrics cover every turn."""
    last = turns[-1]
    def total(key):
        vals = [t.get(key) for t in turns]
        return sum(v for v in vals if v is not None) if any(v is not None for v in vals) else None
    calls_all = [c for t in turns for c in t["calls"]]
    invalid = total("invalid_tool_calls")
    known = all(c.get("args") is not None for c in calls_all)
    duplicates = None
    if known:
        seen, dup = set(), 0
        for c in calls_all:
            key = (c["name"], json.dumps(c["args"], sort_keys=True))
            if key in seen:
                dup += 1
            seen.add(key)
        duplicates = dup
    return {
        "outcome": last["outcome"],
        "answer": last["answer"],
        "calls": last["calls"],
        "steps": total("steps"),
        "tool_calls": len(calls_all),
        "invalid_tool_calls": invalid,
        "duplicate_calls": duplicates,
        "input_tokens": total("input_tokens"),
        "output_tokens": total("output_tokens"),
        "prefill_ms": total("prefill_ms"),
        "prefill_tok_s": None,  # Not measured: DebugControl cannot say how many prompt tokens were reused.
        "first_token_ms": last.get("first_token_ms"),
        "decode_tok_s": _mean([t.get("decode_tok_s") for t in turns]),
        "total_ms": total("total_ms"),
    }


# ---------- device runner ----------

def run_device_task(serial, task, package, timeout_s, stats) -> dict:
    turns = []
    if not task.get("new_session_per_turn"):
        devicectl(serial, "newchat")
    for turn in task["turns"]:
        if task.get("new_session_per_turn"):
            devicectl(serial, "newchat")
        rec = devicectl(serial, "ask", turn, str(timeout_s), timeout=timeout_s + 120)
        if rec.get("outcome") is None:
            return {"error": rec.get("error", "no DONE line"), "turns": turns}
        rec = record_from_done(rec)
        approvals = 0
        while rec["outcome"] == "awaiting_approval" and approvals < 3:
            approvals += 1
            pending = rec["calls"][-1]["name"] if rec["calls"] else None
            verb = "allow" if pending in APPROVE_ON_DEVICE else "deny"
            stats["approvals"].append({"task": task["id"], "tool": pending, "decision": verb})
            nxt = devicectl(serial, "approve", verb)
            if nxt.get("outcome") is None:
                return {"error": f"approval answer had no outcome: {str(nxt)[:160]}", "turns": turns + [rec]}
            rec = record_from_done(nxt)
        turns.append(rec)
        if rec["outcome"] == "awaiting_approval":
            return {"error": "still awaiting approval after 3 decisions", "turns": turns}
    return {"error": None, "turns": turns}


# ---------- desktop runner ----------

def run_desktop_batch(cmd_template, tasks_path, seed, task_ids, timeout_s) -> dict[str, dict]:
    cmd = cmd_template.format(tasks=str(tasks_path), seed=seed, task_ids=",".join(task_ids))
    proc = subprocess.run(cmd, shell=True, capture_output=True, text=True, timeout=timeout_s)
    out = {}
    for line in proc.stdout.splitlines():
        line = line.strip()
        if not line.startswith("{"):
            continue
        d = json.loads(line)
        out[d["task_id"]] = d
    return out


# ---------- files ----------

def git_commit() -> tuple[str, bool]:
    sha = subprocess.run(["git", "-C", str(ROOT), "rev-parse", "--short", "HEAD"],
                         capture_output=True, text=True).stdout.strip() or "unknown"
    dirty = bool(subprocess.run(["git", "-C", str(ROOT), "status", "--porcelain"],
                                capture_output=True, text=True).stdout.strip())
    return sha, dirty


def slug(text: str) -> str:
    return re.sub(r"[^A-Za-z0-9._-]+", "-", text).strip("-") or "unknown"


def write_atomic(path: Path, data: dict):
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    os.replace(tmp, path)


def summarise(runs: list[dict], tasks: list[dict], repeats: list) -> dict:
    wanted = {t["id"] for t in tasks}
    runs = [r for r in runs if r["task_id"] in wanted]
    by_cat: dict[str, dict] = {}
    for r in runs:
        c = by_cat.setdefault(r["category"], {"n": 0, "pass": 0, "fail": 0, "unverified": 0})
        c["n"] += 1
        c[r["verdict"]] += 1
    for c in by_cat.values():
        c["pass_rate"] = round(c["pass"] / c["n"], 4) if c["n"] else None
    n = len(runs)
    passed = sum(1 for r in runs if r["verdict"] == "pass")
    expected = len(tasks) * len(repeats)
    def med(key):
        vals = [r[key] for r in runs if isinstance(r.get(key), (int, float))]
        return round(statistics.median(vals), 2) if vals else None
    return {
        "runs": n,
        "expected_runs": expected,
        "complete": n == expected,
        "pass": passed,
        "fail": sum(1 for r in runs if r["verdict"] == "fail"),
        "unverified": sum(1 for r in runs if r["verdict"] == "unverified"),
        "pass_rate": round(passed / n, 4) if n else None,
        "by_category": by_cat,
        "median": {
            "decode_tok_s": med("decode_tok_s"),
            "prefill_ms": med("prefill_ms"),
            "first_token_ms": med("first_token_ms"),
            "total_ms": med("total_ms"),
            "steps": med("steps"),
            "input_tokens": med("input_tokens"),
            "output_tokens": med("output_tokens"),
        },
        "peak_mem_kb_max": max((r["peak_mem_kb"] for r in runs if r.get("peak_mem_kb")), default=None),
    }


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--target", choices=["device", "desktop"], required=True)
    ap.add_argument("--tasks", type=Path, default=TASKS_DEFAULT)
    ap.add_argument("--out-dir", type=Path, default=RESULTS_DEFAULT)
    ap.add_argument("--model-id", required=True, help="a label for the model, e.g. LFM3-1.7B-QAD-Q4_0")
    ap.add_argument("--model-repo", default=None, help="Hugging Face repo, as SimpleSetup names it (for bundling)")
    ap.add_argument("--model-file", default=None, help="file name, as SimpleSetup names it (for bundling)")
    ap.add_argument("--quant", default=None)
    ap.add_argument("--serial", default=None, help="adb serial (device)")
    ap.add_argument("--package", default=PACKAGE_DEFAULT, help="app package (device)")
    ap.add_argument("--repeats", type=int, default=1, help="device: how many times each task runs")
    ap.add_argument("--timeout", type=int, default=600, help="seconds allowed per device turn")
    ap.add_argument("--seeds", default="1", help="desktop: comma-separated seeds")
    ap.add_argument("--desktop-cmd", default=None, help="desktop: harness command template (see the top of this file)")
    ap.add_argument("--desktop-label", default="desktop", help="desktop: host label recorded in the result")
    ap.add_argument("--server-pid", type=int, default=None, help="desktop: llama-server pid, for peak memory")
    ap.add_argument("--only", default=None, help="comma-separated task ids to run")
    ap.add_argument("--fresh", action="store_true", help="discard an existing result file first")
    ap.add_argument("--dry-run", action="store_true", help="validate the tasks and print the plan, run nothing")
    args = ap.parse_args()

    tasks = [json.loads(l) for l in args.tasks.read_text(encoding="utf-8").splitlines() if l.strip()]
    ids = [t["id"] for t in tasks]
    if len(ids) != len(set(ids)):
        print("duplicate task ids in " + str(args.tasks), file=sys.stderr)
        return 2
    if args.only:
        wanted = set(args.only.split(","))
        tasks = [t for t in tasks if t["id"] in wanted]
    tasks_sha = hashlib.sha256(args.tasks.read_bytes()).hexdigest()

    if args.target == "desktop":
        if not args.desktop_cmd:
            print("--desktop-cmd is required for the desktop runner", file=sys.stderr)
            return 2
        repeats = [int(s) for s in args.seeds.split(",")]
        runner = "desktop-app-loop"
    else:
        if not args.serial and not os.environ.get("ANDROID_SERIAL"):
            print("note: no --serial; adb uses the only attached device", file=sys.stderr)
        repeats = list(range(1, args.repeats + 1))
        runner = "device-app"

    if args.dry_run:
        print(f"{len(tasks)} tasks x {len(repeats)} {'seeds' if runner != 'device-app' else 'repeats'} = {len(tasks) * len(repeats)} runs ({runner})")
        return 0

    sha, dirty = git_commit()
    device = None
    if runner == "device-app":
        device = read_device(args.serial)
        app_version = adb(args.serial, "shell", "dumpsys", "package", args.package)
        vm = re.search(r"versionName=(\S+)", app_version)
        host_key = device["model"] or "device"
        out_name = f"device__{slug(args.model_id)}__{slug(host_key)}__{sha}.json"
    else:
        host_key = args.desktop_label
        vm = None
        out_name = f"desktop__{slug(args.model_id)}__{slug(host_key)}__{sha}.json"
    out_path = args.out_dir / out_name

    prior = {}
    if out_path.exists() and not args.fresh:
        doc = json.loads(out_path.read_text(encoding="utf-8"))
        for r in doc.get("runs", []):
            prior[(r["task_id"], run_key(r))] = r
        print(f"resuming: {len(prior)} runs already in {out_path}", file=sys.stderr)

    doc = {
        "schemaVersion": SCHEMA_VERSION,
        "runner": runner,
        "run_started": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "run_finished": None,
        "tasks_file": str(args.tasks.relative_to(ROOT)) if args.tasks.is_relative_to(ROOT) else str(args.tasks),
        "tasks_sha256": tasks_sha,
        "task_count": len(tasks),
        "model": {"id": args.model_id, "repo": args.model_repo, "file": args.model_file, "quant": args.quant},
        "device": device,
        "host": None if device else {"label": args.desktop_label, "server_pid": args.server_pid},
        "app": {"package": args.package if device else None, "commit": sha, "dirty": dirty,
                "version_name": vm.group(1) if vm else None},
        "repeats": repeats,
        "runs": [],
    }
    stats = {"approvals": []}
    runs_by_key = dict(prior)

    if runner == "device-app":
        prev_mode = None
        state = devicectl(args.serial, "state")
        prev_mode = state.get("mode")
        devicectl(args.serial, "mode", "technical")  # Simple mode auto-accepts the call and message tiers.
        try:
            for rep in repeats:
                for task in tasks:
                    key = (task["id"], rep)
                    if key in runs_by_key:
                        continue
                    t0 = time.monotonic()
                    res = run_device_task(args.serial, task, args.package, args.timeout, stats)
                    peak = device_pid(args.serial, args.package)
                    rec = _finish_run(task, rep, None, res, runner, peak, time.monotonic() - t0)
                    runs_by_key[key] = rec
                    doc["runs"] = list(runs_by_key.values())
                    write_atomic(out_path, _with_summary(doc, tasks, repeats))
                    print(f"{task['id']} r{rep}: {rec['verdict']} {rec['reasons'][:2]}", file=sys.stderr)
        finally:
            if prev_mode in ("simple", "technical"):
                devicectl(args.serial, "mode", prev_mode)
            else:
                print("warning: could not read the phone's mode; it is left in Technical", file=sys.stderr)
    else:
        for seed in repeats:
            missing = [t["id"] for t in tasks if (t["id"], seed) not in runs_by_key]
            if not missing:
                continue
            t0 = time.monotonic()
            batch = run_desktop_batch(args.desktop_cmd, args.tasks, seed, missing, timeout_s=3600)
            per_run = (time.monotonic() - t0) / max(1, len(missing))
            peak = vm_hwm_kb(args.server_pid)
            for task in tasks:
                if task["id"] not in missing:
                    continue
                d = batch.get(task["id"])
                if d is None:
                    res = {"error": "harness printed no line for this task", "turns": []}
                    rec = _finish_run(task, None, seed, res, runner, peak, per_run)
                else:
                    rec = _finish_run(task, None, seed, {"error": d.get("error"), "turns": [record_from_harness(d)]},
                                      runner, peak, per_run)
                runs_by_key[(task["id"], seed)] = rec
                doc["runs"] = list(runs_by_key.values())
                write_atomic(out_path, _with_summary(doc, tasks, repeats))
                print(f"{task['id']} s{seed}: {rec['verdict']} {rec['reasons'][:2]}", file=sys.stderr)

    doc["run_finished"] = datetime.now(timezone.utc).isoformat(timespec="seconds")
    doc["approvals"] = stats["approvals"]
    write_atomic(out_path, _with_summary(doc, tasks, repeats))
    print(str(out_path))
    return 0


def run_key(rec: dict):
    """A run's place in the set: its repeat (device) or its seed (desktop)."""
    return rec["repeat"] if rec.get("repeat") is not None else rec.get("seed")


def _finish_run(task, repeat, seed, res, runner, peak_kb, seconds) -> dict:
    if res.get("error") and not res.get("turns"):
        verdict, reasons, m = "unverified", [res["error"]], None
    elif res.get("error"):
        verdict, reasons = "unverified", [res["error"]]
        m = aggregate_turns(res["turns"])
    else:
        m = aggregate_turns(res["turns"])
        verdict, reasons = grade(task, m["calls"], m["answer"], runner)
    rec = {
        "task_id": task["id"],
        "category": task["category"],
        "lang": task["lang"],
        "fixture": bool(task.get("fixture")),
        "repeat": repeat,
        "seed": seed,
        "verdict": verdict,
        "reasons": reasons,
        "outcome": m["outcome"] if m else None,
        "answer": (m["answer"][:400] if m else ""),
        "calls": [{"name": c["name"], "args": c.get("args")} for c in (m["calls"] if m else [])],
        "steps": m.get("steps") if m else None,
        "tool_calls": m.get("tool_calls") if m else None,
        "invalid_tool_calls": m.get("invalid_tool_calls") if m else None,
        "duplicate_calls": m.get("duplicate_calls") if m else None,
        "input_tokens": m.get("input_tokens") if m else None,
        "output_tokens": m.get("output_tokens") if m else None,
        "prefill_ms": m.get("prefill_ms") if m else None,
        "prefill_tok_s": None,
        "first_token_ms": m.get("first_token_ms") if m else None,
        "decode_tok_s": m.get("decode_tok_s") if m else None,
        "total_ms": m.get("total_ms") if m else None,
        "wall_s": round(seconds, 1),
        "peak_mem_kb": peak_kb,
    }
    return rec


def _with_summary(doc: dict, tasks: list, repeats: list) -> dict:
    doc = dict(doc)
    doc["summary"] = summarise(doc["runs"], tasks, repeats)
    return doc


if __name__ == "__main__":
    sys.exit(main())
