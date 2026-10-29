package de.greenganja.growguardian.grow

import de.greenganja.growguardian.billing.TierFeatures

/**
 * Welche Boxen und Sensoren die aktuelle Stufe überwacht.
 *
 * Gleiche Regel wie im Backend (`backend/functions/src/rules/grow.ts`): Aktiv sind die ältesten Boxen bis zum
 * Box-Limit, und darin die Sensoren in Zuordnungsreihenfolge bis zum Sensor-Limit. Nach einem Downgrade bleiben
 * die übrigen Boxen gespeichert, werden aber gesperrt angezeigt, bis wieder Platz ist oder ein Upgrade folgt.
 *
 * @param boxes in Anlegereihenfolge, wie [GrowBoxRepository.boxes] sie liefert
 */
data class GrowAccess(
    val features: TierFeatures,
    val activeBoxIds: Set<String>,
    val activeDeviceIds: Set<String>,
    val boxCount: Int,
    /** Sensoren in aktiven Boxen, auch die über dem Limit. */
    val assignedDeviceCount: Int,
) {
    fun isLocked(box: GrowBox): Boolean = box.id !in activeBoxIds

    fun isMonitored(deviceId: String): Boolean = deviceId in activeDeviceIds

    val canAddBox: Boolean get() = features.canAddGrowBox(boxCount)

    val lockedBoxCount: Int get() = boxCount - activeBoxIds.size

    /** Wie viele Sensoren eine Box höchstens haben darf, ohne das Gesamtlimit zu reißen; null = unbegrenzt. */
    fun maxDevicesFor(box: GrowBox?, boxes: List<GrowBox>): Int? {
        val limit = features.maxDevices ?: return null
        val usedElsewhere = boxes
            .filter { it.id != box?.id && it.id in activeBoxIds }
            .sumOf { it.deviceIds.size }
        return (limit - usedElsewhere).coerceAtLeast(0)
    }

    companion object {
        fun of(boxes: List<GrowBox>, features: TierFeatures): GrowAccess {
            val activeBoxes = features.maxGrowBoxes?.let { boxes.take(it) } ?: boxes
            val assigned = activeBoxes.flatMap { it.deviceIds }.distinct()
            val activeDevices = features.maxDevices?.let { assigned.take(it) } ?: assigned
            return GrowAccess(
                features = features,
                activeBoxIds = activeBoxes.map { it.id }.toSet(),
                activeDeviceIds = activeDevices.toSet(),
                boxCount = boxes.size,
                assignedDeviceCount = assigned.size,
            )
        }
    }
}

/** Woher die Live-Werte kommen. In der App Tuya, in Tests und Vorschauen eine Attrappe. */
interface SensorSource {
    /** Alle Geräte des Kontos mit aktuellen Werten, aktualisiert sich live. */
    fun sensors(): kotlinx.coroutines.flow.Flow<SensorFeed>

    /** Einmal die letzten Werte aller Geräte lesen, für den Hintergrund-Job. */
    suspend fun snapshot(): Map<String, SensorDevice>
}

sealed interface SensorFeed {
    data object Loading : SensorFeed

    data class Ready(val devices: Map<String, SensorDevice>) : SensorFeed

    data class Failed(val message: String) : SensorFeed
}
