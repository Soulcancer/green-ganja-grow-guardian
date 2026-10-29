import * as logger from "firebase-functions/logger";
import { DAY_MS, RC_PROJECT_ID, RC_SECRET_KEY, SOCIAL_CLUB_LOOKUP_KEY } from "./config";

const BASE_URL = "https://api.revenuecat.com/v2";

export class RevenueCatError extends Error {
  constructor(
    readonly status: number,
    message: string,
  ) {
    super(message);
  }
}

interface RcResponse {
  status: number;
  json: any;
}

async function rc(method: "GET" | "POST", path: string, body?: unknown): Promise<RcResponse> {
  const url = `${BASE_URL}/projects/${encodeURIComponent(RC_PROJECT_ID.value())}${path}`;
  const response = await fetch(url, {
    method,
    headers: {
      Authorization: `Bearer ${RC_SECRET_KEY.value()}`,
      "Content-Type": "application/json",
      Accept: "application/json",
    },
    body: body === undefined ? undefined : JSON.stringify(body),
    signal: AbortSignal.timeout(15_000),
  });
  const text = await response.text();
  let json: unknown = null;
  try {
    json = text ? JSON.parse(text) : null;
  } catch {
    json = null;
  }
  return { status: response.status, json };
}

const isSuccess = (status: number) => status >= 200 && status < 300;

let cachedEntitlements: Map<string, string> | undefined;

/** Lookup-Key → interne ID ("entl…"). Die API v2 arbeitet mit den internen IDs. */
async function entitlementIds(): Promise<Map<string, string>> {
  if (cachedEntitlements) return cachedEntitlements;
  const found = new Map<string, string>();
  let startingAfter: string | undefined;
  for (let page = 0; page < 10; page++) {
    const query = new URLSearchParams({ limit: "100" });
    if (startingAfter) query.set("starting_after", startingAfter);
    const { status, json } = await rc("GET", `/entitlements?${query.toString()}`);
    if (!isSuccess(status)) throw new RevenueCatError(status, `RevenueCat: Entitlements nicht lesbar (${status})`);

    const items: Array<{ id: string; lookup_key: string }> = json?.items ?? [];
    for (const item of items) found.set(item.lookup_key, item.id);
    if (!json?.next_page || items.length === 0) break;
    startingAfter = items[items.length - 1].id;
  }
  cachedEntitlements = found;
  return found;
}

export async function socialClubEntitlementId(): Promise<string> {
  const id = (await entitlementIds()).get(SOCIAL_CLUB_LOOKUP_KEY);
  if (!id) throw new RevenueCatError(404, `RevenueCat: Entitlement "${SOCIAL_CLUB_LOOKUP_KEY}" nicht gefunden`);
  return id;
}

interface ActiveEntitlement {
  entitlement_id: string;
  expires_at: number | null;
}

/** Aktive Entitlements eines Kunden; ein unbekannter Kunde hat keine. */
async function activeEntitlements(customerId: string): Promise<ActiveEntitlement[]> {
  const { status, json } = await rc("GET", `/customers/${encodeURIComponent(customerId)}`);
  if (status === 404) return [];
  if (!isSuccess(status)) throw new RevenueCatError(status, `RevenueCat: Kunde nicht lesbar (${status})`);
  return json?.active_entitlements?.items ?? [];
}

/** Lookup-Keys der aktiven Entitlements, z. B. ["premium_farmer"]. */
export async function activeLookupKeys(customerId: string): Promise<string[]> {
  const [ids, active] = await Promise.all([entitlementIds(), activeEntitlements(customerId)]);
  const keyById = new Map([...ids].map(([key, id]) => [id, key]));
  const now = Date.now();
  return active
    .filter((item) => item.expires_at === null || item.expires_at > now)
    .map((item) => keyById.get(item.entitlement_id))
    .filter((key): key is string => typeof key === "string");
}

/** Bis wann der Kunde Social Club hat (ms), oder null, wenn nicht aktiv. */
export async function socialClubActiveUntil(customerId: string): Promise<number | null> {
  const entitlementId = await socialClubEntitlementId();
  const items = await activeEntitlements(customerId);
  const match = items.find((item) => item.entitlement_id === entitlementId);
  if (!match) return null;
  // Ohne Ablaufdatum (lebenslang) ein Jahr ansetzen; der nächtliche Abgleich verlängert weiter.
  return match.expires_at ?? Date.now() + 365 * DAY_MS;
}

/** Vergibt Social Club bis [expiresAtMs] an einen Team-Platz. */
export async function grantSocialClub(customerId: string, expiresAtMs: number): Promise<void> {
  const entitlementId = await socialClubEntitlementId();
  const path = `/customers/${encodeURIComponent(customerId)}/actions/grant_entitlement`;
  const body = { entitlement_id: entitlementId, expires_at: Math.round(expiresAtMs) };

  let response = await rc("POST", path, body);
  if (response.status === 404) {
    // Der Kunde existiert in RevenueCat noch nicht, z. B. weil die App nach dem Login noch nicht synchronisiert hat.
    await rc("POST", "/customers", { id: customerId });
    response = await rc("POST", path, body);
  }
  if (!isSuccess(response.status)) {
    throw new RevenueCatError(response.status, `RevenueCat: Freigabe fehlgeschlagen (${response.status})`);
  }
}

/** Entzieht einen vergebenen Team-Platz sofort. */
export async function revokeSocialClub(customerId: string): Promise<void> {
  const entitlementId = await socialClubEntitlementId();
  const response = await rc("POST", `/customers/${encodeURIComponent(customerId)}/actions/revoke_granted_entitlement`, {
    entitlement_id: entitlementId,
  });
  if (response.status === 404) {
    logger.info("RevenueCat: nichts zu entziehen", { customerId });
    return;
  }
  if (!isSuccess(response.status)) {
    throw new RevenueCatError(response.status, `RevenueCat: Entzug fehlgeschlagen (${response.status})`);
  }
}
