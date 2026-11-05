package de.greenganja.growguardian.grow

import kotlin.math.pow

/** Das Nötige aus dem Tuya-Schema eines Datenpunkts. */
data class DpDefinition(
    /** DP-Nummer, z. B. "1". Live-Updates kommen unter dieser Nummer. */
    val id: String,
    /** DP-Code, z. B. "va_temperature". */
    val code: String,
    /** Nachkommastellen: Messwert = Rohwert / 10^scale. */
    val scale: Int = 0,
    val unit: String = "",
)

enum class DeviceKind(val label: String) {
    CLIMATE("Klimasensor"),
    CO2("CO₂-Sensor"),
    SOIL("Bodensensor"),
    WATER("Wassertester"),
    PLUG("Steckdose"),
    LIGHT("Lichtsensor"),
    OTHER("Gerät"),
}

/**
 * Ordnet Tuya-Datenpunkte unseren Messgrößen zu.
 *
 * Erkennt die üblichen Standard-Codes der Tuya-Kategorien. Ein Gerät mit abweichenden Codes
 * lässt sich über [overrides] (Produkt-ID → Code → Messgröße) ergänzen.
 */
object DpMapping {

    private val TEMPERATURE = setOf("va_temperature", "temp_current", "temp_value", "temperature")
    private val HUMIDITY = setOf("va_humidity", "humidity_value", "humidity_current", "humidity")
    private val CO2_CODES = setOf("co2_value", "co2_current")
    private val PH_CODES = setOf("ph_current", "ph_value")
    private val EC_CODES = setOf("ec_current", "ec_value")
    private val TDS_CODES = setOf("tds_current", "tds_value")
    private val SOIL_CODES = setOf("soil_moisture", "soil_humidity")
    private val LIGHT_CODES = setOf("bright_value", "illuminance_value", "lux_value")

    /** Tuya-Kategorie für Helligkeitssensoren. */
    private val LIGHT_CATEGORIES = setOf("ldcg")

    /** Tuya-Kategorie für Pflanzen- bzw. Bodensensoren. */
    private val SOIL_CATEGORIES = setOf("zwjcy")

    /** Steckdose, Steckdosenleiste, Schalter. */
    private val PLUG_CATEGORIES = setOf("cz", "pc", "kg")

    /** Eigene Zuordnungen für Geräte mit ungewöhnlichen Codes: Produkt-ID → (DP-Code → Messgröße). */
    val overrides: MutableMap<String, Map<String, Metric>> = mutableMapOf()

    fun kindOf(category: String?, codes: Collection<String>): DeviceKind = when {
        category in SOIL_CATEGORIES || codes.any { it in SOIL_CODES } -> DeviceKind.SOIL
        codes.any { it in PH_CODES || it in EC_CODES || it in TDS_CODES } -> DeviceKind.WATER
        codes.any { it in CO2_CODES } -> DeviceKind.CO2
        category in PLUG_CATEGORIES || "cur_power" in codes -> DeviceKind.PLUG
        codes.any { it in TEMPERATURE || it in HUMIDITY } -> DeviceKind.CLIMATE
        category in LIGHT_CATEGORIES || codes.any { it in LIGHT_CODES } -> DeviceKind.LIGHT
        else -> DeviceKind.OTHER
    }

    fun metricFor(code: String, kind: DeviceKind, productId: String? = null): Metric? {
        productId?.let { overrides[it]?.get(code) }?.let { return it }
        return when (code) {
            in CO2_CODES -> Metric.CO2
            in PH_CODES -> Metric.PH
            in EC_CODES -> Metric.EC
            in TDS_CODES -> Metric.TDS
            in SOIL_CODES -> Metric.SOIL_MOISTURE
            "cur_power" -> Metric.POWER
            in LIGHT_CODES -> Metric.LIGHT
            in TEMPERATURE -> when (kind) {
                DeviceKind.WATER -> Metric.WATER_TEMPERATURE
                DeviceKind.SOIL -> Metric.SOIL_TEMPERATURE
                DeviceKind.PLUG -> null
                else -> Metric.AIR_TEMPERATURE
            }
            in HUMIDITY -> when (kind) {
                DeviceKind.SOIL -> Metric.SOIL_MOISTURE
                DeviceKind.WATER, DeviceKind.PLUG -> null
                else -> Metric.AIR_HUMIDITY
            }
            else -> null
        }
    }

    /** Messwerte eines Geräts aus seinen DP-Definitionen und Rohwerten (DP-Nummer → Wert). */
    fun read(
        definitions: Collection<DpDefinition>,
        rawById: Map<String, Any?>,
        category: String?,
        productId: String? = null,
    ): Map<Metric, Double> {
        val kind = kindOf(category, definitions.map { it.code })
        val result = linkedMapOf<Metric, Double>()
        for (definition in definitions) {
            val metric = metricFor(definition.code, kind, productId) ?: continue
            val value = convert(definition, rawById[definition.id], metric) ?: continue
            result[metric] = value
        }
        return result
    }

    /** Welcher Datenpunkt liefert welche Messgröße (bei mehreren gilt wie in [read] der letzte). */
    fun sources(definitions: Collection<DpDefinition>, category: String?, productId: String? = null): Map<Metric, DpDefinition> {
        val kind = kindOf(category, definitions.map { it.code })
        val result = linkedMapOf<Metric, DpDefinition>()
        for (definition in definitions) {
            val metric = metricFor(definition.code, kind, productId) ?: continue
            result[metric] = definition
        }
        return result
    }

    /**
     * Messwert → Rohwert für Bedingungen in der Tuya-Cloud, Gegenstück zu [convert].
     * Beispiel: 23,5 °C bei scale 1 → 235; bei einem °F-Sensor zuerst in °F umrechnen.
     */
    fun toRaw(definition: DpDefinition, metric: Metric, value: Double): Int {
        var v = value
        val unit = definition.unit.lowercase()
        if (metric in TEMPERATURE_METRICS && (unit.contains("°f") || unit == "f" || unit.contains("℉"))) {
            v = v * 9.0 / 5.0 + 32.0
        }
        return Math.round(v * 10.0.pow(definition.scale)).toInt()
    }

    /** Schalter-Codes von Steckdosen, Leisten und Relais. */
    private val SWITCH_CODE = Regex("^switch(_[0-9]+)?$")

    fun isSwitchCode(code: String): Boolean = SWITCH_CODE.matches(code)

    /** Anzeigename eines Schaltkanals: "Schalter" bzw. "Dose 2". */
    fun switchLabel(code: String, count: Int): String {
        val number = code.substringAfter("switch_", "").toIntOrNull()
        return if (count <= 1 || number == null) "Schalter" else "Dose $number"
    }

    /** Rohwert → Messwert in unserer Einheit, null bei unbrauchbaren Werten. */
    fun convert(definition: DpDefinition, raw: Any?, metric: Metric): Double? {
        val number = when (raw) {
            is Number -> raw.toDouble()
            is String -> raw.toDoubleOrNull()
            else -> null
        } ?: return null
        var value = number / 10.0.pow(definition.scale)
        val unit = definition.unit.lowercase()
        if (metric in TEMPERATURE_METRICS && (unit.contains("°f") || unit == "f" || unit.contains("℉"))) {
            value = (value - 32.0) * 5.0 / 9.0
        }
        if (metric == Metric.EC) value = toMilliSiemens(value, unit)
        return value.takeIf { isPlausible(metric, it) }
    }

    private val TEMPERATURE_METRICS = setOf(Metric.AIR_TEMPERATURE, Metric.SOIL_TEMPERATURE, Metric.WATER_TEMPERATURE)

    private fun toMilliSiemens(value: Double, unit: String): Double = when {
        unit.contains("us") || unit.contains("µs") || unit.contains("μs") -> value / 1000.0
        unit.contains("ms") -> value
        // Ohne Einheit: Werte über 20 sind praktisch immer µS/cm
        value > 20.0 -> value / 1000.0
        else -> value
    }

    /** Filtert offensichtliche Fehlwerte, z. B. pH 15,00 bei abgezogener Sonde. */
    private fun isPlausible(metric: Metric, value: Double): Boolean = when (metric) {
        Metric.PH -> value in 0.0..14.0
        Metric.AIR_HUMIDITY, Metric.SOIL_MOISTURE -> value in 0.0..100.0
        Metric.AIR_TEMPERATURE, Metric.SOIL_TEMPERATURE, Metric.WATER_TEMPERATURE -> value in -40.0..80.0
        Metric.CO2, Metric.TDS, Metric.EC, Metric.POWER -> value >= 0.0
        Metric.VPD -> value >= 0.0
        Metric.LIGHT -> value in 0.0..300_000.0
    }
}
