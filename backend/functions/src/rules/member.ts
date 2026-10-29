/** Einstellungen, die jedes Mitglied selbst ändern darf. */

export interface NotifySettings {
  /** Reservierung bestätigt, abgelehnt oder ausgegeben. */
  reservations: boolean;
  /** Eine geteilte Club-Box läuft aus dem Zielbereich oder geht offline. */
  clubBoxes: boolean;
  /** Neue Inserate und Antworten im Marktplatz. */
  marketplace: boolean;
}

export const DEFAULT_NOTIFY: NotifySettings = { reservations: true, clubBoxes: true, marketplace: false };

export interface MemberSettingsInput {
  /** Name, den andere Mitglieder sehen (z. B. im Marktplatz); null = Vorname und Initiale. */
  publicName: string | null;
  notify: NotifySettings;
}

const NAME = /^[\p{L}\p{N} ._'-]{2,30}$/u;

export function parseMemberSettings(data: unknown): MemberSettingsInput | string {
  const d = (data ?? {}) as Record<string, unknown>;
  let publicName: string | null = null;
  if (d.publicName !== undefined && d.publicName !== null) {
    if (typeof d.publicName !== "string") return "Ungültiger Anzeigename.";
    const name = d.publicName.trim().replace(/\s+/g, " ");
    if (name.length > 0) {
      if (!NAME.test(name)) return "Der Anzeigename muss 2 bis 30 Zeichen haben (Buchstaben, Ziffern, Leerzeichen, . _ ' -).";
      publicName = name;
    }
  }
  const raw = (d.notify ?? {}) as Record<string, unknown>;
  if (typeof raw !== "object" || Array.isArray(raw)) return "Ungültige Benachrichtigungen.";
  const flag = (key: keyof NotifySettings) => (typeof raw[key] === "boolean" ? (raw[key] as boolean) : DEFAULT_NOTIFY[key]);
  return { publicName, notify: { reservations: flag("reservations"), clubBoxes: flag("clubBoxes"), marketplace: flag("marketplace") } };
}

/** Was andere Mitglieder als Namen sehen: der gewählte Anzeigename, sonst Vorname und Initiale. */
export function visibleName(publicName: string | null | undefined, firstName?: string, lastName?: string, displayName?: string): string {
  if (publicName) return publicName;
  if (firstName) return lastName ? `${firstName} ${lastName.charAt(0)}.` : firstName;
  const first = (displayName ?? "").trim().split(/\s+/)[0];
  return first || "Mitglied";
}

/** Gebuchte Mengen ohne die Reservierung, die gerade geändert wird. */
export function bookingsWithout<T extends { id: string }>(items: T[], excludeId: string): T[] {
  return items.filter((item) => item.id !== excludeId);
}
