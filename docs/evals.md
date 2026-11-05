# Evals

The eval suite is the task list in `evals/tasks.jsonl` (81 tasks, one JSON object per line) and its runner,
`scripts/run-evals.py`. The runner drives the real app on a phone, through `scripts/devicectl.sh` (debug build
only). It writes one result file per model, device and app build, under `docs/scores/results/`, and
`docs/scores/build_scores.py` turns those files into `docs/scores/index.md`.

There is no desktop runner. The app's loop is built in `app/AppContainer.kt` with the on-device model backend,
the Android tool set (`AndroidTools`) and `android.util.Log`, so a desktop run would measure a different loop.

## What a task is

Each line has:

- `id`, `category`, `lang`, and `source`;
- `turns`: the messages sent in order. `new_session_per_turn` starts a new chat for each turn;
- `needs`: the tools the task is about;
- `fixture`: true when an answer depends on the fixture values the desktop harness used (a battery of 37,
  a price of 599). On a phone the answer is judged by the device spec, not those values;
- `setup` (optional): a fault to make before the task and undo after it (see below);
- `checks`: what must hold.

The check types:

| Type | Passes when |
|---|---|
| `call` | the named tool was called, with the arguments given (`args`, exact; `args_text`, a substring of each value; `args_not`, not this value). A call that failed does not count, unless `allow_failed` is set |
| `any_call` | one of the named tools was called |
| `only` | every call is one of the named tools or a read-only lookup (see below) |
| `forbid` | none of the named tools was called |
| `no_calls` | nothing was called |
| `conditional_call` | the named call is required when the battery level in the answer is under `threshold`, and forbidden otherwise; unverified when no level is in the answer |
| `under_fault` | the named tool failed and was tried at most `max_calls` times. Without an observed failure it is unverified |
| `answer` | the answer contains one of `any` (or all of `all`). With a `device` spec, a phone checks the spec instead |
| `answer_clean` | the answer is not empty and names no tool and no tool-call JSON |
| `answer_min_chars` | the answer has at least `n` characters |
| `reply_language` | the reply is in the language asked (below) |

A `device` spec on an `answer` check holds what a phone can see: `clean` (as `answer_clean`), `number` (the reply
has a digit), `any` (one phrase is in the reply) and `time_pattern` (a clock time). Every part present must hold.

## Verdicts

The runner computes every verdict; the phone never does. Each run is one of:

- `pass`: every check passed.
- `fail`: a check failed, or the run ended at the step limit or as an error.
- `unverified`: a check could not be decided from what the run observed. It counts as not passed.
- `wrong_language`: the reply is in the wrong language, and every other check passed. It is counted apart from `fail`.

A read-only call (a tool the catalogue marks `READ_ONLY`, read from `core/.../tool/catalogue/V0ToolCatalogue.kt`,
plus `tools.find`) cannot change the phone. An `only` check lets it through and reports it as a reason
(`harmless lookup: [...]`), so a lookup before the right call does not fail the task.

The reply-language check: for Russian and Japanese the reply's letters must be in the script (Cyrillic; kana for
Japanese). For Dutch, German and Spanish, at least two of the language's common function words must appear.

## Faults

The three `failure` tasks make a fault, and the check is that the agent does not retry the tool more than
`max_calls` times and replies honestly:

| Task | Fault | Undone after the task |
|---|---|---|
| `fault-calendar-denied` | `READ_CALENDAR` revoked (`pm revoke`) | `pm grant` |
| `fault-alarm-exact-denied` | `SCHEDULE_EXACT_ALARM` app-op denied | app-op `allow` |
| `fault-search-unavailable` | every web search service refused for 5 minutes (`devicectl searchblock`) | nothing; the block expires |

A fault task is `unverified` when the fault was not exercised (no failed call was observed), so a run without
the fault cannot pass by accident. These checks are not yet run on a phone; see "Not yet run" below.

## Usage

### Setup, once per emulator

`scripts/eval-setup.sh` does the setup on an emulator only. It refuses any other device: it checks
`ro.kernel.qemu`, `ro.boot.qemu` and `ro.hardware` (`ranchu` or `goldfish`), because a phone keeps its own
permissions and address book. It:

1. grants `READ_CONTACTS`, `READ_CALENDAR`, `ACCESS_COARSE_LOCATION` and `POST_NOTIFICATIONS` to the app;
2. allows `SCHEDULE_EXACT_ALARM` (`cmd appops set`), without which the alarm tools are refused;
3. allows do-not-disturb access (`cmd notification allow_dnd`), without which `phone.dnd` is refused;
4. adds one test contact, `Sam`, with the phone number `+31600000000`, unless it is already there.

The `contact-not-found` task asks for Quentin de Vries, a name this script never adds, so the task expects no such contact.
`LI_PKG` overrides the package (default `dev.localintelligence.app`). The app must be installed first
(debug build, see `docs/build.md`).

```bash
scripts/eval-setup.sh -s emulator-5554
```

### Run the suite

The app is put in Simple mode for the run and restored afterwards. Simple mode auto-accepts the call and message
tiers, so run it only on a test phone or emulator. Technical mode is the default.

```bash
scripts/run-evals.py --target device --serial emulator-5554 --model-id LFM3-1.7B-QAD-Q4_0 \
    --model-repo LiquidAI/LFM3-1.7B-GGUF --model-file LFM3-1.7B-QAD-Q4_0.gguf --mode simple --repeats 3
scripts/run-evals.py --target device --model-id LFM3-1.7B-QAD-Q4_0 --only timer,ms-battery-then-timer
scripts/run-evals.py --target device --model-id LFM3-1.7B-QAD-Q4_0 --dry-run   # validate and print the plan
```

The run is keyed by (task id, repeat). A rerun with the same output file skips every run already recorded; `--fresh`
discards the file first. Each task's setup is applied before it and undone after it, even when the task fails. A
run that cannot read the phone's mode after five tries (the app must be running) starts nothing and changes nothing.

Approvals: during a run the runner allows the tools in `APPROVE_ON_DEVICE` and denies every other tool that asks,
so a run can never place a call or send a message. Each approval is recorded.

## What is reported

Each result file has, per run: the verdict and its reasons, the calls, the answer, the approvals, steps, token
counts, `prefill_ms`, `prefill_tok_s`, `decode_tok_s`, `first_token_ms` and `total_ms`. The summary has the pass,
fail, unverified and wrong-language counts, the same counts by category, the approvals, the empty answers
(no text in the reply), and the median of each timing.

Prefill is always reported beside decode, never decode alone. The run ends with one line:

```
median total <ms> ms, first token <ms> ms, prefill <tok/s> tok/s, decode <tok/s> tok/s; approvals <n>, empty answers <n>
```

## Not yet run

No run on a phone or an emulator is recorded in `docs/scores/results/`, so no score is published. The fault tasks,
the contact-not-found task, the no-tool language tasks and the alarm and do-not-disturb checks have not run
end to end on a device. Until a run is recorded, no figure from this suite is a claim about the app.

`docs/measurements.md` is the authoritative list of what has and has not been measured.

## Related

- [`build.md`](build.md): building the debug app, and the no-test-suite and no-CI policy
- [`measurements.md`](measurements.md): what has been measured, and where
- [`memory-model.md`](memory-model.md): the RAM procedure
- [`scores/index.md`](scores/index.md): the generated score tables
