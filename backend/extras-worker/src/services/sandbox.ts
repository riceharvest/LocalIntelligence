/**
 * Managed code sandbox routes (`POST /v1/sandbox/{create,info,start,stop,exec,write,read}`).
 * Built by job W1.
 */
import { requireCustomer } from "../auth";
import type { Env } from "../env";
import { HttpError } from "../http";

export type SandboxAction = "create" | "info" | "start" | "stop" | "exec" | "write" | "read";

export async function handleSandboxCreate(request: Request, env: Env): Promise<Response> {
  await requireCustomer(request, env);
  throw new HttpError(501, "not_built", "sandbox service is not built yet");
}

export async function handleSandboxInfo(request: Request, env: Env): Promise<Response> {
  await requireCustomer(request, env);
  throw new HttpError(501, "not_built", "sandbox service is not built yet");
}

export async function handleSandboxStart(request: Request, env: Env): Promise<Response> {
  await requireCustomer(request, env);
  throw new HttpError(501, "not_built", "sandbox service is not built yet");
}

export async function handleSandboxStop(request: Request, env: Env): Promise<Response> {
  await requireCustomer(request, env);
  throw new HttpError(501, "not_built", "sandbox service is not built yet");
}

export async function handleSandboxExec(request: Request, env: Env): Promise<Response> {
  await requireCustomer(request, env);
  throw new HttpError(501, "not_built", "sandbox service is not built yet");
}

export async function handleSandboxWrite(request: Request, env: Env): Promise<Response> {
  await requireCustomer(request, env);
  throw new HttpError(501, "not_built", "sandbox service is not built yet");
}

export async function handleSandboxRead(request: Request, env: Env): Promise<Response> {
  await requireCustomer(request, env);
  throw new HttpError(501, "not_built", "sandbox service is not built yet");
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
