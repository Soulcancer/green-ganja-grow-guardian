import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { checkGrowLimits, parseGrowBox, TIER_LIMITS, tierFromEntitlements, type ExistingBox } from "../src/rules/grow";

const box = (id: string, createdAt: number, ...deviceIds: string[]): ExistingBox => ({ id, createdAt, deviceIds });
const valid = { boxId: null, name: " Zelt  1 ", phase: "flowering", medium: "coco", leafOffsetC: -2, deviceIds: ["a", "a", "b"], targets: { ph: { min: 5.8, max: 6.2 } } };

describe("Grow-Box-Eingaben", () => {
  it("bereinigt gültige Eingaben", () => {
    const parsed = parseGrowBox(valid);
    assert.notEqual(typeof parsed, "string");
    if (typeof parsed === "string") return;
    assert.equal(parsed.name, "Zelt 1");
    assert.deepEqual(parsed.deviceIds, ["a", "b"]);
    assert.deepEqual(parsed.targets, { ph: { min: 5.8, max: 6.2 } });
  });

  it("lehnt Unsinn ab", () => {
    assert.equal(typeof parseGrowBox({ ...valid, name: "" }), "string");
    assert.equal(typeof parseGrowBox({ ...valid, phase: "bloom" }), "string");
    assert.equal(typeof parseGrowBox({ ...valid, leafOffsetC: 9 }), "string");
    assert.equal(typeof parseGrowBox({ ...valid, deviceIds: ["../x"] }), "string");
    assert.equal(typeof parseGrowBox({ ...valid, targets: { ph: { min: 7, max: 6 } } }), "string");
    assert.equal(typeof parseGrowBox({ ...valid, targets: { lux: { min: 1, max: 2 } } }), "string");
  });
});

describe("Limits der Stufen", () => {
  it("Freemium: eine Box, fünf Sensoren", () => {
    assert.deepEqual(checkGrowLimits([], { boxId: null, deviceIds: ["1", "2", "3", "4", "5"] }, TIER_LIMITS.freemium), { ok: true });
    assert.deepEqual(checkGrowLimits([], { boxId: null, deviceIds: ["1", "2", "3", "4", "5", "6"] }, TIER_LIMITS.freemium), { ok: false, reason: "device-limit" });
    assert.deepEqual(checkGrowLimits([box("a", 1)], { boxId: null, deviceIds: [] }, TIER_LIMITS.freemium), { ok: false, reason: "box-limit" });
  });

  it("nach einem Downgrade sind jüngere Boxen gesperrt, die älteste bleibt änderbar", () => {
    const boxes = [box("b", 2, "x"), box("a", 1, "1", "2", "3", "4", "5", "6", "7")];
    assert.deepEqual(checkGrowLimits(boxes, { boxId: "b", deviceIds: [] }, TIER_LIMITS.freemium), { ok: false, reason: "box-locked" });
    // über dem Limit, aber nicht mehr Sensoren: erlaubt
    assert.deepEqual(checkGrowLimits(boxes, { boxId: "a", deviceIds: ["1", "2", "3", "4", "5", "6"] }, TIER_LIMITS.freemium), { ok: true });
    assert.deepEqual(checkGrowLimits(boxes, { boxId: "a", deviceIds: ["1", "2", "3", "4", "5", "6", "7", "8"] }, TIER_LIMITS.freemium), { ok: false, reason: "device-limit" });
  });

  it("Premium zählt Sensoren über alle Boxen, Verschieben zählt nicht doppelt", () => {
    const many = Array.from({ length: 29 }, (_, i) => `d${i}`);
    const boxes = [box("a", 1, ...many), box("b", 2)];
    assert.deepEqual(checkGrowLimits(boxes, { boxId: "b", deviceIds: ["n1"] }, TIER_LIMITS.premium), { ok: true });
    assert.deepEqual(checkGrowLimits(boxes, { boxId: "b", deviceIds: ["n1", "n2"] }, TIER_LIMITS.premium), { ok: false, reason: "device-limit" });
    assert.deepEqual(checkGrowLimits(boxes, { boxId: "b", deviceIds: ["d0", "d1", "n1"] }, TIER_LIMITS.premium), { ok: true });
  });

  it("Social Club ist unbegrenzt", () => {
    const boxes = Array.from({ length: 10 }, (_, i) => box(`b${i}`, i, `d${i}`));
    assert.deepEqual(checkGrowLimits(boxes, { boxId: null, deviceIds: ["x"] }, TIER_LIMITS.social_club), { ok: true });
    assert.deepEqual(checkGrowLimits(boxes, { boxId: "zz", deviceIds: [] }, TIER_LIMITS.social_club), { ok: false, reason: "not-found" });
  });

  it("die höchste Stufe gilt", () => {
    assert.equal(tierFromEntitlements(["premium_farmer", "social_club_farmer"]), "social_club");
    assert.equal(tierFromEntitlements(["premium_farmer"]), "premium");
    assert.equal(tierFromEntitlements([]), "freemium");
  });
});

import { isPlainDate, parseGrowBox as parseBoxForJournal } from "../src/rules/grow";

describe("Box mit Sorte und Starttag", () => {
  const base = { name: "Zelt", phase: "flowering", medium: "soil", deviceIds: [], targets: {} };
  it("übernimmt Sorte und Starttag", () => {
    const box = parseBoxForJournal({ ...base, strain: "  Amnesia   Haze ", plantedAt: "2026-10-01" });
    assert.notEqual(typeof box, "string");
    if (typeof box !== "string") {
      assert.equal(box.strain, "Amnesia Haze");
      assert.equal(box.plantedAt, "2026-10-01");
    }
    const empty = parseBoxForJournal({ ...base, strain: "" });
    if (typeof empty !== "string") assert.equal(empty.strain, null);
  });
  it("lehnt kaputte Werte ab", () => {
    assert.equal(typeof parseBoxForJournal({ ...base, plantedAt: "2026-02-30" }), "string");
    assert.equal(typeof parseBoxForJournal({ ...base, strain: "x".repeat(61) }), "string");
    assert.equal(isPlainDate("1999-12-31"), false);
  });
});
