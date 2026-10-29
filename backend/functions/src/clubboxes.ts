import { FieldValue } from "firebase-admin/firestore";
import { onCall } from "firebase-functions/v2/https";
import { requireActiveClub, requireStaff, requireUid } from "./access";
import { storeCallOptions } from "./config";
import { fail } from "./errors";
import { db, displayNameOf } from "./firebase";
import { checkShare, configOf, mayUnshare, MAX_CLUB_BOXES, parseShareRequest, parseUnshareRequest } from "./rules/clubbox";

/**
 * Eine eigene Grow-Box für den Club teilen (nur Team, nur mit aktivem Social-Club-Abo).
 * Erneutes Teilen derselben Box aktualisiert Name, Phase und Zielwerte.
 */
export const shareClubBox = onCall(storeCallOptions, async (request) => {
  const uid = requireUid(request);
  const boxId = parseShareRequest(request.data);
  if (!boxId) fail("invalid-argument", "invalid-input", "Ungültige Box.");
  const { clubRef, club } = await requireStaff(uid);
  requireActiveClub(club);

  const source = await db().doc(`users/${uid}/growBoxes/${boxId}`).get();
  const config = source.exists ? configOf(source.data() ?? {}) : null;
  if (!config) fail("not-found", "not-found", "Diese Box gibt es nicht mehr.");

  const keeperName = await displayNameOf(uid);
  const clubBoxId = await db().runTransaction(async (tx) => {
    const boxes = await tx.get(clubRef.collection("growBoxes"));
    const existing = boxes.docs.find((d) => d.get("keeperUid") === uid && d.get("sourceBoxId") === boxId);
    const check = checkShare(boxes.size, existing !== undefined);
    if (!check.ok) fail("resource-exhausted", "club-box-limit", `Dein Club kann bis zu ${MAX_CLUB_BOXES} Boxen teilen.`);
    if (existing) {
      tx.update(existing.ref, { ...config, keeperName, updatedAt: FieldValue.serverTimestamp() });
      return existing.id;
    }
    const ref = clubRef.collection("growBoxes").doc();
    tx.create(ref, {
      ...config,
      keeperUid: uid,
      keeperName,
      sourceBoxId: boxId,
      latest: null,
      createdAt: FieldValue.serverTimestamp(),
      updatedAt: FieldValue.serverTimestamp(),
    });
    return ref.id;
  });
  return { clubBoxId };
});

/** Teilen beenden: der Betreuer oder der Inhaber. Die Box im eigenen Konto bleibt. */
export const unshareClubBox = onCall(storeCallOptions, async (request) => {
  const uid = requireUid(request);
  const clubBoxId = parseUnshareRequest(request.data);
  if (!clubBoxId) fail("invalid-argument", "invalid-input", "Ungültige Box.");
  const { clubRef, club } = await requireStaff(uid);
  const ref = clubRef.collection("growBoxes").doc(clubBoxId);
  const snap = await ref.get();
  if (!snap.exists) fail("not-found", "not-found", "Diese Box gibt es nicht mehr.");
  if (!mayUnshare(uid, snap.get("keeperUid") as string, club.ownerUid)) {
    fail("permission-denied", "not-staff", "Nur der Betreuer oder der Club-Inhaber kann das Teilen beenden.");
  }
  await db().recursiveDelete(ref);
  return { ok: true };
});
