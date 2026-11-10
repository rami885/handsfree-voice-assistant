import { describe, expect, it } from "vitest";
import { credential, NOW, plan, post, providerResponse, setup, totals, worker } from "./support";

async function rpc(f: ReturnType<typeof setup>, body: Record<string, unknown>) {
  return f.env.INSTALL_BUDGETS.get(f.env.INSTALL_BUDGETS.idFromName("fixture")).fetch("https://budget.internal/transaction", { method: "POST", body: JSON.stringify({ day: "2026-11-10", ...body }) });
}
describe("atomic ledger boundaries", () => {
  it("counts one registration when acknowledgements are lost and retried", async () => {
    const f = setup({ REGISTRATIONS_PER_VERDICT_DAILY: "1" }); f.global.failAfterCommit = 2;
    const app = worker(f); const body = { integrityToken: "fixture", registration: { nonce: "fixture_nonce_123456" } };
    expect((await app.fetch(post("/v1/register", body), f.env, f.tasks)).status).toBe(200);
    expect((await app.fetch(post("/v1/register", body), f.env, f.tasks)).status).toBe(409);
  });
  it("rolls back invalid settlements and never refunds or consumes a reservation twice", async () => {
    const f = setup({ INSTALL_DAILY_BUDGET_MICROS: "100", INSTALL_DAILY_REQUESTS: "1" });
    expect((await rpc(f, { op: "reserve", id: "one", amount: 100 })).status).toBe(200);
    expect((await rpc(f, { op: "reserve", id: "one", amount: 100 })).status).toBe(200);
    expect((await rpc(f, { op: "settle", id: "one", cost: 101, consumed: true })).status).toBe(400);
    expect(totals(f.install, "fixture")?.reservedMicros).toBe(100);
    expect((await rpc(f, { op: "reserve", id: "two", amount: 1 })).status).toBe(429);
    expect((await rpc(f, { op: "settle", id: "one", cost: 20, consumed: true })).status).toBe(200);
    expect((await rpc(f, { op: "settle", id: "one", cost: 20, consumed: true })).status).toBe(200);
    expect((await rpc(f, { op: "settle", id: "one", cost: 0, consumed: false })).status).toBe(409);
    expect(totals(f.install, "fixture")).toEqual({ usedMicros: 20, reservedMicros: 0, usedRequests: 1, reservedRequests: 0 });
    expect((await rpc(f, { op: "reserve", id: "one", amount: 100 })).status).toBe(429);
  });
  it("starts a fresh quota on the next UTC day without erasing the previous ledger", async () => {
    const f = setup({ INSTALL_DAILY_REQUESTS: "1" }); const { token, identity } = await credential(f); const app = worker(f);
    const response = await app.fetch(post("/v1/plan", plan, token), f.env, f.tasks); await response.text(); await f.tasks.drain();
    expect((await app.fetch(post("/v1/plan", plan, token), f.env, f.tasks)).status).toBe(429);
    f.setTime(NOW + 86_400_000);
    const next = await app.fetch(post("/v1/plan", plan, token), f.env, f.tasks); expect(next.status).toBe(200); await next.text(); await f.tasks.drain();
    expect(totals(f.install, identity.id)?.usedRequests).toBe(1); expect(totals(f.install, identity.id, "2026-11-11")?.usedRequests).toBe(1);
  });
  it("releases installation reservations after three failed global acknowledgements without invoking the model", async () => {
    const f = setup(); const { token, identity } = await credential(f); f.global.failAfterCommit = 3; let calls = 0;
    const result = await worker(f, async () => { calls++; return providerResponse(); }).fetch(post("/v1/plan", plan, token), f.env, f.tasks);
    expect(result.status).toBe(503); expect(calls).toBe(0);
    expect(totals(f.install, identity.id)?.reservedMicros).toBe(0); expect(totals(f.global, "global")?.reservedMicros).toBe(0);
  });
  it("does not invoke the model or consume quota for an already cancelled request", async () => {
    const f = setup(); const { token, identity } = await credential(f); const abort = new AbortController(); abort.abort(); let calls = 0;
    const result = await worker(f, async () => { calls++; return providerResponse(); }).fetch(post("/v1/plan", plan, token, abort.signal), f.env, f.tasks);
    expect(result.status).toBe(499); expect(calls).toBe(0); expect(totals(f.install, identity.id)).toBeUndefined();
  });
});
