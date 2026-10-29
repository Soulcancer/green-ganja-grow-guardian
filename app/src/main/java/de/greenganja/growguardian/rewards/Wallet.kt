package de.greenganja.growguardian.rewards

import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import de.greenganja.growguardian.backend.Backend
import de.greenganja.growguardian.billing.TierFeatures
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.tasks.await

/** Coins und was damit freigeschaltet ist. Gebucht wird nur im Backend (functions/src/coins.ts). */
data class Wallet(
    val balance: Int = 0,
    val streak: Int = 0,
    /** "YYYY-MM-DD" der letzten täglichen Belohnung. */
    val lastCheckIn: String? = null,
    val rewardedDay: String? = null,
    val rewardedCount: Int = 0,
    val achievements: Set<String> = emptySet(),
    val adFreeUntil: Long = 0,
    val alertsUntil: Long = 0,
) {
    fun adFree(now: Long): Boolean = now < adFreeUntil
    fun alertsUnlocked(now: Long): Boolean = now < alertsUntil
    fun checkedInToday(today: String): Boolean = lastCheckIn == today
    fun videosLeft(today: String): Int = Rewards.VIDEOS_PER_DAY - if (rewardedDay == today) rewardedCount else 0
}

/** Gleiche Werte wie rules/coins.ts im Backend; hier nur für die Anzeige. */
object Rewards {
    val DAILY = listOf(5, 6, 7, 8, 10, 12, 20)
    const val VIDEO_COINS = 10
    const val VIDEOS_PER_DAY = 5

    data class Achievement(val id: String, val coins: Int, val label: String)

    val ACHIEVEMENTS = listOf(
        Achievement("first_box", 20, "Erste Grow-Box angelegt"),
        Achievement("first_automation", 25, "Erste Automation"),
        Achievement("first_listing", 10, "Erstes Inserat im Marktplatz"),
        Achievement("club_member", 15, "Mitglied in einem Club"),
        Achievement("week_streak", 30, "7 Tage in Folge vorbeigeschaut"),
    )

    enum class ShopItem(val id: String, val coins: Int, val label: String, val description: String) {
        AD_FREE_1D("ad_free_1d", 40, "1 Tag werbefrei", "Keine Banner für 24 Stunden"),
        AD_FREE_7D("ad_free_7d", 200, "7 Tage werbefrei", "Keine Banner für eine Woche"),
        ALERTS_3D("alerts_3d", 60, "3 Tage Alarme", "Benachrichtigungen bei Werten außerhalb des Ziels, auch ohne Premium"),
        BOOST_LISTING("boost_listing", 20, "Inserat hervorheben", "3 Tage ganz oben im Club-Marktplatz"),
    }

    /** Belohnung für den Tag [streakDay] der Serie (1-basiert, nach 7 von vorn). */
    fun dailyReward(streakDay: Int): Int = DAILY[(streakDay.coerceAtLeast(1) - 1) % DAILY.size]

    /** Welcher Serientag heute käme: weiterzählen, wenn gestern abgeholt, sonst Tag 1. */
    fun nextStreak(wallet: Wallet, today: String, yesterday: String): Int = when (wallet.lastCheckIn) {
        today -> wallet.streak
        yesterday -> wallet.streak + 1
        else -> 1
    }

    /** Stufen-Funktionen plus, was mit Coins gerade freigeschaltet ist. */
    fun effectiveFeatures(features: TierFeatures, wallet: Wallet?, now: Long): TierFeatures =
        if (!features.alerts && wallet?.alertsUnlocked(now) == true) features.copy(alerts = true) else features
}

/** Liefert die Wallet einmal, z. B. für den Hintergrund-Job. */
interface WalletSource {
    suspend fun walletOnce(): Wallet?
}

class WalletRepository(
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val backend: Backend = Backend(),
) : WalletSource {
    val currentUid: String? get() = auth.currentUser?.uid

    /** Wallet des jeweils angemeldeten Nutzers; wechselt bei An- und Abmelden mit. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun wallet(): Flow<Wallet> = callbackFlow {
        val listener = FirebaseAuth.AuthStateListener { trySend(it.currentUser?.uid) }
        auth.addAuthStateListener(listener)
        awaitClose { auth.removeAuthStateListener(listener) }
    }.distinctUntilChanged().flatMapLatest { uid -> if (uid == null) flowOf(Wallet()) else walletOf(uid) }

    private fun walletOf(uid: String): Flow<Wallet> {
        return callbackFlow {
            val registration = db.collection("wallets").document(uid).addSnapshotListener { snapshot, error ->
                when {
                    error?.code == FirebaseFirestoreException.Code.PERMISSION_DENIED -> trySend(Wallet())
                    error != null -> close(error)
                    else -> trySend(snapshot?.takeIf { it.exists() }?.toWallet() ?: Wallet())
                }
            }
            awaitClose { registration.remove() }
        }
    }

    override suspend fun walletOnce(): Wallet? {
        val uid = currentUid ?: return null
        return db.collection("wallets").document(uid).get().await().takeIf { it.exists() }?.toWallet() ?: Wallet()
    }

    /** @return Coins für heute plus ein eventueller Serien-Bonus */
    suspend fun dailyCheckIn(): Result<Int> =
        backend.call("dailyCheckIn").map { ((it["reward"] as? Number)?.toInt() ?: 0) + ((it["bonus"] as? Number)?.toInt() ?: 0) }

    /** @return Bezeichnungen neu erreichter Erfolge */
    suspend fun claimAchievements(): Result<List<String>> =
        backend.call("claimAchievements").map { result ->
            (result["granted"] as? List<*>).orEmpty().mapNotNull { (it as? Map<*, *>)?.get("label") as? String }
        }

    suspend fun redeem(item: Rewards.ShopItem, listingId: String? = null): Result<Unit> =
        backend.call("redeemCoins", mapOf("item" to item.id, "listingId" to listingId)).map { }
}

internal fun DocumentSnapshot.toWallet(): Wallet = Wallet(
    balance = getLong("balance")?.toInt() ?: 0,
    streak = getLong("streak")?.toInt() ?: 0,
    lastCheckIn = getString("lastCheckIn"),
    rewardedDay = getString("rewardedDay"),
    rewardedCount = getLong("rewardedCount")?.toInt() ?: 0,
    achievements = (get("achievements") as? List<*>).orEmpty().filterIsInstance<String>().toSet(),
    adFreeUntil = (get("adFreeUntil") as? Timestamp)?.toDate()?.time ?: 0L,
    alertsUntil = (get("alertsUntil") as? Timestamp)?.toDate()?.time ?: 0L,
)
