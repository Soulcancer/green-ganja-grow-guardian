import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { checkAutomationLimit, parseAutomation } from "../src/rules/automation";

const threshold = {
  boxId: "box1", name: " Entfeuchter ", enabled: true, actuatorDeviceId: "plug1", actuatorDpId: "1", actuatorLabel: "Steckdose",
  type: "threshold", sensorDeviceId: "klima1", metric: "air_humidity", dpId: "2", dpCode: "va_humidity", scale: 0, unit: "%",
  switchOnAbove: true, onAt: 60, offAt: 55,
};
const schedule = {
  boxId: "box1", name: "Licht 18/6", actuatorDeviceId: "plug1", actuatorDpId: "2", type: "schedule", onTime: "06:00", offTime: "00:00",
};

describe("Automationen", () => {
  it("nimmt gültige Regeln an", () => {
    const t = parseAutomation(threshold);
    assert.notEqual(typeof t, "string");
    if (typeof t !== "string") assert.equal(t.name, "Entfeuchter");
    const s = parseAutomation(schedule);
    assert.notEqual(typeof s, "string");
    if (typeof s !== "string" && s.type === "schedule") assert.equal(s.days, "1111111");
  });

  it("verlangt Abstand zwischen Ein- und Ausschaltpunkt in der richtigen Richtung", () => {
    assert.equal(typeof parseAutomation({ ...threshold, offAt: 60 }), "string");
    assert.equal(typeof parseAutomation({ ...threshold, switchOnAbove: false }), "string");
    assert.notEqual(typeof parseAutomation({ ...threshold, switchOnAbove: false, onAt: 18, offAt: 20 }), "string");
  });

  it("lehnt Unsinn ab", () => {
    assert.equal(typeof parseAutomation({ ...threshold, metric: "vpd" }), "string");
    assert.equal(typeof parseAutomation({ ...threshold, sensorDeviceId: "plug1" }), "string");
    assert.equal(typeof parseAutomation({ ...schedule, offTime: "06:00" }), "string");
    assert.equal(typeof parseAutomation({ ...schedule, onTime: "24:00" }), "string");
    assert.equal(typeof parseAutomation({ ...schedule, days: "0000000" }), "string");
    assert.equal(typeof parseAutomation({ ...schedule, type: "magic" }), "string");
  });

  it("Limits je Stufe", () => {
    assert.deepEqual(checkAutomationLimit("freemium", 0, true), { ok: false, reason: "automations-premium" });
    assert.deepEqual(checkAutomationLimit("freemium", 3, false), { ok: false, reason: "automations-premium" });
    assert.deepEqual(checkAutomationLimit("premium", 19, true), { ok: true });
    assert.deepEqual(checkAutomationLimit("premium", 20, true), { ok: false, reason: "automation-limit" });
    assert.deepEqual(checkAutomationLimit("premium", 25, false), { ok: true });
    assert.deepEqual(checkAutomationLimit("social_club", 150, true), { ok: true });
  });
});
