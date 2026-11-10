import { readFileSync } from "node:fs";
import { expect, it } from "vitest";
import { credential, plan, post, setup, totals, worker } from "./support";
import { providerRequest } from "../src/provider";

it("explicitly enables incoming request cancellation in Workers", () => {
  const config = readFileSync(new URL("../wrangler.toml", import.meta.url), "utf8");
  expect(config).toMatch(/compatibility_flags\s*=\s*\[[^\]]*"enable_request_signal"/);
});
it("aborts the provider while its response headers are pending", async () => {
  const f = setup(); const { token, identity } = await credential(f);
  const abort = new AbortController(); let providerAborted = false;
  let entered: () => void = () => undefined;
  const waiting = new Promise<void>((resolve) => { entered = resolve; });
  const app = worker(f, async (_input, init) => {
    entered();
    return new Promise<Response>((_resolve, reject) => {
      init?.signal?.addEventListener("abort", () => { providerAborted = true; reject(new DOMException("Aborted", "AbortError")); }, { once: true });
    });
  });
  const pending = app.fetch(post("/v1/plan", plan, token, abort.signal), f.env, f.tasks);
  await waiting; abort.abort();
  expect((await pending).status).toBe(503);
  expect(providerAborted).toBe(true);
  expect(totals(f.install, identity.id)?.usedMicros).toBe(providerRequest(plan, f.env).reserveMicros);
});
