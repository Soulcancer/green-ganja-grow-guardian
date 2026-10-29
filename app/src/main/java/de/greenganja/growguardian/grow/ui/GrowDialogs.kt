package de.greenganja.growguardian.grow.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import de.greenganja.growguardian.grow.DefaultTargets
import de.greenganja.growguardian.grow.GrowBox
import de.greenganja.growguardian.grow.Metric
import de.greenganja.growguardian.grow.TargetRange
import de.greenganja.growguardian.ui.theme.GrowPalette

/** Sensoren einer Box auswählen. Ein Sensor gehört immer zu genau einer Box. */
@Composable
internal fun DeviceDialog(
    box: GrowBox,
    state: GrowUiState,
    onDismiss: () -> Unit,
    onConfirm: (List<String>) -> Unit,
    onPair: (() -> Unit)? = null,
) {
    val selected = remember(box.id) { mutableStateListOf<String>().apply { addAll(box.deviceIds) } }
    val limit = state.access.features.maxDevices
    val usedElsewhere = state.activeBoxes
        .filter { it.box.id != box.id }
        .flatMap { it.box.deviceIds }
        .filter { it !in selected }
        .toSet()
    val free = limit?.let { it - usedElsewhere.size - selected.size }
    val devices = state.devices.values.sortedBy { it.name.lowercase() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sensoren für ${box.name}") },
        text = {
            Column(modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                when {
                    state.sensorError != null -> Muted(state.sensorError, color = GrowPalette.Error)
                    devices.isEmpty() && state.sensorsLoading -> Muted("Geräte werden geladen …")
                    devices.isEmpty() -> Muted("Noch keine Geräte im Konto. Koppel zuerst einen Sensor.")
                }
                if (limit != null) {
                    Muted(
                        "Deine Stufe überwacht bis zu $limit Sensoren, noch ${free?.coerceAtLeast(0)} frei.",
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                devices.forEach { device ->
                    val checked = device.id in selected
                    val otherBox = state.boxOf(device.id)?.takeIf { it.id != box.id }
                    val enabled = checked || free == null || free > 0
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = { on -> if (on) selected.add(device.id) else selected.remove(device.id) },
                            enabled = enabled,
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = device.name, style = MaterialTheme.typography.bodyLarge)
                            val info = listOfNotNull(
                                device.kind.label,
                                if (device.online) null else "offline",
                                otherBox?.let { "wird aus ${it.name} verschoben" }.takeIf { checked } ?: otherBox?.let { "in ${it.name}" },
                            )
                            Muted(info.joinToString(" · "))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(selected.toList()) }) { Text("Speichern") } },
        dismissButton = {
            Row {
                if (onPair != null) TextButton(onClick = onPair) { Text("Gerät koppeln") }
                TextButton(onClick = onDismiss) { Text("Abbrechen") }
            }
        },
    )
}

/** Zielbereiche anpassen; leere oder gleiche Werte wie die Startwerte zählen nicht als eigener Wert. */
@Composable
internal fun TargetsDialog(box: GrowBox, onDismiss: () -> Unit, onConfirm: (Map<Metric, TargetRange>) -> Unit) {
    val defaults = DefaultTargets.forPhase(box.phase, box.medium)
    val metrics = (defaults.keys + box.customTargets.keys).sortedBy { it.ordinal }
    val minText = remember(box.id) {
        mutableStateMapOf<Metric, String>().apply {
            metrics.forEach { metric -> put(metric, box.targets[metric]?.let { metric.formatNumber(it.min) }.orEmpty()) }
        }
    }
    val maxText = remember(box.id) {
        mutableStateMapOf<Metric, String>().apply {
            metrics.forEach { metric -> put(metric, box.targets[metric]?.let { metric.formatNumber(it.max) }.orEmpty()) }
        }
    }
    val parsed = metrics.associateWith { metric ->
        val min = parseNumber(minText[metric].orEmpty())
        val max = parseNumber(maxText[metric].orEmpty())
        if (min != null && max != null && min <= max) TargetRange(min, max) else null
    }
    val valid = metrics.all { metric ->
        parsed[metric] != null || (minText[metric].isNullOrBlank() && maxText[metric].isNullOrBlank())
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Zielwerte") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Muted("Startwerte für ${box.phase.label} in ${box.medium.label}. Felder leeren setzt den Startwert zurück.")
                metrics.forEach { metric ->
                    Text(text = metric.label + if (metric.unit.isEmpty()) "" else " (${metric.unit})", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NumberField("von", minText[metric].orEmpty(), { minText[metric] = it }, Modifier.weight(1f))
                        NumberField("bis", maxText[metric].orEmpty(), { maxText[metric] = it }, Modifier.weight(1f))
                    }
                    val bad = parsed[metric] == null && !(minText[metric].isNullOrBlank() && maxText[metric].isNullOrBlank())
                    if (bad) Muted("„von“ muss kleiner als „bis“ sein.", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val custom = parsed.mapNotNull { (metric, range) ->
                        if (range == null || range == defaults[metric]) null else metric to range
                    }.toMap()
                    onConfirm(custom)
                },
                enabled = valid,
            ) { Text("Speichern") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

@Composable
private fun NumberField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = { text -> onChange(text.filter { it.isDigit() || it == ',' || it == '.' || it == '-' }.take(8)) },
        modifier = modifier,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
    )
}

@Composable
internal fun DeleteDialog(name: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("$name löschen?") },
        text = { Text("Die Box und ihre Zielwerte werden gelöscht. Die Sensoren bleiben in deinem Konto.") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Löschen", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

/** "6,2" und "6.2" werden beide gelesen. */
internal fun parseNumber(text: String): Double? = text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }
