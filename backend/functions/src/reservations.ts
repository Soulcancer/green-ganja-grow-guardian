import {
  FieldValue,
  Timestamp,
  type DocumentReference,
  type DocumentSnapshot,
  type QuerySnapshot,
} from "firebase-admin/firestore";
import { onCall } from "firebase-functions/v2/https";
import { requireActiveClub, requireMember, requireStaff, requireUid } from "./access";
import { RESERVATION_MAX_DAYS_AHEAD, RESERVATION_RETENTION_DAYS, callOptions } from "./config";
import { fail } from "./errors";
import { db } from "./firebase";
import { OPEN_RESERVATION_STATUSES, type DispensationDoc, type MemberDoc, type ReservationDoc } from "./model";
import { csvCell, formatDecimal, isId, optionalText } from "./rules/input";
import { bookingsWithout } from "./rules/member";
import {
  DAILY_LIMIT_G,
  YOUNG_ADULT_MAX_THC_PERCENT,
  addDays,
  berlinDate,
  checkAmount,
  isThcAllowed,
  isValidDate,
  isValidGrams,
  monthOf,
  type AmountCheck,
  type Booking,
} from "./rules/kcang";

const now = () => FieldValue.serverTimestamp();
const dayStart = (date: string) => Timestamp.fromDate(new Date(`${date}T00:00:00Z`));

/** Freigegebenes Mitglied mit vollständigem Profil, gelesen in einer Transaktion. */
function requireProfile(snap: DocumentSnapshot): Required<Pick<MemberDoc, "firstName" | "lastName" | "birthDate">> {
  const member = snap.data() as MemberDoc | undefined;
  if (!member || member.status !== "active") {
    fail("failed-precondition", "member-not-active", "Diese Mitgliedschaft ist noch nicht freigegeben.");
  }
  if (!member.firstName || !member.lastName || !member.birthDate) {
    fail("failed-precondition", "profile-incomplete", "Das Club-Team muss zuerst Name und Geburtsdatum nach der Ausweiskontrolle eintragen.");
  }
  return { firstName: member.firstName, lastName: member.lastName, birthDate: member.birthDate };
}

function failLimit(check: Exclude<AmountCheck, { ok: true }>): never {
  const left = formatDecimal(check.remainingG);
  switch (check.reason) {
    case "under-18":
      fail("failed-precondition", "under-18", "Eine Abgabe ist erst ab 18 Jahren erlaubt.");
    case "daily-limit":
      fail("resource-exhausted", "daily-limit", `Pro Tag sind höchstens ${DAILY_LIMIT_G} g erlaubt. An diesem Tag sind noch ${left} g möglich.`, {
        remainingG: check.remainingG,
      });
    case "monthly-limit":
      fail("resource-exhausted", "monthly-limit", `Das Monatslimit ist erreicht. In diesem Monat sind noch ${left} g möglich.`, {
        remainingG: check.remainingG,
      });
    default:
      fail("invalid-argument", "invalid-input", `Bitte eine Menge zwischen 0,1 und ${DAILY_LIMIT_G} g angeben.`);
  }
}

const bookingsFrom = (snap: QuerySnapshot, dateField: "date" | "pickupDate"): Booking[] =>
  snap.docs.map((doc) => ({ date: doc.get(dateField) as string, grams: doc.get("grams") as number }));

/** Mitglied fragt eine Abgabemenge zur Abholung im Club an. Sichtbar nur für das Team und das Mitglied. */
export const requestReservation = onCall(callOptions, async (request) => {
  const uid = requireUid(request);
  const { clubRef, club, membership } = await requireMember(uid);
  requireActiveClub(club);
  if (membership.status !== "active") {
    fail("failed-precondition", "member-not-active", "Deine Mitgliedschaft ist noch nicht freigegeben.");
  }

  const grams = request.data?.grams;
  const pickupDate = request.data?.pickupDate;
  const strain = optionalText(request.data?.strain, 60);
  const note = optionalText(request.data?.note, 280);
  const today = berlinDate(new Date());
  if (!isValidGrams(grams)) {
    fail("invalid-argument", "invalid-input", `Bitte eine Menge zwischen 0,1 und ${DAILY_LIMIT_G} g angeben.`);
  }
  if (!isValidDate(pickupDate) || pickupDate < today || pickupDate > addDays(today, RESERVATION_MAX_DAYS_AHEAD)) {
    fail("invalid-argument", "invalid-input", `Bitte einen Abholtag zwischen heute und in ${RESERVATION_MAX_DAYS_AHEAD} Tagen wählen.`);
  }
  if (strain === null || note === null) {
    fail("invalid-argument", "invalid-input", "Die Sorte darf höchstens 60, die Notiz höchstens 280 Zeichen haben.");
  }

  const reservationRef = clubRef.collection("reservations").doc();
  const remainingMonthG = await db().runTransaction(async (tx) => {
    const profile = requireProfile(await tx.get(clubRef.collection("members").doc(uid)));
    const reservations = await tx.get(clubRef.collection("reservations").where("memberUid", "==", uid));
    const dispensations = await tx.get(
      clubRef.collection("dispensations").where("memberUid", "==", uid).where("month", "==", monthOf(pickupDate)),
    );

    // Gezählt wird, was schon ausgegeben wurde, plus alles, was noch offen reserviert ist.
    const openReservations = reservations.docs.filter((doc) => OPEN_RESERVATION_STATUSES.includes(doc.get("status")));
    const existing = [
      ...bookingsFrom(dispensations, "date"),
      ...openReservations.map((doc) => ({ date: doc.get("pickupDate") as string, grams: doc.get("grams") as number })),
    ];
    const check = checkAmount(profile.birthDate, pickupDate, grams, existing);
    if (!check.ok) failLimit(check);

    const reservation: ReservationDoc = {
      memberUid: uid,
      memberName: `${profile.firstName} ${profile.lastName}`,
      grams,
      strain: strain ?? null,
      pickupDate,
      note: note ?? null,
      status: "requested",
    };
    tx.create(reservationRef, {
      ...reservation,
      createdAt: now(),
      updatedAt: now(),
      expireAt: dayStart(addDays(pickupDate, RESERVATION_RETENTION_DAYS)),
    });
    return check.remainingMonthG;
  });

  return { reservationId: reservationRef.id, remainingMonthG };
});

/**
 * Eigene Reservierung ändern (Menge, Abholtag, Sorte, Notiz), solange das Team sie noch nicht bestätigt hat.
 * Die Grenzen werden ohne die alte Fassung neu geprüft.
 */
export const updateReservation = onCall(callOptions, async (request) => {
  const uid = requireUid(request);
  const { clubRef, club } = await requireMember(uid);
  requireActiveClub(club);
  const id = request.data?.reservationId;
  const grams = request.data?.grams;
  const pickupDate = request.data?.pickupDate;
  const strain = optionalText(request.data?.strain, 60);
  const note = optionalText(request.data?.note, 280);
  const today = berlinDate(new Date());
  if (!isId(id)) fail("invalid-argument", "invalid-input", "Ungültige Reservierung.");
  if (!isValidGrams(grams)) {
    fail("invalid-argument", "invalid-input", `Bitte eine Menge zwischen 0,1 und ${DAILY_LIMIT_G} g angeben.`);
  }
  if (!isValidDate(pickupDate) || pickupDate < today || pickupDate > addDays(today, RESERVATION_MAX_DAYS_AHEAD)) {
    fail("invalid-argument", "invalid-input", `Bitte einen Abholtag zwischen heute und in ${RESERVATION_MAX_DAYS_AHEAD} Tagen wählen.`);
  }
  if (strain === null || note === null) {
    fail("invalid-argument", "invalid-input", "Die Sorte darf höchstens 60, die Notiz höchstens 280 Zeichen haben.");
  }

  const remainingMonthG = await db().runTransaction(async (tx) => {
    const ref = clubRef.collection("reservations").doc(id);
    const current = (await tx.get(ref)).data() as ReservationDoc | undefined;
    if (!current || current.memberUid !== uid) fail("not-found", "not-found", "Reservierung nicht gefunden.");
    if (current.status !== "requested") {
      fail("failed-precondition", "wrong-status", "Bestätigte Reservierungen kannst du nicht mehr ändern. Storniere sie und frag neu an.");
    }
    const profile = requireProfile(await tx.get(clubRef.collection("members").doc(uid)));
    const reservations = await tx.get(clubRef.collection("reservations").where("memberUid", "==", uid));
    const dispensations = await tx.get(
      clubRef.collection("dispensations").where("memberUid", "==", uid).where("month", "==", monthOf(pickupDate)),
    );
    const others = bookingsWithout(
      reservations.docs.filter((doc) => OPEN_RESERVATION_STATUSES.includes(doc.get("status"))).map((doc) => ({
        id: doc.id, date: doc.get("pickupDate") as string, grams: doc.get("grams") as number,
      })),
      id,
    );
    const check = checkAmount(profile.birthDate, pickupDate, grams, [...bookingsFrom(dispensations, "date"), ...others]);
    if (!check.ok) failLimit(check);
    tx.update(ref, {
      grams,
      pickupDate,
      strain: strain ?? null,
      note: note ?? null,
      updatedAt: now(),
      expireAt: dayStart(addDays(pickupDate, RESERVATION_RETENTION_DAYS)),
    });
    return check.remainingMonthG;
  });
  return { remainingMonthG };
});

/** Eigene Reservierung stornieren, solange sie noch offen ist. */
export const cancelReservation = onCall(callOptions, async (request) => {
  const uid = requireUid(request);
  const { clubRef } = await requireMember(uid);
  const id = request.data?.reservationId;
  if (!isId(id)) fail("invalid-argument", "invalid-input", "Ungültige Reservierung.");

  await db().runTransaction(async (tx) => {
    const ref = clubRef.collection("reservations").doc(id);
    const reservation = (await tx.get(ref)).data() as ReservationDoc | undefined;
    if (!reservation || reservation.memberUid !== uid) fail("not-found", "not-found", "Reservierung nicht gefunden.");
    if (!OPEN_RESERVATION_STATUSES.includes(reservation.status)) {
      fail("failed-precondition", "wrong-status", "Diese Reservierung kann nicht mehr storniert werden.");
    }
    tx.update(ref, { status: "cancelled", updatedAt: now() });
  });
  return {};
});

/** Team bestätigt oder lehnt eine Reservierung ab. */
export const decideReservation = onCall(callOptions, async (request) => {
  const { uid, clubRef, club } = await requireStaff(requireUid(request));
  requireActiveClub(club);
  const id = request.data?.reservationId;
  const decision = request.data?.decision;
  const reason = optionalText(request.data?.reason, 200);
  if (!isId(id) || (decision !== "confirm" && decision !== "decline") || reason === null) {
    fail("invalid-argument", "invalid-input", "Ungültige Entscheidung.");
  }

  await db().runTransaction(async (tx) => {
    const ref = clubRef.collection("reservations").doc(id);
    const reservation = (await tx.get(ref)).data() as ReservationDoc | undefined;
    if (!reservation) fail("not-found", "not-found", "Reservierung nicht gefunden.");
    const allowed = decision === "confirm" ? reservation.status === "requested" : OPEN_RESERVATION_STATUSES.includes(reservation.status);
    if (!allowed) fail("failed-precondition", "wrong-status", "Diese Reservierung ist nicht mehr offen.");
    tx.update(ref, {
      status: decision === "confirm" ? "confirmed" : "declined",
      declineReason: decision === "decline" ? (reason ?? null) : null,
      decidedBy: uid,
      decidedAt: now(),
      updatedAt: now(),
    });
  });
  return {};
});

/**
 * Ausgabe im Club dokumentieren (§ 26 KCanG), mit oder ohne vorherige Reservierung.
 * Prüft Tages- und Monatsgrenze anhand der tatsächlichen Abgaben und das THC-Limit unter 21.
 */
export const recordHandout = onCall(callOptions, async (request) => {
  const { uid, clubRef, club } = await requireStaff(requireUid(request));
  requireActiveClub(club);

  const reservationId = request.data?.reservationId;
  const memberUidInput = request.data?.memberUid;
  const grams = request.data?.grams;
  const thcPercent = request.data?.thcPercent;
  const strainInput = optionalText(request.data?.strain, 60);
  if (reservationId !== undefined && reservationId !== null && !isId(reservationId)) {
    fail("invalid-argument", "invalid-input", "Ungültige Reservierung.");
  }
  if (!reservationId && !isId(memberUidInput)) fail("invalid-argument", "invalid-input", "Bitte ein Mitglied angeben.");
  if (!isValidGrams(grams)) {
    fail("invalid-argument", "invalid-input", `Bitte eine Menge zwischen 0,1 und ${DAILY_LIMIT_G} g angeben.`);
  }
  if (typeof thcPercent !== "number" || !Number.isFinite(thcPercent) || thcPercent < 0 || thcPercent > 100) {
    fail("invalid-argument", "invalid-input", "Bitte den THC-Gehalt in Prozent angeben.");
  }
  if (strainInput === null) fail("invalid-argument", "invalid-input", "Die Sorte darf höchstens 60 Zeichen haben.");

  const today = berlinDate(new Date());
  const dispensationRef = clubRef.collection("dispensations").doc();
  const remainingMonthG = await db().runTransaction(async (tx) => {
    let reservationRef: DocumentReference | null = null;
    let reservation: ReservationDoc | undefined;
    if (reservationId) {
      reservationRef = clubRef.collection("reservations").doc(reservationId);
      reservation = (await tx.get(reservationRef)).data() as ReservationDoc | undefined;
      if (!reservation) fail("not-found", "not-found", "Reservierung nicht gefunden.");
      if (!OPEN_RESERVATION_STATUSES.includes(reservation.status)) {
        fail("failed-precondition", "wrong-status", "Diese Reservierung ist nicht mehr offen.");
      }
    }
    const memberUid = reservation?.memberUid ?? (memberUidInput as string);
    const profile = requireProfile(await tx.get(clubRef.collection("members").doc(memberUid)));
    const dispensations = await tx.get(
      clubRef.collection("dispensations").where("memberUid", "==", memberUid).where("month", "==", monthOf(today)),
    );

    // Rechtlich zählt die tatsächliche Abgabe, Reservierungen sind nur Vormerkungen.
    const check = checkAmount(profile.birthDate, today, grams, bookingsFrom(dispensations, "date"));
    if (!check.ok) failLimit(check);
    if (!isThcAllowed(profile.birthDate, today, thcPercent)) {
      fail("failed-precondition", "thc-limit", `Unter 21 Jahren sind höchstens ${YOUNG_ADULT_MAX_THC_PERCENT} % THC erlaubt.`);
    }

    const year = Number(today.slice(0, 4));
    const dispensation: DispensationDoc = {
      memberUid,
      firstName: profile.firstName,
      lastName: profile.lastName,
      birthYear: Number(profile.birthDate.slice(0, 4)),
      grams,
      thcPercent: Math.round(thcPercent * 10) / 10,
      strain: strainInput ?? reservation?.strain ?? null,
      date: today,
      month: monthOf(today),
      handedOutBy: uid,
      reservationId: reservationRef?.id ?? null,
    };
    tx.create(dispensationRef, {
      ...dispensation,
      createdAt: now(),
      // Fünf Jahre Aufbewahrung, gerechnet ab Ende des Abgabejahres; danach löscht die TTL-Regel.
      expireAt: dayStart(`${year + 6}-01-01`),
    });
    if (reservationRef) {
      tx.update(reservationRef, { status: "handedOut", dispensationId: dispensationRef.id, updatedAt: now() });
    }
    return check.remainingMonthG;
  });

  return { dispensationId: dispensationRef.id, remainingMonthG };
});

/** Abgaben eines Jahres als CSV für die Behörde (§ 26 Abs. 2 KCanG). Geht auch bei pausiertem Abo. */
export const exportDispensations = onCall(callOptions, async (request) => {
  const { clubRef } = await requireStaff(requireUid(request));
  const year = request.data?.year;
  if (!Number.isInteger(year) || year < 2024 || year > 2100) fail("invalid-argument", "invalid-input", "Ungültiges Jahr.");

  const snap = await clubRef
    .collection("dispensations")
    .where("date", ">=", `${year}-01-01`)
    .where("date", "<=", `${year}-12-31`)
    .orderBy("date")
    .get();

  const header = ["Datum", "Nachname", "Vorname", "Geburtsjahr", "Menge (g)", "THC (%)", "Sorte"];
  const rows = snap.docs.map((doc) => {
    const d = doc.data() as DispensationDoc;
    return [d.date, d.lastName, d.firstName, String(d.birthYear), formatDecimal(d.grams), formatDecimal(d.thcPercent), d.strain ?? ""];
  });
  const csv = [header, ...rows].map((row) => row.map(csvCell).join(";")).join("\r\n");
  return { csv, count: rows.length };
});
