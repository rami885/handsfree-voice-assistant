import { type AuditLogger, type DurableNamespace, type Env, type Execution, HttpError, json, object, readJson, validateEnv } from "./platform";
import { clientIpKey } from "./client-ip";
import { issueToken, verifyToken, hexHash } from "./identity";
import { GoogleIntegrity, registration, requestHash, verifyVerdict } from "./integrity";
import { planInput, providerRequest, UsageParser } from "./provider";
export { InstallBudget, GlobalBudget } from "./budget";

interface Dependencies { fetcher: typeof fetch; now: () => number; logger: AuditLogger }
const defaults: Dependencies = { fetcher: (...args) => fetch(...args), now: () => Date.now(), logger: { record: (event) => console.log(JSON.stringify(event)) } };
async function budget(namespace: DurableNamespace, name: string, body: Record<string, unknown>): Promise<boolean> {
  for (let attempt = 0; attempt < 3; attempt++) {
    try {
      const response = await namespace.get(namespace.idFromName(name)).fetch("https://budget.internal/transaction", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) });
      if (response.status === 429) return false;
      if (!response.ok) throw new Error();
      const result: unknown = await response.json();
      if (!object(result) || typeof result.allowed !== "boolean") throw new Error();
      return result.allowed;
    } catch { if (attempt === 2) throw new HttpError(503, "budget_unavailable"); await new Promise((resolve) => setTimeout(resolve, 10 * 2 ** attempt)); }
  }
  throw new HttpError(503, "budget_unavailable");
}
export function createWorker(overrides: Partial<Dependencies> = {}) {
  const deps = { ...defaults, ...overrides };
  return {
    async fetch(request: Request, env: Env, ctx: Execution): Promise<Response> {
      const requestId = crypto.randomUUID();
      try {
        const url = new URL(request.url);
        if (!["/v1/register", "/v1/plan"].includes(url.pathname)) throw new HttpError(404, "not_found");
        if (request.method !== "POST") throw new HttpError(405, "post_required");
        if (url.search) throw new HttpError(400, "query_not_allowed");
        validateEnv(env);
        const now = deps.now(); const day = new Date(now).toISOString().slice(0, 10);
        if (url.pathname === "/v1/register") {
          if (request.signal.aborted) throw new HttpError(499, "request_cancelled");
          const ipKey = await clientIpKey(request, env);
          if (!await budget(env.GLOBAL_BUDGETS, `registration-ip:${ipKey}`, { op: "registration_attempt", day, window: Math.floor(now / 60_000), id: requestId })) throw new HttpError(429, "registration_attempts_exceeded");
          const input = await readJson(request, 32_768);
          if (!object(input) || typeof input.integrityToken !== "string" || !input.integrityToken || Object.keys(input).some((key) => !["integrityToken", "registration"].includes(key))) throw new HttpError(400, "invalid_registration_request");
          const fields = registration(input.registration);
          const hash = await requestHash(fields);
          const verdictKey = env.INTEGRITY_BYPASS === "true" ? await hexHash(`development:${env.EXPECTED_PACKAGE}`) : await verifyVerdict(await new GoogleIntegrity(env, deps.fetcher, deps.now).decode(input.integrityToken, request.signal), hash, env, now);
          const previous = fields.previousToken ? await verifyToken(env.INSTALL_TOKEN_SECRET, fields.previousToken, now, true) : undefined;
          if (!await budget(env.GLOBAL_BUDGETS, "global", { op: "register", day, verdictKey, id: requestId })) throw new HttpError(429, "registration_or_global_cap_reached");
          const token = await issueToken(env.INSTALL_TOKEN_SECRET, now, previous?.id);
          deps.logger.record({ code: "registered", requestId });
          return json({ token, expiresAt: now + 30 * 24 * 60 * 60 * 1000 });
        }
        const authorization = request.headers.get("Authorization");
        if (!authorization?.startsWith("Bearer ")) throw new HttpError(401, "installation_token_required");
        const identity = await verifyToken(env.INSTALL_TOKEN_SECRET, authorization.slice(7), now);
        const planned = providerRequest(planInput(await readJson(request)), env);
        const reservation = { id: requestId, day };
        const settle = async (cost: number, consumed: boolean) => {
          const results = await Promise.allSettled([
            budget(env.INSTALL_BUDGETS, identity.id, { op: "settle", ...reservation, cost, consumed }),
            budget(env.GLOBAL_BUDGETS, "global", { op: "settle", ...reservation, cost, consumed }),
          ]);
          const pending = results.some((result) => result.status === "rejected" || !result.value);
          deps.logger.record({ code: pending ? "settlement_pending" : "settled", requestId, costMicros: cost });
        };
        if (request.signal.aborted) throw new HttpError(499, "request_cancelled");
        try {
          if (!await budget(env.INSTALL_BUDGETS, identity.id, { op: "reserve", ...reservation, amount: planned.reserveMicros })) {
            deps.logger.record({ code: "quota_rejected", requestId }); throw new HttpError(429, "installation_quota_reached");
          }
        } catch (error) { await settle(0, false); throw error; }
        try {
          if (!await budget(env.GLOBAL_BUDGETS, "global", { op: "reserve", ...reservation, amount: planned.reserveMicros })) {
            deps.logger.record({ code: "quota_rejected", requestId }); throw new HttpError(429, "global_spend_cap_reached");
          }
        } catch (error) { await settle(0, false); throw error; }
        const abort = new AbortController();
        const timeout = setTimeout(() => abort.abort(), 30_000);
        let invoked = false;
        const interrupted = () => abort.abort();
        try {
          if (request.signal.aborted) { await settle(0, false); throw new HttpError(499, "request_cancelled"); }
          request.signal.addEventListener("abort", interrupted, { once: true });
          invoked = true;
          const response = await deps.fetcher("https://api.anthropic.com/v1/messages", { method: "POST", headers: { "x-api-key": env.MODEL_API_KEY, "anthropic-version": "2023-06-01", "Content-Type": "application/json" }, body: planned.body, signal: abort.signal });
          if (!response.ok || !response.body || !response.headers.get("content-type")?.includes("text/event-stream")) {
            await response.body?.cancel(); deps.logger.record({ code: "provider_error", requestId, status: response.status });
            throw new HttpError(502, "provider_unavailable");
          }
          const reader = response.body.getReader(); const usage = new UsageParser(planned);
          let cancelled = false; let completed: Promise<void> = Promise.resolve();
          const stream = new ReadableStream<Uint8Array>({
            start(controller) {
              completed = (async () => {
                let clean = false;
                try {
                  while (true) {
                    const next = await reader.read(); if (next.done) break;
                    usage.feed(next.value); controller.enqueue(next.value);
                  }
                  if (!cancelled) { clean = true; controller.close(); }
                } catch {
                  deps.logger.record({ code: "stream_interrupted", requestId });
                  if (!cancelled) controller.error(new Error("upstream_stream_interrupted"));
                } finally {
                  clearTimeout(timeout); request.signal.removeEventListener("abort", interrupted);
                  reader.releaseLock();
                  await settle(clean ? usage.actualCost() ?? planned.reserveMicros : planned.reserveMicros, true);
                }
              })();
              ctx.waitUntil(completed);
            },
            async cancel() { cancelled = true; abort.abort(); await reader.cancel().catch(() => undefined); await completed; },
          });
          return new Response(stream, { headers: { "Content-Type": "text/event-stream", "Cache-Control": "no-store" } });
        } catch (error) {
          clearTimeout(timeout); request.signal.removeEventListener("abort", interrupted); abort.abort();
          if (invoked) { await settle(planned.reserveMicros, true); deps.logger.record({ code: "provider_error", requestId }); }
          throw error;
        }
      } catch (error) {
        const status = error instanceof HttpError ? error.status : 503;
        deps.logger.record({ code: "rejected", requestId, status });
        return json({ code: error instanceof HttpError ? error.code : "service_unavailable", requestId }, status);
      }
    },
  };
}
export default createWorker();
