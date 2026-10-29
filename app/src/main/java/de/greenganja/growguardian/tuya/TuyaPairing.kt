package de.greenganja.growguardian.tuya

import com.google.firebase.auth.FirebaseAuth
import com.thingclips.smart.activator.core.kit.ThingActivatorCoreKit
import com.thingclips.smart.activator.core.kit.ThingActivatorDeviceCoreKit
import com.thingclips.smart.activator.core.kit.bean.ThingActivatorScanDeviceBean
import com.thingclips.smart.activator.core.kit.bean.ThingActivatorScanFailureBean
import com.thingclips.smart.activator.core.kit.bean.ThingDeviceActiveErrorBean
import com.thingclips.smart.activator.core.kit.bean.ThingDeviceActiveLimitBean
import com.thingclips.smart.activator.core.kit.builder.ThingDeviceActiveBuilder
import com.thingclips.smart.activator.core.kit.callback.ThingActivatorScanCallback
import com.thingclips.smart.activator.core.kit.constant.ThingDeviceActiveModeEnum
import com.thingclips.smart.activator.core.kit.listener.IThingDeviceActiveListener
import com.thingclips.smart.android.ble.api.ScanType
import com.thingclips.smart.home.sdk.ThingHomeSdk
import com.thingclips.smart.sdk.api.IThingActivatorGetToken
import com.thingclips.smart.sdk.bean.DeviceBean
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Ein per Bluetooth gefundenes, noch nicht gekoppeltes Gerät. */
class NearbyDevice internal constructor(internal val bean: ThingActivatorScanDeviceBean) {
    val id: String get() = bean.uniqueId

    /** Kombi-Geräte (Bluetooth + WLAN) brauchen die WLAN-Daten, reine Bluetooth-Geräte nicht. */
    val needsWifi: Boolean
        get() = bean.supprotActivatorTypeList.firstOrNull().let {
            it == ThingDeviceActiveModeEnum.BLE_WIFI || it == ThingDeviceActiveModeEnum.MULT_MODE
        }
}

/** Ein gekoppeltes Zigbee-Gateway, über das Zigbee-Sensoren dazukommen. */
data class ZigbeeGateway(val id: String, val name: String, val online: Boolean)

/** Ergebnis einer Kopplung. */
data class PairedDevice(val id: String, val name: String)

enum class WifiPairingMode {
    /** Gerät blinkt schnell (etwa zweimal pro Sekunde). */
    EZ,

    /** Gerät blinkt langsam; das Handy verbindet sich vorher mit dessen Hotspot "SmartLife-…". */
    AP,
}

/**
 * Geräte koppeln über Tuyas Activator-Kit (`thingsmart-expansion-sdk`): Bluetooth-Suche für reine
 * Bluetooth- und Kombi-Geräte, WLAN im EZ- oder AP-Modus und Zigbee-Sensoren über ein Gateway.
 * Neue Geräte landen in der ersten Tuya-Familie des Kontos; [TuyaSensorSource] lädt sie danach automatisch.
 *
 * Alle Funktionen sind abbrechbar: Wird die Coroutine beendet, stoppt die Suche bzw. Kopplung.
 */
class TuyaPairing(
    private val session: TuyaSession,
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
) {
    val isBluetoothOn: Boolean get() = ThingHomeSdk.getBleOperator().isBluetoothOpened

    /** Sucht 60 Sekunden lang nach Bluetooth-Geräten im Kopplungsmodus. */
    fun scanNearby(): Flow<NearbyDevice> = callbackFlow {
        ensureSession()
        val key = ThingActivatorCoreKit.getScanDeviceManager().startBlueToothDeviceSearch(
            60 * 1000, // Literal wie im Tuya-Beispiel, passt zu Int und Long
            arrayListOf(ScanType.SINGLE),
            object : ThingActivatorScanCallback {
                override fun deviceFound(deviceBean: ThingActivatorScanDeviceBean) {
                    trySend(NearbyDevice(deviceBean))
                }

                override fun deviceRepeat(deviceBean: ThingActivatorScanDeviceBean) = Unit
                override fun deviceUpdate(deviceBean: ThingActivatorScanDeviceBean) = Unit

                override fun scanFailure(failureBean: ThingActivatorScanFailureBean) {
                    close(TuyaException(null, failureBean.errorMsg))
                }

                override fun scanFinish() {
                    close()
                }
            },
        )
        awaitClose { ThingActivatorCoreKit.getScanDeviceManager().stopScan(key) }
    }

    /** Koppelt ein gefundenes Bluetooth- oder Kombi-Gerät. */
    suspend fun pairNearby(device: NearbyDevice, ssid: String, password: String): PairedDevice {
        val homeId = homeId()
        val mode = device.bean.supprotActivatorTypeList.firstOrNull() ?: throw TuyaException(null, "Unbekannter Gerätetyp")
        return activate {
            activeModel = mode
            setActivatorScanDeviceBean(device.bean)
            relationId = homeId
            if (device.needsWifi) {
                this.ssid = ssid
                this.password = password
            }
            timeOut = 60
        }
    }

    /** Koppelt ein WLAN-Gerät. Nur 2,4-GHz-Netze werden unterstützt. */
    suspend fun pairWifi(mode: WifiPairingMode, ssid: String, password: String): PairedDevice {
        val token = token(homeId())
        return activate {
            activeModel = if (mode == WifiPairingMode.EZ) ThingDeviceActiveModeEnum.EZ else ThingDeviceActiveModeEnum.AP
            this.ssid = ssid
            this.password = password
            this.token = token
            timeOut = if (mode == WifiPairingMode.EZ) 100 else 120
        }
    }

    /** Lässt das Gateway nach neuen Zigbee-Sensoren suchen; der Sensor muss dazu im Kopplungsmodus sein. */
    suspend fun pairZigbee(gatewayId: String): PairedDevice {
        ensureSession()
        return activate {
            gwId = gatewayId
            activeModel = ThingDeviceActiveModeEnum.SUB
            timeOut = 100
        }
    }

    /** Zigbee-Gateways im Konto. */
    suspend fun gateways(): List<ZigbeeGateway> {
        ensureSession()
        return TuyaCalls.homeIds().flatMap { TuyaCalls.devices(it) }
            .filter { it.isZigBeeWifi }
            .map { ZigbeeGateway(it.devId, it.name ?: "Gateway", it.isOnline == true) }
    }

    private suspend fun ensureSession() {
        val uid = auth.currentUser?.uid ?: throw TuyaException(null, "Nicht angemeldet")
        session.ensureLoggedIn(uid).getOrThrow()
    }

    private suspend fun homeId(): Long {
        ensureSession()
        return TuyaCalls.homeIds().firstOrNull() ?: throw TuyaException(null, "Keine Tuya-Familie gefunden")
    }

    private suspend fun token(homeId: Long): String = suspendCancellableCoroutine { cont ->
        ThingActivatorDeviceCoreKit.getActivatorInstance().getActivatorToken(homeId, object : IThingActivatorGetToken {
            override fun onSuccess(token: String?) {
                if (!cont.isActive) return
                if (token.isNullOrEmpty()) cont.resumeWithException(TuyaException(null, "Kein Kopplungs-Token")) else cont.resume(token)
            }

            override fun onFailure(errorCode: String?, errorMsg: String?) {
                if (cont.isActive) cont.resumeWithException(TuyaException(errorCode, errorMsg))
            }
        })
    }

    private suspend fun activate(configure: ThingDeviceActiveBuilder.() -> Unit): PairedDevice =
        suspendCancellableCoroutine { cont ->
            val manager = ThingActivatorCoreKit.getActiveManager().newThingActiveManager()
            val builder = ThingDeviceActiveBuilder().apply(configure)
            builder.listener = object : IThingDeviceActiveListener {
                override fun onActiveSuccess(deviceBean: DeviceBean) {
                    if (cont.isActive) cont.resume(PairedDevice(deviceBean.devId, deviceBean.name ?: "Neues Gerät"))
                }

                override fun onActiveError(errorBean: ThingDeviceActiveErrorBean) {
                    if (cont.isActive) cont.resumeWithException(TuyaException(null, errorBean.errMsg))
                }

                override fun onActiveLimited(limitBean: ThingDeviceActiveLimitBean) {
                    if (cont.isActive) cont.resumeWithException(TuyaException("limited", "Dieses Gerät ist schon mit einem anderen Konto gekoppelt."))
                }

                override fun onBind(devId: String) = Unit
                override fun onFind(devId: String) = Unit
            }
            manager.startActive(builder)
            cont.invokeOnCancellation { manager.stopActive() }
        }
}
