# Device pass checklist (owner, later)

For the Pixel 10 Pro. **Not run yet.** The owner has stopped device testing for now, so this file
is the checklist for the later pass. Nothing here has been observed.

Rules that apply (docs/roadmap.md, "Before public release (v1.0)", "Device pass"):
- Use **release** builds (minified), not debug. R8 breaking JNI or reflection shows only there.
- Run both flavors: `play` and `full`.
- Verify the 16 KB page alignment on each release output before upload
  (`.github/scripts/check-16k-alignment.sh`, run by hand as the release script does).

## 0. Build

- [ ] `scripts/release.sh` (needs the signing properties outside the repo; docs/build.md section 7).
      It builds `dist/LocalIntelligence-<version>.aab` (play) and the full APK, and runs the alignment check.
- [ ] Note the SHA-256 printed for the full APK. Keep it with the release notes.
- [ ] Optional fallback build: `./gradlew :app:bundlePlayRelease -PplayNoSms=true` (docs/build.md section 8).
      Test it separately, on its own install.

## 1. Clean install, both flavors

- [ ] Install the play release (package `local.intelligence`) on a phone with no app data.
- [ ] Install the full release (package `local.intelligence.full`) beside it. Both should install.
- [ ] Check that chats, models and settings do not carry between the two (ADR 0002).

## 2. Simple-mode first run (roadmap "Should")

- [ ] Tap-by-tap from the install tap to the first answer, with no key and no account.
- [ ] Record two numbers in `docs/measurements.md`: install to first tap, and first tap to first
      answer. Say whether a model download is inside the second number.
- [ ] Simple mode shows only the recommended models in the play build; no Hub search there.
- [ ] No Extras row in Settings in the play build.

## 3. Vertical slice (docs/roadmap.md, "Now")

- [ ] "How much battery do I have?" calls `device.battery` and answers with the real reading.
      Record the answer, the prefill and decode tokens per second from the app's run metrics,
      and the model name (docs/measurements.md).

## 4. Model test plan (docs/roadmap.md, "Model test plan")

- [ ] Run the fixed task list on each listed model, same phone, same tasks.
- [ ] Record: task pass or fail, prefill and decode tokens per second from the app's metrics, peak
      RAM from `dumpsys meminfo` (procedure in docs/memory-model.md), and multimodal features.

## 5. Three Dutch tasks (roadmap "Should", Dutch UI)

Run each in Dutch, in a fresh chat, on the first model that passes the English slice. Record the
answer as given.

- [ ] "Hoeveel batterij heb ik nog?" (expects `device.battery`; answer in Dutch with the reading)
- [ ] "Zet een timer van tien minuten." (expects `timer.set`; the clock app must open or confirm)
- [ ] "Wat is het weer in Amsterdam?" (expects `weather.get`; the web-style call asks first, so
      confirm it, and check the answer names Amsterdam)

## 6. Texts, calls and the default assistant (play build only)

Use the steps in docs/play/declarations.md, section 1 and script 2.

- [ ] Default-assistant role set; the three Permissions rows appear.
- [ ] "Did anyone text me today?" answers from the texts.
- [ ] "Who called me recently?" answers from the call log.
- [ ] A text send asks for confirmation before it sends. Cancel one; check nothing was sent.
- [ ] Role removed: the tools refuse and say the role is needed.
- [ ] Build with `-PplayNoSms=true`: the three tools say the build lacks them, and the three
      Permissions rows are gone.

## 7. Foreground services and the wake word

- [ ] Wake word on: notification shows; "Hey ..." opens the assistant; wake word off: notification gone.
      (Script 1.)
- [ ] A chat task that runs while the app is in the background: notification shows; task finishes.
- [ ] A model download in the background: progress notification; Cancel stops it.
- [ ] ChatGPT sign-in on API 35 and up, with the browser in front: the sign-in completes. Note the
      service type that ran. **Check** the `shortService` question in docs/play/declarations.md.

## 8. Privacy statements that the code may contradict (docs/play/data-safety.md, OPEN)

- [ ] Weather with no place named: does the request carry the phone's coordinates? The privacy page
      says it carries "the place name or search words" only. Check the request on the device.
- [ ] Dictation into the composer when the phone has no on-device recognizer: which recognizer runs,
      and does the audio leave the phone? The privacy page says it does not, unless a remote model
      takes voice.

## 9. Report and reply actions

- [ ] Report one reply with the reply text ticked, and one with it unticked. The Google Form receives
      the reason, note, model name, app version, build name, and the reply text or "(not included)".
- [ ] A report with no network returns "Failed", not a crash.

## 10. Stability

- [ ] Run each tool family once from the chat on the release build (files, device, phone, messages,
      apps, clipboard, alarms, calendar, contacts, notifications, web, info, location, drafts,
      history). Note any crash or a native error.
- [ ] A long chat with a model load and unload. Note any ANR.

## Record

Every result above goes into `docs/measurements.md` with the build name, the phone, and the date.
Anything not run is written as not run. Nothing here counts as observed until it is written there.
