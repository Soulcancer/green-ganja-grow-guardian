package de.greenganja.growguardian.tuya

import com.google.firebase.auth.FirebaseAuth
import com.thingclips.smart.home.sdk.ThingHomeSdk
import com.thingclips.smart.home.sdk.api.IThingHome
import com.thingclips.smart.home.sdk.api.IThingHomeStatusListener
import com.thingclips.smart.sdk.api.IDevListener
import com.thingclips.smart.sdk.api.IThingDevice
import de.greenganja.growguardian.backend.BackendException
import de.greenganja.growguardian.grow.SensorDevice
import de.greenganja.growguardian.grow.SensorFeed
import de.greenganja.growguardian.grow.SensorSource
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Live-Werte aller Tuya-Geräte des Kontos.
 *
 * Lädt die Geräte aller Familien, hört per MQTT auf Datenpunkt-Updates und Online-Status und
 * lädt die Liste neu, wenn Geräte hinzukommen oder entfernt werden. Beim Beenden des Sammelns
 * werden alle Listener abgemeldet.
 */
class TuyaSensorSource(
    private val session: TuyaSession,
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
) : SensorSource {

    override fun sensors(): Flow<SensorFeed> = channelFlow {
        send(SensorFeed.Loading)
        val uid = auth.currentUser?.uid
        if (uid == null) {
            send(SensorFeed.Failed("Bitte melde dich an."))
            return@channelFlow
        }
        session.ensureLoggedIn(uid).onFailure {
            send(SensorFeed.Failed(loginMessage(it)))
            return@channelFlow
        }

        val devices = MutableStateFlow<Map<String, TuyaDevice>?>(null)
        val reload = Channel<Unit>(Channel.CONFLATED)
        val watched = mutableMapOf<String, IThingDevice>()
        val homes = mutableMapOf<Long, IThingHome>()

        val homeListener = object : IThingHomeStatusListener {
            override fun onDeviceAdded(devId: String?) { reload.trySend(Unit) }
            override fun onDeviceRemoved(devId: String?) { reload.trySend(Unit) }
            override fun onGroupAdded(groupId: Long) = Unit
            override fun onGroupRemoved(groupId: Long) = Unit
            override fun onMeshAdded(meshId: String?) = Unit
        }

        fun listenerFor(id: String) = object : IDevListener {
            override fun onDpUpdate(devId: String?, dpStr: String?) {
                val changed = parseDpUpdate(dpStr)
                if (changed.isNotEmpty()) devices.update { all -> all?.updated(id) { it.withUpdate(changed) } }
            }

            override fun onRemoved(devId: String?) { reload.trySend(Unit) }

            override fun onStatusChanged(devId: String?, online: Boolean) {
                devices.update { all -> all?.updated(id) { it.copy(online = online) } }
            }

            override fun onNetworkStatusChanged(devId: String?, status: Boolean) = Unit

            override fun onDevInfoUpdate(devId: String?) {
                val name = ThingHomeSdk.getDataInstance().getDeviceBean(id)?.name ?: return
                devices.update { all -> all?.updated(id) { it.copy(name = name) } }
            }
        }

        try {
            for (homeId in TuyaCalls.homeIds()) {
                homes[homeId] = ThingHomeSdk.newHomeInstance(homeId).also { it.registerHomeStatusListener(homeListener) }
            }
            launch {
                devices.collect { all -> if (all != null) send(SensorFeed.Ready(all.mapValues { it.value.toSensor() })) }
            }
            reload.trySend(Unit)
            for (signal in reload) {
                val beans = homes.keys.flatMap { homeId -> TuyaCalls.devices(homeId) }
                val current = beans.associate { it.devId to TuyaDevice.from(it) }
                // Listener nur für neue Geräte anmelden, entfernte abmelden
                (watched.keys - current.keys).forEach { id -> watched.remove(id)?.release() }
                (current.keys - watched.keys).forEach { id ->
                    watched[id] = ThingHomeSdk.newDeviceInstance(id).also { it.registerDevListener(listenerFor(id)) }
                }
                devices.value = current
            }
        } catch (e: TuyaException) {
            send(SensorFeed.Failed("Tuya ist gerade nicht erreichbar (${e.code ?: "?"})."))
        } finally {
            watched.values.forEach { it.release() }
            homes.values.forEach { it.onDestroy() }
        }
    }

    override suspend fun snapshot(): Map<String, SensorDevice> {
        val uid = auth.currentUser?.uid ?: return emptyMap()
        session.ensureLoggedIn(uid).getOrThrow()
        // getHomeDetail liefert die zuletzt an die Tuya-Cloud gemeldeten Werte
        return TuyaCalls.homeIds()
            .flatMap { TuyaCalls.devices(it) }
            .associate { it.devId to TuyaDevice.from(it).toSensor() }
    }

    private fun IThingDevice.release() {
        unRegisterDevListener()
        onDestroy()
    }

    private fun loginMessage(error: Throwable): String = when (error) {
        is BackendException -> error.message ?: "Anmeldung bei Tuya fehlgeschlagen."
        is TuyaException -> "Anmeldung bei Tuya fehlgeschlagen (${error.code ?: "?"})."
        else -> "Anmeldung bei Tuya fehlgeschlagen."
    }
}

private inline fun Map<String, TuyaDevice>.updated(id: String, change: (TuyaDevice) -> TuyaDevice): Map<String, TuyaDevice> {
    val device = this[id] ?: return this
    return this + (id to change(device))
}
