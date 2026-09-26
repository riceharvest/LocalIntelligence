#!/usr/bin/env bash
# Gate: :core is a pure JVM module.
#
# THE DEFECT THIS CATCHES — the architecture's one non-negotiable rule
# (settings.gradle.kts: "THE ONE ARCHITECTURAL RULE: :core is a PURE KOTLIN/JVM
# module with ZERO Android dependencies") is enforced in a comment and in
# docs/architecture.md, and nothing checked it. A single `import android.os.Build`
# in :core compiles fine against the :android classpath and silently converts the
# whole agent loop into something that cannot run on the JVM, which is the only
# reason a swarm of agents can verify it in seconds. It fails at the point where
# somebody tries to use it, not where it was written.
#
# Plain shell + POSIX awk. No new dependency. Exits non-zero on any violation.
#
# ── WHY THIS IS A LEXER AND NOT A GREP ───────────────────────────────────────
# The previous version of this gate was `grep -rn 'android\.' core/src/`. It was
# red for weeks on `origin/fullstack-gate` over two KDoc lines in MemoryProbe.kt
# that *describe* the platform seam in prose, while the compiled class had zero
# Android references. That is the failure mode this rewrite exists to stop:
#
#   A gate that cries wolf gets disabled. Once disabled, :core can quietly
#   acquire a real `import android.os.Debug` and nothing notices until the module
#   stops building as pure JVM — the exact outcome the gate exists to prevent.
#
# So this gate scans CODE, not text. Comments (line, block and nested block),
# string literals (escaped and raw """ """) and char literals are removed before
# anything is matched. A `${...}` string template is CODE and is kept.
#
# The cost of a naive "just ignore dotted mentions" patch — the revert that
# preceded this one — was 216 false positives on this branch, because Kotlin
# extension imports are used as `x.jsonObject`. Nothing here excludes dotted
# text. Prose is removed by *parsing*, and nothing but prose is removed: the
# prefix anchor `[^A-Za-z0-9_.]` is applied to the CODE stream, so `foo.android`
# as a member access cannot hide a genuine top-level `android.` reference.
#
# ── RECALL: what this must catch, not just the one bug ───────────────────────
# A previous check matched only `android\.(os|content|app|provider|net)\.`, so
# `android.util.Log`, `android.text.TextUtils`, `androidx.compose`, `android.R`,
# `dalvik.system` and `com.android.tools` all passed CI. Every real Android
# namespace is matched now, by one pattern, in one place.
set -uo pipefail

fail=0

readonly CORE_DIR="core"
readonly SRC_DIR="$CORE_DIR/src"
readonly BUILD_FILE="$CORE_DIR/build.gradle.kts"

# A source file that cannot be READ must not be silently skipped. The old gate
# ran `if grep ...; then` — and grep exits 2 on "Permission denied" and 1 on "no
# match", so an unreadable file with a real violation in it reported clean. Every
# read below is checked for an error exit separately from "no match".
unreadable=0

# The lexer: strip Kotlin comments/literals, keep code, preserve line numbers.
# Emits "<file>\t<lineno>\t<code-only>". Nested /* /* */ */ is real Kotlin and is
# tracked by depth. `${` inside a string switches back to code until the matching
# `}`, because a template body is an expression, not prose.
#
# NUL bytes: two files under core/src carry a literal \u0000 as a deliberate
# string separator (StructuredSummary.kt) and char sentinel
# (ActionParserImpl.kt). GNU grep classifies a file containing NUL as BINARY and
# prints "binary file matches" with no line number — and under `grep -I`, or any
# wrapper that adds it, it SKIPS the file entirely, which is a silent pass on a
# real violation. `tr -d '\000'` first means the lexer never sees one, so the
# result cannot depend on the awk implementation or on grep's binary heuristics.
# This program is held in a single-quoted shell string, so it must contain NO
# single-quote character of its own — comparing a char literal to "'" would
# terminate the string. SQ is that character, built numerically instead.
readonly KT_LEXER='
BEGIN { SQ = sprintf("%c", 39); src = (src == "" ? "-" : src)
        keepstr = (keepstr == "1")
        st = "code"; bd = 0; td = 0 }
{
  n = length($0); out = ""
  for (i = 1; i <= n; i++) {
    c  = substr($0, i, 1)
    t2 = (i <  n)   ? substr($0, i, 2) : ""
    t3 = (i <= n-2) ? substr($0, i, 3) : ""
    if (st == "code") {
      if (t3 == "\"\"\"") { st = "raw"; i += 2; continue }
      if (t2 == "/*")    { st = "blk"; bd = 1; i += 1; continue }
      if (t2 == "//")    { break }
      if (c  == "\"")    { st = "str"; continue }
      if (c  == SQ)     { st = "chr"; continue }
      out = out c
    } else if (st == "blk") {
      if (t2 == "/*") { bd++; i += 1; continue }
      if (t2 == "*/") { bd--; i += 1; if (bd <= 0) st = "code"; continue }
    } else if (st == "raw") {
      if (t3 == "\"\"\"") { st = "code"; i += 2; continue }
      if (keepstr) out = out c
    } else if (st == "str") {
      if (c == "\\") { i += 1; continue }
      if (c == "\"") { st = "code"; continue }
      if (c == "$" && (i < n) && substr($0, i+1, 1) == "{") {
        st = "tmpl"; td = 1; out = out "${"; i += 1; continue
      }
      if (keepstr) out = out c
    } else if (st == "chr") {
      if (c == "\\") { i += 1; continue }
      if (c == SQ)  { st = "code"; if (keepstr) out = out c; continue }
      if (keepstr) out = out c
    } else if (st == "tmpl") {
      if (c == "{") { td++; out = out c; continue }
      if (c == "}") { td--; if (td <= 0) { st = "str" } else { out = out c }; continue }
      if (t3 == "\"\"\"") { st = "traw"; i += 2; continue }
      if (c == "\"") { st = "tstr"; continue }
      if (c == SQ)  { st = "tchr"; continue }
      out = out c
    } else if (st == "tstr") {
      if (c == "\\") { i += 1; continue }
      if (c == "\"") { st = "tmpl"; continue }
      if (c == "$" && (i < n) && substr($0, i+1, 1) == "{") { td++; out = out "${"; i += 1; continue }
    } else if (st == "traw") {
      if (t3 == "\"\"\"") { st = "tmpl"; i += 2; continue }
    } else if (st == "tchr") {
      if (c == "\\") { i += 1; continue }
      if (c == SQ)  { st = "tmpl"; continue }
    }
  }
  printf "%s\t%d\t%s\n", src, NR, out
}
'

# The one pattern. Matches a top-level reference to any real Android namespace,
# in code, including inside a `${}` template. The leading `[^A-Za-z0-9_.]` is
# what stops a member access like `config.android` from matching, while leaving
# `import android.os.Debug`, `= android.os.Build.MODEL` and `x ?: android.R.id`
# all matching. `dalvik.` and `com.android.` are the other two real routes into
# platform-only API; both are Android and neither is available on a plain JVM.
# The dot is written `[.]`, not `\.`. gawk treats a backslash-escaped dot inside
# a `-v` dynamic regex as a plain `.` — i.e. a WILDCARD — and merely warns about
# it, so `\.` silently made `dalvikPssBytes` (a local field in MemoryProbe.kt)
# match. `[.]` is unambiguous in every awk. Verified against gawk and busybox awk.
readonly ANDROID_REF='(^|[^A-Za-z0-9_.])(android|androidx|dalvik|com[.]android)[.]'

# 0. The module must exist. A gate that scans nothing because a path moved is
#    the worst kind of green, so this is a hard failure, not a skip.
if [ ! -d "$SRC_DIR" ]; then
  echo "::error::$SRC_DIR does not exist. This gate cannot verify :core purity against a missing tree, and a gate that scans nothing is not a passing gate."
  exit 1
fi

# 1. Every Kotlin source under core/src, code-only, as
#    "<file>\t<line>\t<code>". Built with find -print0/read so a filename with a
#    space or newline cannot split the list, and each file is read individually
#    so one unreadable file is reported rather than skipped.
: > /tmp/li-core-purity.$$ || { echo "::error::cannot create scratch file"; exit 1; }
codefile="/tmp/li-core-purity.$$"
trap 'rm -f "$codefile"' EXIT

while IFS= read -r -d '' f; do
  if [ ! -r "$f" ]; then
    echo "::error::$f is not readable. The purity gate cannot see inside a file it cannot read, so it is failing rather than reporting a clean :core."
    unreadable=1
    continue
  fi
  # -v src= passes the path explicitly: reading from stdin leaves FILENAME as
  # "-", and a violation report that cannot name its file is a finding nobody
  # can act on — which is how this gate stayed broken for weeks.
  tr -d '\000' < "$f" | awk -v src="$f" "$KT_LEXER" >> "$codefile"
  # awk's own exit status: 2+ means it could not process the input.
  if [ "${PIPESTATUS[1]}" -ge 2 ]; then
    echo "::error::the purity lexer failed on $f (awk exit ${PIPESTATUS[1]}). Refusing to report a clean result from an instrument that did not run."
    unreadable=1
  fi
done < <(find "$SRC_DIR" -type f \( -name '*.kt' -o -name '*.kts' \) -print0)

if [ "$unreadable" -ne 0 ]; then
  fail=1
fi

# 2. The real check: an Android reference in CODE anywhere under core/src.
#    Comments, KDoc, string literals and char literals are already gone, so a
#    KDoc sentence naming `android.os.Debug` is silent by construction, and a
#    fully-qualified reference with no import is caught. A seed with an import is
#    also caught here — the old separate import check is redundant now that one
#    pattern covers both, and one pattern cannot drift out of sync with itself.
if [ -s "$codefile" ]; then
  hits=$(awk -F'\t' -v pat="$ANDROID_REF" '$3 ~ pat { print $1 ":" $2 ": " $3 }' "$codefile")
  if [ -n "$hits" ]; then
    echo "$hits"
    echo "::error::$SRC_DIR references Android in CODE. :core is a pure JVM module (see settings.gradle.kts) — a reference that reaches the platform compiles clean here and fails everywhere except a device. Comments and string literals are exempt; this line is neither."
    fail=1
  fi
fi

# 3. :core must not apply the Android plugin, and 4. must not depend on an
#    Android artifact — even transitively through a version-catalog alias whose
#    name does not say "android".
#
#    Both read the build file with COMMENTS REMOVED BUT STRINGS KEPT (keepstr=1).
#    Strings must stay: `id("com.android.library")` and
#    `implementation("androidx.core:core-ktx:…")` ARE strings, so a full source
#    lexer would hide exactly the two things these checks exist to catch. But a
#    prose comment was enough to fail this gate on its own — a comment reading
#    "the api surface must never mention an android implementation" contains
#    both trigger words and was reported as an Android dependency. Same defect
#    class as the KDoc false positive, one file over.
if [ -f "$BUILD_FILE" ] && [ -r "$BUILD_FILE" ]; then
  bf_code=$(tr -d '\000' < "$BUILD_FILE" | awk -v src="$BUILD_FILE" -v keepstr=1 "$KT_LEXER")
  if [ -z "$bf_code" ]; then
    echo "::error::could not read $BUILD_FILE after stripping comments. Refusing to report a clean :core from an instrument that produced nothing."
    fail=1
  else
    # NOTE: each filter below is tested on its OUTPUT, not its exit status. awk
    # exits 0 whether or not it matched, so `if awk ...; then fail=1` fires on
    # a clean build file — the first version of this fix did exactly that and
    # failed the gate on the untouched tree.
    if [ -n "$(printf '%s\n' "$bf_code" | awk -F'\t' '$3 ~ /com[.]android/ { print $1 ":" $2 ": " $3 }')" ]; then
      printf '%s\n' "$bf_code" | awk -F'\t' '$3 ~ /com[.]android/ { print "  " $1 ":" $2 ": " $3 }'
      echo "::error::$BUILD_FILE must not apply the Android plugin"
      fail=1
    fi
    if [ -n "$(printf '%s\n' "$bf_code" | awk -F'\t' '
        { u = toupper($3) }
        u ~ /(^|[^A-Z0-9_.])(IMPLEMENTATION|API)[( ]/ && u ~ /ANDROID/ { print $1 ":" $2 ": " $3 }
      ')" ]; then
      printf '%s\n' "$bf_code" | awk -F'\t' '
        { u = toupper($3) }
        u ~ /(^|[^A-Z0-9_.])(IMPLEMENTATION|API)[( ]/ && u ~ /ANDROID/ { print "  " $1 ":" $2 ": " $3 }
      '
      echo "::error::$BUILD_FILE declares an Android dependency"
      fail=1
    fi
  fi
else
  echo "::error::$BUILD_FILE missing or unreadable. Cannot verify that :core is free of the Android plugin and of Android dependencies."
  fail=1
fi

if [ "$fail" -ne 0 ]; then
  exit 1
fi
echo "OK: :core has zero Android references in code (no imports, no fully-qualified refs, no plugin, no Android dependency). KDoc and string literals are exempt by construction, and every source file under $SRC_DIR was read."
