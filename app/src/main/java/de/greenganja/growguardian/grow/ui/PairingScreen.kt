package de.greenganja.growguardian.grow.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.greenganja.growguardian.tuya.WifiPairingMode
import de.greenganja.growguardian.ui.theme.GrowPalette

/** Berechtigungen, die Tuya für die jeweilige Kopplungsart braucht. */
internal fun pairingPermissions(method: PairingMethod, sdkInt: Int = Build.VERSION.SDK_INT): Array<String> = when (method) {
    PairingMethod.NEARBY -> if (sdkInt >= 31) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.ACCESS_FINE_LOCATION)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    PairingMethod.WIFI -> arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    PairingMethod.ZIGBEE -> emptyArray()
}

/**
 * Assistent „Gerät koppeln“: Bluetooth-Suche, WLAN (schnell oder langsam blinkend) oder Zigbee über ein Gateway.
 * Danach erscheint das Gerät automatisch in der Übersicht und lässt sich einer Box zuordnen.
 */
@Composable
internal fun PairingScreen(viewModel: PairingViewModel, onClose: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var pending by remember { mutableStateOf<PairingMethod?>(null) }
    var hint by remember { mutableStateOf<String?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        val method = pending ?: return@rememberLauncherForActivityResult
        pending = null
        if (results.values.all { it }) {
            viewModel.choose(method)
        } else {
            hint = "Ohne diese Berechtigung kann die App keine Geräte in der Nähe finden. Du kannst sie in den Android-Einstellungen erlauben."
        }
    }

    fun start(method: PairingMethod) {
        hint = null
        if (method == PairingMethod.NEARBY && !viewModel.isBluetoothOn) {
            hint = "Bitte schalte Bluetooth ein und tippe noch einmal."
            return
        }
        val permissions = pairingPermissions(method)
        if (permissions.isEmpty()) {
            viewModel.choose(method)
        } else {
            pending = method
            permissionLauncher.launch(permissions)
        }
    }

    val close = { if (!viewModel.back()) onClose() }
    BackHandler(onBack = close)

    Column(modifier = Modifier.fillMaxSize()) {
        BackBar(title = "Gerät koppeln", onBack = close)
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (val step = state.step) {
                PairingStep.Choose -> {
                    Muted("Wie verbindet sich dein Sensor? Steht nichts dabei, probier zuerst die Bluetooth-Suche.")
                    MethodRow(Icons.Filled.Bluetooth, "In der Nähe suchen", "Empfohlen für neue Sensoren, Bluetooth und Kombi-Geräte") { start(PairingMethod.NEARBY) }
                    MethodRow(Icons.Filled.Wifi, "WLAN-Gerät", "Ältere WLAN-Sensoren und Steckdosen, die blinken") { start(PairingMethod.WIFI) }
                    MethodRow(Icons.Filled.Hub, "Zigbee über Gateway", "Batterie-Sensoren mit Zigbee-Gateway") { start(PairingMethod.ZIGBEE) }
                    hint?.let { Muted(it, color = GrowPalette.Warning) }
                }

                PairingStep.WifiBlink -> {
                    Title("Wie blinkt das Gerät?")
                    Muted("Halte die Taste am Gerät etwa 5 Sekunden gedrückt, bis die Anzeige blinkt.")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ChoiceTile("Schnell", Icons.Filled.Wifi, selected = false, onClick = { viewModel.chooseBlink(WifiPairingMode.EZ) }, modifier = Modifier.weight(1f))
                        ChoiceTile("Langsam", Icons.Filled.Sensors, selected = false, onClick = { viewModel.chooseBlink(WifiPairingMode.AP) }, modifier = Modifier.weight(1f))
                    }
                    Muted("Schnell heißt etwa zweimal pro Sekunde, langsam etwa alle zwei Sekunden.")
                }

                is PairingStep.WifiForm -> {
                    Title(if (step.method == PairingMethod.NEARBY) "Dein WLAN für Kombi-Geräte" else "Mit welchem WLAN verbinden?")
                    Muted("Tuya-Geräte funken nur im 2,4-GHz-Netz. Das Passwort wird nur an das Gerät übertragen und nicht gespeichert.")
                    OutlinedTextField(
                        value = state.ssid,
                        onValueChange = viewModel::setSsid,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("WLAN-Name") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = state.password,
                        onValueChange = viewModel::setPassword,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("WLAN-Passwort") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    )
                    if (step.method == PairingMethod.NEARBY) Muted("Reine Bluetooth-Sensoren brauchen kein WLAN, die Angabe schadet aber nicht.")
                }

                PairingStep.ApConnect -> {
                    Title("Mit dem Gerät verbinden")
                    Steps(
                        "Öffne die WLAN-Einstellungen deines Handys.",
                        "Verbinde dich mit dem Netz „SmartLife-…“ oder ähnlich.",
                        "Komm hierher zurück und tippe auf „Verbunden“.",
                    )
                    OutlinedButton(
                        onClick = { context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("WLAN-Einstellungen öffnen") }
                }

                is PairingStep.Scanning -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (!step.finished) CircularProgressIndicator(modifier = Modifier.padding(end = 12.dp))
                        Title(if (step.finished) "Suche beendet" else "Suche nach Geräten …")
                    }
                    Muted("Das Gerät muss im Kopplungsmodus sein (Anzeige blinkt) und nah am Handy liegen.")
                    if (step.finished && step.found.isEmpty()) Muted("Nichts gefunden. Kopplungsmodus prüfen und erneut suchen.")
                    step.found.forEachIndexed { index, device ->
                        GrowCard(onClick = { viewModel.pairNearby(device) }) {
                            Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                IconBadge(if (device.needsWifi) Icons.Filled.Wifi else Icons.Filled.Bluetooth)
                                Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                    Text(text = "Neues Gerät ${index + 1}", style = MaterialTheme.typography.bodyLarge)
                                    Muted(if (device.needsWifi) "Bluetooth + WLAN" else "Bluetooth")
                                }
                                StatusChip("Koppeln", GrowPalette.Green)
                            }
                        }
                    }
                }

                is PairingStep.Gateways -> {
                    Title("Gateway wählen")
                    val gateways = step.gateways
                    when {
                        gateways == null -> CircularProgressIndicator()
                        gateways.isEmpty() -> Muted("Kein Zigbee-Gateway im Konto. Koppel zuerst das Gateway über „In der Nähe suchen“ oder „WLAN-Gerät“.")
                        else -> {
                            Muted("Bring den Zigbee-Sensor in den Kopplungsmodus und wähle dann das Gateway.")
                            gateways.forEach { gateway ->
                                GrowCard(onClick = { viewModel.pairZigbee(gateway) }) {
                                    Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                        IconBadge(Icons.Filled.Hub, tint = if (gateway.online) GrowPalette.Green else GrowPalette.TextMuted)
                                        Text(text = gateway.name, modifier = Modifier.weight(1f).padding(horizontal = 12.dp), style = MaterialTheme.typography.bodyLarge)
                                        StatusChip(if (gateway.online) "Online" else "Offline", if (gateway.online) GrowPalette.Green else GrowPalette.Error)
                                    }
                                }
                            }
                        }
                    }
                }

                is PairingStep.Pairing -> {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        CircularProgressIndicator()
                        Text(text = step.text, style = MaterialTheme.typography.titleMedium)
                        Muted("Das dauert bis zu zwei Minuten. Lass die App geöffnet.")
                    }
                }

                is PairingStep.Done -> {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        IconBadge(Icons.Filled.CheckCircle, size = 72)
                        Text(text = "Gekoppelt!", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                        Muted("${step.device.name} ist jetzt in deinem Konto. Ordne es in einer Box unter „Sensoren“ zu.")
                    }
                }

                is PairingStep.Failed -> {
                    Title("Das hat nicht geklappt")
                    Muted(step.message, color = MaterialTheme.colorScheme.onSurface)
                    Steps(
                        "Gerät zurücksetzen, bis die Anzeige blinkt.",
                        "Handy nah ans Gerät halten.",
                        "Nur 2,4-GHz-WLAN verwenden, Passwort prüfen.",
                    )
                }
            }
        }
        BottomAction(state, viewModel, onClose)
    }
}

@Composable
private fun BottomAction(state: PairingUiState, viewModel: PairingViewModel, onClose: () -> Unit) {
    val (label, action, enabled) = when (val step = state.step) {
        is PairingStep.WifiForm -> Triple(if (step.method == PairingMethod.NEARBY) "Suche starten" else "Weiter", viewModel::submitWifi, state.wifiReady)
        PairingStep.ApConnect -> Triple("Verbunden", viewModel::apConnected, true)
        is PairingStep.Scanning -> if (step.finished) Triple("Erneut suchen", viewModel::startScan, true) else return
        is PairingStep.Done -> Triple("Fertig", onClose, true)
        is PairingStep.Failed -> Triple("Noch einmal", viewModel::restart, true)
        else -> return
    }
    Button(onClick = action, enabled = enabled, modifier = Modifier.fillMaxWidth().padding(16.dp).height(52.dp)) { Text(label) }
}

@Composable
private fun Title(text: String) {
    Text(text = text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun Steps(vararg lines: String) {
    GrowCard {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            lines.forEachIndexed { index, line ->
                Row {
                    Text(text = "${index + 1}.", modifier = Modifier.padding(end = 8.dp), color = GrowPalette.Green, fontWeight = FontWeight.SemiBold)
                    Text(text = line, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun MethodRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    GrowCard(onClick = onClick) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBadge(icon, size = 42)
            Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(text = title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Muted(subtitle)
            }
        }
    }
}
