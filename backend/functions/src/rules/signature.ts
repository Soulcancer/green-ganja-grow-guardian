import { createHmac, timingSafeEqual } from "node:crypto";

/**
 * Prüft den Header "X-RevenueCat-Webhook-Signature: t=<Zeit>,v1=<HMAC-SHA256>".
 * Signiert ist "<t>.<roher Body>". Ältere Zeitstempel als [toleranceSec] werden abgelehnt.
 */
export function verifyRevenueCatSignature(
  header: string | undefined,
  rawBody: Buffer,
  secret: string,
  nowSec: number,
  toleranceSec = 300,
): boolean {
  if (!header || !secret) return false;

  let timestamp: string | undefined;
  const signatures: string[] = [];
  for (const part of header.split(",")) {
    const separator = part.indexOf("=");
    if (separator < 0) continue;
    const key = part.slice(0, separator).trim();
    const value = part.slice(separator + 1).trim();
    if (key === "t") timestamp = value;
    if (key === "v1" && /^[0-9a-f]+$/i.test(value)) signatures.push(value);
  }
  if (!timestamp || !/^\d+$/.test(timestamp) || signatures.length === 0) return false;

  const t = Number(timestamp);
  const tSec = t > 1e12 ? Math.floor(t / 1000) : t; // falls in Millisekunden geliefert
  if (Math.abs(nowSec - tSec) > toleranceSec) return false;

  const expected = createHmac("sha256", secret).update(`${timestamp}.`).update(rawBody).digest();
  return signatures.some((hex) => {
    const given = Buffer.from(hex, "hex");
    return given.length === expected.length && timingSafeEqual(given, expected);
  });
}
