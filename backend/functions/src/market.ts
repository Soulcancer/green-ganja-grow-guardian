import { FieldValue, Timestamp, type DocumentReference } from "firebase-admin/firestore";
import { onCall } from "firebase-functions/v2/https";
import { requireActiveClub, requireMember, requireStaff, requireUid, type ClubContext } from "./access";
import { callOptions } from "./config";
import { fail } from "./errors";
import { db } from "./firebase";
import type { MemberDoc } from "./model";
import { addDays, ageOn, berlinDate, monthOf, MIN_AGE } from "./rules/kcang";
import { isId, optionalText } from "./rules/input";
import {
  checkPropagation,
  LISTING_VALID_DAYS,
  MATERIALS,
  MAX_OPEN_LISTINGS_PER_MEMBER,
  mayChangeListing,
  parseGear,
  parsePropagation,
  REPORTS_TO_HIDE,
  type Material,
  type MaterialCount,
} from "./rules/market";
import { visibleName } from "./rules/member";
import { blockMessage, isBlocked, mayRemoveListing, MOD_LOG_RETENTION_DAYS, roleIn } from "./rules/moderation";

const now = () => FieldValue.serverTimestamp();
const expiry = () => Timestamp.fromDate(new Date(Date.now() + LISTING_VALID_DAYS * 24 * 60 * 60 * 1000));

/** Aktives Mitglied (oder Team) mit seinem Eintrag. */
async function requireActiveMember(uid: string): Promise<ClubContext & { member: MemberDoc }> {
  const context = await requireMember(uid);
  requireActiveClub(context.club);
  const member = (await context.clubRef.collection("members").doc(uid).get()).data() as MemberDoc | undefined;
  if (!member || member.status !== "active") fail("failed-precondition", "member-not-active", "Deine Mitgliedschaft ist noch nicht freigegeben.");
  return { ...context, member };
}

/** Von der Moderation im Marktplatz gesperrt: keine neuen Inserate und Anfragen. */
function requireNotBlocked(member: MemberDoc): void {
  const until = member.marketBlockedUntil?.toMillis();
  if (until !== undefined && isBlocked(until, Date.now())) {
    fail("permission-denied", "market-blocked", blockMessage(until, member.marketBlockReason));
  }
}

function nameOf(member: MemberDoc & { publicName?: string | null }): string {
  return visibleName(member.publicName, member.firstName, member.lastName, member.displayName);
}

/** Zubehör anbieten oder suchen. Jedes aktive Mitglied, höchstens 10 offene Inserate. */
export const createListing = onCall(callOptions, async (request) => {
  const uid = requireUid(request);
  const input = parseGear(request.data);
  if (typeof input === "string") fail("invalid-argument", "invalid-input", input);
  const { clubRef, member } = await requireActiveMember(uid);
  requireNotBlocked(member);

  const open = await clubRef.collection("listings").where("sellerUid", "==", uid).where("status", "in", ["open", "reserved"]).get();
  if (open.size >= MAX_OPEN_LISTINGS_PER_MEMBER) {
    fail("resource-exhausted", "listing-limit", `Du kannst höchstens ${MAX_OPEN_LISTINGS_PER_MEMBER} offene Inserate haben.`);
  }
  const ref = clubRef.collection("listings").doc();
  await ref.create({
    ...input,
    sellerUid: uid,
    sellerName: nameOf(member),
    status: "open",
    reports: 0,
    createdAt: now(),
    updatedAt: now(),
    expireAt: expiry(),
  });
  return { listingId: ref.id };
});

/** Club-Angebot für Samen oder Stecklinge. Nur das Team. */
export const createPropagationOffer = onCall(callOptions, async (request) => {
  const uid = requireUid(request);
  const input = parsePropagation(request.data);
  if (typeof input === "string") fail("invalid-argument", "invalid-input", input);
  const { clubRef, club } = await requireStaff(uid);
  requireActiveClub(club);
  const ref = clubRef.collection("listings").doc();
  await ref.create({
    ...input,
    sellerUid: uid,
    sellerName: club.name,
    status: "open",
    reports: 0,
    createdAt: now(),
    updatedAt: now(),
    expireAt: expiry(),
  });
  return { listingId: ref.id };
});

/** Status ändern: offen, reserviert, erledigt (Anbieter oder Team) oder entfernt (Team; Moderatoren nur Zubehör). */
export const setListingStatus = onCall(callOptions, async (request) => {
  const uid = requireUid(request);
  const id = request.data?.listingId;
  const status = request.data?.status;
  if (!isId(id) || typeof status !== "string") fail("invalid-argument", "invalid-input", "Ungültiges Inserat.");
  const { clubRef, club, membership } = await requireMember(uid);
  const ref = clubRef.collection("listings").doc(id);
  const snap = await ref.get();
  if (!snap.exists) fail("not-found", "not-found", "Dieses Inserat gibt es nicht mehr.");
  const role = roleIn(uid, club.ownerUid, club.staffUids, club.moderatorUids);
  const sellerUid = snap.get("sellerUid") as string;
  const kind = snap.get("kind") as string;
  const moderatorRemoves = status === "removed" && membership.status === "active" && mayRemoveListing(role, kind);
  if (!mayChangeListing(uid, sellerUid, club.staffUids.includes(uid), status) && !moderatorRemoves) {
    fail("permission-denied", "not-staff", "Das darfst du bei diesem Inserat nicht.");
  }
  const removedByOther = status === "removed" && uid !== sellerUid;
  const actor = removedByOther ? ((await clubRef.collection("members").doc(uid).get()).data() as MemberDoc | undefined) : undefined;
  const batch = db().batch();
  batch.update(ref, {
    status,
    updatedAt: now(),
    ...(status === "open" ? { expireAt: expiry() } : {}),
    ...(status === "removed" ? { removedBy: uid } : {}),
  });
  // Entfernen durch Team oder Moderation steht im Protokoll; die anbietende Person sieht den Eintrag
  if (removedByOther) {
    batch.create(clubRef.collection("modLog").doc(), {
      action: "remove",
      byUid: uid,
      byName: actor ? nameOf(actor) : "Club-Team",
      targetUid: kind === "gear" ? sellerUid : null,
      targetName: kind === "gear" ? (snap.get("sellerName") as string) : null,
      listingId: id,
      listingTitle: (kind === "gear" ? snap.get("title") : snap.get("strain")) as string,
      reason: null,
      until: null,
      createdAt: now(),
      expireAt: Timestamp.fromDate(new Date(Date.now() + MOD_LOG_RETENTION_DAYS * 24 * 60 * 60 * 1000)),
    });
  }
  await batch.commit();
  return { ok: true };
});

/** Interesse melden, mit kurzer Nachricht. Der Anbieter sieht Name und Nachricht. */
export const expressInterest = onCall(callOptions, async (request) => {
  const uid = requireUid(request);
  const id = request.data?.listingId;
  const message = optionalText(request.data?.message, 300);
  if (!isId(id) || message === null) fail("invalid-argument", "invalid-input", "Die Nachricht darf höchstens 300 Zeichen haben.");
  const { clubRef, member } = await requireActiveMember(uid);
  requireNotBlocked(member);
  const listing = await clubRef.collection("listings").doc(id).get();
  if (!listing.exists || listing.get("status") !== "open" || listing.get("kind") !== "gear") {
    fail("failed-precondition", "wrong-status", "Dieses Inserat ist nicht mehr offen.");
  }
  if (listing.get("sellerUid") === uid) fail("invalid-argument", "invalid-input", "Das ist dein eigenes Inserat.");
  await listing.ref.collection("interests").doc(uid).set({
    uid,
    name: nameOf(member),
    message: message ?? null,
    sellerUid: listing.get("sellerUid"),
    createdAt: now(),
  });
  return { ok: true };
});

/** Inserat melden. Ab 3 Meldungen ist es ausgeblendet, bis das Team entscheidet. */
export const reportListing = onCall(callOptions, async (request) => {
  const uid = requireUid(request);
  const id = request.data?.listingId;
  const reason = optionalText(request.data?.reason, 300);
  if (!isId(id) || reason === null) fail("invalid-argument", "invalid-input", "Der Grund darf höchstens 300 Zeichen haben.");
  const { clubRef } = await requireActiveMember(uid);
  await db().runTransaction(async (tx) => {
    const ref = clubRef.collection("listings").doc(id);
    const listing = await tx.get(ref);
    if (!listing.exists) fail("not-found", "not-found", "Dieses Inserat gibt es nicht mehr.");
    const reportRef = ref.collection("reports").doc(uid);
    if ((await tx.get(reportRef)).exists) return; // jede Person zählt einmal
    const reports = ((listing.get("reports") as number | undefined) ?? 0) + 1;
    tx.create(reportRef, { uid, reason: reason ?? null, createdAt: now() });
    tx.update(ref, { reports, ...(reports >= REPORTS_TO_HIDE && listing.get("status") === "open" ? { status: "reported" } : {}) });
  });
  return { ok: true };
});

async function monthCounts(clubRef: DocumentReference, memberUid: string, month: string): Promise<MaterialCount[]> {
  const snap = await clubRef.collection("propagationRequests").where("memberUid", "==", memberUid).where("month", "==", month).get();
  return snap.docs
    .filter((d) => ["requested", "handedOut"].includes(d.get("status")))
    .map((d) => ({ material: d.get("material") as Material, count: d.get("count") as number }));
}

/** Samen oder Stecklinge aus einem Club-Angebot anfragen. Prüft die Monatsgrenzen nach § 20 KCanG. */
export const requestPropagation = onCall(callOptions, async (request) => {
  const uid = requireUid(request);
  const id = request.data?.listingId;
  const count = request.data?.count;
  if (!isId(id) || typeof count !== "number" || !Number.isInteger(count) || count < 1) {
    fail("invalid-argument", "invalid-input", "Bitte eine Stückzahl angeben.");
  }
  const { clubRef, member } = await requireActiveMember(uid);
  if (!member.firstName || !member.lastName || !member.birthDate) {
    fail("failed-precondition", "profile-incomplete", "Das Club-Team muss zuerst Name und Geburtsdatum nach der Ausweiskontrolle eintragen.");
  }
  const today = berlinDate(new Date());
  if (ageOn(member.birthDate, today) < MIN_AGE) fail("failed-precondition", "under-18", "Vermehrungsmaterial gibt es erst ab 18 Jahren.");

  const listing = await clubRef.collection("listings").doc(id).get();
  if (!listing.exists || listing.get("kind") !== "propagation" || listing.get("status") !== "open") {
    fail("failed-precondition", "wrong-status", "Dieses Angebot ist nicht mehr offen.");
  }
  const material = listing.get("material") as Material;
  if (!MATERIALS.includes(material)) fail("failed-precondition", "wrong-status", "Ungültiges Angebot.");
  if (count > (listing.get("stock") as number)) fail("resource-exhausted", "propagation-limit", "So viele sind nicht mehr vorrätig.");

  const month = monthOf(today);
  const check = checkPropagation(await monthCounts(clubRef, uid, month), { material, count });
  if (!check.ok) {
    fail("resource-exhausted", "propagation-limit",
      `Pro Monat sind höchstens 7 Samen, 5 Stecklinge oder zusammen 5 erlaubt. Noch möglich: ${check.seedsLeft} Samen oder ${check.cuttingsLeft} Stecklinge.`);
  }
  const ref = clubRef.collection("propagationRequests").doc();
  await ref.create({
    memberUid: uid,
    memberName: `${member.firstName} ${member.lastName}`,
    listingId: id,
    strain: listing.get("strain"),
    material,
    count,
    month,
    status: "requested",
    createdAt: now(),
    updatedAt: now(),
    expireAt: Timestamp.fromDate(new Date(`${addDays(today, 90)}T00:00:00Z`)),
  });
  return { requestId: ref.id, seedsLeft: check.seedsLeft, cuttingsLeft: check.cuttingsLeft };
});

/** Eigene Anfrage zurückziehen. */
export const cancelPropagation = onCall(callOptions, async (request) => {
  const uid = requireUid(request);
  const id = request.data?.requestId;
  if (!isId(id)) fail("invalid-argument", "invalid-input", "Ungültige Anfrage.");
  const { clubRef } = await requireMember(uid);
  const ref = clubRef.collection("propagationRequests").doc(id);
  const snap = await ref.get();
  if (!snap.exists || snap.get("memberUid") !== uid) fail("not-found", "not-found", "Anfrage nicht gefunden.");
  if (snap.get("status") !== "requested") fail("failed-precondition", "wrong-status", "Diese Anfrage ist nicht mehr offen.");
  await ref.update({ status: "cancelled", updatedAt: now() });
  return { ok: true };
});

/**
 * Team: Ausgabe vor Ort nach Ausweiskontrolle dokumentieren oder ablehnen.
 * Die Ausgabe zählt den Bestand herunter und wird 5 Jahre in `propagationLog` aufbewahrt (§ 26 KCanG).
 */
export const decidePropagation = onCall(callOptions, async (request) => {
  const uid = requireUid(request);
  const id = request.data?.requestId;
  const decision = request.data?.decision;
  if (!isId(id) || (decision !== "handOut" && decision !== "decline")) fail("invalid-argument", "invalid-input", "Ungültige Entscheidung.");
  const { clubRef, club } = await requireStaff(uid);
  requireActiveClub(club);
  await db().runTransaction(async (tx) => {
    const ref = clubRef.collection("propagationRequests").doc(id);
    const req = await tx.get(ref);
    if (!req.exists || req.get("status") !== "requested") fail("failed-precondition", "wrong-status", "Diese Anfrage ist nicht mehr offen.");
    if (decision === "decline") {
      tx.update(ref, { status: "declined", decidedBy: uid, updatedAt: now() });
      return;
    }
    const listingRef = clubRef.collection("listings").doc(req.get("listingId") as string);
    const listing = await tx.get(listingRef);
    const stock = (listing.get("stock") as number | undefined) ?? 0;
    const count = req.get("count") as number;
    if (stock < count) fail("resource-exhausted", "propagation-limit", "Nicht mehr genug vorrätig.");
    tx.update(listingRef, { stock: stock - count, ...(stock - count === 0 ? { status: "closed" } : {}), updatedAt: now() });
    tx.update(ref, { status: "handedOut", decidedBy: uid, handedOutAt: now(), updatedAt: now() });
    tx.create(clubRef.collection("propagationLog").doc(), {
      requestId: id,
      memberUid: req.get("memberUid"),
      memberName: req.get("memberName"),
      material: req.get("material"),
      strain: req.get("strain"),
      count,
      date: berlinDate(new Date()),
      handedOutBy: uid,
      createdAt: now(),
      expireAt: Timestamp.fromDate(new Date(Date.now() + 5 * 366 * 24 * 60 * 60 * 1000)),
    });
  });
  return { ok: true };
});
