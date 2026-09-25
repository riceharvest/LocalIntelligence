# CI

The rules in `docs/architecture.md` and `docs/swarm-plan.md` were written down,
and then twelve agents changed the code anyway. That is not because the rules are
wrong. It is because prose does not fail a build.

This file describes the gates that do.

---

## The design constraint

**Agents have to wait for CI.** A run that takes twenty minutes trains a swarm to
ignore CI, and once CI is ignored, every gate in here is decoration. So:

| Runs on every push | Runs on a schedule |
|---|---|
| `:core` purity, dependency policy, repo hygiene, formatting | APK build (llama.cpp cross-compiled for two ABIs, 5–15 min cold) |
| `:core` tests, eval suite + regression gate | Android unit tests |
| frozen `:core` public API | the gate self-test (this repo's own tests for its own gates) |

The fast gates need no JDK, no Gradle and no Android SDK — they are a few greps
over files already in the checkout, so an architecture violation is reported in
seconds rather than after a build.

The APK build is scheduled because nothing about it is fast: it cross-compiles
llama.cpp through the NDK for `arm64-v8a` and `x86_64`. It is also the check
least likely to be broken by a change to the agent loop, so paying for it on
every push would be paying for the wrong thing.

Run the slow checks on demand with **Actions → nightly → Run workflow**.

---

## Rule → gate

| # | Rule | Gate | When |
|---|---|---|---|
| 1 | `:core` is pure Kotlin/JVM, zero `android.*` | `scripts/check-core-purity.sh` | every push |
| 2 | No cloud, telemetry or MCP | `scripts/check-dependencies.sh` (declared) + resolved-tree check in `ci.yml` | every push |
| 3 | No dependency without a reviewed justification | `scripts/check-dependencies.sh` vs `ci/dependency-allowlist.txt` | every push |
| 4 | Interfaces in `:core` are frozen | `scripts/check-api-freeze.sh` | every push |
| 5 | `:core` tests pass; evals report a score | `./gradlew :core:test`, `scripts/check-evals.sh` | every push |
| 6 | A regression in eval score fails the build | `scripts/check-evals.sh` vs `ci/eval-baseline.txt` | every push |
| 7 | No secrets, model files or APKs; size ceiling | `scripts/check-repo-hygiene.sh` | every push |
| 8 | Formatting consistency across agents | `scripts/check-formatting.sh` | every push |
| 9 | Reproducible dependencies | allowlist + a real APK build on schedule | nightly |

---

## How each one works

### 1. `:core` purity

`check-core-purity.sh` greps `:core` for `android.*` and `androidx.*` imports,
for fully-qualified uses, for the Android plugin in any of its three spellings,
and for Android artifacts in `core/build.gradle.kts`.

It strips string literals and comments first, and that detail is the whole
difference between a gate that works and one that gets deleted. The eval
fixtures legitimately carry the **string** `"android.permission.READ_CONTACTS"`
as tool metadata. A permission *name* is data; an `android.*` *type* is a
violation. An earlier version of this gate failed on `main` because it could not
tell those apart.

### 2 + 3. Dependency policy

Two independent checks, because they fail differently.

- **Allowlist.** Every coordinate in `gradle/libs.versions.toml` must appear in
  `ci/dependency-allowlist.txt` with a non-empty justification. A new
  dependency therefore cannot land quietly: either CI goes red, or the author
  edits the allowlist — which is a diff a reviewer sees.
- **Denylist.** Telemetry, cloud, MCP and JitPack are forbidden outright,
  checked against the whole build rather than the catalog. The allowlist is a
  file an author can edit in the same PR; the denylist is the backstop that
  survives that.
- **Resolved tree.** `ci.yml` also dumps the resolved `releaseRuntimeClasspath`
  and greps *that*, so a transitive dependency cannot smuggle a forbidden SDK
  past a correct-looking catalog.

### 4. Frozen public API

`core/api/core.api` is a committed dump of the public API of `:core`.
`check-api-freeze.sh` regenerates it and fails if it moved. The diff is printed
in full, so a reviewer sees exactly which signature changed.

The dump is produced by
[binary-compatibility-validator](https://github.com/Kotlin/binary-compatibility-validator),
which reads Kotlin metadata and therefore understands `internal`. This matters
more than it sounds: `internal object TokenEstimate` compiles to a **public JVM
class**, so a naive `javap` dump reports internal refactors as broken contracts
and the gate gets ignored within a week.

**Why this script does its own diffing instead of calling `apiCheck`.** BCV
provides a `:core:apiCheck` task and it is the obvious thing to call. It is also
silently broken under this repo's `org.gradle.configuration-cache=true`: with
the cache on, the check task's action never runs and `apiCheck` exits `0` even
when the committed dump has been corrupted. Verified locally — the identical
tree fails correctly with `--no-configuration-cache`. A gate that dies the
moment someone tidies a Gradle property is worse than no gate, so this script
regenerates with the plugin and diffs with `git`. Five lines, no hidden
behaviour, and it works with the configuration cache on or off.

To accept an intentional API change:

```bash
./gradlew :core:apiDump && git add core/api/core.api
```

`core/api/core.api` is in `CODEOWNERS`. GitHub only *requests* that review —
turn on **Settings → Branches → Require review from Code Owners** to make it
blocking. That setting is the part of the freeze that is not expressible in a
file.

### 5 + 6. Tests and the eval regression gate

`./gradlew :core:test` and `scripts/check-evals.sh`, which compares the primary
metric (task success) against `ci/eval-baseline.txt` and fails if it dropped.

The comparison is the easy part. The failure modes are the point:

- a missing `primary:` line **fails** — a harness that reports nothing is not a
  harness that passed;
- a suite that ran zero tasks **fails**;
- a suite that ran *fewer* tasks than the baseline **fails** — a smaller suite at
  the same score is weaker evidence, and quietly dropping tasks is how you get a
  green build that tests nothing.

The threshold is exact rather than a tolerance. One task is `1/50 = 0.02`, so a
single regressed task drops the score and turns the build red. Lowering the
baseline is possible but it is a one-line diff in a reviewable file, which is
the whole design: make lowering it visible, and nobody lowers it to go green.

### 7. Repo hygiene

Banned extensions, a 2 MiB per-file ceiling (which catches the weights with
unusual names), high-confidence secret patterns, and `local.properties`.

High-confidence patterns only, and no exceptions list. A scanner that fires on
the word "tokenize" in a tokenizer, or that needs a "skip this path" escape
hatch, gets deleted — and then it protects nobody.

### 8. Formatting

No ktlint, no detekt, no editor plugin. A linter ecosystem is a supply chain and
a config file nobody reads, and this repo's brief is explicit about preferring
grep. What it checks is the failure mode twelve agents actually produce: tabs
from a shell heredoc, CRLF from a Windows agent, trailing whitespace. Plus one
rule that is really a correctness rule — no `println()` in `:core`, because a
library that prints corrupts whatever the host app prints.

A missing final newline is reported but does **not** fail the build. The
distinction is deliberate: tabs and CRLF are never intentional and actively break
merges — a CRLF file shows every line as changed to the next agent that touches
it. A missing final newline is cosmetic, and `main` arrived with six of them in
files owned by other workstreams. Failing on pre-existing debt this workstream
does not own is what teaches people to reach for an exclusion list, and an
exclusion list is how a gate dies. It is reported so it stays visible, and it is
not allowed to block.

This distinction is also the one case where a rule was *loosened* after it was
written, and the reasoning is recorded here so the next person does not have to
reverse-engineer it.

---

## Proving the gates work

> A gate that has never been seen to fail is not a gate.

```bash
./scripts/test-gates.sh            # ~2 min, needs a JDK
SKIP_GRADLE=1 ./scripts/test-gates.sh   # skips the two Gradle-dependent gates
```

It copies the repo to a scratch directory, breaks one thing at a time, and
asserts the matching gate goes red. It also asserts the **negative controls** —
the cases that must stay green — which is what stops a gate from becoming so
trigger-happy that everyone learns to bypass it. A permission string in a test
fixture must not trip the purity gate; an internal constant changing must not
trip the API freeze.

This runs nightly in CI, because a self-test nobody runs is a self-test that
quietly stops working the first time someone changes a gate.

### Bugs this harness actually caught

Worth recording, because every one of them was a gate that would have shipped
green and useless:

- `import androidx.…` passed the purity gate — the regex required a literal dot
  after `android`, and `androidx` has an `x`.
- A single-line KDoc (`/** … */`) opened a comment-stripping range that never
  closed, silently deleting the rest of the file and making the gate vacuous for
  everything after it.
- `alias(libs.plugins.android.library)` was not detected — the alias form is
  dotted, and the check only matched the hyphenated legacy id.
- The gate self-test itself wrote its mutations into the real checkout instead of
  the scratch copy, because the paths were relative and it never changed
  directory.

---

## Adding a gate

1. Put it in `scripts/`, keep it small enough to read in one sitting, and prefer
   a few lines of `grep` over a framework.
2. Add it to `.github/workflows/ci.yml` — `policy` if it needs no JDK, `core` if
   it runs Gradle.
3. Add the positive **and** negative control cases to `scripts/test-gates.sh`, and
   run it. A gate with no red case is a comment.
4. Add the rule to the table above.
