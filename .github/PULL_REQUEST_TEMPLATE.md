<!--
The checklist below is not decoration. Every item maps to a gate that turns the
build red. A PR that skips one will fail CI, and the failure message will tell
you which rule you broke and how to fix it.
-->

## What this changes

<!-- One or two sentences. What behaviour is different after this PR? -->

## Checklist

**Architecture** (all enforced — see `docs/CI.md`)

- [ ] `:core` still has no `android.*` / `androidx.*` types. Platform access
      goes through an interface in `:core`, implemented in `:android`.
      Gate: `scripts/check-core-purity.sh`
- [ ] **No change to the frozen public API of `:core`.** If you *must* change a
      contract, stop and open an issue instead — a swarm that can redesign the
      contracts is not a swarm. If it has been agreed, regenerate the dump in
      the same PR and say so below:
      `./gradlew :core:apiDump && git add core/api/core.api`
      Gate: `scripts/check-api-freeze.sh`
- [ ] No new Gradle dependency, **or** it is on `ci/dependency-allowlist.txt`
      with a written justification. "Temporarily" is not a justification.
      Gate: `scripts/check-dependencies.sh`
- [ ] No cloud SDK, no telemetry, no MCP, no JitPack.
- [ ] No committed model weights, APKs, `.so` files or secrets.

**Evidence** (docs/evals.md: "If a feature does not move a number here, it does not ship.")

- [ ] `./gradlew :core:test` passes.
- [ ] The eval suite still scores at or above the committed baseline. If this
      PR lowers the score, say why, and treat the baseline diff as part of the
      review.
- [ ] New behaviour has a test. For a tool: the nine-case matrix from
      `docs/tool-contract.md`, including permission denied, empty result and
      observation under budget.

**Scope**

- [ ] This PR touches only the files it was assigned. If it needs a file owned
      by another workstream, say so in a comment rather than editing it.
- [ ] RAM/ROM cost is stated if you added a cache, buffer or index —
      a number, not "it is small" (docs/architecture.md §16).

## Notes for the reviewer

<!--
Anything a reviewer cannot infer from the diff: an interface change you believe
is necessary and why, a baseline you had to lower, a tool you deliberately left
without confirmation.
-->
