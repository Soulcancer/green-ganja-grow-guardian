/** Automationen: Eingaben prüfen und Limits je Stufe. Gleiche Regeln wie AutomationRules.kt in der App. */
import type { Tier } from "./grow";

/** Höchstzahl Regeln je Stufe, null = unbegrenzt. Muss zu FarmerTier.maxAutomations passen. */
export const MAX_AUTOMATIONS: Record<Tier, number | null> = {
  freemium: 0,
  premium: 20,
  social_club: null,
};

/** Obergrenze gegen Missbrauch, auch für Social Club. */
export const HARD_MAX_AUTOMATIONS = 200;

/** Messgrößen, die direkt von einem Sensor-Datenpunkt kommen und in der Tuya-Cloud prüfbar sind. */
export const AUTOMATION_METRICS = ["air_temperature", "air_humidity", "co2", "soil_moisture", "soil_temperature", "water_temperature"] as const;

const ID = /^[A-Za-z0-9_-]{1,128}$/;
const DEVICE_ID = /^[A-Za-z0-9_-]{1,64}$/;
const DP_ID = /^[0-9]{1,4}$/;
const TIME = /^([01][0-9]|2[0-3]):[0-5][0-9]$/;

interface Common {
  automationId: string | null;
  boxId: string;
  name: string;
  enabled: boolean;
  actuatorDeviceId: string;
  actuatorDpId: string;
  actuatorLabel: string;
  /** DP-Code des Schalters, z. B. "switch_2"; für die Server-Ausführung über die Tuya Cloud. */
  actuatorCode: string;
}

export interface ThresholdInput extends Common {
  type: "threshold";
  sensorDeviceId: string;
  metric: (typeof AUTOMATION_METRICS)[number];
  dpId: string;
  dpCode: string;
  scale: number;
  unit: string;
  switchOnAbove: boolean;
  onAt: number;
  offAt: number;
}

export interface ScheduleInput extends Common {
  type: "schedule";
  onTime: string;
  offTime: string;
  days: string;
}

export type AutomationInput = ThresholdInput | ScheduleInput;

const text = (value: unknown, max: number): string | null => {
  if (typeof value !== "string") return null;
  const t = value.trim().replace(/\s+/g, " ");
  return t.length > max ? null : t;
};

/** Bereinigte Eingabe oder ein deutscher Fehlertext. */
export function parseAutomation(data: unknown): AutomationInput | string {
  const d = (data ?? {}) as Record<string, unknown>;
  const automationId = d.automationId === undefined || d.automationId === null ? null : d.automationId;
  if (automationId !== null && !(typeof automationId === "string" && ID.test(automationId))) return "Ungültige Regel.";
  if (typeof d.boxId !== "string" || !ID.test(d.boxId)) return "Ungültige Box.";
  const name = text(d.name, 40);
  if (!name) return "Der Name muss 1 bis 40 Zeichen haben.";
  if (typeof d.actuatorDeviceId !== "string" || !DEVICE_ID.test(d.actuatorDeviceId)) return "Bitte ein Gerät zum Schalten wählen.";
  if (typeof d.actuatorDpId !== "string" || !DP_ID.test(d.actuatorDpId)) return "Ungültiger Schalter.";
  const common: Common = {
    automationId: automationId as string | null,
    boxId: d.boxId,
    name,
    enabled: d.enabled !== false,
    actuatorDeviceId: d.actuatorDeviceId,
    actuatorDpId: d.actuatorDpId,
    actuatorLabel: text(d.actuatorLabel, 80) ?? "",
    actuatorCode: typeof d.actuatorCode === "string" && /^switch(_[0-9]{1,2})?$/.test(d.actuatorCode) ? d.actuatorCode : "",
  };

  if (d.type === "threshold") {
    if (typeof d.sensorDeviceId !== "string" || !DEVICE_ID.test(d.sensorDeviceId)) return "Bitte einen Sensor wählen.";
    if (d.sensorDeviceId === d.actuatorDeviceId) return "Sensor und geschaltetes Gerät müssen verschieden sein.";
    if (!AUTOMATION_METRICS.includes(d.metric as never)) return "Diese Messgröße kann die Tuya-Cloud nicht direkt prüfen.";
    if (typeof d.dpId !== "string" || !DP_ID.test(d.dpId)) return "Ungültiger Datenpunkt.";
    const scale = typeof d.scale === "number" && Number.isInteger(d.scale) && d.scale >= 0 && d.scale <= 6 ? d.scale : null;
    if (scale === null) return "Ungültige Skalierung.";
    const onAt = d.onAt;
    const offAt = d.offAt;
    if (typeof onAt !== "number" || typeof offAt !== "number" || !Number.isFinite(onAt) || !Number.isFinite(offAt)) {
      return "Bitte Ein- und Ausschaltpunkt eingeben.";
    }
    if (Math.abs(onAt) > 100_000 || Math.abs(offAt) > 100_000) return "Ungültige Werte.";
    const switchOnAbove = d.switchOnAbove !== false;
    if (switchOnAbove && offAt >= onAt) return "Der Ausschaltpunkt muss unter dem Einschaltpunkt liegen.";
    if (!switchOnAbove && offAt <= onAt) return "Der Ausschaltpunkt muss über dem Einschaltpunkt liegen.";
    return {
      ...common,
      type: "threshold",
      sensorDeviceId: d.sensorDeviceId,
      metric: d.metric as ThresholdInput["metric"],
      dpId: d.dpId,
      dpCode: text(d.dpCode, 40) ?? "",
      scale,
      unit: text(d.unit, 16) ?? "",
      switchOnAbove,
      onAt,
      offAt,
    };
  }

  if (d.type === "schedule") {
    if (typeof d.onTime !== "string" || typeof d.offTime !== "string" || !TIME.test(d.onTime) || !TIME.test(d.offTime)) {
      return "Bitte Zeiten als HH:MM eingeben, z. B. 06:00.";
    }
    if (d.onTime === d.offTime) return "Ein- und Ausschaltzeit müssen verschieden sein.";
    const days = typeof d.days === "string" ? d.days : "1111111";
    if (!/^[01]{7}$/.test(days) || !days.includes("1")) return "Bitte mindestens einen Tag wählen.";
    return { ...common, type: "schedule", onTime: d.onTime, offTime: d.offTime, days };
  }
  return "Unbekannte Art von Regel.";
}

export type AutomationLimit = { ok: true } | { ok: false; reason: "automations-premium" | "automation-limit" };

/** Neue Regeln nur mit Premium oder Social Club und bis zum Limit; bestehende dürfen geändert werden. */
export function checkAutomationLimit(tier: Tier, existingCount: number, creating: boolean): AutomationLimit {
  const max = MAX_AUTOMATIONS[tier];
  if (max === 0) return { ok: false, reason: "automations-premium" };
  if (!creating) return { ok: true };
  if (existingCount >= HARD_MAX_AUTOMATIONS || (max !== null && existingCount >= max)) return { ok: false, reason: "automation-limit" };
  return { ok: true };
}
