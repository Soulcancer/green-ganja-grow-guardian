/**
 * Grow-Boxen: Eingaben prüfen und Limits der Stufen.
 * Gleiche Regel wie GrowAccess.kt in der App: Aktiv sind die ältesten Boxen bis zum Box-Limit,
 * darin die Sensoren in Zuordnungsreihenfolge bis zum Sensor-Limit.
 */

export type Tier = "freemium" | "premium" | "social_club";

export interface TierLimits {
  /** null = unbegrenzt */
  maxBoxes: number | null;
  maxDevices: number | null;
}

/** Muss zu FarmerTier.kt passen. */
export const TIER_LIMITS: Record<Tier, TierLimits> = {
  freemium: { maxBoxes: 1, maxDevices: 5 },
  premium: { maxBoxes: 3, maxDevices: 30 },
  social_club: { maxBoxes: null, maxDevices: null },
};

/** Obergrenzen gegen Missbrauch, auch für Social Club. */
export const HARD_MAX_BOXES = 200;
export const HARD_MAX_DEVICES_PER_BOX = 100;

export const PHASES = ["seedling", "vegetative", "flowering", "late_flowering", "drying"] as const;
export const MEDIA = ["soil", "coco", "hydro"] as const;
export const METRICS = [
  "air_temperature", "air_humidity", "vpd", "co2", "soil_moisture", "soil_temperature",
  "water_temperature", "ph", "ec", "tds", "power",
] as const;

export interface GrowBoxInput {
  boxId: string | null;
  name: string;
  phase: (typeof PHASES)[number];
  medium: (typeof MEDIA)[number];
  leafOffsetC: number;
  deviceIds: string[];
  targets: Record<string, { min: number; max: number }>;
  /** Alarme für diese Box (nur mit Premium oder Social Club wirksam). */
  alerts: boolean;
  /** Sorte, frei eingegeben; null = keine Angabe. */
  strain: string | null;
  /** Starttag des Grows "YYYY-MM-DD", null = keine Angabe. */
  plantedAt: string | null;
}

/** "YYYY-MM-DD", ein echter Kalendertag zwischen 2000 und 2100. */
export function isPlainDate(value: string): boolean {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) return false;
  const ms = Date.parse(`${value}T00:00:00Z`);
  return Number.isFinite(ms) && new Date(ms).toISOString().slice(0, 10) === value && value >= "2000-01-01" && value <= "2100-12-31";
}

/** Bereinigte Eingabe oder ein deutscher Fehlertext. */
export function parseGrowBox(data: unknown): GrowBoxInput | string {
  const d = (data ?? {}) as Record<string, unknown>;
  const boxId = d.boxId === undefined || d.boxId === null ? null : d.boxId;
  if (boxId !== null && !(typeof boxId === "string" && /^[A-Za-z0-9_-]{1,128}$/.test(boxId))) return "Ungültige Box.";

  const name = typeof d.name === "string" ? d.name.trim().replace(/\s+/g, " ") : "";
  if (name.length < 1 || name.length > 40) return "Der Name muss 1 bis 40 Zeichen haben.";
  if (!PHASES.includes(d.phase as never)) return "Unbekannte Wachstumsphase.";
  if (!MEDIA.includes(d.medium as never)) return "Unbekanntes Medium.";

  const leaf = typeof d.leafOffsetC === "number" ? d.leafOffsetC : -2;
  if (!Number.isFinite(leaf) || leaf < -5 || leaf > 5) return "Der Blatt-Offset muss zwischen −5 und +5 °C liegen.";

  if (!Array.isArray(d.deviceIds)) return "Ungültige Sensorliste.";
  const deviceIds: string[] = [];
  for (const id of d.deviceIds) {
    if (typeof id !== "string" || !/^[A-Za-z0-9_-]{1,64}$/.test(id)) return "Ungültige Sensor-ID.";
    if (!deviceIds.includes(id)) deviceIds.push(id);
  }
  if (deviceIds.length > HARD_MAX_DEVICES_PER_BOX) return "Zu viele Sensoren in einer Box.";

  const targets: Record<string, { min: number; max: number }> = {};
  const rawTargets = d.targets ?? {};
  if (typeof rawTargets !== "object" || Array.isArray(rawTargets)) return "Ungültige Zielwerte.";
  for (const [key, value] of Object.entries(rawTargets as Record<string, unknown>)) {
    if (!METRICS.includes(key as never)) return "Unbekannte Messgröße.";
    const range = value as { min?: unknown; max?: unknown } | null;
    const min = range?.min;
    const max = range?.max;
    if (typeof min !== "number" || typeof max !== "number" || !Number.isFinite(min) || !Number.isFinite(max)) {
      return "Zielwerte müssen Zahlen sein.";
    }
    if (min > max || Math.abs(min) > 100_000 || Math.abs(max) > 100_000) return "„von“ muss kleiner als „bis“ sein.";
    targets[key] = { min, max };
  }

  let strain: string | null = null;
  if (d.strain !== undefined && d.strain !== null) {
    if (typeof d.strain !== "string" || d.strain.trim().length > 60) return "Die Sorte darf höchstens 60 Zeichen haben.";
    strain = d.strain.trim().replace(/\s+/g, " ") || null;
  }
  let plantedAt: string | null = null;
  if (d.plantedAt !== undefined && d.plantedAt !== null) {
    if (typeof d.plantedAt !== "string" || !isPlainDate(d.plantedAt)) return "Ungültiger Starttag.";
    plantedAt = d.plantedAt;
  }

  return {
    boxId: boxId as string | null,
    name,
    phase: d.phase as GrowBoxInput["phase"],
    medium: d.medium as GrowBoxInput["medium"],
    leafOffsetC: leaf,
    deviceIds,
    targets,
    alerts: d.alerts !== false,
    strain,
    plantedAt,
  };
}

export interface ExistingBox {
  id: string;
  /** Anlegezeit in ms */
  createdAt: number;
  deviceIds: string[];
}

export type LimitResult =
  | { ok: true }
  | { ok: false; reason: "box-limit" | "box-locked" | "device-limit" | "not-found" };

/** Boxen in Anlegereihenfolge, bei gleicher Zeit nach ID. */
export function sortBoxes(boxes: ExistingBox[]): ExistingBox[] {
  return [...boxes].sort((a, b) => a.createdAt - b.createdAt || (a.id < b.id ? -1 : a.id > b.id ? 1 : 0));
}

function assignedInActive(boxes: ExistingBox[], limits: TierLimits): number {
  const active = limits.maxBoxes === null ? boxes : boxes.slice(0, limits.maxBoxes);
  return new Set(active.flatMap((b) => b.deviceIds)).size;
}

/**
 * Darf die Änderung mit diesen Limits gespeichert werden?
 * Ein Sensor gehört immer zu genau einer Box: Er wird aus anderen Boxen entfernt.
 * Wer schon über dem Sensor-Limit liegt (z. B. nach einem Downgrade), darf ändern, solange es nicht mehr werden.
 */
export function checkGrowLimits(existing: ExistingBox[], change: { boxId: string | null; deviceIds: string[] }, limits: TierLimits): LimitResult {
  const before = sortBoxes(existing);
  let after: ExistingBox[];

  if (change.boxId === null) {
    if (before.length >= HARD_MAX_BOXES) return { ok: false, reason: "box-limit" };
    if (limits.maxBoxes !== null && before.length >= limits.maxBoxes) return { ok: false, reason: "box-limit" };
    after = [
      ...before.map((b) => ({ ...b, deviceIds: b.deviceIds.filter((id) => !change.deviceIds.includes(id)) })),
      { id: "￿-new", createdAt: Number.MAX_SAFE_INTEGER, deviceIds: change.deviceIds },
    ];
  } else {
    const index = before.findIndex((b) => b.id === change.boxId);
    if (index < 0) return { ok: false, reason: "not-found" };
    if (limits.maxBoxes !== null && index >= limits.maxBoxes) return { ok: false, reason: "box-locked" };
    after = before.map((b) =>
      b.id === change.boxId
        ? { ...b, deviceIds: change.deviceIds }
        : { ...b, deviceIds: b.deviceIds.filter((id) => !change.deviceIds.includes(id)) },
    );
  }

  if (limits.maxDevices !== null) {
    const oldCount = assignedInActive(before, limits);
    const newCount = assignedInActive(after, limits);
    if (newCount > limits.maxDevices && newCount > oldCount) return { ok: false, reason: "device-limit" };
  }
  return { ok: true };
}

/** Höchste aktive Stufe aus den Lookup-Keys der aktiven Entitlements. */
export function tierFromEntitlements(activeLookupKeys: string[]): Tier {
  if (activeLookupKeys.includes("social_club_farmer")) return "social_club";
  if (activeLookupKeys.includes("premium_farmer")) return "premium";
  return "freemium";
}
