package de.greenganja.growguardian.grow.ui

import de.greenganja.growguardian.grow.GrowBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlantTest {
    @Test
    fun `Pflanze in der Box`() {
        val empty = GrowBox(id = "b", name = "Zelt")
        assertFalse(empty.hasPlant)
        assertFalse(empty.copy(strain = "  ").hasPlant)
        assertTrue(empty.copy(strain = "Amnesia").hasPlant)
        assertTrue(empty.copy(plantedAt = "2026-10-01").hasPlant)
        assertEquals("Amnesia", empty.copy(strain = "Amnesia").plantLabel())
        assertEquals("Pflanze ohne Sorte", empty.copy(plantedAt = "2026-10-01").plantLabel())
    }
}
