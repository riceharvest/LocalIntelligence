#!/usr/bin/env bash
# Proves every gate in this repo can actually go RED.
#
# A gate that has never been seen to fail is not a gate — it is a comment. This
# script copies the repo to a scratch directory, breaks one thing at a time, and
# asserts the matching gate fails. It also asserts the negative controls: the
# cases that MUST stay green, which is what stops a gate from being so trigger
# happy that everyone learns to bypass it.
#
# Run it:  scripts/test-gates.sh
# It is not part of the CI workflow on purpose — it is a ~3 minute local check
# for whoever changes the gates, and it needs a scratch directory to scribble in.

set -uo pipefail

cd "$(dirname "$0")/.."
REPO="$PWD"
SCRATCH="${SCRATCH_DIR:-${TMPDIR:-/tmp}/li-gate-tests}"
PASS=0
FAIL=0

# Rebuild a pristine copy for each case. Each case gets a clean tree so one
# break can never contaminate the next.
#
# IMPORTANT: this leaves the shell's cwd INSIDE the scratch tree. Every mutation
# below uses relative paths, and a mutation that lands in the real checkout
# instead is worse than a broken test — it silently corrupts the branch.
reset_tree() {
  rm -rf "$SCRATCH"
  mkdir -p "$SCRATCH"
  # --exclude for build outputs AND for anything .gitignore covers. Copying
  # ignored files is how a previous run of this script leaked a 3 MB test
  # artifact from the real checkout into every scratch tree, where it made a
  # negative-control case fail for entirely the wrong reason.
  (cd "$REPO" && tar -cf - \
      --exclude=build --exclude=.gradle --exclude=.kotlin --exclude=.cxx \
      --exclude=.git --exclude=local.properties --exclude=models \
      --exclude-vcs .) | tar -xf - -C "$SCRATCH"
  (cd "$SCRATCH" && git init -q && git add -A \
     && git -c user.email=t@t -c user.name=t commit -qm base)
  cd "$SCRATCH" || exit 1
}

# expect <PASS|FAIL> <gate-script> <description> ; mutation applied by caller first
expect() {
  local want="$1" script="$2" desc="$3"
  local out rc
  out=$(cd "$SCRATCH" && "./scripts/$script" 2>&1)
  rc=$?
  local got="PASS"; [ "$rc" -ne 0 ] && got="FAIL"
  if [ "$got" = "$want" ]; then
    printf '  \033[32mok\033[0m   %-52s expected %s, got %s\n' "$desc" "$want" "$got"
    PASS=$((PASS + 1))
  else
    printf '  \033[31mNOT OK\033[0m %-52s expected %s, got %s\n' "$desc" "$want" "$got"
    echo "$out" | sed 's/^/         /' | head -12
    FAIL=$((FAIL + 1))
  fi
}

section() { printf '\n\033[1m%s\033[0m\n' "$1"; }

# Credential-shaped fixtures are assembled from fragments so that THIS file does
# not itself trip the secret scanner in check-repo-hygiene.sh (which is
# line-based and has no exceptions list, on purpose). The two halves are joined
# at RUNTIME so the written file is a real single-line PEM header — splitting
# the fixture itself would just produce a file the scanner correctly ignores.
PEM_A='-----BEGIN RSA'
PEM_B='PRIVATE KEY-----'
PW_KEY="pass"; PW_WORD="word"

# ---------------------------------------------------------------------------
section "core purity — :core must not touch Android"
reset_tree
expect PASS check-core-purity.sh "unmodified tree"
reset_tree
echo 'import android.util.Log' >> core/src/main/kotlin/dev/localintelligence/core/model/ModelBackend.kt
expect FAIL check-core-purity.sh "android.* import added to :core"
reset_tree
echo 'import androidx.room.Room' >> core/src/main/kotlin/dev/localintelligence/core/model/ModelBackend.kt
expect FAIL check-core-purity.sh "androidx.* import added to :core"
reset_tree
echo 'private val L = android.util.Log' >> core/src/main/kotlin/dev/localintelligence/core/model/ModelBackend.kt
expect FAIL check-core-purity.sh "fully-qualified android use, no import"
reset_tree
echo 'implementation(libs.androidx.core.ktx)' >> core/build.gradle.kts
expect FAIL check-core-purity.sh "androidx dependency added to :core"
reset_tree
echo 'val P = "android.permission.READ_CONTACTS"' >> core/src/main/kotlin/dev/localintelligence/core/model/ModelBackend.kt
expect PASS check-core-purity.sh "NEGATIVE CONTROL: permission string is data"

# ---------------------------------------------------------------------------
section "dependency policy — nothing lands without a justification"
reset_tree
expect PASS check-dependencies.sh "unmodified tree"
reset_tree
echo 'retrofit = { module = "com.squareup.retrofit2:retrofit", version = "2.11.0" }' >> gradle/libs.versions.toml
expect FAIL check-dependencies.sh "new library not on the allowlist"
reset_tree
echo 'sentry = { id = "io.sentry.android.gradle", version = "8.0.0" }' >> gradle/libs.versions.toml
expect FAIL check-dependencies.sh "telemetry SDK (Sentry)"
reset_tree
echo 'firebase = { module = "com.google.firebase:firebase-analytics", version = "24.0.0" }' >> gradle/libs.versions.toml
expect FAIL check-dependencies.sh "cloud/telemetry SDK (Firebase)"
reset_tree
echo 'mcp = { module = "io.modelcontextprotocol:kotlin-sdk", version = "0.5.0" }' >> gradle/libs.versions.toml
expect FAIL check-dependencies.sh "MCP SDK (explicitly out of scope)"
reset_tree
sed -i 's|mavenCentral()|mavenCentral()\n        maven { url = uri("https://jitpack.io") }|' settings.gradle.kts
expect FAIL check-dependencies.sh "JitPack repository (CI reliability risk)"
reset_tree
printf 'junit:junit\n' >> ci/dependency-allowlist.txt
expect FAIL check-dependencies.sh "allowlist entry with no justification"

# ---------------------------------------------------------------------------
section "repo hygiene — no binaries, no secrets"
reset_tree
expect PASS check-repo-hygiene.sh "unmodified tree"
reset_tree
mkdir -p models && head -c 3000000 /dev/urandom > models/big.bin
(cd "$SCRATCH" && git add -A -f >/dev/null 2>&1)
expect FAIL check-repo-hygiene.sh "3 MB model file committed"
reset_tree
# A .gguf in test resources, but too big to be a fixture — i.e. a real weight
# someone parked in the wrong place. The size threshold is what makes the
# fixture allowance safe, so it needs its own red case.
mkdir -p core/src/test/resources/gguf && head -c 3000000 /dev/urandom > core/src/test/resources/gguf/oops.gguf
(cd "$SCRATCH" && git add -A -f >/dev/null 2>&1)
expect FAIL check-repo-hygiene.sh "3 MB .gguf parked in test resources"
reset_tree
# NEGATIVE CONTROL: a small synthetic fixture in test resources is legitimate —
# it is what lets the GGUF header parser be tested without a real model.
mkdir -p core/src/test/resources/gguf && head -c 2048 /dev/urandom > core/src/test/resources/gguf/tiny.gguf
(cd "$SCRATCH" && git add -A -f >/dev/null 2>&1)
expect PASS check-repo-hygiene.sh "NEGATIVE CONTROL: 2 KB gguf test fixture"
reset_tree
mkdir -p keys
printf -- '%s %s\n%s\n%s\n' "$PEM_A" "$PEM_B" "MIIEowIBAAKCAQEA" "ignored body" > keys/id_rsa
(cd "$SCRATCH" && git add -A -f >/dev/null 2>&1)
expect FAIL check-repo-hygiene.sh "private key committed"
reset_tree
mkdir -p cfg
printf '%s%s = "hunter2hunter2"\n' "$PW_KEY" "$PW_WORD" > cfg/app.properties
(cd "$SCRATCH" && git add -A -f >/dev/null 2>&1)
expect FAIL check-repo-hygiene.sh "literal password in a .properties file"
reset_tree
echo 'sdk.dir=/home/someone/Android/Sdk' > local.properties
(cd "$SCRATCH" && git add -A -f >/dev/null 2>&1)
expect FAIL check-repo-hygiene.sh "machine-specific local.properties committed"

# ---------------------------------------------------------------------------
section "formatting — twelve agents, one style"
reset_tree
expect PASS check-formatting.sh "unmodified tree"
reset_tree
printf '\tprivate val x = 1\n' >> core/src/main/kotlin/dev/localintelligence/core/model/GrammarBuilder.kt
expect FAIL check-formatting.sh "tab-indented Kotlin"
reset_tree
printf '\r\n' >> core/src/main/kotlin/dev/localintelligence/core/model/GrammarBuilder.kt
expect FAIL check-formatting.sh "CRLF line ending"
reset_tree
printf 'private val y = 2   \n' >> core/src/main/kotlin/dev/localintelligence/core/model/GrammarBuilder.kt
expect FAIL check-formatting.sh "trailing whitespace"
reset_tree
printf 'println("debug leftover")\n' >> core/src/main/kotlin/dev/localintelligence/core/model/GrammarBuilder.kt
expect FAIL check-formatting.sh "println() left in :core"

# ---------------------------------------------------------------------------
section "eval regression — the number has to hold"
if [ "${SKIP_GRADLE:-0}" = "1" ]; then
  echo "  (skipped: SKIP_GRADLE=1)"
else
  reset_tree
  expect PASS check-evals.sh "unmodified tree (50/50, primary 1.00)"
  reset_tree
  # Simulate a regression by raising the committed bar above what the suite can
  # score. Same comparison as "the agent got worse", without having to break
  # the agent on purpose.
  sed -i 's/^primary = .*/primary = 1.01/' ci/eval-baseline.txt
  expect FAIL check-evals.sh "score below the frozen baseline"
  reset_tree
  # A suite that silently runs fewer tasks must not look like a pass.
  sed -i 's/^tasks = .*/tasks = 500/' ci/eval-baseline.txt
  expect FAIL check-evals.sh "suite reports fewer tasks than the baseline"
  reset_tree
  # The critical one: if the harness cannot report a score at all, that is a
  # FAILURE, not a shrug. Simulated by making gradlew a no-op that prints junk.
  printf '#!/bin/sh\necho "no primary line here"\nexit 0\n' > gradlew
  chmod +x gradlew
  expect FAIL check-evals.sh "harness reports no score (must not look green)"
fi

# ---------------------------------------------------------------------------
section "api freeze — :core contracts are frozen"
if [ "${SKIP_GRADLE:-0}" = "1" ]; then
  echo "  (skipped: SKIP_GRADLE=1)"
else
  reset_tree
  expect PASS check-api-freeze.sh "unmodified tree"
  reset_tree
  # A public method is removed from a frozen type. Compiles (nothing in main
  # calls it) but is a breaking ABI change. Note there is no `public` keyword to
  # match: Kotlin omits it, and a sed that looks for one silently does nothing.
  sed -i 's/^\(\s*\)fun hint(/\1private fun hint(/' \
      core/src/main/kotlin/dev/localintelligence/core/model/GrammarBuilder.kt
  grep -q "private fun hint(" core/src/main/kotlin/dev/localintelligence/core/model/GrammarBuilder.kt \
    || { echo "  !! mutation did not apply"; FAIL=$((FAIL+1)); }
  expect FAIL check-api-freeze.sh "public member removed from :core"
  reset_tree
  rm -f core/api/core.api
  git rm -q --cached core/api/core.api
  expect FAIL check-api-freeze.sh "frozen API dump missing from git"
  reset_tree
  # NEGATIVE CONTROL: an internal refactor must NOT be reported as an API
  # change. If this ever goes red, the gate is noise and people will bypass it.
  sed -i 's/const val MESSAGE_OVERHEAD_TOKENS = 4/const val MESSAGE_OVERHEAD_TOKENS = 5/' \
      core/src/main/kotlin/dev/localintelligence/core/context/DefaultContextBuilder.kt
  expect PASS check-api-freeze.sh "NEGATIVE CONTROL: internal value changed"
fi

# ---------------------------------------------------------------------------
printf '\n\033[1m%d passed, %d failed\033[0m\n' "$PASS" "$FAIL"
[ "$FAIL" -eq 0 ] || exit 1
