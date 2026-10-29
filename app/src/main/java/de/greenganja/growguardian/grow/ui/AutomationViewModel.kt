package de.greenganja.growguardian.grow.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.greenganja.growguardian.billing.SubscriptionManager
import de.greenganja.growguardian.billing.TierFeatures
import de.greenganja.growguardian.grow.automation.Automation
import de.greenganja.growguardian.grow.automation.GrowAutomations
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class AutomationUiState(
    val loading: Boolean = true,
    val items: List<Automation> = emptyList(),
    val features: TierFeatures,
    val busy: Boolean = false,
) {
    val allowed: Boolean get() = features.automations

    /** Die Stufe erlaubt keine weitere Regel. [totalCount] zählt die Regeln aller Boxen. */
    fun limitReached(totalCount: Int): Boolean = features.maxAutomations?.let { totalCount >= it } ?: false
}

sealed interface AutomationEvent {
    data class Message(val text: String) : AutomationEvent

    /** Speichern hat geklappt; der Editor schließt sich. */
    data object Saved : AutomationEvent
}

/** Regeln einer Box: Liste, Schalter, Speichern und Löschen. */
class AutomationViewModel(
    private val boxId: String,
    private val automations: GrowAutomations,
    subscriptions: SubscriptionManager,
) : ViewModel() {

    private val busy = MutableStateFlow(false)
    private val events = Channel<AutomationEvent>(Channel.BUFFERED)
    val eventFlow = events.receiveAsFlow()

    private val all = automations.automations().catch { emit(emptyList()) }

    /** Alle Regeln des Kontos, für das Limit. */
    val totalCount: StateFlow<Int> = all.map { it.size }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val state: StateFlow<AutomationUiState> = combine(all, subscriptions.state, busy) { items, sub, busy ->
        AutomationUiState(
            loading = false,
            items = items.filter { it.boxId == boxId },
            features = sub.tier.features,
            busy = busy,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AutomationUiState(features = subscriptions.state.value.tier.features))

    fun save(automation: Automation) = act { features ->
        automations.save(automation, features).onSuccess { saved ->
            // Auch wenn Tuya noch nicht erreichbar war: Die Regel steht im Konto und wird nachgeholt
            saved.syncError?.let { events.send(AutomationEvent.Message("Gespeichert, aber noch nicht in Tuya angelegt: $it")) }
            events.send(AutomationEvent.Saved)
        }.onFailure { error ->
            events.send(AutomationEvent.Message(error.message ?: "Das hat nicht geklappt."))
        }
    }

    fun setEnabled(automation: Automation, enabled: Boolean) = act { features ->
        automations.setEnabled(automation, enabled, features).onFailure {
            events.send(AutomationEvent.Message(it.message ?: "Das hat nicht geklappt."))
        }
    }

    fun delete(automation: Automation) = act { _ ->
        automations.delete(automation).onSuccess {
            events.send(AutomationEvent.Message("Regel gelöscht."))
            events.send(AutomationEvent.Saved)
        }.onFailure { events.send(AutomationEvent.Message(it.message ?: "Löschen hat nicht geklappt.")) }
    }

    private fun act(block: suspend (TierFeatures) -> Unit) {
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            try {
                block(state.value.features)
            } finally {
                busy.value = false
            }
        }
    }
}
