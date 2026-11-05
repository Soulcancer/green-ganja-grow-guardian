import type { DocumentReference, Transaction } from "firebase-admin/firestore";
import type { CallableRequest } from "firebase-functions/v2/https";
import { fail } from "./errors";
import { db } from "./firebase";
import { OPEN_RESERVATION_STATUSES, type ClubDoc, type MembershipDoc, type ReservationStatus } from "./model";

/** Angemeldet, auch ohne bestätigte E-Mail (nur für Konto löschen). */
export function requireSignedIn(request: CallableRequest): string {
  const uid = request.auth?.uid;
  if (!uid) fail("unauthenticated", "not-signed-in", "Bitte melde dich zuerst an.");
  return uid;
}

/** Angemeldet mit bestätigter E-Mail-Adresse; das verlangen alle Functions außer dem Löschen des Kontos. */
export function requireUid(request: CallableRequest): string {
  const uid = requireSignedIn(request);
  if (request.auth?.token?.email_verified !== true) {
    fail("permission-denied", "email-not-verified", "Bitte bestätige zuerst deine E-Mail-Adresse.");
  }
  return uid;
}

export async function getMembership(uid: string): Promise<MembershipDoc | null> {
  const snap = await db().doc(`memberships/${uid}`).get();
  return snap.exists ? (snap.data() as MembershipDoc) : null;
}

export interface ClubContext {
  uid: string;
  membership: MembershipDoc;
  clubRef: DocumentReference;
  club: ClubDoc;
}

export async function requireMember(uid: string): Promise<ClubContext> {
  const membership = await getMembership(uid);
  if (!membership) fail("failed-precondition", "not-in-club", "Du bist in keinem Club.");
  const clubRef = db().doc(`clubs/${membership.clubId}`);
  const club = (await clubRef.get()).data() as ClubDoc | undefined;
  if (!club) fail("not-found", "not-in-club", "Diesen Club gibt es nicht mehr.");
  return { uid, membership, clubRef, club };
}

export async function requireStaff(uid: string): Promise<ClubContext> {
  const context = await requireMember(uid);
  if (context.membership.role === "member" || !context.club.staffUids.includes(uid)) {
    fail("permission-denied", "not-staff", "Das darf nur das Club-Team.");
  }
  return context;
}

export async function requireOwner(uid: string): Promise<ClubContext> {
  const context = await requireStaff(uid);
  if (context.club.ownerUid !== uid) fail("permission-denied", "not-owner", "Das darf nur der Club-Inhaber.");
  return context;
}

export function requireActiveClub(club: ClubDoc): void {
  if (!club.active) {
    fail("failed-precondition", "club-inactive", "Das Social-Club-Abo dieses Clubs ist nicht aktiv.");
  }
}

/** Offene Reservierungen eines Mitglieds, gelesen innerhalb einer Transaktion. */
export async function openReservationRefs(
  tx: Transaction,
  clubRef: DocumentReference,
  memberUid: string,
): Promise<DocumentReference[]> {
  const snap = await tx.get(clubRef.collection("reservations").where("memberUid", "==", memberUid));
  return snap.docs
    .filter((doc) => OPEN_RESERVATION_STATUSES.includes(doc.get("status") as ReservationStatus))
    .map((doc) => doc.ref);
}
