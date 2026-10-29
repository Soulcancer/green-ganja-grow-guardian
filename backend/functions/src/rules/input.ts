/** Eingaben aus der App bereinigen. `null` heißt ungültig. */

export function requiredText(value: unknown, min: number, max: number): string | null {
  if (typeof value !== "string") return null;
  const text = value.trim().replace(/\s+/g, " ");
  return text.length >= min && text.length <= max ? text : null;
}

/** Leere oder fehlende Werte werden zu `undefined`, zu lange zu `null`. */
export function optionalText(value: unknown, max: number): string | undefined | null {
  if (value === undefined || value === null) return undefined;
  if (typeof value !== "string") return null;
  const text = value.trim().replace(/\s+/g, " ");
  if (text.length === 0) return undefined;
  return text.length <= max ? text : null;
}

/** Nutzer- oder Dokument-ID. */
export function isId(value: unknown): value is string {
  return typeof value === "string" && /^[A-Za-z0-9_-]{1,128}$/.test(value);
}

/** Zahl mit Komma wie in Deutschland üblich, z. B. "12,5". */
export function formatDecimal(value: number, digits = 1): string {
  return value.toFixed(digits).replace(".", ",");
}

/** Eine CSV-Zelle mit Semikolon als Trenner, geschützt gegen Formeln in Excel. */
export function csvCell(value: string): string {
  const safe = /^[=+\-@\t\r]/.test(value) ? `'${value}` : value;
  return /[";\r\n]/.test(safe) ? `"${safe.replace(/"/g, '""')}"` : safe;
}
