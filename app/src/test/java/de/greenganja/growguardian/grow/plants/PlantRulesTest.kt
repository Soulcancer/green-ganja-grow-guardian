package de.greenganja.growguardian.grow.plants

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlantRulesTest {
    private val today = "2026-11-05"

    @Test
    fun `gueltige und ungueltige Pflanzen`() {
        val ok = Plant(name = "Pflanze 1", strain = "Amnesia", startedAt = "2026-10-01", floweringAt = "2026-10-20")
        assertNull(PlantRules.validate(ok, today))
        assertNotNull(PlantRules.validate(ok.copy(name = " "), today))
        assertNotNull(PlantRules.validate(ok.copy(startedAt = "2026-13-01"), today))
        assertNotNull(PlantRules.validate(ok.copy(startedAt = "2026-11-06"), today)) // Zukunft
        assertNotNull(PlantRules.validate(ok.copy(floweringAt = "2026-09-01"), today)) // Blüte vor Start
        assertNotNull(PlantRules.validate(ok.copy(harvestedAt = "2026-10-10"), today)) // Ernte vor Blüte
        assertNull(PlantRules.validate(ok.copy(harvestedAt = "2026-11-01", yieldDryG = 85.0), today))
        assertNotNull(PlantRules.validate(ok.copy(yieldDryG = -1.0, harvestedAt = today), today))
        assertNotNull(PlantRules.validate(Plant(name = "Neu"), today, countInBox = PlantRules.MAX_PLANTS_PER_BOX))
    }

    @Test
    fun `Tage, Status und Namen`() {
        val plant = Plant(id = "p", name = "Lady A", strain = "Amnesia", origin = PlantOrigin.CLONE, autoflower = true, startedAt = "2026-10-01", floweringAt = "2026-10-25")
        assertEquals("Tag 36 · Blüte Tag 12", PlantRules.status(plant, today))
        assertEquals("Amnesia · Steckling · Automatik", PlantRules.details(plant))
        val harvested = plant.copy(harvestedAt = "2026-11-03", yieldDryG = 85.0)
        assertEquals("Geerntet 03.11.2026 · nach 34 Tagen · 85 g trocken", PlantRules.status(harvested, today))
        assertEquals("Ohne Starttag", PlantRules.status(Plant(name = "X"), today))
        assertEquals("Pflanze 3", PlantRules.nextName(listOf(Plant(name = "Pflanze 1"), Plant(name = "Lady A"))))
        assertEquals("Pflanze 2", PlantRules.nextName(listOf(Plant(name = "Pflanze 1"))))
    }

    @Test
    fun `KCanG-Hinweis und alte Box-Daten`() {
        val growing = mapOf("a" to listOf(Plant(name = "1"), Plant(name = "2")), "b" to listOf(Plant(name = "3"), Plant(name = "4", harvestedAt = "2026-11-01")))
        assertEquals(3, PlantRules.activeCount(growing))
        assertNull(PlantRules.kcangHint(3, isClub = false))
        assertTrue(PlantRules.kcangHint(4, isClub = false)!!.contains("§ 9 KCanG"))
        assertNull(PlantRules.kcangHint(40, isClub = true))
        assertEquals(Plant(name = "Pflanze 1", strain = "Amnesia", startedAt = "2026-10-01"), PlantRules.fromLegacy(" Amnesia ", "2026-10-01"))
        assertNull(PlantRules.fromLegacy(null, null))
        assertNull(PlantRules.fromLegacy(" ", null))
    }
}
