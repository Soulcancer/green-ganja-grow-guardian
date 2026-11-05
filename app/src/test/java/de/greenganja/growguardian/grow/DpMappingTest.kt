package de.greenganja.growguardian.grow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DpMappingTest {

    @Test
    fun `Helligkeitssensor liefert Lux`() {
        val definitions = listOf(
            DpDefinition("2", "bright_value", scale = 0, unit = "lux"),
            DpDefinition("4", "battery_percentage", scale = 0, unit = "%"),
        )
        assertEquals(DeviceKind.LIGHT, DpMapping.kindOf("ldcg", definitions.map { it.code }))
        assertEquals(mapOf(Metric.LIGHT to 42_000.0), DpMapping.read(definitions, mapOf("2" to 42_000, "4" to 80), category = "ldcg"))
        // Klimasensor mit eingebautem Lichtsensor bleibt Klimasensor
        val combo = listOf(DpDefinition("1", "va_temperature", scale = 1), DpDefinition("3", "bright_value"))
        assertEquals(DeviceKind.CLIMATE, DpMapping.kindOf("wsdcg", combo.map { it.code }))
        assertEquals(mapOf(Metric.AIR_TEMPERATURE to 24.0, Metric.LIGHT to 300.0), DpMapping.read(combo, mapOf("1" to 240, "3" to 300), category = "wsdcg"))
    }

    @Test
    fun `liest Klimasensor mit Skalierung`() {
        val definitions = listOf(
            DpDefinition("1", "va_temperature", scale = 1, unit = "℃"),
            DpDefinition("2", "va_humidity", scale = 0, unit = "%"),
            DpDefinition("4", "battery_percentage", scale = 0, unit = "%"),
        )
        val values = DpMapping.read(definitions, mapOf("1" to 235, "2" to 58, "4" to 90), category = "wsdcg")
        assertEquals(mapOf(Metric.AIR_TEMPERATURE to 23.5, Metric.AIR_HUMIDITY to 58.0), values)
    }

    @Test
    fun `erkennt Wassertester, rechnet EC in mS pro cm um und verwirft Fehlwerte`() {
        val definitions = listOf(
            DpDefinition("8", "temp_current", scale = 1, unit = "℃"),
            DpDefinition("106", "ph_current", scale = 2, unit = "pH"),
            DpDefinition("116", "ec_current", scale = 0, unit = "us/cm"),
            DpDefinition("111", "tds_current", scale = 0, unit = "ppm"),
        )
        assertEquals(DeviceKind.WATER, DpMapping.kindOf("dgnbj", definitions.map { it.code }))
        val values = DpMapping.read(definitions, mapOf("8" to 206, "106" to 612, "116" to 1450, "111" to 725), category = "dgnbj")
        assertEquals(20.6, values[Metric.WATER_TEMPERATURE]!!, 1e-9)
        assertEquals(6.12, values[Metric.PH]!!, 1e-9)
        assertEquals(1.45, values[Metric.EC]!!, 1e-9)
        assertEquals(725.0, values[Metric.TDS]!!, 1e-9)
        assertFalse(Metric.AIR_TEMPERATURE in values)

        val unplugged = DpMapping.read(definitions, mapOf("106" to 1500), category = "dgnbj")
        assertFalse(Metric.PH in unplugged)
    }

    @Test
    fun `Bodensensor liefert Bodenfeuchte statt Luftfeuchte`() {
        val definitions = listOf(
            DpDefinition("3", "humidity", scale = 0, unit = "%"),
            DpDefinition("5", "temp_current", scale = 1, unit = "℃"),
        )
        val values = DpMapping.read(definitions, mapOf("3" to 41, "5" to 198), category = "zwjcy")
        assertEquals(mapOf(Metric.SOIL_MOISTURE to 41.0, Metric.SOIL_TEMPERATURE to 19.8), values)
    }

    @Test
    fun `CO2-Melder und Steckdose`() {
        val co2 = DpMapping.read(
            listOf(DpDefinition("2", "co2_value", unit = "ppm"), DpDefinition("18", "temp_current", scale = 1, unit = "℃")),
            mapOf("2" to 860, "18" to 241),
            category = "co2bj",
        )
        assertEquals(mapOf(Metric.CO2 to 860.0, Metric.AIR_TEMPERATURE to 24.1), co2)

        val plug = DpMapping.read(
            listOf(DpDefinition("1", "switch_1"), DpDefinition("19", "cur_power", scale = 1, unit = "W")),
            mapOf("1" to true, "19" to 1235),
            category = "cz",
        )
        assertEquals(mapOf(Metric.POWER to 123.5), plug)
    }

    @Test
    fun `rechnet Fahrenheit um und erlaubt eigene Zuordnungen`() {
        val fahrenheit = DpMapping.read(listOf(DpDefinition("1", "temp_current", scale = 1, unit = "℉")), mapOf("1" to 770), category = "wsdcg")
        assertEquals(25.0, fahrenheit[Metric.AIR_TEMPERATURE]!!, 1e-9)

        DpMapping.overrides["prod123"] = mapOf("leaf_temp" to Metric.AIR_TEMPERATURE)
        try {
            val custom = DpMapping.read(listOf(DpDefinition("101", "leaf_temp", scale = 1)), mapOf("101" to 240), category = null, productId = "prod123")
            assertEquals(24.0, custom[Metric.AIR_TEMPERATURE]!!, 1e-9)
        } finally {
            DpMapping.overrides.remove("prod123")
        }
        assertTrue(DpMapping.read(listOf(DpDefinition("101", "leaf_temp", scale = 1)), mapOf("101" to 240), category = null).isEmpty())
    }
}
