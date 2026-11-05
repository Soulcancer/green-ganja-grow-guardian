package de.greenganja.growguardian.grow.ui

import de.greenganja.growguardian.grow.GrowBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.filled.MoreVert
import de.greenganja.growguardian.ads.inlineAd
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.greenganja.growguardian.billing.FarmerTier
import de.greenganja.growguardian.billing.SubscriptionManager
import de.greenganja.growguardian.grow.GrowBoxRepository
import de.greenganja.growguardian.grow.Metric
import de.greenganja.growguardian.grow.SensorSource
import de.greenganja.growguardian.grow.automation.GrowAutomations
import de.greenganja.growguardian.grow.journal.JournalRepository
import de.greenganja.growguardian.grow.light.LightRepository
import de.greenganja.growguardian.grow.plants.PlantRepository
import de.greenganja.growguardian.grow.light.PhoneLightSensor
import de.greenganja.growguardian.grow.history.GrowHistoryRepository
import de.greenganja.growguardian.tuya.TuyaPairing
import de.greenganja.growguardian.ui.theme.GrowPalette
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val ROUTE_LIST = "list"
private const val ROUTE_NEW = "new"
private const val ROUTE_BOX = "box:"
private const val ROUTE_PAIR = "pair"
private const val ROUTE_HISTORY = "history:"
private const val ROUTE_AUTOMATIONS = "automations:"
private const val ROUTE_JOURNAL = "journal:"
private const val ROUTE_LIGHT = "light:"

/**
 * Einstieg in die Grow-Box-Überwachung: Übersicht, Box-Details und Assistent für neue Boxen.
 *
 * @param sensors in der App [de.greenganja.growguardian.tuya.TuyaSensorSource]
 * @param pairing Geräte koppeln; ohne wird der Eintrag „Gerät koppeln“ ausgeblendet
 * @param onUpgradeClick öffnet euren Kaufbildschirm
 * @param automations Regeln wie „Entfeuchter an über 60 %“ oder „Licht 18/6“; ohne wird der Eintrag ausgeblendet
 */
@Composable
fun GrowScreen(
    repository: GrowBoxRepository,
    sensors: SensorSource,
    subscriptions: SubscriptionManager,
    pairing: TuyaPairing?,
    onUpgradeClick: () -> Unit,
    history: GrowHistoryRepository? = null,
    automations: GrowAutomations? = null,
    wallet: kotlinx.coroutines.flow.Flow<de.greenganja.growguardian.rewards.Wallet>? = null,
    journal: JournalRepository = remember { JournalRepository() },
    light: LightRepository = remember { LightRepository() },
    plantRepository: PlantRepository = remember { PlantRepository() },
    modifier: Modifier = Modifier,
    viewModel: GrowViewModel = viewModel { GrowViewModel(repository, sensors, subscriptions, automations, wallet, journal, plantRepository) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var route by rememberSaveable { mutableStateOf(ROUTE_LIST) }

    LaunchedEffect(viewModel) {
        viewModel.eventFlow.collect { event ->
            when (event) {
                is GrowEvent.Message -> snackbar.showSnackbar(event.text)
                is GrowEvent.BoxCreated -> route = ROUTE_BOX + event.boxId
            }
        }
    }
    BackHandler(enabled = route != ROUTE_LIST) {
        route = when {
            route.startsWith(ROUTE_HISTORY) -> ROUTE_BOX + route.removePrefix(ROUTE_HISTORY)
            route.startsWith(ROUTE_AUTOMATIONS) -> ROUTE_BOX + route.removePrefix(ROUTE_AUTOMATIONS)
            route.startsWith(ROUTE_JOURNAL) -> ROUTE_BOX + route.removePrefix(ROUTE_JOURNAL)
            route.startsWith(ROUTE_LIGHT) -> ROUTE_BOX + route.removePrefix(ROUTE_LIGHT)
            else -> ROUTE_LIST
        }
    }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            when {
                state.loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                route == ROUTE_PAIR && pairing != null -> {
                    val pairingViewModel: PairingViewModel = viewModel { PairingViewModel(pairing) }
                    PairingScreen(pairingViewModel, onClose = {
                        pairingViewModel.restart()
                        route = ROUTE_LIST
                    })
                }
                route.startsWith(ROUTE_HISTORY) && history != null -> {
                    val boxId = route.removePrefix(ROUTE_HISTORY)
                    val item = state.item(boxId)
                    if (item == null) {
                        LaunchedEffect(route) { route = ROUTE_LIST }
                    } else {
                        val historyViewModel: HistoryViewModel = viewModel(key = "history-$boxId") {
                            HistoryViewModel(history, boxId, subscriptions)
                        }
                        val lightReadings by remember(boxId) { light.readings(boxId, limit = 200) }
                            .collectAsStateWithLifecycle(initialValue = emptyList())
                        HistoryScreen(
                            item.box, historyViewModel, onUpgradeClick, onBack = { route = ROUTE_BOX + boxId },
                            lightReadings = lightReadings,
                            co2Ppm = item.state.reading(Metric.CO2)?.value,
                        )
                    }
                }
                route.startsWith(ROUTE_JOURNAL) -> {
                    val boxId = route.removePrefix(ROUTE_JOURNAL)
                    val item = state.item(boxId)
                    if (item == null) {
                        LaunchedEffect(route) { route = ROUTE_LIST }
                    } else {
                        val journalViewModel: JournalViewModel = viewModel(key = "journal-$boxId") { JournalViewModel(boxId, journal) }
                        val scope = rememberCoroutineScope()
                        JournalScreen(
                            box = item.box,
                            viewModel = journalViewModel,
                            onSaveBox = viewModel::saveBox,
                            onMessage = { text -> scope.launch { snackbar.showSnackbar(text) } },
                            onBack = { route = ROUTE_BOX + boxId },
                            plants = item.plants,
                        )
                    }
                }
                route.startsWith(ROUTE_LIGHT) -> {
                    val boxId = route.removePrefix(ROUTE_LIGHT)
                    val item = state.item(boxId)
                    if (item == null || item.locked) {
                        LaunchedEffect(route) { route = ROUTE_LIST }
                    } else {
                        val context = LocalContext.current
                        val lightViewModel: LightViewModel = viewModel(key = "light-$boxId") {
                            LightViewModel(boxId, light, PhoneLightSensor(context))
                        }
                        val scope = rememberCoroutineScope()
                        LightScreen(
                            item = item,
                            allLampTypes = state.access.features.allLampTypes,
                            viewModel = lightViewModel,
                            onUpgradeClick = onUpgradeClick,
                            onMessage = { text -> scope.launch { snackbar.showSnackbar(text) } },
                            onBack = { route = ROUTE_BOX + boxId },
                        )
                    }
                }
                route.startsWith(ROUTE_AUTOMATIONS) && automations != null -> {
                    val boxId = route.removePrefix(ROUTE_AUTOMATIONS)
                    val item = state.item(boxId)
                    if (item == null) {
                        LaunchedEffect(route) { route = ROUTE_LIST }
                    } else {
                        val automationViewModel: AutomationViewModel = viewModel(key = "automations-$boxId") {
                            AutomationViewModel(boxId, automations, subscriptions)
                        }
                        val scope = rememberCoroutineScope()
                        AutomationScreen(
                            box = item.box,
                            devices = state.devices,
                            viewModel = automationViewModel,
                            onUpgradeClick = onUpgradeClick,
                            onMessage = { text -> scope.launch { snackbar.showSnackbar(text) } },
                            onBack = { route = ROUTE_BOX + boxId },
                        )
                    }
                }
                route == ROUTE_NEW -> BoxWizard(
                    initial = null,
                    busy = state.busy,
                    onSave = { viewModel.saveBox(it) },
                    onBack = { route = ROUTE_LIST },
                )
                route.startsWith(ROUTE_BOX) -> {
                    val item = state.item(route.removePrefix(ROUTE_BOX))
                    if (item == null) {
                        // Gerade angelegt und noch nicht im Snapshot, oder gelöscht: kurz warten, dann zur Übersicht
                        LaunchedEffect(route) {
                            delay(3_000)
                            route = ROUTE_LIST
                        }
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    } else {
                        BoxDetail(
                            item, state, viewModel, onUpgradeClick,
                            onPair = pairing?.let { { route = ROUTE_PAIR } },
                            onHistory = history?.let { { route = ROUTE_HISTORY + item.box.id } },
                            onAutomations = automations?.let { { route = ROUTE_AUTOMATIONS + item.box.id } },
                            onJournal = { route = ROUTE_JOURNAL + item.box.id },
                            onLight = { route = ROUTE_LIGHT + item.box.id },
                            onBack = { route = ROUTE_LIST },
                        )
                    }
                }
                else -> Overview(
                    state = state,
                    onDelete = { viewModel.deleteBox(it.id) },
                    onOpen = { route = ROUTE_BOX + it },
                    onNew = { route = ROUTE_NEW },
                    onPair = pairing?.let { { route = ROUTE_PAIR } },
                    onRetry = viewModel::retrySensors,
                    onUpgradeClick = onUpgradeClick,
                )
            }
        }
    }
}

@Composable
private fun Overview(
    state: GrowUiState,
    onDelete: (GrowBox) -> Unit,
    onOpen: (String) -> Unit,
    onNew: () -> Unit,
    onPair: (() -> Unit)?,
    onRetry: () -> Unit,
    onUpgradeClick: () -> Unit,
) {
    var menuFor by remember { mutableStateOf<GrowBox?>(null) }
    var deleting by remember { mutableStateOf<GrowBox?>(null) }
    menuFor?.let { box ->
        BoxActionsDialog(
            box = box,
            onOpen = { menuFor = null; onOpen(box.id) },
            onDelete = { menuFor = null; deleting = box },
            onDismiss = { menuFor = null },
        )
    }
    deleting?.let { box ->
        DeleteDialog(name = box.name, onDismiss = { deleting = null }, onConfirm = { deleting = null; onDelete(box) })
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text(text = "Meine Grow-Boxen", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold) }
        item { LimitSummary(state) }
        inlineAd("grow-overview-top")
        if (state.tier != FarmerTier.SOCIAL_CLUB && !state.isClubSeat) {
            item {
                UpgradeRow(
                    title = if (state.tier == FarmerTier.FREEMIUM) "Mehr Boxen & Sensoren" else "Unbegrenzt mit Social Club",
                    subtitle = if (state.tier == FarmerTier.FREEMIUM) "Werde Premium Farmer" else "Für Vereine mit 5 Team-Plätzen",
                    onClick = onUpgradeClick,
                )
            }
        }
        state.sensorError?.let { message ->
            item {
                GrowCard {
                    Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconBadge(Icons.Filled.CloudOff, tint = GrowPalette.Error)
                        Muted(message, modifier = Modifier.weight(1f).padding(horizontal = 12.dp), color = MaterialTheme.colorScheme.onSurface)
                        TextButton(onClick = onRetry) { Text("Erneut") }
                    }
                }
            }
        }
        if (onPair != null) {
            item {
                GrowCard(onClick = onPair) {
                    Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconBadge(Icons.Filled.Sensors, size = 38)
                        Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(text = "Gerät koppeln", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                            Muted(if (state.devices.isEmpty()) "Noch keine Sensoren im Konto" else "${state.devices.size} Geräte im Konto")
                        }
                        StatusChip("+ Neu", GrowPalette.Green)
                    }
                }
            }
        }
        state.kcangHint?.let { hint -> item { Muted(hint, color = GrowPalette.Warning) } }
        item {
            SectionHeader("Aktive Boxen") {
                PillAction(text = "+ Neue Box", onClick = if (state.access.canAddBox) onNew else onUpgradeClick)
            }
        }
        if (state.boxes.isEmpty()) {
            item { EmptyBoxes(onNew) }
        }
        items(state.activeBoxes, key = { it.box.id }) { item ->
            BoxRow(item, sensorsLoading = state.sensorsLoading, onClick = { onOpen(item.box.id) }, onMenu = { menuFor = item.box }, menuEnabled = !state.busy)
        }
        if (state.lockedBoxes.isNotEmpty()) {
            item {
                SectionHeader("Gesperrt") { TextButton(onClick = onUpgradeClick) { Text("Upgrade") } }
            }
            item {
                Muted("Deine Stufe überwacht ${state.access.features.maxGrowBoxes} Box(en). Die übrigen bleiben gespeichert.")
            }
            items(state.lockedBoxes, key = { it.box.id }) { item ->
                BoxRow(item, sensorsLoading = false, onClick = { onOpen(item.box.id) }, onMenu = { menuFor = item.box }, menuEnabled = !state.busy)
            }
        }
        inlineAd("grow-overview-end")
    }
}

@Composable
private fun LimitSummary(state: GrowUiState) {
    val features = state.access.features
    GrowCard {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Muted("Boxen", modifier = Modifier.weight(1f))
                StatusChip(
                    text = state.tier.displayName.removeSuffix(" Farmer"),
                    color = if (state.tier == FarmerTier.FREEMIUM) GrowPalette.TextMuted else GrowPalette.Green,
                )
            }
            val maxBoxes = features.maxGrowBoxes
            Text(
                text = if (maxBoxes == null) "${state.access.boxCount} · unbegrenzt" else "${state.access.boxCount} / $maxBoxes",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            LinearProgressIndicator(
                progress = { if (maxBoxes == null) 1f else (state.access.boxCount.toFloat() / maxBoxes).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(50)),
                color = GrowPalette.Green,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            )
            val maxDevices = features.maxDevices
            Muted(
                if (maxDevices == null) "${state.access.assignedDeviceCount} Sensoren zugeordnet"
                else "${state.access.assignedDeviceCount} von $maxDevices Sensoren zugeordnet",
            )
        }
    }
}

@Composable
private fun BoxRow(item: BoxItem, sensorsLoading: Boolean, onClick: () -> Unit, onMenu: () -> Unit, menuEnabled: Boolean) {
    val box = item.box
    GrowCard(onClick = onClick) {
        Row(modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 12.dp, end = 0.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBadge(if (item.locked) Icons.Filled.Lock else Icons.Filled.Spa, tint = if (item.locked) GrowPalette.TextMuted else GrowPalette.Green, size = 48)
            Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(text = box.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                val plantCount = item.activePlants.size
                Muted(
                    listOfNotNull(
                        when (plantCount) { 0 -> null; 1 -> item.activePlants.first().strain ?: "1 Pflanze"; else -> "$plantCount Pflanzen" },
                        box.phase.label,
                        box.medium.label,
                    ).joinToString(" · "),
                    maxLines = 1,
                )
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                when {
                    item.locked -> StatusChip("Gesperrt", GrowPalette.TextMuted)
                    sensorsLoading && item.state.devices.isEmpty() && box.deviceIds.isNotEmpty() -> Muted("Lädt …")
                    else -> StatusChip(item.state.status.chipLabel, item.state.status.color)
                }
                val summary = listOfNotNull(
                    item.state.reading(Metric.AIR_TEMPERATURE)?.formatted,
                    item.state.reading(Metric.AIR_HUMIDITY)?.formatted,
                ).joinToString(" · ")
                if (summary.isNotEmpty()) Muted(summary, color = MaterialTheme.colorScheme.onSurface)
            }
            IconButton(onClick = onMenu, enabled = menuEnabled) {
                Icon(imageVector = Icons.Filled.MoreVert, contentDescription = "Aktionen für ${box.name}")
            }
        }
    }
}

@Composable
private fun EmptyBoxes(onNew: () -> Unit) {
    GrowCard {
        Column(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            IconBadge(Icons.Filled.Spa, size = 56)
            Text(text = "Noch keine Grow-Box", style = MaterialTheme.typography.titleMedium)
            Muted("Leg deine Box an, wähle Medium und Phase und ordne deine Tuya-Sensoren zu.")
            Button(onClick = onNew, modifier = Modifier.fillMaxWidth()) { Text("Box anlegen") }
        }
    }
}
