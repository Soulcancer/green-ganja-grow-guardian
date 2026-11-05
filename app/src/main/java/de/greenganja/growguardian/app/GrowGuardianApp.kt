package de.greenganja.growguardian.app

import android.app.Application
import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import de.greenganja.growguardian.BuildConfig
import de.greenganja.growguardian.ads.AdUnits
import de.greenganja.growguardian.ads.AdsManager
import de.greenganja.growguardian.billing.SubscriptionManager
import de.greenganja.growguardian.club.ClubBoxRepository
import de.greenganja.growguardian.club.ClubRepository
import de.greenganja.growguardian.club.MarketRepository
import de.greenganja.growguardian.club.ModerationRepository
import de.greenganja.growguardian.grow.GrowBoxRepository
import de.greenganja.growguardian.grow.automation.AutomationRepository
import de.greenganja.growguardian.grow.automation.GrowAutomations
import de.greenganja.growguardian.grow.background.GrowMonitor
import de.greenganja.growguardian.grow.background.GrowMonitorHost
import de.greenganja.growguardian.grow.background.GrowMonitorWorker
import de.greenganja.growguardian.grow.history.GrowHistoryRepository
import de.greenganja.growguardian.rewards.WalletRepository
import de.greenganja.growguardian.tuya.TuyaRuntime
import de.greenganja.growguardian.tuya.TuyaAlarmPush
import de.greenganja.growguardian.grow.alarm.GrowNotifier
import de.greenganja.growguardian.grow.automation.ServerAutomationEngine
import de.greenganja.growguardian.push.FirestoreServerMonitorStatus
import de.greenganja.growguardian.push.PushTokens
import de.greenganja.growguardian.tuya.TuyaPairing
import de.greenganja.growguardian.tuya.TuyaSensorSource
import de.greenganja.growguardian.tuya.TuyaSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Alle langlebigen Objekte der App an einer Stelle. Im Manifest als `android:name` eingetragen. */
class GrowGuardianApp : Application(), GrowMonitorHost {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    val subscriptions = SubscriptionManager()
    val clubs by lazy { ClubRepository(subscriptions) }
    val tuya by lazy { TuyaSession(this) }
    val sensors by lazy { TuyaSensorSource(tuya) }
    val growBoxes by lazy { GrowBoxRepository() }
    val growHistory by lazy { GrowHistoryRepository() }
    val pairing by lazy { TuyaPairing(tuya) }
    // Automationen laufen auf dem Server (serverMonitor). Alternative: TuyaAutomationSync(tuya) als Tuya-Szenen.
    val growAutomations by lazy { GrowAutomations(this, AutomationRepository(), ServerAutomationEngine()) }
    val clubBoxes by lazy { ClubBoxRepository() }
    val market by lazy { MarketRepository() }
    val moderation by lazy { ModerationRepository() }
    val wallets by lazy { WalletRepository() }
    val ads by lazy {
        AdsManager(
            this, subscriptions, wallets.wallet(),
            AdUnits(
                anchoredBanner = BuildConfig.ADMOB_BANNER_ID,
                inlineBanner = BuildConfig.ADMOB_BANNER_ID,
                rewarded = BuildConfig.ADMOB_REWARDED_ID,
                appOpen = BuildConfig.ADMOB_APP_OPEN_ID,
            ),
        )
    }

    override val growMonitor by lazy {
        GrowMonitor(
            this, growBoxes, sensors, growHistory, subscriptions,
            automations = growAutomations,
            clubBoxes = clubBoxes,
            // Ohne eigene PushTaskFactory bleiben Alarme beim Handy (siehe README „Alarme per Push“)
            alarmPush = TuyaAlarmPush(tuya),
            wallet = wallets,
            server = FirestoreServerMonitorStatus(),
        )
    }

    override fun onCreate() {
        super.onCreate()
        TuyaRuntime.init(this, BuildConfig.TUYA_CONFIGURED)
        GrowNotifier(this).ensureChannel() // auch für Push-Nachrichten im Hintergrund
        val auth = FirebaseAuth.getInstance()
        subscriptions.configure(
            context = this,
            apiKey = BuildConfig.REVENUECAT_API_KEY,
            appUserId = auth.currentUser?.uid,
            debugLogs = BuildConfig.DEBUG,
        )
        // An- und Abmelden: RevenueCat-Konto wechseln, Hintergrund-Job starten oder stoppen
        auth.addAuthStateListener { firebase ->
            val uid = firebase.currentUser?.uid
            scope.launch {
                if (uid != null) {
                    subscriptions.logIn(uid)
                    GrowMonitorWorker.start(this@GrowGuardianApp)
                    PushTokens.register()
                } else {
                    subscriptions.logOut()
                    GrowMonitorWorker.stop(this@GrowGuardianApp)
                }
            }
        }
    }

    /** true nur beim allerersten Start; danach false. Am ersten Start gibt es keine Start-Anzeige. */
    fun consumeFirstLaunch(): Boolean {
        val prefs = getSharedPreferences("app", Context.MODE_PRIVATE)
        val first = !prefs.getBoolean(KEY_LAUNCHED, false)
        if (first) prefs.edit().putBoolean(KEY_LAUNCHED, true).apply()
        return first
    }

    private companion object {
        const val KEY_LAUNCHED = "launchedBefore"
    }
}
