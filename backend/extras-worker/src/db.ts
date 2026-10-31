/**
 * D1 access. Every write that can race (two activations for one purchase, a renewal and a
 * refresh at once) is a conditional UPDATE or INSERT OR IGNORE, so the loser can detect it and
 * clean up its own provider key.
 */
import type { Env } from "./env";

export interface CustomerRow {
  id: string;
  product_id: string;
  status: "active" | "inactive";
  expires_at: string | null;
  latest_token_hash: string | null;
  openrouter_key_hash: string | null;
  openrouter_key_enc: string | null;
  exa_key_id: string | null;
  exa_key_enc: string | null;
}

const CUSTOMER_COLUMNS =
  "id, product_id, status, expires_at, latest_token_hash, openrouter_key_hash, openrouter_key_enc, exa_key_id, exa_key_enc";

export async function findCustomerByTokenHash(env: Env, tokenHash: string): Promise<CustomerRow | null> {
  return env.DB.prepare(
    `SELECT ${CUSTOMER_COLUMNS} FROM customers
       WHERE id = (SELECT customer_id FROM purchase_tokens WHERE token_hash = ?1)`,
  )
    .bind(tokenHash)
    .first<CustomerRow>();
}

export async function findCustomerById(env: Env, id: string): Promise<CustomerRow | null> {
  return env.DB.prepare(`SELECT ${CUSTOMER_COLUMNS} FROM customers WHERE id = ?1`).bind(id).first<CustomerRow>();
}

/**
 * Creates a customer and maps its first token, in one batch. If another request mapped the
 * same token first, INSERT OR IGNORE keeps that row, and the caller re-reads the winner.
 */
export async function createCustomer(env: Env, id: string, productId: string, tokenHash: string): Promise<void> {
  await env.DB.batch([
    env.DB.prepare("INSERT INTO customers (id, product_id, status) VALUES (?1, ?2, 'inactive')").bind(id, productId),
    env.DB.prepare("INSERT OR IGNORE INTO purchase_tokens (token_hash, customer_id) VALUES (?1, ?2)").bind(
      tokenHash,
      id,
    ),
  ]);
}

export async function mapPurchaseToken(env: Env, tokenHash: string, customerId: string): Promise<void> {
  await env.DB.prepare("INSERT OR IGNORE INTO purchase_tokens (token_hash, customer_id) VALUES (?1, ?2)")
    .bind(tokenHash, customerId)
    .run();
}

export async function setLatestToken(env: Env, customerId: string, tokenHash: string): Promise<void> {
  await env.DB.prepare(
    "UPDATE customers SET latest_token_hash = ?2, updated_at = strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE id = ?1",
  )
    .bind(customerId, tokenHash)
    .run();
}

export async function setStatus(
  env: Env,
  customerId: string,
  status: "active" | "inactive",
  expiresAt: string | null,
): Promise<void> {
  await env.DB.prepare(
    "UPDATE customers SET status = ?2, expires_at = ?3, updated_at = strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE id = ?1",
  )
    .bind(customerId, status, expiresAt)
    .run();
}

/** Stores the OpenRouter key only if none is stored yet. Returns false when another request won. */
export async function saveOpenRouterKey(env: Env, customerId: string, hash: string, enc: string): Promise<boolean> {
  const result = await env.DB.prepare(
    "UPDATE customers SET openrouter_key_hash = ?2, openrouter_key_enc = ?3 WHERE id = ?1 AND openrouter_key_hash IS NULL",
  )
    .bind(customerId, hash, enc)
    .run();
  return (result.meta.changes ?? 0) > 0;
}

/** Stores the Exa key only if none is stored yet. Returns false when another request won. */
export async function saveExaKey(env: Env, customerId: string, keyId: string, enc: string): Promise<boolean> {
  const result = await env.DB.prepare(
    "UPDATE customers SET exa_key_id = ?2, exa_key_enc = ?3 WHERE id = ?1 AND exa_key_id IS NULL",
  )
    .bind(customerId, keyId, enc)
    .run();
  return (result.meta.changes ?? 0) > 0;
}

export async function clearExaKey(env: Env, customerId: string): Promise<void> {
  await env.DB.prepare("UPDATE customers SET exa_key_id = NULL, exa_key_enc = NULL WHERE id = ?1")
    .bind(customerId)
    .run();
}

/**
 * Fixed-window counter. Returns true while the caller is under `max` hits in the current window.
 * The key is hashed so install ids are not kept in clear text.
 */
export async function withinRateLimit(
  env: Env,
  keyHash: string,
  max: number,
  windowSeconds: number,
  nowSeconds: number,
): Promise<boolean> {
  const row = await env.DB.prepare(
    `INSERT INTO rate_limits (key_hash, window_start, count) VALUES (?1, ?2, 1)
       ON CONFLICT(key_hash) DO UPDATE SET
         count = CASE WHEN rate_limits.window_start <= ?2 - ?3 THEN 1 ELSE rate_limits.count + 1 END,
         window_start = CASE WHEN rate_limits.window_start <= ?2 - ?3 THEN ?2 ELSE rate_limits.window_start END
       RETURNING count`,
  )
    .bind(keyHash, nowSeconds, windowSeconds)
    .first<{ count: number }>();
  return (row?.count ?? 1) <= max;
}
