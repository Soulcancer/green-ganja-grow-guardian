package de.greenganja.growguardian.grow.ui

import de.greenganja.growguardian.ads.inlineAd
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.greenganja.growguardian.grow.GrowBox
import de.greenganja.growguardian.grow.Metric
import de.greenganja.growguardian.grow.history.HistoryMath
import de.greenganja.growguardian.grow.history.HistoryRange
import de.greenganja.growguardian.grow.history.Trend
import de.greenganja.growguardian.grow.light.LightMath
import de.greenganja.growguardian.grow.light.LightReading
import de.greenganja.growguardian.ui.theme.GrowPalette

/** Analyse einer Box wie im Entwurf: Reiter je Messgröße, Zeitraum, Diagramm und Zusammenfassung. */
@Composable
internal fun HistoryScreen(
    box: GrowBox,
    viewModel: HistoryViewModel,
    onUpgradeClick: () -> Unit,
    onBack: () -> Unit,
    /** Einzelmessungen aus dem Lichtmodul, für den Licht-Reiter ohne Lichtsensor. */
    lightReadings: List<LightReading> = emptyList(),
    co2Ppm: Double? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val metric = state.metric
    val points = state.points(metric)
    val target = box.targets[metric]
    val summary = HistoryMath.summary(points, target?.let { it.max - it.min })

    Column(modifier = Modifier.fillMaxSize()) {
        BackBar(title = "Analyse · ${box.name}", onBack = onBack)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                // Reiter wie "Temperatur | Luftfeuchtigkeit | VPD" im Entwurf
                val tabs = if (state.metrics.isEmpty()) listOf(Metric.AIR_TEMPERATURE, Metric.AIR_HUMIDITY, Metric.VPD, Metric.LIGHT) else state.tabs
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    tabs.forEach { option -> TabPill(option.label, selected = option == metric, onClick = { viewModel.selectMetric(option) }) }
                }
            }
            inlineAd("history-top")
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    HistoryRange.entries.forEach { range ->
                        val allowed = range.isAllowed(state.historyDays)
                        RangeChip(
                            label = range.label,
                            selected = range == state.range,
                            locked = !allowed,
                            onClick = { if (!viewModel.selectRange(range)) onUpgradeClick() },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            if (metric == Metric.LIGHT) item {
                val lightTarget = LightMath.target(box.phase, co2Ppm)
                val view = LightHistoryView.of(
                    box, points, state.range.usesDailyStats, lightReadings, state.fromMillis, state.toMillis, lightTarget,
                )
                LightHistorySection(view, lightTarget, state.fromMillis, state.toMillis, state.loading)
            }
            if (metric != Metric.LIGHT) item {
                GrowCard {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = metric.label + if (metric.unit.isEmpty()) "" else " (${metric.unit})",
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            summary?.let { Text(text = "Ø ${metric.format(it.avg)}", style = MaterialTheme.typography.titleSmall) }
                        }
                        when {
                            state.loading -> Box(modifier = Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                            state.error != null -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                                Muted(state.error.orEmpty(), color = GrowPalette.Error)
                                TextButton(onClick = viewModel::reload) { Text("Erneut laden") }
                            }
                            points.isEmpty() -> Muted(
                                "Noch kein Verlauf in diesem Zeitraum. Die App speichert die Werte etwa alle 15 Minuten im Hintergrund." +
                                    if (state.range.usesDailyStats) " Tageswerte erscheinen ab dem nächsten Morgen." else "",
                                modifier = Modifier.padding(vertical = 24.dp),
                            )
                            else -> HistoryChart(
                                points = points,
                                metric = metric,
                                target = target,
                                fromMillis = state.fromMillis,
                                toMillis = state.toMillis,
                                dailyValues = state.range.usesDailyStats,
                            )
                        }
                        if (target != null && points.isNotEmpty()) {
                            Muted("Helles Band: Zielbereich ${target.format(metric)}" + if (state.range.usesDailyStats) ". Fläche: Tages-Min. bis -Max." else "")
                        }
                    }
                }
            }
            if (summary != null && metric != Metric.LIGHT) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatTile("Min.", metric.format(summary.min), Modifier.weight(1f))
                        StatTile("Ø", metric.format(summary.avg), Modifier.weight(1f))
                        StatTile("Max.", metric.format(summary.max), Modifier.weight(1f))
                    }
                }
            }
            if (state.metrics.isNotEmpty()) {
                item { SectionHeader(state.range.label) }
                item {
                    GrowCard {
                        state.metrics.forEachIndexed { index, m ->
                            val s = HistoryMath.summary(state.points(m), box.targets[m]?.let { it.max - it.min }) ?: return@forEachIndexed
                            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                            Surface(onClick = { viewModel.selectMetric(m) }, color = MaterialTheme.colorScheme.surfaceContainer) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    IconBadge(m.icon, size = 30)
                                    Text(text = m.label, modifier = Modifier.weight(1f).padding(start = 12.dp), style = MaterialTheme.typography.bodyLarge)
                                    Text(text = m.format(s.avg), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                                    Text(
                                        text = s.trend.symbol,
                                        modifier = Modifier.padding(start = 8.dp),
                                        color = if (s.trend == Trend.STEADY) MaterialTheme.colorScheme.onSurfaceVariant else GrowPalette.Green,
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            state.historyDays?.let { days ->
                item {
                    Muted("Deine Stufe speichert den Verlauf $days Tage." + if (days < 365) " Mit Premium Farmer ein ganzes Jahr." else "")
                }
            }
            inlineAd("history-end")
        }
    }
}

@Composable
private fun TabPill(text: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = if (selected) GrowPalette.Green else MaterialTheme.colorScheme.surfaceContainer,
        contentColor = if (selected) GrowPalette.OnGreen else MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) GrowPalette.OnGreen else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RangeChip(label: String, selected: Boolean, locked: Boolean, onClick: () -> Unit, modifier: Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            modifier = Modifier.padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (locked) Icon(imageVector = Icons.Filled.Lock, contentDescription = "Gesperrt", modifier = Modifier.size(12.dp), tint = GrowPalette.Gold)
            Text(
                text = label,
                modifier = Modifier.padding(start = if (locked) 4.dp else 0.dp),
                style = MaterialTheme.typography.labelMedium,
                color = when {
                    selected -> MaterialTheme.colorScheme.primary
                    locked -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier) {
    GrowCard(modifier = modifier) {
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Muted(label)
        }
    }
}
