import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { dayIdOf, dayStats, historyExpireAt, isDayId, statsExpireAt, statsKey, wantsStats } from "../src/rules/history";

const DAY = 24 * 60 * 60 * 1000;

describe("Verlauf", () => {
  it("erkennt gültige Tage", () => {
    assert.equal(isDayId("2026-10-29"), true);
    assert.equal(isDayId("2026-02-30"), false);
    assert.equal(isDayId("2026-1-29"), false);
    assert.equal(dayIdOf(Date.UTC(2026, 9, 29, 23, 59)), "2026-10-29");
  });

  it("bewahrt je Stufe unterschiedlich lange auf", () => {
    const start = Date.UTC(2026, 9, 29);
    assert.equal(historyExpireAt("2026-10-29", "freemium"), start + 8 * DAY);
    assert.equal(historyExpireAt("2026-10-29", "premium"), start + 366 * DAY);
    assert.equal(historyExpireAt("2026-10-29", "social_club"), null);
    assert.equal(statsExpireAt(2026, "premium"), Date.UTC(2027, 0, 1) + 366 * DAY);
    assert.equal(statsExpireAt(2026, "social_club"), null);
  });

  it("rechnet Statistik nur für lange Aufbewahrung", () => {
    assert.equal(wantsStats("2026-10-29", historyExpireAt("2026-10-29", "freemium")), false);
    assert.equal(wantsStats("2026-10-29", historyExpireAt("2026-10-29", "premium")), true);
    assert.equal(wantsStats("2026-10-29", null), true);
  });

  it("berechnet Min, Mittel und Max je Messgröße", () => {
    const stats = dayStats({
      "0000": { air_temperature: 22, air_humidity: 60 },
      "0015": { air_temperature: 24, air_humidity: "x" },
      "0030": { air_temperature: 26.5 },
      kaputt: 5,
    });
    assert.deepEqual(stats.air_temperature, [22, 24.167, 26.5]);
    assert.deepEqual(stats.air_humidity, [60, 60, 60]);
    assert.deepEqual(dayStats(undefined), {});
    assert.equal(statsKey("2026-10-29"), "1029");
  });
});
