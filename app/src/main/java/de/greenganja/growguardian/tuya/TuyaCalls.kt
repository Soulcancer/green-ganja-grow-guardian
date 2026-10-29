package de.greenganja.growguardian.tuya

import com.thingclips.smart.android.device.bean.BoolSchemaBean
import com.thingclips.smart.android.device.bean.ValueSchemaBean
import com.thingclips.smart.android.device.enums.DataTypeEnum
import com.thingclips.smart.android.user.api.ILogoutCallback
import com.thingclips.smart.android.user.api.IUidLoginCallback
import com.thingclips.smart.android.user.bean.User
import com.thingclips.smart.home.sdk.ThingHomeSdk
import com.thingclips.smart.home.sdk.bean.HomeBean
import com.thingclips.smart.home.sdk.callback.IThingGetHomeListCallback
import com.thingclips.smart.home.sdk.callback.IThingHomeResultCallback
import com.thingclips.smart.home.sdk.utils.SchemaMapper
import com.thingclips.smart.sdk.bean.DeviceBean
import de.greenganja.growguardian.account.TuyaLogin
import de.greenganja.growguardian.grow.DpDefinition
import de.greenganja.growguardian.grow.DpMapping
import de.greenganja.growguardian.grow.SensorDevice
import de.greenganja.growguardian.grow.SwitchChannel
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Fehler aus dem Tuya-SDK mit dessen Code. */
class TuyaException(val code: String?, message: String?) : Exception(message ?: "Tuya-Fehler ${code.orEmpty()}")

/** Die Tuya-Aufrufe der App als suspend-Funktionen. */
internal object TuyaCalls {

    /** UID-Login; legt beim ersten Mal Konto und Standard-Familie an. */
    suspend fun loginWithUid(countryCode: String, login: TuyaLogin): Unit = suspendCancellableCoroutine { cont ->
        ThingHomeSdk.getUserInstance().loginOrRegisterWithUid(
            countryCode,
            login.uid,
            login.password,
            true,
            object : IUidLoginCallback {
                override fun onSuccess(user: User?, homeId: Long) {
                    if (cont.isActive) cont.resume(Unit)
                }

                override fun onError(code: String?, error: String?) {
                    if (cont.isActive) cont.resumeWithException(TuyaException(code, error))
                }
            },
        )
    }

    suspend fun logout(): Unit = suspendCancellableCoroutine { cont ->
        ThingHomeSdk.getUserInstance().logout(object : ILogoutCallback {
            override fun onSuccess() {
                if (cont.isActive) cont.resume(Unit)
            }

            override fun onError(code: String?, error: String?) {
                if (cont.isActive) cont.resumeWithException(TuyaException(code, error))
            }
        })
    }

    suspend fun homeIds(): List<Long> = suspendCancellableCoroutine { cont ->
        ThingHomeSdk.getHomeManagerInstance().queryHomeList(object : IThingGetHomeListCallback {
            override fun onSuccess(homeBeans: MutableList<HomeBean>?) {
                if (cont.isActive) cont.resume(homeBeans.orEmpty().map { it.homeId })
            }

            override fun onError(errorCode: String?, error: String?) {
                if (cont.isActive) cont.resumeWithException(TuyaException(errorCode, error))
            }
        })
    }

    /** Geräte einer Familie. Lädt nebenbei Schema und letzte Werte in den SDK-Cache. */
    suspend fun devices(homeId: Long): List<DeviceBean> = suspendCancellableCoroutine { cont ->
        ThingHomeSdk.newHomeInstance(homeId).getHomeDetail(object : IThingHomeResultCallback {
            override fun onSuccess(bean: HomeBean?) {
                if (cont.isActive) cont.resume(bean?.deviceList.orEmpty())
            }

            override fun onError(errorCode: String?, errorMsg: String?) {
                if (cont.isActive) cont.resumeWithException(TuyaException(errorCode, errorMsg))
            }
        })
    }

    /** Zahlen-Datenpunkte eines Geräts mit Skalierung und Einheit aus dem Schema. */
    fun definitions(devId: String): List<DpDefinition> {
        val schema = ThingHomeSdk.getDataInstance().getSchema(devId) ?: return emptyList()
        return schema.values.mapNotNull { bean ->
            val id = bean.id ?: return@mapNotNull null
            val code = bean.code ?: return@mapNotNull null
            if (bean.type != DataTypeEnum.OBJ.type || bean.getSchemaType() != ValueSchemaBean.type) return@mapNotNull null
            val value = SchemaMapper.toValueSchema(bean.property)
            DpDefinition(id = id, code = code, scale = value.scale, unit = value.unit.orEmpty())
        }
    }

    /** Schaltbare Bool-Datenpunkte (switch, switch_1 …), sortiert nach DP-Nummer. */
    fun switchDefinitions(devId: String): List<DpDefinition> {
        val schema = ThingHomeSdk.getDataInstance().getSchema(devId) ?: return emptyList()
        return schema.values.mapNotNull { bean ->
            val id = bean.id ?: return@mapNotNull null
            val code = bean.code ?: return@mapNotNull null
            if (bean.getSchemaType() != BoolSchemaBean.type || !DpMapping.isSwitchCode(code)) return@mapNotNull null
            if (bean.mode?.contains("w") == false) return@mapNotNull null // nur meldend, nicht schaltbar
            DpDefinition(id = id, code = code)
        }.sortedBy { it.id.toIntOrNull() ?: Int.MAX_VALUE }
    }
}

/** Was die App von einem Tuya-Gerät braucht; [raw] sind die Rohwerte je DP-Nummer. */
internal data class TuyaDevice(
    val id: String,
    val name: String,
    val online: Boolean,
    val category: String?,
    val productId: String?,
    val definitions: List<DpDefinition>,
    val raw: Map<String, Any?>,
    val switchDefinitions: List<DpDefinition> = emptyList(),
) {
    fun toSensor(): SensorDevice = SensorDevice(
        id = id,
        name = name,
        online = online,
        kind = DpMapping.kindOf(category, definitions.map { it.code }),
        values = DpMapping.read(definitions, raw, category, productId),
        sources = DpMapping.sources(definitions, category, productId),
        switches = switchDefinitions.map { def ->
            SwitchChannel(def.id, def.code, DpMapping.switchLabel(def.code, switchDefinitions.size), raw[def.id] as? Boolean)
        },
    )

    /** Live-Update übernehmen: Tuya schickt nur die geänderten DPs, z. B. {"1":236}. */
    fun withUpdate(changed: Map<String, Any?>): TuyaDevice = copy(raw = raw + changed)

    companion object {
        fun from(bean: DeviceBean): TuyaDevice = TuyaDevice(
            id = bean.devId,
            name = bean.name ?: "Gerät",
            online = bean.isOnline == true,
            category = bean.productBean?.category,
            productId = bean.productId,
            definitions = TuyaCalls.definitions(bean.devId),
            raw = bean.dps.orEmpty(),
            switchDefinitions = TuyaCalls.switchDefinitions(bean.devId),
        )
    }
}

/** dpStr aus IDevListener.onDpUpdate, z. B. {"1":236,"2":58}. */
internal fun parseDpUpdate(dpStr: String?): Map<String, Any?> {
    if (dpStr.isNullOrBlank()) return emptyMap()
    return runCatching {
        val json = JSONObject(dpStr)
        json.keys().asSequence().associateWith { key -> json.opt(key) }
    }.getOrDefault(emptyMap())
}
