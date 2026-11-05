package de.greenganja.growguardian.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.firebase.auth.FirebaseAuth
import de.greenganja.growguardian.backend.Backend
import de.greenganja.growguardian.billing.FarmerTier
import de.greenganja.growguardian.push.PushTokens
import kotlinx.coroutines.launch

/** Konto: Abo, Werbe-Einwilligung, Rechtstexte, Abmelden und Konto löschen (Pflicht für Google Play). */
@Composable
fun AccountScreen(
    app: GrowGuardianApp,
    onUpgradeClick: () -> Unit,
    modifier: Modifier = Modifier,
    onAppTour: (() -> Unit)? = null,
    onClubTour: (() -> Unit)? = null,
) {
    val sub by app.subscriptions.state.collectAsStateWithLifecycle()
    val auth = remember { FirebaseAuth.getInstance() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirmDelete by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    fun open(url: String) = context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Konto", style = MaterialTheme.typography.headlineSmall)
        Text(auth.currentUser?.email.orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(sub.tier.displayName, style = MaterialTheme.typography.titleMedium)
                when {
                    sub.isClubSeat -> Text("Zugang über deinen Club.")
                    sub.tier != FarmerTier.SOCIAL_CLUB -> Button(onClick = onUpgradeClick, modifier = Modifier.fillMaxWidth()) { Text("Upgrade ansehen") }
                }
                sub.managementUrl?.let { url ->
                    OutlinedButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, url)) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Abo verwalten oder kündigen")
                    }
                }
            }
        }
        if (app.ads.privacyOptionsRequired) {
            OutlinedButton(onClick = { (context as? Activity)?.let(app.ads::showPrivacyOptions) }, modifier = Modifier.fillMaxWidth()) {
                Text("Werbe-Einwilligung ändern")
            }
        }
        if (onAppTour != null) OutlinedButton(onClick = onAppTour, modifier = Modifier.fillMaxWidth()) { Text("App-Einführung erneut starten") }
        if (onClubTour != null) OutlinedButton(onClick = onClubTour, modifier = Modifier.fillMaxWidth()) { Text("Club-Einführung erneut starten") }
        TextButton(onClick = { open(Legal.PRIVACY_URL) }) { Text("Datenschutzerklärung") }
        TextButton(onClick = { open(Legal.TERMS_URL) }) { Text("Nutzungsbedingungen") }
        TextButton(onClick = { open(Legal.IMPRINT_URL) }) { Text("Impressum") }
        message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        OutlinedButton(
            onClick = {
                scope.launch {
                    auth.currentUser?.uid?.let { PushTokens.unregister(it) }
                    auth.signOut()
                }
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Abmelden") }
        TextButton(onClick = { confirmDelete = true }, enabled = !busy) { Text("Konto löschen", color = MaterialTheme.colorScheme.error) }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Konto endgültig löschen?") },
            text = {
                Text(
                    "Gelöscht werden Boxen, Verlauf, Automationen, Coins, deine Club-Mitgliedschaft und das Konto. " +
                        "Die Abgabe-Dokumentation bewahrt dein Club gesetzlich 5 Jahre auf. " +
                        "Ein Google-Play-Abo endet dadurch nicht, kündige es vorher im Play Store.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    busy = true
                    scope.launch {
                        // Zuerst die Automationen in der Tuya-Cloud entfernen, sonst schalten sie weiter
                        runCatching { app.growBoxes.boxesOnce().forEach { app.growAutomations.removeForBox(it.id) } }
                        Backend().call("deleteMyAccount")
                            .onSuccess { auth.signOut() }
                            .onFailure { message = it.message ?: "Löschen hat nicht geklappt." }
                        busy = false
                    }
                }) { Text("Löschen", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Abbrechen") } },
        )
    }
}
