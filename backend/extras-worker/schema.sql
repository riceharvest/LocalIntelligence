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
  access_token_hash    TEXT,                       -- SHA-256 hex of the customer's extrasToken (lookup)
  access_token_enc     TEXT,                       -- base64(iv || AES-GCM ciphertext) of the extrasToken, so every phone on the subscription gets the same one
  created_at           TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
  updated_at           TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);
CREATE INDEX IF NOT EXISTS customers_by_access_token ON customers(access_token_hash);

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

-- Monthly per-customer usage totals for proxied Extras services.
CREATE TABLE IF NOT EXISTS usage (
  customer_id  TEXT NOT NULL REFERENCES customers(id),
  kind         TEXT NOT NULL,
  period       TEXT NOT NULL,                      -- 'YYYY-MM' in UTC
  amount       REAL NOT NULL DEFAULT 0,
  PRIMARY KEY (customer_id, kind, period)
);

-- Agent mailbox messages (inbound via Cloudflare Email Routing, outbound via Email Sending).
CREATE TABLE IF NOT EXISTS mail_messages (
  id           TEXT PRIMARY KEY,
  customer_id  TEXT NOT NULL REFERENCES customers(id),
  direction    TEXT NOT NULL CHECK (direction IN ('in', 'out')),
  from_addr    TEXT NOT NULL,
  to_addr      TEXT NOT NULL,
  subject      TEXT NOT NULL DEFAULT '',
  body_text    TEXT NOT NULL DEFAULT '',
  received_at  TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);
CREATE INDEX IF NOT EXISTS mail_messages_by_customer_received ON mail_messages(customer_id, received_at);

-- Per-customer mailbox local-part assignment on EMAIL_DOMAIN.
CREATE TABLE IF NOT EXISTS mail_addresses (
  local_part   TEXT PRIMARY KEY,
  customer_id  TEXT NOT NULL UNIQUE REFERENCES customers(id)
);

-- Pending deep-link tokens (`t.me/<bot>?start=<token>`) for linking a Telegram chat to a customer.
CREATE TABLE IF NOT EXISTS telegram_links (
  link_token_hash  TEXT PRIMARY KEY,
  customer_id      TEXT NOT NULL REFERENCES customers(id),
  created_at       TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);

-- Telegram chats linked to a customer.
CREATE TABLE IF NOT EXISTS telegram_chats (
  chat_id      INTEGER PRIMARY KEY,
  customer_id  TEXT NOT NULL REFERENCES customers(id),
  title        TEXT NOT NULL DEFAULT ''
);

-- Queued inbound Telegram messages waiting for the phone to poll /v1/telegram/updates.
CREATE TABLE IF NOT EXISTS telegram_inbox (
  id           INTEGER PRIMARY KEY AUTOINCREMENT,
  customer_id  TEXT NOT NULL REFERENCES customers(id),
  chat_id      INTEGER NOT NULL,
  text         TEXT NOT NULL,
  received_at  TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);
