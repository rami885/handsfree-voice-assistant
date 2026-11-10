import { expect, it } from "vitest";
import { providerRequest } from "../src/provider";
import { credential, plan, post, providerResponse, setup, totals, worker } from "./support";

it.each(["missing_delta", "missing_count", "negative_count"])("retains the full reservation for invalid final usage: %s", async (variant) => {
  const f = setup(); const { token, identity } = await credential(f);
  const start = 'data: {"type":"message_start","message":{"usage":{"input_tokens":20,"output_tokens":1}}}\n\n';
  const delta = variant === "missing_delta" ? "" : `data: ${JSON.stringify({ type: "message_delta", usage: variant === "negative_count" ? { output_tokens: -1 } : {} })}\n\n`;
  const response = await worker(f, async () => providerResponse(`${start}${delta}data: {"type":"message_stop"}\n\n`)).fetch(post("/v1/plan", plan, token), f.env, f.tasks);
  await response.text(); await f.tasks.drain();
  expect(totals(f.install, identity.id)?.usedMicros).toBe(providerRequest(plan, f.env).reserveMicros);
  expect(totals(f.global, "global")?.usedMicros).toBe(providerRequest(plan, f.env).reserveMicros);
});
