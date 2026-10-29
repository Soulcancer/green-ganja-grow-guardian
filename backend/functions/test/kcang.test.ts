import assert from "node:assert/strict";
import { describe, it } from "node:test";
import {
  ageOn,
  berlinDate,
  checkAmount,
  isThcAllowed,
  isValidDate,
  isValidGrams,
  monthlyLimitG,
} from "../src/rules/kcang";

const ADULT = "1990-05-17";
const YOUNG = "2006-03-10"; // am 29.10.2026 genau 20 Jahre alt

describe("Datum und Alter", () => {
  it("erkennt gültige und ungültige Daten", () => {
    assert.equal(isValidDate("2028-02-29"), true);
    assert.equal(isValidDate("2026-02-29"), false);
    assert.equal(isValidDate("2026-13-01"), false);
    assert.equal(isValidDate("29.10.2026"), false);
    assert.equal(isValidDate(20261029), false);
  });

  it("rechnet mit deutscher Zeit, auch über Mitternacht und Sommerzeit", () => {
    assert.equal(berlinDate(new Date("2026-10-29T23:30:00Z")), "2026-10-30"); // MEZ, UTC+1
    assert.equal(berlinDate(new Date("2026-07-01T22:30:00Z")), "2026-07-02"); // MESZ, UTC+2
    assert.equal(berlinDate(new Date("2026-07-01T21:30:00Z")), "2026-07-01");
  });

  it("zählt vollendete Lebensjahre", () => {
    assert.equal(ageOn("2005-10-30", "2026-10-29"), 20);
    assert.equal(ageOn("2005-10-29", "2026-10-29"), 21);
    assert.equal(ageOn("2008-02-29", "2027-02-28"), 18);
    assert.equal(ageOn("2008-02-29", "2026-02-28"), 17);
  });

  it("setzt 30 g Monatslimit bis zum 21. Geburtstag, danach 50 g", () => {
    assert.equal(monthlyLimitG(YOUNG, "2026-10-29"), 30);
    assert.equal(monthlyLimitG("2005-10-29", "2026-10-29"), 50);
    assert.equal(monthlyLimitG(ADULT, "2026-10-29"), 50);
  });
});

describe("Mengen", () => {
  it("akzeptiert nur 0,1 bis 25 g in Zehntelschritten", () => {
    assert.equal(isValidGrams(0.1), true);
    assert.equal(isValidGrams(25), true);
    assert.equal(isValidGrams(0), false);
    assert.equal(isValidGrams(-3), false);
    assert.equal(isValidGrams(25.1), false);
    assert.equal(isValidGrams(1.25), false);
    assert.equal(isValidGrams("5"), false);
  });

  it("lässt das Monatslimit genau ausschöpfen", () => {
    const result = checkAmount(ADULT, "2026-10-29", 10, [{ date: "2026-10-02", grams: 40 }]);
    assert.deepEqual(result, { ok: true, remainingMonthG: 0 });
  });

  it("lehnt ab, was über das Monatslimit geht, und nennt den Rest", () => {
    const result = checkAmount(ADULT, "2026-10-29", 10.1, [{ date: "2026-10-02", grams: 40 }]);
    assert.deepEqual(result, { ok: false, reason: "monthly-limit", remainingG: 10 });
  });

  it("prüft das Tageslimit von 25 g", () => {
    const result = checkAmount(ADULT, "2026-10-29", 6, [{ date: "2026-10-29", grams: 20 }]);
    assert.deepEqual(result, { ok: false, reason: "daily-limit", remainingG: 5 });
  });

  it("gibt 18- bis 20-Jährigen nur 30 g im Monat", () => {
    const result = checkAmount(YOUNG, "2026-10-29", 5.1, [{ date: "2026-10-01", grams: 25 }]);
    assert.deepEqual(result, { ok: false, reason: "monthly-limit", remainingG: 5 });
  });

  it("zählt nur Buchungen im selben Kalendermonat", () => {
    const result = checkAmount(ADULT, "2026-11-01", 25, [{ date: "2026-10-31", grams: 25 }]);
    assert.deepEqual(result, { ok: true, remainingMonthG: 25 });
  });

  it("rechnet ohne Rundungsfehler", () => {
    const result = checkAmount(ADULT, "2026-10-29", 24.7, [
      { date: "2026-10-01", grams: 0.1 },
      { date: "2026-10-02", grams: 0.2 },
      { date: "2026-10-03", grams: 25 },
    ]);
    assert.deepEqual(result, { ok: true, remainingMonthG: 0 });
  });

  it("gibt unter 18 nichts ab", () => {
    const result = checkAmount("2009-01-01", "2026-10-29", 1, []);
    assert.equal(result.ok, false);
    assert.equal(!result.ok && result.reason, "under-18");
  });
});

describe("THC", () => {
  it("begrenzt THC unter 21 auf 10 %", () => {
    assert.equal(isThcAllowed(YOUNG, "2026-10-29", 10), true);
    assert.equal(isThcAllowed(YOUNG, "2026-10-29", 10.5), false);
    assert.equal(isThcAllowed(ADULT, "2026-10-29", 24), true);
    assert.equal(isThcAllowed(ADULT, "2026-10-29", Number.NaN), false);
    assert.equal(isThcAllowed(ADULT, "2026-10-29", "18"), false);
  });
});
