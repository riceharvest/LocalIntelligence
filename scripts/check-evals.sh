#!/usr/bin/env bash
# Gate: the agent eval suite must not regress. (docs/evals.md, arch §"Every
# major feature must be measurable against the agent evaluation suite".)
#
# Runs :core:evals and compares the primary metric (task success) against the
# committed baseline in ci/eval-baseline.txt.
#
# The important part is not the comparison, it is the failure modes. A broken
# eval harness must NOT look like a passing build, so:
#   - a missing "primary:" line in the output  -> FAIL (not "can't find it, shrug")
#   - a suite that ran 0 tasks                  -> FAIL
#   - fewer tasks than the baseline             -> FAIL
# EvalMain's own docs warn about exactly this: "a broken eval runner reports
# zero tasks and looks like a passing build, which is the worst possible outcome".
#
# Usage: scripts/check-evals.sh

set -euo pipefail

cd "$(dirname "$0")/.."

BASELINE="ci/eval-baseline.txt"

baseline_primary=$(awk -F= '/^primary/ {gsub(/ /,"",$2); print $2}' "$BASELINE")
baseline_tasks=$(awk -F= '/^tasks/ {gsub(/ /,"",$2); print $2}' "$BASELINE")

if [ -z "$baseline_primary" ] || [ -z "$baseline_tasks" ]; then
  echo "::error::Could not read primary/tasks from $BASELINE"
  exit 1
fi

echo "eval-regression: running :core:evals (baseline: primary >= $baseline_primary, tasks >= $baseline_tasks)"

# --console=plain keeps the summary parseable; the suite prints one line per
# task plus a "N tasks | M pass (P%)" summary and a "primary:" line.
set +e
output=$(./gradlew :core:evals --console=plain -q 2>&1)
gradle_status=$?
set -e

echo "$output" | grep -E "^\[FAIL\]|tasks \||primary:|secondary:" || true

# 1. The suite itself must have passed. (It exits non-zero if any task failed.)
if [ "$gradle_status" -ne 0 ]; then
  echo "::error::The eval suite did not pass (:core:evals exited $gradle_status)."
  echo "A regression in agent behaviour is a failed build. Fix the agent, not the baseline."
  exit 1
fi

# 2. Parse. Missing markers are a failure, never a pass.
actual_primary=$(echo "$output" | awk '/^primary:/ {print $NF}' | tail -1)
actual_tasks=$(echo "$output" | sed -nE 's/^([0-9]+) tasks \|.*/\1/p' | tail -1)

if [ -z "$actual_primary" ]; then
  echo "::error::No 'primary:' line in the eval output — the suite did not report a score."
  echo "A harness that reports nothing is not a harness that passed."
  exit 1
fi
if [ -z "$actual_tasks" ]; then
  echo "::error::No task count in the eval output — the suite did not report what it ran."
  exit 1
fi

# 3. Task count must not have shrunk. A smaller suite at the same score is
#    weaker evidence, and a suite that quietly drops tasks is how you get a
#    green build that tests nothing.
if [ "$actual_tasks" -lt "$baseline_tasks" ]; then
  echo "::error::Eval suite shrank: ran $actual_tasks tasks, baseline requires $baseline_tasks."
  exit 1
fi

# 4. The actual regression check.
if awk "BEGIN{exit !($actual_primary < $baseline_primary)}"; then
  echo "::error::EVAL REGRESSION: task success is $actual_primary, baseline is $baseline_primary."
  echo "  docs/evals.md: \"If a feature does not move a number here, it does not ship.\""
  echo "  Fix the regression. Lowering the baseline in $BASELINE is a reviewed"
  echo "  decision and shows up as a diff in this PR."
  exit 1
fi

echo "eval-regression: OK — primary $actual_primary (>= $baseline_primary), $actual_tasks tasks (>= $baseline_tasks)"
