import { type DurableState, type Env, HttpError, json, natural, numberSetting, object } from "./platform";

interface Totals { usedMicros: number; reservedMicros: number; usedRequests: number; reservedRequests: number }
interface Reservation { amount: number; settled: boolean; charged?: number; consumed?: boolean }
export class AtomicBudget {
  private readonly limit: number;
  private readonly requests: number;
  private readonly registrationLimit: number;
  private readonly registrationAttempts: number;
  constructor(private readonly state: DurableState, env: Env, private readonly global: boolean) {
    this.limit = numberSetting(global ? env.GLOBAL_DAILY_BUDGET_MICROS : env.INSTALL_DAILY_BUDGET_MICROS, "daily_budget");
    this.requests = global ? Number.MAX_SAFE_INTEGER : numberSetting(env.INSTALL_DAILY_REQUESTS, "daily_requests");
    this.registrationAttempts = numberSetting(env.REGISTRATION_ATTEMPTS_PER_IP_MINUTE, "registration_attempt_limit", false);
    this.registrationLimit = numberSetting(env.REGISTRATIONS_PER_VERDICT_DAILY, "registration_limit");
  }
  async fetch(request: Request): Promise<Response> {
    try {
      const body: unknown = await request.json();
      if (!object(body) || typeof body.day !== "string" || !/^\d{4}-\d{2}-\d{2}$/.test(body.day)) throw new HttpError(400, "invalid_budget_request");
      const day = body.day;
      const result = await this.state.storage.transaction(async (tx) => {
        const totalsKey = `${day}:totals`;
        const totals = await tx.get<Totals>(totalsKey) ?? { usedMicros: 0, reservedMicros: 0, usedRequests: 0, reservedRequests: 0 };
        if (body.op === "registration_attempt") {
          if (!this.global || !natural(body.window) || typeof body.id !== "string" || !/^[a-zA-Z0-9-]{1,80}$/.test(body.id)) throw new HttpError(400, "invalid_registration_attempt");
          const previous = await tx.get<{ window: number; receipts: string[] }>("registration-attempts");
          if (previous && previous.window > body.window) return false;
          const attempts = previous?.window === body.window ? previous : { window: body.window, receipts: [] };
          if (attempts.receipts.includes(body.id)) return true;
          if (attempts.receipts.length >= this.registrationAttempts) return false;
          attempts.receipts.push(body.id);
          await tx.put("registration-attempts", attempts); return true;
        }
        if (body.op === "register" || body.op === "renew") {
          if (!this.global || typeof body.verdictKey !== "string" || !/^[a-f0-9]{64}$/.test(body.verdictKey)) throw new HttpError(400, "invalid_verdict_key");
          if (typeof body.id !== "string" || !/^[a-zA-Z0-9-]{1,80}$/.test(body.id)) throw new HttpError(400, "invalid_registration_id");
          if (typeof body.requestHash !== "string" || !/^[a-f0-9]{64}$/.test(body.requestHash) || !natural(body.now) || !natural(body.now + 330_001)) throw new HttpError(400, "invalid_registration_hash");
          const receipt = `${day}:registration-request:${body.id}`;
          if (await tx.get<boolean>(receipt)) return true;
          // Not day-scoped: an attestation can remain valid across UTC midnight.
          const used = await tx.get<Record<string, number>>("registration-replays") ?? {};
          for (const [hash, expiresAt] of Object.entries(used)) if (expiresAt <= body.now) delete used[hash];
          if (used[body.requestHash] !== undefined) throw new HttpError(409, "registration_request_replayed");
          const key = `${day}:registration:${body.verdictKey}`;
          const count = await tx.get<number>(key) ?? 0;
          if (body.op === "register" && (count >= this.registrationLimit || totals.usedMicros + totals.reservedMicros >= this.limit)) return false;
          // Covers the five-minute verdict age plus its allowed 30-second future skew, inclusive.
          used[body.requestHash] = body.now + 330_001;
          await tx.put("registration-replays", used);
          if (body.op === "register") await tx.put(key, count + 1);
          await tx.put(receipt, true); return true;
        }
        if (typeof body.id !== "string" || !/^[a-zA-Z0-9-]{1,80}$/.test(body.id)) throw new HttpError(400, "invalid_reservation_id");
        const key = `${day}:reservation:${body.id}`;
        const previous = await tx.get<Reservation>(key);
        if (body.op === "reserve") {
          if (!natural(body.amount) || body.amount < 1) throw new HttpError(400, "invalid_reservation_amount");
          if (previous) return !previous.settled && previous.amount === body.amount;
          if (body.amount > this.limit - totals.usedMicros - totals.reservedMicros || totals.usedRequests + totals.reservedRequests >= this.requests) return false;
          totals.reservedMicros += body.amount; totals.reservedRequests++;
          await tx.put(key, { amount: body.amount, settled: false } satisfies Reservation);
        } else if (body.op === "settle") {
          if (!natural(body.cost) || typeof body.consumed !== "boolean") throw new HttpError(400, "invalid_settlement");
          if (!previous) return true;
          if (previous.settled) {
            if (previous.charged !== body.cost || previous.consumed !== body.consumed) throw new HttpError(409, "settlement_mismatch");
            return true;
          }
          if (body.cost > previous.amount || (!body.consumed && body.cost !== 0)) throw new HttpError(400, "settlement_exceeds_reservation");
          totals.reservedMicros -= previous.amount; totals.reservedRequests--;
          totals.usedMicros += body.cost; totals.usedRequests += body.consumed ? 1 : 0;
          await tx.put(key, { ...previous, settled: true, charged: body.cost, consumed: body.consumed });
        } else throw new HttpError(400, "invalid_budget_operation");
        await tx.put(totalsKey, totals); return true;
      });
      return json({ allowed: result }, result ? 200 : 429);
    } catch (error) { return json({ code: error instanceof HttpError ? error.code : "budget_unavailable" }, error instanceof HttpError ? error.status : 503); }
  }
}
export class InstallBudget extends AtomicBudget { constructor(state: DurableState, env: Env) { super(state, env, false); } }
export class GlobalBudget extends AtomicBudget { constructor(state: DurableState, env: Env) { super(state, env, true); } }
