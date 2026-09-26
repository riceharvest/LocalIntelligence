#!/usr/bin/env bash
# Runs the MULTI-TURN tool-selection harness on a plain JVM. No JUnit, no
# Gradle task, no device, no emulator, no model.
#
# ONE COMMAND, and it prints a table plus a coverage table. See
# MultiTurnHarnessKt for what the numbers mean and — more importantly — what
# they do not.
#
# WHY A SCRIPT AND NOT A GRADLE JavaExec TASK: adding one needs a line in
# core/build.gradle.kts, which another agent owns concurrently. This needs
# nothing, and `:core:compileKotlin` plus a classpath is a command anyone can
# read and re-run without believing this file.
#
# The classpath is resolved from the Gradle module cache at RUN time, not
# hardcoded, so a Kotlin or kotlinx version bump does not silently break the
# documented command. This is copied from run-recall-harness.sh rather than
# shared, because a shared helper would be a second thing for a concurrent
# edit to collide with; the duplication is deliberate and is noted here so
# nobody "fixes" it into a race.
set -euo pipefail

# Walk up to the first directory that actually looks like the repository root
# and fail loudly if there isn't one. Counting `..` segments is the kind of
# thing that breaks silently the day a package is renamed.
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

exec "$JAVA_HOME/bin/java" -cp "$CP" \
  dev.localintelligence.core.tool.eval.MultiTurnReportKt "$@"
