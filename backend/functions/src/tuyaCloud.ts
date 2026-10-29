import { defineSecret, defineString } from "firebase-functions/params";
import { tuyaSign, tuyaStringToSign, type DpSpec } from "./rules/monitor";

/**
 * Tuya Cloud OpenAPI (serverseitig). Braucht ein Cloud-Projekt in der Tuya IoT Platform, das mit eurer
 * App-SDK-App verknüpft ist (Cloud → Projekt → „Devices“ / „Link App“), damit es die Geräte der App-Nutzer sieht.
 * Secrets: firebase functions:secrets:set TUYA_CLIENT_ID und TUYA_CLIENT_SECRET.
 * Rechenzentrum in .env: TUYA_BASE_URL (Standard Zentraleuropa: https://openapi.tuyaeu.com).
 */
export const TUYA_CLIENT_ID = defineSecret("TUYA_CLIENT_ID");
export const TUYA_CLIENT_SECRET = defineSecret("TUYA_CLIENT_SECRET");
export const TUYA_BASE_URL = defineString("TUYA_BASE_URL", { default: "https://openapi.tuyaeu.com" });

let token: { value: string; expiresAt: number } | null = null;

export class TuyaError extends Error {
  constructor(readonly code: string | number | undefined, message: string) {
    super(message);
  }
}

async function call<T>(method: "GET" | "POST", path: string, body?: unknown): Promise<T> {
  const isTokenCall = path.startsWith("/v1.0/token");
  const accessToken = isTokenCall ? "" : await getToken();
  const content = body === undefined ? "" : JSON.stringify(body);
  const t = Date.now().toString();
  const sign = tuyaSign(TUYA_CLIENT_ID.value(), TUYA_CLIENT_SECRET.value(), accessToken, t, tuyaStringToSign(method, content, path));
  const response = await fetch(TUYA_BASE_URL.value() + path, {
    method,
    headers: {
      client_id: TUYA_CLIENT_ID.value(),
      sign,
      t,
      sign_method: "HMAC-SHA256",
      ...(accessToken ? { access_token: accessToken } : {}),
      "Content-Type": "application/json",
    },
    body: content || undefined,
  });
  const json = (await response.json()) as { success?: boolean; code?: number | string; msg?: string; result?: T };
  if (!json.success) {
    // Abgelaufenes Token: beim nächsten Aufruf neu holen
    if (json.code === 1010 || json.code === 1011) token = null;
    throw new TuyaError(json.code, `Tuya ${path}: ${json.code} ${json.msg ?? ""}`.trim());
  }
  return json.result as T;
}

async function getToken(): Promise<string> {
  if (token && Date.now() < token.expiresAt) return token.value;
  const result = await call<{ access_token: string; expire_time: number }>("GET", "/v1.0/token?grant_type=1");
  token = { value: result.access_token, expiresAt: Date.now() + Math.max(60, result.expire_time - 120) * 1000 };
  return token.value;
}

export interface TuyaDevice {
  id: string;
  name?: string;
  /** Tuya-Nutzer, dem das Gerät gehört. */
  uid?: string;
  category?: string;
  product_id?: string;
  online?: boolean;
  status?: { code: string; value: unknown }[];
}

/** Gerätedetails samt aktuellem Status und Online-Zustand. */
export const deviceDetails = (id: string) => call<TuyaDevice>("GET", `/v1.0/devices/${encodeURIComponent(id)}`);

/** Datenpunkte mit Skalierung und Einheit. */
export const deviceSpecs = async (id: string): Promise<DpSpec[]> =>
  (await call<{ status?: DpSpec[] }>("GET", `/v1.0/devices/${encodeURIComponent(id)}/specifications`)).status ?? [];

/** Nutzerinfo; `username` ist bei UID-Login die Firebase-UID. ANNAHME, siehe README „Server-Überwachung“. */
export const userInfo = (tuyaUid: string) => call<{ uid?: string; username?: string }>("GET", `/v1.0/users/${encodeURIComponent(tuyaUid)}/infos`);

export const sendCommand = (id: string, code: string, value: unknown) =>
  call<boolean>("POST", `/v1.0/devices/${encodeURIComponent(id)}/commands`, { commands: [{ code, value }] });
