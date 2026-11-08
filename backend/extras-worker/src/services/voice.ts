/**
 * Managed Deepgram voice routes (`POST /v1/voice/{stt-token,speak}`).
 * Built by job W4.
 */
import { requireCustomer } from "../auth";
import type { Env } from "../env";
import { HttpError, ProviderError, jsonNoStore, readJsonObject, requireString } from "../http";
import { addUsage, checkCap } from "../usage";

export type VoiceAction = "stt-token" | "speak";

const DEFAULT_TTS_VOICE = "aura-2-thalia-en";

/**
 * Returns a short-lived token grant from Deepgram for real-time STT WebSocket connections.
 *
 * External API: POST https://api.deepgram.com/v1/auth/grant
 * Docs: https://developers.deepgram.com/reference/auth/tokens/grant
 */
export async function handleVoiceSttToken(request: Request, env: Env): Promise<Response> {
  const customer = await requireCustomer(request, env);
  await checkCap(env, customer.id, "voice_stt_minutes", env.VOICE_STT_MINUTES_CAP);

  const apiKey = env.DEEPGRAM_API_KEY;
  if (!apiKey) {
    throw new HttpError(503, "service_off", "Deepgram API key not configured");
  }

  // Deepgram Token Grant API: POST https://api.deepgram.com/v1/auth/grant
  // Docs: https://developers.deepgram.com/reference/auth/tokens/grant
  const res = await fetch("https://api.deepgram.com/v1/auth/grant", {
    method: "POST",
    headers: {
      Authorization: `Token ${apiKey}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify({ ttl_seconds: 60 }),
  });

  if (!res.ok) {
    throw new ProviderError("voice", res.status);
  }

  const data = (await res.json()) as { access_token?: string; expires_in?: number };
  if (!data.access_token) {
    throw new ProviderError("voice", 502);
  }

  await addUsage(env, customer.id, "voice_stt_minutes", 1);

  return jsonNoStore({
    token: data.access_token,
    expiresIn: data.expires_in ?? 60,
  });
}

/**
 * Synthesizes text into speech using Deepgram Aura-2 TTS and streams audio back.
 *
 * External API: POST https://api.deepgram.com/v1/speak?model=<voice>
 * Docs: https://developers.deepgram.com/reference/text-to-speech/speak
 */
export async function handleVoiceSpeak(request: Request, env: Env): Promise<Response> {
  const customer = await requireCustomer(request, env);

  const body = await readJsonObject(request);
  const text = requireString(body, "text", 1, 2000);
  const voice =
    typeof body.voice === "string" && body.voice.trim().length > 0
      ? body.voice.trim()
      : DEFAULT_TTS_VOICE;

  await checkCap(env, customer.id, "voice_tts_chars", env.VOICE_TTS_CHARS_CAP);

  const apiKey = env.DEEPGRAM_API_KEY;
  if (!apiKey) {
    throw new HttpError(503, "service_off", "Deepgram API key not configured");
  }

  // Deepgram Aura-2 TTS: POST https://api.deepgram.com/v1/speak?model=<voice>
  // Docs: https://developers.deepgram.com/reference/text-to-speech/speak
  const url = `https://api.deepgram.com/v1/speak?model=${encodeURIComponent(voice)}`;
  const res = await fetch(url, {
    method: "POST",
    headers: {
      Authorization: `Token ${apiKey}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify({ text }),
  });

  if (!res.ok) {
    throw new ProviderError("voice", res.status);
  }

  await addUsage(env, customer.id, "voice_tts_chars", text.length);

  return new Response(res.body, {
    status: 200,
    headers: {
      "content-type": res.headers.get("content-type") ?? "audio/mpeg",
      "cache-control": "no-store",
    },
  });
}

export async function handleVoice(request: Request, env: Env, action: VoiceAction): Promise<Response> {
  switch (action) {
    case "stt-token":
      return handleVoiceSttToken(request, env);
    case "speak":
      return handleVoiceSpeak(request, env);
  }
}
