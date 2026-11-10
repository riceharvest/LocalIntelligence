#!/usr/bin/env python3
"""Reads the results of toggle-speedtest.sh and prints the two arms side by side.

    toggle-speedtest-stats.py [RESULTS_TSV]      (default /tmp/li-e2e-spec/results.tsv)

The columns of the file are: arm, run, decode, prefill, total_pss, status.
A run with FAIL in a number column is left out and listed at the end. A pair needs a good
run in both arms, so a failed run removes its partner from the paired numbers.
"""
import statistics
import sys

DEFAULT_PATH = "/tmp/li-e2e-spec/results.tsv"


def read_rows(path):
    with open(path) as handle:
        lines = handle.read().strip().split("\n")
    return [line.split("\t") for line in lines if line.strip()]


def series(rows, arm, column):
    """{run number: value} for one arm, from the good rows only."""
    return {row[1]: float(row[column]) for row in rows if row[0] == arm}


def describe(name, values_by_run):
    values = sorted(values_by_run.values())
    spread = statistics.stdev(values) if len(values) > 1 else 0.0
    print(f"{name:<14} n={len(values)}  {' '.join(f'{v:.1f}' for v in values)}")
    print(f"{'':<14} mean {statistics.mean(values):.1f}  median {statistics.median(values):.1f}  "
          f"stdev {spread:.1f}  range {min(values):.1f}-{max(values):.1f}")


def paired_delta(label, digits, off, on, runs):
    """Prints on-minus-off for each run, the mean delta and its share of the off mean."""
    deltas = [on[run] - off[run] for run in runs]
    mean_delta = statistics.mean(deltas)
    share = 100 * mean_delta / statistics.mean(off.values())
    shown = " ".join(f"{d:+.1f}" for d in deltas)
    print(f"{label} per pair (on minus off): {shown}")
    print(f"  mean {mean_delta:+.{digits}f} tok/s ({share:+.1f}% of the off mean)")
    return deltas


def spread_share(values_by_run):
    """The range as a share of the mean: how far one run can sit from another."""
    values = list(values_by_run.values())
    return 100 * (max(values) - min(values)) / statistics.mean(values)


def main():
    path = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_PATH
    rows = read_rows(path)
    good = [row for row in rows if len(row) > 3 and row[3] not in ("", "FAIL") and row[2] != "FAIL"]
    failed = [row for row in rows if len(row) > 3 and "FAIL" in (row[2], row[3])]

    decode_off, decode_on = series(good, "off", 2), series(good, "on", 2)
    prefill_off, prefill_on = series(good, "off", 3), series(good, "on", 3)
    if not decode_off or not decode_on:
        sys.exit(f"Both arms need at least one good run. {path} has off={len(decode_off)} on={len(decode_on)}.")
    pairs = sorted(set(decode_off) & set(decode_on), key=int)
    if not pairs:
        sys.exit("No run number has a good result in both arms, so there are no pairs.")

    print(f"Pairs with a good run in both arms: {len(pairs)}")
    print()
    print("Decode tok/s")
    describe("speculation off", decode_off)
    describe("speculation on", decode_on)
    deltas = paired_delta("decode", 2, decode_off, decode_on, pairs)
    print(f"  pairs that favour on: {sum(1 for d in deltas if d > 0)} of {len(deltas)}")
    print(f"  run-to-run spread of decode alone: off {spread_share(decode_off):.1f}%, "
          f"on {spread_share(decode_on):.1f}% of the mean")
    print("  An effect smaller than that spread is not visible in this harness.")
    print()
    print("Prefill tok/s (same runs)")
    describe("speculation off", prefill_off)
    describe("speculation on", prefill_on)
    paired_delta("prefill", 1, prefill_off, prefill_on, pairs)
    if failed:
        print()
        print("Left out (no number on screen): " + ", ".join(f"{row[0]} run {row[1]}" for row in failed))


if __name__ == "__main__":
    main()
