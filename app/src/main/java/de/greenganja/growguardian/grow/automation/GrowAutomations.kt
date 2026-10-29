package de.greenganja.growguardian.grow.automation

import android.content.Context
import android.content.SharedPreferences
import de.greenganja.growguardian.billing.TierFeatures
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow

/** Ergebnis von [GrowAutomations.save]: die ID der Regel und ein Hinweis, falls Tuya sie noch nicht hat. */
data class SaveResult(val id: String, val syncError: String?)

/**
 * Regeln speichern und in der Tuya-Cloud aktuell halten.
 *
 * Reihenfolge beim Speichern: erst das Backend (prüft Premium, Limit und Werte), dann die Tuya-Automationen,
 * dann deren IDs zurück in Firestore. Geht der Tuya-Teil schief, bleibt die Regel mit Fehlermeldung stehen
 * und wird beim nächsten Hintergrundlauf erneut übertragen.
 */
class GrowAutomations(
    private val repository: AutomationStore,
    private val engine: AutomationEngine,
    private val memo: AutomationMemo,
) {
    constructor(context: Context, repository: AutomationStore, engine: AutomationEngine) :
        this(repository, engine, SharedPreferencesMemo(context.applicationContext.getSharedPreferences("grow_automations", Context.MODE_PRIVATE)))

    fun automations(): Flow<List<Automation>> = repository.automations()

    /**
     * Schlägt nur fehl, wenn die Regel nicht gespeichert werden konnte. Klappt die Übertragung nach Tuya nicht,
     * ist das Speichern trotzdem erfolgreich ([SaveResult.syncError]), damit die Regel nicht doppelt angelegt wird.
     */
    suspend fun save(automation: Automation, features: TierFeatures): Result<SaveResult> {
        AutomationRules.validate(automation)?.let { return Result.failure(IllegalArgumentException(it)) }
        val id = repository.save(automation).getOrElse { return Result.failure(it) }
        val synced = sync(automation.copy(id = id), features)
        return Result.success(SaveResult(id, synced.exceptionOrNull()?.message))
    }

    suspend fun setEnabled(automation: Automation, enabled: Boolean, features: TierFeatures): Result<Unit> {
        repository.save(automation.copy(enabled = enabled)).getOrElse { return Result.failure(it) }
        val updated = automation.copy(enabled = enabled)
        if (!updated.synced) return sync(updated, features)
        return attempt { engine.setEnabled(updated.sceneIds, enabled && features.automations) }
    }

    suspend fun delete(automation: Automation): Result<Unit> {
        attempt { engine.remove(automation.sceneIds) }.onFailure { error ->
            repository.recordSync(automation.id, automation.sceneIds, "Löschen in Tuya fehlgeschlagen: ${error.message}")
            return Result.failure(error)
        }
        return repository.delete(automation.id)
    }

    /** Vor dem Löschen einer Box: deren Automationen in Tuya entfernen (die Regeln löscht das Backend mit). */
    suspend fun removeForBox(boxId: String): Result<Unit> = attempt {
        repository.automationsOnce().filter { it.boxId == boxId }.forEach { engine.remove(it.sceneIds) }
    }

    /**
     * Für den Hintergrund-Job: Nach einem Downgrade alle Automationen pausieren, nach einem Upgrade wieder
     * einschalten; fehlgeschlagene Übertragungen erneut versuchen; einmal am Tag Waisen aufräumen.
     */
    suspend fun maintain(features: TierFeatures, now: Long = System.currentTimeMillis()) {
        val all = repository.automationsOnce()
        val allowed = features.automations
        val lastAllowed = memo.flag(KEY_ALLOWED)
        if (lastAllowed != allowed) {
            for (automation in all.filter { it.synced }) {
                attempt { engine.setEnabled(automation.sceneIds, automation.enabled && allowed) }
            }
            memo.setFlag(KEY_ALLOWED, allowed)
        }
        if (allowed) {
            all.filter { !it.synced }.forEach { sync(it, features) }
        }
        if (now - memo.time(KEY_CLEANUP) > DAY_MS) {
            attempt { engine.cleanup(all.flatMap { it.sceneIds }.toSet()) }
            memo.setTime(KEY_CLEANUP, now)
        }
    }

    private suspend fun sync(automation: Automation, features: TierFeatures): Result<Unit> =
        attempt {
            val scenes = engine.apply(automation, active = automation.enabled && features.automations)
            repository.recordSync(automation.id, scenes, null)
        }.onFailure { error ->
            runCatching { repository.recordSync(automation.id, emptyList(), "In Tuya nicht angelegt: ${error.message}") }
        }

    private suspend fun attempt(block: suspend () -> Unit): Result<Unit> =
        try {
            block()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }

    private companion object {
        const val KEY_ALLOWED = "tierAllowsAutomations"
        const val KEY_CLEANUP = "lastCleanup"
        const val DAY_MS = 24 * 60 * 60 * 1000L
    }
}

private class SharedPreferencesMemo(private val prefs: SharedPreferences) : AutomationMemo {
    override fun flag(key: String): Boolean? = if (prefs.contains(key)) prefs.getBoolean(key, false) else null
    override fun setFlag(key: String, value: Boolean) = prefs.edit().putBoolean(key, value).apply()
    override fun time(key: String): Long = prefs.getLong(key, 0)
    override fun setTime(key: String, value: Long) = prefs.edit().putLong(key, value).apply()
}
