# Extras Worker

The small backend behind the paid "Extras" option in LocalIntelligence Simple mode.

It does three things:

1. **Verifies a Google Play subscription** (`extras_monthly`) with the Play Developer API.
2. **Gives the customer their own capped provider keys**: an OpenRouter key (monthly USD cap) and
   an Exa key (budget and rate limit). The phone then calls OpenRouter and Exa directly.
   The Worker never carries chat or search traffic.
3. **Revokes the keys when the subscription ends** (cancelled and expired, refunded, paused,
   on hold). It re-enables them on renewal. Play tells it about these changes through
   real-time developer notifications.

The app is free. This Worker is only needed for Extras. The app's Extras row stays hidden
until `EXTRAS_BACKEND_URL` is set in the app build.

Nothing here is deployed yet. The owner does the steps below.

## Endpoints

| Method and path | Who calls it | What it does |
|---|---|---|
| `POST /v1/activate` | the app, after a Play purchase | body `{packageName, productId, purchaseToken, installId}`. Verifies the purchase, provisions keys, returns the customer's `extrasToken` (made once, shared by every phone on the subscription), returns `{expiresAt, extrasToken, openrouter: {key, model}, exa: {key}, services: {sandbox, browser, composio, voice, mail, telegram}}`. Idempotent. Limited to 10 calls per install id per hour. |
| `POST /v1/refresh` | the app, at start when expiry is near | body `{purchaseToken, installId}`. Returns the same keys, `extrasToken` and `services` map if still active, `402 not_active` otherwise. |
| `POST /v1/rtdn/<RTDN_PATH_SECRET>` | Google Pub/Sub push | Play real-time developer notification. Needs the path secret and a valid Google OIDC bearer token (`Authorization: Bearer`, checked against Google's signing keys for `PUBSUB_AUDIENCE` and `PUBSUB_SERVICE_ACCOUNT_EMAIL`), otherwise `401`. Re-reads the subscription from Google and disables or re-provisions keys to match. |
| `POST /v1/sandbox/{create,info,start,stop,exec,write,read}` | the app (`Bearer <extrasToken>`) | Managed cloud code sandbox (`@cloudflare/sandbox`). Returns `503 service_off` when unconfigured. |
| `POST /v1/browser/{start,status,result}` | the app (`Bearer <extrasToken>`) | Managed cloud browser task runner. Returns `503 service_off` when unconfigured. |
| `ALL /v1/mcp/composio` | the app (`Bearer <extrasToken>`) | MCP streamable HTTP proxy to Composio Tool Router. Returns `503 service_off` when unconfigured. |
| `POST /v1/voice/{stt-token,speak}` | the app (`Bearer <extrasToken>`) | Deepgram STT short-lived token grant and Aura-2 TTS synthesis. Returns `503 service_off` when unconfigured. |
| `POST /v1/mail/{address,list,read,send}` | the app (`Bearer <extrasToken>`) | Managed agent mailbox (inbound via Cloudflare Email Routing `email()` handler, outbound via Email Sending). Returns `503 service_off` when unconfigured. |
| `POST /v1/telegram/{link,updates,send}` | the app (`Bearer <extrasToken>`) | Shared Telegram bot link token, inbox polling and outbound replies. Returns `503 service_off` when unconfigured. |
| `POST /v1/telegram/webhook/<TELEGRAM_WEBHOOK_SECRET>` | Telegram Bot API webhook | Delivers inbound Telegram updates to D1. Authenticated by the secret path segment. Returns `503 service_off` when unconfigured. |

Error bodies are `{error, message}` with a fixed `error` code. Keys are returned only with
`Cache-Control: no-store`. Nothing logs keys, purchase tokens or provider response bodies.

## Secrets and variables the owner must set

Secrets are set with `wrangler secret put NAME` (it prompts; do not put them in files or chat).

| Name | Kind | Where to get it |
|---|---|---|
| `GOOGLE_SERVICE_ACCOUNT_JSON` | secret | In Google Cloud Console, create a service account in the project linked to the Play Console. Create a JSON key for it and paste the whole file. Then in the Play Console (Users and permissions) invite that service account email and give it permission to view and manage orders and subscriptions for the app. Check the exact permission label in the Play Console. |
| `OPENROUTER_PROVISIONING_KEY` | secret | https://openrouter.ai/settings/management-keys, "Create New Key". This is a Management API key and cannot make chat calls. **Set an expiration.** Expired keys return 401 and every activation fails, so rotate it before the date shown. Docs: https://openrouter.ai/docs/guides/overview/auth/provisioning-api-keys |
| `EXA_ADMIN_KEY` | secret, optional | Without it Extras gives no search key and the app keeps its free search. Ask support@exa.ai to enable Team Management for your team. Then copy the service key from the "Service keys" tab on the Exa API keys page (https://dashboard.exa.ai/api-keys). Docs: https://exa.ai/docs/reference/team-management/create-api-key |
| `RTDN_PATH_SECRET` | secret | Random string, for example `openssl rand -hex 32`. It becomes part of the Pub/Sub push URL. |
| `CREDENTIAL_ENCRYPTION_KEY` | secret | Base64 of 32 random bytes: `openssl rand -base64 32`. Encrypts the provider keys stored in D1. **Losing it means stored keys cannot be read back**, so back it up with your other secrets. |
| `COMPOSIO_API_KEY` | secret, optional | Composio project API key for `/v1/mcp/composio`. Without it `services.composio` is `false`. |
| `DEEPGRAM_API_KEY` | secret, optional | Deepgram API key with member/token-grant permissions for `/v1/voice/*`. Without it `services.voice` is `false`. |
| `TELEGRAM_BOT_TOKEN` | secret, optional | Bot token from `@BotFather` for the shared Extras Telegram bot. Without it `services.telegram` is `false`. |
| `TELEGRAM_WEBHOOK_SECRET` | secret, optional | Random string (for example `openssl rand -hex 32`) placed at the end of `/v1/telegram/webhook/<secret>`. Without it `services.telegram` is `false`. |

Optional Cloudflare bindings in `wrangler.toml` (commented out until each service job is enabled; a missing binding leaves that service `false` in `services`):

| Name | Service | Binding type |
|---|---|---|
| `Sandbox` | `sandbox` | Durable Object namespace (`Sandbox` class driving Containers with `@cloudflare/sandbox` 1.0.3, enabled by job W1). Requires Workers Paid for Containers. |
| `BROWSER` | `browser` | Cloudflare Browser Rendering (`[browser]`), paired with the `BROWSER_TASK` Durable Object (`BrowserTask`, Stagehand 2.5 + `@cloudflare/playwright`). Requires Workers Paid, `nodejs_compat`, and `[alias] playwright = "@cloudflare/playwright"`. |
| `SEND_EMAIL` | `mail` | Cloudflare Email Sending (`[[send_email]]`), paired with an Email Routing catch-all on `EMAIL_DOMAIN` pointing to this Worker's `email()` handler. |

### Code sandbox (`sandbox`, W1)

Managed Linux containers powered by `@cloudflare/sandbox` 1.0.3 and Cloudflare Containers (`durable_object` scheduling policy):
- Exported Durable Object class `Sandbox` from Worker entry point (`src/index.ts` -> `src/services/sandbox.ts`).
- Container image specified in `Dockerfile` (`node:24-trixie-slim` copying `/usr/local/bin/sandbox-shim` from `docker.io/cloudflare/sandbox:1.0.3`).
- Bound via `[[containers]]` (`scheduling_policy = "durable_object"`) and `[[durable_objects.bindings]]` with SQLite migrations in `wrangler.toml`.
- Endpoints: `POST /v1/sandbox/{create,info,start,stop,exec,write,read}` (files capped at 1 MB, base64 encoded).
- Metering: execution wall-clock minutes metered in `sandbox_minutes` against `SANDBOX_MINUTES_CAP`.

### Agent mailbox (`mail`, W5)

Managed email mailbox powered by Cloudflare Email Routing and Cloudflare Email Sending:
- Inbound: Worker default export's `email(message, env, ctx)` handler catches incoming emails on `EMAIL_DOMAIN`.
- Messages parsed with `postal-mime` 4.2.0 and stored into D1 `mail_messages` (capped at 256 KB; unknown recipients rejected).
- Outbound: `POST /v1/mail/send` sends via Cloudflare Email Sending (`[[send_email]]` binding `SEND_EMAIL`) from `<local>@EMAIL_DOMAIN`.
- Metering: 1 count per outbound send metered in `mail_sends` against `MAIL_SENDS_CAP`.
- Endpoints: `POST /v1/mail/{address,list,read,send}` (`list` returns newest 50 without bodies; `read` retrieves one message with body).

Plain variables in `wrangler.toml` `[vars]` (edit the file, then deploy):

| Name | Default | Notes |
|---|---|---|
| `PACKAGE_NAME` | `dev.localintelligence.app` | Android applicationId. Other packages are refused. |
| `PRODUCT_ID` | `extras_monthly` | The Play subscription product id. Create it in the Play Console with this exact id. |
| `DEFAULT_MODEL` | `google/gemini-2.5-flash` | Chat model handed to the phone. Same suggested id the app uses for OpenRouter. |
| `OPENROUTER_MONTHLY_LIMIT_USD` | `3.50` | Per-customer monthly cap, reset by OpenRouter (`limit_reset: monthly`). **Owner to confirm.** |
| `EXA_RATE_LIMIT_RPS` | `5` | Per-customer Exa requests per second. **Owner to confirm.** |
| `EXA_BUDGET_CENTS` | `150` | Per-customer Exa budget for the team's billing period, in cents. **Owner to confirm.** |
| `PUBSUB_AUDIENCE` | `TODO-OWNER-PUBSUB-AUDIENCE` | The audience of the Pub/Sub push token. Use the exact audience you enter on the push subscription (step 9.3). Until it is set, every push is refused with 401. |
| `PUBSUB_SERVICE_ACCOUNT_EMAIL` | `TODO-OWNER-PUBSUB-SERVICE-ACCOUNT-EMAIL` | The service account the push subscription signs as. Until it is set, every push is refused with 401. |
| `SANDBOX_MINUTES_CAP` | `120` | Per-customer monthly sandbox execution minutes cap (`sandbox`). **Owner to confirm.** |
| `BROWSER_MINUTES_CAP` | `60` | Per-customer monthly cloud browser minutes cap (`browser`). **Owner to confirm.** |
| `COMPOSIO_CALLS_CAP` | `500` | Per-customer monthly Composio MCP calls cap (`composio`). **Owner to confirm.** |
| `DEEPGRAM_PROJECT_ID` | `""` | Deepgram project ID used when minting short-lived STT tokens (`voice`). Empty leaves `voice` off. |
| `VOICE_STT_MINUTES_CAP` | `60` | Per-customer monthly Deepgram speech-to-text minutes cap (`voice`). **Owner to confirm.** |
| `VOICE_TTS_CHARS_CAP` | `20000` | Per-customer monthly Deepgram text-to-speech characters cap (`voice`). **Owner to confirm.** |
| `EMAIL_DOMAIN` | `""` | Domain used for managed agent mailboxes (`mail`). Empty leaves `mail` off. |
| `MAIL_SENDS_CAP` | `100` | Per-customer monthly outbound email sends cap (`mail`). **Owner to confirm.** |
| `TELEGRAM_BOT_USERNAME` | `""` | Public username of the shared Telegram bot without `@` (`telegram`). Empty leaves `telegram` off. |

## Deploy steps (owner)

Run these in `backend/extras-worker/`.

1. `npm install`
2. `npx wrangler login` (logs into your Cloudflare account; the account is not in the repo).
3. `npx wrangler d1 create localintelligence-extras`. Copy the printed `database_id` into
   `wrangler.toml`, replacing `TODO-OWNER-D1-DATABASE-ID`.
4. `npx wrangler d1 execute localintelligence-extras --remote --file=schema.sql`
   (If upgrading an existing `customers` table created before wave 2, also run:
   `npx wrangler d1 execute localintelligence-extras --remote --command="ALTER TABLE customers ADD COLUMN access_token_hash TEXT; ALTER TABLE customers ADD COLUMN access_token_enc TEXT; CREATE INDEX IF NOT EXISTS customers_by_access_token ON customers(access_token_hash);"`)
5. Set the five secrets above with `npx wrangler secret put <NAME>`.
6. Review the `[vars]` table in `wrangler.toml`.
7. `npm run typecheck`
8. `npx wrangler deploy`. Note the URL it prints, for example `https://localintelligence-extras.<subdomain>.workers.dev`.
9. Play Console, real-time developer notifications:
   1. Create a Pub/Sub topic in the Google Cloud project and grant
      `google-play-developer-notifications@system.gserviceaccount.com` the Pub/Sub Publisher role on it.
   2. Set that topic as the notification topic in the Play Console (Monetization setup).
   3. Create a push subscription on the topic with endpoint
      `https://<worker URL>/v1/rtdn/<RTDN_PATH_SECRET>`, with authentication enabled: pick a service
      account (it becomes `PUBSUB_SERVICE_ACCOUNT_EMAIL`) and set the audience (it becomes
      `PUBSUB_AUDIENCE`; the endpoint URL is a sensible choice). Then set both values in `wrangler.toml`
      and deploy. Equivalent gcloud flags: `--push-auth-service-account` and `--push-auth-token-audience`.
   4. Send a test notification from the Play Console and check the Worker logs show no errors.
   Docs: https://developer.android.com/google/play/billing/getting-ready and https://cloud.google.com/pubsub/docs/push
10. Create the `extras_monthly` subscription in the Play Console with the price you choose.
11. Give the app the Worker URL through the build field `EXTRAS_BACKEND_URL` (app side; not part of this Worker).

To change a secret later, run `wrangler secret put` again. To change a var, edit `wrangler.toml` and redeploy.

## Open items before launch

- **Exa create response has no documented secret field.** The documented `apiKey` object
  (https://exa.ai/docs/team-management-spec.yaml) lists id, name, rateLimit, budgetCents,
  isOverBudget, teamId, userId and createdAt, with no key string. `extractExaSecret` in
  `src/providers.ts` returns null, so activation deletes the new Exa key and returns 503 until the
  real field is confirmed with Exa support. Update that one function when confirmed.
- **Grace period counts as active** (`SUBSCRIPTION_STATE_IN_GRACE_PERIOD`), following Play's
  guidance that users keep access while billing is retried. This is a policy choice; change
  `entitlementFor` in `src/google.ts` if you want it stricter.
- **Pub/Sub push OIDC check is written but not tested against a live push.** `src/pubsub-auth.ts`
  checks the token's signature, `iss`, `aud`, `email`, `email_verified` and `exp`. The issuer and JWKS
  URL were confirmed from Google's OpenID configuration. Confirm with a real test notification
  from the Play Console before launch.
- **Exa key recreation on renewal.** A cancelled customer's Exa key is deleted and a new one is
  created on renewal. The phone only learns the new key on its next `/v1/refresh`. The app should
  refresh when its Exa key gets a 401 or when expiry has passed.
- **Refund and revoke handling** is by re-reading the subscription state after the voided-purchase
  or subscription notification. Confirm against a real test refund before launch.
- **Unverified provider terms**: OpenRouter's terms allow using the API inside your own product,
  but the research note asks for written confirmation before a per-customer key sale. See
  `/home/dario/LI-briefs/research-managed-keys.md`.
- **EU data processing terms** for OpenRouter and Exa are not verified.
