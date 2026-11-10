import { beforeAll, expect, it, vi } from "vitest";
import { post, setup, worker } from "./support";

let pem = "";
beforeAll(async () => {
  const pair = await crypto.subtle.generateKey({ name: "RSASSA-PKCS1-v1_5", modulusLength: 2048, publicExponent: new Uint8Array([1, 0, 1]), hash: "SHA-256" }, true, ["sign", "verify"]);
  pem = `-----BEGIN PRIVATE KEY-----\n${btoa(String.fromCharCode(...new Uint8Array(await crypto.subtle.exportKey("pkcs8", pair.privateKey))))}\n-----END PRIVATE KEY-----`;
});
function production() {
  const f = setup({ ENVIRONMENT: "production", INTEGRITY_BYPASS: "false", GOOGLE_SERVICE_ACCOUNT_EMAIL: "fixture@example.invalid" });
  f.env.GOOGLE_PRIVATE_KEY = pem;
  return f;
}
function request(ip = "192.0.2.1", signal?: AbortSignal) {
  const result = post("/v1/register", { integrityToken: "invalid_fixture", registration: { nonce: "fixture_nonce_123456" } }, undefined, signal);
  result.headers.set("CF-Connecting-IP", ip);
  return result;
}
it("atomically limits rejected Integrity attempts per IP before either Google endpoint", async () => {
  const f = production(); Object.assign(f.env, { REGISTRATION_ATTEMPTS_PER_IP_MINUTE: "2" });
  let oauth = 0; let decode = 0;
  const app = worker(f, async (input) => {
    if (String(input).includes("oauth2")) { oauth++; return Response.json({ access_token: "fixture" }); }
    decode++; return new Response(null, { status: 401 });
  });
  const responses = await Promise.all(Array.from({ length: 10 }, () => app.fetch(request(), f.env, f.tasks)));
  expect(responses.filter((r) => r.status === 429)).toHaveLength(8);
  expect(oauth).toBe(2); expect(decode).toBe(2);
  expect((await app.fetch(request("192.0.2.2"), f.env, f.tasks)).status).toBe(401);
  expect(oauth).toBe(3); expect(decode).toBe(3);
  f.setTime(f.now() + 60_000);
  expect((await app.fetch(request(), f.env, f.tasks)).status).toBe(401);
  expect(oauth).toBe(4);
  expect(JSON.stringify(f.events)).not.toContain("192.0.2.");
  expect(JSON.stringify(f.events)).not.toContain("invalid_fixture");
});
it.each(["oauth", "decode"])("times out the Google %s stage and aborts its fetch", async (stage) => {
  vi.useFakeTimers();
  try {
    const f = production(); let entered: () => void = () => undefined; let aborted = false;
    const waiting = new Promise<void>((resolve) => { entered = resolve; });
    const app = worker(f, async (input, init) => {
      if (stage === "decode" && String(input).includes("oauth2")) return Response.json({ access_token: "fixture" });
      entered();
      if (!init?.signal) throw new Error("missing_google_abort_signal");
      return new Promise<Response>((_resolve, reject) => { init.signal!.addEventListener("abort", () => { aborted = true; reject(new DOMException("Aborted", "AbortError")); }, { once: true }); });
    });
    const pending = app.fetch(request(), f.env, f.tasks);
    await waiting; await vi.advanceTimersByTimeAsync(5_000);
    expect((await pending).status).toBe(504); expect(aborted).toBe(true);
  } finally { vi.useRealTimers(); }
});
it("cancels Google verification when the registering client disconnects", async () => {
  const f = production(); const abort = new AbortController();
  let entered: () => void = () => undefined; let aborted = false;
  const waiting = new Promise<void>((resolve) => { entered = resolve; });
  const app = worker(f, async (_input, init) => {
    entered(); if (!init?.signal) throw new Error("missing_google_abort_signal");
    return new Promise<Response>((_resolve, reject) => { init.signal!.addEventListener("abort", () => { aborted = true; reject(new DOMException("Aborted", "AbortError")); }, { once: true }); });
  });
  const pending = app.fetch(request("192.0.2.1", abort.signal), f.env, f.tasks);
  await waiting; abort.abort();
  expect((await pending).status).toBe(499); expect(aborted).toBe(true);
});
