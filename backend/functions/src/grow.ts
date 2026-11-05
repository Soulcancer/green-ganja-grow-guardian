import { FieldValue, type DocumentData, type Timestamp } from "firebase-admin/firestore";
import * as logger from "firebase-functions/logger";
import { onCall } from "firebase-functions/v2/https";
import { getMembership, requireUid } from "./access";
import { storeCallOptions } from "./config";
import { fail } from "./errors";
import { db } from "./firebase";
import { deleteAutomationsOfBox } from "./automations";
import { freshTier } from "./tier";
import { checkGrowLimits, parseGrowBox, TIER_LIMITS, type ExistingBox, type LimitResult, type TierLimits } from "./rules/grow";

function boxesRef(uid: string) {
  return db().collection(`users/${uid}/growBoxes`);
}

function toExisting(id: string, data: DocumentData): ExistingBox {
  const createdAt = (data.createdAt as Timestamp | undefined)?.toMillis() ?? 0;
  const deviceIds = Array.isArray(data.deviceIds) ? data.deviceIds.filter((d: unknown): d is string => typeof d === "string") : [];
  return { id, createdAt, deviceIds };
}

function failLimit(result: Exclude<LimitResult, { ok: true }>, limits: TierLimits): never {
  switch (result.reason) {
    case "box-limit":
      fail("resource-exhausted", "box-limit", `Deine Stufe erlaubt ${limits.maxBoxes} Grow-Box(en). Mit einem Upgrade bekommst du mehr.`);
    case "box-locked":
      fail("failed-precondition", "box-locked", "Diese Box ist mit deiner Stufe gesperrt. Lösche eine ältere Box oder mach ein Upgrade.");
    case "device-limit":
      fail("resource-exhausted", "device-limit", `Deine Stufe überwacht bis zu ${limits.maxDevices} Sensoren.`);
    case "not-found":
      fail("not-found", "not-found", "Diese Box gibt es nicht mehr.");
  }
}

/**
 * Legt eine Grow-Box an oder speichert sie. Prüft die Limits der Stufe; RevenueCat wird nur gefragt,
 * wenn die Änderung über Freemium hinausgeht. Sensoren werden aus anderen Boxen des Nutzers entfernt.
 */
export const saveGrowBox = onCall(storeCallOptions, async (request) => {
  const uid = requireUid(request);
  const input = parseGrowBox(request.data);
  if (typeof input === "string") fail("invalid-argument", "invalid-input", input);

  // Vorab prüfen, ob die Freemium-Grenzen reichen; sonst Stufe bei RevenueCat nachsehen.
  const preview = (await boxesRef(uid).get()).docs.map((d) => toExisting(d.id, d.data()));
  let limits = TIER_LIMITS.freemium;
  if (!checkGrowLimits(preview, input, limits).ok) {
    try {
      limits = TIER_LIMITS[await freshTier(uid)];
    } catch (error) {
      logger.error("Stufe nicht prüfbar", { uid, error });
      fail("unavailable", "store-unavailable", "Dein Abo-Status ist gerade nicht prüfbar. Bitte versuch es gleich noch einmal.");
    }
  }

  const boxId = await db().runTransaction(async (tx) => {
    // Das Nutzer-Dokument serialisiert gleichzeitige Änderungen desselben Nutzers.
    const userRef = db().doc(`users/${uid}`);
    await tx.get(userRef);
    const snap = await tx.get(boxesRef(uid));
    const existing = snap.docs.map((d) => toExisting(d.id, d.data()));
    const result = checkGrowLimits(existing, input, limits);
    if (!result.ok) failLimit(result, limits);

    const ref = input.boxId === null ? boxesRef(uid).doc() : boxesRef(uid).doc(input.boxId);
    const fields = {
      name: input.name,
      phase: input.phase,
      medium: input.medium,
      leafOffsetC: input.leafOffsetC,
      deviceIds: input.deviceIds,
      targets: input.targets,
      alerts: input.alerts,
      strain: input.strain,
      plantedAt: input.plantedAt,
      updatedAt: FieldValue.serverTimestamp(),
    };
    if (input.boxId === null) tx.create(ref, { ...fields, createdAt: FieldValue.serverTimestamp() });
    else tx.update(ref, fields);

    // Ein Sensor gehört zu genau einer Box
    for (const doc of snap.docs) {
      if (doc.id === ref.id) continue;
      const ids = toExisting(doc.id, doc.data()).deviceIds;
      const kept = ids.filter((id) => !input.deviceIds.includes(id));
      if (kept.length !== ids.length) tx.update(doc.ref, { deviceIds: kept, updatedAt: FieldValue.serverTimestamp() });
    }
    tx.set(userRef, { growUpdatedAt: FieldValue.serverTimestamp() }, { merge: true });
    return ref.id;
  });
  return { boxId };
});

export const deleteGrowBox = onCall(storeCallOptions, async (request) => {
  const uid = requireUid(request);
  const boxId = (request.data as { boxId?: unknown } | null)?.boxId;
  if (typeof boxId !== "string" || !/^[A-Za-z0-9_-]{1,128}$/.test(boxId)) fail("invalid-argument", "invalid-input", "Ungültige Box.");
  // Box samt Verlauf, Statistik, Tagebuch und Lichtmessungen löschen
  await db().recursiveDelete(boxesRef(uid).doc(boxId));
  await deleteAutomationsOfBox(uid, boxId);
  const removedClubBoxes = await deleteSharedCopies(uid, boxId);
  return { ok: true, removedClubBoxes };
});

/** Für den Club geteilte Ansichten der Box enden mit ihr (sonst blieben verwaiste Club-Boxen stehen). */
async function deleteSharedCopies(uid: string, boxId: string): Promise<number> {
  const membership = await getMembership(uid);
  if (!membership) return 0;
  try {
    const shared = await db().collection(`clubs/${membership.clubId}/growBoxes`).where("keeperUid", "==", uid).get();
    const copies = shared.docs.filter((d) => d.get("sourceBoxId") === boxId);
    await Promise.all(copies.map((d) => db().recursiveDelete(d.ref)));
    return copies.length;
  } catch (error) {
    // Die eigene Box ist schon gelöscht; eine übrig gebliebene Club-Ansicht kann das Team von Hand beenden
    logger.warn("Geteilte Club-Box nicht entfernt", { uid, boxId, error: String(error) });
    return 0;
  }
}
