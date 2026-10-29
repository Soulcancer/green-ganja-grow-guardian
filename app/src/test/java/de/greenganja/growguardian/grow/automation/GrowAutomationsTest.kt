package de.greenganja.growguardian.grow.automation

import de.greenganja.growguardian.billing.FarmerTier
import de.greenganja.growguardian.billing.TierFeatures
import de.greenganja.growguardian.grow.DpDefinition
import de.greenganja.growguardian.grow.Metric
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GrowAutomationsTest {

    private class FakeStore(var items: MutableList<Automation> = mutableListOf()) : AutomationStore {
        var nextId = 1
        var saveError: Throwable? = null
        override fun automations(): Flow<List<Automation>> = flowOf(items.toList())
        override suspend fun automationsOnce() = items.toList()
        override suspend fun save(automation: Automation): Result<String> {
            saveError?.let { return Result.failure(it) }
            val id = automation.id.ifEmpty { "a${nextId++}" }
            items.removeAll { it.id == id }
            items += automation.copy(id = id)
            return Result.success(id)
        }
        override suspend fun delete(id: String): Result<Unit> { items.removeAll { it.id == id }; return Result.success(Unit) }
        override suspend fun recordSync(id: String, sceneIds: List<String>, error: String?) {
            items.replaceAll { if (it.id == id) it.copy(sceneIds = sceneIds, syncError = error) else it }
        }
    }

    private class FakeEngine : AutomationEngine {
        val log = mutableListOf<String>()
        var failApply = false
        var scenes = 0
        override suspend fun apply(automation: Automation, active: Boolean): List<String> {
            log += "apply ${automation.id} active=$active"
            if (failApply) throw SceneFailure("Tuya nicht erreichbar")
            return listOf("s${++scenes}", "s${++scenes}")
        }
        override suspend fun setEnabled(sceneIds: List<String>, enabled: Boolean) { log += "enable $sceneIds $enabled" }
        override suspend fun remove(sceneIds: List<String>) { log += "remove $sceneIds" }
        override suspend fun cleanup(knownSceneIds: Set<String>): Int { log += "cleanup $knownSceneIds"; return 0 }
    }

    private class SceneFailure(message: String) : Exception(message)

    private class FakeMemo : AutomationMemo {
        val flags = mutableMapOf<String, Boolean>()
        val times = mutableMapOf<String, Long>()
        override fun flag(key: String) = flags[key]
        override fun setFlag(key: String, value: Boolean) { flags[key] = value }
        override fun time(key: String) = times[key] ?: 0L
        override fun setTime(key: String, value: Long) { times[key] = value }
    }

    private val premium = FarmerTier.entries.first { it.features.automations && it.features.maxAutomations == 20 }.features
    private val free: TierFeatures = FarmerTier.entries.first { !it.features.automations }.features

    private fun draft(id: String = "") = Automation(
        id = id, boxId = "box1", name = "Entfeuchter", enabled = true,
        trigger = AutomationTrigger.Threshold("klima1", Metric.AIR_HUMIDITY, DpDefinition("2", "va_humidity", 0, "%"), true, 60.0, 55.0),
        actuatorDeviceId = "plug1", actuatorDpId = "1", actuatorLabel = "Steckdose",
    )

    private fun setup() = Triple(FakeStore(), FakeEngine(), FakeMemo())
    private fun subject(s: FakeStore, e: FakeEngine, m: FakeMemo) = GrowAutomations(s, e, m)

    @Test
    fun `Speichern legt die Regel an und uebertraegt sie nach Tuya`() = runBlocking {
        val (store, engine, memo) = setup()
        val result = subject(store, engine, memo).save(draft(), premium)
        assertEquals(SaveResult("a1", null), result.getOrNull())
        assertEquals(listOf("s1", "s2"), store.items.single().sceneIds)
        assertTrue(store.items.single().synced)
        assertEquals("apply a1 active=true", engine.log.last())
    }

    @Test
    fun `Ungueltige Regel erreicht weder Backend noch Tuya`() = runBlocking {
        val (store, engine, memo) = setup()
        val bad = draft().copy(name = "")
        assertTrue(subject(store, engine, memo).save(bad, premium).isFailure)
        assertTrue(store.items.isEmpty())
        assertTrue(engine.log.isEmpty())
    }

    @Test
    fun `Backend-Ablehnung stoppt vor Tuya`() = runBlocking {
        val (store, engine, memo) = setup()
        store.saveError = IllegalStateException("Automationen gibt es ab Premium Farmer.")
        val result = subject(store, engine, memo).save(draft(), free)
        assertTrue(result.isFailure)
        assertTrue(engine.log.isEmpty())
    }

    @Test
    fun `Tuya-Fehler bleibt als Hinweis an der Regel stehen`() = runBlocking {
        val (store, engine, memo) = setup()
        engine.failApply = true
        val result = subject(store, engine, memo).save(draft(), premium)
        // Die Regel ist gespeichert, nur Tuya fehlt: kein Fehler, aber ein Hinweis
        assertEquals("Tuya nicht erreichbar", result.getOrNull()?.syncError)
        val saved = store.items.single()
        assertFalse(saved.synced)
        assertTrue(saved.syncError!!.contains("Tuya nicht erreichbar"))
    }

    @Test
    fun `maintain versucht Unfertiges erneut`() = runBlocking {
        val (store, engine, memo) = setup()
        engine.failApply = true
        val automations = subject(store, engine, memo)
        automations.save(draft(), premium)
        engine.failApply = false
        automations.maintain(premium, now = 1_000)
        assertTrue(store.items.single().synced)
    }

    @Test
    fun `Downgrade pausiert, Upgrade schaltet wieder ein`() = runBlocking {
        val (store, engine, memo) = setup()
        val automations = subject(store, engine, memo)
        automations.maintain(premium, now = 1_000)
        automations.save(draft(), premium)
        engine.log.clear()

        automations.maintain(free, now = 2_000)
        assertEquals(listOf("enable [s1, s2] false"), engine.log.filter { it.startsWith("enable") })
        engine.log.clear()

        // Ohne Änderung der Stufe passiert nichts
        automations.maintain(free, now = 3_000)
        assertTrue(engine.log.none { it.startsWith("enable") })

        automations.maintain(premium, now = 4_000)
        assertEquals(listOf("enable [s1, s2] true"), engine.log.filter { it.startsWith("enable") })
    }

    @Test
    fun `Ausgeschaltete Regeln bleiben nach dem Upgrade aus`() = runBlocking {
        val (store, engine, memo) = setup()
        val automations = subject(store, engine, memo)
        automations.maintain(free, now = 1_000)
        store.items += draft("x1").copy(enabled = false, sceneIds = listOf("s8", "s9"))
        engine.log.clear()
        automations.maintain(premium, now = 2_000)
        assertEquals(listOf("enable [s8, s9] false"), engine.log.filter { it.startsWith("enable") })
    }

    @Test
    fun `Loeschen entfernt erst Tuya, dann die Regel`() = runBlocking {
        val (store, engine, memo) = setup()
        val automations = subject(store, engine, memo)
        automations.save(draft(), premium)
        val saved = store.items.single()
        assertTrue(automations.delete(saved).isSuccess)
        assertTrue(store.items.isEmpty())
        assertTrue(engine.log.contains("remove [s1, s2]"))
    }

    @Test
    fun `Aufraeumen laeuft hoechstens einmal am Tag`() = runBlocking {
        val (store, engine, memo) = setup()
        val automations = subject(store, engine, memo)
        val day = 24 * 60 * 60 * 1000L
        automations.maintain(premium, now = 2 * day)
        automations.maintain(premium, now = 2 * day + 1000)
        assertEquals(1, engine.log.count { it.startsWith("cleanup") })
        automations.maintain(premium, now = 4 * day)
        assertEquals(2, engine.log.count { it.startsWith("cleanup") })
    }

    @Test
    fun `Box loeschen entfernt nur ihre Automationen in Tuya`() = runBlocking {
        val (store, engine, memo) = setup()
        store.items += draft("a").copy(sceneIds = listOf("s1", "s2"))
        store.items += draft("b").copy(boxId = "box2", sceneIds = listOf("s3", "s4"))
        assertTrue(subject(store, engine, memo).removeForBox("box1").isSuccess)
        assertEquals(listOf("remove [s1, s2]"), engine.log)
    }
}
