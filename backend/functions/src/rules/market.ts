/**
 * Club-Marktplatz als reine Funktionen.
 *
 * - Zubehör: Mitglieder untereinander (Biete, Suche, Tausch, Verschenken). Kein Cannabis.
 * - Vermehrungsmaterial (Samen, Stecklinge): nach § 20 KCanG nur die Anbauvereinigung, persönlich im Club,
 *   mit Ausweiskontrolle, höchstens 7 Samen, 5 Stecklinge oder zusammen 5 je Person und Kalendermonat.
 *   Mitglieder fragen an, das Team gibt aus und dokumentiert. Keine Rechtsberatung; Stand Oktober 2026.
 */

export const LISTING_TYPES = ["offer", "wanted", "swap", "free"] as const;
export const CATEGORIES = ["light", "tent", "air", "pots", "tech", "tools", "other"] as const;
export const CONDITIONS = ["new", "good", "used", "defect"] as const;
export const MATERIALS = ["seed", "cutting"] as const;

export type ListingType = (typeof LISTING_TYPES)[number];
export type Material = (typeof MATERIALS)[number];

/** Grenzen nach § 20 Abs. 3 KCanG je Person und Kalendermonat. */
export const MAX_SEEDS_PER_MONTH = 7;
export const MAX_CUTTINGS_PER_MONTH = 5;
export const MAX_MIXED_PER_MONTH = 5;

export const LISTING_VALID_DAYS = 60;
export const MAX_OPEN_LISTINGS_PER_MEMBER = 10;
export const MAX_PRICE_EUR = 10_000;
/** Ab so vielen Meldungen verschwindet ein Inserat, bis das Team entscheidet. */
export const REPORTS_TO_HIDE = 3;

/**
 * Wörter, die auf Cannabis selbst hinweisen. Zubehör-Inserate mit diesen Wörtern werden abgelehnt.
 * Bewusst grob: lieber eine Rückfrage als ein verbotenes Angebot.
 */
const FORBIDDEN = [
  "gras", "weed", "blüte", "blüten", "bud", "buds", "haschisch", "hasch", "haze", "kush", "thc", "harz", "resin",
  "joint", "dope", "marihuana", "marijuana", "ganja", "samen", "seed", "seeds", "steckling", "stecklinge", "clone", "clones",
  "mutterpflanze", "edible", "edibles", "wax", "shatter", "rosin", "gramm",
];

function words(text: string): string[] {
  return text.toLowerCase().normalize("NFC").split(/[^\p{L}\p{N}]+/u).filter(Boolean);
}

/** Erstes verbotenes Wort oder null. */
export function forbiddenWord(text: string): string | null {
  const found = words(text).find((w) => FORBIDDEN.includes(w));
  return found ?? null;
}

export interface GearInput {
  kind: "gear";
  type: ListingType;
  category: (typeof CATEGORIES)[number];
  condition: (typeof CONDITIONS)[number] | null;
  title: string;
  description: string;
  /** In Euro; null bei Suche, Tausch, Verschenken oder "Preis auf Anfrage". */
  priceEur: number | null;
}

export interface PropagationInput {
  kind: "propagation";
  material: Material;
  strain: string;
  description: string;
  /** Verfügbare Stückzahl. */
  stock: number;
}

const clean = (v: unknown, max: number): string | null => {
  if (typeof v !== "string") return null;
  const t = v.trim().replace(/[ \t]+/g, " ");
  return t.length <= max ? t : null;
};

export function parseGear(data: unknown): GearInput | string {
  const d = (data ?? {}) as Record<string, unknown>;
  if (!LISTING_TYPES.includes(d.type as never)) return "Bitte Art wählen: Biete, Suche, Tausch oder Verschenken.";
  if (!CATEGORIES.includes(d.category as never)) return "Bitte eine Kategorie wählen.";
  const condition = d.condition === undefined || d.condition === null ? null : d.condition;
  if (condition !== null && !CONDITIONS.includes(condition as never)) return "Unbekannter Zustand.";
  const title = clean(d.title, 60);
  if (!title || title.length < 3) return "Der Titel muss 3 bis 60 Zeichen haben.";
  const description = clean(d.description ?? "", 1000);
  if (description === null) return "Die Beschreibung darf höchstens 1000 Zeichen haben.";
  const bad = forbiddenWord(`${title} ${description}`);
  if (bad) {
    return `„${bad}“ ist im Marktplatz nicht erlaubt. Hier gibt es nur Zubehör; Samen und Stecklinge bietet nur der Club selbst an.`;
  }
  let priceEur: number | null = null;
  if (d.type === "offer" && d.priceEur !== undefined && d.priceEur !== null) {
    if (typeof d.priceEur !== "number" || !Number.isFinite(d.priceEur) || d.priceEur < 0 || d.priceEur > MAX_PRICE_EUR) {
      return `Der Preis muss zwischen 0 und ${MAX_PRICE_EUR} € liegen.`;
    }
    priceEur = Math.round(d.priceEur * 100) / 100;
  }
  return {
    kind: "gear",
    type: d.type as ListingType,
    category: d.category as GearInput["category"],
    condition: condition as GearInput["condition"],
    title,
    description,
    priceEur,
  };
}

export function parsePropagation(data: unknown): PropagationInput | string {
  const d = (data ?? {}) as Record<string, unknown>;
  if (!MATERIALS.includes(d.material as never)) return "Bitte Samen oder Stecklinge wählen.";
  const strain = clean(d.strain, 60);
  if (!strain || strain.length < 2) return "Bitte die Sorte angeben (2 bis 60 Zeichen).";
  const description = clean(d.description ?? "", 500);
  if (description === null) return "Die Beschreibung darf höchstens 500 Zeichen haben.";
  if (typeof d.stock !== "number" || !Number.isInteger(d.stock) || d.stock < 1 || d.stock > 1000) {
    return "Bitte die verfügbare Stückzahl angeben (1 bis 1000).";
  }
  return { kind: "propagation", material: d.material as Material, strain, description, stock: d.stock };
}

export interface MaterialCount {
  material: Material;
  count: number;
}

export type PropagationCheck =
  | { ok: true; seedsLeft: number; cuttingsLeft: number }
  | { ok: false; reason: "propagation-limit"; seedsLeft: number; cuttingsLeft: number };

/**
 * Prüft eine Anfrage gegen die Monatsgrenzen. [existing] = schon ausgegebene und offen angefragte Mengen
 * desselben Monats. Nur Samen: bis 7. Nur Stecklinge: bis 5. Gemischt: zusammen bis 5.
 */
export function checkPropagation(existing: MaterialCount[], request: MaterialCount): PropagationCheck {
  const all = [...existing, request];
  const seeds = all.filter((c) => c.material === "seed").reduce((s, c) => s + c.count, 0);
  const cuttings = all.filter((c) => c.material === "cutting").reduce((s, c) => s + c.count, 0);
  const left = (s: number, c: number) => {
    if (c === 0) return { seedsLeft: Math.max(0, MAX_SEEDS_PER_MONTH - s), cuttingsLeft: Math.max(0, MAX_MIXED_PER_MONTH - s) };
    if (s === 0) return { seedsLeft: Math.max(0, MAX_MIXED_PER_MONTH - c), cuttingsLeft: Math.max(0, MAX_CUTTINGS_PER_MONTH - c) };
    const rest = Math.max(0, MAX_MIXED_PER_MONTH - s - c);
    return { seedsLeft: rest, cuttingsLeft: rest };
  };
  const ok =
    request.count >= 1 &&
    (cuttings === 0 ? seeds <= MAX_SEEDS_PER_MONTH : seeds === 0 ? cuttings <= MAX_CUTTINGS_PER_MONTH : seeds + cuttings <= MAX_MIXED_PER_MONTH);
  if (ok) return { ok: true, ...left(seeds, cuttings) };
  const before = existing;
  const s0 = before.filter((c) => c.material === "seed").reduce((s, c) => s + c.count, 0);
  const c0 = before.filter((c) => c.material === "cutting").reduce((s, c) => s + c.count, 0);
  return { ok: false, reason: "propagation-limit", ...left(s0, c0) };
}

/** Wer den Status eines Inserats ändern darf: der Anbieter (offen, reserviert, erledigt) oder das Team (auch entfernen). */
export function mayChangeListing(uid: string, sellerUid: string, isStaff: boolean, status: string): boolean {
  if (status === "removed") return isStaff;
  if (!["open", "reserved", "closed"].includes(status)) return false;
  return uid === sellerUid || isStaff;
}
