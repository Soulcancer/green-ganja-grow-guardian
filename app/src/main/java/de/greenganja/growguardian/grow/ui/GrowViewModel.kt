package de.greenganja.growguardian.grow.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.greenganja.growguardian.billing.FarmerTier
import de.greenganja.growguardian.billing.SubscriptionManager
import de.greenganja.growguardian.grow.GrowAccess
import de.greenganja.growguardian.grow.GrowBox
import de.greenganja.growguardian.grow.GrowBoxRepository
import de.greenganja.growguardian.grow.GrowBoxState
import de.greenganja.growguardian.grow.Metric
import de.greenganja.growguardian.grow.SensorDevice
import de.greenganja.growguardian.grow.SensorFeed
import de.greenganja.growguardian.grow.SensorSource
import de.greenganja.growguardian.grow.TargetRange
import de.greenganja.growguardian.grow.automation.GrowAutomations
import de.greenganja.growguardian.rewards.Rewards
import de.greenganja.growguardian.rewards.Wallet
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Eine Box, wie die Übersicht sie zeigt. Gesperrte Boxen haben keine Live-Werte. */
data class BoxItem(val state: GrowBoxState, val locked: Boolean) {
    val box: GrowBox get() = state.box
}

data class GrowUiState(
    val loading: Boolean = true,
    val tier: FarmerTier = FarmerTier.FREEMIUM,
    val isClubSeat: Boolean = false,
    val boxes: List<BoxItem> = emptyList(),
    val access: GrowAccess = GrowAccess.of(emptyList(), FarmerTier.FREEMIUM.features),
    /** Alle Tuya-Geräte des Kontos, auch nicht zugeordnete. */
    val devices: Map<String, SensorDevice> = emptyMap(),
    val sensorsLoading: Boolean = true,
    val sensorError: String? = null,
    val busy: Boolean = false,
) {
    fun item(boxId: String): BoxItem? = boxes.firstOrNull { it.box.id == boxId }

    /** In welcher Box ein Gerät steckt, für den Zuordnungsdialog. */
    fun boxOf(deviceId: String): GrowBox? = boxes.firstOrNull { deviceId in it.box.deviceIds }?.box

    val activeBoxes: List<BoxItem> get() = boxes.filter { !it.locked }
    val lockedBoxes: List<BoxItem> get() = boxes.filter { it.locked }
}

sealed interface GrowEvent {
    data class Message(val text: String) : GrowEvent

    /** Eine neue Box wurde angelegt, die Oberfläche öffnet sie. */
    data class BoxCreated(val boxId: String) : GrowEvent
}

private data class Inputs(
    val boxes: List<GrowBox>?,
    val feed: SensorFeed,
    val tier: FarmerTier,
    val isClubSeat: Boolean,
    val busy: Boolean,
    val wallet: Wallet? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class GrowViewModel(
    private val repository: GrowBoxRepository,
    sensors: SensorSource,
    subscriptions: SubscriptionManager,
    private val automations: GrowAutomations? = null,
    wallet: Flow<Wallet>? = null,
) : ViewModel() {

    private val retry = MutableStateFlow(0)
    private val busy = MutableStateFlow(false)
    private val events = Channel<GrowEvent>(Channel.BUFFERED)
    val eventFlow = events.receiveAsFlow()

    private val feed = retry.flatMapLatest { sensors.sensors() }
        .catch { emit(SensorFeed.Failed("Sensoren konnten nicht geladen werden.")) }

    private val boxes = repository.boxes().catch { emit(emptyList()) }

    private val coins: Flow<Wallet?> = wallet?.catch { emit(Wallet()) } ?: flowOf(null)

    val state: StateFlow<GrowUiState> = combine(boxes, feed, subscriptions.state, busy, coins) { boxes, feed, sub, busy, coins ->
        build(Inputs(boxes, feed, sub.tier, sub.isClubSeat, busy, coins))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GrowUiState())

    private fun build(inputs: Inputs): GrowUiState {
        val boxes = inputs.boxes.orEmpty()
        // Mit Coins freigeschaltete Alarme zählen wie die der Stufe
        val access = GrowAccess.of(boxes, Rewards.effectiveFeatures(inputs.tier.features, inputs.wallet, System.currentTimeMillis()))
        val devices = (inputs.feed as? SensorFeed.Ready)?.devices.orEmpty()
        val monitored = devices.filterKeys { access.isMonitored(it) }
        val items = boxes.map { box ->
            val locked = access.isLocked(box)
            BoxItem(GrowBoxState.of(box, if (locked) emptyMap() else monitored), locked)
        }
        return GrowUiState(
            loading = inputs.boxes == null,
            tier = inputs.tier,
            isClubSeat = inputs.isClubSeat,
            boxes = items,
            access = access,
            devices = devices,
            sensorsLoading = inputs.feed is SensorFeed.Loading,
            sensorError = (inputs.feed as? SensorFeed.Failed)?.message,
            busy = inputs.busy,
        )
    }

    fun retrySensors() {
        retry.value++
    }

    /** Neue Box (leere ID) anlegen oder bestehende speichern. */
    fun saveBox(box: GrowBox) = act {
        repository.save(box).onSuccess { id ->
            if (box.id.isEmpty()) events.send(GrowEvent.BoxCreated(id)) else events.send(GrowEvent.Message("Gespeichert."))
        }
    }

    fun setDevices(box: GrowBox, deviceIds: List<String>) =
        act { repository.save(box.copy(deviceIds = deviceIds)).onSuccess { events.send(GrowEvent.Message("Sensoren zugeordnet.")) } }

    fun setTargets(box: GrowBox, targets: Map<Metric, TargetRange>) =
        act { repository.save(box.copy(customTargets = targets)).onSuccess { events.send(GrowEvent.Message("Zielwerte gespeichert.")) } }

    fun setAlerts(box: GrowBox, enabled: Boolean) =
        act {
            repository.save(box.copy(alertsEnabled = enabled)).onSuccess {
                events.send(GrowEvent.Message(if (enabled) "Alarme an." else "Alarme aus."))
            }
        }

    /** Erst die Automationen der Box in Tuya entfernen, sonst bleiben sie dort ohne Regel zurück. */
    fun deleteBox(boxId: String) =
        act {
            automations?.removeForBox(boxId)?.onFailure {
                // Nicht abbrechen: Übrig gebliebene Automationen räumt der tägliche Hintergrundlauf auf
            }
            repository.delete(boxId).onSuccess { events.send(GrowEvent.Message("Box gelöscht.")) }
        }

    private fun act(block: suspend () -> Result<*>) {
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            try {
                block().onFailure { events.send(GrowEvent.Message(it.message ?: "Das hat nicht geklappt.")) }
            } finally {
                busy.value = false
            }
        }
    }
}
