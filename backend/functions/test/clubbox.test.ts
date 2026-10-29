import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { checkShare, configOf, MAX_CLUB_BOXES, mayUnshare, parseShareRequest, parseUnshareRequest } from "../src/rules/clubbox";

describe("Club-Boxen", () => {
  it("übernimmt nur gültige Konfiguration", () => {
    const config = configOf({
      name: " Zelt 80×80 ", phase: "flowering", medium: "soil", leafOffsetC: -2,
      targets: { air_humidity: { min: 40, max: 55 }, bogus: { min: 1, max: 2 }, ph: { min: 7, max: 6 } },
    });
    assert.deepEqual(config, {
      name: "Zelt 80×80", phase: "flowering", medium: "soil", leafOffsetC: -2, targets: { air_humidity: { min: 40, max: 55 } },
    });
  });

  it("lehnt kaputte Boxen ab", () => {
    assert.equal(configOf({ name: "", phase: "flowering", medium: "soil" }), null);
    assert.equal(configOf({ name: "x", phase: "magic", medium: "soil" }), null);
    assert.equal(configOf({ name: "x".repeat(41), phase: "flowering", medium: "soil" }), null);
    assert.equal(configOf({ name: "x", phase: "flowering", medium: "soil", leafOffsetC: Infinity })?.leafOffsetC, -2);
  });

  it("prüft Kennungen", () => {
    assert.equal(parseShareRequest({ boxId: "abc_1" }), "abc_1");
    assert.equal(parseShareRequest({ boxId: "../x" }), null);
    assert.equal(parseUnshareRequest({ clubBoxId: "" }), null);
    assert.equal(parseUnshareRequest(null), null);
  });

  it("Limit gilt nur für neue Boxen", () => {
    assert.deepEqual(checkShare(MAX_CLUB_BOXES - 1, false), { ok: true });
    assert.deepEqual(checkShare(MAX_CLUB_BOXES, false), { ok: false, reason: "club-box-limit" });
    assert.deepEqual(checkShare(MAX_CLUB_BOXES, true), { ok: true });
  });

  it("Betreuer und Inhaber dürfen das Teilen beenden", () => {
    assert.equal(mayUnshare("a", "a", "o"), true);
    assert.equal(mayUnshare("o", "a", "o"), true);
    assert.equal(mayUnshare("b", "a", "o"), false);
  });
});
