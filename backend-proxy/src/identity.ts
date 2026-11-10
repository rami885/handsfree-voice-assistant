import { HttpError, natural, object } from "./platform";

const utf8 = new TextEncoder();
export const TOKEN_LIFETIME_MS = 30 * 24 * 60 * 60 * 1000;
export const RENEWAL_GRACE_MS = 30 * 24 * 60 * 60 * 1000;
export function base64url(bytes: Uint8Array): string { return btoa(Array.from(bytes, (b) => String.fromCharCode(b)).join("")).replace(/=/g, "").replace(/\+/g, "-").replace(/\//g, "_"); }
function unbase64url(value: string): Uint8Array<ArrayBuffer> {
  if (!/^[A-Za-z0-9_-]+$/.test(value)) throw new HttpError(401, "invalid_installation_token");
  const raw = atob(value.replace(/-/g, "+").replace(/_/g, "/").padEnd(Math.ceil(value.length / 4) * 4, "="));
  return Uint8Array.from(raw, (c) => c.charCodeAt(0));
}
async function key(secret: string): Promise<CryptoKey> { return crypto.subtle.importKey("raw", utf8.encode(secret), { name: "HMAC", hash: "SHA-256" }, false, ["sign", "verify"]); }
export interface Installation { v: 1; id: string; issuedAt: number; expiresAt: number }
export async function issueToken(secret: string, now: number, id: string = crypto.randomUUID()): Promise<string> {
  const payload: Installation = { v: 1, id, issuedAt: now, expiresAt: now + TOKEN_LIFETIME_MS };
  const encoded = base64url(utf8.encode(JSON.stringify(payload)));
  const signature = await crypto.subtle.sign("HMAC", await key(secret), utf8.encode(encoded));
  return `${encoded}.${base64url(new Uint8Array(signature))}`;
}
export async function verifyToken(secret: string, token: string, now: number, renewal = false): Promise<Installation> {
  try {
    if (token.length > 1024) throw new Error();
    const parts = token.split("."); const encoded = parts[0]; const signature = parts[1];
    if (parts.length !== 2 || !encoded || !signature || !await crypto.subtle.verify("HMAC", await key(secret), unbase64url(signature), utf8.encode(encoded))) throw new Error();
    const payload: unknown = JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(unbase64url(encoded)));
    if (!object(payload) || payload.v !== 1 || typeof payload.id !== "string" || !/^[0-9a-f-]{36}$/.test(payload.id) ||
        !natural(payload.issuedAt) || !natural(payload.expiresAt) || payload.expiresAt - payload.issuedAt !== TOKEN_LIFETIME_MS || payload.issuedAt > now + 30_000 || (!renewal && payload.expiresAt <= now) || (renewal && payload.expiresAt < now - RENEWAL_GRACE_MS)) throw new Error();
    return { v: 1, id: payload.id, issuedAt: payload.issuedAt, expiresAt: payload.expiresAt };
  } catch { throw new HttpError(401, "invalid_installation_token"); }
}
export async function sha256(value: string): Promise<string> { return base64url(new Uint8Array(await crypto.subtle.digest("SHA-256", utf8.encode(value)))); }
export async function hexHash(value: string): Promise<string> { return Array.from(new Uint8Array(await crypto.subtle.digest("SHA-256", utf8.encode(value))), (b) => b.toString(16).padStart(2, "0")).join(""); }
export function canonical(value: Record<string, unknown>): string { return JSON.stringify(Object.fromEntries(Object.entries(value).sort(([a], [b]) => a.localeCompare(b)))); }
