# Google Play AI policy: what it says and what it means for LocalIntelligence

Read 2026-11-04. Sources are the Play policy pages themselves. Quotes are short phrases only.

Source: Generative AI apps policy, https://support.google.com/googleplay/android-developer/answer/14094294

## What the page says

- **Scope.** The policy covers apps where AI generates content from text, voice, or image prompts. It names three kinds: text-to-text chatbot apps where the chatbot is "a central feature", image-generation apps, and apps that create AI voice or video recordings of real people.
- **Out of scope.** Apps that only host AI-generated content without generating it fall under the User Generated Content policy. Apps that summarize non-AI content when summarizing is their only feature are out of scope. So are productivity apps that use AI to improve an existing feature, such as AI-suggested email drafts.
- **Requirements.** Developers must make sure generative AI apps do not produce offensive content. That covers content prohibited under the Inappropriate Content policy, content that may exploit or abuse children, and content that deceives users or enables dishonest behavior. Generative AI apps must also comply with all other Developer Policy Center policies.
- **Stated aim.** Keep AI-generated content safe for all users, and incorporate user feedback.
- **Prohibited examples listed.** Non-consensual sexual deepfakes; voice or video recordings of real people that facilitate scams; content encouraging dangerous activities or self-harm; demonstrably false election content; content that facilitates bullying or harassment; generative AI apps primarily intended to be sexually gratifying; AI-generated official documentation that enables dishonest behavior; malicious code creation.

## What the page does not say

- No declaration or disclosure requirement for AI apps.
- No 2026 change. The footer reads 2026, but the page lists no dated updates.
- No enforcement, penalty or appeal detail.
- No technical safeguard that developers must implement. The page links to a "Best Practices to Safeguard AI-Generated Content" article, which was not read for this note.

## What affects this app

LocalIntelligence is a text chatbot (on-device and remote models) whose chat is its central feature, so the Generative AI apps policy applies to it. Consequences, as far as the page states them:

1. The app must keep its own output within the listed prohibitions. The page does not say how; the app's current moderation (if any) was not audited here.
2. The app must comply with the other Developer Policy Center policies. Those were not read here.
3. The app is a tool that runs models the user chooses. Remote providers (OpenRouter, Groq, Google Gemini, and others, listed in the README) generate the text for those chats, so the same policy applies to their output.
4. Feedback: the page's stated aim is that developers incorporate user feedback. The app has no reporting path for model output that was checked against this page.

## Open items (unconfirmed)

- The "Best Practices to Safeguard AI-Generated Content" article, and the "related" policies it links, were not read.
- Whether a Play Console declaration form exists for AI apps is not stated on this page. Check the Console when submitting.
- Whether the app needs an in-app report action is not stated; this note records the gap, not a requirement.
