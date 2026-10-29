package de.greenganja.growguardian.grow

import kotlin.math.exp

/** Dampfdruckdefizit nach der Magnus-Tetens-Formel. */
object Vpd {
    /** Blätter sind meist etwas kühler als die Luft, unter LED etwa 1 bis 3 °C. */
    const val DEFAULT_LEAF_OFFSET = -2.0

    /** Sättigungsdampfdruck in kPa. */
    fun saturationPressure(tempC: Double): Double = 0.61078 * exp(17.27 * tempC / (tempC + 237.3))

    /** Blatt-VPD in kPa. */
    fun leaf(airTempC: Double, relativeHumidity: Double, leafOffsetC: Double = DEFAULT_LEAF_OFFSET): Double {
        val rh = relativeHumidity.coerceIn(0.0, 100.0) / 100.0
        return (saturationPressure(airTempC + leafOffsetC) - saturationPressure(airTempC) * rh).coerceAtLeast(0.0)
    }
}

enum class GrowPhase(val id: String, val label: String) {
    SEEDLING("seedling", "Keimling / Steckling"),
    VEGETATIVE("vegetative", "Wachstum"),
    FLOWERING("flowering", "Blüte"),
    LATE_FLOWERING("late_flowering", "Späte Blüte"),
    DRYING("drying", "Trocknen"),
    ;

    companion object {
        fun from(id: String?): GrowPhase = entries.firstOrNull { it.id == id } ?: VEGETATIVE
    }
}

enum class GrowMedium(val id: String, val label: String) {
    SOIL("soil", "Erde"),
    COCO("coco", "Kokos"),
    HYDRO("hydro", "Hydro"),
    ;

    companion object {
        fun from(id: String?): GrowMedium = entries.firstOrNull { it.id == id } ?: SOIL
    }
}

/**
 * Startwerte je Phase und Medium, wie sie in Grow-Anleitungen üblich sind.
 * Jede Box kann sie mit eigenen Zielwerten überschreiben.
 */
object DefaultTargets {

    private fun range(min: Double, max: Double) = TargetRange(min, max)

    fun forPhase(phase: GrowPhase, medium: GrowMedium): Map<Metric, TargetRange> {
        val targets = linkedMapOf<Metric, TargetRange>()
        when (phase) {
            GrowPhase.SEEDLING -> {
                targets[Metric.AIR_TEMPERATURE] = range(22.0, 28.0)
                targets[Metric.AIR_HUMIDITY] = range(65.0, 80.0)
                targets[Metric.VPD] = range(0.4, 0.8)
                targets[Metric.CO2] = range(400.0, 800.0)
            }
            GrowPhase.VEGETATIVE -> {
                targets[Metric.AIR_TEMPERATURE] = range(22.0, 28.0)
                targets[Metric.AIR_HUMIDITY] = range(55.0, 70.0)
                targets[Metric.VPD] = range(0.8, 1.2)
                targets[Metric.CO2] = range(600.0, 1200.0)
            }
            GrowPhase.FLOWERING -> {
                targets[Metric.AIR_TEMPERATURE] = range(20.0, 26.0)
                targets[Metric.AIR_HUMIDITY] = range(40.0, 55.0)
                targets[Metric.VPD] = range(1.0, 1.5)
                targets[Metric.CO2] = range(800.0, 1500.0)
            }
            GrowPhase.LATE_FLOWERING -> {
                targets[Metric.AIR_TEMPERATURE] = range(18.0, 24.0)
                targets[Metric.AIR_HUMIDITY] = range(35.0, 45.0)
                targets[Metric.VPD] = range(1.2, 1.6)
                targets[Metric.CO2] = range(400.0, 1000.0)
            }
            GrowPhase.DRYING -> {
                targets[Metric.AIR_TEMPERATURE] = range(16.0, 20.0)
                targets[Metric.AIR_HUMIDITY] = range(55.0, 62.0)
                return targets
            }
        }
        targets[Metric.PH] = when (medium) {
            GrowMedium.SOIL -> range(6.0, 7.0)
            GrowMedium.COCO -> range(5.8, 6.3)
            GrowMedium.HYDRO -> range(5.5, 6.2)
        }
        targets[Metric.EC] = when (phase) {
            GrowPhase.SEEDLING -> range(0.4, 0.8)
            GrowPhase.VEGETATIVE -> range(1.0, 1.6)
            GrowPhase.FLOWERING -> range(1.4, 2.0)
            else -> range(1.0, 1.6)
        }
        if (medium == GrowMedium.HYDRO) targets[Metric.WATER_TEMPERATURE] = range(18.0, 22.0)
        if (medium != GrowMedium.HYDRO) targets[Metric.SOIL_MOISTURE] = range(30.0, 70.0)
        return targets
    }
}

enum class MetricStatus(val label: String) {
    LOW("Zu niedrig"),
    OK("Im Ziel"),
    HIGH("Zu hoch"),
    UNRATED("Kein Ziel"),
}

data class MetricReading(val metric: Metric, val value: Double, val target: TargetRange?, val status: MetricStatus) {
    val formatted: String get() = metric.format(value)
}

enum class BoxStatus(val label: String) {
    OK("Alles im Ziel"),
    WARNING("Werte außerhalb"),
    OFFLINE("Sensor offline"),
    NO_DATA("Noch keine Messwerte"),
    NO_DEVICES("Keine Sensoren"),
}

object GrowEvaluation {

    /** Mittelwert je Messgröße über alle Geräte einer Box; VPD kommt aus Temperatur und Luftfeuchte. */
    fun combine(perDevice: List<Map<Metric, Double>>, leafOffsetC: Double): Map<Metric, Double> {
        val collected = mutableMapOf<Metric, MutableList<Double>>()
        for (values in perDevice) {
            for ((metric, value) in values) {
                if (metric != Metric.VPD) collected.getOrPut(metric) { mutableListOf() }.add(value)
            }
        }
        val combined = collected.mapValues { (_, values) -> values.average() }.toMutableMap()
        val temperature = combined[Metric.AIR_TEMPERATURE]
        val humidity = combined[Metric.AIR_HUMIDITY]
        if (temperature != null && humidity != null) combined[Metric.VPD] = Vpd.leaf(temperature, humidity, leafOffsetC)
        return combined.toSortedMap(compareBy { it.ordinal })
    }

    fun rate(value: Double, target: TargetRange?): MetricStatus = when {
        target == null -> MetricStatus.UNRATED
        value < target.min -> MetricStatus.LOW
        value > target.max -> MetricStatus.HIGH
        else -> MetricStatus.OK
    }

    fun readings(values: Map<Metric, Double>, targets: Map<Metric, TargetRange>): List<MetricReading> =
        values.entries
            .sortedBy { it.key.ordinal }
            .map { (metric, value) -> MetricReading(metric, value, targets[metric], rate(value, targets[metric])) }

    fun boxStatus(deviceCount: Int, onlineCount: Int, readings: List<MetricReading>): BoxStatus = when {
        deviceCount == 0 -> BoxStatus.NO_DEVICES
        onlineCount < deviceCount -> BoxStatus.OFFLINE
        readings.any { it.status == MetricStatus.LOW || it.status == MetricStatus.HIGH } -> BoxStatus.WARNING
        readings.isEmpty() -> BoxStatus.NO_DATA
        else -> BoxStatus.OK
    }
}
