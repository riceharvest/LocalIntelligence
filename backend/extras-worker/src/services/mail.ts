/**
 * Managed agent mailbox routes (`POST /v1/mail/{address,list,read,send}`) and the
 * Cloudflare Email Routing inbound `email()` handler. Built by job W5.
 */
import { requireCustomer } from "../auth";
import type { Env } from "../env";
import { HttpError } from "../http";

export type MailAction = "address" | "list" | "read" | "send";

export async function handleMailAddress(request: Request, env: Env): Promise<Response> {
  await requireCustomer(request, env);
  throw new HttpError(501, "not_built", "mail service is not built yet");
}

export async function handleMailList(request: Request, env: Env): Promise<Response> {
  await requireCustomer(request, env);
  throw new HttpError(501, "not_built", "mail service is not built yet");
}

export async function handleMailRead(request: Request, env: Env): Promise<Response> {
  await requireCustomer(request, env);
  throw new HttpError(501, "not_built", "mail service is not built yet");
}

export async function handleMailSend(request: Request, env: Env): Promise<Response> {
  await requireCustomer(request, env);
  throw new HttpError(501, "not_built", "mail service is not built yet");
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

export async function handleInboundEmail(
  _message: ForwardableEmailMessage,
  _env: Env,
  _ctx: ExecutionContext,
): Promise<void> {
  // Stub until job W5 implements inbound Email Routing delivery.
}
