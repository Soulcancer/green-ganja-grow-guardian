/**
 * Gemeinsame Club-Boxen: eine Box aus dem Konto eines Team-Mitglieds wird für alle Mitglieder sichtbar.
 * Geteilt wird nur die Konfiguration (Name, Phase, Zielwerte). Messwerte lädt das Handy des Betreuers hoch;
 * Mitglieder lesen sie nur. Sensoren und Automationen bleiben im Tuya-Konto des Betreuers.
 */
import { METRICS, MEDIA, PHASES } from "./grow";

/** Höchstzahl gemeinsamer Boxen je Club, gegen Missbrauch. */
export const MAX_CLUB_BOXES = 10;

export interface ClubBoxConfig {
  name: string;
  phase: string;
  medium: string;
  leafOffsetC: number;
  targets: Record<string, { min: number; max: number }>;
}

export function parseShareRequest(data: unknown): string | null {
  const id = (data as { boxId?: unknown } | null)?.boxId;
  return typeof id === "string" && /^[A-Za-z0-9_-]{1,128}$/.test(id) ? id : null;
}

export function parseUnshareRequest(data: unknown): string | null {
  const id = (data as { clubBoxId?: unknown } | null)?.clubBoxId;
  return typeof id === "string" && /^[A-Za-z0-9_-]{1,128}$/.test(id) ? id : null;
}

/** Konfiguration aus einem gespeicherten Box-Dokument übernehmen; unbrauchbare Werte fallen weg. */
export function configOf(data: Record<string, unknown>): ClubBoxConfig | null {
  const name = typeof data.name === "string" ? data.name.trim() : "";
  if (!name || name.length > 40) return null;
  if (!PHASES.includes(data.phase as never) || !MEDIA.includes(data.medium as never)) return null;
  const leaf = typeof data.leafOffsetC === "number" && Number.isFinite(data.leafOffsetC) ? data.leafOffsetC : -2;
  const targets: ClubBoxConfig["targets"] = {};
  const raw = data.targets && typeof data.targets === "object" ? (data.targets as Record<string, { min?: unknown; max?: unknown }>) : {};
  for (const [key, range] of Object.entries(raw)) {
    if (!METRICS.includes(key as never)) continue;
    if (typeof range?.min === "number" && typeof range?.max === "number" && range.min <= range.max) {
      targets[key] = { min: range.min, max: range.max };
    }
  }
  return { name, phase: data.phase as string, medium: data.medium as string, leafOffsetC: leaf, targets };
}

export type ShareCheck = { ok: true } | { ok: false; reason: "club-box-limit" };

/** Eine bereits geteilte Box darf immer aktualisiert werden; neue nur bis zum Limit. */
export function checkShare(existingCount: number, alreadyShared: boolean): ShareCheck {
  if (alreadyShared || existingCount < MAX_CLUB_BOXES) return { ok: true };
  return { ok: false, reason: "club-box-limit" };
}

/** Wer eine geteilte Box entfernen darf: der Betreuer oder der Inhaber. */
export function mayUnshare(uid: string, keeperUid: string, ownerUid: string): boolean {
  return uid === keeperUid || uid === ownerUid;
}
