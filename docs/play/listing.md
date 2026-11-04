# Play store listing (drafts)

Draft text only. Nothing is submitted. Source for the rules: the store-listing page
https://support.google.com/googleplay/android-developer/answer/9866151 (named in
docs/roadmap.md, "Before public release (v1.0)", Store listing).

Facts used below come from the code and the privacy page. Anything else is marked UNCONFIRMED.

App name: **LocalIntelligence** (both languages).

---

## English

**Short description** (80 characters max; 76 characters)

> Private AI assistant that runs its models on your phone. No account, no ads.

**Full description**

LocalIntelligence is an AI assistant for Android that runs its language model on your phone.

Pick a model you already have, or download a recommended one. In Simple mode the app offers
the recommended models only. Technical mode adds the rest of the Hugging Face catalogue and
your own keys for remote services.

What you can ask it to do, when you ask:
- Answer questions and work through multi-step tasks in a chat.
- Set alarms and timers, and look at or add calendar events.
- Look up contacts, open apps, read a file you pick, or check the battery.
- Read the notifications you allow it to see, and reply to them.
- Read your texts and recent calls, and send a text, only if you make LocalIntelligence your
  default digital assistant app. Sending a text always asks you first.
- Dictate into the chat, send a photo, or record a voice clip, if the model accepts it.
- Turn on a wake word in Settings, so it opens hands-free.

Things that leave your phone are off until you set them up, and each network step asks first:
web search and page reading, weather, Wikipedia and place lookup, and remote model providers
where you enter your own key. The privacy policy lists each one and what it receives.

Privacy: no account, no analytics, no crash reporting, no ads. Your chats and what the
assistant remembers stay on the phone, and Android backup is turned off for the app. Replies
can be reported to the developer from the reply itself; you choose the reason, can add a note,
and can leave out the reply text.

Privacy policy: https://riceharvest.github.io/LocalIntelligence/privacy.html

**Category:** Productivity (proposed by this draft; UNCONFIRMED, the owner chooses in Play Console)

**Contact details**
- Website: none
- Contact method: GitHub issues, https://github.com/riceharvest/LocalIntelligence/issues
- Email: OPEN. Play Console asks for an email address on the listing. This draft does not write
  one. Use a project address the owner chooses, never a personal address.

---

## Nederlands

**Korte beschrijving** (maximaal 80 tekens; 67 tekens)

> Privé-AI-assistent op je telefoon. Geen account, geen advertenties.

**Volledige beschrijving**

LocalIntelligence is een AI-assistent voor Android die het taalmodel op je telefoon draait.

Kies een model dat je al hebt, of download een aanbevolen model. In de eenvoudige modus biedt
de app alleen de aanbevolen modellen aan. In de technische modus zie je de rest van de
Hugging Face-catalogus, en kun je je eigen sleutels voor externe diensten invullen.

Wat je de assistent kunt vragen, wanneer je dat vraagt:
- Vragen beantwoorden en stappen uitwerken in een gesprek.
- Wekkers en timers zetten, en agenda-afspraken bekijken of toevoegen.
- Contacten opzoeken, apps openen, een bestand lezen dat je kiest, of de batterij controleren.
- Meldingen lezen die je de app laat zien, en erop reageren.
- Je sms'jes en recente gesprekken lezen, en een sms'je sturen, alleen als je LocalIntelligence
  instelt als standaard digitale assistent. Een sms'je versturen vraagt altijd eerst om
  bevestiging.
- Dicteren in het gesprek, een foto sturen, of een spraakclip opnemen, als het model dat aankan.
- Een wekwoord aanzetten in de instellingen, zodat de assistent handsfree opent.

Dingen die je telefoon verlaten staan uit tot je ze instelt, en bij elke netwerkstap wordt eerst
gevraagd: websearch en het lezen van webpagina's, het weer, Wikipedia- en plaatsopzoekingen, en
externe modelaanbieders waarvoor je zelf een sleutel invult. Het privacybeleid zegt bij elk van
die diensten wat er naartoe gaat.

Privacy: geen account, geen analyse, geen crashrapportage, geen advertenties. Je gesprekken en
wat de assistent onthoudt blijven op de telefoon, en Android-back-up staat uit voor de app.
Antwoorden kun je vanuit de app melden bij de ontwikkelaar; je kiest de reden, kunt een opmerking
toevoegen, en kunt de tekst van het antwoord weglaten.

Privacybeleid: https://riceharvest.github.io/LocalIntelligence/privacy.html

**Categorie:** Productiviteit (voorstel van dit concept; NIET BEVESTIGD, de eigenaar kiest dit in
de Play Console)

**Contactgegevens**
- Website: geen
- Contactmethode: GitHub-issues, https://github.com/riceharvest/LocalIntelligence/issues
- E-mail: OPEN. De Play Console vraagt om een e-mailadres bij de vermelding. Dit concept vult er
  geen in. Gebruik een projectadres dat de eigenaar kiest, nooit een persoonlijk adres.

---

## Checks

Short descriptions were counted with Python: 76 characters (English) and 67 characters (Dutch).

Claims kept out on purpose: speed, memory use, answer quality, "works offline", and any
comparison with other apps. None of them has been measured on a device (docs/roadmap.md,
docs/measurements.md).
