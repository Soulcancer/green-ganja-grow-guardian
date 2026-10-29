import { createVerify } from "node:crypto";
import { FieldValue, Timestamp, type DocumentReference, type Transaction } from "firebase-admin/firestore";
import * as logger from "firebase-functions/logger";
import { onCall, onRequest } from "firebase-functions/v2/https";
import { getMembership, requireUid } from "./access";
import { callOptions, REGION, storeCallOptions } from "./config";
import { fail } from "./errors";
import { db } from "./firebase";
import {
  ACHIEVEMENTS,
  base64UrlToBuffer,
  checkIn,
  grantAchievement,
  purchase,
  REWARDED_AD_COINS,
  rewardAd,
  SHOP,
  splitSsvQuery,
  walletFrom,
  type AchievementId,
  type WalletState,
} from "./rules/coins";
import { berlinDate } from "./rules/kcang";
import { freshTier } from "./tier";

/**
 * Struktur:
 * wallets/{uid}              Guthaben, Serie, werbefrei/Alarme bis, Erfolge (liest nur der Nutzer, schreiben nur Functions)
 * wallets/{uid}/ledger/{id}  Buchungen, 1 Jahr aufbewahrt
 * adRewards/{transactionId}  Bereits gutgeschriebene Belohnungsvideos (gegen doppelte Gutschrift)
 */
const walletRef = (uid: string) => db().doc(`wallets/${uid}`);
const YEAR_MS = 365 * 24 * 60 * 60 * 1000;

function writeWallet(tx: Transaction, ref: DocumentReference, wallet: WalletState): void {
  tx.set(ref, {
    ...wallet,
    adFreeUntil: Timestamp.fromMillis(wallet.adFreeUntil),
    alertsUntil: Timestamp.fromMillis(wallet.alertsUntil),
    updatedAt: FieldValue.serverTimestamp(),
  });
}

function book(tx: Transaction, uid: string, amount: number, reason: string): void {
  tx.create(walletRef(uid).collection("ledger").doc(), {
    amount,
    reason,
    createdAt: FieldValue.serverTimestamp(),
    expireAt: Timestamp.fromMillis(Date.now() + YEAR_MS),
  });
}

const publicWallet = (w: WalletState) => ({
  balance: w.balance, streak: w.streak, adFreeUntil: w.adFreeUntil, alertsUntil: w.alertsUntil, achievements: w.achievements,
});

/** Tägliche Belohnung abholen. Nach 7 Tagen in Folge zusätzlich der Erfolg „week_streak“. */
export const dailyCheckIn = onCall(callOptions, async (request) => {
  const uid = requireUid(request);
  const today = berlinDate(new Date());
  return db().runTransaction(async (tx) => {
    const ref = walletRef(uid);
    let wallet = walletFrom((await tx.get(ref)).data());
    const result = checkIn(wallet, today);
    if (!result.ok) fail("failed-precondition", "already-claimed", "Die tägliche Belohnung hast du heute schon abgeholt.");
    wallet = result.wallet;
    book(tx, uid, result.reward, `Tag ${result.streak} der Serie`);
    let bonus = 0;
    if (result.streak >= 7) {
      const extra = grantAchievement(wallet, "week_streak");
      if (extra.ok) {
        wallet = extra.wallet;
        bonus = extra.coins;
        book(tx, uid, extra.coins, ACHIEVEMENTS.week_streak.label);
      }
    }
    writeWallet(tx, ref, wallet);
    return { reward: result.reward, bonus, streak: result.streak, wallet: publicWallet(wallet) };
  });
});

/** Prüft alle Erfolge und schreibt neu erreichte gut. */
export const claimAchievements = onCall(callOptions, async (request) => {
  const uid = requireUid(request);
  const membership = await getMembership(uid);
  const [boxes, automations, listings] = await Promise.all([
    db().collection(`users/${uid}/growBoxes`).limit(1).get(),
    db().collection(`users/${uid}/automations`).limit(1).get(),
    membership
      ? db().collection(`clubs/${membership.clubId}/listings`).where("sellerUid", "==", uid).limit(1).get()
      : Promise.resolve(null),
  ]);
  const reached: AchievementId[] = [];
  if (!boxes.empty) reached.push("first_box");
  if (!automations.empty) reached.push("first_automation");
  if (listings && !listings.empty) reached.push("first_listing");
  if (membership?.status === "active") reached.push("club_member");

  return db().runTransaction(async (tx) => {
    const ref = walletRef(uid);
    let wallet = walletFrom((await tx.get(ref)).data());
    if (wallet.streak >= 7) reached.push("week_streak");
    const granted: { id: string; coins: number; label: string }[] = [];
    for (const id of reached) {
      const r = grantAchievement(wallet, id);
      if (!r.ok) continue;
      wallet = r.wallet;
      granted.push({ id, coins: r.coins, label: ACHIEVEMENTS[id].label });
      book(tx, uid, r.coins, ACHIEVEMENTS[id].label);
    }
    if (granted.length > 0) writeWallet(tx, ref, wallet);
    return { granted, wallet: publicWallet(wallet) };
  });
});

/** Coins einlösen. Alarme auf Zeit nur, wenn die Stufe sie nicht ohnehin enthält. */
export const redeemCoins = onCall(storeCallOptions, async (request) => {
  const uid = requireUid(request);
  const item = request.data?.item;
  const listingId = request.data?.listingId;
  if (typeof item !== "string" || !(item in SHOP)) fail("invalid-argument", "invalid-input", "Unbekannter Artikel.");

  let hasFeature = false;
  if (item === "alerts_3d") {
    try {
      hasFeature = (await freshTier(uid)) !== "freemium";
    } catch {
      fail("unavailable", "store-unavailable", "Dein Abo-Status ist gerade nicht prüfbar. Bitte versuch es gleich noch einmal.");
    }
  }
  let listingRef: DocumentReference | null = null;
  if (item === "boost_listing") {
    const membership = await getMembership(uid);
    if (!membership || typeof listingId !== "string" || !/^[A-Za-z0-9_-]{1,128}$/.test(listingId)) {
      fail("invalid-argument", "invalid-input", "Bitte ein eigenes Inserat wählen.");
    }
    listingRef = db().doc(`clubs/${membership.clubId}/listings/${listingId}`);
  }

  return db().runTransaction(async (tx) => {
    const ref = walletRef(uid);
    const wallet = walletFrom((await tx.get(ref)).data());
    let currentUntil = 0;
    if (listingRef) {
      const listing = await tx.get(listingRef);
      if (!listing.exists || listing.get("sellerUid") !== uid || listing.get("status") !== "open") {
        fail("failed-precondition", "wrong-status", "Hervorheben geht nur bei eigenen, offenen Inseraten.");
      }
      currentUntil = (listing.get("boostedUntil") as Timestamp | undefined)?.toMillis() ?? 0;
    }
    const result = purchase(wallet, item, Date.now(), currentUntil, hasFeature);
    if (!result.ok) {
      if (result.reason === "insufficient") fail("failed-precondition", "insufficient-coins", "Dafür reichen deine Coins noch nicht.");
      if (result.reason === "not-needed") fail("failed-precondition", "not-needed", "Deine Stufe enthält Alarme schon.");
      fail("invalid-argument", "invalid-input", "Unbekannter Artikel.");
    }
    if (listingRef) tx.update(listingRef, { boostedUntil: Timestamp.fromMillis(result.until) });
    book(tx, uid, -SHOP[item as keyof typeof SHOP].coins, SHOP[item as keyof typeof SHOP].label);
    writeWallet(tx, ref, result.wallet);
    return { until: result.until, wallet: publicWallet(result.wallet) };
  });
});

// ---------- Belohnungsvideos: Rückmeldung von AdMob (Server-Side Verification) ----------

const KEYS_URL = "https://www.gstatic.com/admob/reward/verifier-keys.json";
let keyCache: { at: number; keys: Map<string, string> } | null = null;

async function verifierKeys(): Promise<Map<string, string>> {
  if (keyCache && Date.now() - keyCache.at < 24 * 60 * 60 * 1000) return keyCache.keys;
  const response = await fetch(KEYS_URL);
  if (!response.ok) throw new Error(`Schlüssel nicht ladbar: ${response.status}`);
  const body = (await response.json()) as { keys?: { keyId?: number | string; pem?: string }[] };
  const keys = new Map<string, string>();
  for (const key of body.keys ?? []) {
    if (key.keyId !== undefined && key.pem) keys.set(String(key.keyId), key.pem);
  }
  keyCache = { at: Date.now(), keys };
  return keys;
}

/**
 * AdMob ruft diese URL nach jedem fertig angesehenen Belohnungsvideo auf. In AdMob beim Anzeigenblock
 * „Serverseitige Bestätigung“ mit dieser URL eintragen; die App setzt die Firebase-UID als `user_id`.
 */
export const admobRewardCallback = onRequest({ region: REGION, maxInstances: 10 }, async (req, res) => {
  const query = req.originalUrl.includes("?") ? req.originalUrl.slice(req.originalUrl.indexOf("?") + 1) : "";
  const parts = splitSsvQuery(query);
  if (!parts) {
    // AdMob prüft die URL beim Eintragen ohne Parameter; das darf nicht fehlschlagen
    res.status(200).send("ok");
    return;
  }
  let valid = false;
  try {
    const pem = (await verifierKeys()).get(parts.keyId);
    valid = !!pem && createVerify("sha256").update(parts.message).verify(pem, base64UrlToBuffer(parts.signature));
  } catch (error) {
    logger.error("SSV-Prüfung fehlgeschlagen", { error });
    res.status(500).send("retry");
    return;
  }
  if (!valid) {
    logger.warn("Belohnung mit ungültiger Signatur abgewiesen");
    res.status(400).send("invalid");
    return;
  }

  const params = new URLSearchParams(parts.message);
  const uid = params.get("user_id");
  const transactionId = params.get("transaction_id");
  if (!uid || !/^[A-Za-z0-9]{1,128}$/.test(uid) || !transactionId) {
    res.status(200).send("ignored");
    return;
  }
  await db().runTransaction(async (tx) => {
    const seen = db().doc(`adRewards/${transactionId.replace(/[^A-Za-z0-9_-]/g, "_").slice(0, 200)}`);
    if ((await tx.get(seen)).exists) return;
    const ref = walletRef(uid);
    const wallet = walletFrom((await tx.get(ref)).data());
    const result = rewardAd(wallet, berlinDate(new Date()));
    tx.create(seen, { uid, granted: result.ok, createdAt: FieldValue.serverTimestamp(), expireAt: Timestamp.fromMillis(Date.now() + YEAR_MS) });
    if (!result.ok) return; // Tagesgrenze erreicht: Video zählt, gibt aber nichts
    book(tx, uid, REWARDED_AD_COINS, "Belohnungsvideo");
    writeWallet(tx, ref, result.wallet);
  });
  res.status(200).send("ok");
});
