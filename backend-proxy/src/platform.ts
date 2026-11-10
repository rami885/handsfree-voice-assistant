export interface TransactionStore {
  get<T>(key: string): Promise<T | undefined>;
  put<T>(key: string, value: T): Promise<void>;
}
export interface DurableStorage extends TransactionStore {
  transaction<T>(callback: (transaction: TransactionStore) => Promise<T>): Promise<T>;
}
export interface DurableState { storage: DurableStorage }
export interface DurableStub { fetch(input: string | Request, init?: RequestInit): Promise<Response> }
export interface DurableNamespace { idFromName(name: string): unknown; get(id: unknown): DurableStub }
export interface Execution { waitUntil(promise: Promise<unknown>): void }
export interface Env {
  ENVIRONMENT: string; INTEGRITY_BYPASS: string;
  INSTALL_TOKEN_SECRET: string; MODEL_API_KEY: string; MODEL_ID: string;
  EXPECTED_PACKAGE: string; EXPECTED_CERTIFICATE_SHA256: string;
  GOOGLE_SERVICE_ACCOUNT_EMAIL: string; GOOGLE_PRIVATE_KEY: string;
  INSTALL_DAILY_BUDGET_MICROS: string; INSTALL_DAILY_REQUESTS: string;
  GLOBAL_DAILY_BUDGET_MICROS: string; REGISTRATIONS_PER_VERDICT_DAILY: string; REGISTRATION_ATTEMPTS_PER_IP_MINUTE: string;
  INPUT_MICROS_PER_TOKEN: string; OUTPUT_MICROS_PER_TOKEN: string; MAX_OUTPUT_TOKENS: string;
  INSTALL_BUDGETS: DurableNamespace; GLOBAL_BUDGETS: DurableNamespace;
}
export type AuditCode = "registered" | "rejected" | "quota_rejected" | "provider_error" | "stream_interrupted" | "settled" | "settlement_pending";
export interface AuditEvent { code: AuditCode; requestId: string; status?: number; costMicros?: number }
export interface AuditLogger { record(event: AuditEvent): void }
export class HttpError extends Error {
  constructor(readonly status: number, readonly code: string) { super(code); }
}
export function object(value: unknown): value is Record<string, unknown> { return value !== null && typeof value === "object" && !Array.isArray(value); }
export function natural(value: unknown): value is number { return typeof value === "number" && Number.isSafeInteger(value) && value >= 0; }
export function json(value: unknown, status = 200): Response { return new Response(JSON.stringify(value), { status, headers: { "Content-Type": "application/json", "Cache-Control": "no-store" } }); }
export function numberSetting(value: string, name: string, allowZero = true): number {
  const parsed = Number(value);
  if (!value || !Number.isSafeInteger(parsed) || parsed < (allowZero ? 0 : 1)) throw new HttpError(503, `invalid_${name}`);
  return parsed;
}
export function validateEnv(env: Env): void {
  if (env.INTEGRITY_BYPASS === "true" && env.ENVIRONMENT !== "development") throw new HttpError(503, "production_integrity_bypass_forbidden");
  if (!["production", "development"].includes(env.ENVIRONMENT) || !["true", "false"].includes(env.INTEGRITY_BYPASS)) throw new HttpError(503, "environment_not_explicit");
  if (!env.INSTALL_TOKEN_SECRET || env.INSTALL_TOKEN_SECRET.length < 32) throw new HttpError(503, "signing_secret_not_configured");
  if (!/^[a-z][a-z0-9_]*(?:\.[a-z0-9_]+)+$/.test(env.EXPECTED_PACKAGE)) throw new HttpError(503, "package_not_configured");
}
export async function readJson(request: Request, limit = 16_384): Promise<unknown> {
  if (!request.headers.get("content-type")?.toLowerCase().startsWith("application/json")) throw new HttpError(415, "json_required");
  const reader = request.body?.getReader();
  if (!reader) throw new HttpError(400, "body_required");
  let length = 0; const chunks: Uint8Array[] = [];
  try {
    while (true) {
      const next = await reader.read(); if (next.done) break;
      length += next.value.byteLength;
      if (length > limit) { await reader.cancel(); throw new HttpError(413, "request_too_large"); }
      chunks.push(next.value);
    }
    const bytes = new Uint8Array(length); let offset = 0;
    for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.byteLength; }
    try { return JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(bytes)); }
    catch { throw new HttpError(400, "invalid_json"); }
  } finally { reader.releaseLock(); }
}
