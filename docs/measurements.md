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
