import { defineSecret, defineString } from "firebase-functions/params";

/** Frankfurt: Daten und Functions bleiben in der EU. */
export const REGION = "europe-west3";

/** Personen pro Social-Club-Abo, der Käufer zählt mit. Muss zu FarmerTier.SOCIAL_CLUB.features.seats passen. */
export const SEATS_PER_CLUB = 5;

/** Lookup-Key des Entitlements im RevenueCat-Dashboard. */
export const SOCIAL_CLUB_LOOKUP_KEY = "social_club_farmer";

export const DAY_MS = 24 * 60 * 60 * 1000;
export const TWO_HOURS_MS = 2 * 60 * 60 * 1000;

/** Team-Plätze laufen einen Tag länger als das Abo des Käufers, damit eine verspätete Verlängerung keine Lücke reißt. */
export const GRANT_BUFFER_MS = DAY_MS;

export const STAFF_INVITE_VALID_DAYS = 7;
export const RESERVATION_MAX_DAYS_AHEAD = 30;

/** § 26 KCanG: Aufzeichnungen fünf Jahre aufbewahren. */
export const DISPENSATION_RETENTION_YEARS = 5;

/** Reservierungen sind keine Pflichtaufzeichnung und werden nach der Abholung nicht lange gebraucht. */
export const RESERVATION_RETENTION_DAYS = 90;

/** Grow-Box-Limits je Stufe: siehe TIER_LIMITS in rules/grow.ts (muss zu FarmerTier.kt passen). */

/** Secret API Key (v2) aus RevenueCat. Rechte: customer_information:customers:read_write, project_configuration:entitlements:read */
export const RC_SECRET_KEY = defineSecret("REVENUECAT_SECRET_KEY");

/** Signing Secret der RevenueCat-Webhook-Integration (HMAC). */
export const RC_WEBHOOK_SECRET = defineSecret("REVENUECAT_WEBHOOK_SECRET");

/** Projekt-ID aus RevenueCat, z. B. "proj1ab2c3d4". */
export const RC_PROJECT_ID = defineString("REVENUECAT_PROJECT_ID");

export const callOptions = { region: REGION, maxInstances: 10 };
export const storeCallOptions = { ...callOptions, secrets: [RC_SECRET_KEY] };
