package de.greenganja.growguardian.rewards

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.greenganja.growguardian.billing.FarmerTier
import de.greenganja.growguardian.billing.SubscriptionManager
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.ZoneId

data class RewardsUiState(
    val loading: Boolean = true,
    val wallet: Wallet = Wallet(),
    val tier: FarmerTier = FarmerTier.FREEMIUM,
    val today: String = "",
    val yesterday: String = "",
    val now: Long = 0,
    val busy: Boolean = false,
) {
    val canCheckIn: Boolean get() = !wallet.checkedInToday(today)
    val nextStreak: Int get() = Rewards.nextStreak(wallet, today, yesterday)
    val nextReward: Int get() = Rewards.dailyReward(nextStreak)
    val videosLeft: Int get() = wallet.videosLeft(today).coerceAtLeast(0)

    /** Social Club ist werbefrei; Alarme auf Zeit braucht nur Freemium. */
    fun offered(item: Rewards.ShopItem): Boolean = when (item) {
        Rewards.ShopItem.AD_FREE_1D, Rewards.ShopItem.AD_FREE_7D -> tier != FarmerTier.SOCIAL_CLUB
        Rewards.ShopItem.ALERTS_3D -> !tier.features.alerts
        Rewards.ShopItem.BOOST_LISTING -> false // wird direkt am eigenen Inserat angeboten
    }
}

sealed interface RewardsEvent {
    data class Message(val text: String) : RewardsEvent

    /** Die Oberfläche soll ein Belohnungsvideo zeigen (braucht die Activity). */
    data object ShowVideo : RewardsEvent
}

class RewardsViewModel(
    private val wallets: WalletRepository,
    subscriptions: SubscriptionManager,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    private val busy = MutableStateFlow(false)
    private val events = Channel<RewardsEvent>(Channel.BUFFERED)
    val eventFlow: Flow<RewardsEvent> = events.receiveAsFlow()

    val state: StateFlow<RewardsUiState> = combine(
        wallets.wallet().catch { emit(Wallet()) },
        subscriptions.state,
        busy,
    ) { wallet, sub, busy ->
        val now = clock()
        val today = java.time.Instant.ofEpochMilli(now).atZone(BERLIN).toLocalDate()
        RewardsUiState(false, wallet, sub.tier, today.toString(), today.minusDays(1).toString(), now, busy)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RewardsUiState())

    init {
        // Erfolge prüfen, sobald der Bildschirm öffnet (z. B. erste Box seit dem letzten Besuch)
        viewModelScope.launch {
            wallets.claimAchievements().onSuccess { labels ->
                labels.forEach { events.send(RewardsEvent.Message("Erfolg: $it")) }
            }
        }
    }

    fun checkIn() = act({ wallets.dailyCheckIn() }) { "+$it Coins. Bis morgen!" }

    fun watchVideo() {
        if (state.value.videosLeft <= 0) {
            events.trySend(RewardsEvent.Message("Für heute sind alle Videos angesehen. Morgen gibt es neue."))
            return
        }
        events.trySend(RewardsEvent.ShowVideo)
    }

    fun videoEarned() {
        events.trySend(RewardsEvent.Message("Danke! ${Rewards.VIDEO_COINS} Coins kommen in wenigen Sekunden."))
    }

    fun redeem(item: Rewards.ShopItem) = act({ wallets.redeem(item) }) { "${item.label} eingelöst." }

    private fun <T> act(action: suspend () -> Result<T>, success: (T) -> String) {
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            try {
                action()
                    .onSuccess { events.send(RewardsEvent.Message(success(it))) }
                    .onFailure { events.send(RewardsEvent.Message(it.message ?: "Das hat nicht geklappt.")) }
            } finally {
                busy.value = false
            }
        }
    }

    private companion object {
        val BERLIN: ZoneId = ZoneId.of("Europe/Berlin")
    }
}
