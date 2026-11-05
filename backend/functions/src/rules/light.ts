/**
 * Lichtmodul: Lampen einer Box prüfen. Muss zu LampCatalog in der App passen
 * (app/src/main/java/de/greenganja/growguardian/grow/light/Lamps.kt).
 */
import type { Tier } from "./grow";

/** Alle Lampenarten der App. */
export const LAMP_IDS = [
  "daylight", "incandescent",
  "led_white_2700", "led_white_3000", "led_white_3000_90", "led_white_3500", "led_white_4000", "led_white_5000",
  "led_white_red", "led_full_uv_ir", "led_cob", "led_blurple", "led_red_660", "led_blue_450", "led_uv", "led_far_red",
  "hps_se", "hps_de", "hps_dual", "mh", "cmh_3100", "cmh_4200",
  "t5_6500", "t5_3000", "cfl_6500", "cfl_2700", "induction",
  "plasma",
] as const;

export type LampId = (typeof LAMP_IDS)[number];

/** Ohne Abo wählbar: nur Tageslicht und Glühbirne. */
export const FREE_LAMP_IDS: readonly LampId[] = ["daylight", "incandescent"];

export const MAX_LAMPS = 6;
export const MAX_WATTS = 3000;
export const MAX_COUNT = 50;

export interface LampUseInput {
  lampId: LampId;
  watts: number | null;
  count: number;
}

export interface LightSetupInput {
  boxId: string;
  lamps: LampUseInput[];
  areaM2: number | null;
  dimPercent: number | null;
  hoursPerDay: number | null;
  calibration: number;
}

function optionalNumber(value: unknown, min: number, max: number): number | null | "invalid" {
  if (value === undefined || value === null) return null;
  if (typeof value !== "number" || !Number.isFinite(value) || value < min || value > max) return "invalid";
  return value;
}

/** Prüft die Eingabe; liefert eine deutsche Fehlermeldung oder die bereinigten Daten. */
export function parseLightSetup(data: unknown): LightSetupInput | string {
  const d = (data ?? {}) as Record<string, unknown>;
  if (typeof d.boxId !== "string" || !/^[A-Za-z0-9_-]{1,128}$/.test(d.boxId)) return "Ungültige Box.";
  if (!Array.isArray(d.lamps) || d.lamps.length < 1) return "Wähl mindestens eine Lampe.";
  if (d.lamps.length > MAX_LAMPS) return `Höchstens ${MAX_LAMPS} Lampen je Box.`;

  const lamps: LampUseInput[] = [];
  for (const raw of d.lamps) {
    const l = (raw ?? {}) as Record<string, unknown>;
    if (!LAMP_IDS.includes(l.lampId as never)) return "Unbekannte Lampe.";
    const watts = optionalNumber(l.watts, 1, MAX_WATTS);
    if (watts === "invalid") return `Leistung je Lampe 1 bis ${MAX_WATTS} W.`;
    const count = l.count === undefined || l.count === null ? 1 : l.count;
    if (typeof count !== "number" || !Number.isInteger(count) || count < 1 || count > MAX_COUNT) return `Anzahl 1 bis ${MAX_COUNT}.`;
    lamps.push({ lampId: l.lampId as LampId, watts, count });
  }

  const areaM2 = optionalNumber(d.areaM2, 0.05, 100);
  if (areaM2 === "invalid") return "Fläche 0,05 bis 100 m².";
  const dimPercent = optionalNumber(d.dimPercent, 10, 100);
  if (dimPercent === "invalid") return "Dimmer 10 bis 100 %.";
  const hoursPerDay = optionalNumber(d.hoursPerDay, 0, 24);
  if (hoursPerDay === "invalid") return "Lichtstunden 0 bis 24.";
  const calibration = optionalNumber(d.calibration, 0.2, 5);
  if (calibration === "invalid") return "Kalibrierfaktor 0,2 bis 5.";

  return { boxId: d.boxId, lamps, areaM2, dimPercent, hoursPerDay, calibration: calibration ?? 1 };
}

export function needsPremium(input: LightSetupInput): boolean {
  return input.lamps.some((l) => !FREE_LAMP_IDS.includes(l.lampId));
}

export function lampsAllowed(input: LightSetupInput, tier: Tier): boolean {
  return tier !== "freemium" || !needsPremium(input);
}

/** Was im Box-Dokument unter `light` landet. lampIds brauchen die Firestore-Regeln für Messungen. */
export function lightField(input: LightSetupInput): Record<string, unknown> {
  return {
    lamps: input.lamps.map((l) => ({ lampId: l.lampId, watts: l.watts, count: l.count })),
    lampIds: [...new Set(input.lamps.map((l) => l.lampId))],
    areaM2: input.areaM2,
    dimPercent: input.dimPercent,
    hoursPerDay: input.hoursPerDay,
    calibration: input.calibration,
  };
}
