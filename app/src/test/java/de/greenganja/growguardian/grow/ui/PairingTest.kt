package de.greenganja.growguardian.grow.ui

import de.greenganja.growguardian.tuya.TuyaException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingTest {

    @Test
    fun `fragt je nach Android-Version die richtigen Berechtigungen an`() {
        assertEquals(
            listOf("android.permission.BLUETOOTH_SCAN", "android.permission.BLUETOOTH_CONNECT", "android.permission.ACCESS_FINE_LOCATION"),
            pairingPermissions(PairingMethod.NEARBY, sdkInt = 34).toList(),
        )
        assertEquals(listOf("android.permission.ACCESS_FINE_LOCATION"), pairingPermissions(PairingMethod.NEARBY, sdkInt = 30).toList())
        assertEquals(listOf("android.permission.ACCESS_FINE_LOCATION"), pairingPermissions(PairingMethod.WIFI, sdkInt = 34).toList())
        assertTrue(pairingPermissions(PairingMethod.ZIGBEE, sdkInt = 34).isEmpty())
    }

    @Test
    fun `übersetzt Kopplungsfehler`() {
        assertEquals("Kopplung fehlgeschlagen: timeout", pairingMessage(TuyaException("1", "timeout")))
        assertEquals("Schon gekoppelt.", pairingMessage(TuyaException("limited", "Schon gekoppelt.")))
        assertTrue(pairingMessage(IllegalStateException()).contains("2,4-GHz"))
    }

    @Test
    fun `WLAN-Formular braucht einen Namen`() {
        assertEquals(false, PairingUiState(ssid = " ").wifiReady)
        assertEquals(true, PairingUiState(ssid = "Growroom").wifiReady)
    }
}
