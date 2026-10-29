package de.greenganja.growguardian.grow.ui

import de.greenganja.growguardian.grow.Metric
import de.greenganja.growguardian.grow.SensorDevice
import de.greenganja.growguardian.grow.automation.AUTOMATION_METRICS
import de.greenganja.growguardian.grow.automation.Automation
import de.greenganja.growguardian.grow.automation.AutomationRules
import de.greenganja.growguardian.grow.automation.AutomationTrigger

enum class DraftKind(val label: String) { SCHEDULE("Zeitplan"), THRESHOLD("Messwert") }

/** Übliche Lichtzyklen für den Zeitplan: Stunden Licht an. */
object LightCycles {
    val HOURS = listOf(18, 12, 20, 16)

    fun label(hours: Int) = "$hours/${24 - hours}"
}

/**
 * Eingaben im Regel-Editor als Text, bevor sie eine gültige [Automation] sind.
 * Bleibt frei von Compose, damit die Umwandlung getestet werden kann.
 */
data class AutomationDraft(
    val id: String = "",
    val boxId: String,
    val name: String = "",
    val enabled: Boolean = true,
    val kind: DraftKind = DraftKind.SCHEDULE,
    val actuatorId: String? = null,
    val actuatorDpId: String? = null,
    // Messwert
    val sensorId: String? = null,
    val metric: Metric? = null,
    val switchOnAbove: Boolean = true,
    val onAt: String = "",
    val offAt: String = "",
    // Zeitplan
    val onTime: String = "06:00",
    val offTime: String = "00:00",
    val days: String = AutomationTrigger.EVERY_DAY,
) {
    /** Lichtzyklus setzen: Einschaltzeit bleibt, die Ausschaltzeit folgt. */
    fun withLightHours(hours: Int): AutomationDraft =
        AutomationRules.offTimeFor(onTime, hours)?.let { copy(offTime = it) } ?: this

    /** Einschaltzeit ändern und dabei die Länge des Lichttags halten. */
    fun withOnTime(time: String): AutomationDraft {
        val minutes = AutomationRules.lightMinutes(onTime, offTime)
        val shifted = minutes?.let { m -> AutomationRules.minutesOf(time)?.let { AutomationRules.timeOf(it + m) } }
        return copy(onTime = time, offTime = shifted ?: offTime)
    }

    fun toggleDay(index: Int): AutomationDraft {
        if (index !in 0..6 || days.length != 7) return this
        val chars = days.toCharArray()
        chars[index] = if (chars[index] == '1') '0' else '1'
        return copy(days = String(chars))
    }

    /** Sensor wechseln: Messgröße auf eine passende des neuen Sensors setzen. */
    fun withSensor(device: SensorDevice): AutomationDraft {
        val options = automationMetrics(device)
        return copy(sensorId = device.id, metric = metric?.takeIf { it in options } ?: options.firstOrNull())
    }

    /**
     * Prüft die Eingaben und baut die Regel. [base] ist die bisherige Regel beim Bearbeiten,
     * damit ihre Tuya-Automationen beim Speichern ersetzt statt doppelt angelegt werden.
     */
    fun build(devices: Map<String, SensorDevice>, base: Automation? = null): Result<Automation> {
        val actuator = actuatorId?.let { devices[it] }
        val channel = actuator?.switches?.firstOrNull { it.dpId == actuatorDpId }
        if (actuator == null || channel == null) return fail("Bitte wähle, was geschaltet werden soll.")
        val actuatorLabel = if (actuator.switches.size > 1) "${actuator.name} · ${channel.label}" else actuator.name

        val trigger: AutomationTrigger = when (kind) {
            DraftKind.SCHEDULE -> AutomationTrigger.Schedule(onTime.trim(), offTime.trim(), days)
            DraftKind.THRESHOLD -> {
                val sensor = sensorId?.let { devices[it] } ?: return fail("Bitte wähle einen Sensor.")
                val chosen = metric ?: return fail("Bitte wähle eine Messgröße.")
                val source = sensor.sources[chosen] ?: return fail("Dieser Sensor liefert ${chosen.label} nicht.")
                val on = parseNumber(onAt) ?: return fail("Bitte gib den Einschaltpunkt als Zahl ein.")
                val off = parseNumber(offAt) ?: return fail("Bitte gib den Ausschaltpunkt als Zahl ein.")
                AutomationTrigger.Threshold(sensor.id, chosen, source, switchOnAbove, on, off)
            }
        }
        val finalName = name.trim().ifEmpty { suggestedName(trigger, actuator.name) }
        val automation = Automation(
            id = base?.id ?: id,
            boxId = boxId,
            name = finalName,
            enabled = enabled,
            trigger = trigger,
            actuatorDeviceId = actuator.id,
            actuatorDpId = channel.dpId,
            actuatorLabel = actuatorLabel,
            sceneIds = base?.sceneIds.orEmpty(),
            actuatorCode = channel.code,
        )
        AutomationRules.validate(automation)?.let { return fail(it) }
        return Result.success(automation)
    }

    private fun fail(message: String): Result<Automation> = Result.failure(IllegalArgumentException(message))

    private fun suggestedName(trigger: AutomationTrigger, actuatorName: String): String {
        val text = when (trigger) {
            is AutomationTrigger.Threshold -> "${trigger.metric.label} → $actuatorName"
            is AutomationTrigger.Schedule -> {
                val hours = AutomationRules.lightMinutes(trigger.onTime, trigger.offTime)?.div(60)
                if (hours != null) "$actuatorName ${LightCycles.label(hours)}" else actuatorName
            }
        }
        return text.take(AutomationRules.MAX_NAME)
    }

    companion object {
        fun of(automation: Automation): AutomationDraft {
            val base = AutomationDraft(
                id = automation.id,
                boxId = automation.boxId,
                name = automation.name,
                enabled = automation.enabled,
                actuatorId = automation.actuatorDeviceId,
                actuatorDpId = automation.actuatorDpId,
            )
            return when (val t = automation.trigger) {
                is AutomationTrigger.Schedule -> base.copy(kind = DraftKind.SCHEDULE, onTime = t.onTime, offTime = t.offTime, days = t.days)
                is AutomationTrigger.Threshold -> base.copy(
                    kind = DraftKind.THRESHOLD,
                    sensorId = t.sensorDeviceId,
                    metric = t.metric,
                    switchOnAbove = t.switchOnAbove,
                    onAt = t.metric.formatNumber(t.onAt),
                    offAt = t.metric.formatNumber(t.offAt),
                )
            }
        }

        /** Messgrößen, die ein Sensor liefert und die Tuya in der Cloud prüfen kann. */
        fun automationMetrics(device: SensorDevice): List<Metric> = AUTOMATION_METRICS.filter { it in device.sources }

        /** Geräte, die etwas schalten können. */
        fun actuators(devices: Collection<SensorDevice>): List<SensorDevice> =
            devices.filter { it.switches.isNotEmpty() }.sortedBy { it.name.lowercase() }

        /** Geräte, die etwas messen: die der Box zuerst. */
        fun sensors(devices: Collection<SensorDevice>, boxDeviceIds: List<String>): List<SensorDevice> =
            devices.filter { automationMetrics(it).isNotEmpty() }
                .sortedWith(compareBy({ it.id !in boxDeviceIds }, { it.name.lowercase() }))
    }
}
