/**
 * Customer access-token authentication for proxied Extras services.
 *
 * Each `/v1/activate` and `/v1/refresh` response hands the phone a fresh 32-byte random
 * `extrasToken` (base64url) and stores only its SHA-256 hex in `customers.access_token_hash`.
 * Proxied endpoints pass `Authorization: Bearer <extrasToken>`.
 */
import { sha256Hex } from "./crypto";
import { findCustomerByAccessTokenHash, type CustomerRow } from "./db";
import type { Env } from "./env";
import { HttpError } from "./http";

export async function requireCustomer(request: Request, env: Env): Promise<CustomerRow> {
  const authorization = request.headers.get("authorization") ?? "";
  if (!authorization.startsWith("Bearer ")) {
    throw new HttpError(401, "unauthorized", "missing bearer token");
  }
  const token = authorization.slice("Bearer ".length).trim();
  if (token.length === 0 || token.length > 512) {
    throw new HttpError(401, "unauthorized", "invalid bearer token");
  }
  const tokenHash = await sha256Hex(token);
  const customer = await findCustomerByAccessTokenHash(env, tokenHash);
  if (!customer) {
    throw new HttpError(401, "unauthorized", "invalid bearer token");
  }
  const expiryMs = customer.expires_at ? Date.parse(customer.expires_at) : Number.NaN;
  if (customer.status !== "active" || !Number.isFinite(expiryMs) || expiryMs <= Date.now()) {
    throw new HttpError(402, "not_active", "the Extras subscription is not active");
  }
  return customer;
}
