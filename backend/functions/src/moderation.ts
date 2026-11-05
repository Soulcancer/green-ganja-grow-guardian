import { FieldValue, Timestamp, type DocumentReference, type Transaction } from "firebase-admin/firestore";
import { onCall } from "firebase-functions/v2/https";
import { requireMember, requireStaff, requireUid, type ClubContext } from "./access";
import { callOptions, DAY_MS } from "./config";
import { fail } from "./errors";
import { db } from "./firebase";
import type { MemberDoc, ModLogDoc } from "./model";
import { isId } from "./rules/input";
import { LISTING_VALID_DAYS } from "./rules/market";
import { visibleName } from "./rules/member";
import {
  ANNOUNCEMENT_VALID_DAYS,
  announcementProblem,
  appointProblem,
  blockedUntil,
  canModerate,
  MAX_ANNOUNCEMENTS,
  mayActOn,
  mayRemoveListing,
  MOD_LOG_RETENTION_DAYS,
  parseAnnouncement,
  parseBlockDays,
  parseDecision,
  parseReason,
  roleIn,
  type ModAction,
  type ModRole,
} from "./rules/moderation";

const now = () => FieldValue.serverTimestamp();

type Member = MemberDoc & { publicName?: string | null };

const nameOf = (member: Member | undefined): string =>
  member ? visibleName(member.publicName, member.firstName, member.lastName, member.displayName) : "Mitglied";

export interface ModerationContext extends ClubContext {
  role: ModRole;
  actorName: string;
}

/** Aktives Mitglied mit Moderationsrechten: Moderator, Team oder Inhaber. */
export async function requireModeration(uid: string): Promise<ModerationContext> {
  const context = await requireMember(uid);
  const role = roleIn(uid, context.club.ownerUid, context.club.staffUids, context.club.moderatorUids);
  if (!canModerate(role) || context.membership.status !== "active") {
    fail("permission-denied", "not-moderator", "Das dürfen nur Moderatoren und das Club-Team.");
  }
  const actor = (await context.clubRef.collection("members").doc(uid).get()).data() as Member | undefined;
  return { ...context, role, actorName: nameOf(actor) };
}

interface LogInput {
  action: ModAction;
  byUid: string;
  byName: string;
  targetUid?: string | null;
  targetName?: string | null;
  listingId?: string | null;
  listingTitle?: string | null;
  reason?: string | null;
  untilMs?: number | null;
}

/** Protokolleintrag, ein Jahr aufbewahrt. Die betroffene Person kann ihre Einträge lesen (Firestore-Regeln). */
function writeLog(tx: Transaction, clubRef: DocumentReference, input: LogInput): void {
  const entry: Omit<ModLogDoc, "createdAt"> = {
    action: input.action,
    byUid: input.byUid,
    byName: input.byName,
    targetUid: input.targetUid ?? null,
    targetName: input.targetName ?? null,
    listingId: input.listingId ?? null,
    listingTitle: input.listingTitle ?? null,
    reason: input.reason ?? null,
    until: input.untilMs ? Timestamp.fromMillis(input.untilMs) : null,
    expireAt: Timestamp.fromMillis(Date.now() + MOD_LOG_RETENTION_DAYS * DAY_MS),
  };
  tx.create(clubRef.collection("modLog").doc(), { ...entry, createdAt: now() });
}

function requireReason(value: unknown): string {
  const reason = parseReason(value);
  if (!reason) fail("invalid-argument", "invalid-input", "Bitte einen Grund mit 3 bis 300 Zeichen angeben. Die betroffene Person sieht ihn.");
  return reason;
}

/** Zielperson lesen und prüfen, dass die eigene Rolle über ihrer steht. */
async function targetMember(tx: Transaction, context: ModerationContext, target: unknown) {
  if (!isId(target)) fail("invalid-argument", "invalid-input", "Ungültiges Mitglied.");
  const ref = context.clubRef.collection("members").doc(target);
  const member = (await tx.get(ref)).data() as Member | undefined;
  if (!member || member.status !== "active") fail("not-found", "not-found", "Diese Person ist kein aktives Mitglied.");
  if (!mayActOn(context.uid, context.role, target, member.role)) {
    fail("permission-denied", "not-moderator", "Moderatoren und Team-Mitglieder kann nur verwarnen oder sperren, wer im Club darüber steht.");
  }
  return { ref, member, uid: target };
}

/** Moderator ernennen oder abberufen. Nur Inhaber und Team; Moderatoren belegen keinen Team-Platz. */
export const setModerator = onCall(callOptions, async (request) => {
  const { uid, clubRef } = await requireStaff(requireUid(request));
  const target = request.data?.uid;
  const appoint = request.data?.moderator;
  if (!isId(target) || typeof appoint !== "boolean" || target === uid) {
    fail("invalid-argument", "invalid-input", "Ungültiges Mitglied.");
  }
  await db().runTransaction(async (tx) => {
    const memberRef = clubRef.collection("members").doc(target);
    const membershipRef = db().doc(`memberships/${target}`);
    const actorRef = clubRef.collection("members").doc(uid);
    const [clubSnap, memberSnap, membershipSnap, actorSnap] = await tx.getAll(clubRef, memberRef, membershipRef, actorRef);
    const member = memberSnap.data() as Member | undefined;
    if (!member || membershipSnap.get("clubId") !== clubRef.id) {
      fail("not-found", "not-found", "Diese Person ist kein Mitglied eures Clubs.");
    }
    const moderators = (clubSnap.get("moderatorUids") as string[] | undefined) ?? [];
    const problem = appointProblem(appoint, member.role, member.status, moderators.length);
    if (problem) fail("failed-precondition", problem.reason, problem.message);

    const role = appoint ? "moderator" : "member";
    tx.update(clubRef, {
      moderatorUids: appoint ? FieldValue.arrayUnion(target) : FieldValue.arrayRemove(target),
      updatedAt: now(),
    });
    tx.update(memberRef, { role });
    tx.update(membershipRef, { role, updatedAt: now() });
    writeLog(tx, clubRef, {
      action: appoint ? "appoint" : "dismiss",
      byUid: uid,
      byName: nameOf(actorSnap.data() as Member | undefined),
      targetUid: target,
      targetName: nameOf(member),
    });
  });
  return { ok: true };
});

const millis = (value: unknown): number | null => (value instanceof Timestamp ? value.toMillis() : null);

/**
 * Alles für den Moderations-Bereich in einem Aufruf: aktive Mitglieder (nur Anzeigename, Rolle, Verwarnungen,
 * Sperre), gemeldete Inserate mit Gründen, Ankündigungen und die letzten 50 Protokolleinträge.
 */
export const moderationOverview = onCall(callOptions, async (request) => {
  const context = await requireModeration(requireUid(request));
  const { clubRef } = context;
  const [members, reported, announcements, log] = await Promise.all([
    clubRef.collection("members").where("status", "==", "active").get(),
    clubRef.collection("listings").where("status", "==", "reported").limit(50).get(),
    clubRef.collection("announcements").orderBy("createdAt", "desc").limit(MAX_ANNOUNCEMENTS).get(),
    clubRef.collection("modLog").orderBy("createdAt", "desc").limit(50).get(),
  ]);
  const reports = await Promise.all(reported.docs.map(async (d) => {
    const reasons = await d.ref.collection("reports").orderBy("createdAt", "desc").limit(20).get();
    return {
      listingId: d.id,
      kind: d.get("kind") as string,
      title: (d.get("kind") === "gear" ? d.get("title") : d.get("strain")) as string,
      sellerUid: d.get("sellerUid") as string,
      sellerName: d.get("sellerName") as string,
      reports: (d.get("reports") as number | undefined) ?? 0,
      reasons: reasons.docs.map((r) => r.get("reason") as string | null).filter((r): r is string => !!r),
      mayRemove: mayRemoveListing(context.role, d.get("kind") as string),
    };
  }));
  return {
    role: context.role,
    members: members.docs
      .map((d) => {
        const member = d.data() as Member & { warnings?: number };
        return {
          uid: d.id,
          name: nameOf(member),
          role: member.role,
          warnings: member.warnings ?? 0,
          blockedUntil: millis(member.marketBlockedUntil),
          blockReason: member.marketBlockReason ?? null,
          mayAct: mayActOn(context.uid, context.role, d.id, member.role),
        };
      })
      .sort((a, b) => a.name.localeCompare(b.name, "de")),
    reports,
    announcements: announcements.docs.filter((d) => (millis(d.get("expireAt")) ?? Infinity) > Date.now()).map((d) => ({
      id: d.id,
      title: d.get("title") as string,
      text: d.get("text") as string,
      pinned: d.get("pinned") === true,
      byName: d.get("byName") as string,
      at: millis(d.get("createdAt")),
    })),
    log: log.docs.map((d) => ({
      id: d.id,
      action: d.get("action") as string,
      byName: d.get("byName") as string,
      targetName: (d.get("targetName") as string | null) ?? null,
      listingTitle: (d.get("listingTitle") as string | null) ?? null,
      reason: (d.get("reason") as string | null) ?? null,
      until: millis(d.get("until")),
      at: millis(d.get("createdAt")),
    })),
  };
});

/** Verwarnung mit Grund. Zählt mit und erscheint beim Mitglied unter „Hinweise vom Club“. */
export const warnMember = onCall(callOptions, async (request) => {
  const context = await requireModeration(requireUid(request));
  const reason = requireReason(request.data?.reason);
  await db().runTransaction(async (tx) => {
    const target = await targetMember(tx, context, request.data?.uid);
    tx.update(target.ref, { warnings: FieldValue.increment(1) });
    writeLog(tx, context.clubRef, {
      action: "warn",
      byUid: context.uid,
      byName: context.actorName,
      targetUid: target.uid,
      targetName: nameOf(target.member),
      reason,
    });
  });
  return { ok: true };
});

/** Befristete Sperre im Marktplatz: keine neuen Inserate und keine Anfragen. Bestehende Inserate bleiben. */
export const blockMarket = onCall(callOptions, async (request) => {
  const context = await requireModeration(requireUid(request));
  const reason = requireReason(request.data?.reason);
  const days = parseBlockDays(request.data?.days);
  if (days === null) fail("invalid-argument", "invalid-input", "Bitte 1, 3, 7 oder 30 Tage wählen.");
  const until = blockedUntil(Date.now(), days);
  await db().runTransaction(async (tx) => {
    const target = await targetMember(tx, context, request.data?.uid);
    tx.update(target.ref, { marketBlockedUntil: Timestamp.fromMillis(until), marketBlockReason: reason });
    writeLog(tx, context.clubRef, {
      action: "block",
      byUid: context.uid,
      byName: context.actorName,
      targetUid: target.uid,
      targetName: nameOf(target.member),
      reason,
      untilMs: until,
    });
  });
  return { until };
});

/** Sperre vorzeitig aufheben. */
export const unblockMarket = onCall(callOptions, async (request) => {
  const context = await requireModeration(requireUid(request));
  await db().runTransaction(async (tx) => {
    const target = await targetMember(tx, context, request.data?.uid);
    tx.update(target.ref, { marketBlockedUntil: null, marketBlockReason: null });
    writeLog(tx, context.clubRef, {
      action: "unblock",
      byUid: context.uid,
      byName: context.actorName,
      targetUid: target.uid,
      targetName: nameOf(target.member),
    });
  });
  return { ok: true };
});

/**
 * Gemeldetes Inserat entscheiden: wieder freigeben (Meldungen werden gelöscht) oder entfernen.
 * Club-Angebote (Samen, Stecklinge) entfernt nur das Team.
 */
export const decideReport = onCall(callOptions, async (request) => {
  const context = await requireModeration(requireUid(request));
  const id = request.data?.listingId;
  const decision = parseDecision(request.data?.decision);
  if (!isId(id) || !decision) fail("invalid-argument", "invalid-input", "Ungültige Entscheidung.");
  // Grund beim Entfernen freiwillig, aber wenn angegeben, dann sinnvoll lang; die anbietende Person sieht ihn
  const raw = request.data?.reason;
  const reason = decision === "remove" && raw !== undefined && raw !== null && raw !== "" ? requireReason(raw) : null;

  await db().runTransaction(async (tx) => {
    const ref = context.clubRef.collection("listings").doc(id);
    const listing = await tx.get(ref);
    if (!listing.exists) fail("not-found", "not-found", "Dieses Inserat gibt es nicht mehr.");
    const kind = listing.get("kind") as string;
    const status = listing.get("status") as string;
    const title = (kind === "gear" ? listing.get("title") : listing.get("strain")) as string;

    if (decision === "restore") {
      if (status !== "reported") fail("failed-precondition", "wrong-status", "Dieses Inserat ist nicht gemeldet.");
      const reports = await tx.get(ref.collection("reports"));
      reports.docs.forEach((r) => tx.delete(r.ref));
      tx.update(ref, {
        status: "open",
        reports: 0,
        updatedAt: now(),
        expireAt: Timestamp.fromMillis(Date.now() + LISTING_VALID_DAYS * DAY_MS),
      });
    } else {
      if (status === "removed") fail("failed-precondition", "wrong-status", "Dieses Inserat ist schon entfernt.");
      if (!mayRemoveListing(context.role, kind)) {
        fail("permission-denied", "not-staff", "Club-Angebote für Samen und Stecklinge verwaltet das Team.");
      }
      tx.update(ref, { status: "removed", removedBy: context.uid, updatedAt: now() });
    }
    writeLog(tx, context.clubRef, {
      action: decision,
      byUid: context.uid,
      byName: context.actorName,
      targetUid: kind === "gear" ? (listing.get("sellerUid") as string) : null,
      targetName: kind === "gear" ? (listing.get("sellerName") as string) : null,
      listingId: id,
      listingTitle: title,
      reason,
    });
  });
  return { ok: true };
});

/** Ankündigung an alle Mitglieder, 30 Tage sichtbar. Höchstens 20 gleichzeitig, 3 davon angeheftet. */
export const postAnnouncement = onCall(callOptions, async (request) => {
  const context = await requireModeration(requireUid(request));
  const input = parseAnnouncement(request.data);
  if (typeof input === "string") fail("invalid-argument", "invalid-input", input);
  const ref = context.clubRef.collection("announcements").doc();
  await db().runTransaction(async (tx) => {
    const active = await tx.get(context.clubRef.collection("announcements").where("expireAt", ">", Timestamp.now()));
    const problem = announcementProblem(active.size, active.docs.filter((d) => d.get("pinned") === true).length, input.pinned);
    if (problem) fail("resource-exhausted", "announcement-limit", problem);
    tx.create(ref, {
      ...input,
      byUid: context.uid,
      byName: context.actorName,
      createdAt: now(),
      expireAt: Timestamp.fromMillis(Date.now() + ANNOUNCEMENT_VALID_DAYS * DAY_MS),
    });
    writeLog(tx, context.clubRef, { action: "announce", byUid: context.uid, byName: context.actorName, reason: input.title });
  });
  return { announcementId: ref.id };
});

export const deleteAnnouncement = onCall(callOptions, async (request) => {
  const context = await requireModeration(requireUid(request));
  const id = request.data?.announcementId;
  if (!isId(id)) fail("invalid-argument", "invalid-input", "Ungültige Ankündigung.");
  await db().runTransaction(async (tx) => {
    const ref = context.clubRef.collection("announcements").doc(id);
    const snap = await tx.get(ref);
    if (!snap.exists) fail("not-found", "not-found", "Diese Ankündigung gibt es nicht mehr.");
    tx.delete(ref);
    writeLog(tx, context.clubRef, {
      action: "unannounce",
      byUid: context.uid,
      byName: context.actorName,
      reason: snap.get("title") as string,
    });
  });
  return { ok: true };
});
