package de.greenganja.growguardian.grow.ui

import de.greenganja.growguardian.ads.inlineAd
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.greenganja.growguardian.grow.GrowBox
import de.greenganja.growguardian.grow.SensorDevice
import de.greenganja.growguardian.grow.automation.Automation
import de.greenganja.growguardian.grow.automation.AutomationRules
import de.greenganja.growguardian.grow.automation.AutomationTrigger
import de.greenganja.growguardian.ui.theme.GrowPalette

/**
 * Regeln einer Box: Liste mit Schalter und ein Editor für neue oder bestehende Regeln.
 *
 * @param devices alle Tuya-Geräte des Kontos; Sensoren und Schalter werden daraus gewählt
 * @param onMessage zeigt Hinweise in der Snackbar der Grow-Oberfläche
 */
@Composable
internal fun AutomationScreen(
    box: GrowBox,
    devices: Map<String, SensorDevice>,
    viewModel: AutomationViewModel,
    onUpgradeClick: () -> Unit,
    onMessage: (String) -> Unit,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val total by viewModel.totalCount.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<EditTarget?>(null) }

    LaunchedEffect(viewModel) {
        viewModel.eventFlow.collect { event ->
            when (event) {
                is AutomationEvent.Message -> onMessage(event.text)
                AutomationEvent.Saved -> editing = null
            }
        }
    }

    val target = editing
    if (target != null) {
        BackHandler { editing = null }
        AutomationEditor(
            box = box,
            devices = devices,
            initial = target.draft,
            base = target.base,
            busy = state.busy,
            onSave = viewModel::save,
            onDelete = target.base?.let { rule -> { viewModel.delete(rule) } },
            onBack = { editing = null },
        )
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        BackBar(title = "Automationen · ${box.name}", onBack = onBack)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!state.allowed) {
                item {
                    UpgradeRow(
                        title = "Automationen ab Premium Farmer",
                        subtitle = "Befeuchter, Lüfter und Licht automatisch schalten",
                        onClick = onUpgradeClick,
                    )
                }
            }
            inlineAd("automations-top")
            item {
                SectionHeader("Regeln") {
                    PillAction(
                        "+ Neue Regel",
                        enabled = !state.busy,
                        onClick = {
                            when {
                                !state.allowed -> onUpgradeClick()
                                state.limitReached(total) -> onMessage("Deine Stufe erlaubt ${state.features.maxAutomations} Regeln.")
                                else -> editing = EditTarget(AutomationDraft(boxId = box.id), base = null)
                            }
                        },
                    )
                }
            }
            if (state.items.isEmpty()) {
                item {
                    Muted(
                        "Noch keine Regel. Beispiele: Entfeuchter an, wenn die Luftfeuchte über 60 % steigt, oder Licht 18/6 über eine Steckdose.",
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            }
            state.items.forEach { rule ->
                item(key = rule.id) {
                    RuleCard(
                        rule = rule,
                        allowed = state.allowed,
                        busy = state.busy,
                        onToggle = { on -> if (state.allowed) viewModel.setEnabled(rule, on) else onUpgradeClick() },
                        onClick = { editing = EditTarget(AutomationDraft.of(rule), base = rule) },
                    )
                }
            }
            item {
                Muted(
                    "Die Regeln laufen in der Tuya-Cloud und schalten auch bei ausgeschaltetem Handy. " +
                        "Der Sensor muss dafür online sein.",
                )
            }
            inlineAd("automations-end")
        }
    }
}

private data class EditTarget(val draft: AutomationDraft, val base: Automation?)

@Composable
private fun RuleCard(rule: Automation, allowed: Boolean, busy: Boolean, onToggle: (Boolean) -> Unit, onClick: () -> Unit) {
    val active = allowed && rule.enabled
    GrowCard(onClick = onClick) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(
                    when (val t = rule.trigger) {
                        is AutomationTrigger.Threshold -> t.metric.icon
                        is AutomationTrigger.Schedule -> Icons.Filled.Schedule
                    },
                    tint = if (active) GrowPalette.Green else GrowPalette.TextMuted,
                    size = 38,
                )
                Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = rule.name,
                            modifier = Modifier.weight(1f, fill = false),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                        )
                        when {
                            !allowed -> StatusChip("Pausiert", GrowPalette.Gold, Modifier.padding(start = 8.dp))
                            rule.syncError != null -> StatusChip("Fehler", GrowPalette.Error, Modifier.padding(start = 8.dp))
                            !rule.synced && rule.enabled -> StatusChip("Wird übertragen", GrowPalette.TextMuted, Modifier.padding(start = 8.dp))
                        }
                    }
                    Muted(AutomationRules.describe(rule.trigger))
                    Muted("schaltet ${rule.actuatorLabel}")
                }
                Switch(checked = active, enabled = !busy, onCheckedChange = onToggle)
            }
            rule.syncError?.let { Muted(it, color = GrowPalette.Error) }
        }
    }
}

/** Neue Regel oder Änderung: Name, Art, Auslöser und was geschaltet wird. */
@Composable
private fun AutomationEditor(
    box: GrowBox,
    devices: Map<String, SensorDevice>,
    initial: AutomationDraft,
    base: Automation?,
    busy: Boolean,
    onSave: (Automation) -> Unit,
    onDelete: (() -> Unit)?,
    onBack: () -> Unit,
) {
    var draft by remember { mutableStateOf(initial) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val actuators = remember(devices) { AutomationDraft.actuators(devices.values) }
    val sensors = remember(devices, box.deviceIds) { AutomationDraft.sensors(devices.values, box.deviceIds) }
    val sensor = draft.sensorId?.let { devices[it] }
    val metrics = sensor?.let(AutomationDraft::automationMetrics).orEmpty()
    fun change(next: AutomationDraft) {
        draft = next
        error = null
    }

    Column(modifier = Modifier.fillMaxSize()) {
        BackBar(title = if (base == null) "Neue Regel" else "Regel bearbeiten", onBack = onBack) {
            if (onDelete != null) {
                IconButton(onClick = { confirmDelete = true }, enabled = !busy) {
                    Icon(imageVector = Icons.Filled.Delete, contentDescription = "Löschen", tint = GrowPalette.Error)
                }
            }
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                OutlinedTextField(
                    value = draft.name,
                    onValueChange = { change(draft.copy(name = it.take(AutomationRules.MAX_NAME))) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Name (optional)") },
                    singleLine = true,
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DraftKind.entries.forEach { kind ->
                        Pill(kind.label, selected = draft.kind == kind, onClick = { change(draft.copy(kind = kind)) })
                    }
                }
            }

            item { SectionHeader("Was soll geschaltet werden?") }
            if (actuators.isEmpty()) {
                item { Muted("Keine schaltbare Steckdose im Konto. Koppel zuerst eine Tuya-Steckdose.") }
            }
            actuators.forEach { device ->
                device.switches.forEach { channel ->
                    item(key = "act-${device.id}-${channel.dpId}") {
                        OptionRow(
                            title = if (device.switches.size > 1) "${device.name} · ${channel.label}" else device.name,
                            subtitle = if (device.online) null else "offline",
                            selected = draft.actuatorId == device.id && draft.actuatorDpId == channel.dpId,
                            onClick = { change(draft.copy(actuatorId = device.id, actuatorDpId = channel.dpId)) },
                        )
                    }
                }
            }

            if (draft.kind == DraftKind.SCHEDULE) {
                item { SectionHeader("Zeitplan") }
                item {
                    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LightCycles.HOURS.forEach { hours ->
                            val selected = AutomationRules.lightMinutes(draft.onTime, draft.offTime) == hours * 60
                            Pill("Licht ${LightCycles.label(hours)}", selected, onClick = { change(draft.withLightHours(hours)) })
                        }
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TimeField("An um", draft.onTime, { change(draft.withOnTime(it)) }, Modifier.weight(1f))
                        TimeField("Aus um", draft.offTime, { change(draft.copy(offTime = it)) }, Modifier.weight(1f))
                    }
                }
                item {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        // Wochentage Mo–So; Tuya zählt ab Sonntag, daher Index (i + 1) % 7
                        (1..7).forEach { i ->
                            val index = i % 7
                            val on = draft.days.getOrNull(index) == '1'
                            DayChip(AutomationRules.DAY_NAMES[index], on, onClick = { change(draft.toggleDay(index)) }, modifier = Modifier.weight(1f))
                        }
                    }
                }
                item {
                    Muted(AutomationRules.describe(AutomationTrigger.Schedule(draft.onTime, draft.offTime, draft.days)))
                }
            } else {
                item { SectionHeader("Auslöser") }
                if (sensors.isEmpty()) {
                    item { Muted("Kein Sensor mit Temperatur, Luftfeuchte, CO₂ oder Bodenwerten im Konto.") }
                }
                sensors.forEach { device ->
                    item(key = "sen-${device.id}") {
                        OptionRow(
                            title = device.name,
                            subtitle = AutomationDraft.automationMetrics(device).joinToString(", ") { it.label } +
                                if (device.online) "" else " · offline",
                            selected = draft.sensorId == device.id,
                            onClick = { change(draft.withSensor(device)) },
                        )
                    }
                }
                if (metrics.isNotEmpty()) {
                    item {
                        Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            metrics.forEach { m -> Pill(m.label, draft.metric == m, onClick = { change(draft.copy(metric = m)) }) }
                        }
                    }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Pill("An über Wert", draft.switchOnAbove, onClick = { change(draft.copy(switchOnAbove = true)) })
                            Pill("An unter Wert", !draft.switchOnAbove, onClick = { change(draft.copy(switchOnAbove = false)) })
                        }
                    }
                    item {
                        val unit = draft.metric?.unit.orEmpty()
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            NumberInput("Einschalten bei $unit".trim(), draft.onAt, { change(draft.copy(onAt = it)) }, Modifier.weight(1f))
                            NumberInput("Ausschalten bei $unit".trim(), draft.offAt, { change(draft.copy(offAt = it)) }, Modifier.weight(1f))
                        }
                    }
                    item {
                        Muted(
                            if (draft.switchOnAbove) "Der Ausschaltpunkt liegt tiefer als der Einschaltpunkt. Der Abstand verhindert ständiges Takten."
                            else "Der Ausschaltpunkt liegt höher als der Einschaltpunkt. Der Abstand verhindert ständiges Takten.",
                        )
                    }
                }
            }

            error?.let { item { Muted(it, color = GrowPalette.Error) } }
            item {
                Button(
                    onClick = {
                        draft.build(devices, base)
                            .onSuccess { error = null; onSave(it) }
                            .onFailure { error = it.message }
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (busy) "Speichert …" else "Speichern") }
            }
        }
    }

    if (confirmDelete && onDelete != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("${base?.name.orEmpty()} löschen?") },
            text = { Text("Die Regel und ihre Automationen in Tuya werden entfernt.") },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { confirmDelete = false; onDelete() }) {
                    Text("Löschen", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { confirmDelete = false }) { Text("Abbrechen") } },
        )
    }
}

@Composable
private fun Pill(text: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = if (selected) GrowPalette.Green else MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) GrowPalette.OnGreen else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
private fun DayChip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Text(
            text = text,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
private fun OptionRow(title: String, subtitle: String?, selected: Boolean, onClick: () -> Unit) {
    GrowCard(onClick = onClick) {
        Row(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.bodyLarge, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                if (!subtitle.isNullOrBlank()) Muted(subtitle)
            }
            if (selected) Icon(imageVector = Icons.Filled.Check, contentDescription = "Gewählt", tint = GrowPalette.Green)
        }
    }
}

@Composable
private fun TimeField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.filter { c -> c.isDigit() || c == ':' }.take(5)) },
        modifier = modifier,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}

@Composable
private fun NumberInput(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.filter { c -> c.isDigit() || c == ',' || c == '.' || c == '-' }.take(8)) },
        modifier = modifier,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
    )
}
