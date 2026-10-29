package de.greenganja.growguardian.grow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class GrowRulesTest {

    private fun near(expected: Double, actual: Double?, tolerance: Double = 0.01) {
        assertTrue("erwartet $expected, war $actual", actual != null && abs(expected - actual) <= tolerance)
    }

    @Test
    fun `VPD stimmt mit der Magnus-Formel`() {
        near(3.168, Vpd.saturationPressure(25.0), 0.002)
        // 25 °C Luft, 60 % rF, Blatt 2 °C kühler
        near(0.909, Vpd.leaf(25.0, 60.0, -2.0))
        // Blatt so warm wie die Luft: 40 % des Sättigungsdrucks
        near(1.267, Vpd.leaf(25.0, 60.0, 0.0))
        // Kondensation ergibt keinen negativen VPD
        assertEquals(0.0, Vpd.leaf(20.0, 100.0, -3.0), 0.0)
    }

    @Test
    fun `Startwerte passen zu Phase und Medium`() {
        val flowerSoil = DefaultTargets.forPhase(GrowPhase.FLOWERING, GrowMedium.SOIL)
        assertEquals(TargetRange(40.0, 55.0), flowerSoil[Metric.AIR_HUMIDITY])
        assertEquals(TargetRange(6.0, 7.0), flowerSoil[Metric.PH])
        assertTrue(Metric.SOIL_MOISTURE in flowerSoil)
        assertFalse(Metric.WATER_TEMPERATURE in flowerSoil)

        val vegHydro = DefaultTargets.forPhase(GrowPhase.VEGETATIVE, GrowMedium.HYDRO)
        assertEquals(TargetRange(5.5, 6.2), vegHydro[Metric.PH])
        assertTrue(Metric.WATER_TEMPERATURE in vegHydro)

        val drying = DefaultTargets.forPhase(GrowPhase.DRYING, GrowMedium.SOIL)
        assertEquals(setOf(Metric.AIR_TEMPERATURE, Metric.AIR_HUMIDITY), drying.keys)
    }

    @Test
    fun `eigene Zielwerte überschreiben die Startwerte`() {
        val box = GrowBox("b", "Zelt", GrowPhase.FLOWERING, customTargets = mapOf(Metric.AIR_HUMIDITY to TargetRange(45.0, 50.0)))
        assertEquals(TargetRange(45.0, 50.0), box.targets[Metric.AIR_HUMIDITY])
        assertEquals(TargetRange(20.0, 26.0), box.targets[Metric.AIR_TEMPERATURE])
    }

    @Test
    fun `mittelt mehrere Sensoren und berechnet den VPD`() {
        val combined = GrowEvaluation.combine(
            listOf(
                mapOf(Metric.AIR_TEMPERATURE to 24.0, Metric.AIR_HUMIDITY to 58.0),
                mapOf(Metric.AIR_TEMPERATURE to 26.0, Metric.AIR_HUMIDITY to 62.0, Metric.CO2 to 900.0),
            ),
            leafOffsetC = -2.0,
        )
        near(25.0, combined[Metric.AIR_TEMPERATURE])
        near(60.0, combined[Metric.AIR_HUMIDITY])
        near(0.909, combined[Metric.VPD])
        near(900.0, combined[Metric.CO2])
        assertEquals(Metric.AIR_TEMPERATURE, combined.keys.first())
    }

    @Test
    fun `bewertet Werte und den Zustand der Box`() {
        val targets = DefaultTargets.forPhase(GrowPhase.FLOWERING, GrowMedium.SOIL)
        val readings = GrowEvaluation.readings(mapOf(Metric.AIR_TEMPERATURE to 27.5, Metric.AIR_HUMIDITY to 50.0, Metric.POWER to 240.0), targets)
        assertEquals(MetricStatus.HIGH, readings.first { it.metric == Metric.AIR_TEMPERATURE }.status)
        assertEquals(MetricStatus.OK, readings.first { it.metric == Metric.AIR_HUMIDITY }.status)
        assertEquals(MetricStatus.UNRATED, readings.first { it.metric == Metric.POWER }.status)

        assertEquals(BoxStatus.WARNING, GrowEvaluation.boxStatus(2, 2, readings))
        assertEquals(BoxStatus.OFFLINE, GrowEvaluation.boxStatus(2, 1, readings))
        assertEquals(BoxStatus.NO_DEVICES, GrowEvaluation.boxStatus(0, 0, emptyList()))
        assertEquals(BoxStatus.NO_DATA, GrowEvaluation.boxStatus(1, 1, emptyList()))
    }

    @Test
    fun `Box-Zustand nutzt nur erreichbare Sensoren`() {
        val box = GrowBox("b", "Zelt", GrowPhase.VEGETATIVE, deviceIds = listOf("t1", "t2"))
        val devices = mapOf(
            "t1" to SensorDevice("t1", "Klima", true, DeviceKind.CLIMATE, mapOf(Metric.AIR_TEMPERATURE to 25.0, Metric.AIR_HUMIDITY to 60.0)),
            "t2" to SensorDevice("t2", "Klima 2", false, DeviceKind.CLIMATE, mapOf(Metric.AIR_TEMPERATURE to 40.0)),
        )
        val state = GrowBoxState.of(box, devices)
        near(25.0, state.reading(Metric.AIR_TEMPERATURE)?.value)
        assertEquals(MetricStatus.OK, state.reading(Metric.VPD)?.status)
        assertEquals(BoxStatus.OFFLINE, state.status)
    }

    @Test
    fun `formatiert deutsch`() {
        assertEquals("23,5 °C", Metric.AIR_TEMPERATURE.format(23.46))
        assertEquals("6,12", Metric.PH.format(6.123))
        assertEquals("1,05 kPa", Metric.VPD.format(1.049))
        assertEquals("22,0–28,0 °C", TargetRange(22.0, 28.0).format(Metric.AIR_TEMPERATURE))
    }
}
