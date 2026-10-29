import { randomBytes } from "node:crypto";
import { FieldValue } from "firebase-admin/firestore";
import { onCall } from "firebase-functions/v2/https";
import { requireUid } from "./access";
import { callOptions } from "./config";
import { db } from "./firebase";

/**
 * Zugangsdaten für Tuyas UID-Login (loginOrRegisterWithUid).
 * UID = Firebase-UID, Passwort = zufälliger, dauerhafter Wert, den nur dieser Nutzer abrufen kann.
 * So hängt das Tuya-Konto am Firebase-Login, ohne dass Nutzer ein zweites Passwort brauchen.
 */
export const getTuyaLogin = onCall(callOptions, async (request) => {
  const uid = requireUid(request);
  const ref = db().doc(`tuyaAccounts/${uid}`);
  const password = await db().runTransaction(async (tx) => {
    const snap = await tx.get(ref);
    const existing = snap.get("password");
    if (typeof existing === "string") return existing;
    const created = randomBytes(24).toString("base64url");
    tx.create(ref, { password: created, createdAt: FieldValue.serverTimestamp() });
    return created;
  });
  return { uid, password };
});
