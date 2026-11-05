package de.greenganja.growguardian.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.greenganja.growguardian.ui.theme.GrowPalette

/** Inhalte der Startseite, ohne Compose (testbar). */
object WelcomeContent {
    /** Bei inhaltlichen Änderungen der Hinweise erhöhen: dann müssen alle sie neu bestätigen. */
    const val DISCLAIMER_VERSION = 1

    data class Feature(val key: String, val title: String, val text: String)

    val features = listOf(
        Feature("sensors", "Live-Werte aus der Box", "Temperatur, Luftfeuchte, VPD, CO₂, pH und EC deiner Tuya-Sensoren, mit Zielwerten je Phase."),
        Feature("plants", "Pflanzen und Tagebuch", "Mehrere Pflanzen je Zelt mit Sorte, Start, Blüte und Ernte. Gießen, Düngen und Notizen im Tagebuch."),
        Feature("light", "Licht und Spektrum", "PPFD und Tageslichtmenge messen, Spektrum je Lampe, Lampenrechner."),
        Feature("history", "Analyse und Verlauf", "Kurven von 24 Stunden bis zu einem Jahr, Trends und Tageswerte."),
        Feature("alerts", "Alarme und Automationen", "Push-Nachricht, wenn ein Wert aus dem Ziel läuft; Licht, Lüfter oder Entfeuchter automatisch schalten."),
        Feature("club", "Social Club", "Für Anbauvereinigungen: Mitglieder, gemeinsame Boxen und Team-Plätze."),
    )

    val tiers = listOf(
        "Freemium: kostenlos, 1 Box, 7 Tage Verlauf, mit Werbung",
        "Premium: 3 Boxen, Alarme, Automationen, alle Lampenarten, 1 Jahr Verlauf",
        "Social Club: ohne Grenzen, werbefrei, Club-Verwaltung mit 5 Team-Plätzen",
    )

    /** Hinweise vor Anmeldung und Registrierung. Rechtsstand KCanG (Stand der App-Version), keine Rechtsberatung. */
    val disclaimer = listOf(
        "Nur für Erwachsene ab 18 Jahren.",
        "Nur für den legalen Anbau in Deutschland nach dem Konsumcannabisgesetz (KCanG): privat höchstens drei Pflanzen gleichzeitig je volljähriger Person, an deinem Wohnsitz oder gewöhnlichen Aufenthalt (§ 9 KCanG). Zu Hause sind bis zu 50 g getrocknetes Cannabis erlaubt (§ 3 KCanG).",
        "Cannabis aus dem Eigenanbau darf nicht an andere weitergegeben werden (§ 9 Abs. 2 KCanG). Pflanzen und Ernte so aufbewahren, dass Kinder, Jugendliche und Dritte nicht herankommen.",
        "Die App verkauft, vermittelt und liefert kein Cannabis.",
        "Messwerte, Zielwerte und Empfehlungen sind Richtwerte ohne Gewähr. Sensoren und Handy-Messungen können abweichen.",
        "Keine Rechts- oder Medizinberatung. Die Rechtslage kann sich ändern; im Zweifel aktuelle Vorschriften prüfen oder fachkundig beraten lassen.",
        "Elektrik, Lüftung und Heizung in der Growbox sicher installieren und nicht unbeaufsichtigt überlasten (Brandgefahr).",
    )

    fun canContinue(adult: Boolean, read: Boolean): Boolean = adult && read
}

/** Merkt sich, welche Version der Hinweise bestätigt wurde. */
class WelcomeConsent(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("welcome", Context.MODE_PRIVATE)

    val accepted: Boolean get() = prefs.getInt(KEY, 0) >= WelcomeContent.DISCLAIMER_VERSION

    fun accept() {
        prefs.edit().putInt(KEY, WelcomeContent.DISCLAIMER_VERSION).apply()
    }

    private companion object {
        const val KEY = "disclaimerVersion"
    }
}

private fun iconFor(key: String): ImageVector = when (key) {
    "sensors" -> Icons.Filled.Sensors
    "plants" -> Icons.Filled.Edit
    "light" -> Icons.Filled.WbSunny
    "history" -> Icons.Filled.Timeline
    "alerts" -> Icons.Filled.NotificationsActive
    "club" -> Icons.Filled.Groups
    else -> Icons.Filled.Bolt
}

/**
 * Startseite ohne Login: was die App kann, Stufen, wichtige Hinweise (Disclaimer) und der Weg zu
 * Registrierung oder Anmeldung. Weiter geht es erst mit „ab 18“ und „Hinweise gelesen“.
 */
@Composable
fun WelcomeScreen(consent: WelcomeConsent, onRegister: () -> Unit, onSignIn: () -> Unit) {
    val context = LocalContext.current
    var adult by remember { mutableStateOf(consent.accepted) }
    var read by remember { mutableStateOf(consent.accepted) }
    val ok = WelcomeContent.canContinue(adult, read)
    fun go(next: () -> Unit) {
        consent.accept()
        next()
    }
    fun open(url: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier.size(64.dp).background(GrowPalette.Green.copy(alpha = 0.16f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Spa, contentDescription = null, tint = GrowPalette.Green, modifier = Modifier.size(36.dp))
        }
        Text("Green Ganja Grow Guardian", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(
            "Dein Begleiter für den legalen Eigenanbau: Sensoren, Pflanzen, Licht und Tagebuch an einem Ort.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text("Was die App kann", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        WelcomeContent.features.forEach { feature ->
            Row(verticalAlignment = Alignment.Top) {
                Box(
                    modifier = Modifier.size(36.dp).background(GrowPalette.Green.copy(alpha = 0.14f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(iconFor(feature.key), contentDescription = null, tint = GrowPalette.Green, modifier = Modifier.size(20.dp))
                }
                Column(modifier = Modifier.padding(start = 12.dp)) {
                    Text(feature.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    Text(feature.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        Text("Stufen", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        WelcomeContent.tiers.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }

        Card(
            colors = CardDefaults.cardColors(containerColor = GrowPalette.Warning.copy(alpha = 0.10f)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Wichtige Hinweise", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = GrowPalette.Warning)
                WelcomeContent.disclaimer.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = adult, onCheckedChange = { adult = it })
            Text("Ich bin mindestens 18 Jahre alt.")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = read, onCheckedChange = { read = it })
            Text("Ich habe die Hinweise gelesen und nutze die App nur im Rahmen des Gesetzes.")
        }

        Button(onClick = { go(onRegister) }, enabled = ok, modifier = Modifier.fillMaxWidth()) { Text("Konto anlegen") }
        OutlinedButton(onClick = { go(onSignIn) }, enabled = ok, modifier = Modifier.fillMaxWidth()) { Text("Ich habe schon ein Konto: Anmelden") }
        if (!ok) {
            Text(
                "Bitte bestätige beides, um fortzufahren.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Row {
            TextButton(onClick = { open(Legal.PRIVACY_URL) }) { Text("Datenschutz") }
            TextButton(onClick = { open(Legal.TERMS_URL) }) { Text("Nutzungsbedingungen") }
            TextButton(onClick = { open(Legal.IMPRINT_URL) }) { Text("Impressum") }
        }
    }
}
