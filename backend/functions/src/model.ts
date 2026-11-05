import type { Timestamp } from "firebase-admin/firestore";

/**
 * Firestore-Struktur
 *
 * memberships/{uid}                    Zu welchem Club ein Nutzer gehört (liest nur der Nutzer selbst)
 * clubs/{clubId}                       Club, clubId = UID des Käufers (liest nur das Club-Team)
 * clubs/{clubId}/members/{uid}         Mitglieder inkl. Team und Moderatoren (liest das Team und das Mitglied selbst)
 * clubs/{clubId}/reservations/{id}     Reservierungen (liest das Team und wer reserviert hat)
 * clubs/{clubId}/dispensations/{id}    Abgabe-Dokumentation nach § 26 KCanG (liest nur das Team)
 * invites/{code}                       Einladungen für Team-Plätze (nur Functions)
 * users/{uid}                         tierCache: zuletzt bei RevenueCat geprüfte Stufe (nur Functions)
 * users/{uid}/growBoxes/{boxId}       Grow-Boxen: Name, Phase, Medium, Sensoren, Zielwerte, Alarme (liest nur der Nutzer)
 * …/growBoxes/{boxId}/history/{day}  Messwerte eines UTC-Tages, schreibt die App; Löschfrist setzt die Function
 * …/growBoxes/{boxId}/journal/{id}   Grow-Tagebuch: gegossen, Dünger, Messwerte von Hand, Notizen (schreibt die App)
 * …/growBoxes/{boxId}/stats/{year}    Tagesstatistik Min/Mittel/Max, schreibt die nächtliche Function
 * users/{uid}/automations/{id}        Regeln (Schwellwert, Zeitplan); die App schreibt nur sceneIds/syncError
 * clubs/{clubId}/growBoxes/{id}       Gemeinsame Club-Boxen: Konfiguration + zuletzt hochgeladene Werte (liest jedes aktive Mitglied)
 * clubs/{clubId}/listings/{id}         Marktplatz: Zubehör der Mitglieder, Samen/Stecklinge nur als Club-Angebot
 * …/listings/{id}/interests/{uid}      Interesse an einem Inserat (liest Anbieter, Interessent, Team)
 * …/listings/{id}/reports/{uid}        Meldungen (nur Functions und Team)
 * clubs/{clubId}/propagationRequests/{id}  Anfragen nach Samen/Stecklingen (liest das Team und wer angefragt hat)
 * clubs/{clubId}/propagationLog/{id}   Ausgabe von Vermehrungsmaterial, 5 Jahre (§ 26 KCanG, nur Team)
 * clubs/{clubId}/announcements/{id}   Ankündigungen von Team und Moderatoren, 30 Tage (liest jedes aktive Mitglied)
 * clubs/{clubId}/modLog/{id}          Moderations-Protokoll, 1 Jahr (liest Team, Moderatoren und die betroffene Person)
 * wallets/{uid}                       Coins: Guthaben, Serie, werbefrei/Alarme bis, Erfolge (liest der Nutzer, nur Functions schreiben)
 * wallets/{uid}/ledger/{id}           Coin-Buchungen, 1 Jahr
 * adRewards/{transactionId}           Gutgeschriebene Belohnungsvideos (nur Functions)
 * users/{uid}/pushTokens/{token}      FCM-Token der Geräte des Nutzers (schreibt die App)
 * users/{uid}/serverState/{alarms|monitor}  Zustand der Server-Überwachung (nur Functions; monitor liest die App)
 * tuyaDevices/{deviceId}              Besitzer und Datenpunkte eines Tuya-Geräts, zwischengespeichert (nur Functions)
 * tuyaAccounts/{uid}                   Zugangsdaten für den Tuya-UID-Login (nur Functions)
 */

/** moderator: aktives Mitglied mit Moderations-Werkzeugen, ohne Team-Platz und ohne Personendaten. */
export type Role = "owner" | "staff" | "moderator" | "member";
export type MemberStatus = "pending" | "active";
export type ReservationStatus = "requested" | "confirmed" | "declined" | "handedOut" | "cancelled";

export const OPEN_RESERVATION_STATUSES: ReservationStatus[] = ["requested", "confirmed"];

export interface MembershipDoc {
  clubId: string;
  clubName: string;
  role: Role;
  status: MemberStatus;
}

export interface ClubDoc {
  name: string;
  ownerUid: string;
  /** Team-Plätze inkl. Inhaber. */
  seats: number;
  /** Inhaber und Team, höchstens [seats]. */
  staffUids: string[];
  /** Social-Club-Abo des Inhabers aktiv. */
  active: boolean;
  expiresAt: Timestamp | null;
  /** Code, mit dem Mitglieder eine Aufnahme anfragen. */
  memberCode: string;
  /** Bis wann jeder Team-Platz in RevenueCat freigegeben ist (ms). */
  staffGrants?: Record<string, number>;
  /** Moderatoren (höchstens MAX_MODERATORS), zählen nicht zu den Team-Plätzen. */
  moderatorUids?: string[];
}

export interface MemberDoc {
  uid: string;
  role: Role;
  status: MemberStatus;
  displayName: string;
  firstName?: string;
  lastName?: string;
  /** "YYYY-MM-DD", vom Team nach Ausweiskontrolle eingetragen. */
  birthDate?: string;
  /** Anzahl der Verwarnungen durch Moderation. */
  warnings?: number;
  /** Bis dahin keine neuen Inserate und Anfragen im Marktplatz. */
  marketBlockedUntil?: Timestamp | null;
  marketBlockReason?: string | null;
}

export interface ModLogDoc {
  action: string;
  byUid: string;
  byName: string;
  /** Betroffene Person; sie kann den Eintrag lesen. */
  targetUid: string | null;
  targetName: string | null;
  listingId: string | null;
  listingTitle: string | null;
  reason: string | null;
  /** Ende einer Sperre. */
  until: Timestamp | null;
  createdAt: unknown;
  expireAt: Timestamp;
}

export interface ReservationDoc {
  memberUid: string;
  memberName: string;
  grams: number;
  strain: string | null;
  pickupDate: string;
  note: string | null;
  status: ReservationStatus;
  declineReason?: string | null;
  dispensationId?: string;
}

export interface DispensationDoc {
  memberUid: string;
  firstName: string;
  lastName: string;
  birthYear: number;
  grams: number;
  thcPercent: number;
  strain: string | null;
  date: string;
  month: string;
  handedOutBy: string;
  reservationId: string | null;
}

export interface InviteDoc {
  clubId: string;
  type: "staff";
  createdBy: string;
  expiresAt: Timestamp;
}
