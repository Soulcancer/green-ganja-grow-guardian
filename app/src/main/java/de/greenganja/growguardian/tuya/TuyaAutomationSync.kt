package de.greenganja.growguardian.tuya

import com.google.firebase.auth.FirebaseAuth
import de.greenganja.growguardian.grow.automation.Automation
import de.greenganja.growguardian.grow.automation.AutomationEngine
import de.greenganja.growguardian.grow.automation.AutomationRules
import de.greenganja.growguardian.grow.automation.PlannedCondition
import de.greenganja.growguardian.grow.automation.PlannedScene

import com.thingclips.smart.home.sdk.ThingHomeSdk
import com.thingclips.smart.home.sdk.bean.scene.SceneBean
import com.thingclips.smart.home.sdk.bean.scene.SceneCondition
import com.thingclips.smart.home.sdk.bean.scene.SceneTask
import com.thingclips.smart.home.sdk.bean.scene.condition.rule.TimerRule
import com.thingclips.smart.home.sdk.bean.scene.condition.rule.ValueRule
import com.thingclips.smart.home.sdk.callback.IThingResultCallback
import com.thingclips.smart.sdk.api.IResultCallback
import kotlinx.coroutines.suspendCancellableCoroutine
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Fehler der Tuya-Szenen-Schnittstelle. */
class SceneException(val code: String?, message: String?) : Exception(message ?: "Tuya-Automation fehlgeschlagen (${code.orEmpty()})")

/**
 * Legt Regeln als Automationen in der Tuya-Cloud an. Die Cloud wertet sie aus, sobald ein Sensor meldet
 * oder die Uhrzeit erreicht ist, auch wenn das Handy aus ist.
 *
 * Nutzt die Szenen-Schnittstelle des Smart Life App SDK (`getSceneManagerInstance`). Tuya markiert sie als
 * veraltet zugunsten des Szenen-Baukastens im expansion-sdk, sie ist in thingsmart 7.x aber weiter enthalten
 * und deutlich einfacher, weil sie Bedingungen direkt aus Datenpunkt und Rohwert baut.
 *
 * Jede Regel wird zu zwei Automationen: einschalten und ausschalten. Ihre Namen beginnen mit [NAME_PREFIX],
 * damit [cleanup] verwaiste Automationen findet.
 */
class TuyaAutomationSync(
    private val session: TuyaSession,
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
) : AutomationEngine {

    private suspend fun homeId(): Long {
        val uid = auth.currentUser?.uid ?: throw SceneException("login", "Nicht angemeldet")
        session.ensureLoggedIn(uid).getOrThrow()
        return TuyaCalls.homeIds().firstOrNull() ?: throw SceneException("home", "Keine Tuya-Familie")
    }

    /** Löscht die bisherigen Automationen der Regel und legt sie neu an. Liefert die neuen IDs. */
    override suspend fun apply(automation: Automation, active: Boolean): List<String> {
        remove(automation.sceneIds)
        val home = homeId()
        val created = mutableListOf<String>()
        try {
            for (planned in AutomationRules.plan(automation)) {
                val scene = createScene(home, automation, planned)
                created += scene.id
                if (!active) setSceneEnabled(scene.id, false)
            }
        } catch (e: Exception) {
            remove(created) // halbe Regeln nicht stehen lassen
            throw e
        }
        return created
    }

    override suspend fun setEnabled(sceneIds: List<String>, enabled: Boolean) {
        for (id in sceneIds) setSceneEnabled(id, enabled)
    }

    /** Fehlende Automationen gelten als gelöscht. */
    override suspend fun remove(sceneIds: List<String>) {
        for (id in sceneIds) {
            try {
                deleteScene(id)
            } catch (e: SceneException) {
                if (!isNotFound(e)) throw e
            }
        }
    }

    /** Entfernt Automationen dieser App, die zu keiner Regel mehr gehören (z. B. nach einem Absturz beim Löschen). */
    override suspend fun cleanup(knownSceneIds: Set<String>): Int {
        val orphans = sceneList(homeId()).filter { it.name?.startsWith(NAME_PREFIX) == true && it.id !in knownSceneIds }
        remove(orphans.map { it.id })
        return orphans.size
    }

    private suspend fun createScene(home: Long, automation: Automation, planned: PlannedScene): SceneBean {
        val condition = when (val c = planned.condition) {
            is PlannedCondition.Value -> {
                val device = ThingHomeSdk.getDataInstance().getDeviceBean(c.deviceId)
                    ?: throw SceneException("device", "Sensor nicht im Konto")
                // Schlüssel "dp" + DP-Nummer, wie bei BoolRule in Tuyas Doku
                SceneCondition.createDevCondition(device, c.dpId, ValueRule.newInstance("dp${c.dpId}", c.operator, c.raw))
            }
            is PlannedCondition.Time -> SceneCondition.createTimerCondition(
                "${c.time} ${AutomationRules.daysLabel(c.days)}",
                "Zeitplan",
                "timer",
                TimerRule.newInstance(TimeZone.getDefault().id, c.days, c.time, SimpleDateFormat("yyyyMMdd", Locale.ROOT).format(Date())),
            )
        }
        val task = SceneTask.createDpTask(automation.actuatorDeviceId, hashMapOf<String, Any>(automation.actuatorDpId to planned.turnOn))
        val name = "$NAME_PREFIX ${automation.name.take(30)} · ${if (planned.turnOn) "an" else "aus"}"
        return suspendCancellableCoroutine { cont ->
            ThingHomeSdk.getSceneManagerInstance().createScene(
                home, name, false, null, listOf(condition), listOf(task), SceneBean.MATCH_TYPE_OR,
                object : IThingResultCallback<SceneBean> {
                    override fun onSuccess(result: SceneBean?) {
                        if (!cont.isActive) return
                        if (result?.id == null) cont.resumeWithException(SceneException("empty", "Keine Szenen-ID")) else cont.resume(result)
                    }

                    override fun onError(errorCode: String?, errorMessage: String?) {
                        if (cont.isActive) cont.resumeWithException(SceneException(errorCode, errorMessage))
                    }
                },
            )
        }
    }

    private suspend fun setSceneEnabled(sceneId: String, enabled: Boolean) = resultCall { cb ->
        val scene = ThingHomeSdk.newSceneInstance(sceneId)
        if (enabled) scene.enableScene(sceneId, cb) else scene.disableScene(sceneId, cb)
    }

    private suspend fun deleteScene(sceneId: String) = resultCall { cb -> ThingHomeSdk.newSceneInstance(sceneId).deleteScene(cb) }

    private suspend fun sceneList(home: Long): List<SceneBean> = suspendCancellableCoroutine { cont ->
        ThingHomeSdk.getSceneManagerInstance().getSceneList(home, object : IThingResultCallback<List<SceneBean>> {
            override fun onSuccess(result: List<SceneBean>?) {
                if (cont.isActive) cont.resume(result.orEmpty())
            }

            override fun onError(errorCode: String?, errorMessage: String?) {
                if (cont.isActive) cont.resumeWithException(SceneException(errorCode, errorMessage))
            }
        })
    }

    private suspend fun resultCall(block: (IResultCallback) -> Unit): Unit = suspendCancellableCoroutine { cont ->
        block(object : IResultCallback {
            override fun onSuccess() {
                if (cont.isActive) cont.resume(Unit)
            }

            override fun onError(code: String?, error: String?) {
                if (cont.isActive) cont.resumeWithException(SceneException(code, error))
            }
        })
    }

    private fun isNotFound(e: SceneException): Boolean {
        val text = "${e.code} ${e.message}".lowercase()
        return "not exist" in text || "not found" in text || "does not exist" in text
    }

    companion object {
        const val NAME_PREFIX = "[GG]"
    }
}
