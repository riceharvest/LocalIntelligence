/**
 * Managed code sandbox routes (`POST /v1/sandbox/{create,info,start,stop,exec,write,read}`).
 * Powered by `@cloudflare/sandbox` (latest stable 0.12.x).
 *
 * Docs:
 * - Lifecycle: https://developers.cloudflare.com/sandbox/sdk/api/lifecycle/
 * - Commands: https://developers.cloudflare.com/sandbox/sdk/api/commands/
 * - Files: https://developers.cloudflare.com/sandbox/sdk/api/files/
 */
import { getSandbox, Sandbox } from "@cloudflare/sandbox";
import { requireCustomer } from "../auth";
import type { Env } from "../env";
import { HttpError } from "../http";
import { addUsage, checkCap } from "../usage";

export type SandboxAction = "create" | "info" | "start" | "stop" | "exec" | "write" | "read";

const MAX_FILE_BYTES = 1024 * 1024; // 1 MB cap

function base64ByteLength(b64: string): number {
  if (b64.length === 0) return 0;
  const padding = b64.endsWith("==") ? 2 : b64.endsWith("=") ? 1 : 0;
  return Math.floor((b64.length * 3) / 4) - padding;
}

function getCustomerSandbox(env: Env, customerId: string): Sandbox {
  if (!env.Sandbox) {
    throw new HttpError(503, "service_off", "sandbox service is not configured");
  }
  // Docs: https://developers.cloudflare.com/sandbox/sdk/api/lifecycle/#getsandbox
  return getSandbox(env.Sandbox as DurableObjectNamespace<Sandbox>, `c-${customerId}`, {
    sleepAfter: "10m",
  });
}

export async function handleSandboxCreate(request: Request, env: Env): Promise<Response> {
  const customer = await requireCustomer(request, env);
  await checkCap(env, customer.id, "sandbox_minutes", env.SANDBOX_MINUTES_CAP);
  getCustomerSandbox(env, customer.id);
  return Response.json({
    id: `c-${customer.id}`,
    state: "started",
    toolbox: null,
  });
}

export async function handleSandboxInfo(request: Request, env: Env): Promise<Response> {
  const customer = await requireCustomer(request, env);
  await checkCap(env, customer.id, "sandbox_minutes", env.SANDBOX_MINUTES_CAP);
  getCustomerSandbox(env, customer.id);
  return Response.json({
    id: `c-${customer.id}`,
    state: "started",
    toolbox: null,
  });
}

export async function handleSandboxStart(request: Request, env: Env): Promise<Response> {
  const customer = await requireCustomer(request, env);
  await checkCap(env, customer.id, "sandbox_minutes", env.SANDBOX_MINUTES_CAP);
  getCustomerSandbox(env, customer.id);
  return Response.json({ ok: true });
}

export async function handleSandboxStop(request: Request, env: Env): Promise<Response> {
  const customer = await requireCustomer(request, env);
  const sandbox = getCustomerSandbox(env, customer.id);
  try {
    await sandbox.stop();
  } catch {
    // Best effort: container also sleeps after 10m idle
  }
  return Response.json({ ok: true });
}

export async function handleSandboxExec(request: Request, env: Env): Promise<Response> {
  const customer = await requireCustomer(request, env);
  await checkCap(env, customer.id, "sandbox_minutes", env.SANDBOX_MINUTES_CAP);

  const body = (await request.json().catch(() => ({}))) as {
    command?: string;
    timeoutSeconds?: number;
    timeout?: number;
  };
  const command = body.command;
  if (!command || typeof command !== "string") {
    throw new HttpError(400, "bad_request", "missing or invalid command");
  }

  const timeoutSec = body.timeoutSeconds ?? (body.timeout ? Math.round(body.timeout / 1000) : undefined);
  const timeoutMs = timeoutSec && timeoutSec > 0 ? timeoutSec * 1000 : undefined;

  const sandbox = getCustomerSandbox(env, customer.id);
  const startMs = Date.now();
  try {
    // Docs: https://developers.cloudflare.com/sandbox/sdk/api/commands/#exec
    const result = await sandbox.exec(command, timeoutMs ? { timeout: timeoutMs } : undefined);
    const stdout = result.stdout ?? "";
    const stderr = result.stderr ?? "";
    const output = stdout ? (stderr ? `${stdout}\n${stderr}` : stdout) : stderr;
    return Response.json({
      exitCode: result.exitCode ?? (result.success ? 0 : 1),
      output,
    });
  } catch (err) {
    throw err instanceof HttpError
      ? err
      : new HttpError(500, "exec_failed", `Sandbox exec failed: ${err instanceof Error ? err.message : String(err)}`);
  } finally {
    const elapsedSeconds = Math.max(0.001, (Date.now() - startMs) / 1000);
    await addUsage(env, customer.id, "sandbox_minutes", elapsedSeconds / 60);
  }
}

export async function handleSandboxWrite(request: Request, env: Env): Promise<Response> {
  const customer = await requireCustomer(request, env);
  await checkCap(env, customer.id, "sandbox_minutes", env.SANDBOX_MINUTES_CAP);

  const body = (await request.json().catch(() => ({}))) as {
    path?: string;
    content?: string;
  };
  const path = body.path;
  const content = body.content;
  if (!path || typeof path !== "string") {
    throw new HttpError(400, "bad_request", "missing or invalid path");
  }
  if (typeof content !== "string") {
    throw new HttpError(400, "bad_request", "missing or invalid content");
  }
  if (base64ByteLength(content) > MAX_FILE_BYTES) {
    throw new HttpError(413, "file_too_large", "The file is larger than the 1 MB limit.");
  }

  const sandbox = getCustomerSandbox(env, customer.id);
  const lastSlash = path.lastIndexOf("/");
  if (lastSlash > 0) {
    const dir = path.slice(0, lastSlash);
    try {
      await sandbox.mkdir(dir, { recursive: true });
    } catch {
      // Ignore if directory already exists
    }
  }

  try {
    // Docs: https://developers.cloudflare.com/sandbox/sdk/api/files/#writefile
    await sandbox.writeFile(path, content, { encoding: "base64" });
    return Response.json({ ok: true });
  } catch (err) {
    throw err instanceof HttpError
      ? err
      : new HttpError(500, "write_failed", `Failed to write file: ${err instanceof Error ? err.message : String(err)}`);
  }
}

export async function handleSandboxRead(request: Request, env: Env): Promise<Response> {
  const customer = await requireCustomer(request, env);
  await checkCap(env, customer.id, "sandbox_minutes", env.SANDBOX_MINUTES_CAP);

  const body = (await request.json().catch(() => ({}))) as {
    path?: string;
  };
  const path = body.path;
  if (!path || typeof path !== "string") {
    throw new HttpError(400, "bad_request", "missing or invalid path");
  }

  const sandbox = getCustomerSandbox(env, customer.id);
  try {
    // Docs: https://developers.cloudflare.com/sandbox/sdk/api/files/#readfile
    const file = await sandbox.readFile(path, { encoding: "base64" });
    const content = file.content ?? "";
    if (base64ByteLength(content) > MAX_FILE_BYTES) {
      throw new HttpError(413, "file_too_large", "The file is larger than the 1 MB limit.");
    }
    return Response.json({ content });
  } catch (err) {
    if (err instanceof HttpError) throw err;
    const msg = err instanceof Error ? err.message : String(err);
    if (msg.includes("ENOENT") || msg.toLowerCase().includes("not found")) {
      throw new HttpError(404, "not_found", "The file was not found.");
    }
    throw new HttpError(500, "read_failed", `Failed to read file: ${msg}`);
  }
}

export async function handleSandbox(request: Request, env: Env, action: SandboxAction): Promise<Response> {
  switch (action) {
    case "create":
      return handleSandboxCreate(request, env);
    case "info":
      return handleSandboxInfo(request, env);
    case "start":
      return handleSandboxStart(request, env);
    case "stop":
      return handleSandboxStop(request, env);
    case "exec":
      return handleSandboxExec(request, env);
    case "write":
      return handleSandboxWrite(request, env);
    case "read":
      return handleSandboxRead(request, env);
  }
}
