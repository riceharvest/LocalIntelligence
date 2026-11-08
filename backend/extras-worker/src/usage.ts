/**
 * Per-customer monthly usage metering and cap enforcement for proxied Extras services.
 *
 * Counters live in `usage(customer_id, kind, period, amount)` where `period` is the UTC
 * `YYYY-MM` month string, so every allowance resets on the 1st of the month.
 */
import type { Env } from "./env";
import { HttpError } from "./http";

export function currentPeriod(now: Date = new Date()): string {
  return now.toISOString().slice(0, 7);
}

export async function addUsage(env: Env, customerId: string, kind: string, amount: number): Promise<void> {
  if (!Number.isFinite(amount) || amount <= 0) return;
  const period = currentPeriod();
  await env.DB.prepare(
    `INSERT INTO usage (customer_id, kind, period, amount) VALUES (?1, ?2, ?3, ?4)
       ON CONFLICT(customer_id, kind, period) DO UPDATE SET amount = usage.amount + excluded.amount`,
  )
    .bind(customerId, kind, period, amount)
    .run();
}

export async function checkCap(
  env: Env,
  customerId: string,
  kind: string,
  cap: number | string | undefined,
): Promise<void> {
  const limit = typeof cap === "number" ? cap : Number(cap);
  const period = currentPeriod();
  const row = await env.DB.prepare(
    "SELECT amount FROM usage WHERE customer_id = ?1 AND kind = ?2 AND period = ?3",
  )
    .bind(customerId, kind, period)
    .first<{ amount: number }>();
  const used = row?.amount ?? 0;
  if (!Number.isFinite(limit) || limit <= 0 || used >= limit) {
    throw new HttpError(429, "over_cap", `This month's ${kind} allowance is used up. It resets on the 1st.`);
  }
}
