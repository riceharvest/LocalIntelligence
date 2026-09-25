#!/usr/bin/env bash
# Gate: nothing dangerous gets committed to a PUBLIC repo.
#   - no model weights, APKs, native libs or other big binaries
#   - no file above a size ceiling (catches the ones without a known extension)
#   - no secrets
#
# This is the gate that protects the people who clone it, and the size ceiling
# is the part that actually works: a 4 GB GGUF does not care what it is called.
#
# Deliberately high-confidence patterns only. A secret scanner that cries wolf
# on the word "tokenize" in a tokenizer gets deleted, and then it protects
# nobody. Precision matters more than recall here.
#
# Usage: scripts/check-repo-hygiene.sh

set -euo pipefail

cd "$(dirname "$0")/.."

status=0
fail() { echo "::error::$1"; status=1; }

# ------------------------------------------------- 1. committed model/binaries
# The repo already .gitignores these, but .gitignore is a convention, not a
# gate: `git add -f` bypasses it, and so does a merge from a branch prepared
# elsewhere. This checks what is ACTUALLY tracked.
#
# Test fixtures are allowed, but ONLY if they are small. A real GGUF is 100 MB+;
# the synthetic ones in core/src/test/resources/gguf are 24 bytes to 41 KB. The
# threshold is what separates a fixture from a weight — an extension match
# cannot, and banning the extension outright would ban the fixtures that let the
# GGUF header parser be tested at all.
FIXTURE_MAX_BYTES=$((64 * 1024))
is_small_test_fixture() {
  local f="$1" size
  size=$(stat -c%s "$f" 2>/dev/null || echo 999999999)
  [ "$size" -le "$FIXTURE_MAX_BYTES" ] && [[ "$f" == */src/test/resources/* ]]
}

BANNED_EXT='\.(apk|aab|gguf|onnx|tflite|safetensors|pt|pth|ckpt|bin|so|dylib|dll|jar|war|zip|tar|gz|7z|rar|keystore|jks|p12|pem|key|der)$'
bad=""
while IFS= read -r f; do
  # The Gradle wrapper jar is a build-tool bootstrap file, checked in by
  # convention, and it is 45 KB.
  [ "$f" = "gradle/wrapper/gradle-wrapper.jar" ] && continue
  is_small_test_fixture "$f" && continue
  bad+="$f"$'\n'
done < <(git ls-files | grep -iE "$BANNED_EXT" || true)

if [ -n "$(printf '%s' "$bad" | sed '/^$/d')" ]; then
  fail "Committed build/binary/model artifact(s):"
  printf '%s' "$bad" | sed '/^$/d' | sed 's/^/  /'
  echo "  A test fixture is allowed only under */src/test/resources/* and"
  echo "  under $FIXTURE_MAX_BYTES bytes. (gradle-wrapper.jar excepted.)"
  echo "  Model weights belong on Hugging Face and are downloaded at first run."
fi

# ------------------------------------------------------------ 2. size ceiling
# Catches the unnamed ones: a checkpoint called "data.bin2", or a tarball.
# 2 MiB is generous for source; the largest real source file here is ~68 KiB.
MAX_BYTES=$((2 * 1024 * 1024))
while IFS= read -r -d '' f; do
  [ -f "$f" ] || continue
  size=$(stat -c%s "$f" 2>/dev/null || echo 0)
  if [ "$size" -gt "$MAX_BYTES" ]; then
    fail "Tracked file exceeds 2 MiB: $f ($((size / 1024)) KiB)"
  fi
done < <(git ls-files -z)

# ----------------------------------------------------------------- 3. secrets
# High-confidence only: real key formats, not the words "key" or "token".
secret_hits=""
# Private key blocks
secret_hits+=$(git grep -lIE 'BEGIN (RSA |EC |OPENSSH |DSA |PGP )?PRIVATE KEY' -- . 2>/dev/null || true)
# Provider key formats with unmistakable prefixes
secret_hits+=$(git grep -lIE '(AKIA|ASIA)[0-9A-Z]{16}' -- . 2>/dev/null || true)
secret_hits+=$(git grep -lIE 'gh[pousr]_[A-Za-z0-9]{30,}' -- . 2>/dev/null || true)
secret_hits+=$(git grep -lIE 'sk-[A-Za-z0-9_-]{32,}' -- . 2>/dev/null || true)
secret_hits+=$(git grep -lIE 'xox[abprs]-[A-Za-z0-9-]{10,}' -- . 2>/dev/null || true)
# Credentials assigned a literal value in a config-ish file
secret_hits+=$(git grep -lIE '(password|passwd|secret|api[_-]?key|access[_-]?token)[[:space:]]*[:=][[:space:]]*["'"'"'][^"'"'"']{8,}' -- '*.properties' '*.yml' '*.yaml' '*.json' '*.toml' 2>/dev/null || true)

if [ -n "$(echo "$secret_hits" | tr -d '[:space:]')" ]; then
  fail "Possible committed secret(s):"
  echo "$secret_hits" | sort -u | tr ' ' '\n' | grep -v '^$' | sed 's/^/  /'
  echo "  Rotate the credential. Removing it from the file is not enough once it is in history."
fi

# local.properties is machine-specific and must never be committed.
if git ls-files --error-unmatch local.properties >/dev/null 2>&1; then
  fail "local.properties is tracked — it is machine-specific (SDK paths) and must stay local."
fi

if [ "$status" -eq 0 ]; then
  echo "repo-hygiene: OK — no binaries, no oversized files, no secrets"
fi

exit "$status"
