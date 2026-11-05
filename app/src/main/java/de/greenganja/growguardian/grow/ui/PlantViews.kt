package de.greenganja.growguardian.grow.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import de.greenganja.growguardian.grow.GrowPhase
import de.greenganja.growguardian.grow.plants.Plant
import de.greenganja.growguardian.grow.plants.PlantOrigin
import de.greenganja.growguardian.grow.plants.PlantRules
import de.greenganja.growguardian.ui.theme.GrowPalette

/**
 * Pflanzen einer Box als Listeneinträge: Überschrift mit „+ Pflanze“, wachsende Pflanzen, darunter die geernteten.
 */
internal fun LazyListScope.plantItems(
    item: BoxItem,
    today: String,
    kcangHint: String?,
    busy: Boolean,
    onAdd: () -> Unit,
    onOpen: (Plant) -> Unit,
    onHarvest: (Plant) -> Unit,
    onDelete: (Plant) -> Unit,
) {
    val active = item.plants.filter { it.active }
    val harvested = item.plants.filter { !it.active }
    item(key = "plants-header") {
        SectionHeader(if (active.isEmpty()) "Pflanzen" else "Pflanzen (${active.size})") {
            if (!item.locked) PillAction("+ Pflanze", onClick = onAdd, enabled = !busy && item.plantsLoaded)
        }
    }
    if (item.plantsLoaded && item.plants.isEmpty()) {
        item(key = "plants-empty") { Muted("Noch keine Pflanze. Leg jede Pflanze einzeln an: mit Sorte, Samen oder Steckling und Starttag.") }
    }
    kcangHint?.let { hint -> item(key = "plants-kcang") { Muted(hint, color = GrowPalette.Warning) } }
    items(active, key = { "plant-${it.id}" }) { plant ->
        PlantCard(plant, today, locked = item.locked, busy = busy, onOpen = { onOpen(plant) }, onHarvest = { onHarvest(plant) }, onDelete = { onDelete(plant) })
    }
    if (harvested.isNotEmpty()) {
        item(key = "plants-harvested") { Muted("Geerntet", modifier = Modifier.padding(top = 4.dp)) }
        items(harvested, key = { "plant-${it.id}" }) { plant ->
            PlantCard(plant, today, locked = item.locked, busy = busy, onOpen = { onOpen(plant) }, onHarvest = null, onDelete = { onDelete(plant) })
        }
    }
}

@Composable
private fun PlantCard(plant: Plant, today: String, locked: Boolean, busy: Boolean, onOpen: () -> Unit, onHarvest: (() -> Unit)?, onDelete: () -> Unit) {
    GrowCard(onClick = if (locked) null else onOpen) {
        Column(modifier = Modifier.padding(start = 14.dp, top = 10.dp, end = 8.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(Icons.Filled.Spa, tint = if (plant.active) GrowPalette.Green else GrowPalette.TextMuted, size = 34)
                Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(plant.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    PlantRules.details(plant).takeIf { it.isNotEmpty() }?.let { Muted(it) }
                    Muted(PlantRules.status(plant, today), color = MaterialTheme.colorScheme.onSurface)
                }
            }
            if (!locked) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    if (onHarvest != null) TextButton(onClick = onHarvest, enabled = !busy) { Text("Ernten") }
                    TextButton(onClick = onDelete, enabled = !busy) { Text("Löschen", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
}

/** Pflanze anlegen oder bearbeiten. */
@Composable
internal fun PlantEditor(initial: Plant, today: String, existingCount: Int, onSave: (Plant) -> String?, onBack: () -> Unit) {
    var name by remember { mutableStateOf(initial.name) }
    var strain by remember { mutableStateOf(initial.strain.orEmpty()) }
    var origin by remember { mutableStateOf(initial.origin) }
    var auto by remember { mutableStateOf(initial.autoflower) }
    var started by remember { mutableStateOf(initial.startedAt.orEmpty()) }
    var flowering by remember { mutableStateOf(initial.floweringAt.orEmpty()) }
    var notes by remember { mutableStateOf(initial.notes.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        BackBar(title = if (initial.id.isEmpty()) "Neue Pflanze" else initial.name, onBack = onBack)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { OutlinedTextField(value = name, onValueChange = { name = it.take(PlantRules.MAX_NAME) }, label = { Text("Name, z. B. Pflanze 1 oder Lady A") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
            item { OutlinedTextField(value = strain, onValueChange = { strain = it.take(PlantRules.MAX_STRAIN) }, label = { Text("Sorte (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
            item {
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChoicePill("Unbekannt", origin == null) { origin = null }
                    PlantOrigin.entries.forEach { o -> ChoicePill(o.label, origin == o) { origin = o } }
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Automatik-Sorte (Autoflower)")
                        Muted("Blüht nach Alter statt nach Lichtzeit.")
                    }
                    Switch(checked = auto, onCheckedChange = { auto = it })
                }
            }
            item { DateField("Starttag: Keimung bzw. Steckling (JJJJ-MM-TT)", started, { started = it }, onToday = { started = today }) }
            item { DateField("Blütebeginn (optional, JJJJ-MM-TT)", flowering, { flowering = it }, onToday = { flowering = today }) }
            item {
                OutlinedTextField(
                    value = notes, onValueChange = { notes = it.take(PlantRules.MAX_NOTES) }, label = { Text("Notiz (optional)") },
                    minLines = 2, maxLines = 5, modifier = Modifier.fillMaxWidth(),
                )
            }
            if (initial.id.isEmpty() && existingCount >= PlantRules.KCANG_PRIVATE_LIMIT) {
                item { Muted("Hinweis: Privat erlaubt § 9 KCanG höchstens ${PlantRules.KCANG_PRIVATE_LIMIT} Pflanzen gleichzeitig je volljähriger Person im Haushalt.", color = GrowPalette.Warning) }
            }
            error?.let { item { Muted(it, color = MaterialTheme.colorScheme.error) } }
            item {
                Button(onClick = {
                    val plant = initial.copy(
                        name = name.trim(),
                        strain = strain.trim().ifBlank { null },
                        origin = origin,
                        autoflower = auto,
                        startedAt = started.trim().ifBlank { null },
                        floweringAt = flowering.trim().ifBlank { null },
                        notes = notes.trim().ifBlank { null },
                    )
                    error = onSave(plant)
                }, modifier = Modifier.fillMaxWidth()) { Text("Speichern") }
            }
        }
    }
}

@Composable
private fun ChoicePill(text: String, selected: Boolean, onClick: () -> Unit) {
    androidx.compose.material3.Surface(
        onClick = onClick,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(50),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        border = androidx.compose.foundation.BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
    ) {
        Text(
            text, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelLarge,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun DateField(label: String, value: String, onChange: (String) -> Unit, onToday: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = value, onValueChange = { onChange(it.filter { c -> c.isDigit() || c == '-' }.take(10)) }, label = { Text(label) },
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onToday) { Text("Heute") }
    }
}

/** Ernten: Datum und Trockengewicht (optional, auch später nachtragbar). */
@Composable
internal fun HarvestDialog(plant: Plant, today: String, onDismiss: () -> Unit, onConfirm: (Plant) -> String?) {
    var date by remember { mutableStateOf(today) }
    var dry by remember { mutableStateOf(plant.yieldDryG?.let { if (it % 1.0 == 0.0) it.toLong().toString() else it.toString().replace('.', ',') }.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("„${plant.name}“ ernten") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = date, onValueChange = { date = it.filter { c -> c.isDigit() || c == '-' }.take(10) }, label = { Text("Erntetag (JJJJ-MM-TT)") }, singleLine = true)
                OutlinedTextField(
                    value = dry, onValueChange = { dry = it.filter { c -> c.isDigit() || c == ',' || c == '.' }.take(7) },
                    label = { Text("Trockengewicht in g (optional)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                Muted("Die Pflanze wandert zu „Geerntet“; Verlauf und Tagebuch bleiben. Das Trockengewicht kannst du später nachtragen.")
                error?.let { Muted(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = { error = onConfirm(plant.copy(harvestedAt = date.trim(), yieldDryG = parseNumber(dry))) }) { Text("Ernten") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

/** Pflanze löschen; war es die letzte wachsende, kann die Box-Phase zurück auf Keimling. */
@Composable
internal fun DeletePlantDialog(
    plant: Plant,
    boxName: String,
    lastActive: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (deleteJournal: Boolean, resetPhase: Boolean) -> Unit,
) {
    var deleteJournal by remember { mutableStateOf(false) }
    var resetPhase by remember { mutableStateOf(lastActive) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("„${plant.name}“ löschen?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Die Pflanze wird aus „$boxName“ gelöscht. Box, Sensoren, Zielwerte, Verlauf und die anderen Pflanzen bleiben.")
                if (plant.active) Muted("Nach der Ernte lieber „Ernten“ nehmen, dann bleibt sie mit Ertrag erhalten.")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = deleteJournal, onCheckedChange = { deleteJournal = it })
                    Text("Ihre Tagebuch-Einträge auch löschen")
                }
                if (lastActive) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = resetPhase, onCheckedChange = { resetPhase = it })
                        Text("Box-Phase auf „${GrowPhase.SEEDLING.label}“ zurücksetzen")
                    }
                }
                if (deleteJournal) Text("Die Einträge sind danach weg.", color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(deleteJournal, lastActive && resetPhase) }) { Text("Löschen", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}
