package de.greenganja.growguardian.grow.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.greenganja.growguardian.grow.light.LightRules
import de.greenganja.growguardian.grow.light.LightSetup
import de.greenganja.growguardian.grow.plants.Plant
import de.greenganja.growguardian.grow.plants.PlantRules
import de.greenganja.growguardian.ui.wizard.WizardFlow
import de.greenganja.growguardian.ui.wizard.WizardPage
import de.greenganja.growguardian.ui.wizard.WizardScaffold

enum class AppWizardMode {
    /** Erster Start: führt durch die ganze App außer dem Club-Bereich. */
    FULL,

    /** Nur die Einstellungen einer Box, z. B. direkt nach dem Anlegen. */
    BOX,
}

/** Seiten der App-Einführung, ohne Compose. */
object AppWizardContent {
    /** Bei neuen Seiten erhöhen, dann erscheint die Einführung erneut. */
    const val VERSION = 1

    private val BOX_STEPS = setOf("plants", "light", "sensors", "targets", "alerts")

    fun pages(mode: AppWizardMode, item: BoxItem?, alertsAllowed: Boolean, automationsAllowed: Boolean, allLampTypes: Boolean): List<WizardPage> {
        val box = item?.box
        val all = listOf(
            WizardPage(
                "welcome", "Willkommen!",
                "In wenigen Schritten richtest du deine erste Grow-Box ein und lernst die App kennen. Jeden Schritt kannst du überspringen; die Einführung startest du jederzeit neu unter Konto.",
                listOf("Box mit Medium, Phase und Blatttemperatur", "Pflanzen, Licht und Sensoren", "Zielwerte und Alarme", "Analyse, Tagebuch, Coins und Konto"),
            ),
            WizardPage(
                "box", "Grow-Box anlegen",
                "Eine Box ist ein Zelt oder Schrank mit eigenem Klima. Name, Medium (Erde, Kokos, Hydro) und Phase bestimmen die Startwerte für Temperatur, Luftfeuchte, VPD, pH und EC.",
                listOf("Die Blatttemperatur (meist 1–2 °C unter der Luft) braucht der VPD.", "Alles lässt sich später unter Bearbeiten ändern."),
                done = box != null,
            ),
            WizardPage(
                "plants", "Pflanzen",
                "Leg jede Pflanze einzeln an: Name, Sorte, Samen oder Steckling, Automatik, Starttag und Blütebeginn. Nach der Ernte trägst du Erntetag und Trockengewicht ein.",
                listOf("Alle Pflanzen einer Box teilen Sensoren, Zielwerte und Phase.", "Privat erlaubt § 9 KCanG höchstens 3 Pflanzen je volljähriger Person."),
                done = item?.activePlants?.isNotEmpty() == true,
            ),
            WizardPage(
                "light", "Licht",
                "Trag deine Lampen ein: Art, Leistung, Anzahl, Fläche und Lichtstunden. Daraus rechnet die App PPFD, Tageslichtmenge und Spektrum; messen kannst du später im Lichtmodul mit dem Handy.",
                listOfNotNull(
                    "Ohne Abo: Tageslicht und Glühbirne, alle anderen Lampen ab Premium.".takeIf { !allLampTypes },
                    "Alle 28 Lampenarten freigeschaltet.".takeIf { allLampTypes },
                ),
                done = box?.light != null,
            ),
            WizardPage(
                "sensors", "Sensoren",
                "Koppel deine Tuya-Sensoren (Klima, CO₂, Boden, Wasser, Licht, Steckdosen) und ordne sie der Box zu. Dann siehst du die Werte live.",
                listOf("Bluetooth-, WLAN- und Zigbee-Geräte über „Gerät koppeln“.", "Ein Sensor gehört immer zu genau einer Box."),
                done = box?.deviceIds?.isNotEmpty() == true,
            ),
            WizardPage(
                "targets", "Zielwerte",
                "Für jede Messgröße gibt es einen Zielbereich passend zu Phase und Medium. Passe ihn an, wenn deine Sorte oder dein Setup anderes braucht.",
                box?.targets?.entries?.sortedBy { it.key.ordinal }?.take(6)?.map { (m, r) -> "${m.label}: ${r.format(m)}" }.orEmpty(),
            ),
            WizardPage(
                "alerts", "Alarme und Automationen",
                if (alertsAllowed) "Lass dich per Push benachrichtigen, wenn ein Wert aus dem Ziel läuft oder ein Sensor offline geht."
                else "Alarme gibt es ab Premium Farmer oder für ein paar Tage gegen Coins.",
                listOf(
                    if (automationsAllowed) "Automationen schalten Licht, Lüfter oder Entfeuchter nach Zeitplan oder Messwert, zu finden in der Box."
                    else "Automationen (z. B. „Licht 18/6“ oder „Entfeuchter an über 60 %“) gibt es ab Premium.",
                ),
                done = if (alertsAllowed) box?.alertsEnabled == true else null,
            ),
            WizardPage(
                "analysis", "Analyse, Verlauf und Tagebuch",
                "Unter „Verlauf & Analyse“ siehst du Kurven von 24 Stunden bis zu einem Jahr, mit Zielband, Trend und einem Licht-Reiter mit Tageslichtmenge.",
                listOf("Das Tagebuch hält Gießen, Dünger, Handmessungen und Notizen fest, für die ganze Box oder eine Pflanze.", "Freemium speichert 7 Tage Verlauf, Premium ein Jahr."),
            ),
            WizardPage(
                "coins", "Coins und Werbung",
                "Im Reiter Coins holst du dir täglich Coins, bekommst welche für Videos und Erfolge und löst sie gegen werbefreie Tage oder Alarme ein.",
                listOf("Coins sind kein Geld und lassen sich nicht kaufen oder auszahlen.", "Social Club ist werbefrei, außer in den Einführungen."),
            ),
            WizardPage(
                "account", "Konto",
                "Unter Konto verwaltest du dein Abo, änderst die Werbe-Einwilligung, startest die Einführungen neu, lädst deine Daten herunter oder löschst dein Konto.",
                listOf("Den Club-Bereich erklärt eine eigene Einführung, sobald du ihn öffnest."),
            ),
            WizardPage(
                "done", if (mode == AppWizardMode.BOX) "Box eingerichtet" else "Fertig!",
                if (mode == AppWizardMode.BOX) "Das ist eingerichtet. Offene Schritte findest du jederzeit in der Box unter „Einrichtung“."
                else "Du kennst jetzt die wichtigsten Bereiche. Viel Erfolg mit deinem Grow!",
            ),
        )
        return if (mode == AppWizardMode.BOX) all.filter { it.key in BOX_STEPS || it.key == "done" } else all
    }

    /** Erledigte und offene Einrichtungsschritte einer Box, für die Zeile „Einrichtung“. */
    fun boxProgress(item: BoxItem, alertsAllowed: Boolean): Pair<Int, Int> {
        val tasks = pages(AppWizardMode.BOX, item, alertsAllowed, automationsAllowed = false, allLampTypes = false).filter { it.done != null }
        return WizardFlow.doneCount(tasks) to tasks.size
    }
}

/**
 * Einführung mit echten Einstellungen: Box anlegen, Pflanzen, Licht, Sensoren, Zielwerte, Alarme, dann ein kurzer
 * Rundgang durch Analyse, Coins und Konto. Banner sehen hier alle Stufen.
 */
@Composable
internal fun AppWizard(
    mode: AppWizardMode,
    boxId: String?,
    state: GrowUiState,
    viewModel: GrowViewModel,
    onSelectBox: (String) -> Unit,
    onSaveLight: (boxId: String, setup: LightSetup) -> Unit,
    onPair: (() -> Unit)?,
    onUpgradeClick: () -> Unit,
    onFinish: (boxId: String?) -> Unit,
) {
    val item = state.boxes.firstOrNull { it.box.id == boxId } ?: if (mode == AppWizardMode.FULL) state.activeBoxes.lastOrNull() else null
    val features = state.access.features
    val pages = AppWizardContent.pages(mode, item, features.alerts, features.automations, features.allLampTypes)
    var index by rememberSaveable(mode, boxId) { mutableStateOf(if (mode == AppWizardMode.BOX) WizardFlow.firstOpen(pages) else 0) }
    var sub by remember { mutableStateOf<String?>(null) }
    var plant by remember { mutableStateOf<Plant?>(null) }
    val today = java.time.LocalDate.now(java.time.ZoneId.of("Europe/Berlin")).toString()

    // Unterseiten im Vollbild
    when (sub) {
        "newBox", "editBox" -> {
            BackHandler { sub = null }
            BoxWizard(
                initial = if (sub == "editBox") item?.box else null,
                busy = state.busy,
                onSave = { viewModel.saveBox(it); sub = null },
                onBack = { sub = null },
            )
            return
        }
        "lamps" -> if (item != null) {
            BackHandler { sub = null }
            LampEditor(
                initial = item.box.light ?: LightSetup(),
                phase = item.box.phase,
                allLampTypes = features.allLampTypes,
                onUpgradeClick = onUpgradeClick,
                onSave = { setup ->
                    LightRules.validate(setup, features.allLampTypes) ?: run {
                        onSaveLight(item.box.id, setup)
                        sub = null
                        null
                    }
                },
                onBack = { sub = null },
            )
            return
        }
    }
    plant?.let { current ->
        if (item != null) {
            BackHandler { plant = null }
            PlantEditor(
                initial = current,
                today = today,
                existingCount = state.activePlantCount,
                onSave = { edited ->
                    PlantRules.validate(edited, today, item.plants.size) ?: run {
                        viewModel.savePlant(item.box.id, edited)
                        plant = null
                        null
                    }
                },
                onBack = { plant = null },
            )
            return
        }
    }

    WizardScaffold(
        title = if (mode == AppWizardMode.BOX) "Box einrichten${item?.let { " · ${it.box.name}" } ?: ""}" else "Einführung",
        pages = pages,
        index = index,
        onIndex = { index = WizardFlow.clamp(it, pages.size) },
        onClose = { onFinish(item?.box?.id) },
        onFinish = { onFinish(item?.box?.id) },
        finishLabel = if (mode == AppWizardMode.BOX) "Zur Box" else "Los geht's",
    ) { page ->
        WizardStepContent(
            page = page,
            item = item,
            state = state,
            viewModel = viewModel,
            onSelectBox = onSelectBox,
            onSub = { sub = it },
            onNewPlant = { item?.let { plant = Plant(name = PlantRules.nextName(it.plants)) } },
            onPair = onPair,
            onUpgradeClick = onUpgradeClick,
        )
    }
}

@Composable
private fun WizardStepContent(
    page: WizardPage,
    item: BoxItem?,
    state: GrowUiState,
    viewModel: GrowViewModel,
    onSelectBox: (String) -> Unit,
    onSub: (String) -> Unit,
    onNewPlant: () -> Unit,
    onPair: (() -> Unit)?,
    onUpgradeClick: () -> Unit,
) {
    var dialog by remember { mutableStateOf<String?>(null) }
    val box = item?.box
    val needsBox = page.key in setOf("plants", "light", "sensors", "targets", "alerts")
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (needsBox && box == null) {
            Muted("Leg zuerst eine Box an, dann geht es hier weiter.")
            Button(onClick = { onSub("newBox") }, enabled = state.access.canAddBox, modifier = Modifier.fillMaxWidth()) { Text("Box anlegen") }
        } else when (page.key) {
            "box" -> {
                if (state.activeBoxes.size > 1) {
                    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.activeBoxes.forEach { b -> PillAction(if (b.box.id == box?.id) "✓ ${b.box.name}" else b.box.name, onClick = { onSelectBox(b.box.id) }) }
                    }
                }
                if (box != null) {
                    GrowCard {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(box.name, style = MaterialTheme.typography.titleMedium)
                            Muted("${box.medium.label} · ${box.phase.label} · Blatt ${String.format(java.util.Locale.GERMANY, "%+.1f", box.leafOffsetC)} °C")
                        }
                    }
                    OutlinedButton(onClick = { onSub("editBox") }, modifier = Modifier.fillMaxWidth()) { Text("Einstellungen der Box ändern") }
                }
                if (state.access.canAddBox) {
                    Button(onClick = { onSub("newBox") }, modifier = Modifier.fillMaxWidth()) { Text(if (box == null) "Box anlegen" else "Weitere Box anlegen") }
                } else if (box == null) {
                    Button(onClick = onUpgradeClick, modifier = Modifier.fillMaxWidth()) { Text("Mehr Boxen mit Premium") }
                }
            }
            "plants" -> {
                item!!.plants.filter { it.active }.forEach { Muted("${it.name} · ${PlantRules.details(it).ifEmpty { "ohne Sorte" }}", color = MaterialTheme.colorScheme.onSurface) }
                state.kcangHint?.let { Muted(it, color = de.greenganja.growguardian.ui.theme.GrowPalette.Warning) }
                Button(onClick = onNewPlant, enabled = item.plantsLoaded && !state.busy, modifier = Modifier.fillMaxWidth()) { Text("+ Pflanze anlegen") }
            }
            "light" -> {
                box!!.light?.lamps?.forEach { use ->
                    Muted("${use.count} × ${use.watts?.let { "${it.toInt()} W " } ?: ""}${use.lamp?.name ?: use.lampId}", color = MaterialTheme.colorScheme.onSurface)
                }
                Button(onClick = { onSub("lamps") }, modifier = Modifier.fillMaxWidth()) { Text(if (box.light == null) "Lampen eintragen" else "Lampen ändern") }
            }
            "sensors" -> {
                Muted("${box!!.deviceIds.size} Sensor(en) zugeordnet, ${state.devices.size} im Konto.")
                if (onPair != null) OutlinedButton(onClick = onPair, modifier = Modifier.fillMaxWidth()) { Text("Gerät koppeln") }
                Button(onClick = { dialog = "devices" }, enabled = !state.busy && !item!!.locked, modifier = Modifier.fillMaxWidth()) { Text("Sensoren zuordnen") }
            }
            "targets" -> Button(onClick = { dialog = "targets" }, enabled = !state.busy && !item!!.locked, modifier = Modifier.fillMaxWidth()) { Text("Zielwerte anpassen") }
            "alerts" -> AlarmCard(
                enabled = box!!.alertsEnabled,
                allowed = state.access.features.alerts,
                busy = state.busy,
                onToggle = { viewModel.setAlerts(box, it) },
                onUpgradeClick = onUpgradeClick,
            )
            "done" -> if (item != null) {
                AppWizardContent.pages(AppWizardMode.BOX, item, state.access.features.alerts, false, false).filter { it.done != null }.forEach {
                    Text((if (it.done == true) "✓ " else "○ ") + it.title, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
    when (dialog) {
        "devices" -> if (box != null) DeviceDialog(
            box = box,
            state = state,
            onDismiss = { dialog = null },
            onConfirm = { ids -> viewModel.setDevices(box, ids); dialog = null },
            onPair = onPair?.let { pair -> { dialog = null; pair() } },
        )
        "targets" -> if (box != null) TargetsDialog(
            box = box,
            onDismiss = { dialog = null },
            onConfirm = { targets -> viewModel.setTargets(box, targets); dialog = null },
        )
    }
}
