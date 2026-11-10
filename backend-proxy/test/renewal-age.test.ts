import { expect, it } from "vitest";
import { issueToken, TOKEN_LIFETIME_MS, verifyToken } from "../src/identity";
import { NOW, post, setup, worker } from "./support";

const grace = 30 * 24 * 60 * 60 * 1000;
it.each([0, grace, grace + 1])("bounds renewal to thirty days after expiry: %d", async (age) => {
  const f = setup(); const issuedAt = NOW - TOKEN_LIFETIME_MS - age;
  const old = await issueToken(f.env.INSTALL_TOKEN_SECRET, issuedAt);
  await expect(verifyToken(f.env.INSTALL_TOKEN_SECRET, old, NOW)).rejects.toThrow();
  const response = await worker(f).fetch(post("/v1/register", { integrityToken: "fixture", registration: { nonce: "fixture_nonce_renewal", previousToken: old } }), f.env, f.tasks);
  expect(response.status).toBe(age <= grace ? 200 : 401);
  if (age <= grace) {
    const body = await response.json() as { token: string };
    expect((await verifyToken(f.env.INSTALL_TOKEN_SECRET, body.token, NOW)).id).toBe((await verifyToken(f.env.INSTALL_TOKEN_SECRET, old, NOW, true)).id);
  } else await expect(verifyToken(f.env.INSTALL_TOKEN_SECRET, old, NOW, true)).rejects.toThrow("invalid_installation_token");
});
