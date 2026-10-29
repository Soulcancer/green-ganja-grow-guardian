import { Timestamp } from "firebase-admin/firestore";
import * as logger from "firebase-functions/logger";
import { db } from "./firebase";
import { activeLookupKeys } from "./revenuecat";
import { tierFromEntitlements, type Tier } from "./rules/grow";

/** So lange gilt eine bei RevenueCat geprüfte Stufe als aktuell. */
const TIER_CACHE_MS = 6 * 60 * 60 * 1000;

const TIERS: Tier[] = ["freemium", "premium", "social_club"];

/** Stufe direkt bei RevenueCat; merkt sie sich in `users/{uid}.tierCache` für Hintergrundaufgaben. */
export async function freshTier(uid: string): Promise<Tier> {
  const tier = tierFromEntitlements(await activeLookupKeys(uid));
  await db().doc(`users/${uid}`).set({ tierCache: { tier, checkedAt: Timestamp.now() } }, { merge: true });
  return tier;
}

/**
 * Stufe für Hintergrundaufgaben: bis zu 6 Stunden aus dem Zwischenspeicher, sonst von RevenueCat.
 * Ist RevenueCat nicht erreichbar, gilt der letzte bekannte Wert; gibt es keinen, wird der Fehler weitergereicht.
 */
export async function cachedTier(uid: string): Promise<Tier> {
  const cache = (await db().doc(`users/${uid}`).get()).get("tierCache") as { tier?: string; checkedAt?: Timestamp } | undefined;
  const known = TIERS.find((t) => t === cache?.tier);
  if (known && cache?.checkedAt && Date.now() - cache.checkedAt.toMillis() < TIER_CACHE_MS) return known;
  try {
    return await freshTier(uid);
  } catch (error) {
    if (known) {
      logger.warn("RevenueCat nicht erreichbar, nutze letzte bekannte Stufe", { uid, error });
      return known;
    }
    throw error;
  }
}
