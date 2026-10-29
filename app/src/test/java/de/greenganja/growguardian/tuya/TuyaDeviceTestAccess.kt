package de.greenganja.growguardian.tuya

import de.greenganja.growguardian.grow.DpDefinition
import de.greenganja.growguardian.grow.SensorDevice

/** Baut ein Tuya-Gerät ohne SDK, damit Tests das Zusammenführen von Live-Updates prüfen können. */
object TuyaDeviceTestAccess {
    fun climateWithUpdate(changed: Map<String, Any?>): SensorDevice =
        TuyaDevice(
            id = "dev1",
            name = "Klima",
            online = true,
            category = "wsdcg",
            productId = null,
            definitions = listOf(DpDefinition("1", "va_temperature", scale = 1), DpDefinition("2", "va_humidity")),
            raw = mapOf("1" to 235, "2" to 55),
        ).withUpdate(changed).toSensor()
}
