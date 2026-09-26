# Background execution

What this app does when it is not in front of you, what each part needs from
Android, and what you will and will not see.

> **None of this has been verified on a device.** The Android emulator on the
> development host SIGSEGVs during boot (it reaches `bootanim` and then exits
> 139), so the emulator is out of use. Every statement below is read from the
> source and from the manifest, not observed. Where a behaviour is a platform
> guarantee rather than something this code implements, it says so. Where a
> behaviour is implemented here, the file and line are given. There are no
> measured figures in this document — no RAM numbers, no latencies, no
> tokens-per-second — because none have been measured.

---

## 1. The one-sentence version

The app runs an agent in a **foreground service** (a visible, ongoing
notification) and starts that service from an **exact alarm**; it does **not**
have a persistent background daemon, and it does not read your notifications
unless you separately grant notification access for the three `notifications.*`
tools.

---

## 2. The two capabilities, and why they are not one

This is the distinction the old UI got wrong, so it is first.

| | Notification access | Alarms and reminders |
|---|---|---|
| **Grants** | `notifications.list`, `.reply`, `.dismiss` | `alarm.create`; scheduled tasks |
| **Mechanism** | `NotificationListenerService` bound by the system after a human flips a switch | `SCHEDULE_EXACT_ALARM` special access, checked with `AlarmManager.canScheduleExactAlarms()` |
| **Runtime permission?** | **No.** There is no `requestPermissions()` call and no `shouldShowRequestPermissionRationale()` for it. The system binds the service or it does not. | On API 31+ it is a Settings toggle an OEM may revoke. Below API 31 `canScheduleExactAlarms()` always returns `true`, so it cannot be denied. |
| **Settings screen** | `ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS` (API 30+), else `ACTION_NOTIFICATION_LISTENER_SETTINGS` | `ACTION_REQUEST_SCHEDULE_EXACT_ALARM` |
| **Needed for foreground chat?** | **No** | **No** |

A third thing is deliberately kept out of both columns, because folding it in
is its own dishonesty: **`POST_NOTIFICATIONS`**. That is the app being allowed
to post *its own* notifications. It is what lets a scheduled task tell you it
ran. It is not needed for chat, and it is not needed for anything to be *read*.
`TaskReadiness` (`app/.../data/TaskReadiness.kt:68-76`) already surfaces it as
its own blocker with that wording.

Concretely: turning on notification access does **not** let the app run anything
by itself, and turning on alarms does **not** let it read anything. The old
chat-screen banner presented notification access as "background access", which
told a user with an on-device, private app that it wanted to read notifications
in order to do its job.

---

## 3. What a scheduled run actually does

The path, end to end:

1. **Arm.** `TaskAlarmScheduler.arm` (`app/.../execution/TaskAlarmScheduler.kt:98`)
   calls `AlarmManager.setExactAndAllowWhileIdle(RTC_WAKEUP, nextRunAtMillis, pi)`.
   The `PendingIntent` carries only the task id; the prompt is never copied into
   the Intent, so a stale alarm cannot carry an old prompt.
2. **Fire.** `ScheduledTaskFireReceiver.onReceive`
   (`app/.../execution/ScheduledTaskFireReceiver.kt:67`). A `BOOT_COMPLETED` or
   `MY_PACKAGE_REPLACED` delivery goes to re-arming instead.
3. **Guard.** A row that is missing or has `enabled == false` is **disarmed and
   not run** (`ScheduledTaskFireReceiver.kt:94-102`). A task paused between
   arming and firing does not run.
4. **Re-arm first, then run.** `advanceSchedule` writes the next occurrence and
   arms it *before* `startRun` hands over to the service
   (`ScheduledTaskFireReceiver.kt:108-109`). Reversed, a process death between
   the two would leave a recurring task with no future alarm and no visible
   symptom. Arming first means the worst case is a missed run on a live
   schedule, which the user can see.
5. **One model load.** `ExecutionService.startRun`
   (`app/.../ExecutionService.kt:246`) calls `container.ensureModelReady()` once
   per run, then `container.newController()`, then runs. Loading 2 GB inside a
   `BroadcastReceiver` is how you get a broadcast ANR, so the load is in the
   service where it belongs.
6. **Report.** `ScheduledRunReporter.settle`
   (`app/.../execution/ScheduledRunReporter.kt:124`) re-reads the row and writes
   `lastResult`. Five outcomes, each with a distinct prefix:
   `Answered: `, `Refused: `, `Cancelled: `, `Failed: `, `Did not run: `.

### The three "not scheduled" outcomes are different facts

- **No model / load refused** → `Did not run: <reason>`. The run never started;
  this is not a failure of the run.
- **`startForegroundService` threw** → `Did not run: the system refused to start
  a background run (...)` (`ScheduledTaskFireReceiver.kt:239-245`). No service
  ever existed, so nothing will overwrite this. It is terminal, not a
  placeholder.
- **Next occurrence could not be armed** → the sentence is prefixed
  `Next run not scheduled: ` and the reporter *carries it forward* onto the
  finished result (`ScheduledRunReporter.kt:228-233`), so an answer can never
  hide the fact that the schedule is now broken.

### A refusal is read out of the trace, and only when there is no answer

`AgentController.refuse` does not end a run — the justification goes back to the
model as an observation and the loop continues. There is therefore no terminal
"refused" state to read, and inventing one would mean editing `:core`. So
`Refused: ` is reported when a run finished **with no answer** and its trace
carries a `refused <tool>: <rule> — <justification>` entry
(`ScheduledRunReporter.kt:184`). If the model was refused and then answered
anyway, the answer is the result and the user sees the agent's own words.

---

## 4. Pause versus cancel — verified

`ScheduledTaskController` documents that pausing does not cancel a running task.
**The code does that.**

| | `pause` (`ScheduledTaskController.kt:113`) | `delete` (`ScheduledTaskController.kt:87`) |
|---|---|---|
| Cancels a run in flight | **No** — no call to `cancelIfRunning` anywhere in the file except line 88, which is inside `delete` | **Yes** — `ScheduledRunRegistry.cancelIfRunning(context, taskId)` is the *first* statement |
| Disarms the alarm | Yes, `TaskAlarmScheduler.disarm` | Yes |
| Removes the row | No — `upsert(task.copy(enabled = false))` | Yes — `ScheduledTaskStore.remove` |
| Order | disarm, then write the row | cancel, disarm, then remove the row |

`cancelIfRunning` is conditional on the id (`ScheduledRunRegistry.kt:76`), so
deleting a paused task cannot kill an unrelated chat.

**The user can tell the difference.** `ScheduleScreen` renders three different
status lines (`app/.../ui/trace/ScheduleScreen.kt:305-321`):

- `Running now.`
- `Paused. Not scheduled.` — `enabled == false`, recurring
- `Already ran. Not scheduled any more.` — `enabled == false`, one-shot
- `Next: <time>` — armed

The single button is `Pause` when enabled and `Resume` when not
(`ScheduleScreen.kt:361`); delete is a separate icon
(`ScheduleScreen.kt:297-299`) labelled "Delete this task".

One consequence worth stating plainly, because it is the honest version of the
documented rationale: **a run already in flight when you press Pause keeps going
to its own terminal state.** You asked to stop the *schedule*, not to interrupt
work already under way. That run's outcome is written onto the row anyway, so a
task paused mid-run comes back saying `Paused. Not scheduled.` **and** what the
run it just finished said. Both are true and both are needed. If you want the
run itself stopped, delete the task.

If you want a running task stopped, `delete` is the button — it is the only
operation that cancels.

---

## 5. The reboot path

A reboot clears every `AlarmManager` `PendingIntent` the app registered. The
task rows are on disk, so without re-arming, a schedule would silently stop
existing while the UI went on listing it as scheduled.

`ScheduledTaskFireReceiver` (`ScheduledTaskFireReceiver.kt:65-78`) listens for
both `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED` and calls `rearmAll`:

- Returns **early and visibly** if `canScheduleExactAlarms` is false
  (`ScheduledTaskFireReceiver.kt:262-268`). The rows stay; the schedule screen
  reads the same permission and says so.
- Rolls a task whose `nextRunAtMillis` has passed forward to
  `TaskCadence.nextAfter(cadence, now)` rather than firing it late
  (`:273-278`). A phone that was off for three days does not wake up and run
  nine overdue occurrences.
- Skips `!enabled` tasks (`:272`), so a paused task is not re-armed by a reboot.
- A `SecurityException` or `IllegalStateException` on one task does not stop the
  others (`:282-286`).

`:android`'s `AlarmBootReceiver` handles the same two broadcasts for the
*clock-alarm* registry. The two registries are separate stores in separate
modules and `AlarmSpec` has no field for a task prompt, so it cannot re-arm
these. Collapsing the two into one receiver is a one-line follow-up for whoever
owns `:android`; it is called out in the PR description rather than smuggled in.

Manifest, `app/src/main/AndroidManifest.xml:156-164`: not exported, narrow
filter. `BOOT_COMPLETED` is a protected broadcast, so only the system can send
it and the platform skips the exported check for protected broadcasts.

---

## 6. What you see

### While a run is in flight

A foreground service notification, `FOREGROUND_SERVICE_TYPE_DATA_SYNC`
(`ExecutionService.kt:296-320`). It is ongoing and low priority. The service is
not exported (`AndroidManifest.xml:101-104`); nothing outside the app can start
it.

### After it finishes

The `lastResult` on the task row, in the Scheduled tasks screen, capped at 3
lines with a live region so a screen-reader user learns the outcome without
navigating to it (`ScheduleScreen.kt:328-348`). The stored string is itself
capped at 400 characters on a word boundary with an explicit ellipsis
(`ScheduledRunReporter.kt:245-252`), so a truncated answer cannot be mistaken
for a complete one.

### Before you schedule anything

`TaskReadiness.blockers` lists, worst first and each with a button that opens
the screen where it is fixed: no model, no `POST_NOTIFICATIONS`, battery saver
on, no exact-alarm access (`TaskReadiness.kt:55-97`). The Create button is
disabled rather than hidden, with the reason listed above it
(`ScheduleScreen.kt:219-228`).

### About consent

The consent surface is `BackgroundAccessActivity`. It is reached from inside
the app, and from a `PendingIntent`; it is not exported and has no LAUNCHER
category (`AndroidManifest.xml:176-180`).

The rules, as implemented in `BackgroundConsent`:

1. **Nothing is asked for on a recurring basis until the user opts in.** There
   is a `background_interest` flag, default `false`, and the only writer is an
   explicit tap on "Offer these when I need them". Until that tap, no capability
   is ever offered by the app on its own.
2. **A capability is offered only if** it is not granted, has not been
   dismissed, and interest has been recorded. So a user with no scheduled task
   and no interest in background work is never asked.
3. **Any deliberate act on a row is remembered.** "Not now" records a
   dismissal for that one capability, and so does "Turn on" — because a user who
   taps it, changes their mind on the Settings screen and comes back has already
   given their answer, just in a different process. The dismissal is stored per
   capability and survives a grant and a later revoke: it is a statement about
   being *asked*, not about the current state of a switch. Only "Ask me again"
   clears it. Refusing notifications does not silence alarms — a refusal of one
   row is not a statement about the others.
4. **Declining costs nothing in the foreground.** Both rows state their
   consequence inline, a "What is switched off" panel spells out the cost in
   full sentences rendered from the same strings the rows use, and the screen
   states plainly that chat, model import and every unrelated tool work with
   both switches off.
5. **Revocation is offered, not faked.** An app cannot revoke its own
   `NotificationListenerService` binding or its own exact-alarm grant. The
   "Turn off in Settings" button opens the same system screen as "Turn on" and
   says why it has to be done there — rather than a button that appears to work.

### What the old screen did wrong

`NotificationAccessBanner` was rendered unconditionally on the chat route
(`MainActivity.kt:125`) and its only condition was `if (granted) return`
(`MainActivity.kt:380`). So:

- It showed to **every** user, on **every** launch, for the entire time the grant
  was absent — which is the default state of a fresh install.
- There was **no dismissal** and **no memory** of one. The only way to silence it
  was to grant a permission the user had not asked for.
- It conflated notification access with "background", implying the app needed it
  to run at all.

The banner lives in `MainActivity.kt`, which this change does not own. The exact
replacement is in the PR description. `BackgroundConsent.shouldAsk(...)` is the
predicate that replaces `if (granted) return`.

---

## 7. What is declared in the manifest

All of it, from `AndroidManifest.xml:5-27`. Nothing is requested at runtime
except where noted.

| Permission | Used by | Runtime prompt? |
|---|---|---|
| `INTERNET` | `web.fetch` only. Model inference is on-device. | Install-time |
| `SCHEDULE_EXACT_ALARM` | `alarm.create`, scheduled tasks | Special access on API 31+ |
| `RECEIVE_BOOT_COMPLETED` | `AlarmBootReceiver`, `ScheduledTaskFireReceiver` | Install-time |
| `POST_NOTIFICATIONS` | The foreground-service notification and the scheduled-task result | Runtime, API 33+ |
| `FOREGROUND_SERVICE` + `_DATA_SYNC` | `ExecutionService` | Install-time |
| `READ_CONTACTS` / `READ_CALENDAR` / `WRITE_CALENDAR` | The matching tools | Runtime, on first use |
| `READ_MEDIA_IMAGES` / `_VIDEO` / `_AUDIO` | Media tools | Runtime, on first use |
| `VIBRATE` | `alarm.create` | Install-time, not revocable |

There is **no** `BIND_NOTIFICATION_LISTENER_SERVICE` in this table because it is
not a permission you request: it is the `android:permission` attribute on the
listener service declaration (`AndroidManifest.xml:189`), which is how the
platform restricts who may bind it.

---

## 8. What could not be verified without a device

The emulator on this host SIGSEGVs at boot (exit 139 after `bootanim`). The
following are therefore **read from code, not observed**:

- That `BOOT_COMPLETED` is actually delivered to both receivers and that
  `rearmAll` re-arms successfully on a real reboot.
- That an exact alarm fires at the intended time, and that
  `setExactAndAllowWhileIdle` survives Doze on a specific OEM build.
- That a scheduled run survives the screen being off and the process being
  killed. The foreground service is supposed to; nothing here observed it.
- That granting notification access in Settings binds the listener within the
  window the tools tolerate, and that returning to `BackgroundAccessActivity`
  re-reads the grant correctly.
- Whether any OEM build of Android 12+ has revoked or hidden the exact-alarm
  toggle in a way `canScheduleExactAlarms()` does not report.
- Battery-saver behaviour. `TaskReadiness` reports that the restriction is
  *on*; it does not claim a task will or will not be dropped, because that is
  not knowable from an app.
- All latency, memory and throughput characteristics. **No such numbers appear
  in this document because none have been measured.**

What *was* verified: the code compiles (`:core:assemble :android:assemble
:app:assembleDebug`), and the pause-versus-cancel behaviour is a direct reading
of the two functions in `ScheduledTaskController` with the line numbers given in
§4.
