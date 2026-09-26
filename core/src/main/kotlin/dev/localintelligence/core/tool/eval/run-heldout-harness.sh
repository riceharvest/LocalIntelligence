#!/usr/bin/env bash
# Runs the held-out selectability harness on a plain JVM. No JUnit, no Gradle
# task, no device, no emulator, no model.
#
# WHY A SCRIPT AND NOT A GRADLE JavaExec TASK: adding one needs a line in
# core/build.gradle.kts, which this change-set deliberately does not touch. This
# needs nothing, and `:core:compileKotlin` plus a classpath is a command anyone
# can read and re-run without believing this file.
#
# The classpath is resolved from the Gradle module cache at RUN time, not
# hardcoded, so a Kotlin or kotlinx version bump does not silently break the
# documented command.
#
# Run from the repository root:
#   export JAVA_HOME=$HOME/jdk21
#   ./gradlew :core:compileKotlin
#   ./core/src/main/kotlin/dev/localintelligence/core/tool/eval/run-heldout-harness.sh
set -euo pipefail

# This file lives at
#   core/src/main/kotlin/dev/localintelligence/core/tool/eval/
# and the repository root is several levels above `eval/`. Counting `..`
# segments is the kind of thing that breaks silently the day a package is
# renamed, so walk up to the first directory that actually looks like the
# repository root and fail loudly if there isn't one.
here="$(cd "$(dirname "$0")" && pwd)"
REPO="$here"
for _ in 1 2 3 4 5 6 7 8 9 10 11 12; do
  if [ -f "$REPO/settings.gradle.kts" ] && [ -d "$REPO/core/src/main" ]; then
    break
  fi
  REPO="$(dirname "$REPO")"
done
if [ ! -f "$REPO/settings.gradle.kts" ]; then
  echo "could not find the repository root above $here" >&2
  exit 1
fi
cd "$REPO"

export JAVA_HOME="${JAVA_HOME:-$HOME/jdk21}"

# Resolve the jars :core actually compiles against, at whatever version
# libs.versions.toml currently pins. `find` + version sort so a stale jar from
# an older resolution cannot win.
pick() { # pick <group-path> <artifact>
  find "$HOME/.gradle/caches/modules-2/files-2.1/$1/$2" -name "$2-*.jar" \
    ! -name "*-sources.jar" ! -name "*-javadoc.jar" 2>/dev/null |
    sort -V | tail -1
}

CP="$REPO/core/build/classes/kotlin/main"
# A heredoc loop rather than `for spec in ...; set -- $spec`: `set --` would
# clobber "$@" and the harness would be handed a stray jar name as an argument.
while read -r group artifact; do
  [ -n "$artifact" ] || continue
  jar="$(pick "$group" "$artifact")"
  if [ -z "$jar" ]; then
    echo "could not find $artifact in the Gradle cache;" \
         "run ./gradlew :core:compileKotlin first" >&2
    exit 1
  fi
  CP="$CP:$jar"
done <<'JARS'
org.jetbrains.kotlinx kotlinx-serialization-json-jvm
org.jetbrains.kotlinx kotlinx-serialization-core-jvm
org.jetbrains.kotlinx kotlinx-coroutines-core-jvm
org.jetbrains.kotlin kotlin-stdlib
JARS

# The harness reads `:android` sources to verify the tool snapshot it prices
# retrieval against, so it must run with the repository root as its working
# directory. The check reports SKIPPED rather than passing if it cannot find
# them, which is a visible difference rather than a silent one.
exec "$JAVA_HOME/bin/java" -cp "$CP" \
  dev.localintelligence.core.tool.eval.HeldOutReportKt "$@"
