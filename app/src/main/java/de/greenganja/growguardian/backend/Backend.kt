package de.greenganja.growguardian.backend

import com.google.firebase.FirebaseException
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import kotlinx.coroutines.tasks.await

object BackendConfig {
    /** Muss zur REGION der Cloud Functions passen. */
    const val REGION = "europe-west3"
}

/**
 * Fehler aus den Cloud Functions.
 *
 * [message] ist eine deutsche Meldung, die die UI direkt anzeigen kann.
 * [reason] ist maschinenlesbar, z. B. "club-full", "monthly-limit", "network".
 */
class BackendException(
    val reason: String,
    message: String,
    /** Bei Mengengrenzen: wie viel Gramm noch möglich wären. */
    val remainingG: Double? = null,
    cause: Throwable? = null,
) : Exception(message, cause)

/** Ruft Cloud Functions auf und übersetzt Fehler in [BackendException]. */
class Backend(
    private val functions: FirebaseFunctions = FirebaseFunctions.getInstance(BackendConfig.REGION),
) {
    suspend fun call(name: String, data: Map<String, Any?> = emptyMap()): Result<Map<String, Any?>> =
        try {
            val result = functions.getHttpsCallable(name).call(data).await()
            @Suppress("UNCHECKED_CAST")
            Result.success(result.data as? Map<String, Any?> ?: emptyMap())
        } catch (e: FirebaseFunctionsException) {
            Result.failure(e.toBackendException())
        } catch (e: FirebaseException) {
            Result.failure(BackendException("network", "Keine Verbindung zum Server.", cause = e))
        }
}

private fun FirebaseFunctionsException.toBackendException(): BackendException {
    val details = details as? Map<*, *>
    val serverReason = details?.get("reason") as? String
    val reason = serverReason ?: when (code) {
        FirebaseFunctionsException.Code.UNAVAILABLE,
        FirebaseFunctionsException.Code.DEADLINE_EXCEEDED,
        -> "network"
        FirebaseFunctionsException.Code.UNAUTHENTICATED -> "not-signed-in"
        else -> "unknown"
    }
    val text = when {
        serverReason != null && !message.isNullOrBlank() -> message.orEmpty()
        reason == "network" -> "Keine Verbindung zum Server."
        else -> "Etwas ist schiefgelaufen. Bitte versuche es noch einmal."
    }
    val remaining = (details?.get("remainingG") as? Number)?.toDouble()
    return BackendException(reason, text, remaining, this)
}
