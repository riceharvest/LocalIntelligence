/**
 * Managed agent mailbox routes (`POST /v1/mail/{address,list,read,send}`) and the
 * Cloudflare Email Routing inbound `email()` handler. Built by job W5.
 *
 * Docs:
 * - Email Routing handler: https://developers.cloudflare.com/email-routing/email-workers/
 * - Email Routing API: https://developers.cloudflare.com/email-service/api/route-emails/email-handler/
 * - Email Sending Workers API: https://developers.cloudflare.com/email-service/api/send-emails/workers-api/
 * - postal-mime: https://www.npmjs.com/package/postal-mime
 */
import PostalMime, { type Email } from "postal-mime";
import { requireCustomer } from "../auth";
import type { Env } from "../env";
import { HttpError } from "../http";
import { addUsage, checkCap } from "../usage";

export type MailAction = "address" | "list" | "read" | "send";

const MAX_EMAIL_SIZE = 256 * 1024; // 256 KB cap
const CHARS = "abcdefghijklmnopqrstuvwxyz";

let messageCounter = 0;

/**
 * Returns a monotonic numeric id string (fits comfortably in JS Number and 64-bit integer)
 * for use as uid and primary key.
 */
function nextMessageId(): string {
  messageCounter = (messageCounter + 1) % 1000;
  return `${Date.now()}${messageCounter.toString().padStart(3, "0")}`;
}

/**
 * Generates 10 random lowercase characters for the customer's mailbox local part.
 */
function randomLocalPart(): string {
  const bytes = new Uint8Array(10);
  crypto.getRandomValues(bytes);
  let local = "";
  for (let i = 0; i < 10; i++) {
    const byte = bytes[i] ?? 0;
    local += CHARS[byte % CHARS.length] ?? "a";
  }
  return local;
}

/**
 * Retrieves the customer's mailbox address or provisions a new random 10-char local part.
 */
async function getOrCreateAddress(env: Env, customerId: string): Promise<string> {
  const domain = env.EMAIL_DOMAIN?.trim();
  if (!domain) {
    throw new HttpError(503, "service_off", "mail domain is not configured");
  }

  const existing = await env.DB.prepare(
    "SELECT local_part FROM mail_addresses WHERE customer_id = ?1",
  )
    .bind(customerId)
    .first<{ local_part: string }>();

  if (existing?.local_part) {
    return `${existing.local_part}@${domain}`;
  }

  for (let attempt = 0; attempt < 5; attempt++) {
    const local = randomLocalPart();
    try {
      await env.DB.prepare(
        "INSERT INTO mail_addresses (local_part, customer_id) VALUES (?1, ?2)",
      )
        .bind(local, customerId)
        .run();
      return `${local}@${domain}`;
    } catch {
      // Retry on local_part collision, or check if concurrent insert succeeded for customer_id
      const found = await env.DB.prepare(
        "SELECT local_part FROM mail_addresses WHERE customer_id = ?1",
      )
        .bind(customerId)
        .first<{ local_part: string }>();
      if (found?.local_part) {
        return `${found.local_part}@${domain}`;
      }
    }
  }

  throw new HttpError(500, "address_generation_failed", "Failed to generate mail address");
}

/**
 * Extracts the local part of an email address (e.g. from "local@domain.com" or "Name <local@domain.com>").
 */
function extractLocalPart(to: string): string {
  const match = to.match(/<([^>]+)>/);
  const matched = match?.[1];
  const addr = (matched ?? to).trim().toLowerCase();
  const atIdx = addr.indexOf("@");
  return atIdx >= 0 ? addr.slice(0, atIdx) : addr;
}

/**
 * Extracts plain-text body content from a parsed postal-mime email object.
 */
function extractBodyText(parsed: Email): string {
  if (parsed.text && parsed.text.trim().length > 0) {
    return parsed.text;
  }
  if (parsed.html) {
    return parsed.html
      .replace(/<style[^>]*>[\s\S]*?<\/style>/gi, "")
      .replace(/<script[^>]*>[\s\S]*?<\/script>/gi, "")
      .replace(/<br\s*\/?>/gi, "\n")
      .replace(/<\/p>/gi, "\n\n")
      .replace(/<[^>]+>/g, " ")
      .replace(/&nbsp;/gi, " ")
      .replace(/&amp;/gi, "&")
      .replace(/&lt;/gi, "<")
      .replace(/&gt;/gi, ">")
      .replace(/&quot;/gi, '"')
      .replace(/[ \t]+/g, " ")
      .trim();
  }
  return "";
}

/**
 * Returns the customer's mailbox address, provisioning one on first call.
 * `POST /v1/mail/address`
 */
export async function handleMailAddress(request: Request, env: Env): Promise<Response> {
  const customer = await requireCustomer(request, env);
  const address = await getOrCreateAddress(env, customer.id);
  return Response.json({ address });
}

/**
 * Lists the newest 50 messages for the customer without bodies.
 * `POST /v1/mail/list`
 */
export async function handleMailList(request: Request, env: Env): Promise<Response> {
  const customer = await requireCustomer(request, env);
  const rows = await env.DB.prepare(
    `SELECT id, direction, from_addr, to_addr, subject, received_at
     FROM mail_messages
     WHERE customer_id = ?1
     ORDER BY received_at DESC, id DESC
     LIMIT 50`,
  )
    .bind(customer.id)
    .all<{
      id: string;
      direction: string;
      from_addr: string;
      to_addr: string;
      subject: string;
      received_at: string;
    }>();

  const messages = (rows.results ?? []).map((r) => ({
    id: r.id,
    direction: r.direction,
    from: r.from_addr,
    to: r.to_addr,
    subject: r.subject,
    receivedAt: r.received_at,
  }));

  return Response.json({ messages });
}

/**
 * Reads a single message by id including body text.
 * `POST /v1/mail/read`
 */
export async function handleMailRead(request: Request, env: Env): Promise<Response> {
  const customer = await requireCustomer(request, env);
  const body = (await request.json().catch(() => ({}))) as {
    id?: string | number;
    uid?: string | number;
  };

  const rawId = body.id ?? body.uid;
  if (rawId === undefined || rawId === null || String(rawId).trim().length === 0) {
    throw new HttpError(400, "bad_request", "missing message id");
  }
  const id = String(rawId).trim();

  const message = await env.DB.prepare(
    `SELECT id, direction, from_addr, to_addr, subject, body_text, received_at
     FROM mail_messages
     WHERE id = ?1 AND customer_id = ?2`,
  )
    .bind(id, customer.id)
    .first<{
      id: string;
      direction: string;
      from_addr: string;
      to_addr: string;
      subject: string;
      body_text: string;
      received_at: string;
    }>();

  if (!message) {
    throw new HttpError(404, "not_found", "message not found");
  }

  return Response.json({
    id: message.id,
    direction: message.direction,
    from: message.from_addr,
    to: message.to_addr,
    subject: message.subject,
    body: message.body_text,
    text: message.body_text,
    receivedAt: message.received_at,
  });
}

/**
 * Sends an outbound email from the customer's mailbox address via the Cloudflare Email Sending binding.
 * `POST /v1/mail/send`
 */
export async function handleMailSend(request: Request, env: Env): Promise<Response> {
  const customer = await requireCustomer(request, env);
  await checkCap(env, customer.id, "mail_sends", env.MAIL_SENDS_CAP);

  if (!env.SEND_EMAIL) {
    throw new HttpError(503, "service_off", "email sending is not configured");
  }

  const body = (await request.json().catch(() => ({}))) as {
    to?: string;
    subject?: string;
    text?: string;
    body?: string;
  };

  const to = typeof body.to === "string" ? body.to.trim() : "";
  if (!to || !to.includes("@")) {
    throw new HttpError(400, "bad_request", "missing or invalid recipient email address");
  }
  const subject = typeof body.subject === "string" ? body.subject : "";
  const text = typeof body.text === "string" ? body.text : (typeof body.body === "string" ? body.body : "");

  const fromAddress = await getOrCreateAddress(env, customer.id);

  // Send email via Cloudflare Email Sending API (send_email binding)
  // Docs: https://developers.cloudflare.com/email-service/api/send-emails/workers-api/
  try {
    await env.SEND_EMAIL.send({
      from: fromAddress,
      to,
      subject,
      text,
    });
  } catch (err) {
    throw new HttpError(500, "send_failed", `Failed to send email: ${err instanceof Error ? err.message : String(err)}`);
  }

  await addUsage(env, customer.id, "mail_sends", 1);

  const id = nextMessageId();
  await env.DB.prepare(
    `INSERT INTO mail_messages (id, customer_id, direction, from_addr, to_addr, subject, body_text)
     VALUES (?1, ?2, 'out', ?3, ?4, ?5, ?6)`,
  )
    .bind(id, customer.id, fromAddress, to, subject, text)
    .run();

  return Response.json({ ok: true, id });
}

export async function handleMail(request: Request, env: Env, action: MailAction): Promise<Response> {
  switch (action) {
    case "address":
      return handleMailAddress(request, env);
    case "list":
      return handleMailList(request, env);
    case "read":
      return handleMailRead(request, env);
    case "send":
      return handleMailSend(request, env);
  }
}

/**
 * Inbound handler for Cloudflare Email Routing catch-all rule on `EMAIL_DOMAIN`.
 * Parses the incoming email with `postal-mime` and stores it into `mail_messages`.
 * Rejects unknown recipient addresses and messages larger than 256 KB.
 *
 * Docs:
 * - https://developers.cloudflare.com/email-routing/email-workers/
 * - https://developers.cloudflare.com/email-service/api/route-emails/email-handler/
 */
export async function handleInboundEmail(
  message: ForwardableEmailMessage,
  env: Env,
  _ctx: ExecutionContext,
): Promise<void> {
  // Reject messages exceeding 256 KB limit
  if (message.rawSize > MAX_EMAIL_SIZE) {
    message.setReject("Email exceeds size limit of 256 KB");
    return;
  }

  const localPart = extractLocalPart(message.to);
  const row = await env.DB.prepare(
    "SELECT customer_id FROM mail_addresses WHERE local_part = ?1",
  )
    .bind(localPart)
    .first<{ customer_id: string }>();

  if (!row) {
    message.setReject("Unknown mailbox address");
    return;
  }

  const raw = await new Response(message.raw).arrayBuffer();
  if (raw.byteLength > MAX_EMAIL_SIZE) {
    message.setReject("Email exceeds size limit of 256 KB");
    return;
  }

  // Parse raw email content using postal-mime
  // Docs: https://www.npmjs.com/package/postal-mime
  const parsed = await PostalMime.parse(raw);
  const subject = parsed.subject ?? message.headers.get("subject") ?? "";
  const bodyText = extractBodyText(parsed);

  const id = nextMessageId();
  await env.DB.prepare(
    `INSERT INTO mail_messages (id, customer_id, direction, from_addr, to_addr, subject, body_text)
     VALUES (?1, ?2, 'in', ?3, ?4, ?5, ?6)`,
  )
    .bind(id, row.customer_id, message.from, message.to, subject, bodyText)
    .run();
}
