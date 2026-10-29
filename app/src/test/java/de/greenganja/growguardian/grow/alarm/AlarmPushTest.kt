package de.greenganja.growguardian.grow.alarm

import de.greenganja.growguardian.billing.FarmerTier
import de.greenganja.growguardian.grow.DeviceKind
import de.greenganja.growguardian.grow.DpDefinition
import de.greenganja.growguardian.grow.GrowBox
import de.greenganja.growguardian.grow.GrowBoxState
import de.greenganja.growguardian.grow.GrowPhase
import de.greenganja.growguardian.grow.Metric
import de.greenganja.growguardian.grow.SensorDevice
import de.greenganja.growguardian.grow.TargetRange
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmPushTest {
    private val premium = FarmerTier.entries.first { it.features.alerts }.features
    private val free = FarmerTier.entries.first { !it.features.alerts }.features

    private val sensor = SensorDevice(
        "k1", "Klima", true, DeviceKind.CLIMATE, mapOf(Metric.AIR_TEMPERATURE to 24.0, Metric.AIR_HUMIDITY to 50.0),
        sources = mapOf(
            Metric.AIR_TEMPERATURE to DpDefinition("1", "va_temperature", 1, "°C"),
            Metric.AIR_HUMIDITY to DpDefinition("2", "va_humidity", 0, "%"),
        ),
    )

    private fun state(alerts: Boolean = true, humidity: TargetRange = TargetRange(40.0, 55.0)) = GrowBoxState.of(
        GrowBox(
            id = "b1", name = "Zelt", phase = GrowPhase.FLOWERING, deviceIds = listOf("k1"), alertsEnabled = alerts,
            customTargets = mapOf(Metric.AIR_HUMIDITY to humidity),
        ),
        mapOf("k1" to sensor),
    )

    private class FakeEngine : AlarmPushEngine {
        val created = mutableListOf<String>()
        val removed = mutableListOf<String>()
        var fail = false
        private var n = 0
        override suspend fun create(plan: AlarmScenePlan): String {
            if (fail) throw IllegalStateException("Tuya-Push-Aktion ist noch nicht eingerichtet")
            created += plan.key
            return "scene${++n}"
        }
        override suspend fun remove(sceneId: String) { removed += sceneId }
    }

    private class Memo : AlarmPushMemo {
        var text: String? = null
        override fun load() = text
        override fun save(text: String) { this.text = text }
    }

    @Test
    fun `Plan mit Rohwerten je Messgroesse`() {
        val plans = AlarmPushPlanner.plan(listOf(state()), premium).associateBy { it.metric }
        val humidity = plans.getValue(Metric.AIR_HUMIDITY)
        assertEquals("b1|air_humidity", humidity.key)
        assertEquals(40, humidity.minRaw)
        assertEquals(55, humidity.maxRaw)
        // Temperatur mit Skalierung 1: Blüte-Startwert 20–26 °C
        val temp = plans.getValue(Metric.AIR_TEMPERATURE)
        assertEquals(200, temp.minRaw)
        assertEquals(260, temp.maxRaw)
        // VPD kann die Cloud nicht prüfen
        assertTrue(Metric.VPD !in plans)
    }

    @Test
    fun `Ohne Alarme in Stufe oder Box keine Szenen`() {
        assertTrue(AlarmPushPlanner.plan(listOf(state()), free).isEmpty())
        assertTrue(AlarmPushPlanner.plan(listOf(state(alerts = false)), premium).isEmpty())
    }

    @Test
    fun `Sync legt an, aendert und raeumt auf`() = runBlocking {
        val engine = FakeEngine()
        val memo = Memo()
        val sync = AlarmPushSync(engine, memo)
        val first = sync.sync(AlarmPushPlanner.plan(listOf(state()), premium))
        assertEquals(setOf("b1|air_temperature", "b1|air_humidity"), first.cloudKeys)
        assertNull(first.error)
        assertEquals(2, engine.created.size)

        // Unverändert: nichts passiert
        sync.sync(AlarmPushPlanner.plan(listOf(state()), premium))
        assertEquals(2, engine.created.size)

        // Neuer Zielbereich: nur diese Szene wird ersetzt
        val changed = sync.sync(AlarmPushPlanner.plan(listOf(state(humidity = TargetRange(45.0, 60.0))), premium))
        assertEquals(1, engine.removed.size)
        assertEquals(3, engine.created.size)
        assertEquals(2, changed.cloudKeys.size)

        // Downgrade: alles weg
        val gone = sync.sync(AlarmPushPlanner.plan(listOf(state()), free))
        assertTrue(gone.cloudKeys.isEmpty())
        assertEquals(3, engine.removed.size)
    }

    @Test
    fun `Ohne Push-Aktion bleibt der Handy-Alarm zustaendig`() = runBlocking {
        val engine = FakeEngine().apply { fail = true }
        val result = AlarmPushSync(engine, Memo()).sync(AlarmPushPlanner.plan(listOf(state()), premium))
        assertTrue(result.cloudKeys.isEmpty())
        assertTrue(result.error!!.contains("nicht eingerichtet"))
    }
}
