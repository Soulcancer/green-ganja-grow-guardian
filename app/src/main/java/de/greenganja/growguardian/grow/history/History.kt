package de.greenganja.growguardian.grow.history

import de.greenganja.growguardian.grow.Metric
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

/** Ein gespeicherter Messzeitpunkt einer Box. */
data class HistorySample(val timeMillis: Long, val values: Map<Metric, Double>)

/** Tageswerte aus der nächtlichen Statistik. */
data class DailyStat(val dayStartMillis: Long, val values: Map<Metric, MinAvgMax>)

data class MinAvgMax(val min: Double, val avg: Double, val max: Double)

/** Ein Punkt im Diagramm. Bei Tageswerten ist [value] der Mittelwert, [low]/[high] die Spanne. */
data class ChartPoint(val timeMillis: Long, val value: Double, val low: Double = value, val high: Double = value)

enum class Trend(val symbol: String, val label: String) {
    UP("↑", "steigt"),
    DOWN("↓", "fällt"),
    STEADY("→", "stabil"),
}

/** Kennzahlen eines Zeitraums für eine Messgröße. */
data class HistorySummary(val min: Double, val avg: Double, val max: Double, val latest: Double, val trend: Trend)

/** Zeiträume der Analyse; [days] entscheidet, ob die Stufe sie zeigen darf. */
enum class HistoryRange(val label: String, val days: Int) {
    DAY("24 Std.", 1),
    WEEK("7 Tage", 7),
    MONTH("30 Tage", 30),
    YEAR("1 Jahr", 365),
    ;

    /** Ab 30 Tagen kommen Tageswerte aus der Statistik statt einzelner Messungen. */
    val usesDailyStats: Boolean get() = days >= 30

    fun isAllowed(historyDays: Int?): Boolean = historyDays == null || days <= historyDays
}

/**
 * Gespeichert wird je Box und UTC-Tag ein Dokument `history/{yyyy-MM-dd}` mit `s: { "HHmm": { metricId: Wert } }`.
 * Gleiches Format wie im Backend (`rules/history.ts`).
 */
object HistoryFormat {
    const val SLOT_MINUTES = 5
    const val DAY_MS = 24 * 60 * 60 * 1000L

    private val UTC: TimeZone = TimeZone.getTimeZone("UTC")

    private fun utc(millis: Long): Calendar = Calendar.getInstance(UTC, Locale.ROOT).apply { timeInMillis = millis }

    fun dayId(millis: Long): String {
        val c = utc(millis)
        return String.format(Locale.ROOT, "%04d-%02d-%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    }

    /** "HHmm" in UTC, auf 5 Minuten abgerundet. */
    fun slotKey(millis: Long): String {
        val c = utc(millis)
        val minute = c.get(Calendar.MINUTE) / SLOT_MINUTES * SLOT_MINUTES
        return String.format(Locale.ROOT, "%02d%02d", c.get(Calendar.HOUR_OF_DAY), minute)
    }

    fun dayStart(dayId: String): Long? {
        val parts = dayId.split('-').mapNotNull { it.toIntOrNull() }
        if (parts.size != 3) return null
        return Calendar.getInstance(UTC, Locale.ROOT).apply {
            clear()
            set(parts[0], parts[1] - 1, parts[2])
        }.timeInMillis
    }

    /** Werte für Firestore: Metrik-IDs, auf 3 Nachkommastellen gerundet. */
    fun encode(values: Map<Metric, Double>): Map<String, Double> =
        values.filterValues { it.isFinite() }.entries.associate { (metric, value) -> metric.id to Math.round(value * 1000) / 1000.0 }

    /** Ein Tagesdokument in Messpunkte zerlegen; unbrauchbare Einträge fallen weg. */
    fun decodeDay(dayId: String, slots: Map<*, *>?): List<HistorySample> {
        val start = dayStart(dayId) ?: return emptyList()
        return slots.orEmpty().mapNotNull { (key, raw) ->
            val slot = key as? String ?: return@mapNotNull null
            if (slot.length != 4) return@mapNotNull null
            val hour = slot.substring(0, 2).toIntOrNull() ?: return@mapNotNull null
            val minute = slot.substring(2).toIntOrNull() ?: return@mapNotNull null
            if (hour !in 0..23 || minute !in 0..59) return@mapNotNull null
            val values = (raw as? Map<*, *>).orEmpty().mapNotNull { (id, value) ->
                val metric = Metric.from(id as? String) ?: return@mapNotNull null
                val number = (value as? Number)?.toDouble()?.takeIf { it.isFinite() } ?: return@mapNotNull null
                metric to number
            }.toMap()
            if (values.isEmpty()) null else HistorySample(start + (hour * 60 + minute) * 60_000L, values)
        }.sortedBy { it.timeMillis }
    }

    /** Jahres-Statistik `d: { "MMdd": { metricId: [min, avg, max] } }`. */
    fun decodeStats(year: Int, days: Map<*, *>?): List<DailyStat> =
        days.orEmpty().mapNotNull { (key, raw) ->
            val mmdd = key as? String ?: return@mapNotNull null
            if (mmdd.length != 4) return@mapNotNull null
            val start = dayStart(String.format(Locale.ROOT, "%04d-%s-%s", year, mmdd.substring(0, 2), mmdd.substring(2))) ?: return@mapNotNull null
            val values = (raw as? Map<*, *>).orEmpty().mapNotNull { (id, triple) ->
                val metric = Metric.from(id as? String) ?: return@mapNotNull null
                val list = (triple as? List<*>)?.mapNotNull { (it as? Number)?.toDouble() } ?: return@mapNotNull null
                if (list.size != 3) null else metric to MinAvgMax(list[0], list[1], list[2])
            }.toMap()
            if (values.isEmpty()) null else DailyStat(start, values)
        }.sortedBy { it.dayStartMillis }
}

object HistoryMath {

    fun samplePoints(samples: List<HistorySample>, metric: Metric): List<ChartPoint> =
        samples.mapNotNull { sample -> sample.values[metric]?.let { ChartPoint(sample.timeMillis, it) } }

    fun dailyPoints(stats: List<DailyStat>, metric: Metric): List<ChartPoint> =
        stats.mapNotNull { day ->
            day.values[metric]?.let { ChartPoint(day.dayStartMillis + HistoryFormat.DAY_MS / 2, it.avg, it.min, it.max) }
        }

    /** Tageswerte aus einzelnen Messungen, für Tage, die noch nicht in der Statistik stehen. */
    fun dailyFromSamples(samples: List<HistorySample>): List<DailyStat> =
        samples.groupBy { it.timeMillis - Math.floorMod(it.timeMillis, HistoryFormat.DAY_MS) }
            .map { (dayStart, daySamples) ->
                val metrics = daySamples.flatMap { it.values.keys }.toSet()
                DailyStat(
                    dayStart,
                    metrics.associateWith { metric ->
                        val values = daySamples.mapNotNull { it.values[metric] }
                        MinAvgMax(values.min(), values.average(), values.max())
                    },
                )
            }
            .sortedBy { it.dayStartMillis }

    /**
     * Höchstens [maxPoints] Punkte: gleich breite Zeitfenster, je Fenster Mittelwert mit Spanne.
     * Hält Diagramme mit einem Jahr Messungen flüssig.
     */
    fun downsample(points: List<ChartPoint>, maxPoints: Int): List<ChartPoint> {
        if (points.size <= maxPoints || maxPoints < 2) return points
        val first = points.first().timeMillis
        val span = (points.last().timeMillis - first).coerceAtLeast(1)
        return points.groupBy { ((it.timeMillis - first) * (maxPoints - 1) / span).toInt() }
            .toSortedMap()
            .map { (_, bucket) ->
                ChartPoint(
                    timeMillis = bucket.map { it.timeMillis }.average().toLong(),
                    value = bucket.map { it.value }.average(),
                    low = bucket.minOf { it.low },
                    high = bucket.maxOf { it.high },
                )
            }
    }

    /**
     * Teilt die Kurve an Lücken (z. B. Handy aus, Sensor offline), damit keine Linie über fehlende Zeit gezogen wird.
     * Eine Lücke ist mehr als das Dreifache des üblichen Abstands.
     */
    fun segments(points: List<ChartPoint>): List<List<ChartPoint>> {
        if (points.size < 2) return listOf(points).filter { it.isNotEmpty() }
        val gaps = points.zipWithNext { a, b -> b.timeMillis - a.timeMillis }.sorted()
        val typical = gaps[gaps.size / 2].coerceAtLeast(1)
        val result = mutableListOf(mutableListOf(points.first()))
        for ((previous, next) in points.zipWithNext()) {
            if (next.timeMillis - previous.timeMillis > typical * 3) result += mutableListOf<ChartPoint>()
            result.last() += next
        }
        return result
    }

    /**
     * Ø, Min, Max, letzter Wert und Trend. Der Trend vergleicht das erste mit dem letzten Drittel;
     * "stabil", solange die Änderung unter 3 % der Spanne des Zielbereichs bzw. der Werte bleibt.
     */
    fun summary(points: List<ChartPoint>, targetWidth: Double? = null): HistorySummary? {
        if (points.isEmpty()) return null
        val values = points.map { it.value }
        val third = (points.size / 3).coerceAtLeast(1)
        val early = values.take(third).average()
        val late = values.takeLast(third).average()
        val reference = targetWidth?.takeIf { it > 0 } ?: (points.maxOf { it.high } - points.minOf { it.low }).coerceAtLeast(1e-9)
        val change = late - early
        val trend = when {
            points.size < 3 || abs(change) < reference * 0.03 -> Trend.STEADY
            change > 0 -> Trend.UP
            else -> Trend.DOWN
        }
        return HistorySummary(points.minOf { it.low }, values.average(), points.maxOf { it.high }, values.last(), trend)
    }
}
