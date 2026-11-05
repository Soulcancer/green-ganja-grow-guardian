/**
 * Moderation im Club als reine Funktionen.
 *
 * Moderatoren sind aktive Mitglieder, die Inhaber oder Team ernennen. Sie belegen keinen der 5 Team-Plätze
 * (kein Social-Club-Abo nötig) und sehen keine Personendaten wie vollen Namen oder Geburtsdatum.
 * Werkzeuge: Meldungen im Marktplatz entscheiden, Inserate entfernen, Mitglieder verwarnen, im Marktplatz
 * befristet sperren und Ankündigungen an alle Mitglieder schreiben. Jede Aktion steht im Protokoll; die
 * betroffene Person sieht die Einträge über sich mit Grund.
 */
import { berlinDate } from "./kcang";
import { requiredText } from "./input";

/** Höchstens so viele Moderatoren je Club. */
export const MAX_MODERATORS = 10;
/** Erlaubte Dauer einer Marktplatz-Sperre in Tagen. */
export const BLOCK_DAYS = [1, 3, 7, 30] as const;
/** Protokolleinträge werden nach einem Jahr gelöscht. */
export const MOD_LOG_RETENTION_DAYS = 365;
/** Ankündigungen verschwinden nach 30 Tagen von selbst. */
export const ANNOUNCEMENT_VALID_DAYS = 30;
export const MAX_ANNOUNCEMENTS = 20;
export const MAX_PINNED = 3;

export type ModRole = "owner" | "staff" | "moderator" | "member";
export type ModAction =
  | "appoint"
  | "dismiss"
  | "warn"
  | "block"
  | "unblock"
  | "restore"
  | "remove"
  | "announce"
  | "unannounce";
export type ReportDecision = "restore" | "remove";

const RANK: Record<ModRole, number> = { member: 0, moderator: 1, staff: 2, owner: 3 };

export function rank(role: string): number {
  return RANK[role as ModRole] ?? 0;
}

/** Moderieren dürfen Moderatoren, Team und Inhaber. */
export function canModerate(role: string): boolean {
  return rank(role) >= RANK.moderator;
}

/** Moderatoren ernennen und abberufen dürfen Team und Inhaber. */
export function canAppoint(role: string): boolean {
  return rank(role) >= RANK.staff;
}

/** Verwarnen und sperren: nur Personen mit niedrigerer Rolle, nie sich selbst. */
export function mayActOn(actorUid: string, actorRole: string, targetUid: string, targetRole: string): boolean {
  return actorUid !== targetUid && canModerate(actorRole) && rank(targetRole) < rank(actorRole);
}

/** Inserate entfernen: Team alle, Moderatoren nur Zubehör (Club-Angebote für Samen und Stecklinge gehören dem Team). */
export function mayRemoveListing(role: string, kind: string): boolean {
  if (rank(role) >= RANK.staff) return true;
  return role === "moderator" && kind === "gear";
}

/** Rolle im Club aus den Listen im Club-Dokument; ersetzt nicht die Prüfung der Mitgliedschaft. */
export function roleIn(uid: string, ownerUid: string, staffUids: string[], moderatorUids: string[] | undefined): ModRole {
  if (uid === ownerUid) return "owner";
  if (staffUids.includes(uid)) return "staff";
  if ((moderatorUids ?? []).includes(uid)) return "moderator";
  return "member";
}

export interface Problem {
  reason: "moderator-limit" | "member-not-active" | "wrong-status";
  message: string;
}

/** Warum eine Ernennung oder Abberufung nicht geht, sonst null. */
export function appointProblem(appoint: boolean, targetRole: string, targetStatus: string, moderatorCount: number): Problem | null {
  if (targetStatus !== "active") {
    return { reason: "member-not-active", message: "Moderator kann nur werden, wer schon freigegeben ist." };
  }
  if (appoint) {
    if (targetRole === "moderator") return { reason: "wrong-status", message: "Diese Person ist schon Moderator." };
    if (targetRole !== "member") return { reason: "wrong-status", message: "Team-Mitglieder haben die Werkzeuge schon." };
    if (moderatorCount >= MAX_MODERATORS) {
      return { reason: "moderator-limit", message: `Ein Club kann höchstens ${MAX_MODERATORS} Moderatoren haben.` };
    }
    return null;
  }
  return targetRole === "moderator" ? null : { reason: "wrong-status", message: "Diese Person ist kein Moderator." };
}

/** Pflicht-Begründung, 3 bis 300 Zeichen. */
export function parseReason(value: unknown): string | null {
  return requiredText(value, 3, 300);
}

export function parseBlockDays(value: unknown): number | null {
  return typeof value === "number" && (BLOCK_DAYS as readonly number[]).includes(value) ? value : null;
}

export function parseDecision(value: unknown): ReportDecision | null {
  return value === "restore" || value === "remove" ? value : null;
}

export function blockedUntil(nowMs: number, days: number): number {
  return nowMs + days * 24 * 60 * 60 * 1000;
}

export function isBlocked(untilMs: number | null | undefined, nowMs: number): boolean {
  return typeof untilMs === "number" && untilMs > nowMs;
}

/** "TT.MM.JJJJ" in deutscher Zeit. */
export function germanDate(ms: number): string {
  const [y, m, d] = berlinDate(new Date(ms)).split("-");
  return `${d}.${m}.${y}`;
}

/** Meldung für gesperrte Mitglieder beim Inserieren oder Anfragen. */
export function blockMessage(untilMs: number, reason: string | null | undefined): string {
  const why = reason ? ` Grund: ${reason}` : "";
  return `Du bist im Marktplatz bis ${germanDate(untilMs)} gesperrt.${why}`;
}

export interface AnnouncementInput {
  title: string;
  text: string;
  pinned: boolean;
}

/** Ankündigung an alle Mitglieder. Nur intern im Club sichtbar, keine Werbung nach außen (§ 6 KCanG). */
export function parseAnnouncement(data: unknown): AnnouncementInput | string {
  const d = (data ?? {}) as Record<string, unknown>;
  const title = requiredText(d.title, 3, 60);
  if (!title) return "Der Titel muss 3 bis 60 Zeichen haben.";
  if (typeof d.text !== "string") return "Bitte einen Text eingeben.";
  const text = d.text.trim().replace(/[ \t]+/g, " ").replace(/\n{3,}/g, "\n\n");
  if (text.length < 1 || text.length > 1000) return "Der Text muss 1 bis 1000 Zeichen haben.";
  if (d.pinned !== undefined && typeof d.pinned !== "boolean") return "Ungültige Angabe zum Anheften.";
  return { title, text, pinned: d.pinned === true };
}

/** Warum eine neue Ankündigung nicht geht, sonst null. */
export function announcementProblem(active: number, pinnedActive: number, pinned: boolean): string | null {
  if (active >= MAX_ANNOUNCEMENTS) return `Es gibt schon ${MAX_ANNOUNCEMENTS} Ankündigungen. Lösche zuerst eine ältere.`;
  if (pinned && pinnedActive >= MAX_PINNED) return `Höchstens ${MAX_PINNED} Ankündigungen können angeheftet sein.`;
  return null;
}
