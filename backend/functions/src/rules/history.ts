/**
 * Messwert-Verlauf: Aufbewahrung je Stufe und Tagesstatistik.
 *
 * Gespeichert wird je Box und UTC-Tag ein Dokument `history/{yyyy-MM-dd}` mit
 * `s: { "HHmm": { metricId: Wert } }` (HHmm in UTC, 5-Minuten-Raster).
 */
import type { Tier } from "./grow";

const DAY_MS = 24 * 60 * 60 * 1000;

/** Tage im Verlauf je Stufe, null = unbegrenzt. Muss zu FarmerTier.historyDays passen. */
export const HISTORY_DAYS: Record<Tier, number | null> = {
  freemium: 7,
  premium: 365,
  social_club: null,
};

/** Ab so vielen Tagen Aufbewahrung lohnt sich eine Tagesstatistik (für 30-Tage- und Jahresansicht). */
export const STATS_MIN_DAYS = 30;

/** Höchstens ein Wert alle 5 Minuten. */
export const MAX_SLOTS_PER_DAY = 288;

export function isDayId(value: string): boolean {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) return false;
  const ms = Date.parse(`${value}T00:00:00Z`);
  return Number.isFinite(ms) && new Date(ms).toISOString().slice(0, 10) === value;
}

export function dayStartMs(day: string): number {
  return Date.parse(`${day}T00:00:00Z`);
}

export function dayIdOf(ms: number): string {
  return new Date(ms).toISOString().slice(0, 10);
}

/** Wann das Tagesdokument gelöscht wird (ms), null = nie. Ein Tag Puffer, damit die Ansicht volle Tage zeigt. */
export function historyExpireAt(day: string, tier: Tier): number | null {
  const days = HISTORY_DAYS[tier];
  return days === null ? null : dayStartMs(day) + (days + 1) * DAY_MS;
}

/** Statistik nur für Stufen mit langem Verlauf; abgeleitet aus der Löschfrist des Tagesdokuments. */
export function wantsStats(day: string, expireAtMs: number | null): boolean {
  return expireAtMs === null || expireAtMs - dayStartMs(day) >= STATS_MIN_DAYS * DAY_MS;
}

/** Jahres-Statistik bleibt so lange wie die längste darin enthaltene Tageszeile. */
export function statsExpireAt(year: number, tier: Tier): number | null {
  const days = HISTORY_DAYS[tier];
  return days === null ? null : Date.UTC(year + 1, 0, 1) + (days + 1) * DAY_MS;
}

export type DayStats = Record<string, [min: number, avg: number, max: number]>;

const round = (value: number) => Math.round(value * 1000) / 1000;

/** Minimum, Mittelwert und Maximum je Messgröße eines Tages. */
export function dayStats(samples: unknown): DayStats {
  const collected = new Map<string, number[]>();
  if (samples && typeof samples === "object") {
    for (const values of Object.values(samples as Record<string, unknown>)) {
      if (!values || typeof values !== "object") continue;
      for (const [metric, value] of Object.entries(values as Record<string, unknown>)) {
        if (typeof value !== "number" || !Number.isFinite(value)) continue;
        const list = collected.get(metric) ?? [];
        list.push(value);
        collected.set(metric, list);
      }
    }
  }
  const stats: DayStats = {};
  for (const [metric, values] of collected) {
    const sum = values.reduce((a, b) => a + b, 0);
    stats[metric] = [round(Math.min(...values)), round(sum / values.length), round(Math.max(...values))];
  }
  return stats;
}

/** "MMdd" für die Jahres-Statistik. */
export function statsKey(day: string): string {
  return day.slice(5, 7) + day.slice(8, 10);
}
