# HuggingFace download — design notes and the seam left for `core/model/gguf`

## What shipped

A HuggingFace model download split across two modules:

- `:core` (`dev.localintelligence.core.hub`) — pure JVM: repo-id validation, the
  HF REST client, quant/shard parsing, the RAM and disk fit gates, resume
  policy, SHA verification, progress throttling, and the error taxonomy.
- `:android` (`dev.localintelligence.android.hub`) — the platform half:
  `HttpURLConnection` transport, `StatFs`/`ActivityManager` device budget, an
  Android-Keystore token store, and the download loop.
- `:app` — one Compose screen (`HubScreen`) and its ViewModel.

## The invariant everything else serves

> Bytes are written to `<name>.gguf.part` and appear at `<name>.gguf` only
> after the transfer completes **and** the SHA matches.

No code path puts incomplete bytes at the final path. The consumer of that
path is `llama_model_load_from_file`, which memory-maps: a truncated GGUF is a
native crash with no Java stack and no file name, and on some devices a SIGBUS
rather than a clean error. Cancellation, a dropped connection, a checksum
failure and a process kill all leave the same safe state.

## The seam left for `core/model/gguf`

`core/model/gguf` is landing a `ModelMemoryEstimator` that computes weights
from the GGUF **tensor table** — exact, and the correct authority for a memory
model. This branch was built and tested without it (it is not on `origin/main`
at the time of writing), so it does not hard-depend on it.

`core/model/gguf` has since landed on `origin/main`, so the seam is now
**compiled against the real estimator** — see `GgufMemoryModel` below. The
`GgufMemoryModelTest` that drove it was deleted with the rest of the test
sources, so the seam is compile-checked but not exercised.

The seam is three declarations in `core/hub/FitGate.kt`:

```kotlin
fun interface MemoryModel {
    fun estimate(fileBytes: Long, quant: GgufQuant?, contextLength: Int, parameterCount: Long?): Long
}

interface DeviceBudget {
    fun availableRamBytes(): Long
    fun freeDiskBytes(): Long
    fun totalRamBytes(): Long
}
```

`HuggingFaceClient.plan()` and `chooseBestFitting()` both take a `MemoryModel`
parameter defaulting to `PreDownloadMemoryModel`. Wiring the real estimator is
one call-site change:

```kotlin
val client = HuggingFaceClient(transport)
// once a header is readable, delegate:
val plan = client.plan(file, contextLength, budget, model = { fb, q, ctx, pc ->
    ModelMemoryEstimator().estimate(parsedHeader, ctx).totalBytes
})
```

### The seam is closed, not just declared

`GgufMemoryModel` in `core/hub/GgufMemoryModel.kt` adapts the landed
`ModelMemoryEstimator` to the hub's `MemoryModel` interface. A seam nobody has
ever called is a guess; this one at least fails to compile the moment the
estimator's signature changes, rather than failing silently at runtime on a
user's phone. It is called from `HuggingFaceClient.probeHeader` → `plan()`; see
docs/memory-model.md for the measured agreement between the two paths.

It is deliberately **not** wired into `ModelDownloader`. The reason is the one
that makes the seam necessary in the first place:

- `ModelMemoryEstimator.estimate(header, ...)` needs a **parsed `GgufHeader`**,
  which only exists after the bytes have landed.
- The download has to be *planned* before the bytes land, which is the only
  moment the app still has leverage over a 2 GB decision.

So the two models answer different questions. `PreDownloadMemoryModel` is a
conservative pre-flight bound; `ModelMemoryEstimator` is the exact post-download
recheck. Wiring the second into the first is not possible and not desirable.
The intended flow, once a caller is already parsing a header:

```kotlin
val plan   = client.plan(file, ctx, budget)                  // conservative
download(file, plan).collect { ... }                         // bytes on disk
val exact  = ModelMemoryEstimator().estimate(parsedHeader)   // exact
val final  = GgufMemoryModel.refine(exact, budget)           // same 1.15 factor
```

### Why `PreDownloadMemoryModel` is a measured bound rather than a guess

Before any bytes exist, the KV-cache term needs `block_count`,
`attention.head_count_kv` and `key_length`, and none of those are in a file
name. The model estimates:

```
weights  = fileBytes                      exact; llama.cpp maps the file
params   = file name, else fileBytes*8/bpw  bpw is measured per quant
arch     = measured table, else derived     9 real architectures on disk
kv       = 2 * layers * kvWidth * ctx * 2    kvWidth = head_count_kv * key_length
overhead = max(64 MiB, 2% of weights)        ESTIMATE, not measured
total    = weights + kv + overhead
```

and `FitGate.ramFit` multiplies that by **1.15** before comparing it to the
device budget.

`kvWidth` is one number rather than `kvHeads * headDim` because grouped-query
attention is a *ratio* and the ratio is what varies — 1, 2, 4, 7 and 8 all
occur among the nine architectures measured — while their product is a single
number per model. The previous version guessed both factors independently (a
quadratic root for the hidden size, a hard-coded `/512` for the head count, a
hard-coded `128` for the head dimension) and was wrong by up to 4.4x on the KV
term, including under-estimating Phi-3-mini by 1.2 GiB.

`arch` is a table of values read out of real files rather than a curve, because
`block_count` is not a smooth function of parameter count — Qwen2.5-3B has 36
layers and Qwen2.5-7B has 28 — so any monotone curve is wrong for one of them.
(A `ln(params) * 1.35` curve was tried and predicted 29 layers for a 3B model
whose real value is 36; that was a test failure, not a preference.)

The full derivation, the measured tables and the on-device measurement
procedure are in [`memory-model.md`](memory-model.md).

## Things found while building, and fixed

- **The 200 OK range trap.** A client that sends `Range: bytes=N-` and the
  server answers **200 with the whole entity** gets a body starting at byte 0.
  Appending it to a partial file yields a file of exactly the right length
  containing the prefix twice. `ModelDownloader.resolveWriteOffset` checks the
  body's start offset against the requested one *before writing anything*, and
  discards the partial if they disagree. Without this, the SHA check would
  eventually catch it — but only after a full 2 GB transfer thrown away.
- **Progress throttling was bounded by the wrong gate.** The original rule was
  "emit if enough bytes OR enough time". On a fast link 256 KiB arrives far
  more often than 250 ms, so a 2 GB download emitted ~7600 times — the exact
  per-buffer flood the class exists to prevent. The rule is now "at most once
  per interval, requires real forward progress, and forces an emission after
  8 × minBytes" so a slow link is not frozen at 0%.
- **No terminal progress emission.** A small download's last gated event was
  some way short of the total, so the bar froze at 33% and was then replaced by
  the completion state.
- **Cancellation could not interrupt a blocked read.** `ensureActive()` inside
  the copy loop is only reached *between* buffer reads, so a download parked in
  a blocked read on a dead link did not observe a cancel until that read
  returned — up to the full 60 s read timeout. A test hung for exactly 60 s and
  exposed it. (The test is gone; the fix it produced is in the code.) `ModelDownloader` now registers a cancellation handler that
  closes the response body, which shuts the socket and makes the cancel
  prompt. This is the requirement `docs/tool-contract.md` states as "check
  `context.signal` in any loop or long read", taken seriously.
- **`minBytes * STARVATION_FACTOR` overflowed** when a caller passed
  `Long.MAX_VALUE` to disable the byte gate. Caught by a test; now saturating.
- **HF types `gated` inconsistently** — a boolean on some endpoints, the string
  `"auto"`/`"manual"` on the models API. It is read as a `JsonElement` so a
  `"manual"` repo does not fail to decode; those are precisely the repos a
  phone user most wants.
- **A 401 from HF means "gated", not "unauthorized".** Mapping it to
  "create a token" tells a user to mint a credential that cannot open a repo
  they have not licensed. `HubError.fromStatus` checks `X-Error-Code` first and
  then maps both 401 and 403 to `GatedRepo`.
- **A `.gguf` sibling with no `lfs` block is not a model.** HF routes every real
  model through LFS; a non-LFS sibling is a blob that happens to end in `.gguf`,
  and its `size` is the blob's, not a payload's. Those are dropped rather than
  offered as a 134-byte "model".

## Auth

`androidx.security:security-crypto` is not on the classpath and this project
forbids new Gradle dependencies, so `KeystoreTokenStore` uses
`android.security.keystore` + `javax.crypto` directly — the same mechanism
`EncryptedSharedPreferences` wraps, with zero new dependencies. The AES key is
non-exportable and lives in the TEE where the device has one; what lands in the
preferences XML is `iv:ciphertext`.

**The feature works fully without a token.** `HubTokenSource.NONE` is the
default and every ungated download needs nothing.

The Keystore round-trip is **unverified**. `KeyGenParameterSpec` needs a real
Android Keystore, which does not exist on the JVM — and the JVM tests that used
to cover the no-token path were deleted with the rest of the test sources, so
**nothing automated covers any of this now**.

## What is NOT verified

**Live HuggingFace downloads are UNVERIFIED; no network/device was available.**

Specifically untested:

- `UrlConnectionTransport` against a real socket. It is the class that opens
  the connection, and nothing exercises it. (It used to be covered through a
  fake `HubTransport`; those tests were deleted.)
- `KeystoreTokenStore` encrypt/decrypt round-trip (needs a device Keystore).
- `AndroidDeviceBudget` against real `StatFs` / `ActivityManager` values.
- The HF API's live response shapes. Hand-written fixtures modelled the
  documented shape of `GET /api/models/{id}?blobs=true`, including its awkward
  cases (pointer `size` beside `lfs.size`, `gated` as a string, a sibling with
  no size, a nested path, a shard marker) — but the fixtures are gone with the
  test suite, so those shapes are no longer checked against anything.

No real HF token was used, requested, or committed. Test fixtures contain no
credential.
