package de.greenganja.growguardian.grow.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.greenganja.growguardian.billing.SubscriptionManager
import de.greenganja.growguardian.grow.Metric
import de.greenganja.growguardian.grow.history.ChartPoint
import de.greenganja.growguardian.grow.history.DailyStat
import de.greenganja.growguardian.grow.history.GrowHistoryRepository
import de.greenganja.growguardian.grow.history.HistoryMath
import de.greenganja.growguardian.grow.history.HistoryRange
import de.greenganja.growguardian.grow.history.HistorySample
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HistoryUiState(
    val range: HistoryRange = HistoryRange.DAY,
    val metric: Metric = Metric.AIR_TEMPERATURE,
    val loading: Boolean = true,
    val error: String? = null,
    /** Einzelne Messungen (24 Std., 7 Tage). */
    val samples: List<HistorySample> = emptyList(),
    /** Tageswerte (30 Tage, 1 Jahr). */
    val daily: List<DailyStat> = emptyList(),
    val historyDays: Int? = 7,
    val fromMillis: Long = 0,
    val toMillis: Long = 0,
) {
    /** Messgrößen mit Daten im Zeitraum, in fester Reihenfolge. */
    val metrics: List<Metric>
        get() = (samples.flatMap { it.values.keys } + daily.flatMap { it.values.keys }).toSet().sortedBy { it.ordinal }

    fun points(metric: Metric): List<ChartPoint> =
        if (range.usesDailyStats) HistoryMath.dailyPoints(daily, metric) else HistoryMath.samplePoints(samples, metric)
}

/** Lädt den Verlauf einer Box für den gewählten Zeitraum; Zeiträume über dem Limit der Stufe sind gesperrt. */
class HistoryViewModel(
    private val repository: GrowHistoryRepository,
    private val boxId: String,
    subscriptions: SubscriptionManager,
    private val now: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    private val _state = MutableStateFlow(HistoryUiState(historyDays = subscriptions.state.value.tier.features.historyDays))
    val state: StateFlow<HistoryUiState> = _state.asStateFlow()
    private var job: Job? = null

    init {
        load(HistoryRange.DAY)
        viewModelScope.launch {
            subscriptions.state.collect { sub -> _state.update { it.copy(historyDays = sub.tier.features.historyDays) } }
        }
    }

    fun selectMetric(metric: Metric) = _state.update { it.copy(metric = metric) }

    /** @return false, wenn die Stufe den Zeitraum nicht enthält (dann Upgrade anbieten) */
    fun selectRange(range: HistoryRange): Boolean {
        if (!range.isAllowed(_state.value.historyDays)) return false
        if (range != _state.value.range || _state.value.error != null) load(range)
        return true
    }

    fun reload() = load(_state.value.range)

    private fun load(range: HistoryRange) {
        job?.cancel()
        val to = now()
        val from = to - range.days * 24L * 60 * 60 * 1000
        _state.update { it.copy(range = range, loading = true, error = null, fromMillis = from, toMillis = to) }
        job = viewModelScope.launch {
            try {
                if (range.usesDailyStats) {
                    val daily = repository.dailyStats(boxId, from, to)
                    _state.update { it.copy(loading = false, daily = daily, samples = emptyList()) }
                } else {
                    val samples = repository.samples(boxId, from, to)
                    _state.update { it.copy(loading = false, samples = samples, daily = emptyList()) }
                }
                // Gewählte Messgröße hat keine Daten: auf die erste vorhandene wechseln
                _state.update { s -> if (s.metric in s.metrics || s.metrics.isEmpty()) s else s.copy(metric = s.metrics.first()) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = "Verlauf konnte nicht geladen werden.") }
            }
        }
    }
}
