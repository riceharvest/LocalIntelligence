/**
 * Managed Composio MCP streamable HTTP proxy (`ALL /v1/mcp/composio`).
 * Built by job W3.
 */
import { requireCustomer } from "../auth";
import type { Env } from "../env";
import { HttpError } from "../http";

export async function handleComposio(request: Request, env: Env): Promise<Response> {
  await requireCustomer(request, env);
  throw new HttpError(501, "not_built", "composio service is not built yet");
}
