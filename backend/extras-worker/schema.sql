-- D1 schema for the Extras Worker. Apply with:
--   wrangler d1 execute localintelligence-extras --remote --file=schema.sql
-- (The owner runs this; the Worker itself never runs DDL.)
-- Only SHA-256 hashes of Play purchase tokens and install ids are stored. Provider
-- secrets are stored AES-GCM encrypted with CREDENTIAL_ENCRYPTION_KEY, because a
-- provider key is only shown once and must be handed back on re-activation.

CREATE TABLE IF NOT EXISTS customers (
  id                   TEXT PRIMARY KEY,           -- random UUID; also the OpenRouter key name
  product_id           TEXT NOT NULL,              -- Play product id, e.g. extras_monthly
  status               TEXT NOT NULL CHECK (status IN ('active', 'inactive')),
  expires_at           TEXT,                       -- ISO 8601, latest lineItems[].expiryTime from Play
  latest_token_hash    TEXT,                       -- hash of the newest purchase token; only this one may disable
  openrouter_key_hash  TEXT,                       -- OpenRouter key hash (used for PATCH/DELETE)
  openrouter_key_enc   TEXT,                       -- base64(iv || AES-GCM ciphertext) of the key secret
  exa_key_id           TEXT,                       -- Exa team-management API key id (used for PUT/DELETE)
  exa_key_enc          TEXT,                       -- base64(iv || AES-GCM ciphertext) of the Exa key secret
  created_at           TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
  updated_at           TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);

-- Every purchase token ever seen for a customer (a renewal keeps one token; an upgrade
-- or re-subscribe creates a new token whose linkedPurchaseToken points at the old one).
CREATE TABLE IF NOT EXISTS purchase_tokens (
  token_hash   TEXT PRIMARY KEY,                   -- SHA-256 hex of the purchaseToken
  customer_id  TEXT NOT NULL REFERENCES customers(id),
  created_at   TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);
CREATE INDEX IF NOT EXISTS purchase_tokens_by_customer ON purchase_tokens(customer_id);

-- Fixed-window counter for /v1/activate, one row per install id hash.
CREATE TABLE IF NOT EXISTS rate_limits (
  key_hash      TEXT PRIMARY KEY,                  -- SHA-256 hex of "activate:" + installId
  window_start  INTEGER NOT NULL,                  -- unix seconds, start of the current window
  count         INTEGER NOT NULL
);
