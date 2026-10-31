/**
 * Small HTTP helpers shared by the handlers. Error bodies are fixed codes and never echo
 * request bodies, provider response bodies, keys or purchase tokens.
 */

/** An error that maps to a JSON response with a stable `error` code. */
export class HttpError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
  ) {
    super(message);
  }
}

/**
 * A call to Google or a provider failed for a reason that is not the customer's fault.
 * Only the service name and HTTP status are kept; the body is dropped on purpose.
 */
export class ProviderError extends Error {
  constructor(
    readonly service: string,
    readonly upstreamStatus: number,
  ) {
    super(`${service} failed with HTTP ${upstreamStatus}`);
  }
}

const MAX_BODY_BYTES = 8 * 1024;

export function json(body: unknown, status = 200, extraHeaders: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json; charset=utf-8", ...extraHeaders },
  });
}

/** Responses that carry provider keys must not be cached by anything in between. */
export function jsonNoStore(body: unknown, status = 200): Response {
  return json(body, status, { "cache-control": "no-store" });
}

export function errorResponse(code: string, status: number, message: string): Response {
  return json({ error: code, message }, status, { "cache-control": "no-store" });
}

export async function readJsonObject(request: Request): Promise<Record<string, unknown>> {
  const text = await request.text();
  if (text.length > MAX_BODY_BYTES) {
    throw new HttpError(413, "payload_too_large", "request body is too large");
  }
  let parsed: unknown;
  try {
    parsed = JSON.parse(text);
  } catch {
    throw new HttpError(400, "invalid_request", "body must be JSON");
  }
  if (typeof parsed !== "object" || parsed === null || Array.isArray(parsed)) {
    throw new HttpError(400, "invalid_request", "body must be a JSON object");
  }
  return parsed as Record<string, unknown>;
}

export function requireString(obj: Record<string, unknown>, key: string, min: number, max: number): string {
  const value = obj[key];
  if (typeof value !== "string" || value.length < min || value.length > max) {
    throw new HttpError(400, "invalid_request", `${key} is missing or malformed`);
  }
  return value;
}
