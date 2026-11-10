import { beforeAll, describe, expect, it } from "vitest";
import { base64url, hexHash, issueToken, TOKEN_LIFETIME_MS, verifyToken } from "../src/identity";
import { registration, requestHash, verifyVerdict } from "../src/integrity";
import { NOW, post, setup, worker } from "./support";

let pem = ""; let publicKey: CryptoKey;
beforeAll(async () => {
  const pair = await crypto.subtle.generateKey({ name: "RSASSA-PKCS1-v1_5", modulusLength: 2048, publicExponent: new Uint8Array([1, 0, 1]), hash: "SHA-256" }, true, ["sign", "verify"]);
  publicKey = pair.publicKey;
  pem = `-----BEGIN PRIVATE KEY-----\n${btoa(String.fromCharCode(...new Uint8Array(await crypto.subtle.exportKey("pkcs8", pair.privateKey))))}\n-----END PRIVATE KEY-----`;
});
function verdict(hash: string) {
  return { requestDetails: { requestHash: hash, requestPackageName: "com.handsfree.assistant", timestampMillis: String(NOW) },
    appIntegrity: { appRecognitionVerdict: "PLAY_RECOGNIZED", packageName: "com.handsfree.assistant", certificateSha256Digest: ["fixture-certificate-digest"] },
    deviceIntegrity: { deviceRecognitionVerdict: ["MEETS_DEVICE_INTEGRITY"] } };
}
describe("standard Play Integrity", () => {
  it("uses signed service-account OAuth and the Google decode endpoint before issuing an identity", async () => {
    const f = setup({ ENVIRONMENT: "production", INTEGRITY_BYPASS: "false", GOOGLE_SERVICE_ACCOUNT_EMAIL: "fixture@project.iam.gserviceaccount.com" }); f.env.GOOGLE_PRIVATE_KEY = pem;
    const fields = { nonce: "fixture_nonce_123456" }; const expectedHash = await requestHash(fields); let oauthCalls = 0; let decodeCalls = 0;
    const app = worker(f, async (input, init) => {
      if (String(input) === "https://oauth2.googleapis.com/token") {
        oauthCalls++; const form = new URLSearchParams(String(init?.body)); const assertion = form.get("assertion")!;
        const parts = assertion.split("."); const sig = parts[2]!; const bytes = Uint8Array.from(atob(sig.replace(/-/g, "+").replace(/_/g, "/").padEnd(Math.ceil(sig.length / 4) * 4, "=")), (c) => c.charCodeAt(0));
        expect(await crypto.subtle.verify("RSASSA-PKCS1-v1_5", publicKey, bytes, new TextEncoder().encode(`${parts[0]}.${parts[1]}`))).toBe(true);
        return Response.json({ access_token: "oauth-fixture" });
      }
      expect(String(input)).toBe("https://playintegrity.googleapis.com/v1/com.handsfree.assistant:decodeIntegrityToken"); decodeCalls++;
      expect(new Headers(init?.headers).get("Authorization")).toBe("Bearer oauth-fixture"); expect(JSON.parse(String(init?.body))).toEqual({ integrity_token: "integrity-fixture" });
      return Response.json({ tokenPayloadExternal: verdict(expectedHash) });
    });
    const response = await app.fetch(post("/v1/register", { integrityToken: "integrity-fixture", registration: fields }), f.env, f.tasks);
    expect(response.status).toBe(200); const result = await response.json() as { token: string };
    expect((await verifyToken(f.env.INSTALL_TOKEN_SECRET, result.token, NOW)).expiresAt).toBe(NOW + TOKEN_LIFETIME_MS);
    expect(oauthCalls).toBe(1); expect(decodeCalls).toBe(1); expect(JSON.stringify(f.events)).not.toContain("integrity-fixture"); expect(JSON.stringify(f.events)).not.toContain("oauth-fixture");
  });
  it("rejects bad hash, package, recognition, device verdict, certificate and timestamps", async () => {
    const f = setup(); const hash = await requestHash({ nonce: "fixture_nonce_123456" }); const valid = verdict(hash);
    const invalid = [
      { ...valid, requestDetails: { ...valid.requestDetails, requestHash: "wrong" } },
      { ...valid, requestDetails: { ...valid.requestDetails, requestPackageName: "other.app" } },
      { ...valid, appIntegrity: { ...valid.appIntegrity, appRecognitionVerdict: "UNEVALUATED" } },
      { ...valid, appIntegrity: { ...valid.appIntegrity, packageName: "other.app" } },
      { ...valid, appIntegrity: { ...valid.appIntegrity, certificateSha256Digest: ["wrong"] } },
      { ...valid, deviceIntegrity: { deviceRecognitionVerdict: ["MEETS_BASIC_INTEGRITY"] } },
      { ...valid, requestDetails: { ...valid.requestDetails, timestampMillis: String(NOW - 300001) } },
      { ...valid, requestDetails: { ...valid.requestDetails, timestampMillis: String(NOW + 30001) } }, {},
    ];
    for (const value of invalid) await expect(verifyVerdict(value, hash, f.env, NOW)).rejects.toThrow("integrity");
    const key = await verifyVerdict(valid, hash, f.env, NOW);
    const fresh = verdict(await requestHash({ nonce: "different_nonce_123456" }));
    expect(await verifyVerdict(fresh, fresh.requestDetails.requestHash, f.env, NOW)).toBe(key);
    expect(key).toMatch(/^[a-f0-9]{64}$/);
  });
  it("rejects an empty previous token instead of silently creating a new installation", () => {
    expect(() => registration({ nonce: "fixture_nonce_123456", previousToken: "" })).toThrow("invalid_registration");
  });
  it("binds renewal fields into the standard request hash", async () => {
    const a = await requestHash({ nonce: "fixture_nonce_123456" });
    expect(await requestHash({ nonce: "fixture_nonce_123456", previousToken: "changed" })).not.toBe(a);
    expect(await requestHash({ previousToken: "changed", nonce: "fixture_nonce_123456" })).toBe(await requestHash({ nonce: "fixture_nonce_123456", previousToken: "changed" }));
  });
  it("accepts a freshly attested renewal after expiry while rejecting the expired plan credential", async () => {
    const f = setup(); const old = await issueToken(f.env.INSTALL_TOKEN_SECRET, NOW - TOKEN_LIFETIME_MS - 1);
    await expect(verifyToken(f.env.INSTALL_TOKEN_SECRET, old, NOW)).rejects.toThrow();
    const response = await worker(f).fetch(post("/v1/register", { integrityToken: "fixture", registration: { nonce: "fixture_nonce_123456", previousToken: old } }), f.env, f.tasks);
    expect(response.status).toBe(200); const body = await response.json() as { token: string };
    expect((await verifyToken(f.env.INSTALL_TOKEN_SECRET, body.token, NOW)).id).toBe((await verifyToken(f.env.INSTALL_TOKEN_SECRET, old, NOW, true)).id);
  });
  it("produces URL-safe hashes without recording the registration body", async () => {
    expect(base64url(new Uint8Array([251, 255]))).toBe("-_8"); expect(await hexHash("fixture")).toHaveLength(64);
  });
});
