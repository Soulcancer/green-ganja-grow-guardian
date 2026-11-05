import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { FREE_LAMP_IDS, LAMP_IDS, lampsAllowed, lightField, needsPremium, parseLightSetup } from "../src/rules/light";
import { kindOf, metricFor } from "../src/rules/monitor";

const valid = {
  boxId: "box1",
  lamps: [{ lampId: "led_white_3000", watts: 300, count: 2 }, { lampId: "led_far_red", watts: 50 }],
  areaM2: 1.44,
  dimPercent: 80,
  hoursPerDay: 12,
  calibration: 1.1,
};

describe("Lichtmodul", () => {
  it("Katalog: 28 Lampen, frei nur Tageslicht und Glühbirne", () => {
    assert.equal(LAMP_IDS.length, 28);
    assert.equal(new Set(LAMP_IDS).size, LAMP_IDS.length);
    assert.deepEqual([...FREE_LAMP_IDS], ["daylight", "incandescent"]);
  });

  it("bereinigt gültige Eingaben", () => {
    const parsed = parseLightSetup(valid);
    assert.notEqual(typeof parsed, "string");
    if (typeof parsed === "string") return;
    assert.deepEqual(parsed.lamps[1], { lampId: "led_far_red", watts: 50, count: 1 });
    assert.deepEqual(lightField(parsed).lampIds, ["led_white_3000", "led_far_red"]);
    assert.notEqual(typeof parseLightSetup({ boxId: "b", lamps: [{ lampId: "daylight" }] }), "string");
  });

  it("lehnt Unsinn ab", () => {
    assert.equal(typeof parseLightSetup({ ...valid, lamps: [] }), "string");
    assert.equal(typeof parseLightSetup({ ...valid, lamps: [{ lampId: "laser" }] }), "string");
    assert.equal(typeof parseLightSetup({ ...valid, lamps: [{ lampId: "mh", watts: 5000 }] }), "string");
    assert.equal(typeof parseLightSetup({ ...valid, lamps: [{ lampId: "mh", count: 1.5 }] }), "string");
    assert.equal(typeof parseLightSetup({ ...valid, dimPercent: 5 }), "string");
    assert.equal(typeof parseLightSetup({ ...valid, calibration: 9 }), "string");
    assert.equal(typeof parseLightSetup({ ...valid, boxId: "../x" }), "string");
    assert.equal(typeof parseLightSetup({ ...valid, lamps: new Array(7).fill({ lampId: "mh" }) }), "string");
  });

  it("Freemium nur mit freien Lampen", () => {
    const premium = parseLightSetup(valid);
    const free = parseLightSetup({ ...valid, lamps: [{ lampId: "daylight" }, { lampId: "incandescent", watts: 100 }] });
    if (typeof premium === "string" || typeof free === "string") throw new Error(String(premium) + String(free));
    assert.equal(needsPremium(free), false);
    assert.equal(lampsAllowed(free, "freemium"), true);
    assert.equal(lampsAllowed(premium, "freemium"), false);
    assert.equal(lampsAllowed(premium, "premium"), true);
    assert.equal(lampsAllowed(premium, "social_club"), true);
  });

  it("Tuya-Helligkeitssensor wie in der App", () => {
    assert.equal(kindOf("ldcg", ["bright_value", "battery_percentage"]), "light");
    assert.equal(metricFor("bright_value", "light"), "light");
    assert.equal(kindOf("wsdcg", ["va_temperature", "bright_value"]), "climate");
  });
});
