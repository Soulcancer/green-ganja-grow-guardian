import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { checkPropagation, forbiddenWord, mayChangeListing, parseGear, parsePropagation } from "../src/rules/market";

const gear = { type: "offer", category: "light", condition: "good", title: "LED 240 W Vollspektrum", description: "Ein Jahr alt, läuft einwandfrei.", priceEur: 89.999 };

describe("Marktplatz", () => {
  it("nimmt Zubehör an und rundet den Preis", () => {
    const g = parseGear(gear);
    assert.notEqual(typeof g, "string");
    if (typeof g !== "string") assert.equal(g.priceEur, 90);
  });

  it("Preis nur bei Biete", () => {
    const g = parseGear({ ...gear, type: "free" });
    if (typeof g !== "string") assert.equal(g.priceEur, null);
    assert.equal(typeof parseGear({ ...gear, priceEur: -1 }), "string");
  });

  it("lässt kein Cannabis und keine Samen im Zubehör zu", () => {
    assert.equal(forbiddenWord("Biete 5 Gramm Haze"), "gramm");
    assert.equal(forbiddenWord("Zelt mit Abluft"), null);
    assert.equal(typeof parseGear({ ...gear, title: "Samen Northern Lights" }), "string");
    assert.equal(typeof parseGear({ ...gear, description: "Dazu gibt es ein paar Blüten" }), "string");
    // Teilwörter sind erlaubt: "Grasmatte" ist kein "gras"
    assert.equal(forbiddenWord("Grasmatte für den Balkon"), null);
  });

  it("prüft Pflichtfelder", () => {
    assert.equal(typeof parseGear({ ...gear, title: "ab" }), "string");
    assert.equal(typeof parseGear({ ...gear, category: "plants" }), "string");
    assert.equal(typeof parseGear({ ...gear, type: "sell" }), "string");
  });

  it("Club-Angebot für Vermehrungsmaterial", () => {
    assert.notEqual(typeof parsePropagation({ material: "seed", strain: "Northern Lights", stock: 20 }), "string");
    assert.equal(typeof parsePropagation({ material: "plant", strain: "x", stock: 1 }), "string");
    assert.equal(typeof parsePropagation({ material: "cutting", strain: "Haze", stock: 0 }), "string");
  });

  it("Monatsgrenzen nach § 20 KCanG", () => {
    assert.deepEqual(checkPropagation([], { material: "seed", count: 7 }), { ok: true, seedsLeft: 0, cuttingsLeft: 0 });
    assert.equal(checkPropagation([], { material: "seed", count: 8 }).ok, false);
    assert.deepEqual(checkPropagation([], { material: "cutting", count: 5 }), { ok: true, seedsLeft: 0, cuttingsLeft: 0 });
    assert.equal(checkPropagation([], { material: "cutting", count: 6 }).ok, false);
    // Gemischt zusammen höchstens 5
    assert.equal(checkPropagation([{ material: "seed", count: 3 }], { material: "cutting", count: 2 }).ok, true);
    const mixed = checkPropagation([{ material: "seed", count: 3 }], { material: "cutting", count: 3 });
    assert.deepEqual(mixed, { ok: false, reason: "propagation-limit", seedsLeft: 4, cuttingsLeft: 2 });
    // Nach 6 Samen ist kein Steckling mehr möglich
    assert.equal(checkPropagation([{ material: "seed", count: 6 }], { material: "cutting", count: 1 }).ok, false);
    assert.equal(checkPropagation([], { material: "seed", count: 0 }).ok, false);
  });

  it("Rechte am Inserat", () => {
    assert.equal(mayChangeListing("a", "a", false, "closed"), true);
    assert.equal(mayChangeListing("b", "a", false, "closed"), false);
    assert.equal(mayChangeListing("a", "a", false, "removed"), false);
    assert.equal(mayChangeListing("s", "a", true, "removed"), true);
    assert.equal(mayChangeListing("a", "a", false, "deleted"), false);
  });
});
