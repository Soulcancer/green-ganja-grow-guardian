import { FieldValue, Timestamp, type QueryDocumentSnapshot } from "firebase-admin/firestore";
import { getMessaging } from "firebase-admin/messaging";
import * as logger from "firebase-functions/logger";
import { onSchedule } from "firebase-functions/v2/scheduler";
import { RC_SECRET_KEY, REGION } from "./config";
import { db } from "./firebase";
import { walletFrom } from "./rules/coins";
import { sortBoxes, TIER_LIMITS, type Tier } from "./rules/grow";
import {
  berlinClock, combine, effectiveTargets, evaluate, issues, readDevice, roundValues, scheduleWantsOn, slotKey, thresholdDecision,
  type AlarmEntry, type DpSpec, type MetricId, type Range,
} from "./rules/monitor";
import { cachedTier } from "./tier";
import { deviceDetails, deviceSpecs, sendCommand, TUYA_CLIENT_ID, TUYA_CLIENT_SECRET, userInfo, type TuyaDevice } from "./tuyaCloud";

/**
 * Server-Überwachung alle 5 Minuten, auch wenn kein Handy online ist:
 * 1. liest über die Tuya Cloud die Sensoren aller aktiven Boxen (mit Prüfung, dass die Geräte dem Nutzer gehören),
 * 2. schreibt den Verlauf (gleiches Format wie die App),
 * 3. prüft die Alarme und schickt sie per Push (Firebase Cloud Messaging),
 * 4. schaltet Automationen, die auf den Server gestellt sind (sceneIds enthält "server").
 * Damit das Handy nicht doppelt meldet, steht der letzte erfolgreiche Lauf in users/{uid}/serverState/monitor.
 */
const SPEC_MAX_AGE_MS = 7 * 24 * 60 * 60 * 1000;
const OWNER_MAX_AGE_MS = 24 * 60 * 60 * 1000;
const DAY_MS = 24 * 60 * 60 * 1000;

interface DeviceInfo {
  device: TuyaDevice;
  specs: DpSpec[];
}

/** Gehört das Gerät diesem Nutzer? Ergebnis 24 Std. in tuyaDevices/{id} zwischengespeichert. */
async function ownedDevice(firebaseUid: string, deviceId: string): Promise<DeviceInfo | null> {
  const cacheRef = db().doc(`tuyaDevices/${deviceId.replace(/[^A-Za-z0-9_-]/g, "_")}`);
  const cache = (await cacheRef.get()).data() ?? {};
  const device = await deviceDetails(deviceId);
  let owner = cache.firebaseUid as string | undefined;
  const ownerFresh = (cache.ownerCheckedAt as Timestamp | undefined)?.toMillis() ?? 0;
  if (!owner || cache.tuyaUid !== device.uid || Date.now() - ownerFresh > OWNER_MAX_AGE_MS) {
    owner = device.uid ? (await userInfo(device.uid)).username : undefined;
    await cacheRef.set({ firebaseUid: owner ?? null, tuyaUid: device.uid ?? null, ownerCheckedAt: Timestamp.now() }, { merge: true });
  }
  if (owner !== firebaseUid) {
    logger.warn("Gerät gehört nicht zum Nutzer, übersprungen", { firebaseUid, deviceId });
    return null;
  }
  let specs = cache.specs as DpSpec[] | undefined;
  const specsAt = (cache.specsAt as Timestamp | undefined)?.toMillis() ?? 0;
  if (!specs || Date.now() - specsAt > SPEC_MAX_AGE_MS) {
    specs = await deviceSpecs(deviceId);
    await cacheRef.set({ specs, specsAt: Timestamp.now() }, { merge: true });
  }
  return { device, specs };
}

async function push(uid: string, title: string, body: string, boxId: string): Promise<void> {
  const tokensSnap = await db().collection(`users/${uid}/pushTokens`).get();
  const tokens = tokensSnap.docs.map((d) => d.id);
  if (tokens.length === 0) return;
  const result = await getMessaging().sendEachForMulticast({
    tokens,
    notification: { title, body },
    data: { boxId },
    android: { priority: "high", notification: { channelId: "grow_alarms", tag: `box-${boxId}` } },
  });
  // Abgemeldete oder ungültige Geräte entfernen
  await Promise.all(result.responses.map((r, i) =>
    !r.success && r.error && /registration-token-not-registered|invalid-registration-token/.test(r.error.code)
      ? tokensSnap.docs[i].ref.delete()
      : Promise.resolve()));
}

async function monitorUser(uid: string, boxDocs: QueryDocumentSnapshot[], now: number): Promise<void> {
  let tier: Tier;
  try {
    tier = await cachedTier(uid);
  } catch {
    return;
  }
  const limits = TIER_LIMITS[tier];
  const wallet = walletFrom((await db().doc(`wallets/${uid}`).get()).data());
  const alertsAllowed = tier !== "freemium" || wallet.alertsUntil > now;

  // Aktive Boxen und Sensoren wie in der App (älteste zuerst, bis zum Limit)
  const sorted = sortBoxes(boxDocs.map((d) => ({
    id: d.id,
    createdAt: (d.get("createdAt") as Timestamp | undefined)?.toMillis() ?? 0,
    deviceIds: (d.get("deviceIds") as string[] | undefined) ?? [],
  })));
  const active = limits.maxBoxes === null ? sorted : sorted.slice(0, limits.maxBoxes);
  let deviceBudget = limits.maxDevices ?? Number.POSITIVE_INFINITY;

  const devices = new Map<string, DeviceInfo | null>();
  const load = async (id: string) => {
    if (!devices.has(id)) {
      try {
        devices.set(id, await ownedDevice(uid, id));
      } catch (error) {
        logger.warn("Tuya-Gerät nicht lesbar", { uid, id, error: String(error) });
        devices.set(id, null);
      }
    }
    return devices.get(id) ?? null;
  };

  const stateRef = db().doc(`users/${uid}/serverState/alarms`);
  const previous = ((await stateRef.get()).get("entries") ?? {}) as Record<string, AlarmEntry>;
  const found = [];
  const boxValues = new Map<string, Partial<Record<MetricId, number>>>();
  let anyRead = false;

  for (const meta of active) {
    const doc = boxDocs.find((d) => d.id === meta.id)!;
    const box = doc.data();
    const ids = meta.deviceIds.slice(0, Math.max(0, Math.min(meta.deviceIds.length, deviceBudget)));
    deviceBudget -= ids.length;
    const perDevice: Partial<Record<MetricId, number>>[] = [];
    const offline: string[] = [];
    for (const id of ids) {
      const info = await load(id);
      if (!info) continue;
      anyRead = true;
      if (info.device.online === false) {
        offline.push(info.device.name ?? id);
        continue;
      }
      perDevice.push(readDevice(info.device.status ?? [], info.specs, info.device.category));
    }
    const values = combine(perDevice, typeof box.leafOffsetC === "number" ? box.leafOffsetC : -2);
    boxValues.set(doc.id, values);

    if (Object.keys(values).length > 0) {
      await doc.ref.collection("history").doc(new Date(now).toISOString().slice(0, 10)).set(
        { day: new Date(now).toISOString().slice(0, 10), s: { [slotKey(now)]: roundValues(values) } },
        { merge: true },
      );
    }
    if (alertsAllowed && box.alerts !== false) {
      const targets = effectiveTargets(box as { phase?: string; medium?: string; targets?: Record<string, Range> });
      found.push(...issues({ id: doc.id, name: String(box.name ?? "Box") }, values, targets, offline, new Set(Object.keys(previous))));
    }
  }

  const result = evaluate(previous, found, now);
  for (const alert of result.alerts) {
    await push(uid, alert.boxName, alert.lines.join("\n"), alert.boxId);
  }
  await stateRef.set({ entries: result.state, updatedAt: FieldValue.serverTimestamp() });

  // Automationen, die der Server ausführt
  if (tier !== "freemium") {
    const rules = await db().collection(`users/${uid}/automations`).where("sceneIds", "array-contains", "server").get();
    const { minuteOfDay, weekday } = berlinClock(now);
    for (const rule of rules.docs) {
      const r = rule.data();
      if (r.enabled === false || typeof r.actuatorDeviceId !== "string") continue;
      try {
        const actuator = await load(r.actuatorDeviceId);
        if (!actuator || actuator.device.online === false) continue;
        const status = actuator.device.status ?? [];
        const code = typeof r.actuatorCode === "string" && r.actuatorCode
          ? r.actuatorCode
          : status.find((s) => /^switch(_1)?$/.test(s.code))?.code;
        if (!code) continue;
        const current = status.find((s) => s.code === code)?.value;
        let want: boolean | null = null;
        if (r.type === "schedule") {
          want = scheduleWantsOn({ type: "schedule", onTime: r.onTime, offTime: r.offTime, days: r.days ?? "1111111" }, minuteOfDay, weekday);
        } else if (r.type === "threshold") {
          const sensor = await load(r.sensorDeviceId);
          const reading = sensor && sensor.device.online !== false
            ? readDevice(sensor.device.status ?? [], sensor.specs, sensor.device.category)[r.metric as MetricId]
            : undefined;
          want = thresholdDecision({ type: "threshold", metric: r.metric, switchOnAbove: r.switchOnAbove !== false, onAt: r.onAt, offAt: r.offAt }, reading);
        }
        if (want !== null && current !== want) {
          await sendCommand(r.actuatorDeviceId, code, want);
          await rule.ref.update({ lastSwitchedAt: FieldValue.serverTimestamp(), lastState: want, syncError: null });
        }
      } catch (error) {
        logger.warn("Automation fehlgeschlagen", { uid, rule: rule.id, error: String(error) });
        await rule.ref.update({ syncError: "Server konnte das Gerät nicht schalten" }).catch(() => undefined);
      }
    }
  }

  if (anyRead) {
    await db().doc(`users/${uid}/serverState/monitor`).set({
      lastOkAt: Timestamp.fromMillis(now),
      boxes: boxValues.size,
      expireAt: Timestamp.fromMillis(now + 30 * DAY_MS),
    });
  }
}

export const serverMonitor = onSchedule(
  {
    schedule: "every 5 minutes",
    timeZone: "Europe/Berlin",
    region: REGION,
    secrets: [TUYA_CLIENT_ID, TUYA_CLIENT_SECRET, RC_SECRET_KEY],
    timeoutSeconds: 280,
    maxInstances: 1,
  },
  async () => {
    const now = Date.now();
    const snap = await db().collectionGroup("growBoxes").get();
    // Nur eigene Boxen unter users/{uid}/growBoxes, nicht die geteilten Club-Boxen
    const byUser = new Map<string, QueryDocumentSnapshot[]>();
    for (const doc of snap.docs) {
      const owner = doc.ref.parent.parent;
      if (!owner || owner.parent.id !== "users") continue;
      byUser.set(owner.id, [...(byUser.get(owner.id) ?? []), doc]);
    }
    for (const [uid, docs] of byUser) {
      try {
        await monitorUser(uid, docs, now);
      } catch (error) {
        logger.error("Server-Überwachung fehlgeschlagen", { uid, error: String(error) });
      }
    }
  },
);
