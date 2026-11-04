# Data safety form (draft)

Draft answers for the Play Console Data safety form, built from the code and from
docs/privacy.html. Nothing is submitted. Rules: https://support.google.com/googleplay/android-developer/answer/10787469
(named in docs/roadmap.md, "Data safety form"). Google's category names and the exemption
wording were NOT read on that page for this draft; every category name below is UNCONFIRMED
until the owner checks the form.

Scope: the **play** build as shipped (no `-PplayNoSms`). The **full** build is not on Play and
is not covered here.

Key: **Yes** = the code does it. **OPEN** = the code and the privacy page disagree, or the
answer needs an owner decision. **No** = the code does not do it.

## Account, ads, analytics

| Question | Answer | Evidence |
|---|---|---|
| Accounts | No account. | No login code; docs/privacy.html "no account". |
| Advertising ID / ads | No. | No ads SDK in `gradle/libs.versions.toml`. |
| Analytics or crash SDK | No. | None in `gradle/libs.versions.toml`; roadmap "Should" item on crashes is still open. |
| Backup | Off. | `android:allowBackup="false"` in app/src/main/AndroidManifest.xml. |

## Data the app collects or shares

"Collected" here means: leaves the phone to the developer or to a service the person turned on.
On-device reads are listed where they matter for the reviewer, not as collection.

| Data | Leaves the phone? | To whom, when | Optional? | Notes and evidence |
|---|---|---|---|---|
| Reply report: reason, note, model name, app version, build (play), reply text if ticked | **Yes** | Developer, through a Google Form, POST over HTTPS, only when the person taps Send on the report sheet | Yes | `android/.../report/ReplyReportSender.kt` posts to the form URL in `BuildConfig.REPORT_FORM_URL` (default set in app/build.gradle.kts). Payload: core/.../report/ReplyReport.kt `format()`. Nothing else from chats. Retention 12 months per privacy page. |
| Chat content (messages, tool results, attached images or audio) | **Yes, only with a remote model** | The provider the person picked (OpenRouter, Groq, Cerebras, DeepSeek, Together, Mistral, Google Gemini, ChatGPT sign-in, or a custom server) | Yes; off until a remote model is set | README feature table; docs/privacy.html. With a local model nothing leaves. |
| Web addresses and search words | **Yes, when a tool runs** | The site the model names (`web.fetch`), or DuckDuckGo by default, or Brave, Tavily, Exa, SearXNG the person chose (`web.search`) | Yes; each call asks first | README; `android/.../tools/web/`. Loopback and private addresses refused. |
| Weather and place lookups | **Yes** | Open-Meteo, Nominatim, Wikipedia: the place or search words. **OPEN:** when no place is given, `WeatherTool.kt` sends the phone's coordinates to Open-Meteo (`latitude=`/`longitude=` in the forecast URL, line ~183). docs/privacy.html says "the place name or search words" only. The privacy page must say coordinates too, or the code must change. Owner decision. | Yes; each call asks | `android/.../tools/info/WeatherTool.kt`. |
| Approximate location | On device by default. **OPEN** as above when weather runs without a place. | `location.get` returns the fix to the model, which can then send it to a remote model if one is on. | Yes; permission asked on use | `ACCESS_COARSE_LOCATION` in the manifest. |
| Contacts | Only through a remote model's conversation | `contacts.search` / `contacts.get` results appear in the chat. | Yes; permission asked on use | `READ_CONTACTS`. |
| Calendar | Only through a remote model's conversation | `calendar.search` / `calendar.create` | Yes; permission asked on use | `READ_CALENDAR`, `WRITE_CALENDAR`. |
| Texts (SMS) | **Yes, in one way.** To a remote model, if one is on, as part of a request the person started in the app. Not to the developer. Sending a text leaves the phone to the recipient through the carrier, after the person confirms. | `sms.search` is `READ_ONLY`, so it runs without a confirmation in a chat the person started. Scheduled and Telegram-started runs do not get it in the Play build (AppContainer.kt `pluginTools`). | Yes; default assistant role plus permission | `READ_SMS`, `SEND_SMS`. Code read, not run. Owner decision: see "Texts and call log: owner decision" below. |
| Call log | **Yes, in the same one way as texts.** Scheduled and Telegram-started runs do not get it in the Play build. | `calls.recent` is `READ_ONLY`. | Yes; default assistant role plus permission | `READ_CALL_LOG`. Same rule as texts. |
| Notifications | Only through a remote model's conversation | `notifications.list`, `.reply`, `.dismiss`; needs notification access | Yes | `LocalNotificationListenerService`. |
| Installed apps list | On device; a remote model sees it only if the tool result is sent. | `apps.list` | Yes | Package visibility only (`<queries>` in the manifest), no permission. |
| Files the person picks | Only through a remote model's conversation | `files.*` through the document picker | Yes | Play build: `READ_EXTERNAL_STORAGE` only up to API 32 (manifest). |
| Clipboard | On device; sent only if the tool result is in a remote conversation | `clipboard.read`, `clipboard.write` | Yes | No permission. |
| Microphone audio (voice clip for a model that reads audio) | No. Processed on the phone. | `VoiceClipRecorder.kt`; llama.cpp / LiteRT-LM on device. | Yes | `RECORD_AUDIO`. |
| Microphone audio (dictation into the composer) | **OPEN** | `LiveDictation.kt` uses the on-device recognizer when the phone has one, and otherwise the system `SpeechRecognizer`. The system recognizer may send audio to a speech service. docs/privacy.html says audio "is processed on the phone and is not stored or sent anywhere, unless you chose a remote model that takes voice". That sentence is not true for the fallback. Owner decision: fix the privacy page, or restrict dictation to on-device. Confirm on the Pixel which path runs. | Yes | |
| Wake word audio | No. Spotted in memory; not stored or sent (WakeWordService.kt KDoc). | `WakeWordService.kt` | Yes; off by default (`voice.wake_word` default false). | `RECORD_AUDIO`, `FOREGROUND_SERVICE_MICROPHONE`. |
| Screen-time totals (usage access) | On device | `device.screen_time` | Yes; special access the person switches on | `PACKAGE_USAGE_STATS`. |
| Telegram messages | **Yes**, to `api.telegram.org` and back | Messages from a linked private chat, and the replies. Groups are never offered (README). | Yes; bot token and linking | `TelegramService.kt`. |
| Email | **Yes**, to the person's own IMAP / SMTP server | `email.search`, `email.read`, `email.send` after confirmation | Yes; address and app password | README. |
| Home Assistant | **Yes**, to the person's own server | Service calls approved by the person; states come back | Yes | README. |
| MCP servers, cloud sandboxes, Browser Use | **Yes**, to servers the person enters | Requests the assistant makes | Yes; off until set up | README; the MCP plugin is off by default. |
| Model downloads | **Yes**, a file request to Hugging Face | Download file requests only | Yes; the person picks the model | docs/hf-hub-download.md. |
| Image or video generation (OpenRouter) | **Yes**, the prompt | openrouter.ai | Yes; OpenRouter plugin on, with a key | README. |
| Extras (paid subscription, Play Billing) | **No, in the build as shipped.** The Extras row is hidden and no billing call runs when `EXTRAS_BACKEND_URL` is empty. | `ExtrasBilling.enabled` is false; `checkAtStart()` runs only when enabled (AppContainer.kt, ExtrasBilling.kt). | Yes | See "Extras and Billing" below. |

## Texts and call log

Owner's decision (2026-11-04): in the Play build, scheduled and Telegram-started runs do not get
`sms.search`, `sms.send` or `calls.recent` (`AppContainer.kt`, `pluginTools`). So a linked Telegram
chat cannot receive texts or call history from the Play build. The full build keeps them; this form
covers the Play build.

## Extras and Billing (owner decision)

- With the default empty `EXTRAS_BACKEND_URL`, the Extras screen is not reachable and no Play
  Billing connection is made (`ExtrasBilling.enabled`, `MainActivity` `onOpenExtras`).
- The Billing library (`com.android.billingclient:billing-ktx`) is still in the Play APK.
- The Extras backend would receive the Play purchase token and a random install id
  (`ExtrasBackend.activate` / `refresh`, README). Neither is sent in this build.
- **OPEN:** whether to declare purchase history and an identifier now, or only when Extras is
  switched on. The roadmap asks the owner to decide this first (docs/roadmap.md, Data safety).
  This draft declares nothing for Extras.

## Security questions

| Question | Draft answer | Evidence and caveat |
|---|---|---|
| Data encrypted in transit? | **OPEN: not for every path.** Keys and remote calls use HTTPS. Plain HTTP is allowed to a server on the person's own network (a private IP or `.local` name) for the custom model endpoint and Home Assistant. | README "Keys and tokens"; docs/privacy.html "Plain HTTP is allowed only to a server on your own home network". The form may ask this as a yes/no. Answer with the exception stated, or tighten the code. |
| Keys stored securely? | Yes. Android Keystore. | docs/privacy.html; README. |
| Can users request data deletion? | Yes, by uninstalling or clearing storage, and for a report by GitHub issue. | docs/privacy.html "Keeping and deleting data". |

## Reviewer notes to paste if the form asks

- Reports are the only data the developer receives, and only when the person sends one.
- Other data stays on the phone, or goes to a service the person turned on and set up. Texts and
  call-log results are an exception to "stays on the phone" when a remote model receives them; see
  "Texts and call log" above.
- Sending texts always asks first (`sms.send` is never auto-accepted; DEFAULT_NEVER_AUTO_ACCEPT in core/.../policy/PolicyConfig.kt).
