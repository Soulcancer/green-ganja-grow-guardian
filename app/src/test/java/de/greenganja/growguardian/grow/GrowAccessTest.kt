package de.greenganja.growguardian.grow

import de.greenganja.growguardian.billing.FarmerTier
import de.greenganja.growguardian.grow.ui.parseNumber
import de.greenganja.growguardian.tuya.TuyaDeviceTestAccess
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GrowAccessTest {

    private fun box(id: String, vararg devices: String) = GrowBox(id, "Box $id", deviceIds = devices.toList())

    @Test
    fun `Freemium überwacht die älteste Box und 5 Sensoren`() {
        val boxes = listOf(box("a", "1", "2", "3", "4", "5", "6"), box("b", "7"))
        val access = GrowAccess.of(boxes, FarmerTier.FREEMIUM.features)
        assertEquals(setOf("a"), access.activeBoxIds)
        assertTrue(access.isLocked(boxes[1]))
        assertEquals(setOf("1", "2", "3", "4", "5"), access.activeDeviceIds)
        assertFalse(access.isMonitored("6"))
        assertFalse(access.isMonitored("7"))
        assertFalse(access.canAddBox)
        assertEquals(1, access.lockedBoxCount)
        assertEquals(6, access.assignedDeviceCount)
    }

    @Test
    fun `Premium zählt Sensoren über alle aktiven Boxen`() {
        val boxes = listOf(box("a", "1", "2"), box("b", "3"))
        val access = GrowAccess.of(boxes, FarmerTier.PREMIUM.features)
        assertTrue(access.canAddBox)
        assertEquals(28, access.maxDevicesFor(boxes[1], boxes))
        assertEquals(27, access.maxDevicesFor(null, boxes))
    }

    @Test
    fun `Social Club ist unbegrenzt`() {
        val boxes = (1..12).map { box("b$it", "d$it") }
        val access = GrowAccess.of(boxes, FarmerTier.SOCIAL_CLUB.features)
        assertEquals(12, access.activeBoxIds.size)
        assertEquals(null, access.maxDevicesFor(null, boxes))
        assertTrue(access.canAddBox)
    }

    @Test
    fun `Box wird für die Cloud Function richtig verpackt`() {
        val payload = GrowBox(
            id = "",
            name = "  Zelt  ",
            phase = GrowPhase.FLOWERING,
            medium = GrowMedium.COCO,
            deviceIds = listOf("x"),
            customTargets = mapOf(Metric.AIR_HUMIDITY to TargetRange(45.0, 50.0)),
        ).toPayload()
        assertEquals(null, payload["boxId"])
        assertEquals("Zelt", payload["name"])
        assertEquals("flowering", payload["phase"])
        assertEquals("coco", payload["medium"])
        assertEquals(mapOf("air_humidity" to mapOf("min" to 45.0, "max" to 50.0)), payload["targets"])
    }

    @Test
    fun `Live-Update ändert nur die gemeldeten Datenpunkte`() {
        val sensor = TuyaDeviceTestAccess.climateWithUpdate(mapOf("1" to 241))
        assertEquals(24.1, sensor.values[Metric.AIR_TEMPERATURE]!!, 1e-9)
        assertEquals(55.0, sensor.values[Metric.AIR_HUMIDITY]!!, 1e-9)
        assertEquals(DeviceKind.CLIMATE, sensor.kind)
    }

    @Test
    fun `liest Zahlen mit Komma`() {
        assertEquals(6.2, parseNumber(" 6,2 ")!!, 1e-9)
        assertEquals(-2.5, parseNumber("-2.5")!!, 1e-9)
        assertEquals(null, parseNumber("abc"))
    }
}
