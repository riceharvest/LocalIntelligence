/**
 * Managed Telegram routes (`POST /v1/telegram/{link,updates,send}` and
 * `POST /v1/telegram/webhook/<secret>`). Built by job W6.
 *
 * Endpoints:
 * - POST /v1/telegram/link: Generates a temporary deep link (15 min) for linking
 *   a Telegram chat to a customer via `https://t.me/<TELEGRAM_BOT_USERNAME>?start=<token>`.
 * - POST /v1/telegram/webhook/<secret>: Telegram Bot API webhook delivering inbound
 *   updates. /start <token> links chat_id -> customer and replies; other messages
 *   from linked chats go to telegram_inbox (keeping last 200 per customer).
 * - POST /v1/telegram/updates: Phone polls queued messages ({afterId}) and acknowledges delivery.
 * - POST /v1/telegram/send: Phone sends replies ({chatId, text}) to customer's linked chats.
 */
import { requireCustomer } from "../auth";
import { constantTimeEquals, generateAccessToken, sha256Hex } from "../crypto";
import type { Env } from "../env";
import { HttpError, ProviderError, json, jsonNoStore, readJsonObject, requireString } from "../http";

const LONG_POLL_MS = 25_000;
const LONG_POLL_STEP_MS = 2_000;

export type TelegramAction = "link" | "updates" | "send";

/**
 * Sends a message via Telegram Bot API.
 *
 * External API: POST https://api.telegram.org/bot<token>/sendMessage
 * Docs: https://core.telegram.org/bots/api#sendmessage
 */
async function sendTelegramMessage(botToken: string, chatId: number, text: string): Promise<void> {
  const res = await fetch(`https://api.telegram.org/bot${botToken}/sendMessage`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ chat_id: chatId, text }),
  });

  if (!res.ok) {
    throw new ProviderError("telegram", res.status);
  }
}

/**
 * Generates a deep link URL for linking a customer's Telegram chat to LocalIntelligence.
 */
export async function handleTelegramLink(request: Request, env: Env): Promise<Response> {
  const customer = await requireCustomer(request, env);
  if (!env.TELEGRAM_BOT_USERNAME) {
    throw new HttpError(503, "service_off", "telegram bot username is not configured");
  }

  // Generates 32 random bytes encoded as base64url without padding (43 characters)
  const linkToken = generateAccessToken();
  const tokenHash = await sha256Hex(linkToken);

  // Store token hash in telegram_links (valid 15 minutes)
  await env.DB.prepare(
    "INSERT INTO telegram_links (link_token_hash, customer_id) VALUES (?1, ?2)",
  )
    .bind(tokenHash, customer.id)
    .run();

  const botUsername = env.TELEGRAM_BOT_USERNAME.replace(/^@/, "").trim();
  const url = `https://t.me/${botUsername}?start=${linkToken}`;

  return jsonNoStore({ url });
}

/**
 * Inbound webhook from Telegram Bot API.
 *
 * External API: POST https://api.telegram.org/bot<token>/sendMessage
 * Docs: https://core.telegram.org/bots/api#sendmessage
 * Webhook spec: https://core.telegram.org/bots/api#setwebhook
 */
export async function handleTelegramWebhook(request: Request, env: Env, secret: string): Promise<Response> {
  const headerSecret = request.headers.get("x-telegram-bot-api-secret-token") ?? "";
  if (
    !env.TELEGRAM_WEBHOOK_SECRET ||
    !constantTimeEquals(secret, env.TELEGRAM_WEBHOOK_SECRET) ||
    !constantTimeEquals(headerSecret, env.TELEGRAM_WEBHOOK_SECRET)
  ) {
    throw new HttpError(404, "not_found", "not found");
  }

  const update = (await request.json().catch(() => null)) as Record<string, unknown> | null;
  if (!update || typeof update !== "object") {
    return json({ ok: true });
  }

  const message = update.message as Record<string, unknown> | undefined;
  if (!message || typeof message !== "object") {
    return json({ ok: true });
  }

  const chat = message.chat as Record<string, unknown> | undefined;
  if (!chat || typeof chat !== "object") {
    return json({ ok: true });
  }

  // Private chats only. A message that reaches the phone drives the customer's agent, so a
  // linked group would let every member of that group give it orders.
  if (chat.type !== "private") {
    return json({ ok: true });
  }

  const rawChatId = chat.id;
  const chatId = typeof rawChatId === "number" ? rawChatId : Number(rawChatId);
  if (!Number.isFinite(chatId)) {
    return json({ ok: true });
  }

  const title =
    typeof chat.title === "string" && chat.title.trim().length > 0
      ? chat.title.trim()
      : [chat.first_name, chat.last_name]
          .filter((s): s is string => typeof s === "string" && s.trim().length > 0)
          .join(" ")
          .trim() ||
        (typeof chat.username === "string" && chat.username.trim().length > 0
          ? `@${chat.username.trim()}`
          : String(chatId));

  const text = typeof message.text === "string" ? message.text.trim() : "";
  const startMatch = /^\/start(?:\s+(\S+))?$/.exec(text);

  if (startMatch && startMatch[1]) {
    const linkToken = startMatch[1].trim();
    const linkTokenHash = await sha256Hex(linkToken);

    const linkRow = await env.DB.prepare(
      "SELECT customer_id, created_at FROM telegram_links WHERE link_token_hash = ?1",
    )
      .bind(linkTokenHash)
      .first<{ customer_id: string; created_at: string }>();

    if (linkRow) {
      const createdMs = Date.parse(linkRow.created_at);
      const isFresh = Number.isFinite(createdMs) && Date.now() - createdMs <= 15 * 60 * 1000;

      await env.DB.prepare("DELETE FROM telegram_links WHERE link_token_hash = ?1")
        .bind(linkTokenHash)
        .run()
        .catch(() => {});

      if (isFresh) {
        await env.DB.prepare(
          `INSERT INTO telegram_chats (chat_id, customer_id, title) VALUES (?1, ?2, ?3)
             ON CONFLICT(chat_id) DO UPDATE SET customer_id = excluded.customer_id, title = excluded.title`,
        )
          .bind(chatId, linkRow.customer_id, title)
          .run();

        if (env.TELEGRAM_BOT_TOKEN) {
          try {
            // External API: POST https://api.telegram.org/bot<token>/sendMessage
            // Docs: https://core.telegram.org/bots/api#sendmessage
            await sendTelegramMessage(env.TELEGRAM_BOT_TOKEN, chatId, "Linked. Messages here now reach your phone.");
          } catch (e) {
            console.error("failed to send telegram link confirmation", e instanceof Error ? e.message : e);
          }
        }
        return json({ ok: true });
      }
    }
  }

  // Other messages from linked chats go to telegram_inbox
  const chatRow = await env.DB.prepare(
    "SELECT customer_id FROM telegram_chats WHERE chat_id = ?1",
  )
    .bind(chatId)
    .first<{ customer_id: string }>();

  if (chatRow) {
    const customerId = chatRow.customer_id;
    await env.DB.prepare(
      "INSERT INTO telegram_inbox (customer_id, chat_id, text) VALUES (?1, ?2, ?3)",
    )
      .bind(customerId, chatId, text)
      .run();

    // Keep last 200 per customer
    await env.DB.prepare(
      `DELETE FROM telegram_inbox
         WHERE customer_id = ?1
           AND id NOT IN (
             SELECT id FROM telegram_inbox
             WHERE customer_id = ?1
             ORDER BY id DESC
             LIMIT 200
           )`,
    )
      .bind(customerId)
      .run();
  }

  return json({ ok: true });
}

/**
 * Returns pending inbox messages after afterId, and deletes delivered rows (id <= afterId).
 */
export async function handleTelegramUpdates(request: Request, env: Env): Promise<Response> {
  const customer = await requireCustomer(request, env);
  const body = (await request.json().catch(() => ({}))) as { afterId?: unknown };
  const afterId =
    typeof body.afterId === "number" && Number.isFinite(body.afterId)
      ? body.afterId
      : typeof body.afterId === "string" && Number.isFinite(Number(body.afterId))
        ? Number(body.afterId)
        : 0;

  if (afterId > 0) {
    await env.DB.prepare(
      "DELETE FROM telegram_inbox WHERE customer_id = ?1 AND id <= ?2",
    )
      .bind(customer.id, afterId)
      .run();
  }

  // Long poll: hold the request up to LONG_POLL_MS while the inbox is empty, checking every
  // LONG_POLL_STEP_MS. Workers bill CPU time, not wall time, so waiting is nearly free, and the
  // phone makes ~2 requests a minute instead of one every few seconds.
  const deadline = Date.now() + LONG_POLL_MS;
  let result;
  for (;;) {
    result = await env.DB.prepare(
      "SELECT id, chat_id, text, received_at FROM telegram_inbox WHERE customer_id = ?1 AND id > ?2 ORDER BY id ASC LIMIT 100",
    )
      .bind(customer.id, afterId)
      .all<{ id: number; chat_id: number; text: string; received_at: string }>();
    if ((result.results ?? []).length > 0 || Date.now() + LONG_POLL_STEP_MS > deadline) break;
    await new Promise((resolve) => setTimeout(resolve, LONG_POLL_STEP_MS));
  }

  const updates = (result.results ?? []).map((row) => ({
    id: row.id,
    chatId: row.chat_id,
    text: row.text,
    receivedAt: row.received_at,
  }));

  return jsonNoStore({ updates });
}

/**
 * Sends a message to a customer's linked Telegram chat.
 *
 * External API: POST https://api.telegram.org/bot<token>/sendMessage
 * Docs: https://core.telegram.org/bots/api#sendmessage
 */
export async function handleTelegramSend(request: Request, env: Env): Promise<Response> {
  const customer = await requireCustomer(request, env);
  if (!env.TELEGRAM_BOT_TOKEN) {
    throw new HttpError(503, "service_off", "telegram bot token is not configured");
  }

  const body = await readJsonObject(request);
  const rawChatId = body.chatId;
  const chatId =
    typeof rawChatId === "number"
      ? rawChatId
      : typeof rawChatId === "string"
        ? parseInt(rawChatId, 10)
        : Number.NaN;
  if (!Number.isFinite(chatId)) {
    throw new HttpError(400, "invalid_request", "chatId is missing or invalid");
  }

  const text = requireString(body, "text", 1, 4096);

  const chatRow = await env.DB.prepare(
    "SELECT chat_id FROM telegram_chats WHERE chat_id = ?1 AND customer_id = ?2",
  )
    .bind(chatId, customer.id)
    .first<{ chat_id: number }>();

  if (!chatRow) {
    throw new HttpError(403, "forbidden", "chat is not linked to this account");
  }

  await sendTelegramMessage(env.TELEGRAM_BOT_TOKEN, chatId, text);

  return jsonNoStore({ ok: true });
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
