import { base64url, canonical, hexHash, sha256 } from "./identity";
import { type Env, HttpError, object } from "./platform";

export interface Registration { nonce: string; previousToken?: string }
export function registration(value: unknown): Registration {
  if (!object(value) || typeof value.nonce !== "string" || !/^[A-Za-z0-9_-]{16,128}$/.test(value.nonce) ||
      Object.keys(value).some((key) => !["nonce", "previousToken"].includes(key)) ||
      (value.previousToken !== undefined && (typeof value.previousToken !== "string" || value.previousToken.length === 0 || value.previousToken.length > 1024))) throw new HttpError(400, "invalid_registration");
  return { nonce: value.nonce, ...(typeof value.previousToken === "string" ? { previousToken: value.previousToken } : {}) };
}
export async function requestHash(value: Registration): Promise<string> { return sha256(canonical({ ...value })); }
export class GoogleIntegrity {
  constructor(private readonly env: Env, private readonly fetcher: typeof fetch, private readonly now: () => number) {}
  async decode(token: string, signal?: AbortSignal): Promise<unknown> {
    if (signal?.aborted) throw new HttpError(499, "request_cancelled");
    const abort = new AbortController(); let timedOut = false;
    const interrupted = () => abort.abort();
    signal?.addEventListener("abort", interrupted, { once: true });
    const timer = setTimeout(() => { timedOut = true; abort.abort(); }, 5_000);
    try { return await this.decodeRequest(token, abort.signal); }
    catch (error) {
      if (signal?.aborted) throw new HttpError(499, "request_cancelled");
      if (timedOut) throw new HttpError(504, "integrity_timeout");
      if (error instanceof HttpError) throw error;
      throw new HttpError(503, "integrity_unavailable");
    } finally { clearTimeout(timer); signal?.removeEventListener("abort", interrupted); }
  }
  private async decodeRequest(token: string, signal: AbortSignal): Promise<unknown> {
    if (!this.env.GOOGLE_PRIVATE_KEY || !this.env.GOOGLE_SERVICE_ACCOUNT_EMAIL) throw new HttpError(503, "integrity_credentials_missing");
    const seconds = Math.floor(this.now() / 1000);
    const header = base64url(new TextEncoder().encode(JSON.stringify({ alg: "RS256", typ: "JWT" })));
    const payload = base64url(new TextEncoder().encode(JSON.stringify({ iss: this.env.GOOGLE_SERVICE_ACCOUNT_EMAIL, scope: "https://www.googleapis.com/auth/playintegrity", aud: "https://oauth2.googleapis.com/token", iat: seconds, exp: seconds + 3600 })));
    const content = `${header}.${payload}`;
    const pem = this.env.GOOGLE_PRIVATE_KEY.replace(/-----BEGIN PRIVATE KEY-----|-----END PRIVATE KEY-----|\s/g, "");
    const bytes = Uint8Array.from(atob(pem), (c) => c.charCodeAt(0));
    const key = await crypto.subtle.importKey("pkcs8", bytes, { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["sign"]);
    const signature = await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, new TextEncoder().encode(content));
    signal.throwIfAborted();
    const oauth = await this.fetcher("https://oauth2.googleapis.com/token", { method: "POST", headers: { "Content-Type": "application/x-www-form-urlencoded" }, body: new URLSearchParams({ grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer", assertion: `${content}.${base64url(new Uint8Array(signature))}` }), signal });
    if (oauth.status >= 500) throw new HttpError(503, "integrity_unavailable");
    if (!oauth.ok) throw new HttpError(503, "integrity_oauth_failed");
    const authorization: unknown = await oauth.json();
    if (!object(authorization) || typeof authorization.access_token !== "string") throw new HttpError(503, "integrity_oauth_invalid");
    signal.throwIfAborted();
    const response = await this.fetcher(`https://playintegrity.googleapis.com/v1/${encodeURIComponent(this.env.EXPECTED_PACKAGE)}:decodeIntegrityToken`, { method: "POST", headers: { Authorization: `Bearer ${authorization.access_token}`, "Content-Type": "application/json" }, body: JSON.stringify({ integrity_token: token }), signal });
    if (response.status >= 500) throw new HttpError(503, "integrity_unavailable");
    if (!response.ok) throw new HttpError(401, "integrity_decode_failed");
    const decoded: unknown = await response.json();
    if (!object(decoded)) throw new HttpError(401, "integrity_invalid");
    return decoded.tokenPayloadExternal;
  }
}
export async function verifyVerdict(value: unknown, hash: string, env: Env, now: number): Promise<string> {
  if (!object(value) || !object(value.requestDetails) || !object(value.appIntegrity) || !object(value.deviceIntegrity)) throw new HttpError(401, "integrity_invalid");
  const request = value.requestDetails; const app = value.appIntegrity; const device = value.deviceIntegrity;
  const time = Number(request.timestampMillis);
  const tags = device.deviceRecognitionVerdict;
  const certificates = app.certificateSha256Digest;
  if (request.requestHash !== hash || request.requestPackageName !== env.EXPECTED_PACKAGE || !Number.isFinite(time) || time < now - 300_000 || time > now + 30_000 ||
      app.appRecognitionVerdict !== "PLAY_RECOGNIZED" || app.packageName !== env.EXPECTED_PACKAGE || !env.EXPECTED_CERTIFICATE_SHA256 ||
      !Array.isArray(certificates) || !certificates.every((item) => typeof item === "string") || !certificates.includes(env.EXPECTED_CERTIFICATE_SHA256) ||
      !Array.isArray(tags) || !tags.every((item) => typeof item === "string") || !tags.includes("MEETS_DEVICE_INTEGRITY")) throw new HttpError(401, "integrity_rejected");
  // This groups equivalent attested verdicts; it is not a hardware identifier.
  return hexHash(canonical({ packageName: app.packageName, certificates: [...certificates].sort(), deviceVerdicts: [...tags].sort(), appVerdict: app.appRecognitionVerdict }));
}
