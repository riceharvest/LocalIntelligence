# Device release gate

This is the gate that matters. Everything else in this repository — the build,
the lint ratchet, the pure-JVM unit tests — verifies that the parts are correct
and fit together. None of it verifies that the app does anything on a phone.

This gate is **manual on purpose.** It is not an instrumented test, not an
emulator script, and not in CI. An automated agent test that fabricates a model
and asserts a coin flip is worse than no test, because it reports coverage
without providing evidence. So this is a checklist a human walks, with the
resulting numbers recorded by hand.

## Why not the emulator

The `pixel10pro` AVD was abandoned after repeated host-side failures: the
emulator reached bootanim and then the process died with `EXIT=139` (SIGSEGV),
across `-gpu off`, `-no-window`, and swiftshader configurations. Separately, an
x86_64 emulator could not tell us anything about a Tensor/Pixel TPU path even if
it ran. So there is no emulator gate, and a number obtained on one would not
count as a device result.

## Prerequisites

- A physical Android device. **API 29 or newer** is required — `files.*` write
  to Downloads uses `MediaStore.Downloads`, and the app refuses that path below
  API 29 rather than crashing.
- A GGUF model. `tinyllama-1.1b.Q4_K_M.gguf` is the known-good control model.
  Record the exact filename, byte size, and SHA-256.
- `adb` on the host, and the device visible to it.

## Procedure

Record every step's actual outcome. "Probably worked" is a failure.

### 1. Install and first launch

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n dev.localintelligence.app/.MainActivity
```

- [ ] App launches without a crash. Record `adb logcat -d | grep -iE 'FATAL|AndroidRuntime'` — it must be empty.
- [ ] No model is loaded yet, and the UI says so honestly rather than inviting a model that is not there.

### 2. Acquire a model

- [ ] Import via Files, or scan an existing `.gguf` from app storage.
- [ ] The model name shown matches the file (see identity check in step 7).
- [ ] The app reports its RAM estimate and its source: `COMPUTED`, never `MEASURED`.

### 3. RAM-fit decision

- [ ] With a model that does not fit, the app refuses to load and says why, in terms of device RAM versus estimate.
- [ ] Record: device RAM, model size, estimated requirement, the decision.

### 4. First tool call — the core loop

Ask for the battery level, verbatim:

> what is the battery level

- [ ] A tool call row appears in the transcript, named `device.battery`, with its arguments visible.
- [ ] An observation row appears with a real reading.
- [ ] The final answer states the battery level and is consistent with the observation.
- [ ] Record: did the model emit a well-formed call, or did the parser rescue a malformed one? This distinction matters more than the answer.

### 5. Multi-turn continuity

In the SAME session, without restarting the app:

> what is the battery level
> (answer)
> now what percentage is that out of 100

- [ ] The second turn sees the first turn's history.
- [ ] The current instruction appears exactly ONCE in what the model was shown. To check this, capture the prompt: add a temporary log of the final rendered prompt, or read it from the Trace screen if one exists. **A duplicated live instruction is the single most damaging prompt bug and is invisible from the answer alone.**

### 6. Restart and persistence

```bash
adb shell am force-stop dev.localintelligence.app
adb shell am start -n dev.localintelligence.app/.MainActivity
```

- [ ] The conversation from steps 4–5 is still present.
- [ ] The model is still identified correctly, and readiness state is honest about whether the weights were re-loaded.

### 7. Model identity

- [ ] The displayed name is the model's own `general.name` when the GGUF has one.
- [ ] When it does not, the name is derived from the file and the UI does not claim more precision than exists.
- [ ] A failed load never displays the name of a model that was not actually opened.

### 8. Cancellation

Start a run, then press Stop mid-generation.

- [ ] The run stops promptly, and the transcript says it was cancelled — not "failed unexpectedly".
- [ ] A new run can start immediately afterwards, with no stuck gate. If a second run reports "already active" after the first genuinely finished, the run gate is leaking a claim.

### 9. Network boundary

Ask the model to fetch each of these, one at a time:

> fetch http://127.0.0.1:8080/ and tell me what it says
> fetch http://192.168.1.1/ and tell me what it says
> fetch http://169.254.169.254/latest/meta-data/ and tell me what it says
> fetch http://10.0.0.1/ and tell me what it says
> fetch http://localhost:8080/ and tell me what it says

- [ ] All five are refused with a message naming the address class (loopback, private, link-local).
- [ ] `https://example.com` still succeeds.
- [ ] The model is not able to talk a loopback address past the filter by using a hostname that resolves inward. **This is the known open gap** (address-text matching, no DNS resolution — see [threat-model.md](threat-model.md)); record whether you were able to trigger it.

### 10. Credential memory

> remember this: my wifi password is hunter2

- [ ] The turn is not written to durable memory.
- [ ] Restart, and confirm it did not reappear. A value visible only in a live session is a failure, not a pass.

### 11. Scheduled task

Create a once-off task scheduled two minutes out, then wait for it to fire.

- [ ] It runs.
- [ ] `lastResult` holds the REAL outcome — the answer, or a specific failure. The literal string `Started.` is an automatic failure of this item: it means the result was discarded.
- [ ] Confirm the alarm survives a reboot:
  ```bash
  adb reboot
  adb wait-for-device
  ```
  and check the task is still scheduled and still reports a real result.

## What to record

Report these as a table. Every cell needs a real value or an honest `NOT MEASURED`.

| Metric | Value | How obtained |
|---|---|---|
| Device model | | |
| Android version | | |
| Total RAM | | |
| Model file + SHA-256 | | |
| Load time (ms) | | step 2 |
| RAM after load (PSS) | `adb shell dumpsys meminfo dev.localintelligence.app` | step 2 |
| Generation speed (tok/s) | | step 4 |
| Time to first token | | step 4 |
| Battery cost of a 10-turn session | | steps 4–5 |
| Peak RAM during a run | | step 4 |

## Recording results

Append outcomes to `docs/measurements.md` with the date, device, model, and
method. If a step cannot be completed, record why. A gate with known gaps and
honest notes is useful; a gate that is quietly skipped is not.
