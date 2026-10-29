import assert from "node:assert/strict";
import { generateKeyPairSync, createSign, createVerify } from "node:crypto";
import { describe, it } from "node:test";
import {
  base64UrlToBuffer, checkIn, EMPTY_WALLET, grantAchievement, purchase, rewardAd, REWARDED_ADS_PER_DAY, splitSsvQuery, walletFrom,
} from "../src/rules/coins";

const DAY = 86_400_000;

describe("Coins", () => {
  it("Serie wächst nur an aufeinanderfolgenden Tagen", () => {
    let w = EMPTY_WALLET;
    const days = ["2026-10-26", "2026-10-27", "2026-10-28"];
    const rewards: number[] = [];
    for (const d of days) {
      const r = checkIn(w, d);
      assert.ok(r.ok);
      if (r.ok) { rewards.push(r.reward); w = r.wallet; }
    }
    assert.deepEqual(rewards, [5, 6, 7]);
    assert.equal(w.streak, 3);
    assert.deepEqual(checkIn(w, "2026-10-28"), { ok: false, reason: "already" });
    const gap = checkIn(w, "2026-10-30");
    assert.ok(gap.ok && gap.streak === 1 && gap.reward === 5);
  });

  it("Serie über den Monatswechsel und nach Tag 7 von vorn", () => {
    const w = { ...EMPTY_WALLET, streak: 7, lastCheckIn: "2026-10-31" };
    const r = checkIn(w, "2026-11-01");
    assert.ok(r.ok && r.streak === 8 && r.reward === 5);
    const seventh = checkIn({ ...EMPTY_WALLET, streak: 6, lastCheckIn: "2026-11-01" }, "2026-11-02");
    assert.ok(seventh.ok && seventh.reward === 20);
  });

  it("Belohnungsvideos mit Tagesgrenze", () => {
    let w = EMPTY_WALLET;
    for (let i = 0; i < REWARDED_ADS_PER_DAY; i++) {
      const r = rewardAd(w, "2026-10-29");
      assert.ok(r.ok);
      if (r.ok) w = r.wallet;
    }
    assert.equal(w.balance, 50);
    assert.deepEqual(rewardAd(w, "2026-10-29"), { ok: false, reason: "daily-cap" });
    assert.ok(rewardAd(w, "2026-10-30").ok);
  });

  it("Erfolge nur einmal", () => {
    const first = grantAchievement(EMPTY_WALLET, "first_box");
    assert.ok(first.ok && first.coins === 20);
    if (first.ok) assert.deepEqual(grantAchievement(first.wallet, "first_box"), { ok: false, reason: "already" });
    assert.deepEqual(grantAchievement(EMPTY_WALLET, "hack"), { ok: false, reason: "unknown" });
  });

  it("Shop: Guthaben, Verlängerung, schon enthalten", () => {
    const now = 1_800_000_000_000;
    const rich = { ...EMPTY_WALLET, balance: 100 };
    const a = purchase(rich, "ad_free_1d", now);
    assert.ok(a.ok && a.until === now + DAY && a.wallet.balance === 60);
    if (a.ok) {
      const b = purchase(a.wallet, "ad_free_1d", now + 1000);
      assert.ok(b.ok && b.until === now + 2 * DAY);
    }
    assert.deepEqual(purchase({ ...EMPTY_WALLET, balance: 10 }, "ad_free_1d", now), { ok: false, reason: "insufficient" });
    assert.deepEqual(purchase(rich, "alerts_3d", now, 0, true), { ok: false, reason: "not-needed" });
    assert.deepEqual(purchase(rich, "gold", now), { ok: false, reason: "unknown" });
    const boost = purchase(rich, "boost_listing", now, now + DAY);
    assert.ok(boost.ok && boost.until === now + 4 * DAY);
  });

  it("liest Wallet-Dokumente robust", () => {
    const w = walletFrom({ balance: 12, achievements: ["x", 3], adFreeUntil: { toMillis: () => 99 } });
    assert.equal(w.balance, 12);
    assert.deepEqual(w.achievements, ["x"]);
    assert.equal(w.adFreeUntil, 99);
    assert.equal(walletFrom(undefined).streak, 0);
  });

  it("SSV: Signatur über die Query bis vor signature prüfen", () => {
    const { privateKey, publicKey } = generateKeyPairSync("ec", { namedCurve: "prime256v1" });
    const message = "ad_network=5450213213286189855&ad_unit=123&custom_data=x&reward_amount=10&reward_item=coins&timestamp=1&transaction_id=t1&user_id=uid1";
    const sig = createSign("sha256").update(message).sign(privateKey).toString("base64url");
    const parts = splitSsvQuery(`?${message}&signature=${sig}&key_id=3335741209`);
    assert.ok(parts);
    if (!parts) return;
    assert.equal(parts.message, message);
    assert.equal(parts.keyId, "3335741209");
    const ok = createVerify("sha256").update(parts.message).verify(publicKey, base64UrlToBuffer(parts.signature));
    assert.equal(ok, true);
    const tampered = createVerify("sha256").update(parts.message.replace("uid1", "uid2")).verify(publicKey, base64UrlToBuffer(parts.signature));
    assert.equal(tampered, false);
    assert.equal(splitSsvQuery("user_id=1"), null);
  });
});
