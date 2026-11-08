/**
 * Which proxied Extras services are configured on this Worker. A service with any missing
 * binding, secret or variable reports `false` in the grant and answers 503 `service_off`.
 */
import type { Env } from "../env";
import { HttpError } from "../http";

export interface EnabledServices {
  sandbox: boolean;
  browser: boolean;
  composio: boolean;
  voice: boolean;
  mail: boolean;
  telegram: boolean;
}

export type ServiceName = keyof EnabledServices;

function hasText(value: string | undefined): boolean {
  return typeof value === "string" && value.trim().length > 0;
}

function hasPositiveCap(value: string | undefined): boolean {
  if (!hasText(value)) return false;
  const n = Number(value);
  return Number.isFinite(n) && n > 0;
}

export function enabledServices(env: Env): EnabledServices {
  return {
    sandbox: Boolean(env.Sandbox && hasPositiveCap(env.SANDBOX_MINUTES_CAP)),
    browser: Boolean(env.BROWSER && env.BROWSER_TASK && hasPositiveCap(env.BROWSER_MINUTES_CAP)),
    composio: Boolean(hasText(env.COMPOSIO_API_KEY) && hasPositiveCap(env.COMPOSIO_CALLS_CAP)),
    voice: Boolean(
      hasText(env.DEEPGRAM_API_KEY) &&
        hasText(env.DEEPGRAM_PROJECT_ID) &&
        hasPositiveCap(env.VOICE_STT_MINUTES_CAP) &&
        hasPositiveCap(env.VOICE_TTS_CHARS_CAP),
    ),
    mail: Boolean(env.SEND_EMAIL && hasText(env.EMAIL_DOMAIN) && hasPositiveCap(env.MAIL_SENDS_CAP)),
    telegram: Boolean(
      hasText(env.TELEGRAM_BOT_TOKEN) &&
        hasText(env.TELEGRAM_WEBHOOK_SECRET) &&
        hasText(env.TELEGRAM_BOT_USERNAME),
    ),
  };
}

export function requireService(env: Env, service: ServiceName): void {
  if (!enabledServices(env)[service]) {
    throw new HttpError(503, "service_off", "this Extras service is not enabled");
  }
}
