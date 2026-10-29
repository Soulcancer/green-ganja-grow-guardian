import { FieldValue } from "firebase-admin/firestore";
import * as logger from "firebase-functions/logger";
import { onCall } from "firebase-functions/v2/https";
import { getMembership, requireUid } from "./access";
import { removeFromClub } from "./clubs";
import { storeCallOptions } from "./config";
import { fail } from "./errors";
import { auth, db } from "./firebase";
import { revokeSeat } from "./seats";

/**
 * Konto löschen (Pflicht für Google Play, wenn sich Nutzer in der App registrieren können).
 *
 * Gelöscht werden: Grow-Boxen mit Verlauf, Automationen, Coins, Tuya-Zugang, Club-Mitgliedschaft (offene
 * Reservierungen werden storniert), geteilte Club-Boxen und das Firebase-Konto. Eigene Inserate werden entfernt.
 * Aufbewahrt bleibt, was das Gesetz verlangt: die Abgabe-Dokumentation nach § 26 KCanG beim Club (5 Jahre).
 * Ein laufendes Google-Play-Abo endet dadurch nicht; das kündigt der Nutzer im Play Store.
 * Club-Inhaber müssen zuerst den Club auflösen (Abo kündigen), damit Mitglieder nicht ohne Club dastehen.
 */
export const deleteMyAccount = onCall(storeCallOptions, async (request) => {
  const uid = requireUid(request);
  const membership = await getMembership(uid);
  if (membership?.role === "owner") {
    fail("failed-precondition", "owner-cannot-leave",
      "Als Club-Inhaber kannst du dein Konto erst löschen, wenn der Club aufgelöst ist. Kündige dazu das Social-Club-Abo und schreib uns.");
  }

  if (membership) {
    const clubRef = db().doc(`clubs/${membership.clubId}`);
    await removeFromClub(clubRef, uid);
    if (membership.role === "staff") await revokeSeat(clubRef, uid);
    const [shared, listings] = await Promise.all([
      clubRef.collection("growBoxes").where("keeperUid", "==", uid).get(),
      clubRef.collection("listings").where("sellerUid", "==", uid).get(),
    ]);
    await Promise.all(shared.docs.map((d) => db().recursiveDelete(d.ref)));
    await Promise.all(listings.docs.map((d) => d.ref.update({ status: "removed", updatedAt: FieldValue.serverTimestamp() })));
  }

  await db().recursiveDelete(db().doc(`users/${uid}`));
  await db().recursiveDelete(db().doc(`wallets/${uid}`));
  await db().doc(`tuyaAccounts/${uid}`).delete();
  await auth().deleteUser(uid);
  logger.info("Konto gelöscht", { uid });
  return { ok: true };
});
