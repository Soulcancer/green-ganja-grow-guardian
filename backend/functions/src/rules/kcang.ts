/**
 * Abgabegrenzen nach § 19 KCanG (Stand Oktober 2026) als reine Funktionen, ohne Firebase.
 * Alle Daten sind Kalendertage im Format "YYYY-MM-DD" (deutsche Zeit).
 */

export const MIN_AGE = 18;
export const YOUNG_ADULT_UNTIL_AGE = 21;
export const DAILY_LIMIT_G = 25;
export const MONTHLY_LIMIT_G = 50;
export const MONTHLY_LIMIT_YOUNG_ADULT_G = 30;
export const YOUNG_ADULT_MAX_THC_PERCENT = 10;

const DATE_PATTERN = /^\d{4}-\d{2}-\d{2}$/;

export function isValidDate(value: unknown): value is string {
  if (typeof value !== "string" || !DATE_PATTERN.test(value)) return false;
  const [y, m, d] = value.split("-").map(Number);
  const date = new Date(Date.UTC(y, m - 1, d));
  return date.getUTCFullYear() === y && date.getUTCMonth() === m - 1 && date.getUTCDate() === d;
}

/** Heutiges Datum in Deutschland, unabhängig von der Serverzeitzone. */
export function berlinDate(now: Date): string {
  const parts = new Intl.DateTimeFormat("en-GB", {
    timeZone: "Europe/Berlin",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).formatToParts(now);
  const part = (type: string) => parts.find((p) => p.type === type)?.value ?? "";
  return `${part("year")}-${part("month")}-${part("day")}`;
}

export function addDays(date: string, days: number): string {
  const [y, m, d] = date.split("-").map(Number);
  return new Date(Date.UTC(y, m - 1, d + days)).toISOString().slice(0, 10);
}

export function addYears(date: string, years: number): string {
  const [y, m, d] = date.split("-").map(Number);
  return new Date(Date.UTC(y + years, m - 1, d)).toISOString().slice(0, 10);
}

export function monthOf(date: string): string {
  return date.slice(0, 7);
}

/** Vollendete Lebensjahre am Stichtag. */
export function ageOn(birthDate: string, date: string): number {
  const [by, bm, bd] = birthDate.split("-").map(Number);
  const [y, m, d] = date.split("-").map(Number);
  const hadBirthday = m > bm || (m === bm && d >= bd);
  return y - by - (hadBirthday ? 0 : 1);
}

export function isYoungAdult(birthDate: string, date: string): boolean {
  const age = ageOn(birthDate, date);
  return age >= MIN_AGE && age < YOUNG_ADULT_UNTIL_AGE;
}

export function monthlyLimitG(birthDate: string, date: string): number {
  return isYoungAdult(birthDate, date) ? MONTHLY_LIMIT_YOUNG_ADULT_G : MONTHLY_LIMIT_G;
}

/** Eine schon gebuchte Menge: ausgegeben oder fest reserviert. */
export interface Booking {
  date: string;
  grams: number;
}

export type AmountCheck =
  | { ok: true; remainingMonthG: number }
  | { ok: false; reason: "invalid-amount" | "under-18" | "daily-limit" | "monthly-limit"; remainingG: number };

/** In Zehntelgramm rechnen, damit 0,1 + 0,2 nicht an Rundungsfehlern scheitert. */
const toDg = (grams: number): number => Math.round(grams * 10);

export function isValidGrams(grams: unknown): grams is number {
  return (
    typeof grams === "number" &&
    Number.isFinite(grams) &&
    grams > 0 &&
    grams <= DAILY_LIMIT_G &&
    Math.abs(grams * 10 - Math.round(grams * 10)) < 1e-9
  );
}

/**
 * Prüft, ob [grams] am [date] noch in die Tages- und Monatsgrenze passt.
 * [existing] enthält alle schon gebuchten Mengen des Mitglieds.
 */
export function checkAmount(birthDate: string, date: string, grams: number, existing: Booking[]): AmountCheck {
  if (!isValidGrams(grams)) return { ok: false, reason: "invalid-amount", remainingG: 0 };
  if (ageOn(birthDate, date) < MIN_AGE) return { ok: false, reason: "under-18", remainingG: 0 };

  const month = monthOf(date);
  const usedDay = existing.filter((b) => b.date === date).reduce((sum, b) => sum + toDg(b.grams), 0);
  const usedMonth = existing.filter((b) => monthOf(b.date) === month).reduce((sum, b) => sum + toDg(b.grams), 0);

  const leftDay = toDg(DAILY_LIMIT_G) - usedDay;
  const leftMonth = toDg(monthlyLimitG(birthDate, date)) - usedMonth;
  const wanted = toDg(grams);

  if (wanted > Math.min(leftDay, leftMonth)) {
    const reason = leftDay <= leftMonth ? "daily-limit" : "monthly-limit";
    return { ok: false, reason, remainingG: Math.max(0, Math.min(leftDay, leftMonth)) / 10 };
  }
  return { ok: true, remainingMonthG: (leftMonth - wanted) / 10 };
}

/** Für 18- bis 20-Jährige höchstens 10 % THC. */
export function isThcAllowed(birthDate: string, date: string, thcPercent: unknown): thcPercent is number {
  if (typeof thcPercent !== "number" || !Number.isFinite(thcPercent) || thcPercent < 0 || thcPercent > 100) {
    return false;
  }
  return !isYoungAdult(birthDate, date) || thcPercent <= YOUNG_ADULT_MAX_THC_PERCENT;
}
