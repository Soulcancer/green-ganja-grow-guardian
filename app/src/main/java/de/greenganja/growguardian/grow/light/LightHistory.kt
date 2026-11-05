package de.greenganja.growguardian.grow.light

import de.greenganja.growguardian.grow.history.ChartPoint
import de.greenganja.growguardian.grow.history.HistoryFormat

/** Lichtmenge in einem Zeitfenster; [coverage] = Anteil der Zeit mit Messwerten (0–1). */
data class DliEstimate(val dli: Double, val coverage: Double) {
    val complete: Boolean get() = coverage >= 0.9
}

/**
 * Licht im Verlauf: Lux eines Tuya-Lichtsensors in PPFD umrechnen und die Tageslichtmenge (DLI) aufsummieren.
 * Tage sind wie im übrigen Verlauf UTC-Tage.
 */
object LightHistory {
    /** Lücken über einer Stunde zählen nicht mit (Sensor offline, Handy ohne Netz). */
    const val MAX_GAP_MS = 60 * 60 * 1000L

    /** mol/m²/Tag je µmol/m²/s Tagesmittel über 24 Stunden. */
    const val DLI_PER_AVG_PPFD = 0.0864

    /** Lux-Punkte in PPFD; null, wenn die Lampen der Box mit Lux nicht messbar sind. Ohne Handy-Kalibrierung. */
    fun ppfdPoints(luxPoints: List<ChartPoint>, setup: LightSetup): List<ChartPoint>? {
        val perLux = (LightMath.ppfdPerKlx(setup) ?: return null) / 1000.0
        return luxPoints.map { ChartPoint(it.timeMillis, it.value * perLux, it.low * perLux, it.high * perLux) }
    }

    /** Integral des PPFD über [fromMillis, toMillis] in mol/m² (Trapezregel). */
    fun dli(points: List<ChartPoint>, fromMillis: Long, toMillis: Long, maxGapMillis: Long = MAX_GAP_MS): DliEstimate? {
        val span = toMillis - fromMillis
        if (span <= 0) return null
        val window = points.filter { it.timeMillis in fromMillis..toMillis }.sortedBy { it.timeMillis }
        if (window.size < 2) return null
        var micromol = 0.0
        var covered = 0L
        for ((a, b) in window.zipWithNext()) {
            val dt = b.timeMillis - a.timeMillis
            if (dt <= 0 || dt > maxGapMillis) continue
            micromol += (a.value + b.value) / 2.0 * dt / 1000.0
            covered += dt
        }
        return DliEstimate(micromol / 1_000_000.0, (covered.toDouble() / span).coerceIn(0.0, 1.0))
    }

    /** DLI der letzten 24 Stunden bis [nowMillis]. */
    fun lastDay(points: List<ChartPoint>, nowMillis: Long): DliEstimate? = dli(points, nowMillis - HistoryFormat.DAY_MS, nowMillis)

    /** DLI je UTC-Tag aus Einzelwerten, als Punkte in der Tagesmitte. Nur Tage mit mindestens der Hälfte an Messzeit. */
    fun perDay(points: List<ChartPoint>): List<Pair<ChartPoint, DliEstimate>> =
        points.groupBy { it.timeMillis - Math.floorMod(it.timeMillis, HistoryFormat.DAY_MS) }
            .toSortedMap()
            .mapNotNull { (dayStart, _) ->
                val estimate = dli(points, dayStart, dayStart + HistoryFormat.DAY_MS) ?: return@mapNotNull null
                if (estimate.coverage < 0.5) null else ChartPoint(dayStart + HistoryFormat.DAY_MS / 2, estimate.dli) to estimate
            }

    /** DLI aus Tageswerten (Mittel über 24 Stunden), z. B. für 30 Tage und 1 Jahr. */
    fun dliFromDailyAverages(dailyPpfd: List<ChartPoint>): List<ChartPoint> =
        dailyPpfd.map { ChartPoint(it.timeMillis, it.value * DLI_PER_AVG_PPFD) }

    /** Einzelmessungen (Lichtmodul) im Zeitraum als Punkte. */
    fun readingPoints(readings: List<LightReading>, fromMillis: Long, toMillis: Long): List<ChartPoint> =
        readings.mapNotNull { r -> r.createdAtMillis?.takeIf { it in fromMillis..toMillis }?.let { ChartPoint(it, r.ppfd) } }
            .sortedBy { it.timeMillis }
}
