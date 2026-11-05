package de.greenganja.growguardian.grow.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import de.greenganja.growguardian.ads.inlineAd
import de.greenganja.growguardian.grow.GrowBox
import de.greenganja.growguardian.grow.Metric
import de.greenganja.growguardian.grow.journal.JournalEntry
import de.greenganja.growguardian.grow.journal.JournalRepository
import de.greenganja.growguardian.grow.journal.JournalRules
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class JournalViewModel(
    private val boxId: String,
    private val journal: JournalRepository,
    private val today: () -> String = { LocalDate.now(ZoneId.of("Europe/Berlin")).toString() },
) : ViewModel() {
    val entries: StateFlow<List<JournalEntry>?> = journal.entries(boxId).catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val events = Channel<String>(Channel.BUFFERED)
    val messages = events.receiveAsFlow()

    fun today(): String = today.invoke()

    /** @return Fehlermeldung oder null bei Erfolg */
    fun save(entry: JournalEntry): String? {
        JournalRules.validate(entry, today())?.let { return it }
        viewModelScope.launch {
            try {
                journal.save(boxId, entry)
                events.send(if (entry.id.isEmpty()) "Eintrag gespeichert." else "Eintrag geändert.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                events.send("Speichern hat nicht geklappt. Ohne Netz wird es später übertragen.")
            }
        }
        return null
    }

    fun delete(entry: JournalEntry) {
        viewModelScope.launch {
            runCatching { journal.delete(boxId, entry.id) }
            events.send("Eintrag gelöscht.")
        }
    }
}

private val DATE = DateTimeFormatter.ofPattern("EEE, dd.MM.yyyy", java.util.Locale.GERMANY)
private fun formatDate(iso: String) = runCatching { LocalDate.parse(iso).format(DATE) }.getOrDefault(iso)
private fun ml(value: Double) = if (value % 1.0 == 0.0) "${value.toLong()} ml" else String.format(java.util.Locale.GERMANY, "%.1f ml", value)

/** Grow-Tagebuch einer Box: Sorte, Starttag, Einträge zum Gießen, Düngen, Messen und Notizen. */
@Composable
internal fun JournalScreen(
    box: GrowBox,
    viewModel: JournalViewModel,
    onSaveBox: (GrowBox) -> Unit,
    onMessage: (String) -> Unit,
    onBack: () -> Unit,
    onRemovePlant: ((deleteJournal: Boolean, resetPhase: Boolean) -> Unit)? = null,
) {
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<JournalEntry?>(null) }
    var editBox by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<JournalEntry?>(null) }
    var removing by remember { mutableStateOf(false) }
    val today = viewModel.today()

    LaunchedEffect(viewModel) { viewModel.messages.collect(onMessage) }

    val current = editing
    if (current != null) {
        BackHandler { editing = null }
        EntryEditor(current, onSave = { entry -> viewModel.save(entry).also { if (it == null) editing = null } }, onBack = { editing = null })
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        BackBar(title = "Tagebuch · ${box.name}", onBack = onBack)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                GrowCard {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(box.strain ?: "Sorte noch nicht eingetragen", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        val day = JournalRules.growDay(box.plantedAt, today)
                        Muted(
                            listOfNotNull(
                                box.phase.label,
                                box.plantedAt?.let { "Start ${formatDate(it)}" },
                                day?.let { "Tag $it (${JournalRules.weekLabel(it)})" },
                            ).joinToString(" · "),
                        )
                        JournalRules.lastWatered(entries.orEmpty())?.let { Muted("Zuletzt gegossen: ${formatDate(it)}") }
                        Row {
                            TextButton(onClick = { editBox = true }) { Text("Sorte und Start bearbeiten") }
                            if (box.hasPlant && onRemovePlant != null) {
                                TextButton(onClick = { removing = true }) { Text("Pflanze entfernen", color = MaterialTheme.colorScheme.error) }
                            }
                        }
                    }
                }
            }
            item {
                Button(onClick = { editing = JournalEntry(date = today) }, modifier = Modifier.fillMaxWidth()) { Text("+ Eintrag für heute") }
            }
            inlineAd("journal-top")
            if (entries?.isEmpty() == true) item { Muted("Noch keine Einträge. Notier, wann du gießt, düngst und was dir auffällt.") }
            items(entries.orEmpty(), key = { it.id }) { entry ->
                GrowCard(onClick = { editing = entry }) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(formatDate(entry.date), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            JournalRules.growDay(box.plantedAt, entry.date)?.let { Muted("Tag $it") }
                        }
                        val facts = listOfNotNull(
                            ("Gegossen" + (entry.waterMl?.let { " ${ml(it)}" } ?: "")).takeIf { entry.watered },
                            entry.nutrientsMl?.let { "Dünger ${ml(it)}" },
                        ) + entry.values.entries.sortedBy { it.key.ordinal }.map { (m, v) -> "${m.label} ${m.format(v)}" }
                        if (facts.isNotEmpty()) Text(facts.joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
                        entry.notes?.let { Muted(it) }
                        TextButton(onClick = { deleting = entry }) { Text("Löschen", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
            inlineAd("journal-end")
        }
    }

    if (editBox) {
        var strain by remember { mutableStateOf(box.strain.orEmpty()) }
        var planted by remember { mutableStateOf(box.plantedAt.orEmpty()) }
        val plantedOk = planted.isBlank() || (runCatching { LocalDate.parse(planted) }.isSuccess && planted <= today)
        AlertDialog(
            onDismissRequest = { editBox = false },
            title = { Text("Sorte und Start") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = strain, onValueChange = { strain = it.take(60) }, label = { Text("Sorte") }, singleLine = true)
                    OutlinedTextField(
                        value = planted, onValueChange = { planted = it.take(10) }, label = { Text("Starttag (JJJJ-MM-TT)") },
                        singleLine = true, isError = !plantedOk,
                    )
                    TextButton(onClick = { planted = today }) { Text("Heute") }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    editBox = false
                    onSaveBox(box.copy(strain = strain.trim().ifBlank { null }, plantedAt = planted.ifBlank { null }))
                }, enabled = plantedOk) { Text("Speichern") }
            },
            dismissButton = { TextButton(onClick = { editBox = false }) { Text("Abbrechen") } },
        )
    }
    if (removing && onRemovePlant != null) {
        RemovePlantDialog(box, onDismiss = { removing = false }, onConfirm = { deleteJournal, resetPhase ->
            removing = false
            onRemovePlant(deleteJournal, resetPhase)
        })
    }
    deleting?.let { entry ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Eintrag löschen?") },
            text = { Text(formatDate(entry.date)) },
            confirmButton = { TextButton(onClick = { deleting = null; viewModel.delete(entry) }) { Text("Löschen", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Abbrechen") } },
        )
    }
}

@Composable
private fun EntryEditor(initial: JournalEntry, onSave: (JournalEntry) -> String?, onBack: () -> Unit) {
    var date by remember { mutableStateOf(initial.date) }
    var watered by remember { mutableStateOf(initial.watered) }
    var water by remember { mutableStateOf(initial.waterMl?.let(::plain).orEmpty()) }
    var nutrients by remember { mutableStateOf(initial.nutrientsMl?.let(::plain).orEmpty()) }
    var notes by remember { mutableStateOf(initial.notes.orEmpty()) }
    val values = remember { mutableStateMapOf<Metric, String>().apply { initial.values.forEach { (m, v) -> put(m, plain(v)) } } }
    var error by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        BackBar(title = if (initial.id.isEmpty()) "Neuer Eintrag" else "Eintrag bearbeiten", onBack = onBack)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { OutlinedTextField(value = date, onValueChange = { date = it.take(10) }, label = { Text("Datum (JJJJ-MM-TT)") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = watered, onCheckedChange = { watered = it })
                    Text("Gegossen")
                }
            }
            item { NumberInput("Wasser in ml", water, { water = it }) }
            item { NumberInput("Dünger in ml", nutrients, { nutrients = it }) }
            item { SectionHeader("Von Hand gemessen (optional)") }
            items(JournalRules.MANUAL_METRICS, key = { it.id }) { metric ->
                NumberInput(metric.label + if (metric.unit.isEmpty()) "" else " (${metric.unit})", values[metric].orEmpty(), { values[metric] = it })
            }
            item {
                OutlinedTextField(
                    value = notes, onValueChange = { notes = it.take(JournalRules.MAX_NOTES) }, label = { Text("Notizen") },
                    minLines = 3, maxLines = 8, modifier = Modifier.fillMaxWidth(),
                )
            }
            error?.let { item { Muted(it, color = MaterialTheme.colorScheme.error) } }
            item {
                Button(onClick = {
                    val entry = initial.copy(
                        date = date.trim(),
                        watered = watered,
                        waterMl = parseNumber(water),
                        nutrientsMl = parseNumber(nutrients),
                        values = values.mapNotNull { (m, text) -> parseNumber(text)?.let { m to it } }.toMap(),
                        notes = notes.trim().ifBlank { null },
                    )
                    error = onSave(entry)
                }, modifier = Modifier.fillMaxWidth()) { Text("Speichern") }
            }
        }
    }
}

private fun plain(value: Double): String = if (value % 1.0 == 0.0) value.toLong().toString() else value.toString().replace('.', ',')

@Composable
private fun NumberInput(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.filter { c -> c.isDigit() || c == ',' || c == '.' || c == '-' }.take(8)) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(),
    )
}
