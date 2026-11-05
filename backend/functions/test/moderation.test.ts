import assert from "node:assert/strict";
import { describe, it } from "node:test";
import {
  announcementProblem,
  appointProblem,
  blockedUntil,
  blockMessage,
  canAppoint,
  canModerate,
  germanDate,
  isBlocked,
  MAX_MODERATORS,
  MAX_PINNED,
  mayActOn,
  mayRemoveListing,
  parseAnnouncement,
  parseBlockDays,
  parseDecision,
  parseReason,
  roleIn,
} from "../src/rules/moderation";

describe("Moderation: Rechte", () => {
  it("moderieren dürfen Moderatoren, Team und Inhaber", () => {
    assert.equal(canModerate("member"), false);
    assert.equal(canModerate("moderator"), true);
    assert.equal(canModerate("staff"), true);
    assert.equal(canModerate("owner"), true);
    assert.equal(canModerate("unbekannt"), false);
  });

  it("ernennen dürfen nur Team und Inhaber", () => {
    assert.equal(canAppoint("moderator"), false);
    assert.equal(canAppoint("staff"), true);
    assert.equal(canAppoint("owner"), true);
  });

  it("verwarnt und gesperrt wird nur nach unten, nie man selbst", () => {
    assert.equal(mayActOn("m1", "moderator", "a", "member"), true);
    assert.equal(mayActOn("m1", "moderator", "m2", "moderator"), false);
    assert.equal(mayActOn("m1", "moderator", "s", "staff"), false);
    assert.equal(mayActOn("s", "staff", "m1", "moderator"), true);
    assert.equal(mayActOn("s", "staff", "o", "owner"), false);
    assert.equal(mayActOn("o", "owner", "s", "staff"), true);
    assert.equal(mayActOn("a", "member", "b", "member"), false);
    assert.equal(mayActOn("s", "staff", "s", "member"), false);
  });

  it("Moderatoren entfernen nur Zubehör, Club-Angebote bleiben beim Team", () => {
    assert.equal(mayRemoveListing("moderator", "gear"), true);
    assert.equal(mayRemoveListing("moderator", "propagation"), false);
    assert.equal(mayRemoveListing("staff", "propagation"), true);
    assert.equal(mayRemoveListing("member", "gear"), false);
  });

  it("liest die Rolle aus dem Club-Dokument", () => {
    assert.equal(roleIn("o", "o", ["o", "s"], ["m"]), "owner");
    assert.equal(roleIn("s", "o", ["o", "s"], ["m"]), "staff");
    assert.equal(roleIn("m", "o", ["o", "s"], ["m"]), "moderator");
    assert.equal(roleIn("x", "o", ["o", "s"], undefined), "member");
  });
});

describe("Moderation: Ernennen", () => {
  it("ernennt aktive Mitglieder bis zur Obergrenze", () => {
    assert.equal(appointProblem(true, "member", "active", 0), null);
    assert.equal(appointProblem(true, "member", "active", MAX_MODERATORS)?.reason, "moderator-limit");
    assert.equal(appointProblem(true, "member", "pending", 0)?.reason, "member-not-active");
    assert.equal(appointProblem(true, "moderator", "active", 1)?.reason, "wrong-status");
    assert.equal(appointProblem(true, "staff", "active", 1)?.reason, "wrong-status");
  });

  it("beruft nur Moderatoren ab", () => {
    assert.equal(appointProblem(false, "moderator", "active", 3), null);
    assert.equal(appointProblem(false, "member", "active", 3)?.reason, "wrong-status");
  });
});

describe("Moderation: Eingaben", () => {
  it("verlangt einen Grund mit 3 bis 300 Zeichen", () => {
    assert.equal(parseReason("  Beleidigung  im  Chat "), "Beleidigung im Chat");
    assert.equal(parseReason("ok"), null);
    assert.equal(parseReason("x".repeat(301)), null);
    assert.equal(parseReason(5), null);
  });

  it("erlaubt nur feste Sperrdauern", () => {
    assert.equal(parseBlockDays(7), 7);
    assert.equal(parseBlockDays(30), 30);
    assert.equal(parseBlockDays(2), null);
    assert.equal(parseBlockDays("7"), null);
  });

  it("kennt nur Freigeben und Entfernen", () => {
    assert.equal(parseDecision("restore"), "restore");
    assert.equal(parseDecision("remove"), "remove");
    assert.equal(parseDecision("delete"), null);
  });

  it("prüft Ankündigungen", () => {
    assert.deepEqual(parseAnnouncement({ title: " Ausgabe Samstag ", text: "Fällt  aus.\n\n\n\nBis bald", pinned: true }), {
      title: "Ausgabe Samstag",
      text: "Fällt aus.\n\nBis bald",
      pinned: true,
    });
    assert.deepEqual(parseAnnouncement({ title: "Treffen", text: "Am Freitag" }), { title: "Treffen", text: "Am Freitag", pinned: false });
    assert.equal(typeof parseAnnouncement({ title: "x", text: "Text" }), "string");
    assert.equal(typeof parseAnnouncement({ title: "Titel", text: "   " }), "string");
    assert.equal(typeof parseAnnouncement({ title: "Titel", text: "a".repeat(1001) }), "string");
    assert.equal(typeof parseAnnouncement({ title: "Titel", text: "Text", pinned: "ja" }), "string");
  });

  it("begrenzt Anzahl und angeheftete Ankündigungen", () => {
    assert.equal(announcementProblem(0, 0, true), null);
    assert.equal(typeof announcementProblem(20, 0, false), "string");
    assert.equal(typeof announcementProblem(5, MAX_PINNED, true), "string");
    assert.equal(announcementProblem(5, MAX_PINNED, false), null);
  });
});

describe("Moderation: Sperre", () => {
  it("rechnet das Ende und prüft, ob sie noch läuft", () => {
    const now = Date.UTC(2026, 10, 5, 10);
    const until = blockedUntil(now, 7);
    assert.equal(until - now, 7 * 24 * 60 * 60 * 1000);
    assert.equal(isBlocked(until, now), true);
    assert.equal(isBlocked(until, until + 1), false);
    assert.equal(isBlocked(null, now), false);
    assert.equal(isBlocked(undefined, now), false);
  });

  it("nennt Datum und Grund in deutscher Zeit", () => {
    // 23:30 UTC am 11.11. ist in Berlin schon der 12.11.
    assert.equal(germanDate(Date.UTC(2026, 10, 11, 23, 30)), "12.11.2026");
    assert.equal(blockMessage(Date.UTC(2026, 10, 12, 9), "Spam"), "Du bist im Marktplatz bis 12.11.2026 gesperrt. Grund: Spam");
    assert.equal(blockMessage(Date.UTC(2026, 10, 12, 9), null), "Du bist im Marktplatz bis 12.11.2026 gesperrt.");
  });
});
