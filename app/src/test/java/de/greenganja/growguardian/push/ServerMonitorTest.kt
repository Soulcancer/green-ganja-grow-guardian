package de.greenganja.growguardian.push

import de.greenganja.growguardian.grow.DpDefinition
import de.greenganja.growguardian.grow.Metric
import de.greenganja.growguardian.grow.automation.Automation
import de.greenganja.growguardian.grow.automation.AutomationTrigger
import de.greenganja.growguardian.grow.automation.ServerAutomationEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerMonitorTest {
    @Test
    fun `Handy meldet nur, wenn der Server laenger nicht lief`() {
        val now = 1_800_000_000_000L
        assertTrue(ServerMonitorStatus.isActive(now - 5 * 60_000, now))
        assertFalse(ServerMonitorStatus.isActive(now - 25 * 60_000, now))
        assertFalse(ServerMonitorStatus.isActive(null, now))
        assertFalse(ServerMonitorStatus.isActive(now + 60_000, now))
    }

    @Test
    fun `Server-Automationen gelten sofort als uebertragen`() = runBlocking {
        val rule = Automation(
            id = "a1", boxId = "b1", name = "Licht", enabled = true,
            trigger = AutomationTrigger.Threshold("k1", Metric.AIR_HUMIDITY, DpDefinition("2", "va_humidity"), true, 60.0, 55.0),
            actuatorDeviceId = "p1", actuatorDpId = "1", actuatorLabel = "Dose", actuatorCode = "switch_1",
        )
        val ids = ServerAutomationEngine().apply(rule, active = true)
        assertEquals(listOf("server"), ids)
        assertTrue(rule.copy(sceneIds = ids).synced)
    }
}
