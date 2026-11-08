/**
 * Managed Telegram routes (`POST /v1/telegram/{link,updates,send}` and
 * `POST /v1/telegram/webhook/<secret>`). Built by job W6.
 */
import { requireCustomer } from "../auth";
import { constantTimeEquals } from "../crypto";
import type { Env } from "../env";
import { HttpError } from "../http";

export type TelegramAction = "link" | "updates" | "send";

export async function handleTelegramLink(request: Request, env: Env): Promise<Response> {
  await requireCustomer(request, env);
  throw new HttpError(501, "not_built", "telegram service is not built yet");
}

export async function handleTelegramUpdates(request: Request, env: Env): Promise<Response> {
  await requireCustomer(request, env);
  throw new HttpError(501, "not_built", "telegram service is not built yet");
}

export async function handleTelegramSend(request: Request, env: Env): Promise<Response> {
  await requireCustomer(request, env);
  throw new HttpError(501, "not_built", "telegram service is not built yet");
}

export async function handleTelegramWebhook(request: Request, env: Env, secret: string): Promise<Response> {
  void request;
  if (!env.TELEGRAM_WEBHOOK_SECRET || !constantTimeEquals(secret, env.TELEGRAM_WEBHOOK_SECRET)) {
    throw new HttpError(404, "not_found", "not found");
  }
  throw new HttpError(501, "not_built", "telegram service is not built yet");
}

export async function handleTelegram(request: Request, env: Env, action: TelegramAction): Promise<Response> {
  switch (action) {
    case "link":
      return handleTelegramLink(request, env);
    case "updates":
      return handleTelegramUpdates(request, env);
    case "send":
      return handleTelegramSend(request, env);
  }
}
