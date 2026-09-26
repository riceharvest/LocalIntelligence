# The RAM fit model: derivation, measurements, and what is still an estimate

RAM is this product's primary metric. The app shows a fit verdict before the
user spends 2 GB, and if that number is wrong the product is wrong. This
document is the record of what each number in
`core/src/main/kotlin/dev/localintelligence/core/hub/` is derived from, and it
separates **MEASURED** from **ESTIMATED** so a reader never has to guess which
is which.

It also records, in §9, what long-term memory actually does — including the
part of it that was broken and what the numbers below do and do not establish
about retrieval quality.

Two paths produce a verdict, and they must not disagree:

| path | input | class | status |
|---|---|---|---|
| `ModelMemoryEstimator` (`core/model/gguf`) | the file's own tensor table | exact | MEASURED against a real 668,788,096-byte TinyLlama Q4_K_M |
| `PreDownloadMemoryModel` (`core/hub/FitGate.kt`) | file name + file size + context | bound | MEASURED against 160 real GGUFs, see below |

`HuggingFaceClient.probeHeader` fetches the first 8 MiB of the candidate file and
`plan()` hands the parsed header to `GgufMemoryModel`, so the exact path is used
whenever the probe succeeds. The pre-download model is the fallback for an
offline device, a gated repo or a non-GGUF body.

---

## 1. Bits per weight

### 1.1 The derivation

A quantised tensor is a whole number of fixed-size blocks, so its cost is
`ceil(elements / QK) * sizeof(block_qX)`, and the average cost of one weight is
`sizeof(block_qX) * 8 / QK`. That is not a convention to be remembered; it is a
`sizeof` to be taken. `docs/measure/blocksize.py` compiles the header this
project links against and asks:

```
$ python3 docs/measure/blocksize.py
block_q4_0   elems=32   bytes=18   bpw=4.5000
block_q4_1   elems=32   bytes=20   bpw=5.0000
block_q5_0   elems=32   bytes=22   bpw=5.5000
block_q5_1   elems=32   bytes=24   bpw=6.0000
block_q8_0   elems=32   bytes=34   bpw=8.5000
block_iq4_nl elems=32   bytes=18   bpw=4.5000
block_q2_K   elems=256  bytes=84   bpw=2.6250
block_q3_K   elems=256  bytes=110  bpw=3.4375
block_q4_K   elems=256  bytes=144  bpw=4.5000
block_q5_K   elems=256  bytes=176  bpw=5.5000
block_q6_K   elems=256  bytes=210  bpw=6.5625
block_q8_K   elems=256  bytes=292  bpw=9.1250
block_iq2_xxs elems=256 bytes=66   bpw=2.0625
block_iq2_xs  elems=256 bytes=74   bpw=2.3125
block_iq2_s   elems=256 bytes=82   bpw=2.5625
block_iq3_s   elems=256 bytes=110  bpw=3.4375
block_iq1_s   elems=256 bytes=50   bpw=1.5625
block_iq1_m   elems=256 bytes=56   bpw=1.7500
block_iq4_xs  elems=256 bytes=136  bpw=4.2500
```

Against `ggml-common.h` at tag **b4661**, the tag this project builds. Two
figures in the previous `GgufQuant` enum were the wrong block entirely:
`Q4_1` was carrying 4.5 (that is `block_q4_0`, 18 bytes) when `block_q4_1` is
20 bytes per 32 elements = **5.0**, and `Q5_1` was carrying 5.5
(`block_q5_0`, 22 bytes) when `block_q5_1` is 24 bytes = **6.0**.

### 1.2 The measurement, and why the block layout is not enough

A file named `Q4_K_M` is not a Q4_K file. It is a *blend*: most tensors are Q4_K
and some are promoted to Q6_K, and which ones depends on the model and the
converter. So the label alone cannot give a bits-per-weight, and the only honest
figure is one measured from real files.

`docs/measure/gguf_probe.py` fetches **only the header** of each file with a
byte-range request, parses the GGUF tensor table, and computes
`8 * weightBytes / parameters` exactly as `GgufQuantType.bytesFor` would:

```
$ curl -sSL -H 'Range: bytes=0-8388607' \
    'https://huggingface.co/<repo>/resolve/main/<file>?download=true' > prefix.bin
$ python3 docs/measure/gguf_probe.py targets.json results.json
```

Sample: **160 GGUFs across 9 repositories** — `TheBloke/TinyLlama-1.1B-Chat-v1.0`,
`Qwen/Qwen2.5-0.5B-Instruct`, `bartowski/Llama-3.2-1B-Instruct`,
`bartowski/Qwen2.5-3B-Instruct`, `bartowski/gemma-2-2b-it`,
`unsloth/gemma-3-1b-it`, `TheBloke/Mistral-7B-Instruct-v0.2`,
`bartowski/Phi-3-mini-4k-instruct`, `bartowski/Meta-Llama-3.1-8B-Instruct` —
every quant each of them publishes. 160 of 160 parsed.

| label | n | measured median | measured max | block layout | max/median | source |
|---|---|---|---|---|---|---|
| `IQ1_S` | 2 | 3.0824 | 4.4044 | 1.5625 | 1.43 | measured (n<3) |
| `IQ1_M` | 2 | 3.1726 | 4.4267 | 1.7500 | 1.40 | measured (n<3) |
| `IQ2_XXS` | 2 | 3.3231 | 4.4641 | 2.0625 | 1.34 | measured (n<3) |
| `IQ2_XS` | 1 | 2.4125 | 2.4125 | 2.3125 | 1.00 | measured (n<3) |
| `IQ2_S` | 1 | 2.5438 | 2.5438 | 2.5625 | 1.00 | measured (n<3) |
| `IQ2_M` | 4 | 2.9349 | 4.5725 | blend | 1.56 | measured |
| `IQ3_XXS` | 2 | 3.9238 | 4.6815 | 3.0625 | 1.19 | measured (n<3) |
| `IQ3_XS` | 3 | 3.4977 | 3.5919 | 3.3000 | 1.03 | measured |
| `IQ3_S` | 1 | 3.5195 | 3.5195 | 3.4375 | 1.00 | measured (n<3) |
| `IQ3_M` | 5 | 3.8834 | 4.2459 | 3.6600 | 1.09 | measured |
| `IQ4_XS` | 6 | 4.6261 | 5.6639 | 4.2500 | 1.22 | measured |
| `IQ4_NL` | 3 | 4.6526 | 5.7233 | 4.5000 | 1.23 | measured |
| `Q2_K` | 7 | 3.4051 | 5.4669 | 2.6250 | 1.61 | measured |
| `Q2_K_L` | 3 | 3.6704 | 5.4669 | blend | 1.49 | measured |
| `Q2_K_XL` | 1 | 5.4992 | 5.4992 | blend | 1.00 | measured (n<3) |
| `Q3_K_S` | 6 | 3.6345 | 5.4592 | 3.4375 | 1.50 | measured |
| `Q3_K_M` | 7 | 4.0925 | 5.7277 | 3.6400 | 1.40 | measured |
| `Q3_K_L` | 7 | 4.3692 | 4.7259 | 4.0000 | 1.08 | measured |
| `Q3_K_XL` | 4 | 4.9295 | 5.7631 | blend | 1.17 | measured |
| `Q4_0` | 6 | 4.8387 | 5.7237 | 4.5000 | 1.18 | measured |
| `Q4_1` | 0 | — | — | 5.0000 | — | layout only; see note 1 |
| `Q4_0_4_4` | 3 | 4.7093 | 4.9399 | 4.5000 | 1.05 | measured |
| `Q4_0_4_8` | 3 | 5.1589 | 5.3336 | 5.0000 | 1.03 | measured |
| `Q4_0_8_8` | 3 | 6.0580 | 6.1210 | 6.0000 | 1.01 | measured |
| `Q4_K_S` | 8 | 4.7041 | 6.1964 | 4.5800 | 1.32 | measured |
| `Q4_K_M` | 9 | 5.0090 | 6.3969 | 4.8300 | 1.28 | measured |
| `Q4_K_L` | 3 | 5.2828 | 5.5897 | 5.0000 | 1.06 | measured |
| `Q4_K_XL` | 1 | 6.4047 | 6.4047 | blend | 1.00 | measured (n<3) |
| `Q5_0` | 3 | 5.5655 | 6.1500 | 5.5000 | 1.11 | measured |
| `Q5_1` | 0 | — | — | 6.0000 | — | layout only |
| `Q5_K_S` | 8 | 5.5894 | 6.6397 | 5.6900 | 1.19 | measured |
| `Q5_K_M` | 9 | 5.8499 | 6.7593 | 5.9300 | 1.16 | measured |
| `Q5_K_L` | 3 | 6.0266 | 6.2617 | blend | 1.04 | measured |
| `Q5_K_XL` | 1 | 6.9429 | 6.9429 | blend | 1.00 | measured (n<3) |
| `Q6_K` | 9 | 6.5639 | 8.1800 | 6.5625 | 1.25 | measured |
| `Q6_K_L` | 4 | 6.8963 | 7.0020 | blend | 1.02 | measured |
| `Q6_K_XL` | 1 | 8.1969 | 8.1969 | blend | 1.00 | measured (n<3) |
| `Q8_0` | 9 | 8.5013 | 8.5032 | 8.5000 | 1.00 | measured |
| `Q8_K_XL` | 1 | 11.7771 | 11.7771 | blend | 1.00 | measured (n<3) |
| `F16` | 3 | 16.0009 | 16.0011 | 16.0000 | 1.00 | measured |
| `BF16` | 1 | 16.0021 | 16.0021 | 16.0000 | 1.00 | measured (n<3) |
| `F32` | 4 | 32.0000 | 32.0000 | 32.0000 | 1.00 | measured |

For the *uniform* formats the block layout and the measurement agree to within
noise, which is the check that the probe is right: Q8_0 measures 8.5013 against
a layout of 8.5000 (0.015%), F16 16.0009 against 16 (0.005%), Q6_K 6.5639
against 6.5625 (0.02%). For the *blended* labels they diverge by up to 29%:
`Q2_K` measures 3.4051 against a Q2_K block layout of 2.6250, because the
shipped "Q2_K" presets store 58-87% of their tensors as Q3_K.

`GgufQuant.bitsPerWeight` is the measured median; `bitsPerWeightMax` is the
measured maximum; `measuredSamples` says how many files it came from, and an
entry with `n = 0` is the block layout and nothing else. **Every figure in the
enum is traceable to one of those two columns.**

Two rules, both applied in the enum and both worth stating because they are
judgement:

1. **`Q5_1` has no sample at all** (`n = 0`). No repository in this sample
   publishes one, so the entry is the block layout, 24 bytes / 32 = 6.0000.
2. **`Q4_1` has exactly one sample and the enum does not use it.** The single
   file is `gemma-3-1b-it-Q4_1.gguf`, which measures 6.0607 — but 30% of that
   model's parameters are a Q8_0 vocabulary embedding, so the file-level figure
   says more about gemma-3's embedding than about the Q4_1 format, and one
   sample is not a median. The enum uses the block layout (5.0000) and records
   `measuredSamples = 0` so the weaker basis is visible rather than hidden.

### 1.3 What the spread costs and what it does not

The spread reaches 1.61x (`Q2_K`, 2.9635 to 5.4669) because the vocabulary
embedding is the tensor that moves the figure, and converters often keep it in a
wider type than the body. For gemma-3-1b it is 30% of all parameters and sits in
Q8_0, so its "Q4_K_M" file measures 6.3969.

That does **not** damage the fit check, because the *weights* term is the file
size, which is exact and independent of bits-per-weight. The figure is used for
one thing: recovering a parameter count from a file size when the file name does
not declare one, `params = bytes * 8 / bitsPerWeight`. The case that matters is
Phi-3-mini, the one architecture of the nine whose name contains no size:

```
file size 2,393,231,360 bytes x 8 / 5.0090 bits = 3,822,290,053
true      3,821,079,552 parameters                          0.03% out
```

The file is `Phi-3-mini-4k-instruct-Q4_K_M.gguf` and its name contains no size
token at all, so before this change it was priced as
`PreDownloadMemoryModel.UNKNOWN_PARAMETER_COUNT` — 7,000,000,000 parameters,
**636% high** — and then charged a 7B model's KV geometry on top.

---

## 2. The KV cache

```
kvBytes = 2 * layers * kvWidth * contextLength * 2 bytes
```

`kvWidth` is `head_count_kv * key_length`. The leading 2 is K and V; the
trailing 2 is the f16 cache llama.cpp allocates by default.

`kvWidth` is used as one number rather than as `kvHeads * headDim` because
grouped-query attention is a *ratio* and the ratio is the thing that varies
between models while the product is one number per model. The previous version
guessed both factors independently — a quadratic root for the hidden size, a
hard-coded `/512` for the head count, a hard-coded `128` for the head dimension
— and the measured GQA ratios are:

| model | params | layers | n_head | n_head_kv | head_dim | n_embd | GQA ratio |
|---|---|---|---|---|---|---|---|
| Qwen2.5-0.5B-Instruct | 630,139,776 | 24 | 14 | 2 | 64 | 896 | 7.0 |
| gemma-3-1b-it | 999,885,952 | 26 | 4 | 1 | 256 | 1152 | 4.0 |
| TinyLlama-1.1B-Chat | 1,100,048,384 | 22 | 32 | 4 | 64 | 2048 | 8.0 |
| Llama-3.2-1B-Instruct | 1,235,814,432 | 16 | 32 | 8 | 64 | 2048 | 4.0 |
| gemma-2-2b-it | 2,614,341,888 | 26 | 8 | 4 | 256 | 2304 | 2.0 |
| Qwen2.5-3B-Instruct | 3,085,846,528 | 36 | 16 | 2 | 128 | 2048 | 8.0 |
| Phi-3-mini-4k-instruct | 3,821,079,552 | 32 | 32 | 32 | 96 | 3072 | **1.0** |
| Mistral-7B-Instruct-v0.2 | 7,241,732,096 | 32 | 32 | 8 | 128 | 4096 | 4.0 |
| Meta-Llama-3.1-8B-Instruct | 8,030,261,312 | 32 | 32 | 8 | 128 | 4096 | 4.0 |

All nine rows are MEASURED: `<arch>.block_count`, `<arch>.attention.head_count`,
`<arch>.attention.head_count_kv`, `<arch>.attention.key_length` and
`<arch>.embedding_length` read out of the files themselves. Nine architectures
is a sample, not a census, which is why `MeasuredArchitectures` falls back to a
derived estimate outside them and reports the result as a **range** rather than
a point: GQA 8 at the low end, GQA 4 (the median of the measured set) in the
middle, plain MHA at the high end. GQA 1 is not a pessimistic invention —
Phi-3-mini-4k-instruct really ships it, at 3.8B parameters, inside the range
this app targets.

---

## 3. Before and after, through the real call path

File name parsed by `HuggingFaceClient.parseParameterCount`, context 4096
(`ModelImporter.DEFAULT_CONTEXT_LENGTH`), the same floor and ratio on both
sides, one Q4_K_M file per repository. "KV true" is
`2 * block_count * head_count_kv * head_dim * 4096 * 2` from the file's own
metadata.

| file | old total | new total | old / true | KV old | KV true | KV new |
|---|---|---|---|---|---|---|
| Qwen2.5-0.5B-Instruct | 659.2M | 687.9M | 0.96 | 100.7M | 50.3M | 129.4M |
| gemma-3-1b-it | 1011.6M | 982.2M | 1.03 | 157.3M | 109.1M | 109.1M |
| TinyLlama-1.1B-Chat | 874.3M | 828.2M | 1.06 | 157.3M | 92.3M | 92.3M |
| Llama-3.2-1B-Instruct | 1013.2M | 983.9M | 1.03 | 163.6M | 134.2M | 109.1M |
| gemma-2-2b-it | 2019.0M | 2060.3M | 0.98 | 346.0M | 436.2M | 284.6M |
| Qwen2.5-3B-Instruct | 2374.5M | 2148.0M | 1.11 | 367.0M | 151.0M | 151.0M |
| **Phi-3-mini-4k-instruct** | **2997.2M** | **4071.0M** | **0.74** | **367.0M** | **1610.6M** | **1610.6M** |
| Mistral-7B-Instruct-v0.2 | 4992.7M | 4992.7M | 1.00 | 536.9M | 536.9M | 536.9M |
| Meta-Llama-3.1-8B-Instruct | 5589.6M | 5556.0M | 1.01 | 570.4M | 536.9M | 536.9M |

- Five of nine are now **exact** (the measured anchor matched).
- Worst-case error in the total the user is shown: **41.5% -> 11.5%**.
- Mean error: **8.7% -> 2.4%**.
- The one case that understated the requirement by 1.2 GiB is now exact.

The remaining 11.5% is Qwen2.5-0.5B, where 79 MB of KV is missed on a 688 MB
total, and it is missed **high** — which refuses rather than OOMs.

### 3.1 The pre-download model against the exact estimator, on a real file

`docs/measure/MemoryModelProbe.java` runs the repo's own parser and estimator
over a real 668,788,096-byte model (`tinyllama-1.1b.Q4_K_M.gguf`):

```
headerBytes      1709436
tensors          201
complete         true
declaredWeights  667078656
tableParams      1100048384
--- context 4096, exact (tensor table) ---
  weights   667078656
  kv        92274688   (layers=22 kvHeads=4 headDim=64)
  overhead  67108864
  TOTAL     826462208
--- pre-download, name + size only, ctx 4096 ---
  central        828171648
```

**828,171,648 against 826,462,208: 0.21% apart.** The old model returned
893,183,360 for the same input, 8.07% high. The range collapses to a point here
because the file's parameter count lands on a measured anchor.

---

## 4. The header probe window

`HuggingFaceClient.HEADER_PROBE_BYTES` was 4 MiB, on a comment claiming that
cleared "a 151936-token vocabulary". Measured over the same 160 files, the GGUF
header (magic + metadata + tensor table) spans **735,494 to 7,840,907 bytes**,
median 5,956,764, and **113 of 160 — 70.6% — are larger than 4 MiB**. A
truncated probe does not fail loudly: the parser returns what it read, the
tensor table comes back empty, and the app silently drops to the name-based
estimate for seven files in ten. The constant is now 8 MiB, which clears 160 of
160 at a cost of 0.4% of a 2 GB file.

---

## 5. What is still an ESTIMATE

| constant | value | why it is still a guess |
|---|---|---|
| `PreDownloadMemoryModel.RUNTIME_FLOOR_BYTES` | 64 MiB | the compute graph, RoPE tables, `vocab * 4` logits and tokenizer arrays, priced from first principles and never measured on a device |
| `PreDownloadMemoryModel.RUNTIME_RATIO` | 2% of weight bytes | page-in slack and backend graph allocations; no device measurement |
| `MeasuredArchitectures.MATCH_TOLERANCE` | 1.25x | judgement: within a quarter of a measured architecture, borrowing its geometry is defensible; outside it, the model says DERIVED and widens the range |
| `FitGate.DECISION_FACTOR` | 1.15 | unchanged, and unchanged deliberately — see §5.1 for what it does and does not cover |
| `AndroidDeviceBudget.USABLE_FRACTION` | 0.55 of physical RAM | unchanged; the *available* side of the comparison, not the estimate |

Section 6 is the procedure that replaces the first two with measurements.

### 5.1 What `DECISION_FACTOR = 1.15` actually covers

Recomputed through the real call path at the context the app allocates
(`parseParameterCount` on the file name, `PreDownloadMemoryModel.estimateRange`,
the same 64 MiB floor and 2% ratio on both sides):

```
worst case  12.98%  (Qwen2.5-0.5B-Instruct)
mean         2.48%
```

So 15% covers the worst case **on this nine-architecture sample**, with two
points to spare. Three things it does not cover:

1. **The sample.** Nine architectures is a sample. A model outside them takes
   the derived path, whose embedding-width error was measured at up to +65%
   and whose layer count is interpolated between anchors.
2. **The quant spread.** `params = bytes * 8 / bitsPerWeight`, and the
   measured bits-per-weight range reaches 1.61x the median for `Q2_K`. The
   *high* end is the dangerous one: a higher bpw yields fewer parameters, a
   smaller KV cache, and a gate that says yes to a model that will not load.
   `PreDownloadMemoryModel.parameterCountRange` now carries both ends, and the
   measured spread for a 1.7 GB file is:

   | quant | recovered parameters | spread |
   |---|---|---|
   | `Q2_K` | 2,495,914,446 .. 4,007,199,403 | 60.6% |
   | `Q4_K_M` | 2,133,051,116 .. 2,724,079,594 | 27.7% |
   | `Q6_K` | 1,668,082,480 .. 2,078,781,621 | 24.6% |
   | `Q5_K_M` | 2,018,687,539 .. 2,332,503,921 | 15.5% |

3. **The two constants above.** On a 400 MB model the 64 MiB floor alone is
   16% of the total and is pure argument.

`DECISION_FACTOR` is **not** widened. Inventing a larger factor would be
choosing a number for comfort rather than deriving it, which is the failure
this document exists to prevent. What changed is that the verdict no longer
says more than the arithmetic supports — see §5.2.

### 5.2 The verdict now states its margin

`FitGate.MarginBand` grades a passing verdict on its headroom, with the bands
set from the measured distribution rather than chosen:

| band | headroom | what the user is told |
|---|---|---|
| `CONFIDENT` | >= 25% | more room than this estimate has ever been measured wrong |
| `TIGHT` | >= 10% | fits, but the margin is inside the measured error — "will probably fit rather than certainly" |
| `MARGINAL` | < 10% | fits by less than the estimate's worst measured error; "close to a coin flip" |

Nothing in this widens the gate. Every case already fit or did not fit before;
what changed is the sentence. A model needing 95% of the budget and one needing
40% of it used to produce the identical text, and only one of those was a
promise.

The same grading is applied on the tensor-table path in `GgufMemoryModel`, with
one difference that is stated rather than hidden: there the weights and the KV
cache are read term for term out of the file, and the *only* unmeasured term is
`overheadBytes`. Its width is the entire reported range.

### 5.3 The context length, and where it was still wrong

`PreDownloadMemoryModel.DEFAULT_CONTEXT_LENGTH` was 2048. The app allocates
4096 (`ModelImporter.DEFAULT_CONTEXT_LENGTH`, `LlamaCppBackend.DEFAULT_CONTEXT_LENGTH`,
and the load gate in `AppContainer.loadModel`). This is the same defect the
prior audit found in `ModelManagerScreen` — a fit verdict computed at a context
the app does not use — in the opposite direction: it **under**-counted.

Measured against a 4096 load, over the nine measured architectures:

```
architecture            est@2048      true@4096     error
Qwen2.5-0.5B-Instruct     623.3M        609.0M     +2.4%
gemma-3-1b-it             927.8M        982.3M     -5.6%
TinyLlama-1.1B-Chat       782.0M        828.2M     -5.6%
Llama-3.2-1B-Instruct     929.7M       1009.4M     -7.9%
gemma-2-2b-it            1915.0M       2208.9M    -13.3%
Qwen2.5-3B-Instruct      2071.3M       2146.8M     -3.5%
Phi-3-mini-4k-instruct   3265.6M       4071.0M    -19.8%
Mistral-7B-Instruct      4464.6M       4733.0M     -5.7%
Meta-Llama-3.1-8B        5287.6M       5556.0M     -4.8%
```

Worst case **-19.8%**, eight of nine under-statements, and 19.8% > the 15% the
decision factor provides. The KV term is exactly linear in context, so this is
not an estimate disagreeing with reality — it is the model pricing half a
cache. Now 4096.

`ModelDownloader.PreDownloadContextLength` (in `:android`, not owned by the
change that fixed this) is **still 2048** and is the one remaining instance.
The exact edit is in the PR description.

### 5.4 Two unmeasured quant entries no longer feed a safety verdict

`Q4_1` and `Q5_1` have `measuredSamples == 0` — they carry the *block layout* of
a format no repository in the 160-file sample publishes.
`resolveParameterCount` used them unconditionally to recover a parameter count.
It now checks `GgufQuant.isMeasured` and falls back to
`UNKNOWN_PARAMETER_COUNT`, which fails toward refusing. The cost is that a real
`Q4_1` file is priced as a 7B model; the benefit is that a number this project
has never measured no longer reaches a verdict whose entire job is to avoid
unmeasured numbers.

---

## 6. On-device resident cost: NOT MEASURED on this branch

**No `dumpsys meminfo` number is reported here, because none was taken.** The
`pixel10pro` AVD exists (Android 36, x86_64, `hw.device.name=pixel_7`,
`hw.ramSize=4096`, 4 cores, KVM available) and could not be booted. Six
launches, four graphics backends, two accelerator settings; every one ended in
`SIGSEGV` inside `qemu-system-x86_64-headless` at the same point, immediately
after `The emulator is starting from scratch`:

```
$HOME/Android/Sdk/emulator/emulator -avd pixel10pro -no-window -gpu swiftshader -no-snapshot-save
WARNING | Failed to load snapshot 'default_boot'
USER_INFO | The emulator is starting from scratch. Reason: different renderer configured
Segmentation fault (core dumped)

$ dmesg -T | grep qemu-system
Fri 2026-09-25 23:41:15 CEST 940729 SIGSEGV ... qemu-system-x86_64
Fri 2026-09-25 23:44:13 CEST 1000401 SIGSEGV ... qemu-system-x86_64-headless
Fri 2026-09-25 23:50:06 CEST 1070809 SIGSEGV ... qemu-system-x86_64-headless
```

Tried: windowed and `-no-window`; `-gpu swiftshader_indirect`, `-gpu off`,
`-gpu swiftshader`, `-gpu guest`; `-feature -Vulkan`; `-accel on`; with and
without `-no-snapshot-save`. Not tried, because they destroy state the task
forbids: `-wipe-data`, and recreating the AVD. A guess would be worse than
nothing, so here is the procedure instead.

### 6a. A later attempt got further, and still failed

A subsequent attempt cleared the stale lock files left behind by earlier
killed qemu processes — `~/.android/avd/pixel10pro.avd/{hardware-qemu.ini.lock,
multiinstance.lock}` — and then got materially further than anything above: with
`-verbose -show-kernel` the Android kernel log came up and the guest reached
`init: starting service 'bootanim'`, i.e. the system image booted, `apexd`
mounted, `surfaceflinger` started. The AVD and its system image are therefore
NOT corrupt, and the earlier "cannot boot this image" conclusion was wrong.

It then died silently, with no error line, and qemu was gone from the process
table. Re-running with the exit code captured gives the truth:

```
EXIT=139        # 128 + 11 = SIGSEGV, i.e. the emulator process itself faults
```

So this is a host-side emulator crash after a successful guest boot, not an
Android failure and not an out-of-memory condition (`/proc/pressure/memory`
reported `some avg10=0.00` throughout; `/dev/zram0` being 100% full is normal
for zram and is not the cause).

Two false leads worth recording so nobody repeats them:
- `tcsetattr: Inappropriate ioctl for device` appears in the log of every
  failing run. It is an artifact of the emulator's controlling terminal under a
  pipe, not the cause — it appears in runs that reach bootanim and in runs that
  die earlier, and it does not appear in the `-verbose` run that got furthest.
- The GUI path (`Created extended window in 292.741ms`) reaches further than
  `-no-window` in the log, but both die identically, so the display is not it.

Not tried, because they destroy the persistent app data and the staged model:
`-wipe-data`, and recreating the AVD. Conclusion: the emulator is currently
unusable on this host and no resident-cost number can be taken. The procedure
below is what to run on a working emulator or a physical device.

### 6.1 The procedure

```bash
# 0. NEVER -wipe-data. The data partition holds the app and the model.
emulator -avd pixel10pro -no-window -gpu swiftshader -no-snapshot-save &
adb wait-for-device
until [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = 1 ]; do sleep 5; done

# 1. Baseline: app launched, no model loaded.
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n dev.localintelligence.app/.MainActivity
sleep 15
adb shell dumpsys meminfo dev.localintelligence.app > /tmp/meminfo-idle.txt

# 2. With TinyLlama resident. Use the app's own Scan storage button after
#    copying the file into filesDir/models, or load it through the UI.
adb push /mnt/ssd/models/localintelligence/tinyllama-1.1b.Q4_K_M.gguf \
    /data/local/tmp/models/tinyllama-1.1b.Q4_K_M.gguf
#   ... adopt it via Scan storage, which globs files/models/*.gguf ...
sleep 20
adb shell dumpsys meminfo dev.localintelligence.app > /tmp/meminfo-model.txt

# 3. The split that matters: how much of it is file-backed mmap.
adb shell dumpsys meminfo dev.localintelligence.app | sed -n '/App Summary/,/TOTAL/p'
adb shell cat /proc/$(adb shell pidof dev.localintelligence.app)/smaps_rollup
adb shell cat /proc/$(adb shell pidof dev.localintelligence.app)/status | grep -E "VmRSS|VmHWM"
```

Record for both states: `TOTAL PSS`, `Native Heap`, `Dalvik Heap`, `Other`,
the `TOTAL` line's `RSS`, and from `smaps_rollup` the `Private_Dirty` /
`Shared_Clean` / file-backed split. The delta between the two states is the
model's marginal cost, and it is the only part of the number the fit model has
to predict.

### 6.2 The caveat that has to travel with every number from this procedure

An x86_64 AVD is not a phone, and the differences are not small:

- **RAM.** The AVD reports 4 GB with no memory pressure and no other apps. A
  phone has 8-16 GB, several resident apps, and a low-memory killer that decides
  the app's fate. A PSS measured here says nothing about a PSS there.
- **The mmap accounting is the same, and that part does transfer.** The weights
  are a file mapping in both cases, so the *file-backed share* of the delta is
  the part of the measurement that generalises.
- **Decode speed does not transfer at all.** On this AVD, CPU-bound decode has
  been measured at about **0.66 tok/s**, which reads as a hang and is an
  artefact of emulated CPU. No performance conclusion may be drawn from it.
- **What a device would still need.** One run each at context 2048 and 4096,
  one model at least three times larger, and `dumpsys meminfo` taken *after*
  `llama_memory` has actually allocated its context, not merely after the file
  was mapped.

### 6.3 What the build does cost, MEASURED here

Disk, not RAM, and from this build:

| | bytes | |
|---|---|---|
| `app-debug.apk` | 83,906,795 | 80.0 MiB, debug, unstripped |
| `lib/arm64-v8a/liblitertlm_jni.so` | 14,882,976 | |
| `lib/arm64-v8a/libLiteRt.so` | 5,064,136 | |
| `lib/arm64-v8a/liblocalintelligence_llama_jni.so` | 5,001,368 | |
| `lib/arm64-v8a/libLiteRtClGlAccelerator.so` | 2,778,128 | |
| `lib/arm64-v8a/libc++_shared.so` | 1,292,896 | |
| **one ABI's native total** | **29,029,600** | 27.7 MiB, unstripped |

A device installs one ABI. These are debug builds; the release `.so` files are
smaller, and nobody should quote the 27.7 MiB as a shipping figure.

---

## 7. Reproducing everything above

```bash
# block layouts, from the tag this project builds
git clone --depth 1 --branch b4661 https://github.com/ggml-org/llama.cpp /tmp/llama.cpp
python3 docs/measure/blocksize.py

# 160 real GGUFs: header only, no full downloads
#   (edit the target list; one {"repo": ..., "file": ...} per GGUF)
python3 docs/measure/gguf_probe.py targets.json results.json

# the app's own estimator against a real model file
./gradlew :core:assemble
unzip -o -q core/build/libs/core.jar -d /tmp/corejar
javac -cp /tmp/corejar:"$KOTLIN_STDLIB" -d /tmp/probe docs/measure/MemoryModelProbe.java
java -cp /tmp/probe:/tmp/corejar:"$KOTLIN_STDLIB" MemoryModelProbe \
    /mnt/ssd/models/localintelligence/tinyllama-1.1b.Q4_K_M.gguf

# the app itself
PIDROID_LLAMA_DIR=/path/to/llama.cpp ./gradlew :app:assembleDebug
```

The `:core:assemble` line above is a compile, not a test run. There is no test
suite in this repository, and no `:core:test`; see docs/build.md.

---

## 9. Long-term memory: what it actually does

### 9.1 It was never written to

Verified on `b9defbf` by grepping the whole tree for call sites:

```
$ grep -rn '\.remember(' --include=*.kt . | grep -v androidTest
android/.../ResilientMemoryStore.kt:126:  override suspend fun remember(...)
android/.../RoomMemoryStore.kt:206:       override suspend fun remember(...)
core/.../agent/MemoryStore.kt:17:         suspend fun remember(...)
core/.../agent/MemoryStore.kt:36:         override suspend fun remember(...)
```

Four hits: one interface declaration and three implementations. **Zero call
sites.** The loop read memory at `AgentController.kt:1036`
(`memory.search(task, config.memoryResults)`, inside `buildRequest`) and never
wrote any, so the table was empty for the life of the process and the memory
block injected into every prompt was always empty.

The write side now exists as `core/memory/MemoryWritePolicy` (what qualifies)
and `core/memory/LexicalMemoryStore` (`recordTurn`, the call the loop was
missing). It is a first-person declarative pattern matcher — no model call, no
summarisation, no embeddings, no second inference. The reason is RAM: an
unconditional "remember every turn" is a table with no ceiling, and RAM is the
primary metric.

### 9.2 The search was token-set matching with a length filter

The old rule was `lowercase → split on [^a-z0-9]+ → drop anything not longer
than 2 characters`, matched as a set, ranked by `importance` and then `id`.

Measured against a five-memory corpus (the real, compiled
`InMemoryMemoryStore`, not a reading of the source):

| query | result |
|---|---|
| `what's my wifi password` | 2 hits, correct one first — worked |
| `where do I live` | **0 hits** — "live" and "address" are different tokens |
| `how much is the gym` | **4 hits**, gym memory ranked **third** |
| `the` | **3 hits** — "the" is 3 chars, so the length filter admits it |
| `passwords` | **0 hits** — no inflectional normalisation |
| `Anna` | 1 hit — worked |

Three distinct defects, all reproduced:

1. **Stopwords were indexed and matched.** "the" survives a >2-character
   filter, and it is the only term connecting a gym question to a wifi
   memory. Fourteen of the twenty most frequent English words are three
   letters or fewer; a length threshold cannot separate "cat" from "the".
2. **No normalisation.** `passwords` did not match `password`.
3. **Apostrophes and hyphens split words that are one word.** `wi-fi`
   tokenised to `wi` + `fi`, *both* then dropped by the length filter — a
   memory about wi-fi was literally unsearchable by the word wi-fi.

And the ranking was wrong independently of all three: `importance` was the
primary sort key, so a memory matching one of five query terms outranked one
matching all five whenever it had been stored at a higher importance.

After the change, against the same corpus:

| query | result |
|---|---|
| `what's my wifi password` | 1 hit, the right one |
| `how much is the gym` | 1 hit, the gym memory |
| `the` | **0 hits** |
| `passwords` | 1 hit |
| `where do I live` | **0 hits** — still, and cannot be fixed lexically |

### 9.3 What these numbers are and are not

**They are not a retrieval-quality measurement.** The corpus is five sentences
written by the author of this change, constructed to contain the specific
known failures. It is a regression check. There is no user corpus in this
repository, no relevance judgements, and no held-out set, so **no precision,
recall, MRR or nDCG figure is reported here, and none should be quoted from
this document.** Anyone who wants to claim a retrieval number needs to build
the corpus first; the machinery in `core/memory/` is the thing to measure, and
`MemoryIndex.rank` returns a `score` and a `matchedTerms` count per hit
specifically so a future evaluation can read them.

The limitation that survives the fix is stated rather than hidden: **a query
whose terms appear in no memory still retrieves nothing.** `"where do I live"`
does not find `"home address"`. That is a property of lexical retrieval, it
needs embeddings or a synonym table to fix, and there is no corpus against
which such a thing could be evaluated before it was built.

### 9.4 A second tokeniser, and why the store scans

`LexicalMemoryStore.search` takes its candidate set from `MemoryStore.all(500)`
rather than from `MemoryStore.search`. That is deliberate and it is a
workaround: the Room delegate prefilters with a SQL `LIKE` over a `keywords`
column built by `MemoryQueries.tokenize`, which is still the old rule, so a
query for `passwords` never reaches a row keyed `password` and no downstream
re-ranking can recover a row that was never fetched. Measured: **0 results**
for `passwords` before this change, 1 after, with the ranking unchanged.

The one-line fix in `MemoryQueries.tokenize` is in the PR description. Once it
lands, the scan can go back to being a delegated `search` and the per-query
allocation disappears. Until then the store is correct and slightly wasteful,
which is the right way round.
