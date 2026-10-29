package de.greenganja.growguardian.club.ui

import de.greenganja.growguardian.ads.inlineAd
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.greenganja.growguardian.club.ClubBox
import de.greenganja.growguardian.club.ClubBoxRules
import de.greenganja.growguardian.grow.BoxStatus
import de.greenganja.growguardian.grow.Metric
import de.greenganja.growguardian.grow.MetricStatus
import de.greenganja.growguardian.grow.ui.StatusChip
import de.greenganja.growguardian.grow.ui.chipLabel
import de.greenganja.growguardian.ui.theme.GrowPalette

private val BoxStatus.tint: Color
    @Composable get() = when (this) {
        BoxStatus.OK -> MaterialTheme.colorScheme.primary
        BoxStatus.WARNING -> GrowPalette.Warning
        BoxStatus.OFFLINE -> MaterialTheme.colorScheme.error
        BoxStatus.NO_DATA, BoxStatus.NO_DEVICES -> MaterialTheme.colorScheme.onSurfaceVariant
    }

/** Die Messwerte, die die Karte zeigt. */
private val CARD_METRICS = listOf(Metric.AIR_TEMPERATURE, Metric.AIR_HUMIDITY, Metric.VPD)

/** Eine gemeinsame Box: Status, Betreuer, die drei wichtigsten Werte und wie aktuell sie sind. */
@Composable
internal fun ClubBoxCard(box: ClubBox, now: Long, action: (@Composable () -> Unit)? = null) {
    val status = ClubBoxRules.status(box, now)
    val readings = ClubBoxRules.readings(box)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = box.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Hint("${box.phase.label} · ${box.medium.label} · Betreuung: ${box.keeperName.ifBlank { "Team" }}")
                }
                StatusChip(status.chipLabel, status.tint)
            }
            if (readings.isEmpty()) {
                Hint("Noch keine Werte. Sie erscheinen nach dem nächsten Messlauf des Betreuer-Handys.")
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CARD_METRICS.forEach { metric ->
                        val reading = readings.firstOrNull { it.metric == metric }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = reading?.let { metric.format(it.value) } ?: "–",
                                style = MaterialTheme.typography.titleMedium,
                                color = if (reading?.status == MetricStatus.LOW || reading?.status == MetricStatus.HIGH) GrowPalette.Warning else MaterialTheme.colorScheme.onSurface,
                            )
                            Hint(metric.label)
                        }
                    }
                }
                box.latest?.let { Hint("Stand: ${ClubBoxRules.ageLabel(it.atMillis, now)}") }
            }
            action?.invoke()
        }
    }
}

/**
 * Abschnitt „Club-Boxen“ für die Mitgliederansicht: angepinnte zuerst, ausgeblendete nur auf Wunsch.
 * Ohne geteilte Boxen erscheint nichts.
 */
internal fun LazyListScope.clubBoxItems(state: ClubUiState, now: Long, viewModel: ClubViewModel) {
    if (state.clubBoxes.isEmpty()) return
    item { SectionTitle("Club-Boxen") }
    items(state.visibleClubBoxes, key = { "clubbox-${it.id}" }) { box ->
        val pinned = box.id in state.pinnedBoxes
        val hidden = box.id in state.hiddenBoxes
        ClubBoxCard(box, now) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { viewModel.togglePinned(box.id) }) { Text(if (pinned) "Lösen" else "Anpinnen") }
                TextButton(onClick = { viewModel.toggleHidden(box.id) }) { Text(if (hidden) "Einblenden" else "Ausblenden") }
            }
        }
    }
    if (state.hiddenBoxCount > 0) {
        item {
            TextButton(onClick = { viewModel.showHiddenBoxes(!state.showHiddenBoxes) }) {
                Text(if (state.showHiddenBoxes) "Ausgeblendete verbergen" else "Ausgeblendete zeigen (${state.hiddenBoxCount})")
            }
        }
    }
}

/** Reiter „Boxen“ für das Team: geteilte Boxen ansehen, eigene Box teilen, Teilen beenden. */
@Composable
internal fun ClubBoxesTab(state: ClubUiState, viewModel: ClubViewModel) {
    val now = remember(state.clubBoxes) { System.currentTimeMillis() }
    var sharing by remember { mutableStateOf(false) }
    var ending by remember { mutableStateOf<ClubBox?>(null) }
    val ownerUid = state.club?.ownerUid

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Hint(
                    "Teile eine Box aus deinem Konto mit allen Mitgliedern. Sie sehen die Werte nur an. " +
                        "Hochgeladen werden sie von deinem Handy etwa alle 15 Minuten; Sensoren und Automationen bleiben bei dir.",
                )
                Button(
                    onClick = {
                        viewModel.loadOwnBoxes()
                        sharing = true
                    },
                    enabled = !state.busy && state.club?.active != false,
                ) { Text("Eigene Box teilen") }
            }
        }
        inlineAd("staff-boxes-top")
        if (state.clubBoxes.isEmpty()) item { Hint("Noch keine geteilten Boxen.") }
        items(state.clubBoxes, key = { it.id }) { box ->
            ClubBoxCard(box, now) {
                if (box.keeperUid == state.myUid || ownerUid == state.myUid) {
                    TextButton(onClick = { ending = box }, enabled = !state.busy) { Text("Teilen beenden") }
                }
            }
        }
        inlineAd("staff-boxes-end")
    }

    if (sharing) {
        ShareBoxDialog(
            ownBoxes = state.ownBoxes.map { it.id to it.name },
            alreadyShared = state.clubBoxes.filter { it.keeperUid == state.myUid }.map { it.sourceBoxId }.toSet(),
            onShare = {
                sharing = false
                viewModel.shareBox(it)
            },
            onDismiss = { sharing = false },
        )
    }
    ending?.let { box ->
        ConfirmDialog(
            title = "Teilen beenden?",
            text = "„${box.name}“ verschwindet für alle Mitglieder. Die Box in deinem Konto bleibt.",
            confirmLabel = "Beenden",
            onConfirm = {
                ending = null
                viewModel.unshareBox(box.id)
            },
            onDismiss = { ending = null },
        )
    }
}

@Composable
private fun ShareBoxDialog(
    ownBoxes: List<Pair<String, String>>,
    alreadyShared: Set<String>,
    onShare: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Welche Box teilen?") },
        text = {
            Column(modifier = Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                if (ownBoxes.isEmpty()) Hint("Du hast noch keine eigene Box. Lege sie zuerst im Grow-Bereich an.")
                ownBoxes.forEach { (id, name) ->
                    TextButton(onClick = { onShare(id) }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (id in alreadyShared) "$name (aktualisieren)" else name)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}
