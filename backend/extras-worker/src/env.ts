/**
 * Bindings and variables of the Extras Worker.
 *
 * Secrets are set with `wrangler secret put <NAME>` and never appear in wrangler.toml.
 * Plain settings come from the [vars] table in wrangler.toml. See README.md for where
 * each value comes from.
 */
export interface Env {
  /** D1 database holding customers, purchase-token hashes and rate-limit counters. */
  DB: D1Database;

  // Plain vars (wrangler.toml [vars])
  PACKAGE_NAME: string;
  PRODUCT_ID: string;
  DEFAULT_MODEL: string;
  OPENROUTER_MONTHLY_LIMIT_USD: string;
  EXA_RATE_LIMIT_RPS: string;
  EXA_BUDGET_CENTS: string;
  /** Audience of the Pub/Sub push token (the push subscription's audience). Empty rejects every push. */
  PUBSUB_AUDIENCE: string;
  /** Service account email the push subscription signs as. Empty rejects every push. */
  PUBSUB_SERVICE_ACCOUNT_EMAIL: string;

  // Secrets (wrangler secret put)
  /** Full JSON key file of the Google Cloud service account linked to the Play Console. */
  GOOGLE_SERVICE_ACCOUNT_JSON: string;
  /** OpenRouter Management API key (created at openrouter.ai/settings/management-keys). */
  OPENROUTER_PROVISIONING_KEY: string;
  /** Exa service key for the Team Management API (Service keys tab, after Exa enables it). */
  /** Optional: without it Extras provisions no search key. */
  EXA_ADMIN_KEY?: string;
  /** Random path segment that Pub/Sub's push URL must contain, e.g. POST /v1/rtdn/<secret>. */
  RTDN_PATH_SECRET: string;
  /** Base64 of 32 random bytes. Encrypts provider key secrets stored in D1. */
  CREDENTIAL_ENCRYPTION_KEY: string;

  // Optional wave-2 managed services (a missing binding, secret or var leaves that service off)

  /** Sandbox Durable Object namespace (@cloudflare/sandbox, exported by job W1). */
  Sandbox?: DurableObjectNamespace;
  /** Monthly per-customer sandbox execution minutes cap. */
  SANDBOX_MINUTES_CAP?: string;

  /** Cloudflare Browser Rendering binding. */
  BROWSER?: Fetcher;
  /** Monthly per-customer browser minutes cap. */
  BROWSER_MINUTES_CAP?: string;

  /** Composio project API key for the MCP proxy. */
  COMPOSIO_API_KEY?: string;
  /** Monthly per-customer Composio MCP tool calls cap. */
  COMPOSIO_CALLS_CAP?: string;

  /** Deepgram API key for STT token grants and Aura-2 TTS synthesis. */
  DEEPGRAM_API_KEY?: string;
  /** Deepgram project ID used when minting short-lived STT tokens. */
  DEEPGRAM_PROJECT_ID?: string;
  /** Monthly per-customer Deepgram speech-to-text minutes cap. */
  VOICE_STT_MINUTES_CAP?: string;
  /** Monthly per-customer Deepgram text-to-speech characters cap. */
  VOICE_TTS_CHARS_CAP?: string;

  /** Cloudflare Email Sending (`send_email`) binding. */
  SEND_EMAIL?: SendEmail;
  /** Domain whose catch-all Email Routing rule delivers to this Worker's `email()` handler. */
  EMAIL_DOMAIN?: string;
  /** Monthly per-customer outbound email sends cap. */
  MAIL_SENDS_CAP?: string;

  /** Shared Telegram bot token from @BotFather. */
  TELEGRAM_BOT_TOKEN?: string;
  /** Secret path segment for `/v1/telegram/webhook/<secret>`. */
  TELEGRAM_WEBHOOK_SECRET?: string;
  /** Public username of the shared Telegram bot (without leading `@`), for `t.me/<bot>?start=...` links. */
  TELEGRAM_BOT_USERNAME?: string;
}
