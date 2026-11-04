# Content rating and target audience (drafts)

No Play page for the content-rating questionnaire is named in docs/roadmap.md. The questionnaire
itself was **not** read for this draft, so every question name below is UNCONFIRMED. The answers
come from the code. The owner answers in Play Console.

## Content rating: facts from the code

| Topic | What the app does | Evidence |
|---|---|---|
| AI-generated text | The chat model writes the replies. Its output is not filtered by a content classifier in the app. | No moderation code in core/ or android/; the Play build's model policy is the only restriction below. |
| Model choice in the Play build | Simple mode offers recommended models only. Technical mode lets the person download any Hugging Face GGUF, including uncensored ones. | roadmap "Generative AI policy" (owner decision, 2026-11-04); `hubRestricted()` in app/.../AppContainer.kt. |
| Reporting AI replies | Each reply has "Report this reply", which posts to the developer's form. | roadmap "In-app report for AI replies"; android/.../report/ReplyReportSender.kt. |
| User-to-user communication | None inside the app. The only chat partners are the model and, if the person links it, their own private Telegram chat. Groups are never offered (README). Email and SMS are sent by the person to others, after confirmation. | README; TelegramService.kt. |
| Sharing user content publicly | None. There is no feed, no profile, and no public posting. | No such screens or endpoints in the code. |
| Violence, sex, drugs, gambling | No content of its own. Content comes from the model the person chose. | No such features. |
| Location | Coarse location is read on the phone when a tool asks. It is not shared with other users. | Manifest `ACCESS_COARSE_LOCATION`. |
| Purchases | None in the build as shipped. The Extras subscription is hidden (`EXTRAS_BACKEND_URL` empty). | docs/data-safety.md, "Extras and Billing". |
| Ads | None. | docs/roadmap.md "ads: none". |

**OPEN for the owner:** the AI-generated-content and user-interaction answers depend on which
model the person downloads in Technical mode. The owner should answer as "AI-generated content:
yes" and "users can interact: no, except with the person's own linked chats", after checking the
form wording.

## Target audience

- docs/privacy.html: "The app is not directed at children under 13."
- docs/roadmap.md "Store listing": target audience "not directed at children".
- Age band to choose in Play Console: **OPEN** (owner decision). This draft does not pick one.
  The app has no child-specific content or feature.
