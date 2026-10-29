package de.greenganja.growguardian.grow.alarm

import de.greenganja.growguardian.billing.TierFeatures
import de.greenganja.growguardian.grow.DpMapping
import de.greenganja.growguardian.grow.GrowBoxState
import de.greenganja.growguardian.grow.Metric
import de.greenganja.growguardian.grow.automation.AUTOMATION_METRICS
import kotlinx.coroutines.CancellationException

/**
 * Alarme als Push vom Server: Für jede Box und Messgröße mit Zielbereich legt die App in der Tuya-Cloud eine
 * Szene an („Wert unter Minimum ODER über Maximum → Benachrichtigung“). Tuya prüft das bei jeder Sensormeldung
 * und schickt den Push selbst, auch wenn das Handy aus ist.
 *
 * Was die Cloud übernimmt, prüft der Hintergrund-Job nicht doppelt. Alles andere (Sensor offline, VPD, pH, EC)
 * bleibt beim Handy-Alarm.
 */
data class AlarmScenePlan(
    /** Gleicher Schlüssel wie [AlarmIssue.key]: "boxId|metricId". */
    val key: String,
    val boxId: String,
    val metric: Metric,
    val sensorDeviceId: String,
    val dpId: String,
    /** Rohwerte des Sensors für Minimum und Maximum. */
    val minRaw: Int,
    val maxRaw: Int,
    val message: String,
) {
    /** Ändert sich etwas davon, wird die Szene neu angelegt. */
    val signature: String get() = listOf(sensorDeviceId, dpId, minRaw, maxRaw, message).joinToString("~")
}

object AlarmPushPlanner {
    fun plan(states: List<GrowBoxState>, features: TierFeatures): List<AlarmScenePlan> {
        if (!features.alerts) return emptyList()
        return states.filter { it.box.alertsEnabled }.flatMap { state ->
            val box = state.box
            AUTOMATION_METRICS.mapNotNull { metric ->
                val target = box.targets[metric] ?: return@mapNotNull null
                // Erster Sensor der Box, der diese Messgröße liefert; online bevorzugt
                val sensor = state.devices.sortedBy { !it.online }.firstOrNull { metric in it.sources } ?: return@mapNotNull null
                val source = sensor.sources.getValue(metric)
                AlarmScenePlan(
                    key = "${box.id}|${metric.id}",
                    boxId = box.id,
                    metric = metric,
                    sensorDeviceId = sensor.id,
                    dpId = source.id,
                    minRaw = DpMapping.toRaw(source, metric, target.min),
                    maxRaw = DpMapping.toRaw(source, metric, target.max),
                    message = "${box.name}: ${metric.label} außerhalb des Ziels (${target.format(metric)})",
                )
            }
        }
    }
}

/** Legt Alarm-Szenen an und entfernt sie. In der App [de.greenganja.growguardian.tuya.TuyaAlarmPush]. */
interface AlarmPushEngine {
    suspend fun create(plan: AlarmScenePlan): String
    suspend fun remove(sceneId: String)
}

/** Merkt sich auf dem Gerät, welche Szene zu welchem Alarm gehört. */
interface AlarmPushMemo {
    fun load(): String?
    fun save(text: String)
}

data class AlarmPushResult(
    /** Schlüssel, die die Cloud überwacht; der Handy-Alarm überspringt sie. */
    val cloudKeys: Set<String>,
    /** Letzter Fehler beim Anlegen, z. B. weil die Push-Aktion noch nicht eingerichtet ist. */
    val error: String?,
)

class AlarmPushSync(private val engine: AlarmPushEngine, private val memo: AlarmPushMemo) {

    private data class Stored(val sceneId: String, val signature: String)

    private fun decode(text: String?): MutableMap<String, Stored> =
        text.orEmpty().lines().mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size == 3) parts[0] to Stored(parts[1], parts[2]) else null
        }.toMap().toMutableMap()

    private fun encode(map: Map<String, Stored>): String =
        map.entries.joinToString("\n") { (key, s) -> listOf(key, s.sceneId, s.signature).joinToString("\t") }

    /** Bringt die Szenen in der Cloud auf den Stand von [plans]. Fehler brechen nicht ab, sie landen in [AlarmPushResult.error]. */
    suspend fun sync(plans: List<AlarmScenePlan>): AlarmPushResult {
        val stored = decode(memo.load())
        val wanted = plans.associateBy { it.key }
        var error: String? = null

        // Veraltete oder geänderte Szenen entfernen
        for ((key, entry) in stored.toMap()) {
            val plan = wanted[key]
            if (plan != null && plan.signature == entry.signature) continue
            try {
                engine.remove(entry.sceneId)
                stored.remove(key)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "Alarm-Szene konnte nicht entfernt werden"
            }
        }
        // Fehlende anlegen
        for (plan in plans) {
            if (stored[plan.key]?.signature == plan.signature) continue
            if (plan.key in stored) continue // alte Fassung ließ sich nicht entfernen: nicht doppelt anlegen
            try {
                stored[plan.key] = Stored(engine.create(plan), plan.signature)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "Alarm-Szene konnte nicht angelegt werden"
            }
        }
        memo.save(encode(stored))
        val current = stored.filter { (key, s) -> wanted[key]?.signature == s.signature }.keys
        return AlarmPushResult(current, error)
    }
}
