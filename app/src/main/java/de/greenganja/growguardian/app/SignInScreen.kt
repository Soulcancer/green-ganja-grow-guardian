package de.greenganja.growguardian.app

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/** Anmelden oder registrieren mit E-Mail und Passwort. Registrieren nur mit Bestätigung „ab 18“. */
@Composable
fun SignInScreen(auth: FirebaseAuth, initialRegister: Boolean = false, onBack: (() -> Unit)? = null) {
    var register by rememberSaveable { mutableStateOf(initialRegister) }
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var adult by rememberSaveable { mutableStateOf(false) }
    var busy by rememberSaveable { mutableStateOf(false) }
    var message by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val emailOk = SignInRules.isEmail(email)
    val canSubmit = !busy && emailOk && password.length >= SignInRules.MIN_PASSWORD && (!register || adult)

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (onBack != null) {
            TextButton(onClick = onBack, enabled = !busy) { Text("← Zurück zur Startseite") }
        }
        Text("Green Ganja Grow Guardian", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(
            if (register) "Konto anlegen" else "Anmelden",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = email, onValueChange = { email = it.trim() }, modifier = Modifier.fillMaxWidth(),
            label = { Text("E-Mail") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
        )
        OutlinedTextField(
            value = password, onValueChange = { password = it }, modifier = Modifier.fillMaxWidth(),
            label = { Text("Passwort (mind. ${SignInRules.MIN_PASSWORD} Zeichen)") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
        if (register) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = adult, onCheckedChange = { adult = it })
                Text("Ich bin mindestens 18 Jahre alt und akzeptiere die Nutzungsbedingungen und die Datenschutzerklärung.")
            }
            Row {
                TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Legal.TERMS_URL))) }) { Text("Nutzungsbedingungen") }
                TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Legal.PRIVACY_URL))) }) { Text("Datenschutz") }
            }
        }
        message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(
            onClick = {
                busy = true
                message = null
                scope.launch {
                    message = try {
                        if (register) auth.createUserWithEmailAndPassword(email, password).await()
                        else auth.signInWithEmailAndPassword(email, password).await()
                        null
                    } catch (e: FirebaseAuthException) {
                        SignInRules.message(e.errorCode)
                    } catch (e: Exception) {
                        "Keine Verbindung. Bitte versuch es noch einmal."
                    }
                    busy = false
                }
            },
            enabled = canSubmit,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (register) "Konto anlegen" else "Anmelden") }
        TextButton(onClick = { register = !register; message = null }) {
            Text(if (register) "Ich habe schon ein Konto" else "Neu hier? Konto anlegen")
        }
        if (!register) {
            TextButton(
                onClick = {
                    scope.launch {
                        message = runCatching { auth.sendPasswordResetEmail(email).await() }
                            .fold({ "Wir haben dir einen Link zum Zurücksetzen geschickt." }, { "Bitte gib zuerst deine E-Mail-Adresse ein." })
                    }
                },
                enabled = emailOk && !busy,
            ) { Text("Passwort vergessen?") }
        }
        Text(
            "Nur für Erwachsene ab 18 Jahren und den legalen Eigenanbau nach dem Konsumcannabisgesetz.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Ohne Compose, damit testbar. */
object SignInRules {
    const val MIN_PASSWORD = 8

    fun isEmail(text: String): Boolean = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]{2,}$").matches(text.trim())

    /** Deutsche Meldung zu Firebase-Fehlercodes. */
    fun message(code: String?): String = when (code) {
        "ERROR_INVALID_EMAIL" -> "Diese E-Mail-Adresse ist ungültig."
        "ERROR_WRONG_PASSWORD", "ERROR_INVALID_CREDENTIAL", "ERROR_USER_NOT_FOUND" -> "E-Mail oder Passwort stimmt nicht."
        "ERROR_EMAIL_ALREADY_IN_USE" -> "Mit dieser E-Mail gibt es schon ein Konto. Melde dich an."
        "ERROR_WEAK_PASSWORD" -> "Das Passwort ist zu schwach."
        "ERROR_TOO_MANY_REQUESTS" -> "Zu viele Versuche. Bitte warte kurz."
        "ERROR_USER_DISABLED" -> "Dieses Konto ist gesperrt."
        else -> "Anmelden hat nicht geklappt. Bitte versuch es noch einmal."
    }
}
