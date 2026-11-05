import { HttpsError, type FunctionsErrorCode } from "firebase-functions/v2/https";

/** Maschinenlesbarer Grund, den die App in `details.reason` bekommt. */
export type Reason =
  | "not-signed-in"
  | "invalid-input"
  | "no-subscription"
  | "already-in-club"
  | "not-in-club"
  | "not-owner"
  | "not-staff"
  | "club-inactive"
  | "club-full"
  | "invalid-code"
  | "code-expired"
  | "member-not-active"
  | "profile-incomplete"
  | "under-18"
  | "daily-limit"
  | "monthly-limit"
  | "thc-limit"
  | "not-found"
  | "wrong-status"
  | "owner-cannot-leave"
  | "store-unavailable"
  | "box-limit"
  | "box-locked"
  | "device-limit"
  | "automations-premium"
  | "automation-limit"
  | "club-box-limit"
  | "listing-limit"
  | "propagation-limit"
  | "already-claimed"
  | "insufficient-coins"
  | "not-needed"
  | "lamps-premium"
  | "email-not-verified"
  | "not-moderator"
  | "moderator-limit"
  | "market-blocked"
  | "announcement-limit";

/** Wirft einen Fehler mit deutscher Meldung, die die App direkt anzeigen kann. */
export function fail(
  code: FunctionsErrorCode,
  reason: Reason,
  message: string,
  extra: Record<string, unknown> = {},
): never {
  throw new HttpsError(code, message, { reason, ...extra });
}
