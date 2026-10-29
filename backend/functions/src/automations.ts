import { FieldValue } from "firebase-admin/firestore";
import { onCall } from "firebase-functions/v2/https";
import { requireUid } from "./access";
import { storeCallOptions } from "./config";
import { fail } from "./errors";
import { db } from "./firebase";
import { checkAutomationLimit, MAX_AUTOMATIONS, parseAutomation } from "./rules/automation";
import { freshTier } from "./tier";

const automationsRef = (uid: string) => db().collection(`users/${uid}/automations`);

/**
 * Regel anlegen oder ändern. Nur mit Premium oder Social Club (bei RevenueCat geprüft) und bis zum Limit.
 * Die IDs der Tuya-Automationen (sceneIds) bleiben beim Ändern stehen; die App ersetzt sie nach der Übertragung.
 */
export const saveAutomation = onCall(storeCallOptions, async (request) => {
  const uid = requireUid(request);
  const input = parseAutomation(request.data);
  if (typeof input === "string") fail("invalid-argument", "invalid-input", input);

  let tier;
  try {
    tier = await freshTier(uid);
  } catch {
    fail("unavailable", "store-unavailable", "Dein Abo-Status ist gerade nicht prüfbar. Bitte versuch es gleich noch einmal.");
  }

  const automationId = await db().runTransaction(async (tx) => {
    const box = await tx.get(db().doc(`users/${uid}/growBoxes/${input.boxId}`));
    if (!box.exists) fail("not-found", "not-found", "Diese Box gibt es nicht mehr.");
    const existing = await tx.get(automationsRef(uid));
    const creating = input.automationId === null;
    if (!creating && !existing.docs.some((d) => d.id === input.automationId)) fail("not-found", "not-found", "Diese Regel gibt es nicht mehr.");

    const limit = checkAutomationLimit(tier, existing.size, creating);
    if (!limit.ok) {
      if (limit.reason === "automations-premium") {
        fail("permission-denied", "automations-premium", "Automationen gibt es ab Premium Farmer.");
      }
      fail("resource-exhausted", "automation-limit", `Deine Stufe erlaubt ${MAX_AUTOMATIONS[tier]} Regeln.`);
    }

    const { automationId: _ignored, ...fields } = input;
    const ref = creating ? automationsRef(uid).doc() : automationsRef(uid).doc(input.automationId!);
    // Felder der anderen Regel-Art entfernen, falls die Art gewechselt wurde
    const cleared =
      input.type === "threshold"
        ? { onTime: FieldValue.delete(), offTime: FieldValue.delete(), days: FieldValue.delete() }
        : {
            sensorDeviceId: FieldValue.delete(), metric: FieldValue.delete(), dpId: FieldValue.delete(), dpCode: FieldValue.delete(),
            scale: FieldValue.delete(), unit: FieldValue.delete(), switchOnAbove: FieldValue.delete(), onAt: FieldValue.delete(), offAt: FieldValue.delete(),
          };
    if (creating) {
      tx.create(ref, { ...fields, sceneIds: [], syncError: null, createdAt: FieldValue.serverTimestamp(), updatedAt: FieldValue.serverTimestamp() });
    } else {
      tx.set(ref, { ...cleared, ...fields, updatedAt: FieldValue.serverTimestamp() }, { merge: true });
    }
    return ref.id;
  });
  return { automationId };
});

/** Regel löschen. Die App entfernt vorher die Tuya-Automationen. */
export const deleteAutomation = onCall(storeCallOptions, async (request) => {
  const uid = requireUid(request);
  const id = (request.data as { automationId?: unknown } | null)?.automationId;
  if (typeof id !== "string" || !/^[A-Za-z0-9_-]{1,128}$/.test(id)) fail("invalid-argument", "invalid-input", "Ungültige Regel.");
  await automationsRef(uid).doc(id).delete();
  return { ok: true };
});

/** Beim Löschen einer Box ihre Regeln mitlöschen. */
export async function deleteAutomationsOfBox(uid: string, boxId: string): Promise<void> {
  const docs = (await automationsRef(uid).where("boxId", "==", boxId).get()).docs;
  await Promise.all(docs.map((d) => d.ref.delete()));
}
