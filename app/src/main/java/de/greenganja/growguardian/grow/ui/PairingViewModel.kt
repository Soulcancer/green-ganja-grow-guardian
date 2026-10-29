package de.greenganja.growguardian.grow.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.greenganja.growguardian.tuya.NearbyDevice
import de.greenganja.growguardian.tuya.PairedDevice
import de.greenganja.growguardian.tuya.TuyaException
import de.greenganja.growguardian.tuya.TuyaPairing
import de.greenganja.growguardian.tuya.WifiPairingMode
import de.greenganja.growguardian.tuya.ZigbeeGateway
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class PairingMethod { NEARBY, WIFI, ZIGBEE }

/** Die Schritte des Kopplungs-Assistenten. */
sealed interface PairingStep {
    data object Choose : PairingStep

    /** WLAN: Blinkt das Gerät schnell (EZ) oder langsam (AP)? */
    data object WifiBlink : PairingStep

    /** WLAN-Name und Passwort, für [PairingMethod.NEARBY] oder [PairingMethod.WIFI]. */
    data class WifiForm(val method: PairingMethod, val mode: WifiPairingMode? = null) : PairingStep

    /** AP-Modus: Handy mit dem Hotspot des Geräts verbinden. */
    data object ApConnect : PairingStep

    data class Scanning(val found: List<NearbyDevice> = emptyList(), val finished: Boolean = false) : PairingStep

    data class Gateways(val gateways: List<ZigbeeGateway>? = null) : PairingStep

    data class Pairing(val text: String) : PairingStep

    data class Done(val device: PairedDevice) : PairingStep

    data class Failed(val message: String) : PairingStep
}

data class PairingUiState(
    val step: PairingStep = PairingStep.Choose,
    val ssid: String = "",
    val password: String = "",
) {
    /** Tuya koppelt nur in 2,4-GHz-Netzen; ein leeres Passwort ist bei offenen Netzen erlaubt. */
    val wifiReady: Boolean get() = ssid.isNotBlank()
}

class PairingViewModel(private val pairing: TuyaPairing) : ViewModel() {

    private val _state = MutableStateFlow(PairingUiState())
    val state: StateFlow<PairingUiState> = _state.asStateFlow()
    private var job: Job? = null

    val isBluetoothOn: Boolean get() = pairing.isBluetoothOn

    private fun go(step: PairingStep) = _state.update { it.copy(step = step) }

    fun setSsid(value: String) = _state.update { it.copy(ssid = value.take(32)) }

    fun setPassword(value: String) = _state.update { it.copy(password = value.take(64)) }

    /** Nach erteilten Berechtigungen. */
    fun choose(method: PairingMethod) = when (method) {
        PairingMethod.NEARBY -> go(PairingStep.WifiForm(PairingMethod.NEARBY))
        PairingMethod.WIFI -> go(PairingStep.WifiBlink)
        PairingMethod.ZIGBEE -> loadGateways()
    }

    fun chooseBlink(mode: WifiPairingMode) = go(PairingStep.WifiForm(PairingMethod.WIFI, mode))

    /** Weiter im WLAN-Formular. */
    fun submitWifi() {
        val step = _state.value.step as? PairingStep.WifiForm ?: return
        when {
            step.method == PairingMethod.NEARBY -> startScan()
            step.mode == WifiPairingMode.AP -> go(PairingStep.ApConnect)
            else -> pairWifi(WifiPairingMode.EZ)
        }
    }

    /** AP-Modus: Nutzer ist mit dem Geräte-Hotspot verbunden. */
    fun apConnected() = pairWifi(WifiPairingMode.AP)

    fun startScan() = launchStep(PairingStep.Scanning()) {
        pairing.scanNearby().collect { device ->
            _state.update { s ->
                val scanning = s.step as? PairingStep.Scanning ?: return@update s
                if (scanning.found.any { it.id == device.id }) s else s.copy(step = scanning.copy(found = scanning.found + device))
            }
        }
        _state.update { s -> (s.step as? PairingStep.Scanning)?.let { s.copy(step = it.copy(finished = true)) } ?: s }
    }

    fun pairNearby(device: NearbyDevice) {
        val s = _state.value
        launchStep(PairingStep.Pairing("Gerät wird gekoppelt …")) {
            go(PairingStep.Done(pairing.pairNearby(device, s.ssid.trim(), s.password)))
        }
    }

    private fun pairWifi(mode: WifiPairingMode) {
        val s = _state.value
        launchStep(PairingStep.Pairing(if (mode == WifiPairingMode.AP) "Verbinde über den Geräte-Hotspot …" else "Gerät sucht dein WLAN …")) {
            go(PairingStep.Done(pairing.pairWifi(mode, s.ssid.trim(), s.password)))
        }
    }

    private fun loadGateways() = launchStep(PairingStep.Gateways()) {
        go(PairingStep.Gateways(pairing.gateways()))
    }

    fun pairZigbee(gateway: ZigbeeGateway) =
        launchStep(PairingStep.Pairing("${gateway.name} sucht nach Zigbee-Sensoren …")) {
            go(PairingStep.Done(pairing.pairZigbee(gateway.id)))
        }

    /** Zurück; laufende Suche oder Kopplung wird abgebrochen. Liefert false, wenn der Assistent zu Ende ist. */
    fun back(): Boolean {
        job?.cancel()
        val previous = when (val step = _state.value.step) {
            PairingStep.Choose -> return false
            PairingStep.WifiBlink, is PairingStep.Gateways, is PairingStep.Done, is PairingStep.Failed -> PairingStep.Choose
            is PairingStep.WifiForm -> if (step.method == PairingMethod.WIFI) PairingStep.WifiBlink else PairingStep.Choose
            PairingStep.ApConnect, is PairingStep.Scanning, is PairingStep.Pairing -> PairingStep.Choose
        }
        go(previous)
        return true
    }

    fun restart() {
        job?.cancel()
        go(PairingStep.Choose)
    }

    private fun launchStep(start: PairingStep, block: suspend () -> Unit) {
        job?.cancel()
        go(start)
        job = viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                go(PairingStep.Failed(pairingMessage(e)))
            }
        }
    }
}

internal fun pairingMessage(error: Throwable): String {
    val detail = (error as? TuyaException)?.message?.takeIf { it.isNotBlank() }
    return when {
        error is TuyaException && error.code == "limited" -> error.message.orEmpty()
        detail != null -> "Kopplung fehlgeschlagen: $detail"
        else -> "Kopplung fehlgeschlagen. Prüfe WLAN-Passwort, 2,4-GHz-Netz und ob das Gerät im Kopplungsmodus ist."
    }
}
