/**
 * Managed Deepgram voice routes (`POST /v1/voice/{stt-token,speak}`).
 * Built by job W4.
 */
import { requireCustomer } from "../auth";
import type { Env } from "../env";
import { HttpError } from "../http";

export type VoiceAction = "stt-token" | "speak";

export async function handleVoiceSttToken(request: Request, env: Env): Promise<Response> {
  await requireCustomer(request, env);
  throw new HttpError(501, "not_built", "voice service is not built yet");
}

export async function handleVoiceSpeak(request: Request, env: Env): Promise<Response> {
  await requireCustomer(request, env);
  throw new HttpError(501, "not_built", "voice service is not built yet");
}

export async function handleVoice(request: Request, env: Env, action: VoiceAction): Promise<Response> {
  switch (action) {
    case "stt-token":
      return handleVoiceSttToken(request, env);
    case "speak":
      return handleVoiceSpeak(request, env);
  }
}
