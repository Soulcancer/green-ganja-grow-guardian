import { FieldValue, Timestamp, type DocumentReference } from "firebase-admin/firestore";
import { onCall } from "firebase-functions/v2/https";
import { getMembership, openReservationRefs, requireActiveClub, requireOwner, requireStaff, requireUid } from "./access";
import { DAY_MS, SEATS_PER_CLUB, STAFF_INVITE_VALID_DAYS, callOptions, storeCallOptions } from "./config";
import { fail } from "./errors";
import { db, displayNameOf } from "./firebase";
import type { ClubDoc, InviteDoc, MemberDoc, MembershipDoc } from "./model";
import { socialClubActiveUntil } from "./revenuecat";
import { generateCode, normalizeCode } from "./rules/codes";
import { isId, requiredText } from "./rules/input";
import { MIN_AGE, ageOn, berlinDate, isValidDate } from "./rules/kcang";
import { grantSeat, revokeSeat } from "./seats";

const now = () => FieldValue.serverTimestamp();

async function storeCall<T>(work: () => Promise<T>): Promise<T> {
  try {
    return await work();
  } catch {
    fail("unavailable", "store-unavailable", "Der Abo-Status ist gerade nicht abrufbar. Bitte versuche es gleich noch einmal.");
  }
}

/** Club anlegen. Voraussetzung: eigenes, aktives Social-Club-Abo. Der Käufer belegt den ersten Platz. */
export const createClub = onCall(storeCallOptions, async (request) => {
  const uid = requireUid(request);
  const name = requiredText(request.data?.name, 2, 80);
  if (!name) fail("invalid-argument", "invalid-input", "Bitte gib einen Clubnamen mit 2 bis 80 Zeichen ein.");

  const until = await storeCall(() => socialClubActiveUntil(uid));
  if (until === null || until <= Date.now()) {
    fail("failed-precondition", "no-subscription", "Für einen eigenen Club brauchst du ein aktives Social-Club-Abo.");
  }

  const displayName = await displayNameOf(uid);
  const memberCode = generateCode();
  const clubRef = db().doc(`clubs/${uid}`);
  const membershipRef = db().doc(`memberships/${uid}`);

  await db().runTransaction(async (tx) => {
    const [membership, club] = await tx.getAll(membershipRef, clubRef);
    if (membership.exists || club.exists) {
      fail("failed-precondition", "already-in-club", "Du bist bereits in einem Club.");
    }
    tx.create(clubRef, {
      name,
      ownerUid: uid,
      seats: SEATS_PER_CLUB,
      staffUids: [uid],
      active: true,
      expiresAt: Timestamp.fromMillis(until),
      memberCode,
      staffGrants: {},
      moderatorUids: [],
      createdAt: now(),
      updatedAt: now(),
    });
    tx.create(membershipRef, { clubId: uid, clubName: name, role: "owner", status: "active", updatedAt: now() });
    tx.create(clubRef.collection("members").doc(uid), {
      uid,
      role: "owner",
      status: "active",
      displayName,
      joinedAt: now(),
    });
  });

  return { clubId: uid, memberCode };
});

/** Einladung für einen Team-Platz (Social Club Farmer), 7 Tage gültig. Nur für den Inhaber. */
export const createStaffInvite = onCall(callOptions, async (request) => {
  const { uid, club, clubRef } = await requireOwner(requireUid(request));
  requireActiveClub(club);
  if (club.staffUids.length >= club.seats) {
    fail("resource-exhausted", "club-full", `Alle ${club.seats} Plätze sind belegt.`);
  }

  const expiresAt = Timestamp.fromMillis(Date.now() + STAFF_INVITE_VALID_DAYS * DAY_MS);
  for (let attempt = 0; attempt < 3; attempt++) {
    const code = generateCode();
    const invite: InviteDoc = { clubId: clubRef.id, type: "staff", createdBy: uid, expiresAt };
    try {
      await db().doc(`invites/${code}`).create({ ...invite, createdAt: now() });
      return { code, expiresAt: expiresAt.toMillis() };
    } catch {
      // Code war schon vergeben, neuer Versuch
    }
  }
  fail("internal", "invalid-input", "Es konnte kein Code erzeugt werden. Bitte nochmal versuchen.");
});

/**
 * Mit einem Code beitreten. Zwei Arten:
 * - Team-Einladung: sofort Team-Platz mit Social Club (zählt zu den 5 Plätzen)
 * - Mitglieder-Code des Clubs: Aufnahmeantrag, den das Team nach Ausweiskontrolle freigibt
 */
export const joinClub = onCall(storeCallOptions, async (request) => {
  const uid = requireUid(request);
  const code = normalizeCode(request.data?.code);
  if (!code) fail("invalid-argument", "invalid-code", "Dieser Code ist ungültig.");

  const displayName = await displayNameOf(uid);
  const inviteRef = db().doc(`invites/${code}`);
  if ((await inviteRef.get()).exists) return joinAsStaff(uid, displayName, inviteRef);

  const clubs = await db().collection("clubs").where("memberCode", "==", code).limit(1).get();
  if (clubs.empty) fail("not-found", "invalid-code", "Dieser Code ist ungültig.");
  return requestMembership(uid, displayName, clubs.docs[0].ref, code);
});

async function joinAsStaff(uid: string, displayName: string, inviteRef: DocumentReference) {
  const membershipRef = db().doc(`memberships/${uid}`);
  const joined = await db().runTransaction(async (tx) => {
    const inviteSnap = await tx.get(inviteRef);
    if (!inviteSnap.exists) fail("not-found", "invalid-code", "Dieser Code ist ungültig.");
    const invite = inviteSnap.data() as InviteDoc;
    if (invite.expiresAt.toMillis() < Date.now()) {
      fail("failed-precondition", "code-expired", "Diese Einladung ist abgelaufen.");
    }

    const clubRef = db().doc(`clubs/${invite.clubId}`);
    const memberRef = clubRef.collection("members").doc(uid);
    const [clubSnap, membershipSnap, memberSnap] = await tx.getAll(clubRef, membershipRef, memberRef);
    const club = clubSnap.data() as ClubDoc | undefined;
    if (!club) fail("not-found", "invalid-code", "Diesen Club gibt es nicht mehr.");
    requireActiveClub(club);

    const existing = membershipSnap.data() as MembershipDoc | undefined;
    // Mitglieder und Moderatoren desselben Clubs dürfen einen Team-Platz annehmen
    if (existing && (existing.clubId !== clubRef.id || (existing.role !== "member" && existing.role !== "moderator"))) {
      fail("failed-precondition", "already-in-club", "Du bist bereits in einem Club-Team.");
    }
    if (club.staffUids.length >= club.seats) {
      fail("resource-exhausted", "club-full", `Alle ${club.seats} Plätze sind belegt.`);
    }

    tx.update(clubRef, { staffUids: FieldValue.arrayUnion(uid), moderatorUids: FieldValue.arrayRemove(uid), updatedAt: now() });
    tx.set(membershipRef, { clubId: clubRef.id, clubName: club.name, role: "staff", status: "active", updatedAt: now() });
    if (memberSnap.exists) {
      tx.update(memberRef, { role: "staff", status: "active" });
    } else {
      tx.create(memberRef, { uid, role: "staff", status: "active", displayName, joinedAt: now() });
    }
    tx.delete(inviteRef);
    return { clubRef, club, invite, previousStatus: existing?.status, previousRole: existing?.role };
  });

  if (joined.club.expiresAt) {
    try {
      await grantSeat(joined.clubRef, uid, joined.club.expiresAt.toMillis());
    } catch {
      await undoStaffJoin(uid, joined.clubRef, inviteRef, joined.invite, joined.previousStatus, joined.previousRole);
      fail("unavailable", "store-unavailable", "Der Team-Platz konnte gerade nicht freigeschaltet werden. Bitte versuche es gleich noch einmal.");
    }
  }
  return { clubId: joined.clubRef.id, clubName: joined.club.name, role: "staff", status: "active" };
}

/** Macht einen Team-Beitritt rückgängig, wenn RevenueCat nicht erreichbar war. Die Einladung bleibt gültig. */
async function undoStaffJoin(
  uid: string,
  clubRef: DocumentReference,
  inviteRef: DocumentReference,
  invite: InviteDoc,
  previousStatus: MembershipDoc["status"] | undefined,
  previousRole: MembershipDoc["role"] | undefined,
): Promise<void> {
  const membershipRef = db().doc(`memberships/${uid}`);
  const memberRef = clubRef.collection("members").doc(uid);
  await db().runTransaction(async (tx) => {
    tx.update(clubRef, { staffUids: FieldValue.arrayRemove(uid), updatedAt: now() });
    if (previousStatus) {
      // War vorher Mitglied oder Moderator: alten Stand wiederherstellen
      const role = previousRole === "moderator" ? "moderator" : "member";
      if (role === "moderator") tx.update(clubRef, { moderatorUids: FieldValue.arrayUnion(uid) });
      tx.update(membershipRef, { role, status: previousStatus, updatedAt: now() });
      tx.update(memberRef, { role, status: previousStatus });
    } else {
      tx.delete(membershipRef);
      tx.delete(memberRef);
    }
    tx.set(inviteRef, { ...invite, createdAt: now() });
  });
}

async function requestMembership(uid: string, displayName: string, clubRef: DocumentReference, code: string) {
  const membershipRef = db().doc(`memberships/${uid}`);
  const clubName = await db().runTransaction(async (tx) => {
    const [clubSnap, membershipSnap] = await tx.getAll(clubRef, membershipRef);
    const club = clubSnap.data() as ClubDoc | undefined;
    if (!club || club.memberCode !== code) fail("not-found", "invalid-code", "Dieser Code ist ungültig.");
    requireActiveClub(club);
    if (membershipSnap.exists) fail("failed-precondition", "already-in-club", "Du bist bereits in einem Club.");

    tx.create(membershipRef, { clubId: clubRef.id, clubName: club.name, role: "member", status: "pending", updatedAt: now() });
    tx.create(clubRef.collection("members").doc(uid), {
      uid,
      role: "member",
      status: "pending",
      displayName,
      joinedAt: now(),
    });
    return club.name;
  });
  return { clubId: clubRef.id, clubName, role: "member", status: "pending" };
}

/** Club verlassen. Offene Reservierungen werden storniert, ein Team-Platz wird frei. */
export const leaveClub = onCall(storeCallOptions, async (request) => {
  const uid = requireUid(request);
  const membership = await getMembership(uid);
  if (!membership) fail("failed-precondition", "not-in-club", "Du bist in keinem Club.");
  if (membership.role === "owner") {
    fail("failed-precondition", "owner-cannot-leave", "Als Inhaber kannst du den Club nicht verlassen.");
  }

  const clubRef = db().doc(`clubs/${membership.clubId}`);
  await removeFromClub(clubRef, uid);
  if (membership.role === "staff") await revokeSeat(clubRef, uid);
  return {};
});

/** Team-Platz entziehen. Die Person bleibt normales Mitglied. Nur für den Inhaber. */
export const removeStaff = onCall(storeCallOptions, async (request) => {
  const { uid, clubRef } = await requireOwner(requireUid(request));
  const target = request.data?.uid;
  if (!isId(target) || target === uid) fail("invalid-argument", "invalid-input", "Ungültiges Team-Mitglied.");

  await db().runTransaction(async (tx) => {
    const membershipRef = db().doc(`memberships/${target}`);
    const memberRef = clubRef.collection("members").doc(target);
    const [clubSnap, membershipSnap] = await tx.getAll(clubRef, membershipRef);
    const club = clubSnap.data() as ClubDoc;
    if (!club.staffUids.includes(target) || !membershipSnap.exists) {
      fail("not-found", "not-found", "Diese Person ist nicht im Team.");
    }
    tx.update(clubRef, { staffUids: FieldValue.arrayRemove(target), updatedAt: now() });
    tx.update(membershipRef, { role: "member", updatedAt: now() });
    tx.update(memberRef, { role: "member" });
  });
  await revokeSeat(clubRef, target);
  return {};
});

/** Mitglied nach Ausweiskontrolle freigeben oder Profil (auch des Teams) eintragen. Nur für das Team. */
export const approveMember = onCall(callOptions, async (request) => {
  const { uid, club, clubRef } = await requireStaff(requireUid(request));
  requireActiveClub(club);

  const target = request.data?.uid;
  const firstName = requiredText(request.data?.firstName, 1, 60);
  const lastName = requiredText(request.data?.lastName, 1, 60);
  const birthDate = request.data?.birthDate;
  if (!isId(target) || !firstName || !lastName || !isValidDate(birthDate)) {
    fail("invalid-argument", "invalid-input", "Bitte Vorname, Nachname und Geburtsdatum vollständig angeben.");
  }
  if (ageOn(birthDate, berlinDate(new Date())) < MIN_AGE) {
    fail("failed-precondition", "under-18", "Mitglieder müssen mindestens 18 Jahre alt sein.");
  }

  await db().runTransaction(async (tx) => {
    const memberRef = clubRef.collection("members").doc(target);
    const membershipRef = db().doc(`memberships/${target}`);
    const [memberSnap, membershipSnap] = await tx.getAll(memberRef, membershipRef);
    if (!memberSnap.exists || membershipSnap.get("clubId") !== clubRef.id) {
      fail("not-found", "not-found", "Diese Person hat keinen Antrag bei eurem Club.");
    }
    tx.update(memberRef, {
      firstName,
      lastName,
      birthDate,
      status: "active",
      approvedAt: now(),
      approvedBy: uid,
    });
    tx.update(membershipRef, { status: "active", updatedAt: now() });
  });
  return {};
});

/** Mitglied oder Moderator entfernen (kein Team-Mitglied, dafür removeStaff). Nur für das Team. */
export const removeMember = onCall(callOptions, async (request) => {
  const { clubRef } = await requireStaff(requireUid(request));
  const target = request.data?.uid;
  if (!isId(target)) fail("invalid-argument", "invalid-input", "Ungültiges Mitglied.");

  const memberSnap = await clubRef.collection("members").doc(target).get();
  const member = memberSnap.data() as MemberDoc | undefined;
  if (!member) fail("not-found", "not-found", "Diese Person ist kein Mitglied.");
  if (member.role !== "member" && member.role !== "moderator") {
    fail("failed-precondition", "not-owner", "Team-Mitglieder entfernt der Inhaber über die Team-Verwaltung.");
  }
  await removeFromClub(clubRef, target);
  return {};
});

/** Neuer Mitglieder-Code, z. B. wenn der alte die Runde gemacht hat. Nur für das Team. */
export const rotateMemberCode = onCall(callOptions, async (request) => {
  const { clubRef, club } = await requireStaff(requireUid(request));
  requireActiveClub(club);
  const memberCode = generateCode();
  await clubRef.update({ memberCode, updatedAt: now() });
  return { memberCode };
});

/** Entfernt eine Person aus dem Club und storniert ihre offenen Reservierungen. */
export async function removeFromClub(clubRef: DocumentReference, uid: string): Promise<void> {
  await db().runTransaction(async (tx) => {
    const openReservations = await openReservationRefs(tx, clubRef, uid);
    tx.update(clubRef, { staffUids: FieldValue.arrayRemove(uid), moderatorUids: FieldValue.arrayRemove(uid), updatedAt: now() });
    tx.delete(db().doc(`memberships/${uid}`));
    tx.delete(clubRef.collection("members").doc(uid));
    for (const ref of openReservations) {
      tx.update(ref, { status: "cancelled", updatedAt: now() });
    }
  });
}
