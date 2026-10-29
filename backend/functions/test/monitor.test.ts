import assert from "node:assert/strict";
import { describe, it } from "node:test";
import {
  berlinClock, combine, convert, effectiveTargets, evaluate, issues, kindOf, leafVpd, readDevice, scheduleWantsOn,
  slotKey, thresholdDecision, tuyaSign, tuyaStringToSign,
} from "../src/rules/monitor";

describe("Server-Überwachung", () => {
  it("Tuya-Signatur nach Doku", () => {
    const s = tuyaStringToSign("get", "", "/v1.0/token?grant_type=1");
    assert.equal(s, "GET\ne3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855\n\n/v1.0/token?grant_type=1");
    const sign = tuyaSign("id", "secret", "", "1700000000000", s);
    assert.match(sign, /^[0-9A-F]{64}$/);
    assert.notEqual(sign, tuyaSign("id", "secret", "token", "1700000000000", s));
  });

  it("rechnet Datenpunkte mit Skalierung und Einheit um", () => {
    const specs = [
      { code: "va_temperature", values: '{"unit":"°C","scale":1}' },
      { code: "va_humidity", values: '{"unit":"%","scale":0}' },
    ];
    assert.deepEqual(readDevice([{ code: "va_temperature", value: 235 }, { code: "va_humidity", value: 55 }], specs), {
      air_temperature: 23.5, air_humidity: 55,
    });
    assert.equal(convert({ code: "temp_current", values: '{"unit":"°F","scale":0}' }, 68, "air_temperature"), 20);
    assert.equal(convert({ code: "ec_current", values: '{"unit":"us/cm","scale":0}' }, 1600, "ec"), 1.6);
    assert.equal(convert(undefined, 15, "ph"), null, "pH 15 ist unplausibel");
    assert.equal(kindOf("zwjcy", ["temp_current"]), "soil");
    assert.deepEqual(readDevice([{ code: "temp_current", value: 21 }], [], "zwjcy"), { soil_temperature: 21 });
  });

  it("mittelt Sensoren und berechnet den VPD", () => {
    const c = combine([{ air_temperature: 24, air_humidity: 50 }, { air_temperature: 26, air_humidity: 60 }], -2);
    assert.equal(c.air_temperature, 25);
    assert.equal(c.air_humidity, 55);
    assert.ok(Math.abs((c.vpd ?? 0) - leafVpd(25, 55, -2)) < 1e-9);
  });

  it("Zielwerte: Startwerte plus eigene", () => {
    const t = effectiveTargets({ phase: "flowering", medium: "soil", targets: { air_humidity: { min: 45, max: 50 } } });
    assert.deepEqual(t.air_humidity, { min: 45, max: 50 });
    assert.deepEqual(t.air_temperature, { min: 20, max: 26 });
    assert.ok(t.soil_moisture && !t.water_temperature);
  });

  it("Alarme: zweimal hintereinander, deutlich sofort, Erinnerung nach 6 Std.", () => {
    const box = { id: "b1", name: "Zelt" };
    const t = effectiveTargets({ phase: "flowering", medium: "soil" });
    const first = evaluate({}, issues(box, { air_humidity: 57 }, t, [], new Set()), 0);
    assert.equal(first.alerts.length, 0);
    const second = evaluate(first.state, issues(box, { air_humidity: 57 }, t, [], new Set(Object.keys(first.state))), 1);
    assert.equal(second.alerts.length, 1);
    assert.match(second.alerts[0].lines[0], /Luftfeuchte zu hoch: 57 %/);
    const severe = evaluate({}, issues(box, { air_humidity: 70 }, t, [], new Set()), 0);
    assert.equal(severe.alerts.length, 1);
    const quiet = evaluate(second.state, issues(box, { air_humidity: 57 }, t, [], new Set(Object.keys(second.state))), 2);
    assert.equal(quiet.alerts.length, 0);
    const resolved = evaluate(second.state, [], 3);
    assert.deepEqual(resolved.resolvedBoxIds, ["b1"]);
    const offline = issues(box, {}, t, ["Klima"], new Set());
    assert.equal(offline[0].text, "Sensor „Klima“ ist offline");
  });

  it("Automation mit Schaltpunkten", () => {
    const dehum = { type: "threshold" as const, metric: "air_humidity" as const, switchOnAbove: true, onAt: 60, offAt: 55 };
    assert.equal(thresholdDecision(dehum, 61), true);
    assert.equal(thresholdDecision(dehum, 57), null);
    assert.equal(thresholdDecision(dehum, 54), false);
    const heat = { type: "threshold" as const, metric: "air_temperature" as const, switchOnAbove: false, onAt: 18, offAt: 20 };
    assert.equal(thresholdDecision(heat, 17), true);
    assert.equal(thresholdDecision(heat, 21), false);
    assert.equal(thresholdDecision(heat, undefined), null);
  });

  it("Zeitplan über Mitternacht und Wochentage", () => {
    const light = { type: "schedule" as const, onTime: "06:00", offTime: "00:00", days: "1111111" };
    assert.equal(scheduleWantsOn(light, 5 * 60, 1), false);
    assert.equal(scheduleWantsOn(light, 6 * 60, 1), true);
    assert.equal(scheduleWantsOn(light, 23 * 60 + 59, 1), true);
    const night = { type: "schedule" as const, onTime: "20:00", offTime: "08:00", days: "0100000" }; // nur Montag an
    assert.equal(scheduleWantsOn(night, 21 * 60, 1), true);
    assert.equal(scheduleWantsOn(night, 7 * 60, 2), true, "Dienstagmorgen gehört zur Montagsnacht");
    assert.equal(scheduleWantsOn(night, 7 * 60, 1), false);
    const day = { type: "schedule" as const, onTime: "08:00", offTime: "20:00", days: "1111111" };
    assert.equal(scheduleWantsOn(day, 20 * 60, 3), false);
  });

  it("deutsche Uhrzeit und Verlaufsraster", () => {
    // 29.10.2026 12:07 UTC = 13:07 MEZ, Donnerstag
    assert.deepEqual(berlinClock(Date.UTC(2026, 9, 29, 12, 7)), { minuteOfDay: 13 * 60 + 7, weekday: 4 });
    // Sommerzeit: 1.7.2026 12:00 UTC = 14:00 MESZ
    assert.equal(berlinClock(Date.UTC(2026, 6, 1, 12, 0)).minuteOfDay, 14 * 60);
    assert.equal(slotKey(Date.UTC(2026, 9, 29, 9, 7)), "0905");
  });
});
