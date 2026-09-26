# Multi-turn eval harness — where it lives and why

## The call

**Kept in `core/src/main`, and that is fine.** Measured, not assumed.

## The measurement

The question behind requirement 4 is whether shipping a corpus in
`core/src/main` costs the user anything. Three numbers, all measured on this
tree:

| what | bytes | note |
|---|---:|---|
| whole `tool/eval/` package, compiled bytecode | 282,534 | 35 classes |
| this change-set's share of that | 157,363 | 5 new files |
| `:core` bytecode in total | 2,326,278 | eval is 12.15% of the module |
| debug APK delta, eval package present vs absent | 81,920 | on an 87 MB APK |

The last row is the one that looks alarming and is not: 81,920 bytes is
exactly 80 KiB, which is `zipalign` block granularity, not content. The real
question is whether it reaches a user at all.

It does not. `:app` release builds set `isMinifyEnabled = true` and
`isShrinkResources = true`, and R8 strips the package wholesale, because
nothing in the app's call graph reaches a `main()` on a class no Android
component instantiates. Verified against the built
`app-release-unsigned.apk` (59.8 MB) rather than inferred:

```
needle survival in shipped dex:
   stripped   remind me to check that again at 7
   stripped   how much battery is left
   stripped   battery-then-remind
   stripped   MultiTurnDataset
   stripped   WANTED BUT UNREACHABLE
   stripped   SELF_CONTAINED
```

Not one corpus string, class name, or report label survives into the release
dex. So the 276 KB is a cost to the debug build and to CI, and nothing at all
to a user.

## Why not move it to a separate source set anyway

It was considered and rejected, for one reason: **a `main()` on the debug
classpath is reachable, and one in a `test`/`eval` source set is not.**

The harness is run by a human on a workstation, from a checkout, against
whatever the selector currently does. That is the whole point — the numbers in
`ToolRegistry`'s KDoc are quoted in design decisions, and a claim nobody can
re-run is a claim that decays into a fiction. A separate source set would need
a Gradle task to put it back on a runnable classpath, and that task needs a
line in `core/build.gradle.kts`, which a concurrent agent owns. The script
approach needs no build change, survives a Kotlin or kotlinx version bump
because it resolves the classpath from the Gradle cache at run time, and is
readable end to end without trusting the build.

So `src/main` is not a compromise here — it is what makes the harness
runnable by one command with no build-file edit. The thing that would
actually make this expensive (shipping it to users) is already handled by R8,
and that is verified rather than assumed.

## Running it

```
export JAVA_HOME=$HOME/jdk21
./gradlew :core:compileKotlin
./core/src/main/kotlin/dev/localintelligence/core/tool/eval/run-multiturn-harness.sh
```

Prints recall by width, recall by intent, recall by how far back a turn
reaches, the unreachable and partially-reachable turns, the asymmetry check,
and a coverage table over all 25 shipped tools for both corpora.

## What it does not measure

No model, no device, no inference. It scores SELECTOR BEHAVIOUR deterministically
against labelled definitions. Every recall figure is an upper bound on task
success, not a success rate: it says a tool was made callable, never that the
model then called it correctly. Real end-to-end measurement needs a physical
phone.
