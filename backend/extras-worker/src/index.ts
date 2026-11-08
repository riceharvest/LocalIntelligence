/**
 * Extras Worker entry point. Routes:
 *   POST /v1/activate                         first purchase on a phone; returns keys, extrasToken and services
 *   POST /v1/refresh                          same keys and extrasToken for an active purchase, or 402
 *   POST /v1/rtdn/<RTDN_PATH_SECRET>          Play real-time developer notifications via Pub/Sub push
 *   POST /v1/sandbox/{create,info,start,stop,exec,write,read}
 *   POST /v1/browser/{start,status,result}
 *   ALL  /v1/mcp/composio
 *   POST /v1/voice/{stt-token,speak}
 *   POST /v1/mail/{address,list,read,send}
 *   POST /v1/telegram/{link,updates,send}
 *   POST /v1/telegram/webhook/<secret>
 */
import { constantTimeEquals } from "./crypto";
import type { Env } from "./env";
import { handleActivate, handleRefresh, handleRtdn } from "./handlers";
import { errorResponse, HttpError, ProviderError } from "./http";
import { verifyPubsubPush } from "./pubsub-auth";
import { enabledServices, requireService } from "./services";
import { handleBrowser, type BrowserAction } from "./services/browser";
import { handleComposio } from "./services/composio";
import { handleInboundEmail, handleMail, type MailAction } from "./services/mail";
import { handleSandbox, type SandboxAction } from "./services/sandbox";
import { handleTelegram, handleTelegramWebhook, type TelegramAction } from "./services/telegram";
import { handleVoice, type VoiceAction } from "./services/voice";

const SANDBOX_ACTIONS = new Set<SandboxAction>(["create", "info", "start", "stop", "exec", "write", "read"]);
const BROWSER_ACTIONS = new Set<BrowserAction>(["start", "status", "result"]);
const VOICE_ACTIONS = new Set<VoiceAction>(["stt-token", "speak"]);
const MAIL_ACTIONS = new Set<MailAction>(["address", "list", "read", "send"]);
const TELEGRAM_ACTIONS = new Set<TelegramAction>(["link", "updates", "send"]);

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);
    try {
      if (url.pathname === "/v1/mcp/composio") {
        requireService(env, "composio");
        return await handleComposio(request, env);
      }

      if (request.method !== "POST") return errorResponse("method_not_allowed", 405, "use POST");
      if (url.pathname === "/v1/activate") return await handleActivate(request, env);
      if (url.pathname === "/v1/refresh") return await handleRefresh(request, env);
      if (url.pathname.startsWith("/v1/rtdn/")) {
        const secret = url.pathname.slice("/v1/rtdn/".length);
        if (!constantTimeEquals(secret, env.RTDN_PATH_SECRET)) return errorResponse("not_found", 404, "not found");
        await verifyPubsubPush(request, env);
        return await handleRtdn(request, env);
      }

      if (url.pathname.startsWith("/v1/sandbox/")) {
        const action = url.pathname.slice("/v1/sandbox/".length) as SandboxAction;
        if (!SANDBOX_ACTIONS.has(action)) return errorResponse("not_found", 404, "not found");
        requireService(env, "sandbox");
        return await handleSandbox(request, env, action);
      }

      if (url.pathname.startsWith("/v1/browser/")) {
        const action = url.pathname.slice("/v1/browser/".length) as BrowserAction;
        if (!BROWSER_ACTIONS.has(action)) return errorResponse("not_found", 404, "not found");
        requireService(env, "browser");
        return await handleBrowser(request, env, action);
      }

      if (url.pathname.startsWith("/v1/voice/")) {
        const action = url.pathname.slice("/v1/voice/".length) as VoiceAction;
        if (!VOICE_ACTIONS.has(action)) return errorResponse("not_found", 404, "not found");
        requireService(env, "voice");
        return await handleVoice(request, env, action);
      }

      if (url.pathname.startsWith("/v1/mail/")) {
        const action = url.pathname.slice("/v1/mail/".length) as MailAction;
        if (!MAIL_ACTIONS.has(action)) return errorResponse("not_found", 404, "not found");
        requireService(env, "mail");
        return await handleMail(request, env, action);
      }

      if (url.pathname.startsWith("/v1/telegram/webhook/")) {
        const secret = url.pathname.slice("/v1/telegram/webhook/".length);
        if (secret.length === 0) return errorResponse("not_found", 404, "not found");
        requireService(env, "telegram");
        return await handleTelegramWebhook(request, env, secret);
      }

      if (url.pathname.startsWith("/v1/telegram/")) {
        const action = url.pathname.slice("/v1/telegram/".length) as TelegramAction;
        if (!TELEGRAM_ACTIONS.has(action)) return errorResponse("not_found", 404, "not found");
        requireService(env, "telegram");
        return await handleTelegram(request, env, action);
      }

      return errorResponse("not_found", 404, "not found");
    } catch (error) {
      return toErrorResponse(error);
    }
  },

  async email(message: ForwardableEmailMessage, env: Env, ctx: ExecutionContext): Promise<void> {
    if (!enabledServices(env).mail) return;
    await handleInboundEmail(message, env, ctx);
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

export { Sandbox } from "@cloudflare/sandbox";
