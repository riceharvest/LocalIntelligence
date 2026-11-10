# Measurements

**Status: EMPTY. No device measurement has ever been taken for this project.**

This file is intentionally a stub rather than a collection of estimates. The
app's own UI labels its numbers `MEASURED` / `COMPUTED` / `UNKNOWN` precisely
because that distinction is the honest core of the product's claim, and this
document is the same idea applied to the project as a whole.

## What is not measured, and why that is stated rather than filled in

There is no physical-device run of this app. The `pixel10pro` emulator was
abandoned after repeated host-side SIGSEGVs (`EXIT=139`) following bootanim, and
an x86_64 emulator could not speak for Tensor/TPU behaviour even had it run. So:

| Quantity | Value | Why |
|---|---|---|
| RAM after model load | `NOT MEASURED` | needs a physical device |
| Generation speed | `NOT MEASURED` | needs a physical device |
| Time to first token | `NOT MEASURED` | needs a physical device |
| Battery cost per session | `NOT MEASURED` | needs a physical device |
| Peak RAM during a run | `NOT MEASURED` | needs a physical device |
| Task completion rate | `NOT MEASURED` | needs a physical device |
| LiteRT-LM performance | `NOT MEASURED` | no real `.litertlm` has ever loaded |
| NPU / Tensor numbers | `UNAVAILABLE` | stock LiteRT-LM 0.13.1 ships no NPU library and no `GOOGLE_TENSOR` backend |

## Numbers that DO exist, and what they actually are

- **Host CPU llama.cpp control run:** 37.4 tok/s with the same TinyLlama, in a
  clean CPU-only llama.cpp build. This proves the model and llama.cpp are sound.
  It is a **desktop** number and says nothing about Android performance.
- **Android load time, pre-crash-fix era:** roughly 600–720 ms, observed on the
  emulator before emulator testing was abandoned. Useful only as a smoke signal.
- **Emulator generation:** ~0.67 tok/s. **This is not a product metric.** It is
  an x86_64 software-emulation figure recorded only so it is never mistaken for
  a device result later.
- **GGUF memory model:** fitted against 160 real GGUFs across 9 repositories,
  all 160 parsing. Worst-case fit error 11.5%, mean 2.4%. This is a *model of*
  RAM, not a measurement of it.

## How to fill this in

Follow [device-release-gate.md](device-release-gate.md) on a physical device.
For each row, record the device model, Android version, model file with its
SHA-256, and the exact command used. Then replace `NOT MEASURED` with the value
and its method.

Do not add a number here that was estimated, inferred, or taken on an emulator
without saying so in the same row. An empty table with honest labels is worth
more than a full one where nobody can tell which numbers are real — that is the
specific failure this project already made once, when selector recall figures
lived only in KDoc with no harness to re-derive them.

## In-app speed test

The app has a **Speed test**: on the Models screen under the loaded model, and in
Settings under Technical, Model. It runs the model that is already loaded, twice, with
a 256-token prompt and 64 generated tokens, and reports prefill and decode tokens per
second (the median of the two passes). It loads nothing, does not touch the
conversation, and is refused while an agent run is active. The result is kept in memory
until the app is closed and is labelled "measured on this phone" with its date.

It does not compare llama.cpp thread counts. The llama.cpp backend fixes its thread
count at load time, and changing it needs a reload, so the test does not offer it.

A number from the speed test is a measurement of one phone only when it is reported
with the following, all of which the result line or the device shows:

- device model and Android version
- model file name, quant, and SHA-256 if known
- backend (llama.cpp or LiteRT-LM) and accelerator (CPU, GPU or NPU)
- date, and the app build (commit) it came from
- for LiteRT-LM, a note that the token counts are estimates, because that backend
  reports them that way

Record it with those fields next to the row it belongs to. Do not add a number
taken on an emulator, on a desktop, or from a run that was not the in-app test without
saying so on the same line.

## Speculative decoding (branch w/specdecode): EMULATOR numbers, 2026-11-10

Result: speculation did not win. The branch is dropped. Every number in this section is an
**EMULATOR** number. None is a device number.

**What was tested.** Branch `w/specdecode` (d9850a31) adds prompt-lookup speculation to the
llama.cpp decode path. The setting is `model.speculative_decoding`, shown as "Faster replies
(experimental)", and it is off by default. When it is on, the backend asks for 9 output rows
(8 draft tokens plus 1). Drafts come from a 3-gram lookup, up to 8 tokens per batch. The path
runs only when the model is not hybrid and the request has no grammar and no media.

**Environment.** Android Virtual Device `li-e2e` (x86_64, 4 cores, 4 GiB guest RAM,
Android 15 / API 35, `google_apis_playstore` image, `hw.gpu.enabled=no`) on a Ryzen 9 5950X
host with 62 GiB RAM. The app ran with Compute set to CPU, and the result line confirms it:
"llama.cpp on CPU". Model: `MiniCPM5-1B-Q4_K_M.gguf` (688 MB, 219 tensors, architecture
`llama`). It is not hybrid, so the speculation path was allowed to run.

**Method.** The in-app Speed test ran with a 160-token prompt and 64 generated tokens. It
makes two passes and shows the median. The arms ran in interleaved pairs: off, on, off, on,
for eight pairs. A script set the switch through the real UI and read it back from the UI
dump. It reloaded the model for each arm and confirmed the reload with the `LOADED` line in
the `LIControl` log. It read the tok/s figures off the result line on the screen. One run
was dropped from the log because the screen gave no result line. It is not in the numbers.

| Decode tok/s | Speculation off | Speculation on |
|---|---|---|
| Mean of 8 runs (EMULATOR) | 30.6 | 29.9 |
| Median | 30.5 | 30.1 |
| Standard deviation | 2.0 | 3.6 |
| Range | 26.8 to 33.6 | 22.0 to 33.8 |

- **Paired delta (on minus off):** mean -0.70 tok/s, which is -2.3% of the off mean. Four of
  eight pairs favour on. Four favour off. The deltas are -4.0, +0.1, -9.3, +0.7, +7.0, +0.0,
  -0.9 and +0.8.
- **Prefill, same runs:** off 254.0 tok/s, on 239.5 tok/s, mean paired delta -14.4 tok/s
  (-5.7%). This branch changes no prefill code. Read the prefill gap as noise from the same
  machine.
- **Run 9 is not a pair.** The committed driver was re-run once from its repo path on the same
  day (off, 31.1 tok/s) to prove the path works. It sits outside the eight pairs and changes
  no mean here.
- **Noise floor:** the spread of decode alone is 22.3% of the mean with speculation off and
  39.5% with it on. In this harness, an effect smaller than about one fifth of the
  throughput is not visible. A win here needed to clear about 20%. The paired delta of 2.3%
  is far inside that.

**Verdict.** Drop `w/specdecode`. The card rule was to keep the branch only if it wins. It
did not win.

**What this does not show.** Nothing proves that the draft path ran. The native code logs
nothing about it. Memory per process cannot serve as a signal either: it was lower with
speculation on (1,670,786 kB against 1,700,110 kB on the first pair), which is the wrong
direction, and across the other pairs it sits in the same band for both arms (1,711.0 to
1,712.3 MB), so it tracks nothing. A path that never ran and a path that ran slower look the
same on the screen. Both readings give the same answer for this card: there is nothing to
keep. If someone tries speculation again, first add one native log line per batch. It must
state the draft length and the accepted length.

**Re-derive it.** The switch only exists on `w/specdecode`, tagged `archive/w-specdecode` at
d9850a31. Build and install that tree, then run `scripts/e2e/toggle-speedtest.sh loop 8` on a
running `li-e2e` emulator. It appends one row per run to `/tmp/li-e2e-spec/results.tsv`. Then
run `scripts/e2e/toggle-speedtest-stats.py` to print the table above. The header of the driver
script lists the settings and the exit codes. On main the switch is not there: the driver stops
and says the row is missing.
