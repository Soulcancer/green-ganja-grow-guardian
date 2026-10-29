import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { bookingsWithout, DEFAULT_NOTIFY, parseMemberSettings, visibleName } from "../src/rules/member";

describe("Mitglieder-Einstellungen", () => {
  it("nimmt Anzeigename und Benachrichtigungen an", () => {
    assert.deepEqual(parseMemberSettings({ publicName: "  Grüner  Daumen ", notify: { marketplace: true } }), {
      publicName: "Grüner Daumen",
      notify: { ...DEFAULT_NOTIFY, marketplace: true },
    });
    assert.deepEqual(parseMemberSettings({ publicName: "" }), { publicName: null, notify: DEFAULT_NOTIFY });
  });

  it("lehnt ungültige Namen ab", () => {
    assert.equal(typeof parseMemberSettings({ publicName: "x" }), "string");
    assert.equal(typeof parseMemberSettings({ publicName: "<script>" }), "string");
    assert.equal(typeof parseMemberSettings({ publicName: "a".repeat(31) }), "string");
    assert.equal(typeof parseMemberSettings({ publicName: 5 }), "string");
  });

  it("zeigt anderen nur Anzeigename oder Vorname mit Initiale", () => {
    assert.equal(visibleName("Daumen", "Max", "Muster"), "Daumen");
    assert.equal(visibleName(null, "Max", "Muster"), "Max M.");
    assert.equal(visibleName(undefined, undefined, undefined, "Max Muster"), "Max");
    assert.equal(visibleName(null), "Mitglied");
  });

  it("rechnet die geänderte Reservierung nicht doppelt", () => {
    const items = [{ id: "a", date: "2026-11-02", grams: 10 }, { id: "b", date: "2026-11-02", grams: 5 }];
    assert.deepEqual(bookingsWithout(items, "a").map((i) => i.id), ["b"]);
  });
});
