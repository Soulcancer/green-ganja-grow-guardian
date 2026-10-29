package de.greenganja.growguardian.grow.automation

import de.greenganja.growguardian.grow.DpDefinition
import de.greenganja.growguardian.grow.DpMapping
import de.greenganja.growguardian.grow.Metric
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationRulesTest {

    private val humidity = DpDefinition(id = "2", code = "va_humidity", scale = 0, unit = "%")
    private val temperature = DpDefinition(id = "1", code = "va_temperature", scale = 1, unit = "°C")

    private fun threshold(
        metric: Metric = Metric.AIR_HUMIDITY,
        source: DpDefinition = humidity,
        above: Boolean = true,
        onAt: Double = 60.0,
        offAt: Double = 55.0,
        sensor: String = "klima1",
    ) = AutomationTrigger.Threshold(sensor, metric, source, above, onAt, offAt)

    private fun rule(trigger: AutomationTrigger, name: String = "Regel", actuator: String = "plug1") = Automation(
        id = "a1", boxId = "box1", name = name, enabled = true, trigger = trigger,
        actuatorDeviceId = actuator, actuatorDpId = "1", actuatorLabel = "Steckdose",
    )

    @Test
    fun `gueltige Regeln bestehen die Pruefung`() {
        assertNull(AutomationRules.validate(rule(threshold())))
        assertNull(AutomationRules.validate(rule(AutomationTrigger.Schedule("06:00", "00:00"))))
    }

    @Test
    fun `Schwellwerte brauchen Abstand in der richtigen Richtung`() {
        assertNotNull(AutomationRules.validate(rule(threshold(onAt = 60.0, offAt = 60.0))))
        assertNotNull(AutomationRules.validate(rule(threshold(above = false, onAt = 18.0, offAt = 16.0))))
        assertNull(AutomationRules.validate(rule(threshold(above = false, onAt = 18.0, offAt = 20.0))))
        assertNotNull(AutomationRules.validate(rule(threshold(onAt = Double.NaN))))
    }

    @Test
    fun `Name Sensor und Messgroesse werden geprueft`() {
        assertNotNull(AutomationRules.validate(rule(threshold(), name = " ")))
        assertNotNull(AutomationRules.validate(rule(threshold(), name = "x".repeat(41))))
        assertNotNull(AutomationRules.validate(rule(threshold(sensor = "plug1"))))
        assertNotNull(AutomationRules.validate(rule(threshold(metric = Metric.VPD))))
        assertNotNull(AutomationRules.validate(rule(threshold(), actuator = "")))
    }

    @Test
    fun `Zeitplan wird geprueft`() {
        assertNotNull(AutomationRules.validate(rule(AutomationTrigger.Schedule("6:00", "00:00"))))
        assertNotNull(AutomationRules.validate(rule(AutomationTrigger.Schedule("24:00", "00:00"))))
        assertNotNull(AutomationRules.validate(rule(AutomationTrigger.Schedule("06:00", "06:00"))))
        assertNotNull(AutomationRules.validate(rule(AutomationTrigger.Schedule("06:00", "18:00", days = "0000000"))))
        assertNotNull(AutomationRules.validate(rule(AutomationTrigger.Schedule("06:00", "18:00", days = "111"))))
    }

    @Test
    fun `Lichtzyklen rechnen ueber Mitternacht`() {
        assertEquals("00:00", AutomationRules.offTimeFor("06:00", 18))
        assertEquals("18:00", AutomationRules.offTimeFor("06:00", 12))
        assertEquals("02:00", AutomationRules.offTimeFor("22:00", 4))
        assertEquals(18 * 60, AutomationRules.lightMinutes("06:00", "00:00"))
        assertEquals(12 * 60, AutomationRules.lightMinutes("18:00", "06:00"))
        assertNull(AutomationRules.lightMinutes("abc", "06:00"))
    }

    @Test
    fun `Schwellwert wird zu zwei Szenen mit Rohwerten`() {
        val plan = AutomationRules.plan(rule(threshold(metric = Metric.AIR_TEMPERATURE, source = temperature, onAt = 28.5, offAt = 26.0)))
        assertEquals(2, plan.size)
        assertTrue(plan[0].turnOn)
        assertEquals(PlannedCondition.Value("klima1", "1", ">", 285), plan[0].condition)
        assertFalse(plan[1].turnOn)
        assertEquals(PlannedCondition.Value("klima1", "1", "<", 260), plan[1].condition)
    }

    @Test
    fun `Heizen schaltet bei Unterschreiten ein`() {
        val plan = AutomationRules.plan(rule(threshold(above = false, onAt = 18.0, offAt = 20.0)))
        assertEquals("<", (plan[0].condition as PlannedCondition.Value).operator)
        assertEquals(">", (plan[1].condition as PlannedCondition.Value).operator)
    }

    @Test
    fun `Zeitplan ueber Mitternacht schaltet am Folgetag aus`() {
        // Licht 06:00 bis 00:00, nur Montag (Sonntag zuerst: Index 1)
        val plan = AutomationRules.plan(rule(AutomationTrigger.Schedule("06:00", "00:00", days = "0100000")))
        assertEquals(PlannedCondition.Time("06:00", "0100000"), plan[0].condition)
        assertEquals(PlannedCondition.Time("00:00", "0010000"), plan[1].condition)
        // Am selben Tag bleibt der Tag gleich
        val sameDay = AutomationRules.plan(rule(AutomationTrigger.Schedule("06:00", "18:00", days = "0100000")))
        assertEquals(PlannedCondition.Time("18:00", "0100000"), sameDay[1].condition)
    }

    @Test
    fun `Tage rutschen um einen weiter`() {
        assertEquals("1000000", AutomationRules.nextDays("0000001")) // Samstag -> Sonntag
        assertEquals("1111111", AutomationRules.nextDays("1111111"))
        assertEquals("0011111", AutomationRules.nextDays("0111110")) // Mo–Fr -> Di–Sa
        assertEquals("1001111", AutomationRules.nextDays("0011111"))
    }

    @Test
    fun `Beschreibungen sind lesbar`() {
        assertEquals("an über 60 %, aus unter 55 %", AutomationRules.describe(threshold()))
        assertEquals("an unter 18 %, aus über 20 %", AutomationRules.describe(threshold(above = false, onAt = 18.0, offAt = 20.0)))
        assertEquals("an 06:00, aus 00:00 · täglich (18 Std. an)", AutomationRules.describe(AutomationTrigger.Schedule("06:00", "00:00")))
        assertEquals("Mo–Fr", AutomationRules.daysLabel("0111110"))
        assertEquals("Mo, Mi", AutomationRules.daysLabel("0101000"))
    }

    @Test
    fun `Rohwerte und Schalter-Erkennung`() {
        assertEquals(235, DpMapping.toRaw(temperature, Metric.AIR_TEMPERATURE, 23.5))
        assertEquals(60, DpMapping.toRaw(humidity, Metric.AIR_HUMIDITY, 60.0))
        // °F-Sensor: 20 °C = 68 °F
        assertEquals(680, DpMapping.toRaw(temperature.copy(unit = "°F"), Metric.AIR_TEMPERATURE, 20.0))
        assertTrue(DpMapping.isSwitchCode("switch"))
        assertTrue(DpMapping.isSwitchCode("switch_2"))
        assertFalse(DpMapping.isSwitchCode("switch_led"))
        assertFalse(DpMapping.isSwitchCode("countdown_1"))
        assertEquals("Schalter", DpMapping.switchLabel("switch_1", 1))
        assertEquals("Dose 2", DpMapping.switchLabel("switch_2", 4))
        assertEquals("Schalter", DpMapping.switchLabel("switch", 4))
    }

    @Test
    fun `synced braucht IDs und keinen Fehler`() {
        val base = rule(threshold())
        assertFalse(base.synced)
        assertTrue(base.copy(sceneIds = listOf("s1", "s2")).synced)
        assertFalse(base.copy(sceneIds = listOf("s1", "s2"), syncError = "kaputt").synced)
    }
}
