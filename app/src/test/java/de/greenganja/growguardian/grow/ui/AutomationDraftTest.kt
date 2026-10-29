package de.greenganja.growguardian.grow.ui

import de.greenganja.growguardian.grow.DeviceKind
import de.greenganja.growguardian.grow.DpDefinition
import de.greenganja.growguardian.grow.Metric
import de.greenganja.growguardian.grow.SensorDevice
import de.greenganja.growguardian.grow.SwitchChannel
import de.greenganja.growguardian.grow.automation.Automation
import de.greenganja.growguardian.grow.automation.AutomationTrigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationDraftTest {

    private val klima = SensorDevice(
        id = "klima1", name = "Klima", online = true, kind = DeviceKind.CLIMATE,
        values = mapOf(Metric.AIR_HUMIDITY to 58.0),
        sources = mapOf(
            Metric.AIR_HUMIDITY to DpDefinition("2", "va_humidity", 0, "%"),
            Metric.AIR_TEMPERATURE to DpDefinition("1", "va_temperature", 1, "°C"),
            Metric.VPD to DpDefinition("9", "vpd", 2, "kPa"),
        ),
    )
    private val leiste = SensorDevice(
        id = "plug1", name = "Leiste", online = true, kind = DeviceKind.CLIMATE, values = emptyMap(),
        switches = listOf(SwitchChannel("1", "switch_1", "Dose 1", true), SwitchChannel("2", "switch_2", "Dose 2", false)),
    )
    private val dose = leiste.copy(id = "plug2", name = "Entfeuchter", switches = listOf(SwitchChannel("1", "switch", "Schalter", null)))
    private val devices = listOf(klima, leiste, dose).associateBy { it.id }

    private fun blank() = AutomationDraft(boxId = "box1")

    @Test
    fun `Lichtzyklus setzt die Ausschaltzeit`() {
        val draft = blank().withLightHours(18)
        assertEquals("06:00", draft.onTime)
        assertEquals("00:00", draft.offTime)
        assertEquals("18:00", draft.withLightHours(12).offTime)
    }

    @Test
    fun `Neue Einschaltzeit haelt die Laenge des Lichttags`() {
        val draft = blank().withLightHours(12).withOnTime("07:30")
        assertEquals("19:30", draft.offTime)
        // halb getippte Zeit ändert nur die Einschaltzeit
        assertEquals("19:30", draft.withOnTime("07:3").offTime)
        assertEquals("07:3", draft.withOnTime("07:3").onTime)
    }

    @Test
    fun `Tage lassen sich umschalten`() {
        val draft = blank().toggleDay(0).toggleDay(6)
        assertEquals("0111110", draft.days)
        assertEquals("1111110", draft.toggleDay(0).days)
        assertEquals(draft, draft.toggleDay(9))
    }

    @Test
    fun `Zeitplan wird zur Regel mit Namensvorschlag`() {
        val draft = blank().copy(kind = DraftKind.SCHEDULE, actuatorId = "plug1", actuatorDpId = "2").withLightHours(18)
        val rule = draft.build(devices).getOrThrow()
        assertEquals("Leiste 18/6", rule.name)
        assertEquals("Leiste · Dose 2", rule.actuatorLabel)
        assertEquals("2", rule.actuatorDpId)
        assertEquals(AutomationTrigger.Schedule("06:00", "00:00", "1111111"), rule.trigger)
        assertEquals("", rule.id)
    }

    @Test
    fun `Einzelsteckdose braucht keinen Kanalnamen`() {
        val rule = blank().copy(actuatorId = "plug2", actuatorDpId = "1", name = "Trocknen").build(devices).getOrThrow()
        assertEquals("Entfeuchter", rule.actuatorLabel)
        assertEquals("Trocknen", rule.name)
    }

    @Test
    fun `Messwert-Regel liest Kommazahlen und nimmt die Quelle vom Sensor`() {
        val draft = blank().copy(kind = DraftKind.THRESHOLD, actuatorId = "plug2", actuatorDpId = "1")
            .withSensor(klima).copy(metric = Metric.AIR_HUMIDITY, onAt = "60,5", offAt = "55")
        val rule = draft.build(devices).getOrThrow()
        val trigger = rule.trigger as AutomationTrigger.Threshold
        assertEquals("klima1", trigger.sensorDeviceId)
        assertEquals("2", trigger.source.id)
        assertEquals(60.5, trigger.onAt, 0.0)
        assertTrue(trigger.switchOnAbove)
        assertEquals("Luftfeuchte → Entfeuchter", rule.name)
    }

    @Test
    fun `Fehlende oder falsche Eingaben ergeben deutsche Hinweise`() {
        val ok = blank().copy(kind = DraftKind.THRESHOLD, actuatorId = "plug2", actuatorDpId = "1").withSensor(klima).copy(onAt = "60", offAt = "55")
        assertEquals("Bitte wähle, was geschaltet werden soll.", blank().build(devices).exceptionOrNull()?.message)
        assertEquals("Bitte wähle einen Sensor.", ok.copy(sensorId = null).build(devices).exceptionOrNull()?.message)
        assertEquals("Bitte gib den Einschaltpunkt als Zahl ein.", ok.copy(onAt = "").build(devices).exceptionOrNull()?.message)
        assertEquals("Der Ausschaltpunkt muss unter dem Einschaltpunkt liegen.", ok.copy(offAt = "65").build(devices).exceptionOrNull()?.message)
        assertEquals("Dieser Sensor liefert CO₂ nicht.", ok.copy(metric = Metric.CO2).build(devices).exceptionOrNull()?.message)
        assertNotNull(ok.copy(actuatorId = "plug1", actuatorDpId = "9").build(devices).exceptionOrNull())
    }

    @Test
    fun `Bearbeiten behaelt ID und Tuya-Automationen`() {
        val original = Automation(
            id = "a7", boxId = "box1", name = "Licht", enabled = false,
            trigger = AutomationTrigger.Schedule("06:00", "00:00"), actuatorDeviceId = "plug1", actuatorDpId = "1",
            actuatorLabel = "Leiste · Dose 1", sceneIds = listOf("s1", "s2"),
        )
        val draft = AutomationDraft.of(original)
        assertEquals(DraftKind.SCHEDULE, draft.kind)
        assertFalse(draft.enabled)
        val rebuilt = draft.copy(offTime = "02:00").build(devices, original).getOrThrow()
        assertEquals("a7", rebuilt.id)
        assertEquals(listOf("s1", "s2"), rebuilt.sceneIds)
        assertEquals("02:00", (rebuilt.trigger as AutomationTrigger.Schedule).offTime)
    }

    @Test
    fun `Sensor-Wechsel waehlt eine passende Messgroesse`() {
        val draft = blank().withSensor(klima)
        assertEquals(Metric.AIR_TEMPERATURE, draft.metric) // Reihenfolge der Messgrößen, VPD ist nicht dabei
        assertEquals(listOf(Metric.AIR_TEMPERATURE, Metric.AIR_HUMIDITY), AutomationDraft.automationMetrics(klima))
        assertEquals(Metric.AIR_HUMIDITY, draft.copy(metric = Metric.AIR_HUMIDITY).withSensor(klima).metric)
    }

    @Test
    fun `Auswahllisten trennen Sensoren und Schalter`() {
        assertEquals(listOf("plug2", "plug1"), AutomationDraft.actuators(devices.values).map { it.id }) // nach Name sortiert
        assertEquals(listOf("klima1"), AutomationDraft.sensors(devices.values, listOf("klima1")).map { it.id })
    }

    @Test
    fun `Bearbeiten zeigt Zahlen mit den Stellen der Messgroesse`() {
        val rule = Automation(
            id = "a1", boxId = "box1", name = "Heizen", enabled = true,
            trigger = AutomationTrigger.Threshold("klima1", Metric.AIR_TEMPERATURE, DpDefinition("1", "va_temperature", 1, "°C"), false, 18.0, 20.0),
            actuatorDeviceId = "plug2", actuatorDpId = "1", actuatorLabel = "Entfeuchter",
        )
        val draft = AutomationDraft.of(rule)
        assertEquals("18,0", draft.onAt)
        assertEquals("20,0", draft.offAt)
        assertFalse(draft.switchOnAbove)
        assertTrue(draft.build(devices, rule).isSuccess)
    }
}
