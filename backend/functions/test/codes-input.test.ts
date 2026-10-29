import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { CODE_ALPHABET, generateCode, normalizeCode } from "../src/rules/codes";
import { csvCell, formatDecimal, isId, optionalText, requiredText } from "../src/rules/input";

describe("Codes", () => {
  it("erzeugt 8 Zeichen aus dem Alphabet ohne Verwechsler", () => {
    let i = 0;
    const code = generateCode(() => i++ % CODE_ALPHABET.length);
    assert.equal(code, "ABCDEFGH");
    assert.equal(/[ILO01]/.test(CODE_ALPHABET), false);
    assert.match(generateCode(), /^[A-Z2-9]{8}$/);
  });

  it("verträgt Kleinschreibung, Leerzeichen und Bindestrich", () => {
    assert.equal(normalizeCode("abcd-efgh"), "ABCDEFGH");
    assert.equal(normalizeCode(" ABCD EFGH "), "ABCDEFGH");
  });

  it("lehnt falsche Codes ab", () => {
    assert.equal(normalizeCode("ABCD-EFG1"), null);
    assert.equal(normalizeCode("ABCDEFG"), null);
    assert.equal(normalizeCode(12345678), null);
  });
});

describe("Eingaben", () => {
  it("bereinigt Texte", () => {
    assert.equal(requiredText("  Hans   Peter ", 1, 60), "Hans Peter");
    assert.equal(requiredText("", 1, 60), null);
    assert.equal(optionalText("   ", 10), undefined);
    assert.equal(optionalText(undefined, 10), undefined);
    assert.equal(optionalText("x".repeat(11), 10), null);
  });

  it("prüft IDs", () => {
    assert.equal(isId("Ab3_dE-9"), true);
    assert.equal(isId("a/b"), false);
    assert.equal(isId(""), false);
  });

  it("formatiert Zahlen deutsch", () => {
    assert.equal(formatDecimal(7.5), "7,5");
    assert.equal(formatDecimal(10), "10,0");
  });

  it("schützt CSV-Zellen", () => {
    assert.equal(csvCell("Müller"), "Müller");
    assert.equal(csvCell("=HYPERLINK(1)"), "'=HYPERLINK(1)");
    assert.equal(csvCell("a;b"), '"a;b"');
    assert.equal(csvCell('Sorte "Lemon"'), '"Sorte ""Lemon"""');
  });
});
