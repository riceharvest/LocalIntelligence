/**
 * Managed Composio MCP streamable HTTP proxy (`ALL /v1/mcp/composio`).
 *
 * Each customer gets a cached Tool Router session on Composio. The Worker proxies
 * MCP Streamable HTTP requests, strips customer authorization, attaches the project's
 * Composio API key, and meters tools/call invocations into monthly customer usage.
 */
import { requireCustomer } from "../auth";
import type { Env } from "../env";
import { ProviderError } from "../http";
import { addUsage, checkCap } from "../usage";

interface ComposioSession {
  sessionId: string;
  mcpUrl: string;
  fromCache: boolean;
}

export async function handleComposio(request: Request, env: Env): Promise<Response> {
  const customer = await requireCustomer(request, env);

  let bodyBytes: Uint8Array | undefined;
  let toolCallsCount = 0;

  if (request.method !== "GET" && request.method !== "HEAD") {
    const buf = await request.arrayBuffer();
    bodyBytes = new Uint8Array(buf);
    if (bodyBytes.length > 0) {
      try {
        const text = new TextDecoder().decode(bodyBytes);
        const parsed = JSON.parse(text);
        if (Array.isArray(parsed)) {
          for (const item of parsed) {
            if (item && typeof item === "object" && (item as Record<string, unknown>).method === "tools/call") {
              toolCallsCount++;
            }
          }
        } else if (parsed && typeof parsed === "object" && (parsed as Record<string, unknown>).method === "tools/call") {
          toolCallsCount = 1;
        }
      } catch {
        // Not a JSON-RPC request; forward body as-is.
      }
    }
  }

  if (toolCallsCount > 0) {
    await checkCap(env, customer.id, "composio_calls", env.COMPOSIO_CALLS_CAP);
  }

  let session = await getOrInitSession(env, customer.id);
  const incomingUrl = new URL(request.url);
  const proxyHeaders = buildProxyHeaders(request, env);

  // MCP Streamable HTTP transport: https://docs.composio.dev/docs/sessions-via-mcp
  let upstreamRes: Response;
  try {
    upstreamRes = await fetch(buildTargetUrl(session.mcpUrl, incomingUrl), {
      method: request.method,
      headers: proxyHeaders,
      body: bodyBytes && bodyBytes.length > 0 ? bodyBytes : undefined,
    });
  } catch {
    throw new ProviderError("composio", 503);
  }

  if (upstreamRes.status === 404 && session.fromCache) {
    // Stored session may have expired or been pruned on Composio; recreate once.
    await env.DB.prepare("DELETE FROM composio_sessions WHERE customer_id = ?1").bind(customer.id).run();
    session = await createSession(env, customer.id);
    try {
      upstreamRes = await fetch(buildTargetUrl(session.mcpUrl, incomingUrl), {
        method: request.method,
        headers: proxyHeaders,
        body: bodyBytes && bodyBytes.length > 0 ? bodyBytes : undefined,
      });
    } catch {
      throw new ProviderError("composio", 503);
    }
  }

  if (toolCallsCount > 0) {
    await addUsage(env, customer.id, "composio_calls", toolCallsCount);
  }

  const responseHeaders = new Headers(upstreamRes.headers);
  responseHeaders.delete("content-encoding");
  responseHeaders.delete("content-length");
  responseHeaders.delete("transfer-encoding");

  return new Response(upstreamRes.body, {
    status: upstreamRes.status,
    statusText: upstreamRes.statusText,
    headers: responseHeaders,
  });
}

async function getOrInitSession(env: Env, customerId: string): Promise<ComposioSession> {
  const row = await env.DB.prepare(
    "SELECT session_id, mcp_url FROM composio_sessions WHERE customer_id = ?1",
  )
    .bind(customerId)
    .first<{ session_id: string; mcp_url: string }>();

  if (row?.session_id && row?.mcp_url) {
    return { sessionId: row.session_id, mcpUrl: row.mcp_url, fromCache: true };
  }

  return await createSession(env, customerId);
}

// POST https://backend.composio.dev/api/v3.1/tool_router/session
// Docs: https://docs.composio.dev/docs/sessions-via-mcp
// Spec: https://docs.composio.dev/reference/api-reference/tool-router/postToolRouterSession
async function createSession(env: Env, customerId: string): Promise<ComposioSession> {
  let response: Response;
  try {
    response = await fetch("https://backend.composio.dev/api/v3.1/tool_router/session", {
      method: "POST",
      headers: {
        "content-type": "application/json",
        "x-api-key": env.COMPOSIO_API_KEY!,
      },
      body: JSON.stringify({ user_id: customerId }),
    });
  } catch {
    throw new ProviderError("composio", 503);
  }

  if (!response.ok) {
    throw new ProviderError("composio", response.status);
  }

  let data: {
    session_id?: string;
    id?: string;
    mcp?: { url?: string };
    mcp_url?: string;
    url?: string;
  };
  try {
    data = (await response.json()) as typeof data;
  } catch {
    throw new ProviderError("composio", response.status);
  }

  const sessionId = data.session_id ?? data.id;
  const mcpUrl = data.mcp?.url ?? data.mcp_url ?? (typeof data.url === "string" ? data.url : undefined);

  if (!sessionId || !mcpUrl) {
    throw new ProviderError("composio", response.status);
  }

  await env.DB.prepare(
    `INSERT INTO composio_sessions (customer_id, session_id, mcp_url) VALUES (?1, ?2, ?3)
       ON CONFLICT(customer_id) DO UPDATE SET session_id = excluded.session_id, mcp_url = excluded.mcp_url`,
  )
    .bind(customerId, sessionId, mcpUrl)
    .run();

  return { sessionId, mcpUrl, fromCache: false };
}

function buildProxyHeaders(request: Request, env: Env): Headers {
  const headers = new Headers();
  for (const [key, value] of request.headers.entries()) {
    const lower = key.toLowerCase();
    if (lower === "authorization" || lower === "host" || lower === "cf-connecting-ip" || lower === "cf-ray") {
      continue;
    }
    headers.set(key, value);
  }
  headers.set("x-api-key", env.COMPOSIO_API_KEY!);
  return headers;
}

function buildTargetUrl(targetBase: string, incomingUrl: URL): string {
  const url = new URL(targetBase);
  for (const [key, value] of incomingUrl.searchParams.entries()) {
    url.searchParams.set(key, value);
  }
  return url.toString();
}
