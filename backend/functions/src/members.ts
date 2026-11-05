import { FieldValue, Timestamp } from "firebase-admin/firestore";
import { onCall } from "firebase-functions/v2/https";
import { getMembership, requireMember, requireUid } from "./access";
import { callOptions } from "./config";
import { fail } from "./errors";
import { db } from "./firebase";
import { parseMemberSettings } from "./rules/member";

/** Anzeigename und Benachrichtigungen des eigenen Mitglieds-Eintrags. */
export const updateMemberSettings = onCall(callOptions, async (request) => {
  const uid = requireUid(request);
  const input = parseMemberSettings(request.data);
  if (typeof input === "string") fail("invalid-argument", "invalid-input", input);
  const { clubRef } = await requireMember(uid);
  await clubRef.collection("members").doc(uid).update({
    publicName: input.publicName,
    notify: input.notify,
    updatedAt: FieldValue.serverTimestamp(),
  });
  return { ok: true };
});

/** Zeitstempel und Referenzen lesbar machen, damit das Ergebnis reines JSON ist. */
function plain(value: unknown): unknown {
  if (value instanceof Timestamp) return value.toDate().toISOString();
  if (Array.isArray(value)) return value.map(plain);
  if (value && typeof value === "object") {
    return Object.fromEntries(Object.entries(value as Record<string, unknown>).map(([k, v]) => [k, plain(v)]));
  }
  return value;
}

/**
 * Auskunft über alle eigenen Daten (Art. 15 DSGVO): Club-Zugehörigkeit, Mitglieds-Eintrag, Reservierungen,
 * dokumentierte Abgaben an dich, eigene Grow-Boxen, Automationen und Marktplatz-Inserate.
 * Der Verlauf der Messwerte ist ausgenommen (zu groß); er lässt sich in der Analyse ansehen.
 */
export const exportMyData = onCall(callOptions, async (request) => {
  const uid = requireUid(request);
  const membership = await getMembership(uid);
  const result: Record<string, unknown> = { exportedAt: new Date().toISOString(), uid, membership: plain(membership) };

  if (membership) {
    const clubRef = db().doc(`clubs/${membership.clubId}`);
    const [member, reservations, dispensations, listings] = await Promise.all([
      clubRef.collection("members").doc(uid).get(),
      clubRef.collection("reservations").where("memberUid", "==", uid).get(),
      clubRef.collection("dispensations").where("memberUid", "==", uid).get(),
      clubRef.collection("listings").where("sellerUid", "==", uid).get(),
    ]);
    result.member = plain(member.data() ?? null);
    result.reservations = reservations.docs.map((d) => ({ id: d.id, ...(plain(d.data()) as object) }));
    result.dispensations = dispensations.docs.map((d) => ({ id: d.id, ...(plain(d.data()) as object) }));
    result.marketplaceListings = listings.docs.map((d) => ({ id: d.id, ...(plain(d.data()) as object) }));
  }

  const [boxes, automations] = await Promise.all([
    db().collection(`users/${uid}/growBoxes`).get(),
    db().collection(`users/${uid}/automations`).get(),
  ]);
  result.growBoxes = await Promise.all(boxes.docs.map(async (d) => ({
    id: d.id,
    ...(plain(d.data()) as object),
    journal: (await d.ref.collection("journal").get()).docs.map((j) => ({ id: j.id, ...(plain(j.data()) as object) })),
    lightReadings: (await d.ref.collection("lightReadings").get()).docs.map((l) => ({ id: l.id, ...(plain(l.data()) as object) })),
    plants: (await d.ref.collection("plants").get()).docs.map((p) => ({ id: p.id, ...(plain(p.data()) as object) })),
  })));
  result.automations = automations.docs.map((d) => ({ id: d.id, ...(plain(d.data()) as object) }));
  return { json: JSON.stringify(result, null, 2) };
});
