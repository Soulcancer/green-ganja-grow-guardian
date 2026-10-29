/**
 * Coins: verdienen und ausgeben, als reine Funktionen. Gebucht wird nur in den Cloud Functions.
 *
 * Verdienen: tägliche Belohnung mit 7-Tage-Serie, Belohnungsvideos (bis 5 am Tag), einmalige Erfolge.
 * Ausgeben: werbefrei auf Zeit, Alarme auf Zeit (für Freemium), Marktplatz-Inserat hervorheben.
 * Coins sind kein Geld, lassen sich nicht kaufen, auszahlen oder übertragen.
 */

/** Belohnung je Tag der Serie (Tag 1 bis 7, danach von vorn). */
export const DAILY_REWARDS = [5, 6, 7, 8, 10, 12, 20] as const;
export const REWARDED_AD_COINS = 10;
export const REWARDED_ADS_PER_DAY = 5;
/** Obergrenze gegen Fehler: mehr Guthaben als das wird nicht gutgeschrieben. */
export const MAX_BALANCE = 100_000;

export const ACHIEVEMENTS = {
  first_box: { coins: 20, label: "Erste Grow-Box angelegt" },
  first_automation: { coins: 25, label: "Erste Automation" },
  first_listing: { coins: 10, label: "Erstes Inserat im Marktplatz" },
  club_member: { coins: 15, label: "Mitglied in einem Club" },
  week_streak: { coins: 30, label: "7 Tage in Folge vorbeigeschaut" },
} as const;
export type AchievementId = keyof typeof ACHIEVEMENTS;

const HOUR = 60 * 60 * 1000;
const DAY = 24 * HOUR;

export const SHOP = {
  ad_free_1d: { coins: 40, label: "1 Tag werbefrei", field: "adFreeUntil", durationMs: DAY },
  ad_free_7d: { coins: 200, label: "7 Tage werbefrei", field: "adFreeUntil", durationMs: 7 * DAY },
  alerts_3d: { coins: 60, label: "3 Tage Alarme", field: "alertsUntil", durationMs: 3 * DAY },
  boost_listing: { coins: 20, label: "Inserat 3 Tage hervorheben", field: "boostedUntil", durationMs: 3 * DAY },
} as const;
export type ShopItemId = keyof typeof SHOP;

export interface WalletState {
  balance: number;
  streak: number;
  /** "YYYY-MM-DD" (deutsche Zeit) der letzten täglichen Belohnung. */
  lastCheckIn: string | null;
  rewardedDay: string | null;
  rewardedCount: number;
  achievements: string[];
  /** Zeitpunkte in ms. */
  adFreeUntil: number;
  alertsUntil: number;
}

export const EMPTY_WALLET: WalletState = {
  balance: 0, streak: 0, lastCheckIn: null, rewardedDay: null, rewardedCount: 0, achievements: [], adFreeUntil: 0, alertsUntil: 0,
};

function previousDay(date: string): string {
  const [y, m, d] = date.split("-").map(Number);
  return new Date(Date.UTC(y, m - 1, d - 1)).toISOString().slice(0, 10);
}

export type CheckIn = { ok: true; reward: number; streak: number; wallet: WalletState } | { ok: false; reason: "already" };

export function checkIn(wallet: WalletState, today: string): CheckIn {
  if (wallet.lastCheckIn === today) return { ok: false, reason: "already" };
  const streak = wallet.lastCheckIn === previousDay(today) ? wallet.streak + 1 : 1;
  const reward = DAILY_REWARDS[(streak - 1) % DAILY_REWARDS.length];
  return { ok: true, reward, streak, wallet: { ...wallet, streak, lastCheckIn: today, balance: Math.min(MAX_BALANCE, wallet.balance + reward) } };
}

export type RewardedAd = { ok: true; wallet: WalletState } | { ok: false; reason: "daily-cap" };

export function rewardAd(wallet: WalletState, today: string): RewardedAd {
  const count = wallet.rewardedDay === today ? wallet.rewardedCount : 0;
  if (count >= REWARDED_ADS_PER_DAY) return { ok: false, reason: "daily-cap" };
  return {
    ok: true,
    wallet: { ...wallet, rewardedDay: today, rewardedCount: count + 1, balance: Math.min(MAX_BALANCE, wallet.balance + REWARDED_AD_COINS) },
  };
}

export type Achievement = { ok: true; coins: number; wallet: WalletState } | { ok: false; reason: "already" | "unknown" };

export function grantAchievement(wallet: WalletState, id: string): Achievement {
  if (!(id in ACHIEVEMENTS)) return { ok: false, reason: "unknown" };
  if (wallet.achievements.includes(id)) return { ok: false, reason: "already" };
  const coins = ACHIEVEMENTS[id as AchievementId].coins;
  return { ok: true, coins, wallet: { ...wallet, achievements: [...wallet.achievements, id], balance: Math.min(MAX_BALANCE, wallet.balance + coins) } };
}

export type Purchase =
  | { ok: true; wallet: WalletState; until: number }
  | { ok: false; reason: "unknown" | "insufficient" | "not-needed" };

/**
 * Kauf im Shop. Zeiträume verlängern sich ab dem späteren von jetzt und dem bisherigen Ende.
 * [currentUntil] ist bei boost_listing das bisherige Ende am Inserat. [hasFeature]: die Stufe enthält es schon.
 */
export function purchase(wallet: WalletState, item: string, nowMs: number, currentUntil = 0, hasFeature = false): Purchase {
  if (!(item in SHOP)) return { ok: false, reason: "unknown" };
  const entry = SHOP[item as ShopItemId];
  if (hasFeature) return { ok: false, reason: "not-needed" };
  if (wallet.balance < entry.coins) return { ok: false, reason: "insufficient" };
  const base = Math.max(nowMs, entry.field === "adFreeUntil" ? wallet.adFreeUntil : entry.field === "alertsUntil" ? wallet.alertsUntil : currentUntil);
  const until = base + entry.durationMs;
  const next: WalletState = { ...wallet, balance: wallet.balance - entry.coins };
  if (entry.field === "adFreeUntil") next.adFreeUntil = until;
  if (entry.field === "alertsUntil") next.alertsUntil = until;
  return { ok: true, wallet: next, until };
}

/** Wallet aus einem Firestore-Dokument, fehlende Felder mit Standardwerten. */
export function walletFrom(data: Record<string, unknown> | undefined): WalletState {
  const d = data ?? {};
  const num = (v: unknown, fallback = 0) => (typeof v === "number" && Number.isFinite(v) ? v : fallback);
  const ms = (v: unknown) => (v && typeof (v as { toMillis?: unknown }).toMillis === "function" ? (v as { toMillis(): number }).toMillis() : num(v));
  return {
    balance: num(d.balance),
    streak: num(d.streak),
    lastCheckIn: typeof d.lastCheckIn === "string" ? d.lastCheckIn : null,
    rewardedDay: typeof d.rewardedDay === "string" ? d.rewardedDay : null,
    rewardedCount: num(d.rewardedCount),
    achievements: Array.isArray(d.achievements) ? d.achievements.filter((a): a is string => typeof a === "string") : [],
    adFreeUntil: ms(d.adFreeUntil),
    alertsUntil: ms(d.alertsUntil),
  };
}

// ---------- AdMob: Server-seitige Prüfung der Belohnungsvideos (SSV) ----------

/** Der signierte Teil ist die Query bis vor "&signature=". Liefert Nachricht, Signatur und Key-ID. */
export function splitSsvQuery(rawQuery: string): { message: string; signature: string; keyId: string } | null {
  const query = rawQuery.startsWith("?") ? rawQuery.slice(1) : rawQuery;
  const sigIndex = query.indexOf("&signature=");
  if (sigIndex <= 0) return null;
  const message = query.slice(0, sigIndex);
  const rest = new URLSearchParams(query.slice(sigIndex + 1));
  const signature = rest.get("signature");
  const keyId = rest.get("key_id");
  if (!signature || !keyId) return null;
  return { message, signature, keyId };
}

/** base64url (websicher, ohne Auffüllung) nach Bytes. */
export function base64UrlToBuffer(value: string): Buffer {
  const normalized = value.replace(/-/g, "+").replace(/_/g, "/");
  return Buffer.from(normalized + "=".repeat((4 - (normalized.length % 4)) % 4), "base64");
}
