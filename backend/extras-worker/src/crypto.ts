/**
 * Hashing and encryption helpers. Everything here uses WebCrypto, which is the only crypto
 * API the Workers runtime offers.
 *
 * - SHA-256 hashes stand in for Play purchase tokens and install ids in D1, so a leaked
 *   database does not reveal a token that could be replayed against Play.
 * - AES-GCM protects provider key secrets at rest. A provider shows an OpenRouter key only
 *   once, and re-activation has to hand the same key back, so the secret must be kept.
 */

const encoder = new TextEncoder();

export function utf8(text: string): Uint8Array<ArrayBuffer> {
  return new Uint8Array(encoder.encode(text));
}

export function bytesToBase64(bytes: Uint8Array): string {
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary);
}

export function base64ToBytes(base64: string): Uint8Array<ArrayBuffer> {
  const binary = atob(base64);
  const out = new Uint8Array(new ArrayBuffer(binary.length));
  for (let i = 0; i < binary.length; i++) out[i] = binary.charCodeAt(i);
  return out;
}

/** base64url without padding, as JWT and Google's JWT bearer grant require. */
export function base64UrlEncode(bytes: Uint8Array): string {
  return bytesToBase64(bytes).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

export async function sha256Hex(text: string): Promise<string> {
  const digest = new Uint8Array(await crypto.subtle.digest("SHA-256", utf8(text)));
  let hex = "";
  for (const byte of digest) hex += byte.toString(16).padStart(2, "0");
  return hex;
}

/** Compares two secrets without leaking the position of the first difference. */
export function constantTimeEquals(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

async function aesKey(base64Key: string, usage: "encrypt" | "decrypt"): Promise<CryptoKey> {
  const raw = base64ToBytes(base64Key);
  if (raw.length !== 32) {
    throw new Error("CREDENTIAL_ENCRYPTION_KEY must decode to exactly 32 bytes");
  }
  return crypto.subtle.importKey("raw", raw, { name: "AES-GCM" }, false, [usage]);
}

/** Returns base64(iv || ciphertext). A fresh 96-bit IV per call. */
export async function encryptSecret(plaintext: string, base64Key: string): Promise<string> {
  const key = await aesKey(base64Key, "encrypt");
  const iv = crypto.getRandomValues(new Uint8Array(new ArrayBuffer(12)));
  const ciphertext = new Uint8Array(
    await crypto.subtle.encrypt({ name: "AES-GCM", iv }, key, utf8(plaintext)),
  );
  const packed = new Uint8Array(new ArrayBuffer(iv.length + ciphertext.length));
  packed.set(iv, 0);
  packed.set(ciphertext, iv.length);
  return bytesToBase64(packed);
}

export async function decryptSecret(packedBase64: string, base64Key: string): Promise<string> {
  const key = await aesKey(base64Key, "decrypt");
  const packed = base64ToBytes(packedBase64);
  const iv = packed.slice(0, 12);
  const ciphertext = packed.slice(12);
  const plaintext = await crypto.subtle.decrypt({ name: "AES-GCM", iv }, key, ciphertext);
  return new TextDecoder().decode(plaintext);
}
