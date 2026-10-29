package de.greenganja.growguardian.grow.alarm

import de.greenganja.growguardian.grow.DeviceKind
import de.greenganja.growguardian.grow.GrowBox
import de.greenganja.growguardian.grow.GrowBoxState
import de.greenganja.growguardian.grow.GrowPhase
import de.greenganja.growguardian.grow.Metric
import de.greenganja.growguardian.grow.SensorDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmRulesTest {

    private val box = GrowBox("b1", "Zelt", GrowPhase.FLOWERING, deviceIds = listOf("k", "w"))

    /** Blüte: Ziel 20–26 °C */
    private fun state(temp: Double, waterOnline: Boolean = true) = GrowBoxState.of(
        box,
        mapOf(
            "k" to SensorDevice("k", "Klima", true, DeviceKind.CLIMATE, mapOf(Metric.AIR_TEMPERATURE to temp)),
            "w" to SensorDevice("w", "Wasser", waterOnline, DeviceKind.WATER, emptyMap()),
        ),
    )

    @Test
    fun `meldet erst bei der zweiten Prüfung, deutliche Abweichung sofort`() {
        val first = AlarmRules.evaluate(emptyMap(), AlarmRules.issues(state(26.8), emptySet()), now = 0)
        assertTrue(first.alerts.isEmpty())
        val second = AlarmRules.evaluate(first.state, AlarmRules.issues(state(26.9), first.state.keys), now = 900_000)
        assertEquals(1, second.alerts.size)
        assertEquals("Temperatur zu hoch: 26,9 °C (Ziel 20,0–26,0 °C)", second.alerts.single().lines.single())

        val severe = AlarmRules.evaluate(emptyMap(), AlarmRules.issues(state(29.0), emptySet()), now = 0)
        assertEquals(1, severe.alerts.size)
    }

    @Test
    fun `erinnert nach 6 Stunden und hält Ruhe dazwischen`() {
        var result = AlarmRules.evaluate(emptyMap(), AlarmRules.issues(state(30.0), emptySet()), now = 0)
        assertEquals(1, result.alerts.size)
        result = AlarmRules.evaluate(result.state, AlarmRules.issues(state(30.0), result.state.keys), now = 3_600_000)
        assertTrue(result.alerts.isEmpty())
        result = AlarmRules.evaluate(result.state, AlarmRules.issues(state(30.0), result.state.keys), now = AlarmRules.REMIND_AFTER_MS)
        assertEquals(1, result.alerts.size)
    }

    @Test
    fun `gilt erst mit Abstand zur Grenze wieder als gut`() {
        val alarmed = AlarmRules.evaluate(emptyMap(), AlarmRules.issues(state(30.0), emptySet()), now = 0)
        // 25,9 °C liegt im Ziel, aber näher als 5 % der Spanne (0,3 °C) an der Grenze
        val nearEdge = AlarmRules.issues(state(25.9), alarmed.state.keys)
        assertEquals(1, nearEdge.size)
        val ok = AlarmRules.evaluate(alarmed.state, AlarmRules.issues(state(25.0), alarmed.state.keys), now = 1)
        assertTrue(ok.state.isEmpty())
        assertEquals(setOf("b1"), ok.resolvedBoxIds)
    }

    @Test
    fun `meldet Sensoren offline`() {
        val issues = AlarmRules.issues(state(24.0, waterOnline = false), emptySet())
        assertEquals(listOf("b1|offline|w"), issues.map { it.key })
        assertEquals("Sensor „Wasser“ ist offline", issues.single().text)
    }

    @Test
    fun `Zustand übersteht das Speichern`() {
        val state = mapOf("b1|air_temperature" to AlarmEntry(10, 2, 20), "b1|offline|w" to AlarmEntry(5, 1, null))
        assertEquals(state, AlarmRules.decode(AlarmRules.encode(state)))
        assertEquals(emptyMap<String, AlarmEntry>(), AlarmRules.decode(null))
    }
}
