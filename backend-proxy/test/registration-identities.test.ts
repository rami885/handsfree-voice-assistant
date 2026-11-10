import { readFileSync } from "node:fs";
import { expect, it } from "vitest";
import { credential, NOW, post, setup, worker } from "./support";
import { verifyToken } from "../src/identity";

function register(nonce: string, ip: string, previousToken?: string) {
  const request = post("/v1/register", { integrityToken: "fixture", registration: { nonce, ...(previousToken ? { previousToken } : {}) } });
  request.headers.set("CF-Connecting-IP", ip); return request;
}
it("isolates new identities by IP and verdict class and never spends the cap on renewal", async () => {
  const f = setup({ REGISTRATIONS_PER_VERDICT_DAILY: "1" }); const app = worker(f);
  const initial = await app.fetch(register("fixture_nonce_first", "192.0.2.1"), f.env, f.tasks);
  expect(initial.status).toBe(200);
  const { token } = await initial.json() as { token: string };
  expect((await app.fetch(register("fixture_nonce_second", "192.0.2.1"), f.env, f.tasks)).status).toBe(429);
  const renewed = await app.fetch(register("fixture_nonce_renewal", "192.0.2.1", token), f.env, f.tasks);
  expect(renewed.status).toBe(200);
  const next = await renewed.json() as { token: string };
  expect((await verifyToken(f.env.INSTALL_TOKEN_SECRET, next.token, NOW)).id).toBe((await verifyToken(f.env.INSTALL_TOKEN_SECRET, token, NOW)).id);
  expect((await app.fetch(register("fixture_nonce_other_ip", "192.0.2.2"), f.env, f.tasks)).status).toBe(200);
});
it("renews existing identities even with a zero creation cap and stopped model spending", async () => {
  const f = setup({ REGISTRATIONS_PER_VERDICT_DAILY: "0", GLOBAL_DAILY_BUDGET_MICROS: "0" });
  const { token, identity } = await credential(f);
  const response = await worker(f).fetch(register("fixture_nonce_renewal", "192.0.2.1", token), f.env, f.tasks);
  expect(response.status).toBe(200);
  const result = await response.json() as { token: string };
  expect((await verifyToken(f.env.INSTALL_TOKEN_SECRET, result.token, NOW)).id).toBe(identity.id);
});
it("raises the configured per-IP verdict creation cap to one thousand per day", () => {
  const config = readFileSync(new URL("../wrangler.toml", import.meta.url), "utf8");
  expect(config).toMatch(/REGISTRATIONS_PER_VERDICT_DAILY\s*=\s*"1000"/);
});
