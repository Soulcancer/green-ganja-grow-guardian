import { FieldValue, Timestamp } from "firebase-admin/firestore";
import * as logger from "firebase-functions/logger";
import { onDocumentCreated } from "firebase-functions/v2/firestore";
import { onSchedule } from "firebase-functions/v2/scheduler";
import { DAY_MS, RC_SECRET_KEY, REGION } from "./config";
import { db } from "./firebase";
import { dayIdOf, dayStats, historyExpireAt, isDayId, statsExpireAt, statsKey, wantsStats } from "./rules/history";
import { cachedTier } from "./tier";

/**
 * Neuer Verlaufstag einer Box: Löschfrist nach der Stufe des Nutzers setzen
 * (Freemium 7 Tage, Premium 365 Tage, Social Club unbegrenzt). Die App darf `expireAt` nicht schreiben.
 * Mit `retry`, damit ein kurzer RevenueCat-Ausfall keine falsche Frist hinterlässt.
 */
export const onHistoryDayCreated = onDocumentCreated(
  { document: "users/{uid}/growBoxes/{boxId}/history/{day}", region: REGION, secrets: [RC_SECRET_KEY], retry: true },
  async (event) => {
    const snap = event.data;
    if (!snap) return;
    const { uid, day } = event.params;
    if (!isDayId(day)) {
      await snap.ref.delete();
      return;
    }
    const tier = await cachedTier(uid);
    const expireAt = historyExpireAt(day, tier);
    await snap.ref.set({ expireAt: expireAt === null ? FieldValue.delete() : Timestamp.fromMillis(expireAt) }, { merge: true });
  },
);

/**
 * Jede Nacht: Tagesstatistik (Min, Mittel, Max je Messgröße) des Vortags in `stats/{Jahr}` schreiben.
 * Die 30-Tage- und Jahresansicht der App lädt so ein Dokument pro Jahr statt hunderter Tage.
 */
export const nightlyGrowStats = onSchedule(
  { schedule: "every day 03:30", timeZone: "Europe/Berlin", region: REGION },
  async () => {
    const day = dayIdOf(Date.now() - DAY_MS);
    const year = Number(day.slice(0, 4));
    const docs = (await db().collectionGroup("history").where("day", "==", day).get()).docs;
    let written = 0;
    for (const doc of docs) {
      const expireAt = (doc.get("expireAt") as Timestamp | undefined)?.toMillis() ?? null;
      if (!wantsStats(day, expireAt)) continue;
      const boxRef = doc.ref.parent.parent;
      if (!boxRef) continue;
      const stats = dayStats(doc.get("s"));
      if (Object.keys(stats).length === 0) continue;
      const tierForExpiry = expireAt === null ? "social_club" : "premium";
      const statsExpire = statsExpireAt(year, tierForExpiry);
      try {
        await boxRef.collection("stats").doc(String(year)).set(
          {
            d: { [statsKey(day)]: stats },
            ...(statsExpire === null ? {} : { expireAt: Timestamp.fromMillis(statsExpire) }),
          },
          { merge: true },
        );
        written++;
      } catch (error) {
        logger.error("Tagesstatistik fehlgeschlagen", { path: doc.ref.path, error });
      }
    }
    logger.info("Tagesstatistik fertig", { day, days: docs.length, written });
  },
);
