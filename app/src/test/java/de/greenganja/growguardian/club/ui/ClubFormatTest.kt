package de.greenganja.growguardian.club.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ClubFormatTest {

    @Test
    fun `formatiert Datum, Monat, Gramm und Codes deutsch`() {
        assertEquals("29.10.2026", ClubFormat.date("2026-10-29"))
        assertEquals("Oktober 2026", ClubFormat.month("2026-10"))
        assertEquals("März 2027", ClubFormat.month("2027-03"))
        assertEquals("10 g", ClubFormat.grams(10.0))
        assertEquals("7,5 g", ClubFormat.grams(7.5))
        assertEquals("16,5 %", ClubFormat.percent(16.5))
        assertEquals("ABCD-EFGH", ClubFormat.code("ABCDEFGH"))
    }

    @Test
    fun `bestimmt heute in deutscher Zeit`() {
        // 29.10.2026, 23:30 UTC ist in Deutschland schon der 30.
        assertEquals("2026-10-30", ClubFormat.todayIso(1_793_316_600_000L))
        assertEquals("2026-10", ClubFormat.currentMonth(1_793_000_000_000L))
    }

    @Test
    fun `rechnet mit Monaten und Tagen`() {
        assertEquals("2025-12", ClubFormat.shiftMonth("2026-01", -1))
        assertEquals("2027-01", ClubFormat.shiftMonth("2026-12", 1))
        assertEquals("2026-11-28", ClubFormat.addDays("2026-10-29", 30))
        assertEquals("2028-03-01", ClubFormat.addDays("2028-02-28", 2))
    }

    @Test
    fun `wandelt DatePicker-Millisekunden hin und zurück`() {
        val millis = ClubFormat.utcMillisFromIso("2026-11-03")!!
        assertEquals("2026-11-03", ClubFormat.isoFromUtcMillis(millis))
    }

    @Test
    fun `liest deutsche Datumseingaben`() {
        assertEquals("1998-04-12", ClubFormat.parseGermanDate("12.04.1998"))
        assertEquals("1998-04-12", ClubFormat.parseGermanDate(" 12.4.1998 "))
        assertEquals("1998-04-12", ClubFormat.parseGermanDate("12041998"))
        assertEquals("2028-02-29", ClubFormat.parseGermanDate("29.02.2028"))
        assertEquals(null, ClubFormat.parseGermanDate("29.02.2026"))
        assertEquals(null, ClubFormat.parseGermanDate("31.04.1998"))
        assertEquals(null, ClubFormat.parseGermanDate("12.04.98"))
        assertEquals(null, ClubFormat.parseGermanDate("abc"))
    }

    @Test
    fun `liest Zahlen mit Komma oder Punkt`() {
        assertEquals(7.5, ClubFormat.parseDecimal("7,5"))
        assertEquals(7.5, ClubFormat.parseDecimal("7.5"))
        assertEquals(10.0, ClubFormat.parseDecimal("10"))
        assertEquals(null, ClubFormat.parseDecimal("-3"))
        assertEquals(null, ClubFormat.parseDecimal("7,5g"))
    }

    @Test
    fun `berechnet das Alter`() {
        assertEquals(19, ClubFormat.ageOn("2006-03-10", "2026-03-09"))
        assertEquals(20, ClubFormat.ageOn("2006-03-10", "2026-03-10"))
        assertEquals(20, ClubFormat.ageOn("2005-10-30", "2026-10-29"))
        assertEquals(21, ClubFormat.ageOn("2005-10-29", "2026-10-29"))
    }
}
