package de.greenganja.growguardian.grow.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.greenganja.growguardian.billing.FarmerTier
import de.greenganja.growguardian.billing.SubscriptionManager
import de.greenganja.growguardian.grow.GrowAccess
import de.greenganja.growguardian.grow.GrowBox
import de.greenganja.growguardian.grow.GrowPhase
import de.greenganja.growguardian.grow.GrowBoxRepository
import de.greenganja.growguardian.grow.GrowBoxState
import de.greenganja.growguardian.grow.Metric
import de.greenganja.growguardian.grow.SensorDevice
import de.greenganja.growguardian.grow.SensorFeed
import de.greenganja.growguardian.grow.SensorSource
import de.greenganja.growguardian.grow.TargetRange
import de.greenganja.growguardian.grow.automation.GrowAutomations
import de.greenganja.growguardian.grow.plants.Plant
import de.greenganja.growguardian.grow.plants.PlantRepository
import de.greenganja.growguardian.grow.plants.PlantRules
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch

/** Eine Box, wie die Übersicht sie zeigt. Gesperrte Boxen haben keine Live-Werte. */
data class BoxItem(
    val state: GrowBoxState,
    val locked: Boolean,
    val plants: List<Plant> = emptyList(),
    /** false, solange die Pflanzen dieser Box noch nicht geladen sind. */
    val plantsLoaded: Boolean = false,
) {
    val box: GrowBox get() = state.box
    val activePlants: List<Plant> get() = plants.filter { it.active }
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

    /** Wachsende Pflanzen in allen eigenen Boxen. */
    val activePlantCount: Int get() = boxes.sumOf { it.activePlants.size }

    /** § 9 KCanG: mehr als drei Pflanzen privat; nicht für Social Clubs und Club-Plätze. */
    val kcangHint: String? get() = PlantRules.kcangHint(activePlantCount, tier == FarmerTier.SOCIAL_CLUB || isClubSeat)
    val lockedBoxes: List<BoxItem> get() = boxes.filter { it.locked }
}

sealed interface GrowEvent {
    data class Message(val text: String) : GrowEvent

    /** Eine neue Box wurde angelegt, die Oberfläche öffnet sie. */
    data class BoxCreated(val boxId: String) : GrowEvent
}

private data class Inputs(
    val boxes: List<GrowBox>?,
    val plants: Map<String, List<Plant>>,
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
    /** Zum Löschen der Tagebuch-Einträge, wenn eine Pflanze entfernt wird. */
    private val journal: de.greenganja.growguardian.grow.journal.JournalRepository? = null,
    /** Pflanzen je Box; ohne gibt es keine Pflanzenliste. */
    private val plantRepository: PlantRepository? = null,
) : ViewModel() {

    private val retry = MutableStateFlow(0)
    private val busy = MutableStateFlow(false)
    private val events = Channel<GrowEvent>(Channel.BUFFERED)
    val eventFlow = events.receiveAsFlow()

    private val feed = retry.flatMapLatest { sensors.sensors() }
        .catch { emit(SensorFeed.Failed("Sensoren konnten nicht geladen werden.")) }

    // Geteilt: Boxen und Pflanzen lesen dieselbe Box-Liste, ein Firestore-Listener reicht
    private val boxes = repository.boxes().catch { emit(emptyList()) }
        .shareIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    private val coins: Flow<Wallet?> = wallet?.catch { emit(Wallet()) } ?: flowOf(null)

    /** Pflanzen aller Boxen; neu abonniert nur, wenn sich die Box-IDs ändern. */
    private val plants: Flow<Map<String, List<Plant>>> = boxes
        .map { list -> list?.map { it.id }.orEmpty() }
        .distinctUntilChanged()
        .flatMapLatest { ids -> plantRepository?.plantsByBox(ids)?.catch { emit(emptyMap()) } ?: flowOf(emptyMap()) }
        .onStart { emit(emptyMap()) }

    private val boxesAndPlants = combine(boxes, plants) { b, p -> b to p }

    val state: StateFlow<GrowUiState> = combine(boxesAndPlants, feed, subscriptions.state, busy, coins) { (boxes, plants), feed, sub, busy, coins ->
        build(Inputs(boxes, plants, feed, sub.tier, sub.isClubSeat, busy, coins))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GrowUiState())

    private fun build(inputs: Inputs): GrowUiState {
        val boxes = inputs.boxes.orEmpty()
        // Mit Coins freigeschaltete Alarme zählen wie die der Stufe
        val access = GrowAccess.of(boxes, Rewards.effectiveFeatures(inputs.tier.features, inputs.wallet, System.currentTimeMillis()))
        val devices = (inputs.feed as? SensorFeed.Ready)?.devices.orEmpty()
        val monitored = devices.filterKeys { access.isMonitored(it) }
        val items = boxes.map { box ->
            val locked = access.isLocked(box)
            BoxItem(
                GrowBoxState.of(box, if (locked) emptyMap() else monitored),
                locked,
                plants = inputs.plants[box.id].orEmpty(),
                plantsLoaded = box.id in inputs.plants,
            )
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

    /** Pflanze anlegen oder speichern (z. B. auch Ernte mit Datum und Ertrag). */
    fun savePlant(boxId: String, plant: Plant, message: String? = null) =
        act {
            val repo = plantRepository ?: return@act Result.failure<Unit>(IllegalStateException("Pflanzen sind hier nicht verfügbar."))
            runCatching { repo.save(boxId, plant) }.onSuccess {
                events.send(GrowEvent.Message(message ?: if (plant.id.isEmpty()) "„${plant.name.trim()}“ angelegt." else "Gespeichert."))
            }
        }

    /**
     * Pflanze löschen, auf Wunsch mit ihren Tagebuch-Einträgen. War es die letzte wachsende Pflanze,
     * kann die Phase der Box zurück auf Keimling gehen. Box, Sensoren, Verlauf und Automationen bleiben.
     */
    fun deletePlant(box: GrowBox, plant: Plant, deleteJournal: Boolean, resetPhase: Boolean) =
        act {
            val repo = plantRepository ?: return@act Result.failure<Unit>(IllegalStateException("Pflanzen sind hier nicht verfügbar."))
            runCatching {
                repo.delete(box.id, plant.id)
                if (deleteJournal) repo.deleteJournalOf(box.id, plant.id)
            }.mapCatching {
                if (resetPhase && box.phase != GrowPhase.SEEDLING) repository.save(box.copy(phase = GrowPhase.SEEDLING)).getOrThrow()
            }.onSuccess {
                events.send(GrowEvent.Message(if (deleteJournal) "„${plant.name}“ und ihre Tagebuch-Einträge gelöscht." else "„${plant.name}“ gelöscht."))
            }
        }

    /** Alte Boxen hatten Sorte und Starttag direkt an der Box: einmal als „Pflanze 1“ übernehmen. */
    private val migrated = mutableSetOf<String>()

    private suspend fun migrateLegacyPlants(state: GrowUiState) {
        val repo = plantRepository ?: return
        for (item in state.boxes) {
            val box = item.box
            if (!item.plantsLoaded || item.plants.isNotEmpty() || item.locked || box.id in migrated) continue
            val legacy = PlantRules.fromLegacy(box.strain, box.plantedAt) ?: continue
            migrated += box.id
            if (runCatching { repo.save(box.id, legacy) }.isSuccess) {
                repository.save(box.copy(strain = null, plantedAt = null))
            }
        }
    }

    init {
        viewModelScope.launch { state.collect { migrateLegacyPlants(it) } }
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
