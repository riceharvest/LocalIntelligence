/**
 * Managed code sandbox routes (`POST /v1/sandbox/{create,info,start,stop,exec,write,read}`).
 * Powered by `@cloudflare/sandbox` 1.0 and Cloudflare Containers (`durable_object` scheduling policy).
 *
 * Docs:
 * - Migration guide: https://developers.cloudflare.com/sandbox/sdk/migrate/
 * - Replace the Sandbox class: https://developers.cloudflare.com/sandbox/sdk/migrate/replace-the-sandbox-class/
 * - Change command calls: https://developers.cloudflare.com/sandbox/sdk/migrate/commands/
 * - Change file calls: https://developers.cloudflare.com/sandbox/sdk/migrate/files/
 * - Container API: https://developers.cloudflare.com/containers/api/durable-object-container/
 */
import { DurableObject } from "cloudflare:workers";
import { Files, SandboxFileError } from "@cloudflare/sandbox";
import { requireCustomer } from "../auth";
import type { Env } from "../env";
import { HttpError } from "../http";
import { addUsage, checkCap } from "../usage";

export type SandboxAction = "create" | "info" | "start" | "stop" | "exec" | "write" | "read";

// Inactivity timeout: container stops after 10 minutes of inactivity.
// Docs: https://developers.cloudflare.com/sandbox/sdk/migrate/replace-the-sandbox-class/
const INACTIVITY_TIMEOUT_MS = 10 * 60 * 1000;
const MAX_FILE_BYTES = 1024 * 1024; // 1 MB cap

function base64ByteLength(b64: string): number {
  if (b64.length === 0) return 0;
  const padding = b64.endsWith("==") ? 2 : b64.endsWith("=") ? 1 : 0;
  return Math.floor((b64.length * 3) / 4) - padding;
}

/**
 * Custom Durable Object class driving a Linux container instance per customer.
 * Uses the `durable_object` scheduling policy.
 * Docs: https://developers.cloudflare.com/sandbox/sdk/migrate/replace-the-sandbox-class/
 */
export class Sandbox extends DurableObject<Env> {
  private readonly files: Files;
  private setup: Promise<void> | null = null;

  constructor(ctx: DurableObjectState, env: Env) {
    super(ctx, env);
    const container = ctx.container;
    // A restarted Durable Object sets the inactivity timeout again.
    if (container?.running) {
      void ctx.blockConcurrencyWhile(() =>
        container.setInactivityTimeout(INACTIVITY_TIMEOUT_MS),
      );
    }
    this.files = new Files(this.container);
  }

  private get container(): Container {
    const container = this.ctx.container;
    if (!container) {
      throw new Error("No container is configured");
    }
    return container;
  }

  private ensureRunning(): Promise<void> {
    if (this.setup === null || !this.container.running) {
      this.setup = this.startContainer().catch((error) => {
        this.setup = null;
        throw error;
      });
    }
    return this.setup;
  }

  private async startContainer(): Promise<void> {
    const newContainer = !this.container.running;

    if (newContainer) {
      // Docs: https://developers.cloudflare.com/containers/api/durable-object-container/#start
      this.container.start({
        image: this.container.images.sandbox,
        instance: "lite",
        enableInternet: true,
      });
    }

    try {
      await this.container.setInactivityTimeout(INACTIVITY_TIMEOUT_MS);
    } catch (error) {
      await this.container.destroy("Failed to initialize container");
      throw error;
    }
  }

  async init(customerId: string): Promise<{ id: string; state: string; toolbox: null }> {
    await this.ensureRunning();
    return {
      id: `c-${customerId}`,
      state: "started",
      toolbox: null,
    };
  }

  async info(customerId: string): Promise<{ id: string; state: string; toolbox: null }> {
    return {
      id: `c-${customerId}`,
      state: this.container.running ? "started" : "stopped",
      toolbox: null,
    };
  }

  async start(): Promise<void> {
    await this.ensureRunning();
  }

  async stop(): Promise<void> {
    if (this.container.running) {
      await this.container.destroy("Stopped by customer");
    }
  }

  async exec(
    command: string,
    timeoutSeconds?: number,
  ): Promise<{ ok: boolean; result?: { exitCode: number; output: string }; error?: string; message?: string }> {
    try {
      await this.ensureRunning();
      // Coreutils timeout pattern from https://developers.cloudflare.com/sandbox/sdk/migrate/commands/#replace-timeouts
      const cmd = timeoutSeconds && timeoutSeconds > 0
        ? ["timeout", "--kill-after=5", String(timeoutSeconds), "bash", "-c", command]
        : ["bash", "-c", command];
      const process = await this.container.exec(cmd, { cwd: "/workspace" });
      const outputResult = await process.output();
      const stdout = new TextDecoder().decode(outputResult.stdout);
      const stderr = new TextDecoder().decode(outputResult.stderr);
      const output = stdout ? (stderr ? `${stdout}\n${stderr}` : stdout) : stderr;
      return {
        ok: true,
        result: {
          exitCode: outputResult.exitCode,
          output,
        },
      };
    } catch (err) {
      const msg = err instanceof Error ? err.message : String(err);
      return { ok: false, error: "exec_failed", message: `Sandbox exec failed: ${msg}` };
    }
  }

  async writeFile(
    path: string,
    contentBase64: string,
  ): Promise<{ ok: boolean; error?: string; message?: string }> {
    try {
      await this.ensureRunning();
      const bytes = Buffer.from(contentBase64, "base64");
      if (bytes.byteLength > MAX_FILE_BYTES) {
        return { ok: false, error: "file_too_large", message: "The file is larger than the 1 MB limit." };
      }

      // Docs: https://developers.cloudflare.com/sandbox/sdk/migrate/files/#write-into-a-new-directory
      const lastSlash = path.lastIndexOf("/");
      if (lastSlash > 0) {
        const dir = path.slice(0, lastSlash);
        try {
          await this.files.mkdir(dir, { recursive: true });
        } catch {
          // Ignore if directory already exists
        }
      }

      await this.files.writeFile(path, bytes);
      return { ok: true };
    } catch (err) {
      const msg = err instanceof Error ? err.message : String(err);
      return { ok: false, error: "write_failed", message: `Failed to write file: ${msg}` };
    }
  }

  async readFile(path: string): Promise<{ ok: boolean; content?: string; error?: string; message?: string }> {
    try {
      await this.ensureRunning();
      // Docs: https://developers.cloudflare.com/sandbox/sdk/migrate/files/#read-binary-files
      const response = await this.files.readFile(path);
      const buffer = await response.arrayBuffer();
      if (buffer.byteLength > MAX_FILE_BYTES) {
        return { ok: false, error: "file_too_large", message: "The file is larger than the 1 MB limit." };
      }
      return {
        ok: true,
        content: Buffer.from(buffer).toString("base64"),
      };
    } catch (err) {
      if (SandboxFileError.is(err) && (err.code === "ENOENT" || err.code === "ENOTDIR")) {
        return { ok: false, error: "not_found", message: "The file was not found." };
      }
      const msg = err instanceof Error ? err.message : String(err);
      if (msg.includes("ENOENT") || msg.toLowerCase().includes("not found")) {
        return { ok: false, error: "not_found", message: "The file was not found." };
      }
      return { ok: false, error: "read_failed", message: `Failed to read file: ${msg}` };
    }
  }
}

function getSandboxStub(env: Env, customerId: string): DurableObjectStub<Sandbox> {
  if (!env.Sandbox) {
    throw new HttpError(503, "service_off", "sandbox service is not configured");
  }
  // Reach the customer's sandbox Durable Object by scoped name.
  // Docs: https://developers.cloudflare.com/sandbox/sdk/migrate/replace-the-sandbox-class/
  return (env.Sandbox as DurableObjectNamespace<Sandbox>).getByName(`c-${customerId}`);
}

export async function handleSandboxCreate(request: Request, env: Env): Promise<Response> {
  const customer = await requireCustomer(request, env);
  await checkCap(env, customer.id, "sandbox_minutes", env.SANDBOX_MINUTES_CAP);
  const stub = getSandboxStub(env, customer.id);
  const res = await stub.init(customer.id);
  return Response.json(res);
}

export async function handleSandboxInfo(request: Request, env: Env): Promise<Response> {
  const customer = await requireCustomer(request, env);
  await checkCap(env, customer.id, "sandbox_minutes", env.SANDBOX_MINUTES_CAP);
  const stub = getSandboxStub(env, customer.id);
  const res = await stub.info(customer.id);
  return Response.json(res);
}

export async function handleSandboxStart(request: Request, env: Env): Promise<Response> {
  const customer = await requireCustomer(request, env);
  await checkCap(env, customer.id, "sandbox_minutes", env.SANDBOX_MINUTES_CAP);
  const stub = getSandboxStub(env, customer.id);
  await stub.start();
  return Response.json({ ok: true });
}

export async function handleSandboxStop(request: Request, env: Env): Promise<Response> {
  const customer = await requireCustomer(request, env);
  const stub = getSandboxStub(env, customer.id);
  await stub.stop();
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
  const stub = getSandboxStub(env, customer.id);
  const startMs = Date.now();
  try {
    const res = await stub.exec(command, timeoutSec);
    if (!res.ok) {
      throw new HttpError(500, res.error ?? "exec_failed", res.message ?? "Command execution failed");
    }
    return Response.json(res.result);
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

  const stub = getSandboxStub(env, customer.id);
  const res = await stub.writeFile(path, content);
  if (!res.ok) {
    if (res.error === "file_too_large") throw new HttpError(413, "file_too_large", res.message ?? "File too large");
    throw new HttpError(500, res.error ?? "write_failed", res.message ?? "Write failed");
  }
  return Response.json({ ok: true });
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

  const stub = getSandboxStub(env, customer.id);
  const res = await stub.readFile(path);
  if (!res.ok) {
    if (res.error === "not_found") throw new HttpError(404, "not_found", res.message ?? "File not found");
    if (res.error === "file_too_large") throw new HttpError(413, "file_too_large", res.message ?? "File too large");
    throw new HttpError(500, res.error ?? "read_failed", res.message ?? "Read failed");
  }
  return Response.json({ content: res.content });
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
