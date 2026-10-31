/**
 * Google Play Developer API calls and the entitlement rule.
 *
 * Doc URLs (checked 2026-10-31):
 * - subscriptionsv2.get:
 *   https://developers.google.com/android-publisher/api-ref/rest/v3/purchases.subscriptionsv2/get
 *   GET https://androidpublisher.googleapis.com/androidpublisher/v3/applications/{packageName}/purchases/subscriptionsv2/tokens/{token}
 * - subscriptions.acknowledge (v1 path, the only acknowledge endpoint for subscriptions):
 *   https://developers.google.com/android-publisher/api-ref/rest/v3/purchases.subscriptions/acknowledge
 *   POST .../purchases/subscriptions/{subscriptionId}/tokens/{token}:acknowledge
 * - Service-account OAuth, JWT bearer grant (RS256, scope androidpublisher):
 *   https://developers.google.com/identity/protocols/oauth2/service-account
 * - Subscription states (SUBSCRIPTION_STATE_*) are listed on the subscriptionsv2 resource page.
 */
import { base64UrlEncode, base64ToBytes, utf8 } from "./crypto";
import type { Env } from "./env";
import { ProviderError } from "./http";

const TOKEN_URL = "https://oauth2.googleapis.com/token";
const PUBLISHER_BASE = "https://androidpublisher.googleapis.com/androidpublisher/v3/applications";
const SCOPE = "https://www.googleapis.com/auth/androidpublisher";

/** The subset of SubscriptionPurchaseV2 this Worker reads. Unknown fields are ignored. */
export interface SubscriptionPurchaseV2 {
  subscriptionState?: string;
  linkedPurchaseToken?: string | null;
  acknowledgementState?: string;
  lineItems?: Array<{ productId?: string; expiryTime?: string }>;
}

export interface Entitlement {
  active: boolean;
  /** Latest expiry of the Extras line item, ISO 8601, or null when not active. */
  expiresAt: string | null;
}

interface ServiceAccount {
  client_email: string;
  private_key: string;
}

let cachedAccessToken: { value: string; expiresAtMs: number } | null = null;

function parseServiceAccount(raw: string): ServiceAccount {
  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch {
    throw new Error("GOOGLE_SERVICE_ACCOUNT_JSON is not JSON");
  }
  const sa = parsed as Partial<ServiceAccount>;
  if (typeof sa.client_email !== "string" || typeof sa.private_key !== "string") {
    throw new Error("GOOGLE_SERVICE_ACCOUNT_JSON lacks client_email or private_key");
  }
  return { client_email: sa.client_email, private_key: sa.private_key };
}

async function importPkcs8(pem: string): Promise<CryptoKey> {
  const body = pem
    .replace(/-----BEGIN PRIVATE KEY-----/, "")
    .replace(/-----END PRIVATE KEY-----/, "")
    .replace(/\s+/g, "");
  return crypto.subtle.importKey(
    "pkcs8",
    base64ToBytes(body),
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["sign"],
  );
}

/** Signs a short-lived JWT with the service account key and exchanges it for an access token. */
async function accessToken(env: Env): Promise<string> {
  const nowMs = Date.now();
  if (cachedAccessToken && cachedAccessToken.expiresAtMs - 60_000 > nowMs) {
    return cachedAccessToken.value;
  }
  const sa = parseServiceAccount(env.GOOGLE_SERVICE_ACCOUNT_JSON);
  const iat = Math.floor(nowMs / 1000);
  const header = base64UrlEncode(utf8(JSON.stringify({ alg: "RS256", typ: "JWT" })));
  const claims = base64UrlEncode(
    utf8(JSON.stringify({ iss: sa.client_email, scope: SCOPE, aud: TOKEN_URL, iat, exp: iat + 3600 })),
  );
  const key = await importPkcs8(sa.private_key);
  const signature = new Uint8Array(
    await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, utf8(`${header}.${claims}`)),
  );
  const assertion = `${header}.${claims}.${base64UrlEncode(signature)}`;

  const response = await fetch(TOKEN_URL, {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
      assertion,
    }),
  });
  if (!response.ok) throw new ProviderError("google_oauth", response.status);
  const body = (await response.json()) as { access_token?: unknown; expires_in?: unknown };
  if (typeof body.access_token !== "string") throw new ProviderError("google_oauth", 502);
  const lifetimeSeconds = typeof body.expires_in === "number" ? body.expires_in : 3600;
  cachedAccessToken = { value: body.access_token, expiresAtMs: nowMs + lifetimeSeconds * 1000 };
  return body.access_token;
}

/**
 * Returns the purchase, or null when Google does not know the token (400/404). Any other
 * failure throws, so the caller can answer 503 or let Pub/Sub retry.
 */
export async function getSubscription(env: Env, purchaseToken: string): Promise<SubscriptionPurchaseV2 | null> {
  const url =
    `${PUBLISHER_BASE}/${encodeURIComponent(env.PACKAGE_NAME)}` +
    `/purchases/subscriptionsv2/tokens/${encodeURIComponent(purchaseToken)}`;
  const response = await fetch(url, {
    headers: { authorization: `Bearer ${await accessToken(env)}` },
  });
  if (response.status === 400 || response.status === 404) return null;
  if (!response.ok) throw new ProviderError("google_play", response.status);
  return (await response.json()) as SubscriptionPurchaseV2;
}

/**
 * Acknowledges a subscription that Play reports as unacknowledged. Play refunds unacknowledged
 * purchases after a few days, so the caller does this right after verifying the purchase.
 * Acknowledging twice is harmless; the call is only made when the state says PENDING.
 */
export async function acknowledgeIfPending(
  env: Env,
  sub: SubscriptionPurchaseV2,
  subscriptionId: string,
  purchaseToken: string,
): Promise<void> {
  if (sub.acknowledgementState !== "ACKNOWLEDGEMENT_STATE_PENDING") return;
  const url =
    `${PUBLISHER_BASE}/${encodeURIComponent(env.PACKAGE_NAME)}` +
    `/purchases/subscriptions/${encodeURIComponent(subscriptionId)}` +
    `/tokens/${encodeURIComponent(purchaseToken)}:acknowledge`;
  const response = await fetch(url, {
    method: "POST",
    headers: {
      authorization: `Bearer ${await accessToken(env)}`,
      "content-type": "application/json",
    },
    body: "{}",
  });
  if (!response.ok) throw new ProviderError("google_play_acknowledge", response.status);
}

/**
 * The entitlement rule. Keep access for ACTIVE and IN_GRACE_PERIOD (Play still counts the user
 * as entitled), and for CANCELED only until the paid period ends. Everything else (ON_HOLD,
 * PAUSED, EXPIRED, PENDING, unknown) is not active. A refund shows up as an expired state.
 */
export function entitlementFor(sub: SubscriptionPurchaseV2, productId: string, nowMs: number): Entitlement {
  const item = (sub.lineItems ?? []).find((line) => line.productId === productId);
  if (!item) return { active: false, expiresAt: null };
  const expiryMs = item.expiryTime ? Date.parse(item.expiryTime) : Number.NaN;
  const expiresAt = Number.isFinite(expiryMs) ? new Date(expiryMs).toISOString() : null;

  const state = sub.subscriptionState;
  let active = false;
  if (state === "SUBSCRIPTION_STATE_ACTIVE" || state === "SUBSCRIPTION_STATE_IN_GRACE_PERIOD") {
    active = true;
  } else if (state === "SUBSCRIPTION_STATE_CANCELED") {
    active = Number.isFinite(expiryMs) && expiryMs > nowMs;
  }
  return { active, expiresAt: active ? expiresAt : null };
}
