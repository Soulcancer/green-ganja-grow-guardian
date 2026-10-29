import { FieldValue, Timestamp, type DocumentReference } from "firebase-admin/firestore";
import * as logger from "firebase-functions/logger";
import { GRANT_BUFFER_MS, TWO_HOURS_MS } from "./config";
import { db } from "./firebase";
import type { ClubDoc } from "./model";
import { grantSocialClub, revokeSocialClub, socialClubActiveUntil } from "./revenuecat";

/** Gibt einem Team-Mitglied Social Club, solange das Abo des Inhabers läuft. */
export async function grantSeat(clubRef: DocumentReference, uid: string, ownerExpiresAtMs: number): Promise<void> {
  const until = ownerExpiresAtMs + GRANT_BUFFER_MS;
  await grantSocialClub(uid, until);
  await clubRef.update({ [`staffGrants.${uid}`]: until });
}

/** Entzieht einen Team-Platz. Schlägt das fehl, holt der nächtliche Abgleich es nach. */
export async function revokeSeat(clubRef: DocumentReference, uid: string): Promise<void> {
  try {
    await revokeSocialClub(uid);
    await clubRef.update({ [`staffGrants.${uid}`]: FieldValue.delete() });
  } catch (error) {
    logger.error("Team-Platz konnte nicht entzogen werden", { clubId: clubRef.id, uid, error });
  }
}

/**
 * Gleicht einen Club mit RevenueCat ab: Läuft das Abo des Inhabers, werden die Team-Plätze
 * bis zu dessen Ablauf freigegeben. Läuft es nicht, oder hat jemand das Team verlassen,
 * wird entzogen. Mehrfaches Ausführen ist unschädlich.
 */
export async function reconcileClub(clubId: string): Promise<void> {
  const clubRef = db().doc(`clubs/${clubId}`);
  const snap = await clubRef.get();
  if (!snap.exists) return;
  const club = snap.data() as ClubDoc;

  const now = Date.now();
  const ownerUntil = await socialClubActiveUntil(club.ownerUid);
  const active = ownerUntil !== null && ownerUntil > now;
  const grants = club.staffGrants ?? {};
  const seatHolders = club.staffUids.filter((uid) => uid !== club.ownerUid);
  const updates: Record<string, unknown> = {
    active,
    expiresAt: ownerUntil !== null ? Timestamp.fromMillis(ownerUntil) : null,
    updatedAt: FieldValue.serverTimestamp(),
  };
  const failures: unknown[] = [];

  if (active) {
    const grantUntil = ownerUntil + GRANT_BUFFER_MS;
    for (const uid of seatHolders) {
      const current = grants[uid] ?? 0;
      try {
        if (current > grantUntil + TWO_HOURS_MS) {
          // Abo wurde verkürzt: RevenueCat kann nur verlängern, also neu vergeben.
          await revokeSocialClub(uid);
          await grantSocialClub(uid, grantUntil);
          updates[`staffGrants.${uid}`] = grantUntil;
        } else if (current + TWO_HOURS_MS <= grantUntil) {
          await grantSocialClub(uid, grantUntil);
          updates[`staffGrants.${uid}`] = grantUntil;
        }
      } catch (error) {
        failures.push(error);
      }
    }
  }

  for (const uid of Object.keys(grants)) {
    if (active && seatHolders.includes(uid)) continue;
    try {
      await revokeSocialClub(uid);
      updates[`staffGrants.${uid}`] = FieldValue.delete();
    } catch (error) {
      failures.push(error);
    }
  }

  await clubRef.update(updates);
  if (failures.length > 0) {
    logger.error("Abgleich mit RevenueCat unvollständig", { clubId, failures });
    throw new Error(`Abgleich für Club ${clubId} unvollständig (${failures.length} Fehler)`);
  }
}
