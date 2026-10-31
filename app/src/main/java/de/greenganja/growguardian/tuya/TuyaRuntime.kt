package de.greenganja.growguardian.tuya

import android.app.Application
import com.thingclips.smart.home.sdk.ThingHomeSdk

/**
 * Startet das Tuya-SDK nur, wenn der Build dafür eingerichtet ist (App-Key und security-algorithm.aar).
 * Ohne diese Teile würde ThingHomeSdk.init die App beim Start abstürzen lassen; so läuft der Rest der App weiter.
 */
object TuyaRuntime {
    @Volatile
    var ready: Boolean = false
        private set

    fun init(app: Application, configured: Boolean) {
        if (!configured) return
        ready = runCatching { ThingHomeSdk.init(app) }.isSuccess
    }

    fun requireReady() {
        if (!ready) throw TuyaUnavailableException()
    }
}

class TuyaUnavailableException :
    IllegalStateException("Tuya-Geräte sind in dieser App-Version nicht eingerichtet.")
