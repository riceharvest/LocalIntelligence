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
| `POST /v1/activate` | the app, after a Play purchase | body `{packageName, productId, purchaseToken, installId}`. Verifies the purchase, provisions keys, returns `{expiresAt, openrouter: {key, model}, exa: {key}}`. Idempotent. Limited to 10 calls per install id per hour. |
| `POST /v1/refresh` | the app, at start when expiry is near | body `{purchaseToken, installId}`. Same keys if still active, `402 not_active` otherwise. |
| `POST /v1/rtdn/<RTDN_PATH_SECRET>` | Google Pub/Sub push | Play real-time developer notification. Re-reads the subscription from Google and disables or re-provisions keys to match. |

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

Plain variables in `wrangler.toml` `[vars]` (edit the file, then deploy):

| Name | Default | Notes |
|---|---|---|
| `PACKAGE_NAME` | `dev.localintelligence.app` | Android applicationId. Other packages are refused. |
| `PRODUCT_ID` | `extras_monthly` | The Play subscription product id. Create it in the Play Console with this exact id. |
| `DEFAULT_MODEL` | `google/gemini-2.5-flash` | Chat model handed to the phone. Same suggested id the app uses for OpenRouter. |
| `OPENROUTER_MONTHLY_LIMIT_USD` | `3.50` | Per-customer monthly cap, reset by OpenRouter (`limit_reset: monthly`). **Owner to confirm.** |
| `EXA_RATE_LIMIT_RPS` | `5` | Per-customer Exa requests per second. **Owner to confirm.** |
| `EXA_BUDGET_CENTS` | `150` | Per-customer Exa budget for the team's billing period, in cents. **Owner to confirm.** |

## Deploy steps (owner)

Run these in `backend/extras-worker/`.

1. `npm install`
2. `npx wrangler login` (logs into your Cloudflare account; the account is not in the repo).
3. `npx wrangler d1 create localintelligence-extras`. Copy the printed `database_id` into
   `wrangler.toml`, replacing `TODO-OWNER-D1-DATABASE-ID`.
4. `npx wrangler d1 execute localintelligence-extras --remote --file=schema.sql`
5. Set the five secrets above with `npx wrangler secret put <NAME>`.
6. Review the `[vars]` table in `wrangler.toml`.
7. `npm run typecheck`
8. `npx wrangler deploy`. Note the URL it prints, for example `https://localintelligence-extras.<subdomain>.workers.dev`.
9. Play Console, real-time developer notifications:
   1. Create a Pub/Sub topic in the Google Cloud project and grant
      `google-play-developer-notifications@system.gserviceaccount.com` the Pub/Sub Publisher role on it.
   2. Set that topic as the notification topic in the Play Console (Monetization setup).
   3. Create a push subscription on the topic with endpoint
      `https://<worker URL>/v1/rtdn/<RTDN_PATH_SECRET>`.
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
- **Pub/Sub push is authenticated by the secret in the URL path only.** You can add OIDC token
  verification on top (Pub/Sub can sign pushes); not done here.
- **Exa key recreation on renewal.** A cancelled customer's Exa key is deleted and a new one is
  created on renewal. The phone only learns the new key on its next `/v1/refresh`. The app should
  refresh when its Exa key gets a 401 or when expiry has passed.
- **Refund and revoke handling** is by re-reading the subscription state after the voided-purchase
  or subscription notification. Confirm against a real test refund before launch.
- **Unverified provider terms**: OpenRouter's terms allow using the API inside your own product,
  but the research note asks for written confirmation before a per-customer key sale. See
  `/home/dario/LI-briefs/research-managed-keys.md`.
- **EU data processing terms** for OpenRouter and Exa are not verified.
