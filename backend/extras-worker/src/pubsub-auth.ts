/**
 * Verifies the Google-signed OIDC token that Pub/Sub attaches to every push request.
 *
 * The path secret (RTDN_PATH_SECRET) stays as the first gate. This check adds proof that the
 * request came from Pub/Sub's push service account for this subscription, so a leaked URL alone
 * cannot forge a notification. A token that fails any check gets a 401 with a fixed message and
 * nothing from the token is logged.
 *
 * Doc: https://docs.cloud.google.com/pubsub/docs/authenticate-push-subscriptions
 * Issuer and keys: https://accounts.google.com/.well-known/openid-configuration
 *   (issuer "https://accounts.google.com", jwks_uri "https://www.googleapis.com/oauth2/v3/certs")
 */
import { base64UrlToBytes, utf8 } from "./crypto";
import type { Env } from "./env";
import { HttpError, ProviderError } from "./http";

const JWKS_URL = "https://www.googleapis.com/oauth2/v3/certs";
const GOOGLE_ISSUERS = new Set(["https://accounts.google.com", "accounts.google.com"]);
/** Used when Google's response has no max-age, so a rotated key is picked up within the hour. */
const DEFAULT_JWKS_MAX_AGE_SECONDS = 3600;
/** An unknown key id may trigger one refetch per minute at most, so random ids cannot flood Google. */
const MIN_REFETCH_INTERVAL_MS = 60_000;

interface RsaJwk extends JsonWebKey {
  kid: string;
  n: string;
  e: string;
}

interface JwksCache {
  keys: Map<string, RsaJwk>;
  fetchedAtMs: number;
  expiresAtMs: number;
}

let jwksCache: JwksCache | null = null;

function unauthorized(): HttpError {
  return new HttpError(401, "unauthorized", "push request is not authenticated");
}

/**
 * Throws HttpError 401 unless the request carries a valid Pub/Sub OIDC bearer token for this
 * Worker's audience and push service account. Throws ProviderError (503, Pub/Sub retries) when
 * Google's key list cannot be fetched.
 */
export async function verifyPubsubPush(request: Request, env: Env): Promise<void> {
  // An unset or placeholder audience or email fails closed: no push is accepted until the owner
  // has set both variables to the values of the push subscription.
  if (!env.PUBSUB_AUDIENCE || !env.PUBSUB_SERVICE_ACCOUNT_EMAIL) {
    console.error("pubsub push auth is not configured");
    throw unauthorized();
  }

  const authorization = request.headers.get("authorization") ?? "";
  if (!authorization.startsWith("Bearer ")) throw unauthorized();
  const parts = authorization.slice("Bearer ".length).split(".");
  if (parts.length !== 3) throw unauthorized();
  const [headerSegment, payloadSegment, signatureSegment] = parts as [string, string, string];

  let header: { alg?: unknown; kid?: unknown };
  let claims: Record<string, unknown>;
  try {
    header = JSON.parse(new TextDecoder().decode(base64UrlToBytes(headerSegment)));
    claims = JSON.parse(new TextDecoder().decode(base64UrlToBytes(payloadSegment)));
  } catch {
    throw unauthorized();
  }
  if (header.alg !== "RS256" || typeof header.kid !== "string") throw unauthorized();

  if (typeof claims.iss !== "string" || !GOOGLE_ISSUERS.has(claims.iss)) throw unauthorized();
  if (claims.aud !== env.PUBSUB_AUDIENCE) throw unauthorized();
  if (claims.email !== env.PUBSUB_SERVICE_ACCOUNT_EMAIL) throw unauthorized();
  if (claims.email_verified !== true) throw unauthorized();
  if (typeof claims.exp !== "number" || claims.exp * 1000 <= Date.now()) throw unauthorized();

  const jwk = await signingKey(header.kid);
  if (!jwk) throw unauthorized();
  const key = await crypto.subtle.importKey(
    "jwk",
    { kty: "RSA", n: jwk.n, e: jwk.e, alg: "RS256", ext: true },
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["verify"],
  );
  let valid: boolean;
  try {
    valid = await crypto.subtle.verify(
      "RSASSA-PKCS1-v1_5",
      key,
      base64UrlToBytes(signatureSegment),
      utf8(`${headerSegment}.${payloadSegment}`),
    );
  } catch {
    throw unauthorized();
  }
  if (!valid) throw unauthorized();
}

/** Returns Google's RSA key with this id, from the cache when it is fresh. */
async function signingKey(kid: string): Promise<RsaJwk | null> {
  const now = Date.now();
  const cached = jwksCache;
  if (cached && cached.expiresAtMs > now) {
    const hit = cached.keys.get(kid);
    if (hit) return hit;
    if (now - cached.fetchedAtMs < MIN_REFETCH_INTERVAL_MS) return null;
  }
  jwksCache = await fetchJwks(now);
  return jwksCache.keys.get(kid) ?? null;
}

async function fetchJwks(nowMs: number): Promise<JwksCache> {
  const response = await fetch(JWKS_URL);
  if (!response.ok) throw new ProviderError("google_jwks", response.status);
  const body = (await response.json()) as { keys?: unknown };
  if (!Array.isArray(body.keys)) throw new ProviderError("google_jwks", 502);

  const keys = new Map<string, RsaJwk>();
  for (const candidate of body.keys) {
    if (isRsaJwk(candidate)) keys.set(candidate.kid, candidate);
  }
  const maxAge = parseMaxAge(response.headers.get("cache-control")) ?? DEFAULT_JWKS_MAX_AGE_SECONDS;
  return { keys, fetchedAtMs: nowMs, expiresAtMs: nowMs + maxAge * 1000 };
}

function isRsaJwk(value: unknown): value is RsaJwk {
  if (typeof value !== "object" || value === null) return false;
  const jwk = value as Partial<RsaJwk>;
  return jwk.kty === "RSA" && typeof jwk.kid === "string" && typeof jwk.n === "string" && typeof jwk.e === "string";
}

function parseMaxAge(cacheControl: string | null): number | null {
  const match = cacheControl?.match(/max-age=(\d+)/);
  return match ? Number(match[1]) : null;
}
