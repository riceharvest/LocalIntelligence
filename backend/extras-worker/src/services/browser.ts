/**
 * Managed cloud browser routes (`POST /v1/browser/{start,status,result}`).
 * Built by job W2.
 */
import { requireCustomer } from "../auth";
import type { Env } from "../env";
import { HttpError } from "../http";

export type BrowserAction = "start" | "status" | "result";

export async function handleBrowserStart(request: Request, env: Env): Promise<Response> {
  await requireCustomer(request, env);
  throw new HttpError(501, "not_built", "browser service is not built yet");
}

export async function handleBrowserStatus(request: Request, env: Env): Promise<Response> {
  await requireCustomer(request, env);
  throw new HttpError(501, "not_built", "browser service is not built yet");
}

export async function handleBrowserResult(request: Request, env: Env): Promise<Response> {
  await requireCustomer(request, env);
  throw new HttpError(501, "not_built", "browser service is not built yet");
}

export async function handleBrowser(request: Request, env: Env, action: BrowserAction): Promise<Response> {
  switch (action) {
    case "start":
      return handleBrowserStart(request, env);
    case "status":
      return handleBrowserStatus(request, env);
    case "result":
      return handleBrowserResult(request, env);
  }
}
