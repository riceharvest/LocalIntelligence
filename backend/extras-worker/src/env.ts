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
}
