package de.greenganja.growguardian.grow.history

import de.greenganja.growguardian.grow.Metric
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryTest {

    // 29.10.2026 13:47 UTC
    private val t = 1_793_281_620_000L

    @Test
    fun `Tag und Zeitfenster in UTC`() {
        assertEquals("2026-10-29", HistoryFormat.dayId(t))
        assertEquals("1345", HistoryFormat.slotKey(t))
        assertEquals(1_793_232_000_000L, HistoryFormat.dayStart("2026-10-29"))
    }

    @Test
    fun `liest Tagesdokument und Statistik`() {
        val samples = HistoryFormat.decodeDay(
            "2026-10-29",
            mapOf(
                "1345" to mapOf("air_temperature" to 24.5, "unbekannt" to 1),
                "0000" to mapOf("air_humidity" to 55L),
                "9999" to mapOf("air_humidity" to 1),
                "1200" to "kaputt",
            ),
        )
        assertEquals(2, samples.size)
        assertEquals(1_793_232_000_000L, samples[0].timeMillis)
        assertEquals(mapOf(Metric.AIR_HUMIDITY to 55.0), samples[0].values)
        assertEquals(t - 2 * 60_000, samples[1].timeMillis)

        val stats = HistoryFormat.decodeStats(2026, mapOf("1029" to mapOf("air_temperature" to listOf(20, 23.5, 27))))
        assertEquals(MinAvgMax(20.0, 23.5, 27.0), stats.single().values[Metric.AIR_TEMPERATURE])
        assertEquals(mapOf("air_temperature" to 23.457), HistoryFormat.encode(mapOf(Metric.AIR_TEMPERATURE to 23.4567)))
    }

    @Test
    fun `Trend, Zusammenfassung und Verdichtung`() {
        val rising = (0 until 12).map { ChartPoint(it * 900_000L, 20.0 + it * 0.5) }
        val summary = HistoryMath.summary(rising, targetWidth = 6.0)!!
        assertEquals(Trend.UP, summary.trend)
        assertEquals(20.0, summary.min, 1e-9)
        assertEquals(25.5, summary.latest, 1e-9)
        assertEquals(Trend.STEADY, HistoryMath.summary(rising.map { it.copy(value = 24.0, low = 24.0, high = 24.0) })!!.trend)

        val many = (0 until 1000).map { ChartPoint(it * 60_000L, it.toDouble()) }
        val reduced = HistoryMath.downsample(many, 100)
        assertTrue(reduced.size <= 100)
        assertEquals(0.0, reduced.first().low, 1e-9)
        assertEquals(999.0, reduced.last().high, 1e-9)
    }

    @Test
    fun `trennt die Kurve an Lücken`() {
        val points = listOf(0L, 15L, 30L, 45L, 300L, 315L).map { ChartPoint(it * 60_000, 1.0) }
        assertEquals(listOf(4, 2), HistoryMath.segments(points).map { it.size })
    }

    @Test
    fun `Zeiträume je Stufe`() {
        assertTrue(HistoryRange.WEEK.isAllowed(7))
        assertTrue(!HistoryRange.MONTH.isAllowed(7))
        assertTrue(HistoryRange.YEAR.isAllowed(null))
        assertTrue(HistoryRange.MONTH.usesDailyStats)
    }
}
