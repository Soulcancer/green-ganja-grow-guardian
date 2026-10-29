import assert from "node:assert/strict";
import { createHmac } from "node:crypto";
import { describe, it } from "node:test";
import { verifyRevenueCatSignature } from "../src/rules/signature";

const SECRET = "whsec_test";
const BODY = Buffer.from(JSON.stringify({ api_version: "1.0", event: { type: "RENEWAL", app_user_id: "abc" } }));
const NOW = 1_793_000_000;

const sign = (t: number | string, body = BODY, secret = SECRET) =>
  createHmac("sha256", secret).update(`${t}.`).update(body).digest("hex");

describe("RevenueCat-Webhook-Signatur", () => {
  it("akzeptiert eine korrekte Signatur", () => {
    assert.equal(verifyRevenueCatSignature(`t=${NOW},v1=${sign(NOW)}`, BODY, SECRET, NOW), true);
  });

  it("akzeptiert eine von mehreren Signaturen (z. B. beim Schlüsselwechsel)", () => {
    const header = `t=${NOW},v1=${sign(NOW, BODY, "alt")},v1=${sign(NOW)}`;
    assert.equal(verifyRevenueCatSignature(header, BODY, SECRET, NOW), true);
  });

  it("lehnt veränderten Inhalt ab", () => {
    const tampered = Buffer.from(BODY.toString().replace("abc", "xyz"));
    assert.equal(verifyRevenueCatSignature(`t=${NOW},v1=${sign(NOW)}`, tampered, SECRET, NOW), false);
  });

  it("lehnt falsches Secret und alte Zeitstempel ab", () => {
    assert.equal(verifyRevenueCatSignature(`t=${NOW},v1=${sign(NOW, BODY, "falsch")}`, BODY, SECRET, NOW), false);
    const old = NOW - 301;
    assert.equal(verifyRevenueCatSignature(`t=${old},v1=${sign(old)}`, BODY, SECRET, NOW), false);
  });

  it("versteht Zeitstempel in Millisekunden", () => {
    const ms = NOW * 1000;
    assert.equal(verifyRevenueCatSignature(`t=${ms},v1=${sign(ms)}`, BODY, SECRET, NOW), true);
  });

  it("lehnt fehlende oder kaputte Header ab", () => {
    assert.equal(verifyRevenueCatSignature(undefined, BODY, SECRET, NOW), false);
    assert.equal(verifyRevenueCatSignature("v1=abc", BODY, SECRET, NOW), false);
    assert.equal(verifyRevenueCatSignature(`t=${NOW},v1=zz`, BODY, SECRET, NOW), false);
    assert.equal(verifyRevenueCatSignature(`t=${NOW},v1=${sign(NOW)}`, BODY, "", NOW), false);
  });
});
