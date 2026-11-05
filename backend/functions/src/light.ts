import { FieldValue } from "firebase-admin/firestore";
import * as logger from "firebase-functions/logger";
import { onCall } from "firebase-functions/v2/https";
import { requireUid } from "./access";
import { storeCallOptions } from "./config";
import { fail } from "./errors";
import { db } from "./firebase";
import { lampsAllowed, lightField, needsPremium, parseLightSetup } from "./rules/light";
import { freshTier } from "./tier";
import type { Tier } from "./rules/grow";

/**
 * Lampen einer Box speichern. Freemium darf nur Tageslicht und Glühbirne wählen, alle anderen Lampen
 * gibt es ab Premium. Die Stufe kommt direkt von RevenueCat.
 */
export const saveLightSetup = onCall(storeCallOptions, async (request) => {
  const uid = requireUid(request);
  const input = parseLightSetup(request.data);
  if (typeof input === "string") fail("invalid-argument", "invalid-input", input);

  let tier: Tier = "freemium";
  if (needsPremium(input)) {
    try {
      tier = await freshTier(uid);
    } catch (error) {
      logger.error("Stufe nicht prüfbar", { uid, error });
      fail("unavailable", "store-unavailable", "Dein Abo-Status ist gerade nicht prüfbar. Bitte versuch es gleich noch einmal.");
    }
  }
  if (!lampsAllowed(input, tier)) {
    fail("permission-denied", "lamps-premium", "Ohne Abo gehen nur Tageslicht und Glühbirne. Alle anderen Lampen gibt es ab Premium Farmer.");
  }

  const ref = db().doc(`users/${uid}/growBoxes/${input.boxId}`);
  const snap = await ref.get();
  if (!snap.exists) fail("not-found", "not-found", "Die Box gibt es nicht mehr.");
  await ref.update({ light: lightField(input), updatedAt: FieldValue.serverTimestamp() });
  return { ok: true };
});
