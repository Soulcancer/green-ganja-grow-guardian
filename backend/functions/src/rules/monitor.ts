/**
 * Server-Überwachung als reine Funktionen: Tuya-Datenpunkte in Messwerte umrechnen, Zielwerte, VPD,
 * Alarm-Regeln und Automationen. Spiegelt die Regeln der App (DpMapping, DefaultTargets, Vpd, AlarmRules,
 * AutomationRules) und muss mit ihnen zusammen geändert werden.
 */
import { createHash, createHmac } from "node:crypto";

// ---------- Tuya Cloud: Signatur ----------

/** Zu signierender Text nach Tuya-Doku: Methode, SHA-256 des Bodys, (keine signierten Header), Pfad mit Query. */
export function tuyaStringToSign(method: string, body: string, pathWithQuery: string): string {
  return [method.toUpperCase(), createHash("sha256").update(body).digest("hex"), "", pathWithQuery].join("\n");
}

export function tuyaSign(clientId: string, secret: string, accessToken: string, t: string, stringToSign: string, nonce = ""): string {
  return createHmac("sha256", secret).update(clientId + accessToken + t + nonce + stringToSign).digest("hex").toUpperCase();
}

// ---------- Messgrößen ----------

export type MetricId =
  | "air_temperature" | "air_humidity" | "vpd" | "co2" | "soil_moisture" | "soil_temperature"
  | "water_temperature" | "ph" | "ec" | "tds" | "power" | "light";

export const METRIC_INFO: Record<MetricId, { label: string; unit: string; decimals: number }> = {
  air_temperature: { label: "Temperatur", unit: "°C", decimals: 1 },
  air_humidity: { label: "Luftfeuchte", unit: "%", decimals: 0 },
  vpd: { label: "VPD", unit: "kPa", decimals: 2 },
  co2: { label: "CO₂", unit: "ppm", decimals: 0 },
  soil_moisture: { label: "Bodenfeuchte", unit: "%", decimals: 0 },
  soil_temperature: { label: "Bodentemperatur", unit: "°C", decimals: 1 },
  water_temperature: { label: "Wassertemperatur", unit: "°C", decimals: 1 },
  ph: { label: "pH", unit: "", decimals: 2 },
  ec: { label: "EC", unit: "mS/cm", decimals: 2 },
  tds: { label: "TDS", unit: "ppm", decimals: 0 },
  power: { label: "Leistung", unit: "W", decimals: 1 },
  light: { label: "Licht", unit: "lx", decimals: 0 },
};

const TEMPERATURE = ["va_temperature", "temp_current", "temp_value", "temperature"];
const HUMIDITY = ["va_humidity", "humidity_value", "humidity_current", "humidity"];
const CO2 = ["co2_value", "co2_current"];
const PH = ["ph_current", "ph_value"];
const EC = ["ec_current", "ec_value"];
const TDS = ["tds_current", "tds_value"];
const SOIL = ["soil_moisture", "soil_humidity"];
const LIGHT = ["bright_value", "illuminance_value", "lux_value"];
const LIGHT_CATEGORIES = ["ldcg"];
const SOIL_CATEGORIES = ["zwjcy"];
const PLUG_CATEGORIES = ["cz", "pc", "kg"];
const TEMPERATURE_METRICS: MetricId[] = ["air_temperature", "soil_temperature", "water_temperature"];

export type DeviceKind = "soil" | "water" | "co2" | "plug" | "climate" | "light" | "other";

export function kindOf(category: string | undefined, codes: string[]): DeviceKind {
  const has = (list: string[]) => codes.some((c) => list.includes(c));
  if ((category && SOIL_CATEGORIES.includes(category)) || has(SOIL)) return "soil";
  if (has(PH) || has(EC) || has(TDS)) return "water";
  if (has(CO2)) return "co2";
  if ((category && PLUG_CATEGORIES.includes(category)) || codes.includes("cur_power")) return "plug";
  if (has(TEMPERATURE) || has(HUMIDITY)) return "climate";
  if ((category && LIGHT_CATEGORIES.includes(category)) || has(LIGHT)) return "light";
  return "other";
}

export function metricFor(code: string, kind: DeviceKind): MetricId | null {
  if (CO2.includes(code)) return "co2";
  if (PH.includes(code)) return "ph";
  if (EC.includes(code)) return "ec";
  if (TDS.includes(code)) return "tds";
  if (SOIL.includes(code)) return "soil_moisture";
  if (code === "cur_power") return "power";
  if (LIGHT.includes(code)) return "light";
  if (TEMPERATURE.includes(code)) {
    if (kind === "water") return "water_temperature";
    if (kind === "soil") return "soil_temperature";
    return kind === "plug" ? null : "air_temperature";
  }
  if (HUMIDITY.includes(code)) {
    if (kind === "soil") return "soil_moisture";
    return kind === "water" || kind === "plug" ? null : "air_humidity";
  }
  return null;
}

/** Ein Datenpunkt aus `/v1.0/devices/{id}/specifications` (status[]), `values` ist dort ein JSON-Text. */
export interface DpSpec {
  code: string;
  type?: string;
  values?: string;
}

function specValues(spec: DpSpec | undefined): { scale: number; unit: string } {
  try {
    const v = JSON.parse(spec?.values ?? "{}") as { scale?: unknown; unit?: unknown };
    return { scale: typeof v.scale === "number" ? v.scale : 0, unit: typeof v.unit === "string" ? v.unit : "" };
  } catch {
    return { scale: 0, unit: "" };
  }
}

function plausible(metric: MetricId, value: number): boolean {
  switch (metric) {
    case "ph": return value >= 0 && value <= 14;
    case "air_humidity": case "soil_moisture": return value >= 0 && value <= 100;
    case "air_temperature": case "soil_temperature": case "water_temperature": return value >= -40 && value <= 80;
    case "light": return value >= 0 && value <= 300_000;
    default: return value >= 0;
  }
}

/** Rohwert → Messwert in unserer Einheit, null bei unbrauchbaren Werten. */
export function convert(spec: DpSpec | undefined, raw: unknown, metric: MetricId): number | null {
  const number = typeof raw === "number" ? raw : typeof raw === "string" ? Number(raw) : NaN;
  if (!Number.isFinite(number)) return null;
  const { scale, unit } = specValues(spec);
  let value = number / Math.pow(10, scale);
  const u = unit.toLowerCase();
  if (TEMPERATURE_METRICS.includes(metric) && (u.includes("°f") || u === "f" || u.includes("℉"))) value = ((value - 32) * 5) / 9;
  if (metric === "ec") {
    if (u.includes("us") || u.includes("µs") || u.includes("μs") || (!u.includes("ms") && value > 20)) value /= 1000;
  }
  return plausible(metric, value) ? value : null;
}

/** Status `[{code, value}]` eines Geräts → Messwerte. */
export function readDevice(
  status: { code: string; value: unknown }[],
  specs: DpSpec[],
  category?: string,
): Partial<Record<MetricId, number>> {
  const kind = kindOf(category, status.map((s) => s.code));
  const result: Partial<Record<MetricId, number>> = {};
  for (const s of status) {
    const metric = metricFor(s.code, kind);
    if (!metric) continue;
    const value = convert(specs.find((p) => p.code === s.code), s.value, metric);
    if (value !== null) result[metric] = value;
  }
  return result;
}

// ---------- VPD und Zusammenfassen ----------

export const DEFAULT_LEAF_OFFSET = -2;
const saturation = (t: number) => 0.61078 * Math.exp((17.27 * t) / (t + 237.3));

export function leafVpd(airTemp: number, humidity: number, leafOffset = DEFAULT_LEAF_OFFSET): number {
  const rh = Math.min(100, Math.max(0, humidity)) / 100;
  return Math.max(0, saturation(airTemp + leafOffset) - saturation(airTemp) * rh);
}

/** Mittelwert je Messgröße über alle Geräte; VPD aus Temperatur und Luftfeuchte. */
export function combine(perDevice: Partial<Record<MetricId, number>>[], leafOffset: number): Partial<Record<MetricId, number>> {
  const sums: Partial<Record<MetricId, number[]>> = {};
  for (const values of perDevice) {
    for (const [k, v] of Object.entries(values) as [MetricId, number][]) {
      if (k === "vpd") continue;
      (sums[k] ??= []).push(v);
    }
  }
  const out: Partial<Record<MetricId, number>> = {};
  for (const [k, list] of Object.entries(sums) as [MetricId, number[]][]) out[k] = list.reduce((a, b) => a + b, 0) / list.length;
  if (out.air_temperature !== undefined && out.air_humidity !== undefined) out.vpd = leafVpd(out.air_temperature, out.air_humidity, leafOffset);
  return out;
}

// ---------- Zielwerte ----------

export interface Range { min: number; max: number }
type Targets = Partial<Record<MetricId, Range>>;

export function defaultTargets(phase: string, medium: string): Targets {
  const t: Targets = {};
  const r = (min: number, max: number): Range => ({ min, max });
  switch (phase) {
    case "seedling": Object.assign(t, { air_temperature: r(22, 28), air_humidity: r(65, 80), vpd: r(0.4, 0.8), co2: r(400, 800) }); break;
    case "flowering": Object.assign(t, { air_temperature: r(20, 26), air_humidity: r(40, 55), vpd: r(1.0, 1.5), co2: r(800, 1500) }); break;
    case "late_flowering": Object.assign(t, { air_temperature: r(18, 24), air_humidity: r(35, 45), vpd: r(1.2, 1.6), co2: r(400, 1000) }); break;
    case "drying": return { air_temperature: r(16, 20), air_humidity: r(55, 62) };
    default: Object.assign(t, { air_temperature: r(22, 28), air_humidity: r(55, 70), vpd: r(0.8, 1.2), co2: r(600, 1200) });
  }
  t.ph = medium === "coco" ? r(5.8, 6.3) : medium === "hydro" ? r(5.5, 6.2) : r(6.0, 7.0);
  t.ec = phase === "seedling" ? r(0.4, 0.8) : phase === "vegetative" ? r(1.0, 1.6) : phase === "flowering" ? r(1.4, 2.0) : r(1.0, 1.6);
  if (medium === "hydro") t.water_temperature = r(18, 22);
  else t.soil_moisture = r(30, 70);
  return t;
}

export function effectiveTargets(box: { phase?: string; medium?: string; targets?: Record<string, Range> }): Targets {
  return { ...defaultTargets(box.phase ?? "vegetative", box.medium ?? "soil"), ...(box.targets as Targets | undefined) };
}

// ---------- Alarme (wie AlarmRules in der App) ----------

export const REMIND_AFTER_MS = 6 * 60 * 60 * 1000;
const SEVERE_SHARE = 0.25;
const HYSTERESIS_SHARE = 0.05;

export interface AlarmIssue { key: string; boxId: string; boxName: string; text: string; severe: boolean }
export interface AlarmEntry { since: number; seen: number; notifiedAt: number | null }
export interface BoxAlert { boxId: string; boxName: string; lines: string[] }

const fmt = (metric: MetricId, value: number) => {
  const info = METRIC_INFO[metric];
  const n = value.toFixed(info.decimals).replace(".", ",");
  return info.unit ? `${n} ${info.unit}` : n;
};
const fmtRange = (metric: MetricId, r: Range) => {
  const info = METRIC_INFO[metric];
  const a = r.min.toFixed(info.decimals).replace(".", ",");
  const b = r.max.toFixed(info.decimals).replace(".", ",");
  return info.unit ? `${a}–${b} ${info.unit}` : `${a}–${b}`;
};

export function issues(
  box: { id: string; name: string },
  values: Partial<Record<MetricId, number>>,
  targets: Targets,
  offlineDevices: string[],
  activeKeys: Set<string>,
): AlarmIssue[] {
  const result: AlarmIssue[] = [];
  for (const [metric, value] of Object.entries(values) as [MetricId, number][]) {
    const target = targets[metric];
    if (!target) continue;
    const key = `${box.id}|${metric}`;
    const width = Math.max(target.max - target.min, 1e-9);
    const margin = activeKeys.has(key) ? width * HYSTERESIS_SHARE : 0;
    const outside = value < target.min || value > target.max;
    const low = value < target.min + margin;
    const high = value > target.max - margin;
    if (!outside && !(activeKeys.has(key) && (low || high))) continue;
    const tooHigh = outside ? value > target.max : high;
    const distance = tooHigh ? value - target.max : target.min - value;
    result.push({
      key, boxId: box.id, boxName: box.name,
      text: `${METRIC_INFO[metric].label} ${tooHigh ? "zu hoch" : "zu niedrig"}: ${fmt(metric, value)} (Ziel ${fmtRange(metric, target)})`,
      severe: distance > width * SEVERE_SHARE,
    });
  }
  for (const name of offlineDevices) {
    result.push({ key: `${box.id}|offline|${name}`, boxId: box.id, boxName: box.name, text: `Sensor „${name}“ ist offline`, severe: false });
  }
  return result;
}

export function evaluate(previous: Record<string, AlarmEntry>, found: AlarmIssue[], now: number) {
  const state: Record<string, AlarmEntry> = {};
  for (const issue of found) {
    const before = previous[issue.key];
    state[issue.key] = { since: before?.since ?? now, seen: (before?.seen ?? 0) + 1, notifiedAt: before?.notifiedAt ?? null };
  }
  const alerts: BoxAlert[] = [];
  const byBox = new Map<string, AlarmIssue[]>();
  for (const i of found) byBox.set(i.boxId, [...(byBox.get(i.boxId) ?? []), i]);
  for (const [boxId, list] of byBox) {
    const due = list.some((i) => {
      const e = state[i.key];
      return e.notifiedAt === null ? e.seen >= 2 || i.severe : now - e.notifiedAt >= REMIND_AFTER_MS;
    });
    if (!due) continue;
    alerts.push({ boxId, boxName: list[0].boxName, lines: list.map((i) => i.text) });
    for (const i of list) state[i.key] = { ...state[i.key], notifiedAt: now };
  }
  const boxesWithIssues = new Set(found.map((i) => i.boxId));
  const resolvedBoxIds = [...new Set(Object.entries(previous)
    .filter(([, e]) => e.notifiedAt !== null)
    .map(([k]) => k.split("|")[0])
    .filter((b) => !boxesWithIssues.has(b)))];
  return { state, alerts, resolvedBoxIds };
}

// ---------- Automationen (wie AutomationRules in der App) ----------

export interface ThresholdRule { type: "threshold"; metric: MetricId; switchOnAbove: boolean; onAt: number; offAt: number }
export interface ScheduleRule { type: "schedule"; onTime: string; offTime: string; days: string }

/** true = einschalten, false = ausschalten, null = so lassen (zwischen den Schaltpunkten). */
export function thresholdDecision(rule: ThresholdRule, value: number | undefined): boolean | null {
  if (value === undefined) return null;
  if (rule.switchOnAbove) return value > rule.onAt ? true : value < rule.offAt ? false : null;
  return value < rule.onAt ? true : value > rule.offAt ? false : null;
}

const minutesOf = (time: string) => {
  const m = /^([01][0-9]|2[0-3]):([0-5][0-9])$/.exec(time);
  return m ? Number(m[1]) * 60 + Number(m[2]) : null;
};

/**
 * Soll das Gerät jetzt an sein? [minuteOfDay] und [weekday] (0 = Sonntag) in deutscher Zeit.
 * Über Mitternacht (06:00–00:00) gehört die Nacht zum Tag des Einschaltens.
 */
export function scheduleWantsOn(rule: ScheduleRule, minuteOfDay: number, weekday: number): boolean | null {
  const on = minutesOf(rule.onTime);
  const off = minutesOf(rule.offTime);
  if (on === null || off === null || on === off || rule.days.length !== 7) return null;
  const active = (day: number) => rule.days[(day + 7) % 7] === "1";
  if (on < off) return active(weekday) && minuteOfDay >= on && minuteOfDay < off;
  // über Mitternacht
  if (minuteOfDay >= on) return active(weekday);
  if (minuteOfDay < off) return active(weekday - 1);
  return false;
}

/** Minute des Tages und Wochentag in Europe/Berlin. */
export function berlinClock(nowMs: number): { minuteOfDay: number; weekday: number } {
  const parts = new Intl.DateTimeFormat("en-US", {
    timeZone: "Europe/Berlin", hour: "2-digit", minute: "2-digit", weekday: "short", hourCycle: "h23",
  }).formatToParts(new Date(nowMs));
  const get = (t: string) => parts.find((p) => p.type === t)?.value ?? "";
  const weekday = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"].indexOf(get("weekday"));
  return { minuteOfDay: Number(get("hour")) * 60 + Number(get("minute")), weekday };
}

// ---------- Verlauf ----------

/** "HHmm" in UTC, auf 5 Minuten abgerundet, wie HistoryFormat.slotKey in der App. */
export function slotKey(ms: number): string {
  const d = new Date(ms);
  const m = Math.floor(d.getUTCMinutes() / 5) * 5;
  return `${String(d.getUTCHours()).padStart(2, "0")}${String(m).padStart(2, "0")}`;
}

export function roundValues(values: Partial<Record<MetricId, number>>): Record<string, number> {
  return Object.fromEntries(Object.entries(values).map(([k, v]) => [k, Math.round((v as number) * 1000) / 1000]));
}
