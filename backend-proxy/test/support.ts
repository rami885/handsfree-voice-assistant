import { AtomicBudget, GlobalBudget, InstallBudget } from "../src/budget";
import { type AuditEvent, type DurableNamespace, type DurableState, type DurableStorage, type Env, type Execution, type TransactionStore } from "../src/platform";
import { createWorker } from "../src/index";
import { issueToken, verifyToken } from "../src/identity";

export class MemoryStorage implements DurableStorage {
  data = new Map<string, unknown>(); transactions = 0;
  private tail: Promise<void> = Promise.resolve();
  async get<T>(key: string): Promise<T | undefined> { await Promise.resolve(); return structuredClone(this.data.get(key)) as T | undefined; }
  async put<T>(key: string, value: T): Promise<void> { await Promise.resolve(); this.data.set(key, structuredClone(value)); }
  async transaction<T>(callback: (tx: TransactionStore) => Promise<T>): Promise<T> {
    const previous = this.tail; let release: () => void = () => undefined;
    this.tail = new Promise<void>((resolve) => { release = resolve; });
    await previous; this.transactions++;
    const saved = structuredClone(this.data);
    try { return await callback(this); } catch (error) { this.data = saved; throw error; } finally { release(); }
  }
}
export class MemoryNamespace implements DurableNamespace {
  readonly stores = new Map<string, MemoryStorage>(); private readonly objects = new Map<string, AtomicBudget>();
  failAfterCommit = 0;
  constructor(private readonly factory: (state: DurableState) => AtomicBudget) {}
  idFromName(name: string): unknown { return name; }
  get(id: unknown) {
    if (typeof id !== "string") throw new Error("Invalid fixture identity");
    let instance = this.objects.get(id);
    if (!instance) { const store = new MemoryStorage(); this.stores.set(id, store); instance = this.factory({ storage: store }); this.objects.set(id, instance); }
    const object = instance;
    return { fetch: async (input: string | Request, init?: RequestInit): Promise<Response> => {
      const response = await object.fetch(input instanceof Request ? input : new Request(input, init));
      if (this.failAfterCommit > 0) { this.failAfterCommit--; throw new Error("Fixture transport loss"); }
      return response;
    } };
  }
}
export class Tasks implements Execution {
  readonly pending: Promise<unknown>[] = [];
  waitUntil(promise: Promise<unknown>) { this.pending.push(promise); }
  async drain() { await Promise.all(this.pending); }
}
export const NOW = Date.parse("2026-11-10T03:00:00Z");
export const PRIVATE_PROMPT = "PRIVATE_REQUEST_CONTENT";
export const PRIVATE_OUTPUT = "PRIVATE_RESPONSE_CONTENT";
export function setup(overrides: Partial<Omit<Env, "INSTALL_BUDGETS" | "GLOBAL_BUDGETS">> = {}) {
  const install = new MemoryNamespace((state) => new InstallBudget(state, env));
  const global = new MemoryNamespace((state) => new GlobalBudget(state, env));
  const env: Env = {
    ENVIRONMENT: "development", INTEGRITY_BYPASS: "true", INSTALL_TOKEN_SECRET: crypto.randomUUID() + crypto.randomUUID(),
    MODEL_API_KEY: "provider-key-placeholder", MODEL_ID: "fixture-model", EXPECTED_PACKAGE: "com.handsfree.assistant",
    EXPECTED_CERTIFICATE_SHA256: "fixture-certificate-digest", GOOGLE_PRIVATE_KEY: "", GOOGLE_SERVICE_ACCOUNT_EMAIL: "",
    INSTALL_DAILY_BUDGET_MICROS: "1000000", INSTALL_DAILY_REQUESTS: "100", GLOBAL_DAILY_BUDGET_MICROS: "10000000", REGISTRATIONS_PER_VERDICT_DAILY: "1000", REGISTRATION_ATTEMPTS_PER_IP_MINUTE: "120",
    INPUT_MICROS_PER_TOKEN: "2", OUTPUT_MICROS_PER_TOKEN: "3", MAX_OUTPUT_TOKENS: "128",
    ...overrides, INSTALL_BUDGETS: install, GLOBAL_BUDGETS: global,
  };
  let now = NOW; const events: AuditEvent[] = []; const tasks = new Tasks();
  return { env, install, global, events, tasks, now: () => now, setTime: (value: number) => { now = value; } };
}
export function post(path: string, body: unknown, token?: string, signal?: AbortSignal): Request {
  return new Request(`https://proxy.example${path}`, { method: "POST", headers: { "Content-Type": "application/json", "CF-Connecting-IP": "192.0.2.1", ...(token ? { Authorization: `Bearer ${token}` } : {}) }, body: JSON.stringify(body), signal });
}
export const plan = { text: PRIVATE_PROMPT, tools: [{ name: "pause", params: {}, permission: "NONE", sensitivity: "NONE" }] };
export function sse(input = 20, output = 12): string {
  return `event: message_start\ndata: ${JSON.stringify({ type: "message_start", message: { usage: { input_tokens: input, output_tokens: 0 } } })}\n\nevent: content_block_delta\ndata: ${JSON.stringify({ type: "content_block_delta", delta: { type: "text_delta", text: PRIVATE_OUTPUT } })}\n\nevent: message_delta\ndata: ${JSON.stringify({ type: "message_delta", usage: { output_tokens: output } })}\n\nevent: message_stop\ndata: {"type":"message_stop"}\n\n`;
}
export function providerResponse(content = sse()): Response { return new Response(content, { headers: { "Content-Type": "text/event-stream" } }); }
export function worker(fixture: ReturnType<typeof setup>, fetcher: typeof fetch = async () => providerResponse()) {
  return createWorker({ fetcher, now: fixture.now, logger: { record: (event) => fixture.events.push(event) } });
}
export async function credential(fixture: ReturnType<typeof setup>) {
  const token = await issueToken(fixture.env.INSTALL_TOKEN_SECRET, fixture.now());
  return { token, identity: await verifyToken(fixture.env.INSTALL_TOKEN_SECRET, token, fixture.now()) };
}
export function totals(namespace: MemoryNamespace, id: string, day = "2026-11-10") {
  return namespace.stores.get(id)?.data.get(`${day}:totals`) as { usedMicros: number; reservedMicros: number; usedRequests: number; reservedRequests: number } | undefined;
}
