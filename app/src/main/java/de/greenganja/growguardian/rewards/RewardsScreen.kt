package de.greenganja.growguardian.rewards

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.greenganja.growguardian.ads.AdsManager
import de.greenganja.growguardian.ads.inlineAd
import de.greenganja.growguardian.billing.SubscriptionManager
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Coins: tägliche Belohnung mit Serie, Belohnungsvideos, Erfolge und Shop.
 * Coins lassen sich nicht kaufen und nicht auszahlen; sie schalten nur Kleinigkeiten auf Zeit frei.
 */
@Composable
fun RewardsScreen(
    wallets: WalletRepository,
    subscriptions: SubscriptionManager,
    ads: AdsManager?,
    modifier: Modifier = Modifier,
    viewModel: RewardsViewModel = viewModel { RewardsViewModel(wallets, subscriptions) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val activity = LocalContext.current as? Activity

    LaunchedEffect(viewModel) {
        viewModel.eventFlow.collect { event ->
            when (event) {
                is RewardsEvent.Message -> snackbar.showSnackbar(event.text)
                RewardsEvent.ShowVideo -> scope.launch {
                    val uid = wallets.currentUid
                    when {
                        ads == null || activity == null || uid == null -> snackbar.showSnackbar("Videos sind gerade nicht verfügbar.")
                        else -> when (val outcome = ads.showRewarded(activity, uid)) {
                            AdsManager.RewardOutcome.Earned -> viewModel.videoEarned()
                            AdsManager.RewardOutcome.Skipped -> snackbar.showSnackbar("Video abgebrochen, keine Coins.")
                            is AdsManager.RewardOutcome.Failed -> snackbar.showSnackbar(outcome.message)
                        }
                    }
                }
            }
        }
    }

    Scaffold(modifier = modifier, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(text = "${state.wallet.balance}", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold)
                        Text(text = "Coins", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        unlocks(state).forEach { Text(text = it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                    }
                }
            }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(text = "Tägliche Belohnung", style = MaterialTheme.typography.titleMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Rewards.DAILY.forEachIndexed { index, coins ->
                                val day = index + 1
                                val streakDay = ((state.nextStreak - 1) % 7) + 1
                                val done = if (state.canCheckIn) day < streakDay else day <= streakDay
                                Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        text = "+$coins",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(text = "Tag $day", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        Button(onClick = viewModel::checkIn, enabled = state.canCheckIn && !state.busy, modifier = Modifier.fillMaxWidth()) {
                            Text(if (state.canCheckIn) "+${state.nextReward} Coins abholen" else "Heute abgeholt · Serie ${state.wallet.streak}")
                        }
                    }
                }
            }
            inlineAd("rewards-top")
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(text = "Video ansehen", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = "+${Rewards.VIDEO_COINS} Coins je Video, noch ${state.videosLeft} von ${Rewards.VIDEOS_PER_DAY} heute.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        OutlinedButton(onClick = viewModel::watchVideo, enabled = state.videosLeft > 0 && !state.busy, modifier = Modifier.fillMaxWidth()) {
                            Text("Video starten")
                        }
                    }
                }
            }
            item { Text(text = "Einlösen", style = MaterialTheme.typography.titleMedium) }
            val offers = Rewards.ShopItem.entries.filter(state::offered)
            if (offers.isEmpty()) item { Text("Deine Stufe enthält schon alles. Coins kannst du für hervorgehobene Inserate im Marktplatz nutzen.") }
            items(offers, key = { it.id }) { item ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = item.label, style = MaterialTheme.typography.titleSmall)
                            Text(text = item.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Button(onClick = { viewModel.redeem(item) }, enabled = state.wallet.balance >= item.coins && !state.busy) {
                            Text("${item.coins}")
                        }
                    }
                }
            }
            item { Text(text = "Erfolge", style = MaterialTheme.typography.titleMedium) }
            items(Rewards.ACHIEVEMENTS, key = { it.id }) { achievement ->
                val done = achievement.id in state.wallet.achievements
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(text = if (done) "✓" else "○", color = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(text = achievement.label, modifier = Modifier.weight(1f).padding(start = 12.dp))
                    Text(text = "+${achievement.coins}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item {
                Text(
                    text = "Coins sind kein Geld: Sie lassen sich nicht kaufen, auszahlen oder übertragen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            inlineAd("rewards-end")
        }
    }
}

private fun unlocks(state: RewardsUiState): List<String> {
    val format = SimpleDateFormat("dd.MM. HH:mm", Locale.GERMANY)
    return listOfNotNull(
        state.wallet.adFreeUntil.takeIf { it > state.now }?.let { "Werbefrei bis ${format.format(Date(it))}" },
        state.wallet.alertsUntil.takeIf { it > state.now }?.let { "Alarme aktiv bis ${format.format(Date(it))}" },
    )
}
