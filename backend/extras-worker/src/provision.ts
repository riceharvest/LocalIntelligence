/**
 * Makes a customer's provider keys exist and enabled, and returns them decrypted.
 *
 * Idempotent by design: a repeat activation or refresh returns the same keys, and a renewal
 * after a cancellation re-enables the OpenRouter key and creates a new Exa key (the old one was
 * deleted at cancellation). Each stored key is written with a conditional UPDATE, so two
 * racing requests cannot both keep a key; the loser deletes its own key.
 */
import { decryptSecret, encryptSecret } from "./crypto";
import {
  clearExaKey,
  findCustomerById,
  saveExaKey,
  saveOpenRouterKey,
  setStatus,
  type CustomerRow,
} from "./db";
import type { Env } from "./env";
import { ProviderError } from "./http";
import {
  createExaKey,
  createOpenRouterKey,
  deleteExaKey,
  deleteOpenRouterKey,
  setOpenRouterKeyDisabled,
} from "./providers";

export interface ProvisionedKeys {
  openrouterKey: string;
  /** Null when Exa is not set up, not enabled for the team, or did not return a usable key. Search then stays on the free provider. */
  exaKey: string | null;
}

export async function provisionActive(env: Env, start: CustomerRow, expiresAt: string): Promise<ProvisionedKeys> {
  let row = start;

  if (!row.openrouter_key_hash || !row.openrouter_key_enc) {
    const created = await createOpenRouterKey(env, `extras-${row.id}`);
    const enc = await encryptSecret(created.key, env.CREDENTIAL_ENCRYPTION_KEY);
    if (!(await saveOpenRouterKey(env, row.id, created.hash, enc))) {
      await deleteOpenRouterKey(env, created.hash);
    }
    row = await reload(env, row);
  }

  // Re-enable on every active verification. Cheap, and it covers a key disabled by an earlier
  // cancellation whose cleanup then failed part way.
  await setOpenRouterKeyDisabled(env, requireValue(row.openrouter_key_hash), false);

  // Exa is optional: the chat model is what Extras is bought for, so a search key that cannot be
  // made (no admin key, the feature not enabled, or no secret in the reply) never blocks activation.
  if (env.EXA_ADMIN_KEY && (!row.exa_key_id || !row.exa_key_enc)) {
    try {
      const created = await createExaKey(env, `extras-${row.id}`);
      if (!created.secret) {
        // Without the secret the key is useless to the phone, so do not leave it behind.
        await deleteExaKey(env, created.id);
      } else {
        const enc = await encryptSecret(created.secret, env.CREDENTIAL_ENCRYPTION_KEY);
        if (!(await saveExaKey(env, row.id, created.id, enc))) {
          await deleteExaKey(env, created.id);
        }
        row = await reload(env, row);
      }
    } catch (e) {
      console.warn("exa key not provisioned", e instanceof ProviderError ? e.message : "error");
    }
  }

  await setStatus(env, row.id, "active", expiresAt);

  return {
    openrouterKey: await decryptSecret(requireValue(row.openrouter_key_enc), env.CREDENTIAL_ENCRYPTION_KEY),
    exaKey: row.exa_key_enc ? await decryptSecret(row.exa_key_enc, env.CREDENTIAL_ENCRYPTION_KEY) : null,
  };
}

/**
 * Turns an active customer off: the OpenRouter key is disabled first (it is the one that spends
 * money), then the Exa key is deleted. If the Exa delete fails, the error propagates so Pub/Sub
 * retries, and the next attempt repeats both steps safely.
 */
export async function deprovision(env: Env, customer: CustomerRow): Promise<void> {
  if (customer.openrouter_key_hash) {
    await setOpenRouterKeyDisabled(env, customer.openrouter_key_hash, true);
  }
  if (customer.exa_key_id) {
    await deleteExaKey(env, customer.exa_key_id);
    await clearExaKey(env, customer.id);
  }
  await setStatus(env, customer.id, "inactive", null);
}

async function reload(env: Env, row: CustomerRow): Promise<CustomerRow> {
  return (await findCustomerById(env, row.id)) ?? row;
}

function requireValue(value: string | null): string {
  if (!value) throw new Error("provider key missing after provisioning");
  return value;
}
