package de.greenganja.growguardian.grow

/** Eine Grow-Box mit ihren zugeordneten Tuya-Geräten. */
data class GrowBox(
    val id: String,
    val name: String,
    val phase: GrowPhase = GrowPhase.VEGETATIVE,
    val medium: GrowMedium = GrowMedium.SOIL,
    /** Blatt- minus Lufttemperatur in °C, für den VPD. */
    val leafOffsetC: Double = Vpd.DEFAULT_LEAF_OFFSET,
    /** Tuya-Geräte-IDs. */
    val deviceIds: List<String> = emptyList(),
    /** Eigene Zielwerte; überschreiben die Startwerte der Phase. */
    val customTargets: Map<Metric, TargetRange> = emptyMap(),
    /** Alarme für diese Box; wirken nur mit einer Stufe, die Alarme enthält. */
    val alertsEnabled: Boolean = true,
    /** Sorte, frei eingegeben. */
    val strain: String? = null,
    /** Starttag des Grows "YYYY-MM-DD". */
    val plantedAt: String? = null,
    /** Lampen der Box; gespeichert über saveLightSetup, nicht über saveGrowBox. */
    val light: de.greenganja.growguardian.grow.light.LightSetup? = null,
) {
    val targets: Map<Metric, TargetRange>
        get() = DefaultTargets.forPhase(phase, medium) + customTargets
}

/** Ein Tuya-Gerät mit seinen aktuellen Messwerten. */
data class SensorDevice(
    val id: String,
    val name: String,
    val online: Boolean,
    val kind: DeviceKind,
    val values: Map<Metric, Double>,
    /** Aus welchem Datenpunkt jede Messgröße stammt; nötig für Automationen in der Tuya-Cloud. */
    val sources: Map<Metric, DpDefinition> = emptyMap(),
    /** Schaltbare Kanäle, z. B. die Dosen einer Steckdosenleiste. */
    val switches: List<SwitchChannel> = emptyList(),
)

/** Ein schaltbarer Datenpunkt (switch, switch_1 …). */
data class SwitchChannel(val dpId: String, val code: String, val label: String, val on: Boolean?)

/** Alles, was die Oberfläche zu einer Box anzeigt. */
data class GrowBoxState(
    val box: GrowBox,
    val devices: List<SensorDevice>,
    val readings: List<MetricReading>,
    val status: BoxStatus,
) {
    fun reading(metric: Metric): MetricReading? = readings.firstOrNull { it.metric == metric }

    companion object {
        fun of(box: GrowBox, allDevices: Map<String, SensorDevice>): GrowBoxState {
            val devices = box.deviceIds.mapNotNull { allDevices[it] }
            val online = devices.filter { it.online }
            val values = GrowEvaluation.combine(online.map { it.values }, box.leafOffsetC)
            val readings = GrowEvaluation.readings(values, box.targets)
            return GrowBoxState(box, devices, readings, GrowEvaluation.boxStatus(box.deviceIds.size, online.size, readings))
        }
    }
}
