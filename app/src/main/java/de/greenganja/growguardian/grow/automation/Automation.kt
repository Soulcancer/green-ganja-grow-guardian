package de.greenganja.growguardian.grow.automation

import de.greenganja.growguardian.grow.DpDefinition
import de.greenganja.growguardian.grow.Metric
import java.util.Locale

/** Was eine Regel auslöst. */
sealed interface AutomationTrigger {

    /**
     * Ein- und Ausschaltpunkt mit Abstand (Hysterese), damit Geräte nicht ständig takten.
     * Entfeuchter: [switchOnAbove] = true, an über 60 %, aus unter 55 %.
     * Heizmatte: [switchOnAbove] = false, an unter 18 °C, aus über 20 °C.
     */
    data class Threshold(
        val sensorDeviceId: String,
        val metric: Metric,
        /** Datenpunkt des Sensors mit Skalierung und Einheit, für den Tuya-Rohwert. */
        val source: DpDefinition,
        val switchOnAbove: Boolean,
        val onAt: Double,
        val offAt: Double,
    ) : AutomationTrigger

    /** Zeitplan, z. B. Licht 06:00 an, 00:00 aus = 18 Stunden. */
    data class Schedule(
        val onTime: String,
        val offTime: String,
        /** 7 Zeichen, Sonntag zuerst, "1" = aktiv. Wie bei Tuya. */
        val days: String = EVERY_DAY,
    ) : AutomationTrigger

    companion object {
        const val EVERY_DAY = "1111111"
    }
}

/** Eine Regel einer Box. [sceneIds] sind die zugehörigen Tuya-Automationen (an und aus). */
data class Automation(
    val id: String,
    val boxId: String,
    val name: String,
    val enabled: Boolean,
    val trigger: AutomationTrigger,
    val actuatorDeviceId: String,
    val actuatorDpId: String,
    /** z. B. "Steckdose Licht · Dose 2", für die Anzeige. */
    val actuatorLabel: String,
    val sceneIds: List<String> = emptyList(),
    val syncError: String? = null,
    /** DP-Code des Schalters (z. B. "switch_2"), für die Ausführung auf dem Server. */
    val actuatorCode: String = "",
) {
    /** In der Tuya-Cloud angelegt und ohne Fehler. */
    val synced: Boolean get() = sceneIds.isNotEmpty() && syncError == null
}

/** Messgrößen, die direkt von einem Sensor-Datenpunkt kommen und deshalb in der Tuya-Cloud prüfbar sind. */
val AUTOMATION_METRICS = listOf(
    Metric.AIR_TEMPERATURE,
    Metric.AIR_HUMIDITY,
    Metric.CO2,
    Metric.SOIL_MOISTURE,
    Metric.SOIL_TEMPERATURE,
    Metric.WATER_TEMPERATURE,
)

object AutomationRules {
    const val MAX_NAME = 40

    /** Deutsche Fehlermeldung oder null, wenn die Regel gültig ist. Gleiche Regeln wie im Backend. */
    fun validate(automation: Automation): String? {
        val name = automation.name.trim()
        if (name.isEmpty() || name.length > MAX_NAME) return "Der Name muss 1 bis $MAX_NAME Zeichen haben."
        if (automation.actuatorDeviceId.isBlank() || automation.actuatorDpId.isBlank()) return "Bitte ein Gerät zum Schalten wählen."
        return when (val t = automation.trigger) {
            is AutomationTrigger.Threshold -> when {
                t.metric !in AUTOMATION_METRICS -> "${t.metric.label} kann die Tuya-Cloud nicht direkt prüfen."
                t.sensorDeviceId == automation.actuatorDeviceId -> "Sensor und geschaltetes Gerät müssen verschieden sein."
                !t.onAt.isFinite() || !t.offAt.isFinite() -> "Bitte Ein- und Ausschaltpunkt eingeben."
                t.switchOnAbove && t.offAt >= t.onAt -> "Der Ausschaltpunkt muss unter dem Einschaltpunkt liegen."
                !t.switchOnAbove && t.offAt <= t.onAt -> "Der Ausschaltpunkt muss über dem Einschaltpunkt liegen."
                else -> null
            }
            is AutomationTrigger.Schedule -> when {
                minutesOf(t.onTime) == null || minutesOf(t.offTime) == null -> "Bitte Zeiten als HH:MM eingeben, z. B. 06:00."
                t.onTime == t.offTime -> "Ein- und Ausschaltzeit müssen verschieden sein."
                !Regex("^[01]{7}$").matches(t.days) || !t.days.contains('1') -> "Bitte mindestens einen Tag wählen."
                else -> null
            }
        }
    }

    /** "06:00" → 360, ungültig → null. */
    fun minutesOf(time: String): Int? {
        val match = Regex("^([01][0-9]|2[0-3]):([0-5][0-9])$").matchEntire(time.trim()) ?: return null
        return match.groupValues[1].toInt() * 60 + match.groupValues[2].toInt()
    }

    fun timeOf(minutes: Int): String {
        val m = Math.floorMod(minutes, 24 * 60)
        return String.format(Locale.ROOT, "%02d:%02d", m / 60, m % 60)
    }

    /** Ausschaltzeit für einen Lichtzyklus, z. B. 06:00 + 18 Std. = 00:00. */
    fun offTimeFor(onTime: String, lightHours: Int): String? = minutesOf(onTime)?.let { timeOf(it + lightHours * 60) }

    /** Stunden zwischen Ein- und Ausschalten, über Mitternacht hinweg. */
    fun lightMinutes(onTime: String, offTime: String): Int? {
        val on = minutesOf(onTime) ?: return null
        val off = minutesOf(offTime) ?: return null
        return Math.floorMod(off - on, 24 * 60)
    }

    fun daysLabel(days: String): String = when (days) {
        AutomationTrigger.EVERY_DAY -> "täglich"
        "0111110" -> "Mo–Fr"
        "1000001" -> "Sa und So"
        else -> DAY_NAMES.filterIndexed { i, _ -> days.getOrNull(i) == '1' }.joinToString(", ")
    }

    /** Sonntag zuerst, wie Tuya. */
    val DAY_NAMES = listOf("So", "Mo", "Di", "Mi", "Do", "Fr", "Sa")

    /** Kurze Beschreibung für Listen, z. B. "an über 60 %, aus unter 55 %". */
    fun describe(trigger: AutomationTrigger): String = when (trigger) {
        is AutomationTrigger.Threshold -> {
            val m = trigger.metric
            if (trigger.switchOnAbove) "an über ${m.format(trigger.onAt)}, aus unter ${m.format(trigger.offAt)}"
            else "an unter ${m.format(trigger.onAt)}, aus über ${m.format(trigger.offAt)}"
        }
        is AutomationTrigger.Schedule -> {
            val hours = lightMinutes(trigger.onTime, trigger.offTime)?.let { minutes ->
                val h = minutes / 60
                val rest = minutes % 60
                if (rest == 0) " ($h Std. an)" else " ($h Std. $rest Min. an)"
            }.orEmpty()
            "an ${trigger.onTime}, aus ${trigger.offTime} · ${daysLabel(trigger.days)}$hours"
        }
    }

    /**
     * Die beiden Tuya-Automationen einer Regel: wann einschalten, wann ausschalten.
     * Bei Schwellwerten mit Rohwerten des Sensors; Tuya vergleicht nur mit "<" und ">".
     */
    fun plan(automation: Automation): List<PlannedScene> = when (val t = automation.trigger) {
        is AutomationTrigger.Threshold -> {
            val onRaw = de.greenganja.growguardian.grow.DpMapping.toRaw(t.source, t.metric, t.onAt)
            val offRaw = de.greenganja.growguardian.grow.DpMapping.toRaw(t.source, t.metric, t.offAt)
            listOf(
                PlannedScene(turnOn = true, condition = PlannedCondition.Value(t.sensorDeviceId, t.source.id, if (t.switchOnAbove) ">" else "<", onRaw)),
                PlannedScene(turnOn = false, condition = PlannedCondition.Value(t.sensorDeviceId, t.source.id, if (t.switchOnAbove) "<" else ">", offRaw)),
            )
        }
        is AutomationTrigger.Schedule -> {
            // Über Mitternacht (06:00 bis 00:00): Ausschalten gehört zum Folgetag
            val crossesMidnight = (minutesOf(t.offTime) ?: 0) <= (minutesOf(t.onTime) ?: 0)
            listOf(
                PlannedScene(turnOn = true, condition = PlannedCondition.Time(t.onTime, t.days)),
                PlannedScene(turnOn = false, condition = PlannedCondition.Time(t.offTime, if (crossesMidnight) nextDays(t.days) else t.days)),
            )
        }
    }

    /** Jeder aktive Tag verschiebt sich um einen Tag nach hinten (Sa → So). */
    fun nextDays(days: String): String = days.last() + days.dropLast(1)
}

/** Bauplan einer Tuya-Automation, unabhängig vom SDK und damit testbar. */
data class PlannedScene(val turnOn: Boolean, val condition: PlannedCondition)

sealed interface PlannedCondition {
    data class Value(val deviceId: String, val dpId: String, val operator: String, val raw: Int) : PlannedCondition
    data class Time(val time: String, val days: String) : PlannedCondition
}

/** Führt Regeln aus. In der App [de.greenganja.growguardian.tuya.TuyaAutomationSync] (Tuya-Cloud). */
interface AutomationEngine {
    /** Legt die Regel (neu) an und liefert die IDs der Automationen. [active] = false legt sie pausiert an. */
    suspend fun apply(automation: Automation, active: Boolean): List<String>

    suspend fun setEnabled(sceneIds: List<String>, enabled: Boolean)

    suspend fun remove(sceneIds: List<String>)

    /** Entfernt Automationen der App, die zu keiner Regel gehören. Liefert die Anzahl. */
    suspend fun cleanup(knownSceneIds: Set<String>): Int
}

/** Wo die Regeln liegen. In der App [AutomationRepository] (Firestore), in Tests ein Fake. */
interface AutomationStore {
    fun automations(): kotlinx.coroutines.flow.Flow<List<Automation>>
    suspend fun automationsOnce(): List<Automation>

    /** Anlegen (leere ID) oder speichern; liefert die ID. */
    suspend fun save(automation: Automation): Result<String>
    suspend fun delete(id: String): Result<Unit>

    /** Ergebnis der Übertragung in die Tuya-Cloud festhalten. */
    suspend fun recordSync(id: String, sceneIds: List<String>, error: String?)
}

/** Kleiner Merkzettel des Geräts (SharedPreferences), austauschbar für Tests. */
interface AutomationMemo {
    fun flag(key: String): Boolean?
    fun setFlag(key: String, value: Boolean)
    fun time(key: String): Long
    fun setTime(key: String, value: Long)
}
