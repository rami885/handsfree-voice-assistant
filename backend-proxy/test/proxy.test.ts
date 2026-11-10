import { describe, expect, it } from "vitest";
import { execFileSync } from "node:child_process";
import { createWorker } from "../src/index";
import { TOKEN_LIFETIME_MS, verifyToken } from "../src/identity";
import { providerRequest } from "../src/provider";
import { credential, NOW, plan, post, PRIVATE_OUTPUT, PRIVATE_PROMPT, providerResponse, setup, sse, totals, worker } from "./support";

function noContent(events: unknown) {
  const log = JSON.stringify(events);
  expect(log).not.toContain(PRIVATE_PROMPT); expect(log).not.toContain(PRIVATE_OUTPUT);
  expect(log).not.toContain("PRIVATE_PROVIDER_ERROR"); expect(log).not.toContain("PRIVATE_STREAM_FAILURE");
}
describe("installation authentication", () => {
  it("requires a signed, unexpired installation token even in bypass development", async () => {
    const f = setup(); let calls = 0;
    const app = worker(f, async () => { calls++; return providerResponse(); });
    expect((await app.fetch(post("/v1/plan", plan), f.env, f.tasks)).status).toBe(401);
    expect((await app.fetch(post("/v1/plan", plan, "arbitrary-integrity-token"), f.env, f.tasks)).status).toBe(401);
    const { token } = await credential(f);
    expect((await app.fetch(post("/v1/plan", plan, token.slice(0, -5) + "wrong"), f.env, f.tasks)).status).toBe(401);
    f.setTime(NOW + TOKEN_LIFETIME_MS);
    expect((await app.fetch(post("/v1/plan", plan, token), f.env, f.tasks)).status).toBe(401);
    expect(calls).toBe(0); noContent(f.events);
  });
  it("rejects production bypass and implicit environments", async () => {
    for (const ENVIRONMENT of ["production", "", "prod"]) {
      const f = setup({ ENVIRONMENT }); const app = worker(f);
      expect((await app.fetch(post("/v1/register", { integrityToken: "fixture", registration: { nonce: "fixture_nonce_123456" } }), f.env, f.tasks)).status).toBe(503);
    }
  });
  it("issues exactly thirty days and renews the same installation without resetting daily quota", async () => {
    const f = setup({ INSTALL_DAILY_REQUESTS: "1" }); const app = worker(f);
    const initial = await app.fetch(post("/v1/register", { integrityToken: "fixture", registration: { nonce: "fixture_nonce_123456" } }), f.env, f.tasks);
    expect(initial.status).toBe(200);
    const first = await initial.json() as { token: string; expiresAt: number };
    expect(first.expiresAt).toBe(NOW + TOKEN_LIFETIME_MS);
    const id = await verifyToken(f.env.INSTALL_TOKEN_SECRET, first.token, NOW);
    const response = await app.fetch(post("/v1/plan", plan, first.token), f.env, f.tasks); await response.text(); await f.tasks.drain();
    const renewed = await app.fetch(post("/v1/register", { integrityToken: "fixture", registration: { nonce: "fixture_nonce_654321", previousToken: first.token } }), f.env, f.tasks);
    const second = await renewed.json() as { token: string };
    expect((await verifyToken(f.env.INSTALL_TOKEN_SECRET, second.token, NOW)).id).toBe(id.id);
    expect((await app.fetch(post("/v1/plan", plan, second.token), f.env, f.tasks)).status).toBe(429);
  });
  it("limits registrations for an equivalent verdict class and the global cap", async () => {
    const f = setup({ REGISTRATIONS_PER_VERDICT_DAILY: "2" }); const app = worker(f);
    for (let n = 0; n < 3; n++) {
      const result = await app.fetch(post("/v1/register", { integrityToken: `fixture-${n}`, registration: { nonce: `fixture_nonce_123456${n}` } }), f.env, f.tasks);
      expect(result.status).toBe(n < 2 ? 200 : 429);
    }
    const stopped = setup({ GLOBAL_DAILY_BUDGET_MICROS: "0" });
    expect((await worker(stopped).fetch(post("/v1/register", { integrityToken: "fixture", registration: { nonce: "fixture_nonce_123456" } }), stopped.env, stopped.tasks)).status).toBe(429);
  });
});
describe("reservation and streaming", () => {
  it("forwards a streaming request with an env-only key and settles cumulative usage once", async () => {
    const f = setup(); const { token, identity } = await credential(f); let calls = 0;
    const app = worker(f, async (input, init) => {
      calls++; expect(String(input)).toBe("https://api.anthropic.com/v1/messages");
      const headers = new Headers(init?.headers); expect(headers.get("x-api-key")).toBe(f.env.MODEL_API_KEY); expect(headers.has("Authorization")).toBe(false);
      const body = JSON.parse(String(init?.body)); expect(body.stream).toBe(true); expect(body.max_tokens).toBe(128); expect(body.messages[0].content).toContain(PRIVATE_PROMPT);
      return providerResponse();
    });
    const response = await app.fetch(post("/v1/plan", plan, token), f.env, f.tasks);
    expect(response.status).toBe(200); expect(await response.text()).toBe(sse()); await f.tasks.drain();
    expect(calls).toBe(1); expect(totals(f.install, identity.id)).toEqual({ usedMicros: 76, reservedMicros: 0, usedRequests: 1, reservedRequests: 0 });
    expect(totals(f.global, "global")?.usedMicros).toBe(76); noContent(f.events);
  });
  it("rejects over quota before calling the model and releases a rejected global reservation", async () => {
    for (const side of ["install", "global"] as const) {
      const f = setup(side === "install" ? { INSTALL_DAILY_BUDGET_MICROS: "1" } : { GLOBAL_DAILY_BUDGET_MICROS: "1" });
      const { token, identity } = await credential(f); let calls = 0;
      const response = await worker(f, async () => { calls++; return providerResponse(); }).fetch(post("/v1/plan", plan, token), f.env, f.tasks);
      expect(response.status).toBe(429); expect(calls).toBe(0);
      const local = totals(f.install, identity.id); expect(local?.reservedMicros ?? 0).toBe(0); expect(local?.usedRequests ?? 0).toBe(0); noContent(f.events);
    }
  });
  it("atomically limits concurrent reservations, then reconciles actual cost", async () => {
    const f = setup({ INSTALL_DAILY_REQUESTS: "5" });
    const amount = providerRequest(plan, f.env).reserveMicros;
    f.env.INSTALL_DAILY_BUDGET_MICROS = String(amount * 5);
    f.env.GLOBAL_DAILY_BUDGET_MICROS = String(amount * 5);
    const { token, identity } = await credential(f);
    let open: () => void = () => undefined; const gate = new Promise<void>((resolve) => { open = resolve; }); let calls = 0;
    const app = worker(f, async () => {
      calls++;
      return new Response(new ReadableStream({ async start(controller) { await gate; controller.enqueue(new TextEncoder().encode(sse())); controller.close(); } }), { headers: { "Content-Type": "text/event-stream" } });
    });
    const responses = await Promise.all(Array.from({ length: 20 }, () => app.fetch(post("/v1/plan", plan, token), f.env, f.tasks)));
    expect(responses.filter((r) => r.status === 200)).toHaveLength(5); expect(responses.filter((r) => r.status === 429)).toHaveLength(15); expect(calls).toBe(5);
    expect(totals(f.global, "global")?.reservedMicros).toBe(amount * 5);
    open(); await Promise.all(responses.map((response) => response.text())); await f.tasks.drain();
    expect(totals(f.install, identity.id)?.usedMicros).toBe(5 * 76); expect(totals(f.global, "global")?.reservedMicros).toBe(0);
    expect(f.global.stores.get("global")?.transactions).toBeGreaterThan(0);
  });
  it("enforces one global cap across separate installations", async () => {
    const f = setup(); const amount = providerRequest(plan, f.env).reserveMicros; f.env.GLOBAL_DAILY_BUDGET_MICROS = String(amount);
    const first = await credential(f); const second = await credential(f);
    let release: () => void = () => undefined; const gate = new Promise<void>((resolve) => { release = resolve; }); let calls = 0;
    const app = worker(f, async () => { calls++; return new Response(new ReadableStream({ async start(c) { await gate; c.enqueue(new TextEncoder().encode(sse())); c.close(); } }), { headers: { "Content-Type": "text/event-stream" } }); });
    const results = await Promise.all([app.fetch(post("/v1/plan", plan, first.token), f.env, f.tasks), app.fetch(post("/v1/plan", plan, second.token), f.env, f.tasks)]);
    expect(results.map((r) => r.status).sort()).toEqual([200, 429]); expect(calls).toBe(1);
    release(); await Promise.all(results.map((r) => r.text())); await f.tasks.drain();
  });
  it("handles acknowledgement loss idempotently", async () => {
    const f = setup(); f.global.failAfterCommit = 2; const { token } = await credential(f); let calls = 0;
    const response = await worker(f, async () => { calls++; return providerResponse(); }).fetch(post("/v1/plan", plan, token), f.env, f.tasks);
    expect(response.status).toBe(200); await response.text(); await f.tasks.drain(); expect(calls).toBe(1); expect(totals(f.global, "global")?.usedMicros).toBe(76);
  });
  it("never logs provider error content and charges the bound when usage is unknown", async () => {
    const f = setup(); const { token, identity } = await credential(f); const bound = providerRequest(plan, f.env).reserveMicros;
    const response = await worker(f, async () => new Response(`PRIVATE_PROVIDER_ERROR ${PRIVATE_PROMPT}`, { status: 500 })).fetch(post("/v1/plan", plan, token), f.env, f.tasks);
    expect(response.status).toBe(502); expect(await response.text()).not.toContain(PRIVATE_PROMPT);
    expect(totals(f.install, identity.id)?.usedMicros).toBe(bound); noContent(f.events);
  });
  it("never logs interrupted stream chunks or exception messages", async () => {
    const f = setup(); const { token, identity } = await credential(f);
    let upstream!: ReadableStreamDefaultController<Uint8Array>;
    const app = worker(f, async () => new Response(new ReadableStream<Uint8Array>({
      start(controller) { upstream = controller; controller.enqueue(new TextEncoder().encode(PRIVATE_OUTPUT)); },
    }), { headers: { "Content-Type": "text/event-stream" } }));
    const response = await app.fetch(post("/v1/plan", plan, token), f.env, f.tasks);
    const reader = response.body!.getReader();
    const first = await reader.read();
    expect(first.done).toBe(false);
    expect(new TextDecoder().decode(first.value)).toBe(PRIVATE_OUTPUT);
    // Fail only after proving that the real proxy stream delivered a chunk.
    upstream.error(new Error(`PRIVATE_STREAM_FAILURE ${PRIVATE_PROMPT}`));
    await expect(reader.read()).rejects.toThrow("upstream_stream_interrupted"); await f.tasks.drain();
    expect(totals(f.install, identity.id)?.usedMicros).toBe(providerRequest(plan, f.env).reserveMicros);
    expect(totals(f.global, "global")?.usedMicros).toBe(providerRequest(plan, f.env).reserveMicros);
    noContent(f.events);
  });
  it("charges the full reservation after client cancellation", async () => {
    const f = setup(); const { token, identity } = await credential(f); let aborted = false;
    const app = worker(f, async (_input, init) => { init?.signal?.addEventListener("abort", () => { aborted = true; }); return new Response(new ReadableStream({ start(c) { c.enqueue(new TextEncoder().encode(`data: ${PRIVATE_OUTPUT}\n\n`)); } }), { headers: { "Content-Type": "text/event-stream" } }); });
    const response = await app.fetch(post("/v1/plan", plan, token), f.env, f.tasks); const reader = response.body!.getReader(); await reader.read(); await reader.cancel(); await f.tasks.drain();
    expect(aborted).toBe(true); expect(totals(f.install, identity.id)?.usedMicros).toBe(providerRequest(plan, f.env).reserveMicros); noContent(f.events);
  });
  it("keeps a conservative charge for incomplete usage and fractional prices round upward", async () => {
    const f = setup({ INPUT_MICROS_PER_TOKEN: "0.8", OUTPUT_MICROS_PER_TOKEN: "0.9" }); const { token, identity } = await credential(f);
    const response = await worker(f, async () => providerResponse(`data: {"type":"content_block_delta","delta":{"text":"${PRIVATE_OUTPUT}"}}\n\n`)).fetch(post("/v1/plan", plan, token), f.env, f.tasks);
    await response.text(); await f.tasks.drain(); expect(totals(f.install, identity.id)?.usedMicros).toBe(providerRequest(plan, f.env).reserveMicros); noContent(f.events);
  });
});
describe("request boundaries", () => {
  it("rejects oversized, malformed and unsupported requests without exposing content", async () => {
    const f = setup(); const { token } = await credential(f); const app = worker(f);
    expect((await app.fetch(post("/v1/plan", { text: "x".repeat(20_000), tools: [] }, token), f.env, f.tasks)).status).toBe(413);
    const malformed = new Request("https://proxy.example/v1/plan", { method: "POST", headers: { "Content-Type": "application/json", Authorization: `Bearer ${token}` }, body: PRIVATE_PROMPT });
    expect((await app.fetch(malformed, f.env, f.tasks)).status).toBe(400);
    expect((await app.fetch(new Request("https://proxy.example/v1/plan"), f.env, f.tasks)).status).toBe(405);
    expect((await app.fetch(post("/unknown", plan, token), f.env, f.tasks)).status).toBe(404);
    expect((await app.fetch(post("/v1/plan?text=private", plan, token), f.env, f.tasks)).status).toBe(400); noContent(f.events);
  });
  it("runs the repository secret-pattern guard", () => { expect(execFileSync("python3", ["../scripts/check-safety.py"], { encoding: "utf8" })).toContain("Repository safety checks passed"); });
  it("can construct the production entry point without any network call", () => { expect(typeof createWorker().fetch).toBe("function"); });
});
