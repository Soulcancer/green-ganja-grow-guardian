package de.greenganja.growguardian.tuya

import android.content.Context
import com.thingclips.smart.home.sdk.ThingHomeSdk
import de.greenganja.growguardian.account.TuyaAccountLink
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Hält das Tuya-Konto passend zum Firebase-Login.
 *
 * Tuya merkt sich seine Anmeldung selbst über App-Neustarts hinweg. Wechselt der Firebase-Nutzer
 * auf dem Gerät, meldet [ensureLoggedIn] das alte Tuya-Konto vorher ab, damit niemand fremde Geräte sieht.
 *
 * @param countryCode Ländervorwahl, bestimmt das Tuya-Rechenzentrum ("49" = Deutschland, EU)
 */
class TuyaSession(
    context: Context,
    private val accountLink: TuyaAccountLink = TuyaAccountLink(),
    private val countryCode: String = "49",
) {
    private val prefs = context.applicationContext.getSharedPreferences("tuya_session", Context.MODE_PRIVATE)
    private val mutex = Mutex()

    suspend fun ensureLoggedIn(firebaseUid: String): Result<Unit> = mutex.withLock {
        runCatching {
            TuyaRuntime.requireReady()
            val user = ThingHomeSdk.getUserInstance()
            val linkedUid = prefs.getString(KEY_FIREBASE_UID, null)
            if (user.isLogin && linkedUid == firebaseUid) return@runCatching
            if (user.isLogin) TuyaCalls.logout()
            val login = accountLink.fetchLogin().getOrThrow()
            TuyaCalls.loginWithUid(countryCode, login)
            prefs.edit().putString(KEY_FIREBASE_UID, firebaseUid).apply()
        }
    }

    /** Beim Abmelden aus der App aufrufen. */
    suspend fun logOut() = mutex.withLock {
        if (TuyaRuntime.ready && ThingHomeSdk.getUserInstance().isLogin) runCatching { TuyaCalls.logout() }
        prefs.edit().remove(KEY_FIREBASE_UID).apply()
    }

    private companion object {
        const val KEY_FIREBASE_UID = "firebaseUid"
    }
}
