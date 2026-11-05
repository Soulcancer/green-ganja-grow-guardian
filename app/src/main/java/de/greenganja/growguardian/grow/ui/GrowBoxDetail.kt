package de.greenganja.growguardian.grow.ui

import de.greenganja.growguardian.ads.inlineAd
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Switch
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.greenganja.growguardian.grow.Metric
import de.greenganja.growguardian.grow.plants.PlantRules
import de.greenganja.growguardian.grow.plants.Plant
import de.greenganja.growguardian.grow.SensorDevice
import de.greenganja.growguardian.ui.theme.GrowPalette
import java.util.Locale

private const val DIALOG_EDIT = "edit"
private const val DIALOG_DEVICES = "devices"
private const val DIALOG_TARGETS = "targets"
private const val DIALOG_DELETE = "delete"

/** Detailansicht einer Box: Kacheln, alle Messwerte mit Zielbereich, Sensoren und Zielwerte. */
@Composable
internal fun BoxDetail(
    item: BoxItem,
    state: GrowUiState,
    viewModel: GrowViewModel,
    onUpgradeClick: () -> Unit,
    onPair: (() -> Unit)?,
    onHistory: (() -> Unit)?,
    onAutomations: (() -> Unit)? = null,
    onJournal: (() -> Unit)? = null,
    onLight: (() -> Unit)? = null,
    onBack: () -> Unit,
) {
    val box = item.box
    var dialog by rememberSaveable { mutableStateOf<String?>(null) }
    var editingPlant by remember { mutableStateOf<Plant?>(null) }
    var harvestingPlant by remember { mutableStateOf<Plant?>(null) }
    var deletingPlant by remember { mutableStateOf<Plant?>(null) }
    val today = java.time.LocalDate.now(java.time.ZoneId.of("Europe/Berlin")).toString()

    editingPlant?.let { plant ->
        androidx.activity.compose.BackHandler { editingPlant = null }
        PlantEditor(
            initial = plant,
            today = today,
            existingCount = state.activePlantCount,
            onSave = { edited ->
                PlantRules.validate(edited, today, item.plants.size) ?: run {
                    viewModel.savePlant(box.id, edited)
                    editingPlant = null
                    null
                }
            },
            onBack = { editingPlant = null },
        )
        return
    }
    harvestingPlant?.let { plant ->
        HarvestDialog(plant, today, onDismiss = { harvestingPlant = null }, onConfirm = { harvested ->
            PlantRules.validate(harvested, today) ?: run {
                viewModel.savePlant(box.id, harvested, "„${plant.name}“ geerntet.")
                harvestingPlant = null
                null
            }
        })
    }
    deletingPlant?.let { plant ->
        DeletePlantDialog(
            plant = plant,
            boxName = box.name,
            lastActive = plant.active && item.activePlants.size == 1,
            onDismiss = { deletingPlant = null },
            onConfirm = { deleteJournal, resetPhase ->
                deletingPlant = null
                viewModel.deletePlant(box, plant, deleteJournal, resetPhase)
            },
        )
    }

    if (dialog == DIALOG_EDIT) {
        BoxWizard(
            initial = box,
            busy = state.busy,
            onSave = {
                viewModel.saveBox(it)
                dialog = null
            },
            onBack = { dialog = null },
        )
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        BackBar(title = box.name, onBack = onBack) {
            if (!item.locked) {
                IconButton(onClick = { dialog = DIALOG_EDIT }) {
                    Icon(imageVector = Icons.Filled.Edit, contentDescription = "Bearbeiten")
                }
            }
            IconButton(onClick = { dialog = DIALOG_DELETE }, enabled = !state.busy) {
                Icon(imageVector = Icons.Filled.Delete, contentDescription = "Box löschen")
            }
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Muted("${box.phase.label} · ${box.medium.label}", modifier = Modifier.weight(1f))
                    if (item.locked) StatusChip("Gesperrt", GrowPalette.TextMuted)
                    else StatusChip(item.state.status.label, item.state.status.color)
                }
            }
            plantItems(
                item = item,
                today = today,
                kcangHint = state.kcangHint,
                busy = state.busy,
                onAdd = { editingPlant = Plant(name = PlantRules.nextName(item.plants)) },
                onOpen = { editingPlant = it },
                onHarvest = { harvestingPlant = it },
                onDelete = { deletingPlant = it },
            )
            inlineAd("box-detail-top")
            if (item.locked) {
                item { LockedBanner(onUpgradeClick) }
            } else {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(Metric.AIR_TEMPERATURE, Metric.AIR_HUMIDITY, Metric.VPD).forEach { metric ->
                            ValueTile(metric, item.state.reading(metric), modifier = Modifier.weight(1f))
                        }
                    }
                }
                item { Readings(item, sensorsLoading = state.sensorsLoading) }
                if (onHistory != null) {
                    item {
                        UpgradeLikeRow(
                            icon = Icons.Filled.Timeline,
                            title = "Verlauf & Analyse",
                            subtitle = "Kurven von 24 Stunden bis zu einem Jahr",
                            onClick = onHistory,
                        )
                    }
                }
                if (onJournal != null) {
                    item {
                        UpgradeLikeRow(
                            icon = Icons.Filled.Edit,
                            title = "Tagebuch",
                            subtitle = "Gießen, Düngen, Messwerte von Hand, Notizen",
                            onClick = onJournal,
                        )
                    }
                }
                if (onLight != null) {
                    item {
                        UpgradeLikeRow(
                            icon = Icons.Filled.WbSunny,
                            title = "Licht & Spektrum",
                            subtitle = if (state.access.features.allLampTypes) "PPFD, DLI und Spektrum für alle Lampenarten"
                            else "PPFD und DLI für Tageslicht und Glühbirne, alle Lampen ab Premium",
                            onClick = onLight,
                        )
                    }
                }
                if (onAutomations != null) {
                    item {
                        UpgradeLikeRow(
                            icon = Icons.Filled.Bolt,
                            title = "Automationen",
                            subtitle = if (state.access.features.automations) "Geräte nach Messwert oder Zeitplan schalten" else "Ab Premium Farmer",
                            onClick = onAutomations,
                        )
                    }
                }
                item {
                    AlarmCard(
                        enabled = box.alertsEnabled,
                        allowed = state.access.features.alerts,
                        busy = state.busy,
                        onToggle = { viewModel.setAlerts(box, it) },
                        onUpgradeClick = onUpgradeClick,
                    )
                }
            }

            item {
                SectionHeader("Sensoren") {
                    if (!item.locked) PillAction("Zuordnen", onClick = { dialog = DIALOG_DEVICES }, enabled = !state.busy)
                }
            }
            item { DeviceList(box.deviceIds, state) }

            item {
                SectionHeader("Zielwerte") {
                    if (!item.locked) PillAction("Anpassen", onClick = { dialog = DIALOG_TARGETS }, enabled = !state.busy)
                }
            }
            item {
                GrowCard {
                    Column(modifier = Modifier.padding(vertical = 6.dp)) {
                        box.targets.forEach { (metric, range) ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(text = metric.label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                if (metric in box.customTargets) {
                                    StatusChip("eigener Wert", GrowPalette.Gold, modifier = Modifier.padding(end = 8.dp))
                                }
                                Text(text = range.format(metric), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }
            }
            item {
                val offset = String.format(Locale.GERMANY, "%+.1f", box.leafOffsetC)
                Muted("Der VPD rechnet mit einer Blatttemperatur von $offset °C gegenüber der Luft. Ändern unter Bearbeiten.")
            }
            item {
                TextButton(onClick = { dialog = DIALOG_DELETE }, enabled = !state.busy) {
                    Text("Box löschen", color = MaterialTheme.colorScheme.error)
                }
            }
            inlineAd("box-detail-end")
        }
    }

    when (dialog) {
        DIALOG_DEVICES -> DeviceDialog(
            box = box,
            state = state,
            onDismiss = { dialog = null },
            onConfirm = { ids ->
                viewModel.setDevices(box, ids)
                dialog = null
            },
            onPair = onPair?.let { pair -> { dialog = null; pair() } },
        )
        DIALOG_TARGETS -> TargetsDialog(
            box = box,
            onDismiss = { dialog = null },
            onConfirm = { targets ->
                viewModel.setTargets(box, targets)
                dialog = null
            },
        )
        DIALOG_DELETE -> DeleteDialog(
            name = box.name,
            onDismiss = { dialog = null },
            onConfirm = {
                viewModel.deleteBox(box.id)
                dialog = null
                onBack()
            },
        )
    }
}

@Composable
private fun Readings(item: BoxItem, sensorsLoading: Boolean) {
    GrowCard {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = "Aktuelle Messwerte", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            Muted(if (sensorsLoading) "lädt …" else "live")
        }
        if (item.state.readings.isEmpty()) {
            Muted(
                text = if (item.box.deviceIds.isEmpty()) "Ordne Sensoren zu, um Werte zu sehen." else "Noch keine Messwerte von den Sensoren.",
                modifier = Modifier.padding(16.dp),
            )
        }
        item.state.readings.forEachIndexed { index, reading ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            ReadingRow(reading)
        }
    }
}

@Composable
private fun DeviceList(deviceIds: List<String>, state: GrowUiState) {
    GrowCard {
        Column(modifier = Modifier.padding(vertical = 6.dp)) {
            if (deviceIds.isEmpty()) {
                Muted("Noch keine Sensoren zugeordnet.", modifier = Modifier.padding(16.dp))
            }
            deviceIds.forEachIndexed { index, id ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                DeviceRow(id, state.devices[id], monitored = state.access.isMonitored(id), sensorsLoading = state.sensorsLoading)
            }
        }
    }
}

@Composable
private fun DeviceRow(id: String, device: SensorDevice?, monitored: Boolean, sensorsLoading: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBadge(device?.kind?.icon ?: Icons.Filled.Sensors, tint = if (device?.online == true) GrowPalette.Green else GrowPalette.TextMuted, size = 32)
        Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
            Text(text = device?.name ?: if (sensorsLoading) "Lädt …" else "Nicht im Tuya-Konto", style = MaterialTheme.typography.bodyLarge)
            val values = device?.values?.entries?.joinToString(" · ") { (metric, value) -> metric.format(value) }
            Muted(listOfNotNull(device?.kind?.label, values?.takeIf { it.isNotEmpty() }).joinToString(" · ").ifEmpty { id })
        }
        when {
            !monitored -> StatusChip("Über Limit", GrowPalette.Gold)
            device == null -> Unit
            device.online -> StatusChip("Online", GrowPalette.Green)
            else -> StatusChip("Offline", GrowPalette.Error)
        }
    }
}

@Composable
private fun LockedBanner(onUpgradeClick: () -> Unit) {
    GrowCard {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(Icons.Filled.Lock, tint = GrowPalette.Gold)
                Text(
                    text = "Diese Box ist gesperrt",
                    modifier = Modifier.padding(start = 12.dp),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Muted("Deine Stufe überwacht weniger Boxen, als du angelegt hast. Mit einem Upgrade oder nach dem Löschen einer älteren Box ist sie wieder aktiv.")
            Button(onClick = onUpgradeClick, modifier = Modifier.fillMaxWidth()) { Text("Upgrade ansehen") }
        }
    }
}

/** Zeile mit Icon und Pfeil, im Stil der Upgrade-Zeile, aber grün. */
@Composable
private fun UpgradeLikeRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    GrowCard(onClick = onClick) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBadge(icon, size = 38)
            Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(text = title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Muted(subtitle)
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Alarme an/aus. Ab Android 13 fragt die App beim Einschalten nach der Berechtigung für Benachrichtigungen.
 * Ohne Premium oder Social Club führt der Schalter zum Upgrade.
 */
@Composable
private fun AlarmCard(enabled: Boolean, allowed: Boolean, busy: Boolean, onToggle: (Boolean) -> Unit, onUpgradeClick: () -> Unit) {
    val context = LocalContext.current
    var permissionMissing by remember { mutableStateOf(!notificationsAllowed(context)) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionMissing = !granted
        if (granted) onToggle(true)
    }
    val active = allowed && enabled
    GrowCard {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(Icons.Filled.NotificationsActive, tint = if (active) GrowPalette.Green else GrowPalette.TextMuted, size = 38)
                Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = "Alarme", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                        if (!allowed) StatusChip("Premium", GrowPalette.Gold, modifier = Modifier.padding(start = 8.dp))
                    }
                    Muted(
                        when {
                            !allowed -> "Push-Nachricht, wenn ein Wert aus dem Ziel läuft"
                            active && permissionMissing -> "Benachrichtigungen sind für die App ausgeschaltet"
                            active -> "Meldet Werte außerhalb des Ziels und Sensoren offline"
                            else -> "Aus"
                        },
                    )
                }
                Switch(
                    checked = active,
                    enabled = !busy,
                    onCheckedChange = { on ->
                        when {
                            !allowed -> onUpgradeClick()
                            on && Build.VERSION.SDK_INT >= 33 && !notificationsAllowed(context) ->
                                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            else -> onToggle(on)
                        }
                    },
                )
            }
            if (active) Muted("Geprüft wird etwa alle 15 Minuten, auch bei geschlossener App.")
        }
    }
}

private fun notificationsAllowed(context: Context): Boolean =
    Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
