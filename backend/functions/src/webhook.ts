import * as logger from "firebase-functions/logger";
import { onRequest } from "firebase-functions/v2/https";
import { onSchedule } from "firebase-functions/v2/scheduler";
import { RC_SECRET_KEY, RC_WEBHOOK_SECRET, REGION } from "./config";
import { db } from "./firebase";
import { verifyRevenueCatSignature } from "./rules/signature";
import { reconcileClub } from "./seats";

interface RevenueCatEvent {
  id?: string;
  type?: string;
  app_user_id?: string;
  original_app_user_id?: string;
  aliases?: string[];
  transferred_from?: string[];
  transferred_to?: string[];
}

/**
 * Empfängt Ereignisse von RevenueCat (Kauf, Verlängerung, Ablauf, Erstattung, Übertragung …).
 * Statt jedes Ereignis einzeln auszuwerten, wird der betroffene Club komplett abgeglichen.
 */
export const revenuecatWebhook = onRequest(
  { region: REGION, secrets: [RC_SECRET_KEY, RC_WEBHOOK_SECRET], maxInstances: 5 },
  async (req, res) => {
    if (req.method !== "POST") {
      res.status(405).send("Method Not Allowed");
      return;
    }
    const signature = req.header("X-RevenueCat-Webhook-Signature");
    const valid = verifyRevenueCatSignature(signature, req.rawBody, RC_WEBHOOK_SECRET.value(), Math.floor(Date.now() / 1000));
    if (!valid) {
      logger.warn("Webhook mit ungültiger Signatur abgewiesen");
      res.status(401).send("Unauthorized");
      return;
    }

    let event: RevenueCatEvent;
    try {
      event = (JSON.parse(req.rawBody.toString("utf8")) as { event?: RevenueCatEvent }).event ?? {};
    } catch {
      res.status(400).send("Bad Request");
      return;
    }
    if (event.type === "TEST") {
      res.status(200).send("ok");
      return;
    }

    // Club-IDs sind die UIDs der Inhaber; für alle anderen Kunden passiert nichts.
    const ids = new Set(
      [
        event.app_user_id,
        event.original_app_user_id,
        ...(event.aliases ?? []),
        ...(event.transferred_from ?? []),
        ...(event.transferred_to ?? []),
      ].filter((id): id is string => typeof id === "string" && /^[A-Za-z0-9_-]{1,128}$/.test(id)),
    );

    try {
      for (const id of ids) await reconcileClub(id);
      res.status(200).send("ok");
    } catch (error) {
      logger.error("Webhook-Verarbeitung fehlgeschlagen, RevenueCat versucht es erneut", { eventId: event.id, error });
      res.status(500).send("retry");
    }
  },
);

/** Sicherheitsnetz, falls ein Webhook verloren geht: jede Nacht alle Clubs abgleichen. */
export const nightlyClubSync = onSchedule(
  { schedule: "every day 04:00", timeZone: "Europe/Berlin", region: REGION, secrets: [RC_SECRET_KEY] },
  async () => {
    const clubs = await db().collection("clubs").select().get();
    let failed = 0;
    for (const club of clubs.docs) {
      try {
        await reconcileClub(club.id);
      } catch (error) {
        failed++;
        logger.error("Nächtlicher Abgleich fehlgeschlagen", { clubId: club.id, error });
      }
    }
    logger.info("Nächtlicher Abgleich fertig", { clubs: clubs.size, failed });
  },
);
