package de.greenganja.growguardian.grow

/** Messgrößen, die die App anzeigt und bewertet. */
/** [id] ist der Schlüssel in Firestore und im Backend, nicht ändern. */
enum class Metric(val id: String, val label: String, val unit: String, val decimals: Int) {
    AIR_TEMPERATURE("air_temperature", "Temperatur", "°C", 1),
    AIR_HUMIDITY("air_humidity", "Luftfeuchte", "%", 0),

    /** Blatt-VPD, berechnet aus Temperatur, Luftfeuchte und Blatt-Offset. */
    VPD("vpd", "VPD", "kPa", 2),
    CO2("co2", "CO₂", "ppm", 0),
    SOIL_MOISTURE("soil_moisture", "Bodenfeuchte", "%", 0),
    SOIL_TEMPERATURE("soil_temperature", "Bodentemperatur", "°C", 1),
    WATER_TEMPERATURE("water_temperature", "Wassertemperatur", "°C", 1),
    PH("ph", "pH", "", 2),

    /** Leitwert in mS/cm (Tuya liefert meist µS/cm, wird umgerechnet). */
    EC("ec", "EC", "mS/cm", 2),
    TDS("tds", "TDS", "ppm", 0),
    POWER("power", "Leistung", "W", 1),
    ;

    companion object {
        fun from(id: String?): Metric? = entries.firstOrNull { it.id == id }
    }

    /** Nur die Zahl, z. B. "23,5". */
    fun formatNumber(value: Double): String = String.format(java.util.Locale.GERMANY, "%.${decimals}f", value)

    /** z. B. "23,5 °C" oder "6,12" für pH. */
    fun format(value: Double): String {
        val number = formatNumber(value)
        return if (unit.isEmpty()) number else "$number $unit"
    }
}

/** Ein Wertebereich, in dem eine Messgröße liegen soll. */
data class TargetRange(val min: Double, val max: Double) {
    init {
        require(min <= max) { "min muss kleiner oder gleich max sein" }
    }

    operator fun contains(value: Double): Boolean = value in min..max

    /** z. B. "22–28 °C" */
    fun format(metric: Metric): String {
        val locale = java.util.Locale.GERMANY
        val from = String.format(locale, "%.${metric.decimals}f", min)
        val to = String.format(locale, "%.${metric.decimals}f", max)
        return if (metric.unit.isEmpty()) "$from–$to" else "$from–$to ${metric.unit}"
    }
}
