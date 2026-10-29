package de.greenganja.growguardian.grow.alarm

import de.greenganja.growguardian.grow.GrowBoxState
import de.greenganja.growguardian.grow.MetricStatus

/** Ein Problem in einer Box, z. B. "Temperatur zu hoch" oder "Sensor offline". */
data class AlarmIssue(
    /** Stabil über Prüfungen hinweg, z. B. "box1|air_temperature" oder "box1|offline|dev7". */
    val key: String,
    val boxId: String,
    val boxName: String,
    val text: String,
    /** Deutlich daneben: sofort melden statt erst bei der zweiten Prüfung. */
    val severe: Boolean,
)

/** Was der Hintergrund-Job sich zwischen zwei Läufen merkt. */
data class AlarmEntry(val since: Long, val seen: Int, val notifiedAt: Long?)

/** Eine Benachrichtigung je Box mit allen aktuellen Problemen. */
data class BoxAlert(val boxId: String, val boxName: String, val lines: List<String>)

data class AlarmResult(
    val state: Map<String, AlarmEntry>,
    val alerts: List<BoxAlert>,
    /** Boxen, deren Benachrichtigung weg kann, weil alles wieder passt. */
    val resolvedBoxIds: Set<String>,
)

/**
 * Regeln für Alarme, ohne Android, damit sie testbar sind.
 *
 * - Gemeldet wird, wenn ein Problem bei zwei Prüfungen hintereinander besteht (etwa 15 bis 30 Minuten);
 *   deutliche Abweichungen (mehr als ein Viertel der Zielspanne daneben) sofort.
 * - Besteht das Problem weiter, kommt nach 6 Stunden eine Erinnerung.
 * - Ein Wert gilt erst wieder als gut, wenn er 5 % der Spanne innerhalb des Ziels liegt; so springt der Alarm
 *   bei Werten an der Grenze nicht ständig an und aus.
 */
object AlarmRules {
    const val REMIND_AFTER_MS = 6 * 60 * 60 * 1000L
    private const val SEVERE_SHARE = 0.25
    private const val HYSTERESIS_SHARE = 0.05

    fun issues(state: GrowBoxState, activeKeys: Set<String>): List<AlarmIssue> {
        val box = state.box
        val result = mutableListOf<AlarmIssue>()
        for (reading in state.readings) {
            val target = reading.target ?: continue
            val key = "${box.id}|${reading.metric.id}"
            val width = (target.max - target.min).coerceAtLeast(1e-9)
            val margin = if (key in activeKeys) width * HYSTERESIS_SHARE else 0.0
            val low = reading.value < target.min + margin
            val high = reading.value > target.max - margin
            val outside = reading.status == MetricStatus.LOW || reading.status == MetricStatus.HIGH
            if (!outside && !(key in activeKeys && (low || high))) continue
            val tooHigh = if (outside) reading.status == MetricStatus.HIGH else high
            val distance = if (tooHigh) reading.value - target.max else target.min - reading.value
            result += AlarmIssue(
                key = key,
                boxId = box.id,
                boxName = box.name,
                text = "${reading.metric.label} ${if (tooHigh) "zu hoch" else "zu niedrig"}: ${reading.formatted} (Ziel ${target.format(reading.metric)})",
                severe = distance > width * SEVERE_SHARE,
            )
        }
        for (device in state.devices) {
            if (device.online) continue
            result += AlarmIssue("${box.id}|offline|${device.id}", box.id, box.name, "Sensor „${device.name}“ ist offline", severe = false)
        }
        return result
    }

    fun evaluate(previous: Map<String, AlarmEntry>, issues: List<AlarmIssue>, now: Long): AlarmResult {
        val state = issues.associate { issue ->
            val before = previous[issue.key]
            issue.key to AlarmEntry(since = before?.since ?: now, seen = (before?.seen ?: 0) + 1, notifiedAt = before?.notifiedAt)
        }.toMutableMap()

        val alerts = mutableListOf<BoxAlert>()
        for ((boxId, boxIssues) in issues.groupBy { it.boxId }) {
            val due = boxIssues.any { issue ->
                val entry = state.getValue(issue.key)
                val notified = entry.notifiedAt
                if (notified == null) entry.seen >= 2 || issue.severe else now - notified >= REMIND_AFTER_MS
            }
            if (!due) continue
            alerts += BoxAlert(boxId, boxIssues.first().boxName, boxIssues.map { it.text })
            for (issue in boxIssues) state[issue.key] = state.getValue(issue.key).copy(notifiedAt = now)
        }

        val boxesWithIssues = issues.map { it.boxId }.toSet()
        val resolved = previous.filterValues { it.notifiedAt != null }.keys
            .map { it.substringBefore('|') }
            .filter { it !in boxesWithIssues }
            .toSet()
        return AlarmResult(state, alerts, resolved)
    }

    /** Einfaches Textformat für SharedPreferences: eine Zeile je Eintrag. */
    fun encode(state: Map<String, AlarmEntry>): String =
        state.entries.joinToString("\n") { (key, e) -> listOf(key, e.since, e.seen, e.notifiedAt ?: "-").joinToString("\t") }

    fun decode(text: String?): Map<String, AlarmEntry> =
        text.orEmpty().lineSequence().mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size != 4) return@mapNotNull null
            val since = parts[1].toLongOrNull() ?: return@mapNotNull null
            val seen = parts[2].toIntOrNull() ?: return@mapNotNull null
            parts[0] to AlarmEntry(since, seen, parts[3].toLongOrNull())
        }.toMap()
}
