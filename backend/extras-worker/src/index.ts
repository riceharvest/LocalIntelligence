/**
 * Extras Worker entry point. Routes:
 *   POST /v1/activate            first purchase on a phone; returns the customer's keys
 *   POST /v1/refresh             same keys for an active purchase, or 402 when it is no longer active
 *   POST /v1/rtdn/<RTDN_PATH_SECRET>   Play real-time developer notifications via Pub/Sub push
 *
 * The Worker never proxies chat or search traffic. The phone calls the providers directly with
 * the keys it receives here.
 */
import { constantTimeEquals } from "./crypto";
import type { Env } from "./env";
import { handleActivate, handleRefresh, handleRtdn } from "./handlers";
import { errorResponse, HttpError, ProviderError } from "./http";

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);
    try {
      if (request.method !== "POST") return errorResponse("method_not_allowed", 405, "use POST");
      if (url.pathname === "/v1/activate") return await handleActivate(request, env);
      if (url.pathname === "/v1/refresh") return await handleRefresh(request, env);
      if (url.pathname.startsWith("/v1/rtdn/")) {
        const secret = url.pathname.slice("/v1/rtdn/".length);
        if (!constantTimeEquals(secret, env.RTDN_PATH_SECRET)) return errorResponse("not_found", 404, "not found");
        return await handleRtdn(request, env);
      }
      return errorResponse("not_found", 404, "not found");
    } catch (error) {
      return toErrorResponse(error);
    }
  },
} satisfies ExportedHandler<Env>;

/**
 * Logs our own error messages only. Provider bodies, keys and tokens never reach a
 * log line, and ProviderError carries only the service name and HTTP status.
 */
function toErrorResponse(error: unknown): Response {
  if (error instanceof HttpError) return errorResponse(error.code, error.status, error.message);
  if (error instanceof ProviderError) {
    console.error(`upstream failure: ${error.service} HTTP ${error.upstreamStatus}`);
    return errorResponse("provider_unavailable", 503, "Extras is unavailable right now, try again later");
  }
  console.error(`internal error: ${error instanceof Error ? error.message : "unknown"}`);
  return errorResponse("internal", 500, "internal error");
}
