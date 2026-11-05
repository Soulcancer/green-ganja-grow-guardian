package de.greenganja.growguardian.grow.light

import de.greenganja.growguardian.grow.GrowBox
import de.greenganja.growguardian.grow.GrowPhase
import de.greenganja.growguardian.grow.Metric
import de.greenganja.growguardian.grow.history.ChartPoint
import de.greenganja.growguardian.grow.history.HistorySample
import de.greenganja.growguardian.grow.ui.HistoryUiState
import de.greenganja.growguardian.grow.ui.LightHistoryView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LightHistoryTest {
    private val hour = 60 * 60 * 1000L
    private val day = 24 * hour
    private val dayStart = 20_000 * day // ein UTC-Tag

    /** 12 h Licht mit festem PPFD, alle 15 Minuten ein Wert, Rest dunkel. */
    private fun bloomDay(start: Long, ppfd: Double): List<ChartPoint> =
        (0 until 96).map { i ->
            val t = start + i * 15 * 60 * 1000L
            ChartPoint(t, if (i in 24 until 72) ppfd else 0.0)
        }

    @Test
    fun `DLI aus dem Verlauf`() {
        val points = bloomDay(dayStart, 800.0) + ChartPoint(dayStart + day, 0.0)
        val estimate = LightHistory.dli(points, dayStart, dayStart + day)!!
        // 800 µmol × 12 h ≈ 34,56 mol, Trapez an den Schaltkanten: ±1 Intervall
        assertEquals(34.56, estimate.dli, 0.8)
        assertTrue(estimate.complete)
        assertNull(LightHistory.dli(listOf(ChartPoint(dayStart, 1.0)), dayStart, dayStart + day))
    }

    @Test
    fun `Luecken zaehlen nicht mit`() {
        val points = bloomDay(dayStart, 800.0).filterIndexed { i, _ -> i !in 30..50 }
        val estimate = LightHistory.dli(points, dayStart, dayStart + day)!!
        assertFalse(estimate.complete)
        assertTrue(estimate.dli < 30.0)
        assertEquals(1, LightHistory.perDay(bloomDay(dayStart, 600.0)).size)
    }

    @Test
    fun `Lux in PPFD und Tageswerte in DLI`() {
        val hps = LightSetup(listOf(LampUse("hps_se", 600.0)), calibration = 1.5)
        val ppfd = LightHistory.ppfdPoints(listOf(ChartPoint(1, 50_000.0)), hps)!!
        assertEquals(610.0, ppfd.single().value, 0.01) // Handy-Kalibrierung gilt nicht für den Sensor
        assertNull(LightHistory.ppfdPoints(listOf(ChartPoint(1, 100.0)), LightSetup(listOf(LampUse("led_far_red", 50.0)))))
        // Tagesmittel 400 µmol/m²/s über 24 h = 34,56 mol/m²/Tag
        assertEquals(34.56, LightHistory.dliFromDailyAverages(listOf(ChartPoint(0, 400.0))).single().value, 0.001)
    }

    @Test
    fun `Licht-Reiter mit und ohne Sensor`() {
        val target = LightMath.target(GrowPhase.FLOWERING)
        val box = GrowBox(id = "b", name = "Zelt", phase = GrowPhase.FLOWERING, light = LightSetup(listOf(LampUse("led_white_3000", 480.0))))
        // Sensor: Lux-Kurve
        val lux = bloomDay(dayStart, 800.0 / 14.3 * 1000)
        val withSensor = LightHistoryView.of(box, lux, false, emptyList(), dayStart, dayStart + day, target)
        assertTrue(withSensor.fromSensor)
        assertEquals(34.5, withSensor.dli!!, 1.0)
        assertEquals(TargetRangeCheck(600.0, 900.0), TargetRangeCheck(withSensor.chartTarget!!.min, withSensor.chartTarget!!.max))

        // Ohne Sensor: letzte Einzelmessung × Lichtstunden
        val reading = LightReading(
            createdAtMillis = dayStart + 2 * hour, source = LightSource.PHONE, lux = null, ppfd = 700.0, dli = null,
            hours = 12.0, points = 1, minPpfd = null, maxPpfd = null, lampIds = listOf("led_white_3000"), phase = "flowering",
        )
        val spot = LightHistoryView.of(box, emptyList(), false, listOf(reading), dayStart, dayStart + day, target)
        assertFalse(spot.fromSensor)
        assertEquals(30.24, spot.dli!!, 0.001)
        assertEquals(1, spot.points.size)
        assertNotNull(spot.note)

        val empty = LightHistoryView.of(box.copy(light = null), emptyList(), false, emptyList(), dayStart, dayStart + day, target)
        assertNull(empty.dli)
        assertTrue(empty.note!!.contains("Kein Lichtsensor"))
    }

    @Test
    fun `Licht ist immer ein Reiter`() {
        val state = HistoryUiState(samples = listOf(HistorySample(1, mapOf(Metric.AIR_TEMPERATURE to 24.0, Metric.CO2 to 800.0))))
        assertEquals(listOf(Metric.AIR_TEMPERATURE, Metric.CO2, Metric.LIGHT), state.tabs)
        assertFalse(Metric.LIGHT in state.metrics)
    }

    private data class TargetRangeCheck(val min: Double, val max: Double)
}
