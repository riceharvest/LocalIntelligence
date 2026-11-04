# Permission and foreground-service declarations (drafts)

Draft text for the Play Console declaration forms. Nothing is submitted. The rules are the
pages named in docs/roadmap.md, "Before public release (v1.0)":
- SMS and call log: https://support.google.com/googleplay/android-developer/answer/10208820
- Foreground services: https://support.google.com/googleplay/android-developer/answer/13392821
- Full list of permissions to check: https://support.google.com/googleplay/android-developer/answer/9888170

Facts are from the manifests (app/src/main/AndroidManifest.xml, android/src/main/AndroidManifest.xml)
and the code named next to each. Anything not confirmed on a device or on the Play page is marked
UNCONFIRMED.

---

## 1. SMS and call log declaration

### Permissions and where each is used

| Permission | Tool | Use |
|---|---|---|
| `READ_SMS` | `sms.search` | Search the texts on the phone when the person asks. |
| `SEND_SMS` | `sms.send` | Send one text after the person confirms the recipient and text. |
| `READ_CALL_LOG` | `calls.recent` | List recent calls when the person asks. |

Every call first checks that LocalIntelligence holds the default digital assistant role
(`DefaultAssistant.refusalUnlessHeld`, android/.../tools/messages/MessageTools.kt), then the runtime
permission. Without the role nothing is read or sent.

The Permissions screen lists the three rows in every build that declares them. Without the role,
a row reads "Off", says "Only the default assistant app can use this", and offers only "Set as
default assistant" (`statusOf`, `NEEDS_ASSISTANT_ROLE`, app/.../ui/PermissionScreen.kt). The
Allow button appears only once the role is held (`PermissionStatus.canAsk`).

**Where the tools run (read from code, not yet run):** the role check is not the only gate.
Scheduled runs and Telegram-started runs (`ExecutionService`, `replyChat` or `scheduledTaskId`)
run with `background = true` and `declineApprovals = true`. Those runs keep every tool except
screen control (`AppContainer.kt`, `pluginTools`). `sms.search` and `calls.recent` are
`READ_ONLY`, and `RiskPolicy` asks for confirmation only for guarded tools, so both run without
asking. `sms.send` needs confirmation, and an unattended run declines it (`AgentViewModel.kt`,
the `declineApprovals` loop). Nothing in the code passes `requestedByMessage = false`, so the
"not requested by the message" guard is not wired.

### Declaration text (for the form)

**DO NOT SUBMIT this text as it stands.** It describes the behaviour the owner wants, and the
code does not yet do it. The owner must first choose one of these:

- (a) Keep the tools in scheduled and Telegram runs. Then the text below stands, with the last two
  sentences as written.
- (b) Leave `sms.search`, `sms.send` and `calls.recent` out of unattended runs. That is a code change
  in `AppContainer.kt` (`pluginTools`, where screen control is already filtered out for `background`).
  Then delete the "scheduled" and "Telegram" sentences below.

> LocalIntelligence is an on-device AI assistant that a person can choose as the phone's default
> digital assistant app. Only while it holds that role, it can search the texts on the phone, list
> recent calls, or send one text message. Sending always asks the person to confirm the recipient
> and the text first, and an unattended run cannot send. Texts and call entries are shown to the
> person. They leave the phone in these cases only: a remote model provider the person turned on
> receives them as part of a request; or a private Telegram chat the person linked receives the
> assistant's reply to a request from that chat. The developer does not receive texts or call
> history. Scheduled tasks that the person has set up can also search texts and read the call log
> without asking each time, and their answers are kept on the phone unless a remote model or a linked
> Telegram chat receives them.

### Steps for the reviewers to set the default assistant

1. Install the release build from the closed test track.
2. Open LocalIntelligence. Complete first run in Simple mode (no key, no account).
3. Ask the assistant a text question, or open the Permissions screen. The app opens the system
   request to make LocalIntelligence the default assistant (RoleManager, `ROLE_ASSISTANT`, through
   `DefaultAssistant.requestIntent`) when the phone offers it. On a stock Android phone the manual
   path is Settings, then Apps, then Default apps, then Digital assistant app. **UNCONFIRMED** on the
   Pixel 10 Pro and on the Play review phone.
4. Choose LocalIntelligence. Return to the app.
5. Open the Permissions screen. Before step 3 the rows "Texts, reading", "Texts, sending" and
   "Call history" read "Off" with a "Set as default assistant" button. After step 3 they show "Allow".
6. Grant the three rows. Ask: "Did anyone text me today?" (texts search). Ask: "Who called me
   recently?" (call log). Ask: "Text 0600000000 saying hello" (send). Check that the app asks for
   confirmation before it sends.
7. To test the refusal path, choose another default assistant. The tools then refuse and say
   the default assistant role is needed. **UNCONFIRMED** on device; the code path is
   `DefaultAssistant.refusalUnlessHeld`.

If the form is refused: build the Play variant without these permissions with
`./gradlew :app:bundlePlayRelease -PplayNoSms=true` (docs/build.md, section 8). That build has no
texts or call log, and the tools say so.

---

## 2. Foreground service declarations (Android 14+)

Foreground service types in the merged manifest, one row per service (types from each service's
`startForeground` call and the manifest):

| Service (class) | Type in manifest | What it does | Impact if interrupted | Video needed |
|---|---|---|---|---|
| `ExecutionService` | `dataSync` | Runs one agent task (started from a chat message or a scheduled task) while the person is in another app. Stops itself at the end of the run. | The run stops early. Android 15 caps `dataSync` at six hours in 24 hours. | Yes. One video covers the type (script 3). |
| `DownloadService` | `dataSync` | Keeps a model download from the Hugging Face hub running in the background. Shows progress and a Cancel button. | The download stops and the person has to restart it. Resume behaviour is in docs/hf-hub-download.md, UNCONFIRMED on device. | Covered by script 3. |
| `TelegramService` | `dataSync` | Long-polls Telegram while the Telegram plugin is on, so messages from a linked private chat reach the assistant with the app in the background. | Messages from linked chats are not read until the service starts again. | Covered by script 3 where the person starts it. |
| `ChatGptSignInService` | `dataSync` and `shortService` (the manifest lists both) | Keeps the process alive while the browser waits for ChatGPT sign-in. Uses `shortService` on API 35 and up, `dataSync` below. | The sign-in can fail and has to be started again. Android can stop a short service after about three minutes; `onTimeout` removes the notification. | Covered by script 3 where the person starts it. |
| `WakeWordService` | `microphone` | Listens for the wake phrase while the wake word setting is on. Audio is read in memory and discarded (WakeWordService.kt KDoc). | "Hey ..." stops working until the service starts again. | **Yes**, script 1. |

**Check before submitting:**
- `ChatGptSignInService` lists `shortService` in the manifest. Check whether the `shortService`
  type needs any type-specific permission. **UNCONFIRMED** here; neither the manifest nor the
  Play page in docs/roadmap.md was read for it. Check on a device with API 35 and on the Play
  policy page before deciding anything.
- The manifest declares `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC` and
  `FOREGROUND_SERVICE_MICROPHONE`. It does not declare a short-service permission.
- Each service's type must match its `startForeground` call. The manifest comments say so; a
  mismatch throws on API 34 and up. Confirm on the device pass.

### Short purpose text for each declaration

- dataSync (ExecutionService, DownloadService, TelegramService, ChatGptSignInService):
  "Runs a task the person started, or a model download, or a linked Telegram chat, and stops when
  that work ends. It keeps the work running while the app is in the background, and shows a
  notification with a stop control."
- microphone (WakeWordService): "Listens for the wake phrase the person set, only while the
  person has switched the wake word on. The notification shows while it runs. Audio is not
  stored or sent."

---

## 3. Video scripts

Each script is one take on a phone running the release build. Play rejects automatic or
background behaviour as the video (docs/roadmap.md), so the person starts each step on screen.

### Script 1: wake word (microphone service)

1. Open LocalIntelligence on the home screen.
2. Open Settings, then the Voice section (`SettingSection.VOICE`), then switch on Wake word.
   Show the switch on. **UNCONFIRMED:** the exact on-screen path; check on the device.
3. Pull down the notification shade. Show the LocalIntelligence notification that says
   the wake word is listening. **UNCONFIRMED:** the notification text. Film what it says.
4. Say the wake phrase ("Hey" then the assistant name). Show the assistant overlay opening.
5. Switch the wake word off in Settings. Show that the notification goes.

### Script 2: assistant role (texts and call log)

1. Open the phone's default-apps settings (see the reviewer steps above). Choose LocalIntelligence
   as the digital assistant app.
2. Open LocalIntelligence, then Permissions. Show the Texts and Call history rows.
3. Grant the rows. Ask "Did anyone text me today?" Show the answer.
4. Ask "Text 0600000000 saying hello." Show the confirmation before it sends. Cancel it.
5. Cancel the send. Show that nothing was sent.

### Script 3: dataSync (chat task or model download)

1. Start a model download from the Hub, or send a chat task that takes a few seconds.
2. Switch to another app. Show the LocalIntelligence notification with the stop control.
3. Return to the app. Show the task or download finished.

---

## 4. Content of the form, for the owner

- The app does not sell or share data beyond what docs/play/data-safety.md lists.
- The app asks for these permissions only when a tool needs them (Android runtime prompts, or
  Settings switches for special access).
