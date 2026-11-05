package de.greenganja.growguardian.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import de.greenganja.growguardian.ui.theme.GrowPalette
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/** Regeln der E-Mail-Bestätigung, ohne Android. */
object VerifyRules {
    /** So lange zwischen zwei Bestätigungs-Mails (Firebase drosselt sonst mit TOO_MANY_REQUESTS). */
    const val RESEND_COOLDOWN_MS = 60_000L

    /** Wie oft der Bildschirm von selbst nachsieht, ob der Link geklickt wurde. */
    const val POLL_MS = 5_000L

    fun secondsLeft(lastSentMillis: Long, now: Long): Int =
        ((lastSentMillis + RESEND_COOLDOWN_MS - now).coerceAtLeast(0) / 1000).toInt()

    fun sendError(code: String?): String = when (code) {
        "ERROR_TOO_MANY_REQUESTS" -> "Zu viele Mails in kurzer Zeit. Bitte warte ein paar Minuten."
        "ERROR_USER_NOT_FOUND", "ERROR_USER_TOKEN_EXPIRED" -> "Bitte melde dich neu an."
        else -> "Die Mail ließ sich gerade nicht senden. Bitte versuch es gleich noch einmal."
    }
}

/** Bestätigungs-Mail senden, auf Deutsch. Fehler gibt die Meldung zurück, Erfolg null. */
internal suspend fun sendVerificationMail(auth: FirebaseAuth): String? {
    val user = auth.currentUser ?: return "Bitte melde dich neu an."
    auth.setLanguageCode("de")
    return try {
        user.sendEmailVerification().await()
        null
    } catch (e: FirebaseAuthException) {
        VerifyRules.sendError(e.errorCode)
    } catch (e: Exception) {
        VerifyRules.sendError(null)
    }
}

/**
 * Nach Registrierung oder Anmeldung mit unbestätigter Adresse: Erst nach dem Klick auf den Link in der Mail geht
 * es in die App. Die Functions und die Firestore-Regeln verlangen ebenfalls eine bestätigte Adresse.
 *
 * @param sentAtStart true direkt nach der Registrierung (die Mail ist schon unterwegs)
 */
@Composable
fun VerifyEmailScreen(auth: FirebaseAuth, sentAtStart: Boolean, onVerified: () -> Unit) {
    val scope = rememberCoroutineScope()
    var lastSent by remember { mutableStateOf<Long>(if (sentAtStart) System.currentTimeMillis() else 0L) }
    var now by remember { mutableStateOf<Long>(System.currentTimeMillis()) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val email = auth.currentUser?.email.orEmpty()

    /** Neu laden; bestätigt → frisches ID-Token (sonst sehen Functions und Regeln die Bestätigung bis zu 1 Std. nicht). */
    suspend fun check(manual: Boolean) {
        val user = auth.currentUser ?: return
        val verified = runCatching { user.reload().await(); auth.currentUser?.isEmailVerified == true }.getOrDefault(false)
        if (verified) {
            runCatching { auth.currentUser?.getIdToken(true)?.await() }
            onVerified()
        } else if (manual) {
            message = "Noch nicht bestätigt. Schau auch im Spam-Ordner nach."
        }
    }

    // Beim Öffnen einmal senden, wenn noch keine Mail unterwegs ist (z. B. Anmeldung mit altem, unbestätigtem Konto)
    LaunchedEffect(Unit) {
        if (!sentAtStart) {
            message = sendVerificationMail(auth)
            if (message == null) lastSent = System.currentTimeMillis()
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(VerifyRules.POLL_MS)
            now = System.currentTimeMillis()
            check(manual = false)
        }
    }
    LaunchedEffect(lastSent) {
        while (VerifyRules.secondsLeft(lastSent, System.currentTimeMillis()) > 0) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
        now = System.currentTimeMillis()
    }
    val wait = VerifyRules.secondsLeft(lastSent, now)

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(modifier = Modifier.size(64.dp).background(GrowPalette.Green.copy(alpha = 0.16f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Email, contentDescription = null, tint = GrowPalette.Green, modifier = Modifier.size(34.dp))
        }
        Text("Bestätige deine E-Mail-Adresse", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(
            "Wir haben einen Link an $email geschickt. Öffne die Mail und tippe auf den Link, dann geht es hier von selbst weiter.",
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            "Die Bestätigung schützt dein Konto und stellt sicher, dass Alarme, Rechnungen und das Zurücksetzen des Passworts bei dir ankommen.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(onClick = {
            busy = true
            message = null
            scope.launch { check(manual = true); busy = false }
        }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Ich habe bestätigt") }
        OutlinedButton(onClick = {
            busy = true
            scope.launch {
                message = sendVerificationMail(auth)
                if (message == null) {
                    lastSent = System.currentTimeMillis()
                    message = "Neue Mail ist unterwegs."
                }
                busy = false
            }
        }, enabled = !busy && wait == 0, modifier = Modifier.fillMaxWidth()) {
            Text(if (wait > 0) "Erneut senden in $wait s" else "Mail erneut senden")
        }
        TextButton(onClick = { auth.signOut() }, enabled = !busy) { Text("Falsche Adresse? Abmelden und neu registrieren") }
    }
}
