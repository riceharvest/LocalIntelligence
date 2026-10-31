/**
 * Per-customer key provisioning at OpenRouter and Exa. Both calls use the management or
 * service key from the Worker's secrets, so these keys never reach the phone.
 *
 * - OpenRouter key management (create / update / delete):
 *   https://openrouter.ai/docs/api/api-reference/api-keys/create-a-new-api-key
 *   https://openrouter.ai/docs/api/api-reference/api-keys/update-an-api-key
 *   https://openrouter.ai/docs/api/api-reference/api-keys/delete-an-api-key
 *   Guide and management-key expiry rules: https://openrouter.ai/docs/guides/overview/auth/provisioning-api-keys
 *   POST https://openrouter.ai/api/v1/keys  ->  { data: { hash, ... }, key: "sk-or-v1-..." }
 *   The plaintext key is returned only by the create call.
 * - Exa Team Management API (create / delete):
 *   https://exa.ai/docs/reference/team-management/create-api-key
 *   https://exa.ai/docs/reference/team-management/delete-api-key
 *   OpenAPI spec: https://exa.ai/docs/team-management-spec.yaml
 *   Base https://admin-api.exa.ai/team-management, header x-api-key: <service key>.
 *   The team must be enabled by support@exa.ai before any of this works.
 */
import type { Env } from "./env";
import { ProviderError } from "./http";

const OPENROUTER_KEYS = "https://openrouter.ai/api/v1/keys";
const EXA_KEYS = "https://admin-api.exa.ai/team-management/api-keys";

export interface NewOpenRouterKey {
  hash: string;
  key: string;
}

export async function createOpenRouterKey(env: Env, name: string): Promise<NewOpenRouterKey> {
  const limit = Number(env.OPENROUTER_MONTHLY_LIMIT_USD);
  if (!Number.isFinite(limit) || limit <= 0) throw new Error("OPENROUTER_MONTHLY_LIMIT_USD is not a positive number");
  const response = await fetch(OPENROUTER_KEYS, {
    method: "POST",
    headers: {
      authorization: `Bearer ${env.OPENROUTER_PROVISIONING_KEY}`,
      "content-type": "application/json",
    },
    body: JSON.stringify({
      name,
      limit,
      limit_reset: "monthly",
      include_byok_in_limit: false,
    }),
  });
  if (!response.ok) throw new ProviderError("openrouter_create_key", response.status);
  const body = (await response.json()) as { key?: unknown; data?: { hash?: unknown } };
  const hash = body.data?.hash;
  if (typeof body.key !== "string" || typeof hash !== "string") {
    throw new ProviderError("openrouter_create_key", 502);
  }
  return { hash, key: body.key };
}

/** Disables or re-enables an OpenRouter key by hash. Requests stop being accepted while disabled. */
export async function setOpenRouterKeyDisabled(env: Env, hash: string, disabled: boolean): Promise<void> {
  const response = await fetch(`${OPENROUTER_KEYS}/${encodeURIComponent(hash)}`, {
    method: "PATCH",
    headers: {
      authorization: `Bearer ${env.OPENROUTER_PROVISIONING_KEY}`,
      "content-type": "application/json",
    },
    body: JSON.stringify({ disabled }),
  });
  if (!response.ok) throw new ProviderError("openrouter_update_key", response.status);
}

/** Deletes an OpenRouter key. A 404 means it is already gone, which counts as success. */
export async function deleteOpenRouterKey(env: Env, hash: string): Promise<void> {
  const response = await fetch(`${OPENROUTER_KEYS}/${encodeURIComponent(hash)}`, {
    method: "DELETE",
    headers: { authorization: `Bearer ${env.OPENROUTER_PROVISIONING_KEY}` },
  });
  if (!response.ok && response.status !== 404) throw new ProviderError("openrouter_delete_key", response.status);
}

export interface NewExaKey {
  id: string;
  /** The searchable secret. Null means the create response did not contain it; see extractExaSecret. */
  secret: string | null;
}

export async function createExaKey(env: Env, name: string): Promise<NewExaKey> {
  const rateLimit = Number(env.EXA_RATE_LIMIT_RPS);
  const budgetCents = Number(env.EXA_BUDGET_CENTS);
  if (!Number.isFinite(rateLimit) || rateLimit <= 0) throw new Error("EXA_RATE_LIMIT_RPS is not a positive number");
  if (!Number.isFinite(budgetCents) || budgetCents < 0) throw new Error("EXA_BUDGET_CENTS is not a number >= 0");
  const response = await fetch(EXA_KEYS, {
    method: "POST",
    headers: {
      "x-api-key": exaAdminKey(env),
      "content-type": "application/json",
    },
    body: JSON.stringify({ name, rateLimit, budgetCents }),
  });
  if (!response.ok) throw new ProviderError("exa_create_key", response.status);
  const body = (await response.json()) as { apiKey?: { id?: unknown } };
  const id = body.apiKey?.id;
  if (typeof id !== "string") throw new ProviderError("exa_create_key", 502);
  return { id, secret: extractExaSecret(body) };
}

/**
 * Pulls the searchable key string out of a create response.
 *
 * OPEN ISSUE (see README "Unverified"): the documented 200 response schema has `apiKey` with
 * id, name, rateLimit, budgetCents, isOverBudget, teamId, userId and createdAt, and no field
 * for the secret. Until Exa confirms where the secret is returned, this returns null, and
 * createExaKey's caller deletes the key and fails the activation rather than hand out a key
 * the phone cannot use. Add the field here once confirmed.
 */
function extractExaSecret(_body: unknown): string | null {
  return null;
}

export async function deleteExaKey(env: Env, id: string): Promise<void> {
  const response = await fetch(`${EXA_KEYS}/${encodeURIComponent(id)}`, {
    method: "DELETE",
    headers: { "x-api-key": exaAdminKey(env) },
  });
  if (!response.ok && response.status !== 404) throw new ProviderError("exa_delete_key", response.status);
}

/** The Exa team-management key; callers only reach Exa when it is set. */
function exaAdminKey(env: Env): string {
  if (!env.EXA_ADMIN_KEY) throw new ProviderError("exa_not_configured", 503);
  return env.EXA_ADMIN_KEY;
}
