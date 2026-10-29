package de.greenganja.growguardian.club

import de.greenganja.growguardian.grow.BoxStatus
import de.greenganja.growguardian.grow.DeviceKind
import de.greenganja.growguardian.grow.GrowBox
import de.greenganja.growguardian.grow.GrowBoxState
import de.greenganja.growguardian.grow.GrowMedium
import de.greenganja.growguardian.grow.GrowPhase
import de.greenganja.growguardian.grow.Metric
import de.greenganja.growguardian.grow.SensorDevice
import de.greenganja.growguardian.grow.TargetRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClubBoxRulesTest {
    private val now = 1_800_000_000_000L

    private fun box(id: String = "b1", name: String = "Zelt", latest: ClubBoxLatest? = null) = ClubBox(
        id = id, name = name, phase = GrowPhase.FLOWERING, medium = GrowMedium.SOIL, leafOffsetC = -2.0,
        targets = mapOf(Metric.AIR_HUMIDITY to TargetRange(40.0, 50.0)), keeperUid = "k", keeperName = "Kim",
        sourceBoxId = "s1", latest = latest,
    )

    @Test
    fun `Status aus den hochgeladenen Werten`() {
        assertEquals(BoxStatus.NO_DATA, ClubBoxRules.status(box(), now))
        val fresh = ClubBoxLatest(now - 10 * 60_000, true, mapOf(Metric.AIR_TEMPERATURE to 24.0, Metric.AIR_HUMIDITY to 45.0))
        assertEquals(BoxStatus.OK, ClubBoxRules.status(box(latest = fresh), now))
        // Eigene Zielwerte der Box gelten: 58 % liegt über 50 %
        val humid = fresh.copy(values = mapOf(Metric.AIR_HUMIDITY to 58.0))
        assertEquals(BoxStatus.WARNING, ClubBoxRules.status(box(latest = humid), now))
        assertEquals(BoxStatus.OFFLINE, ClubBoxRules.status(box(latest = fresh.copy(online = false)), now))
        // Älter als eine Stunde: das Handy des Betreuers lädt nicht mehr hoch
        assertEquals(BoxStatus.OFFLINE, ClubBoxRules.status(box(latest = fresh.copy(atMillis = now - 61 * 60_000)), now))
    }

    @Test
    fun `Alter verstaendlich`() {
        assertEquals("gerade eben", ClubBoxRules.ageLabel(now - 30_000, now))
        assertEquals("vor 15 Min.", ClubBoxRules.ageLabel(now - 15 * 60_000, now))
        assertEquals("vor 3 Std.", ClubBoxRules.ageLabel(now - 3 * 3_600_000, now))
        assertEquals("vor 2 Tagen", ClubBoxRules.ageLabel(now - 49 * 3_600_000, now))
    }

    @Test
    fun `Angepinnte zuerst, ausgeblendete nur auf Wunsch`() {
        val boxes = listOf(box("a", "Anzucht"), box("b", "Blüte"), box("c", "Clone"))
        assertEquals(listOf("c", "a"), ClubBoxRules.arrange(boxes, pinned = setOf("c"), hidden = setOf("b"), showHidden = false).map { it.id })
        assertEquals(listOf("c", "a", "b"), ClubBoxRules.arrange(boxes, pinned = setOf("c"), hidden = setOf("b"), showHidden = true).map { it.id })
    }

    @Test
    fun `Upload nur mit Werten`() {
        val growBox = GrowBox(id = "s1", name = "Zelt", deviceIds = listOf("d1"))
        val device = SensorDevice("d1", "Klima", true, DeviceKind.CLIMATE, mapOf(Metric.AIR_TEMPERATURE to 24.0, Metric.AIR_HUMIDITY to 50.0))
        val latest = ClubBoxRules.latestOf(GrowBoxState.of(growBox, mapOf("d1" to device)), now)!!
        assertTrue(latest.online)
        assertEquals(24.0, latest.values[Metric.AIR_TEMPERATURE]!!, 0.0)
        assertTrue(Metric.VPD in latest.values)
        assertNull(ClubBoxRules.latestOf(GrowBoxState.of(growBox, emptyMap()), now))
    }
}
