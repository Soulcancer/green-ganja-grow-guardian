package de.greenganja.growguardian.grow.journal

import de.greenganja.growguardian.grow.Metric
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class JournalRulesTest {
    private val today = "2026-10-30"

    @Test
    fun `gueltige und ungueltige Eintraege`() {
        assertNull(JournalRules.validate(JournalEntry(date = today, watered = true, waterMl = 500.0), today))
        assertNull(JournalRules.validate(JournalEntry(date = today, notes = "Erste Seitentriebe"), today))
        assertNotNull(JournalRules.validate(JournalEntry(date = today), today)) // leer
        assertNotNull(JournalRules.validate(JournalEntry(date = "2026-10-31", watered = true), today)) // Zukunft
        assertNotNull(JournalRules.validate(JournalEntry(date = "2026-13-01", watered = true), today))
        assertNotNull(JournalRules.validate(JournalEntry(date = today, values = mapOf(Metric.PH to 15.0)), today))
        assertNotNull(JournalRules.validate(JournalEntry(date = today, waterMl = -1.0, watered = true), today))
    }

    @Test
    fun `Grow-Tag und Woche`() {
        assertEquals(1, JournalRules.growDay("2026-10-01", "2026-10-01"))
        assertEquals(30, JournalRules.growDay("2026-10-01", "2026-10-30"))
        assertNull(JournalRules.growDay("2026-10-05", "2026-10-01"))
        assertNull(JournalRules.growDay(null, today))
        assertEquals("Woche 1, Tag 1", JournalRules.weekLabel(1))
        assertEquals("Woche 2, Tag 1", JournalRules.weekLabel(8))
        assertEquals("Woche 5, Tag 2", JournalRules.weekLabel(30))
    }

    @Test
    fun `zuletzt gegossen`() {
        val entries = listOf(
            JournalEntry(date = "2026-10-28", watered = true),
            JournalEntry(date = "2026-10-29", notes = "nur Notiz"),
            JournalEntry(date = "2026-10-25", watered = true),
        )
        assertEquals("2026-10-28", JournalRules.lastWatered(entries))
        assertNull(JournalRules.lastWatered(emptyList()))
    }
}
