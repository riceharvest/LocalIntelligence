/**
 * The three endpoints: /v1/activate, /v1/refresh and the RTDN push (/v1/rtdn/<secret>).
 *
 * The Play subscription is always re-read from Google before a key is handed out or changed.
 * Notification payloads and client claims are only hints about which purchase to look at.
 */
import { acknowledgeIfPending, entitlementFor, getSubscription, type SubscriptionPurchaseV2 } from "./google";
import { base64ToBytes, sha256Hex } from "./crypto";
import {
  createCustomer,
  findCustomerByTokenHash,
  mapPurchaseToken,
  setLatestToken,
  withinRateLimit,
  type CustomerRow,
} from "./db";
import type { Env } from "./env";
import { HttpError, json, jsonNoStore, readJsonObject, requireString } from "./http";
import { deprovision, provisionActive } from "./provision";

const ACTIVATE_MAX_PER_HOUR = 10;

/** Pub/Sub push envelope and the parts of the Play developer notification that we read. */
interface PushEnvelope {
  message?: { data?: unknown; messageId?: unknown };
}

interface DeveloperNotification {
  version?: string;
  packageName?: string;
  subscriptionNotification?: { notificationType?: number; purchaseToken?: string; subscriptionId?: string };
  voidedPurchaseNotification?: { purchaseToken?: string; productType?: number };
}

export async function handleActivate(request: Request, env: Env): Promise<Response> {
  const body = await readJsonObject(request);
  const packageName = requireString(body, "packageName", 1, 200);
  const productId = requireString(body, "productId", 1, 200);
  const purchaseToken = requireString(body, "purchaseToken", 1, 4096);
  const installId = requireInstallId(body);

  if (packageName !== env.PACKAGE_NAME) throw new HttpError(400, "wrong_package", "unknown app package");
  if (productId !== env.PRODUCT_ID) throw new HttpError(400, "wrong_product", "unknown product");

  // Per install id, so one phone cannot spam Google and the provider APIs.
  const allowed = await withinRateLimit(
    env,
    await sha256Hex(`activate:${installId}`),
    ACTIVATE_MAX_PER_HOUR,
    3600,
    nowSeconds(),
  );
  if (!allowed) throw new HttpError(429, "rate_limited", "too many attempts, try again later");

  const sub = await getSubscription(env, purchaseToken);
  if (!sub) throw new HttpError(402, "not_active", "no Extras purchase found for this token");
  const ent = entitlementFor(sub, env.PRODUCT_ID, Date.now());
  if (!ent.active || !ent.expiresAt) throw new HttpError(402, "not_active", "the Extras subscription is not active");

  // Acknowledge before provisioning: Play refunds an unacknowledged purchase after a few days.
  await acknowledgeIfPending(env, sub, env.PRODUCT_ID, purchaseToken);

  const tokenHash = await sha256Hex(purchaseToken);
  const customer = await resolveCustomer(env, tokenHash, sub);
  await setLatestToken(env, customer.id, tokenHash);
  const keys = await provisionActive(env, customer, ent.expiresAt);
  return credentialsResponse(env, ent.expiresAt, keys.openrouterKey, keys.exaKey);
}

export async function handleRefresh(request: Request, env: Env): Promise<Response> {
  const body = await readJsonObject(request);
  const purchaseToken = requireString(body, "purchaseToken", 1, 4096);
  requireInstallId(body);

  const sub = await getSubscription(env, purchaseToken);
  if (!sub) throw new HttpError(402, "not_active", "no Extras purchase found for this token");
  const ent = entitlementFor(sub, env.PRODUCT_ID, Date.now());
  if (!ent.active || !ent.expiresAt) throw new HttpError(402, "not_active", "the Extras subscription is not active");
  await acknowledgeIfPending(env, sub, env.PRODUCT_ID, purchaseToken);

  const tokenHash = await sha256Hex(purchaseToken);
  const customer = await findCustomerForToken(env, tokenHash, sub);
  if (!customer) throw new HttpError(404, "unknown_purchase", "this purchase has not been activated; activate first");
  await setLatestToken(env, customer.id, tokenHash);
  const keys = await provisionActive(env, customer, ent.expiresAt);
  return credentialsResponse(env, ent.expiresAt, keys.openrouterKey, keys.exaKey);
}

/**
 * Play real-time developer notification, delivered by Pub/Sub push. Every outcome that is not
 * a transient failure returns 200, so Pub/Sub does not retry messages that can never succeed.
 * Transient Google or provider failures throw, which gives a 503 and a Pub/Sub retry.
 *
 * Doc: https://developer.android.com/google/play/billing/rtdn-reference
 * Push format: https://cloud.google.com/pubsub/docs/push
 */
export async function handleRtdn(request: Request, env: Env): Promise<Response> {
  const envelope = (await readJsonObject(request)) as PushEnvelope;
  if (typeof envelope.message?.data !== "string") return ack("no message data");

  let notification: DeveloperNotification;
  try {
    notification = JSON.parse(new TextDecoder().decode(base64ToBytes(envelope.message.data)));
  } catch {
    return ack("undecodable message");
  }
  if (notification.packageName !== env.PACKAGE_NAME) return ack("other package");

  let token: string | undefined;
  const sn = notification.subscriptionNotification;
  const voided = notification.voidedPurchaseNotification;
  if (sn) {
    if (sn.subscriptionId !== env.PRODUCT_ID) return ack("other product");
    token = sn.purchaseToken;
  } else if (voided && voided.productType === 1) {
    // productType 1 is a subscription. A voided (refunded) subscription must cut access.
    token = voided.purchaseToken;
  }
  if (typeof token !== "string" || token.length === 0) return ack("not a subscription event");

  const tokenHash = await sha256Hex(token);
  const customer = await findCustomerByTokenHash(env, tokenHash);
  // Only the newest token of a customer may change its keys. An old token from before an
  // upgrade reports EXPIRED, and acting on it would cut a paying customer off.
  if (!customer || customer.latest_token_hash !== tokenHash) return ack("unknown or superseded token");

  const sub = await getSubscription(env, token);
  const ent = sub ? entitlementFor(sub, env.PRODUCT_ID, Date.now()) : { active: false, expiresAt: null };
  if (sub && ent.active && ent.expiresAt) {
    await acknowledgeIfPending(env, sub, env.PRODUCT_ID, token);
    await provisionActive(env, customer, ent.expiresAt);
  } else {
    await deprovision(env, customer);
  }
  return ack("reconciled");
}

/** Finds the customer for a token, or for the token it was upgraded from; creates one for a new purchase. */
async function resolveCustomer(env: Env, tokenHash: string, sub: SubscriptionPurchaseV2): Promise<CustomerRow> {
  const existing = await findCustomerForToken(env, tokenHash, sub);
  if (existing) return existing;
  await createCustomer(env, crypto.randomUUID(), env.PRODUCT_ID, tokenHash);
  // Re-read rather than trust the insert: a concurrent activation may have mapped the token first.
  const created = await findCustomerByTokenHash(env, tokenHash);
  if (!created) throw new Error("customer row missing after insert");
  return created;
}

async function findCustomerForToken(
  env: Env,
  tokenHash: string,
  sub: SubscriptionPurchaseV2,
): Promise<CustomerRow | null> {
  const direct = await findCustomerByTokenHash(env, tokenHash);
  if (direct) return direct;
  if (!sub.linkedPurchaseToken) return null;
  const upgradedFrom = await findCustomerByTokenHash(env, await sha256Hex(sub.linkedPurchaseToken));
  if (!upgradedFrom) return null;
  await mapPurchaseToken(env, tokenHash, upgradedFrom.id);
  return upgradedFrom;
}

function requireInstallId(body: Record<string, unknown>): string {
  const installId = requireString(body, "installId", 8, 128);
  if (!/^[A-Za-z0-9_-]+$/.test(installId)) throw new HttpError(400, "invalid_request", "installId is malformed");
  return installId;
}

function credentialsResponse(env: Env, expiresAt: string, openrouterKey: string, exaKey: string): Response {
  return jsonNoStore({
    expiresAt,
    openrouter: { key: openrouterKey, model: env.DEFAULT_MODEL },
    exa: { key: exaKey },
  });
}

/** 200 for Pub/Sub. `note` is a fixed reason string, never data from the message. */
function ack(note: string): Response {
  return json({ ok: true, note });
}

function nowSeconds(): number {
  return Math.floor(Date.now() / 1000);
}
