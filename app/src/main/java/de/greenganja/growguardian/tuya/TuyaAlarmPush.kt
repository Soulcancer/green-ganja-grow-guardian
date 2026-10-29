package de.greenganja.growguardian.tuya

import com.google.firebase.auth.FirebaseAuth
import com.thingclips.smart.home.sdk.ThingHomeSdk
import com.thingclips.smart.home.sdk.bean.scene.SceneBean
import com.thingclips.smart.home.sdk.bean.scene.SceneCondition
import com.thingclips.smart.home.sdk.bean.scene.SceneTask
import com.thingclips.smart.home.sdk.bean.scene.condition.rule.ValueRule
import com.thingclips.smart.home.sdk.callback.IThingResultCallback
import com.thingclips.smart.sdk.api.IResultCallback
import de.greenganja.growguardian.grow.alarm.AlarmPushEngine
import de.greenganja.growguardian.grow.alarm.AlarmScenePlan
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Baut die Aktion „Push-Benachrichtigung senden“ einer Tuya-Szene.
 *
 * OFFEN: Die öffentliche Doku des Smart App SDK nennt die Fabrikmethode dafür nicht (Stand Oktober 2026),
 * deshalb ist hier keine erfundene API eingebaut. Im echten Projekt in der SDK-Referenz oder beim Tuya-Support
 * nachsehen (Stichworte: SceneTask, „message“/„push“-Aktion, Message Center) und eine eigene Implementierung
 * an [TuyaAlarmPush] übergeben. Bis dahin wirft [Unconfigured] einen Fehler; die App fällt dann auf den
 * Handy-Alarm zurück und nichts geht verloren.
 */
fun interface PushTaskFactory {
    fun create(message: String): SceneTask

    object Unconfigured : PushTaskFactory {
        override fun create(message: String): SceneTask =
            throw SceneException("push-task", "Tuya-Push-Aktion ist noch nicht eingerichtet (PushTaskFactory)")
    }
}

/**
 * Alarm-Szenen in der Tuya-Cloud: Bedingung „Wert < Minimum“ ODER „Wert > Maximum“ am Sensor,
 * Aktion Push-Benachrichtigung. Für den Push muss in der Tuya IoT Platform der Push-Dienst
 * (Firebase Cloud Messaging) für eure App eingerichtet sein.
 */
class TuyaAlarmPush(
    private val session: TuyaSession,
    private val taskFactory: PushTaskFactory = PushTaskFactory.Unconfigured,
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
) : AlarmPushEngine {

    override suspend fun create(plan: AlarmScenePlan): String {
        val task = taskFactory.create(plan.message) // zuerst, damit ohne Push-Aktion nichts angelegt wird
        val uid = auth.currentUser?.uid ?: throw SceneException("login", "Nicht angemeldet")
        session.ensureLoggedIn(uid).getOrThrow()
        val home = TuyaCalls.homeIds().firstOrNull() ?: throw SceneException("home", "Keine Tuya-Familie")
        val device = ThingHomeSdk.getDataInstance().getDeviceBean(plan.sensorDeviceId)
            ?: throw SceneException("device", "Sensor nicht im Konto")
        // Schlüssel "dp" + DP-Nummer wie bei den Automationen (siehe TuyaAutomationSync, dort ebenfalls zu prüfen)
        val low = SceneCondition.createDevCondition(device, plan.dpId, ValueRule.newInstance("dp${plan.dpId}", "<", plan.minRaw))
        val high = SceneCondition.createDevCondition(device, plan.dpId, ValueRule.newInstance("dp${plan.dpId}", ">", plan.maxRaw))
        val name = "${TuyaAutomationSync.NAME_PREFIX} Alarm ${plan.key}".take(60)
        return suspendCancellableCoroutine { cont ->
            ThingHomeSdk.getSceneManagerInstance().createScene(
                home, name, false, null, listOf(low, high), listOf(task), SceneBean.MATCH_TYPE_OR,
                object : IThingResultCallback<SceneBean> {
                    override fun onSuccess(result: SceneBean?) {
                        if (!cont.isActive) return
                        val id = result?.id
                        if (id == null) cont.resumeWithException(SceneException("empty", "Keine Szenen-ID")) else cont.resume(id)
                    }

                    override fun onError(errorCode: String?, errorMessage: String?) {
                        if (cont.isActive) cont.resumeWithException(SceneException(errorCode, errorMessage))
                    }
                },
            )
        }
    }

    override suspend fun remove(sceneId: String) = suspendCancellableCoroutine { cont ->
        ThingHomeSdk.newSceneInstance(sceneId).deleteScene(object : IResultCallback {
            override fun onSuccess() {
                if (cont.isActive) cont.resume(Unit)
            }

            override fun onError(code: String?, error: String?) {
                if (!cont.isActive) return
                val text = "$code $error".lowercase()
                if ("not exist" in text || "not found" in text) cont.resume(Unit) else cont.resumeWithException(SceneException(code, error))
            }
        })
    }
}
