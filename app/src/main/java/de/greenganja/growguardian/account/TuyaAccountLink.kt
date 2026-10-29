package de.greenganja.growguardian.account

import de.greenganja.growguardian.backend.Backend

/** Zugangsdaten für Tuyas UID-Login, abgeleitet vom Firebase-Konto. */
data class TuyaLogin(val uid: String, val password: String)

/**
 * Koppelt das Tuya-Konto an den Firebase-Login. Nutzer melden sich nur einmal an (Firebase),
 * die App loggt sie danach mit diesen Daten bei Tuya ein:
 *
 * ```
 * ThingHomeSdk.getUserInstance().loginOrRegisterWithUid(countryCode, login.uid, login.password, callback)
 * ```
 */
class TuyaAccountLink(private val backend: Backend = Backend()) {

    suspend fun fetchLogin(): Result<TuyaLogin> =
        backend.call("getTuyaLogin").mapCatching {
            TuyaLogin(
                uid = it["uid"] as? String ?: error("uid fehlt"),
                password = it["password"] as? String ?: error("password fehlt"),
            )
        }
}
