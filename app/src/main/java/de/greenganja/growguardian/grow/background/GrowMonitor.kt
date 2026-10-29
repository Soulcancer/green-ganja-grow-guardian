package de.greenganja.growguardian.grow.background

import android.content.Context
import kotlinx.coroutines.CancellationException
import de.greenganja.growguardian.billing.SubscriptionManager
import de.greenganja.growguardian.grow.GrowAccess
import de.greenganja.growguardian.grow.GrowBoxRepository
import de.greenganja.growguardian.grow.GrowBoxState
import de.greenganja.growguardian.grow.SensorSource
import de.greenganja.growguardian.grow.alarm.AlarmRules
import de.greenganja.growguardian.grow.alarm.GrowNotifier
import de.greenganja.growguardian.club.ClubBoxPublisher
import de.greenganja.growguardian.push.ServerMonitorStatus
import de.greenganja.growguardian.rewards.Rewards
import de.greenganja.growguardian.rewards.WalletSource
import de.greenganja.growguardian.grow.alarm.AlarmPushEngine
import de.greenganja.growguardian.grow.alarm.AlarmPushMemo
import de.greenganja.growguardian.grow.alarm.AlarmPushPlanner
import de.greenganja.growguardian.grow.alarm.AlarmPushSync
import de.greenganja.growguardian.grow.automation.GrowAutomations
import de.greenganja.growguardian.grow.history.GrowHistoryRepository

/**
 * Ein Durchlauf der Überwachung im Hintergrund:
 * Werte aller Sensoren lesen, je aktiver Box einen Verlaufspunkt speichern und Alarme prüfen.
 * Gesperrte Boxen und Sensoren über dem Limit werden wie in der App nicht überwacht.
 */
class GrowMonitor(
    context: Context,
    private val boxes: GrowBoxRepository,
    private val sensors: SensorSource,
    private val history: GrowHistoryRepository,
    private val subscriptions: SubscriptionManager,
    private val notifier: GrowNotifier = GrowNotifier(context),
    private val automations: GrowAutomations? = null,
    private val clubBoxes: ClubBoxPublisher? = null,
    private val alarmPush: AlarmPushEngine? = null,
    private val wallet: WalletSource? = null,
    private val server: ServerMonitorStatus? = null,
) {
    private val prefs = context.applicationContext.getSharedPreferences("grow_alarms", Context.MODE_PRIVATE)
    private val pushSync = alarmPush?.let { engine ->
        AlarmPushSync(
            engine,
            object : AlarmPushMemo {
                override fun load(): String? = prefs.getString(KEY_PUSH, null)
                override fun save(text: String) = prefs.edit().putString(KEY_PUSH, text).apply()
            },
        )
    }

    /** @return false, wenn niemand angemeldet ist (dann muss der Job nicht weiterlaufen) */
    suspend fun runOnce(now: Long = System.currentTimeMillis()): Boolean {
        val allBoxes = boxes.boxesOnce()
        if (allBoxes.isEmpty()) return true
        subscriptions.refresh()
        // Mit Coins freigeschaltete Alarme zählen wie die der Stufe
        val coins = try {
            wallet?.walletOnce()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        val features = Rewards.effectiveFeatures(subscriptions.state.value.tier.features, coins, now)
        val access = GrowAccess.of(allBoxes, features)
        val devices = sensors.snapshot().filterKeys { access.isMonitored(it) }
        val states = allBoxes.filter { !access.isLocked(it) }.map { GrowBoxState.of(it, devices) }

        for (state in states) {
            val values = state.readings.associate { it.metric to it.value }
            if (values.isNotEmpty()) history.record(state.box.id, now, values)
        }

        // Gemeinsame Club-Boxen: aktuelle Werte für die Mitglieder hochladen
        try {
            clubBoxes?.publish(states, now)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Kein Netz oder kein Club: beim nächsten Lauf erneut
        }

        val previous = AlarmRules.decode(prefs.getString(KEY_STATE, null))
        val watched = if (features.alerts) states.filter { it.box.alertsEnabled } else emptyList()
        // Überwacht der Server (serverMonitor), schickt er die Alarme; das Handy meldet dann nichts doppelt
        val serverActive = try {
            ServerMonitorStatus.isActive(server?.lastOkAt(), now)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
        if (serverActive) {
            prefs.edit().remove(KEY_STATE).apply()
            maintainAutomations(features, now)
            return true
        }

        // Was die Tuya-Cloud per Push überwacht, meldet das Handy nicht noch einmal
        val cloudKeys = try {
            pushSync?.sync(AlarmPushPlanner.plan(states, features))?.cloudKeys.orEmpty()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptySet()
        }
        val issues = watched.flatMap { AlarmRules.issues(it, previous.keys) }.filter { it.key !in cloudKeys }
        val result = AlarmRules.evaluate(previous, issues, now)
        result.alerts.forEach(notifier::show)
        result.resolvedBoxIds.forEach(notifier::cancel)
        prefs.edit().putString(KEY_STATE, AlarmRules.encode(result.state)).apply()

        maintainAutomations(features, now)
        return true
    }

    /** Automationen: nach Down- oder Upgrade pausieren bzw. fortsetzen, Fehlgeschlagenes nachholen. */
    private suspend fun maintainAutomations(features: de.greenganja.growguardian.billing.TierFeatures, now: Long) {
        try {
            automations?.maintain(features, now)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Tuya nicht erreichbar: beim nächsten Lauf erneut
        }
    }

    private companion object {
        const val KEY_STATE = "state"
        const val KEY_PUSH = "pushScenes"
    }
}
