/**
 * Custom Stagehand LLMClient that calls OpenRouter's Chat Completions API with the
 * customer's own Extras OpenRouter key, so browser task LLM usage counts toward the
 * customer's monthly OpenRouter cap.
 *
 * Patterned on Cloudflare's official Stagehand `workersAIClient.ts` guide:
 *   https://developers.cloudflare.com/browser-run/stagehand/
 *   https://github.com/cloudflare/playwright/blob/main/packages/playwright-cloudflare/examples/stagehand/src/worker/workersAIClient.ts
 *
 * OpenRouter Chat Completions and Structured Outputs docs:
 *   https://openrouter.ai/docs/api-reference/chat-completion
 *   https://openrouter.ai/docs/guides/features/structured-outputs
 */
import {
  type AvailableModel,
  type CreateChatCompletionOptions,
  LLMClient,
  type LogLine,
} from "@browserbasehq/stagehand";
import zodToJsonSchema from "zod-to-json-schema";
import { ProviderError } from "../../http";

const OPENROUTER_CHAT_COMPLETIONS_URL = "https://openrouter.ai/api/v1/chat/completions";

export interface OpenRouterClientOptions {
  apiKey: string;
  model: string;
  logger?: (line: LogLine) => void;
}

function stripMarkdownJsonFence(text: string): string {
  const trimmed = text.trim();
  if (!trimmed.startsWith("```")) return trimmed;
  return trimmed
    .replace(/^```(?:json)?\s*/i, "")
    .replace(/\s*```$/, "")
    .trim();
}

export class OpenRouterClient extends LLMClient {
  public type = "openrouter" as const;
  private readonly apiKey: string;
  private readonly logger?: (line: LogLine) => void;

  constructor(options: OpenRouterClientOptions) {
    super(options.model as AvailableModel);
    this.apiKey = options.apiKey;
    this.logger = options.logger;
  }

  async createChatCompletion<T>({ options }: CreateChatCompletionOptions): Promise<T> {
    const schema = options.response_model?.schema;
    this.logger?.({ category: "openrouter", message: "thinking..." });

    const tools = options.tools?.map((tool) => ({
      type: "function" as const,
      function: {
        name: tool.name,
        description: tool.description,
        parameters: tool.parameters,
      },
    }));

    const responseFormat = schema
      ? {
          type: "json_schema" as const,
          json_schema: {
            name: options.response_model?.name || "Response",
            strict: true,
            schema: zodToJsonSchema(schema),
          },
        }
      : undefined;

    // OpenRouter Chat Completions API: https://openrouter.ai/docs/api-reference/chat-completion
    // Structured outputs (json_schema): https://openrouter.ai/docs/guides/features/structured-outputs
    const response = await fetch(OPENROUTER_CHAT_COMPLETIONS_URL, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${this.apiKey}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({
        model: this.modelName,
        messages: options.messages,
        tools,
        tool_choice: options.tool_choice,
        response_format: responseFormat,
        temperature: options.temperature ?? 0,
      }),
    });

    if (!response.ok) {
      throw new ProviderError("openrouter", response.status);
    }

    const payload = (await response.json()) as {
      choices?: Array<{ message?: { content?: unknown } }>;
      usage?: { prompt_tokens?: number; completion_tokens?: number; total_tokens?: number };
    };
    this.logger?.({ category: "openrouter", message: "completed thinking!" });

    if (!schema) {
      return payload as T;
    }

    const rawContent = payload.choices?.[0]?.message?.content;
    let parsed: unknown;
    if (typeof rawContent === "string") {
      parsed = JSON.parse(stripMarkdownJsonFence(rawContent));
    } else if (rawContent && typeof rawContent === "object") {
      parsed = rawContent;
    } else {
      parsed = {};
    }

    const validated = schema.parse(parsed);
    return {
      data: validated,
      usage: payload.usage,
    } as T;
  }
}
