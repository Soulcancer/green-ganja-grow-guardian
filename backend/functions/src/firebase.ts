import { getApps, initializeApp } from "firebase-admin/app";
import { getAuth, type Auth } from "firebase-admin/auth";
import { getFirestore, type Firestore } from "firebase-admin/firestore";

if (getApps().length === 0) initializeApp();

export const db = (): Firestore => getFirestore();
export const auth = (): Auth => getAuth();

/** Name für die Mitgliederliste des Clubs. */
export async function displayNameOf(uid: string): Promise<string> {
  try {
    const user = await auth().getUser(uid);
    return user.displayName?.trim() || user.email || "Mitglied";
  } catch {
    return "Mitglied";
  }
}
