package de.greenganja.growguardian.app

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.revenuecat.purchases.models.Period
import de.greenganja.growguardian.billing.FarmerPlan
import de.greenganja.growguardian.billing.FarmerTier
import de.greenganja.growguardian.billing.PurchaseOutcome
import de.greenganja.growguardian.billing.SubscriptionManager
import kotlinx.coroutines.launch

/** Was jede Stufe bringt, für die Kaufseite. */
internal object PaywallText {
    fun benefits(tier: FarmerTier): List<String> = when (tier) {
        FarmerTier.FREEMIUM -> listOf("1 Grow-Box, 5 Sensoren", "7 Tage Verlauf", "Lichtmessung für Tageslicht und Glühbirne", "Mit Werbung")
        FarmerTier.PREMIUM -> listOf(
            "3 Grow-Boxen, 30 Sensoren", "1 Jahr Verlauf und Analyse", "Alarme bei Werten außerhalb des Ziels",
            "Bis zu 20 Automationen (Licht, Entfeuchter …)", "Datenexport",
            "Lichtmodul mit allen Lampenarten: LED, NDL, CMH, MH, T5 … mit Spektrum und Lampenrechner",
        )
        FarmerTier.SOCIAL_CLUB -> listOf(
            "Alles aus Premium, ohne Grenzen", "Werbefrei", "Club-Verwaltung mit 5 Team-Plätzen",
            "Mitglieder, Reservierungen, Abgabe-Doku nach KCanG", "Gemeinsame Club-Boxen und Marktplatz-Angebote",
        )
    }

    fun period(period: Period?): String = when (period?.unit) {
        Period.Unit.MONTH -> if (period.value == 1) "pro Monat" else "alle ${period.value} Monate"
        Period.Unit.YEAR -> if (period.value == 1) "pro Jahr" else "alle ${period.value} Jahre"
        Period.Unit.WEEK -> "pro Woche"
        else -> ""
    }

    fun outcome(result: PurchaseOutcome): String? = when (result) {
        is PurchaseOutcome.Success -> "Willkommen bei ${result.tier.displayName}!"
        PurchaseOutcome.Cancelled -> null
        PurchaseOutcome.Pending -> "Zahlung ausstehend. Der Zugang kommt automatisch, sobald bezahlt ist."
        PurchaseOutcome.AlreadyOwned -> "Dieses Abo ist schon aktiv."
        PurchaseOutcome.NotAllowed -> "Käufe sind auf diesem Gerät gesperrt."
        PurchaseOutcome.NetworkError -> "Keine Verbindung. Bitte versuch es noch einmal."
        is PurchaseOutcome.Failed -> result.message
    }
}

/** Kaufseite für Premium Farmer und Social Club Farmer (Preise aus Google Play über RevenueCat). */
@Composable
fun PaywallScreen(subscriptions: SubscriptionManager, onClose: () -> Unit) {
    val sub by subscriptions.state.collectAsStateWithLifecycle()
    var plans by remember { mutableStateOf<List<FarmerPlan>?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val activity = LocalContext.current as? Activity

    LaunchedEffect(Unit) {
        plans = subscriptions.loadPlans().getOrElse {
            message = "Die Angebote konnten nicht geladen werden."
            emptyList()
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück") }
                Text("Upgrade", style = MaterialTheme.typography.headlineSmall)
            }
        }
        item { Text("Aktuell: ${sub.tier.displayName}", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (sub.isClubSeat) item { Text("Dein Zugang kommt über deinen Club. Ein eigenes Abo ist nicht nötig.") }
        message?.let { text -> item { Text(text, color = MaterialTheme.colorScheme.primary) } }
        if (plans == null) item { CircularProgressIndicator() }
        listOf(FarmerTier.PREMIUM, FarmerTier.SOCIAL_CLUB).forEach { tier ->
            item(key = tier.name) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(tier.displayName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        PaywallText.benefits(tier).forEach { Text("• $it") }
                        val tierPlans = plans.orEmpty().filter { it.tier == tier }
                        if (plans != null && tierPlans.isEmpty()) Text("Gerade nicht verfügbar.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        tierPlans.forEach { plan ->
                            Button(
                                onClick = {
                                    val host = activity ?: return@Button
                                    busy = true
                                    scope.launch {
                                        message = PaywallText.outcome(subscriptions.purchase(host, plan))
                                        busy = false
                                    }
                                },
                                enabled = !busy && sub.tier != tier && !sub.isClubSeat,
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("${plan.price} ${PaywallText.period(plan.billingPeriod)}") }
                        }
                    }
                }
            }
        }
        item {
            TextButton(onClick = {
                busy = true
                scope.launch {
                    message = PaywallText.outcome(subscriptions.restore()) ?: "Keine Käufe gefunden."
                    busy = false
                }
            }, enabled = !busy) { Text("Käufe wiederherstellen") }
        }
        item {
            Text(
                "Abos verlängern sich automatisch und lassen sich jederzeit im Google Play Store kündigen. " +
                    "Die Abrechnung erfolgt über dein Google-Konto.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

