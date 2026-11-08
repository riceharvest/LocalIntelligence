/**
 * Managed cloud browser routes (`POST /v1/browser/{start,status,result}`) and the
 * `BrowserTask` Durable Object that drives each task via Stagehand + `@cloudflare/playwright`.
 *
 * Docs:
 * - Stagehand on Cloudflare Browser Run (`@browserbasehq/stagehand` 2.5.x + `@cloudflare/playwright`):
 *   https://developers.cloudflare.com/browser-run/stagehand/
 * - Playwright on Cloudflare Browser Run (`endpointURLString`, `keep_alive` inactivity timeout):
 *   https://developers.cloudflare.com/browser-run/playwright/
 * - Browser Run session and keep-alive limits (60 s default idle timeout, extendable up to 10–20 min):
 *   https://developers.cloudflare.com/browser-run/limits/
 *
 * Note on `stagehand.agent()`: in `@browserbasehq/stagehand@2.5.9` (`dist/index.js` lines 23717–23733),
 * `StagehandAgentHandler` requires `llmClient.getLanguageModel()` and `llmClient.generateText()` (an
 * AI SDK-backed client) and throws when given a custom `LLMClient` subclass following `workersAIClient.ts`.
 * Tasks therefore use the `page.observe()` -> `page.act()` loop (up to 25 steps / 5 minutes) followed
 * by `page.extract()`.
 */
import { Stagehand } from "@browserbasehq/stagehand";
import { endpointURLString } from "@cloudflare/playwright";
import { z } from "zod";
import { requireCustomer } from "../auth";
import { decryptSecret } from "../crypto";
import type { Env } from "../env";
import { HttpError, jsonNoStore, readJsonObject, requireString } from "../http";
import { addUsage, checkCap } from "../usage";
import { OpenRouterClient } from "./browser/openRouterClient";

export type BrowserAction = "start" | "status" | "result";

type TaskState = "running" | "completed" | "failed" | "cancelled";

interface StoredTask {
  id: string;
  customerId: string;
  state: TaskState;
  result: string | null;
  error: string | null;
  startedAt: number;
  finishedAt: number | null;
}

interface ActiveCustomerTask {
  taskId: string;
  startedAt: number;
}

interface DoStartBody {
  id: string;
  customerId: string;
  instruction: string;
  openrouterKey: string;
}

const MAX_INSTRUCTION_CHARS = 4000;
const MAX_STEPS = 25;
/** Maximum wall-clock duration of one task (5 minutes), well inside Browser Rendering's keep_alive limit. */
const MAX_TASK_DURATION_MS = 5 * 60 * 1000;
/**
 * Browser Rendering `keep_alive` inactivity timeout in milliseconds (5 minutes).
 * Docs: https://developers.cloudflare.com/browser-run/playwright/#keep-alive
 *       https://developers.cloudflare.com/browser-run/limits/
 * `@cloudflare/playwright` `WorkersLaunchOptions.keep_alive` supports 10_000..600_000 ms.
 */
const SESSION_KEEP_ALIVE_MS = 5 * 60 * 1000;
const TASK_ID_REGEX = /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/;
const DEFAULT_START_URL = "https://duckduckgo.com";

function requireTaskNamespace(env: Env): DurableObjectNamespace {
  if (!env.BROWSER_TASK) {
    throw new HttpError(503, "service_off", "this Extras service is not enabled");
  }
  return env.BROWSER_TASK;
}

function extractFirstHttpUrl(instruction: string): string | null {
  const match = instruction.match(/https?:\/\/[^\s"'<>]+/i);
  if (!match) return null;
  const candidate = match[0].replace(/[.,;:!?)]+$/, "");
  try {
    const parsed = new URL(candidate);
    if (parsed.protocol === "http:" || parsed.protocol === "https:") {
      return parsed.toString();
    }
  } catch {
    // Ignore malformed URLs in free-form text and fall back to default search page.
  }
  return null;
}

function customerLockStub(namespace: DurableObjectNamespace, customerId: string): DurableObjectStub {
  return namespace.get(namespace.idFromName(`customer:${customerId}`));
}

function taskStub(namespace: DurableObjectNamespace, taskId: string): DurableObjectStub {
  return namespace.get(namespace.idFromName(taskId));
}

/**
 * Durable Object that runs a single browser task (when addressed by `taskId`) or tracks a
 * customer's currently running task (when addressed by `customer:<customerId>`).
 */
export class BrowserTask implements DurableObject {
  constructor(
    private readonly state: DurableObjectState,
    private readonly env: Env,
  ) {}

  async fetch(request: Request): Promise<Response> {
    const url = new URL(request.url);
    if (request.method !== "POST") {
      return new Response("method not allowed", { status: 405 });
    }

    if (url.pathname === "/claim") {
      const body = (await request.json()) as { taskId: string };
      return this.handleClaim(body.taskId);
    }

    if (url.pathname === "/release") {
      const body = (await request.json()) as { taskId: string };
      return this.handleRelease(body.taskId);
    }

    if (url.pathname === "/start") {
      const body = (await request.json()) as DoStartBody;
      return this.handleStart(body);
    }

    if (url.pathname === "/get") {
      const stored = await this.state.storage.get<StoredTask>("task");
      if (!stored) {
        return new Response(JSON.stringify({ found: false }), {
          status: 404,
          headers: { "content-type": "application/json" },
        });
      }
      return new Response(JSON.stringify({ found: true, task: stored }), {
        status: 200,
        headers: { "content-type": "application/json" },
      });
    }

    return new Response("not found", { status: 404 });
  }

  private async handleClaim(taskId: string): Promise<Response> {
    const current = await this.state.storage.get<ActiveCustomerTask>("active");
    if (current && Date.now() - current.startedAt < MAX_TASK_DURATION_MS + 30_000) {
      const namespace = this.env.BROWSER_TASK;
      if (namespace) {
        const res = await taskStub(namespace, current.taskId).fetch("https://do.internal/get", {
          method: "POST",
        });
        if (res.ok) {
          const data = (await res.json()) as { found: boolean; task?: StoredTask };
          if (data.found && data.task?.state === "running") {
            return new Response(JSON.stringify({ ok: false }), {
              status: 409,
              headers: { "content-type": "application/json" },
            });
          }
        }
      }
    }
    await this.state.storage.put<ActiveCustomerTask>("active", {
      taskId,
      startedAt: Date.now(),
    });
    return new Response(JSON.stringify({ ok: true }), {
      status: 200,
      headers: { "content-type": "application/json" },
    });
  }

  private async handleRelease(taskId: string): Promise<Response> {
    const current = await this.state.storage.get<ActiveCustomerTask>("active");
    if (current && current.taskId === taskId) {
      await this.state.storage.delete("active");
    }
    return new Response(JSON.stringify({ ok: true }), {
      status: 200,
      headers: { "content-type": "application/json" },
    });
  }

  private async handleStart(body: DoStartBody): Promise<Response> {
    const initial: StoredTask = {
      id: body.id,
      customerId: body.customerId,
      state: "running",
      result: null,
      error: null,
      startedAt: Date.now(),
      finishedAt: null,
    };
    await this.state.storage.put<StoredTask>("task", initial);
    this.state.waitUntil(this.runTask(initial, body.instruction, body.openrouterKey));
    return new Response(JSON.stringify({ id: initial.id, status: initial.state }), {
      status: 200,
      headers: { "content-type": "application/json" },
    });
  }

  private async runTask(initial: StoredTask, instruction: string, openrouterKey: string): Promise<void> {
    const startedAt = Date.now();
    let stagehand: Stagehand | null = null;
    try {
      if (!this.env.BROWSER) {
        throw new Error("BROWSER binding is not configured");
      }
      // Build the Cloudflare Browser Rendering CDP URL with keep_alive bounded to 5 minutes.
      // Docs: https://developers.cloudflare.com/browser-run/stagehand/
      //       https://developers.cloudflare.com/browser-run/playwright/#keep-alive
      const rawCdpUrl = endpointURLString(this.env.BROWSER, { keep_alive: SESSION_KEEP_ALIVE_MS });
      const cdpUrlObj = new URL(rawCdpUrl);
      cdpUrlObj.searchParams.set("keep_alive", String(SESSION_KEEP_ALIVE_MS));

      // Stagehand initialization on Cloudflare Browser Rendering with custom LLMClient:
      // Docs: https://developers.cloudflare.com/browser-run/stagehand/
      stagehand = new Stagehand({
        env: "LOCAL",
        localBrowserLaunchOptions: { cdpUrl: cdpUrlObj.toString() },
        llmClient: new OpenRouterClient({
          apiKey: openrouterKey,
          model: this.env.DEFAULT_MODEL,
        }),
        disablePino: true,
        verbose: 0,
        logger: () => {},
      });

      await stagehand.init();
      const page = stagehand.page;

      const startUrl = extractFirstHttpUrl(instruction) ?? DEFAULT_START_URL;
      await page.goto(startUrl, { waitUntil: "domcontentloaded" });

      // Observe -> act loop bounded to 25 steps and 5 minutes.
      // Docs: https://developers.cloudflare.com/browser-run/stagehand/#3-write-the-worker-code
      for (let step = 0; step < MAX_STEPS; step++) {
        if (Date.now() - startedAt >= MAX_TASK_DURATION_MS) break;
        const actions = await page.observe(
          `Goal: ${instruction}\nStep ${step + 1} of ${MAX_STEPS}. Identify the next browser action needed to complete the goal, or return no elements if the page already has the answer or the goal is complete.`,
        );
        if (!actions || actions.length === 0) break;
        const nextAction = actions[0];
        if (!nextAction) break;
        await page.act(nextAction);
      }

      // Extract a plain-text answer from the final page state.
      // Docs: https://developers.cloudflare.com/browser-run/stagehand/#3-write-the-worker-code
      const extracted = await page.extract({
        instruction: `Answer this request in plain text using the current page: ${instruction}`,
        schema: z.object({
          answer: z.string().describe("Plain-text answer or summary of the completed browser task"),
        }),
      });

      const answer = extracted.answer.trim() || "The browser task finished with no text output.";
      await this.state.storage.put<StoredTask>("task", {
        ...initial,
        state: "completed",
        result: answer,
        error: null,
        finishedAt: Date.now(),
      });
    } catch {
      await this.state.storage.put<StoredTask>("task", {
        ...initial,
        state: "failed",
        result: null,
        error: "The browser task could not finish. Try again with a simpler instruction.",
        finishedAt: Date.now(),
      });
    } finally {
      if (stagehand) {
        try {
          await stagehand.close();
        } catch {
          // Ignore close errors after task completion/timeout.
        }
      }
      const wallMinutes = Math.max(0.01, (Date.now() - startedAt) / 60_000);
      try {
        await addUsage(this.env, initial.customerId, "browser_minutes", wallMinutes);
      } catch {
        // Usage write best-effort in background DO teardown.
      }
      if (this.env.BROWSER_TASK) {
        try {
          await customerLockStub(this.env.BROWSER_TASK, initial.customerId).fetch(
            "https://do.internal/release",
            {
              method: "POST",
              headers: { "content-type": "application/json" },
              body: JSON.stringify({ taskId: initial.id }),
            },
          );
        } catch {
          // Lock auto-expires after MAX_TASK_DURATION_MS + 30s if release fails.
        }
      }
    }
  }
}

async function readTaskForCustomer(
  env: Env,
  customerId: string,
  taskId: string,
): Promise<StoredTask> {
  if (!TASK_ID_REGEX.test(taskId)) {
    throw new HttpError(400, "invalid_request", "id is missing or malformed");
  }
  const namespace = requireTaskNamespace(env);
  const res = await taskStub(namespace, taskId).fetch("https://do.internal/get", {
    method: "POST",
  });
  if (!res.ok) {
    throw new HttpError(404, "not_found", "no such browser task");
  }
  const payload = (await res.json()) as { found: boolean; task?: StoredTask };
  if (!payload.found || !payload.task || payload.task.customerId !== customerId) {
    throw new HttpError(404, "not_found", "no such browser task");
  }
  return payload.task;
}

export async function handleBrowserStart(request: Request, env: Env): Promise<Response> {
  const customer = await requireCustomer(request, env);
  await checkCap(env, customer.id, "browser_minutes", env.BROWSER_MINUTES_CAP);

  const body = await readJsonObject(request);
  const instruction = requireString(body, "instruction", 1, MAX_INSTRUCTION_CHARS).trim();
  if (instruction.length === 0) {
    throw new HttpError(400, "invalid_request", "instruction is missing or malformed");
  }
  if (!customer.openrouter_key_enc) {
    throw new HttpError(503, "provider_unavailable", "Extras chat key is not available for this account");
  }

  const openrouterKey = await decryptSecret(customer.openrouter_key_enc, env.CREDENTIAL_ENCRYPTION_KEY);
  const namespace = requireTaskNamespace(env);
  const taskId = crypto.randomUUID();

  const claimRes = await customerLockStub(namespace, customer.id).fetch("https://do.internal/claim", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ taskId }),
  });
  if (claimRes.status === 409) {
    throw new HttpError(
      409,
      "task_running",
      "A browser task is already running. Wait for it to finish before starting another.",
    );
  }
  if (!claimRes.ok) {
    throw new HttpError(503, "provider_unavailable", "Extras is unavailable right now, try again later");
  }

  const startBody: DoStartBody = {
    id: taskId,
    customerId: customer.id,
    instruction,
    openrouterKey,
  };
  const startRes = await taskStub(namespace, taskId).fetch("https://do.internal/start", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(startBody),
  });
  if (!startRes.ok) {
    await customerLockStub(namespace, customer.id).fetch("https://do.internal/release", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ taskId }),
    });
    throw new HttpError(503, "provider_unavailable", "Extras is unavailable right now, try again later");
  }

  return jsonNoStore({ id: taskId, status: "running" });
}

export async function handleBrowserStatus(request: Request, env: Env): Promise<Response> {
  const customer = await requireCustomer(request, env);
  const body = await readJsonObject(request);
  const id = requireString(body, "id", 36, 36);
  const task = await readTaskForCustomer(env, customer.id, id);
  return jsonNoStore({ id: task.id, status: task.state });
}

export async function handleBrowserResult(request: Request, env: Env): Promise<Response> {
  const customer = await requireCustomer(request, env);
  const body = await readJsonObject(request);
  const id = requireString(body, "id", 36, 36);
  const task = await readTaskForCustomer(env, customer.id, id);
  return jsonNoStore({
    id: task.id,
    status: task.state,
    result: task.result,
    error: task.error,
  });
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
