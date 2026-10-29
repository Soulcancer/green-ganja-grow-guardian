package de.greenganja.growguardian.club.ui

import java.util.Calendar
import java.util.TimeZone

/**
 * Datums- und Zahlenformate für die Club-Oberflächen.
 * Bewusst ohne java.time, damit es ab API 23 ohne Desugaring läuft.
 * Daten werden intern als "YYYY-MM-DD" geführt, wie im Backend.
 */
object ClubFormat {

    private val BERLIN: TimeZone = TimeZone.getTimeZone("Europe/Berlin")
    private val UTC: TimeZone = TimeZone.getTimeZone("UTC")
    private val MONTHS = listOf(
        "Januar", "Februar", "März", "April", "Mai", "Juni",
        "Juli", "August", "September", "Oktober", "November", "Dezember",
    )
    private val ISO_DATE = Regex("""^(\d{4})-(\d{2})-(\d{2})$""")
    private val ISO_MONTH = Regex("""^(\d{4})-(\d{2})$""")

    /** "2026-10-29" → "29.10.2026" */
    fun date(iso: String): String {
        val match = ISO_DATE.matchEntire(iso) ?: return iso
        val (year, month, day) = match.destructured
        return "$day.$month.$year"
    }

    /** "2026-10" → "Oktober 2026" */
    fun month(isoMonth: String): String {
        val match = ISO_MONTH.matchEntire(isoMonth) ?: return isoMonth
        val (year, month) = match.destructured
        return MONTHS.getOrNull(month.toInt() - 1)?.let { "$it $year" } ?: isoMonth
    }

    /** 10.0 → "10 g", 7.5 → "7,5 g" */
    fun grams(value: Double): String = "${decimal(value)} g"

    /** 16.5 → "16,5 %" */
    fun percent(value: Double): String = "${decimal(value)} %"

    fun decimal(value: Double): String {
        val tenths = Math.round(value * 10)
        val whole = tenths / 10
        val fraction = Math.abs(tenths % 10)
        return if (fraction == 0L) whole.toString() else "$whole,$fraction"
    }

    /** "ABCDEFGH" → "ABCD-EFGH" */
    fun code(code: String): String = code.chunked(4).joinToString("-")

    /** Zeitpunkt als deutsches Datum, z. B. für Ablaufdaten. */
    fun dateOf(millis: Long): String = date(todayIso(millis))

    /** Heute in Deutschland als "YYYY-MM-DD". */
    fun todayIso(nowMillis: Long = System.currentTimeMillis()): String =
        Calendar.getInstance(BERLIN).apply { timeInMillis = nowMillis }.toIso()

    /** Aktueller Monat als "YYYY-MM". */
    fun currentMonth(nowMillis: Long = System.currentTimeMillis()): String = todayIso(nowMillis).substring(0, 7)

    /** Monat verschieben: ("2026-01", -1) → "2025-12" */
    fun shiftMonth(isoMonth: String, delta: Int): String {
        val match = ISO_MONTH.matchEntire(isoMonth) ?: return isoMonth
        val (year, month) = match.destructured
        val index = year.toInt() * 12 + (month.toInt() - 1) + delta
        return "%04d-%02d".format(index / 12, index % 12 + 1)
    }

    fun addDays(iso: String, days: Int): String {
        val calendar = utcCalendar(iso) ?: return iso
        calendar.add(Calendar.DAY_OF_MONTH, days)
        return calendar.toIso()
    }

    /** Der DatePicker arbeitet mit Mitternacht UTC des gewählten Tages. */
    fun isoFromUtcMillis(utcMillis: Long): String =
        Calendar.getInstance(UTC).apply { timeInMillis = utcMillis }.toIso()

    fun utcMillisFromIso(iso: String): Long? = utcCalendar(iso)?.timeInMillis

    /**
     * Deutsches Datum in "YYYY-MM-DD" umwandeln, null bei ungültiger Eingabe.
     * Akzeptiert "12.04.1998", "12.4.1998", "12-04-1998" und "12041998".
     */
    fun parseGermanDate(input: String): String? {
        val text = input.trim()
        val parts = when {
            Regex("""^\d{8}$""").matches(text) -> listOf(text.substring(0, 2), text.substring(2, 4), text.substring(4))
            else -> text.split('.', '-', '/', ' ').filter { it.isNotEmpty() }
        }
        if (parts.size != 3 || parts.any { part -> part.any { !it.isDigit() } }) return null
        val day = parts[0].toInt()
        val month = parts[1].toInt()
        val year = parts[2].toInt()
        if (parts[2].length != 4 || month !in 1..12 || day !in 1..daysInMonth(year, month)) return null
        return "%04d-%02d-%02d".format(year, month, day)
    }

    /** "7,5" oder "7.5" → 7.5, null bei ungültiger Eingabe. */
    fun parseDecimal(input: String): Double? =
        input.trim().replace(',', '.').takeIf { Regex("""^\d+(\.\d+)?$""").matches(it) }?.toDouble()

    /** Vollendete Lebensjahre am Stichtag, beide als "YYYY-MM-DD". */
    fun ageOn(birthIso: String, dateIso: String): Int? {
        val birth = ISO_DATE.matchEntire(birthIso)?.destructured ?: return null
        val date = ISO_DATE.matchEntire(dateIso)?.destructured ?: return null
        val (by, bm, bd) = birth
        val (y, m, d) = date
        val hadBirthday = m.toInt() > bm.toInt() || (m.toInt() == bm.toInt() && d.toInt() >= bd.toInt())
        return y.toInt() - by.toInt() - if (hadBirthday) 0 else 1
    }

    private fun daysInMonth(year: Int, month: Int): Int = when (month) {
        2 -> if ((year % 4 == 0 && year % 100 != 0) || year % 400 == 0) 29 else 28
        4, 6, 9, 11 -> 30
        else -> 31
    }

    private fun utcCalendar(iso: String): Calendar? {
        val match = ISO_DATE.matchEntire(iso) ?: return null
        val (year, month, day) = match.destructured
        return Calendar.getInstance(UTC).apply {
            clear()
            set(year.toInt(), month.toInt() - 1, day.toInt())
        }
    }

    private fun Calendar.toIso(): String =
        "%04d-%02d-%02d".format(get(Calendar.YEAR), get(Calendar.MONTH) + 1, get(Calendar.DAY_OF_MONTH))
}
