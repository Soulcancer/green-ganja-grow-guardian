package de.greenganja.growguardian.ads

import android.app.Activity
import android.content.Context
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.RequestConfiguration
import com.google.android.gms.ads.appopen.AppOpenAd
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import com.google.android.gms.ads.rewarded.ServerSideVerificationOptions
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import de.greenganja.growguardian.billing.SubscriptionManager
import de.greenganja.growguardian.rewards.Wallet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Werbung der App: Einwilligung (UMP, in der EU Pflicht), Start des Google Mobile Ads SDK und die Entscheidung,
 * ob gerade Anzeigen erscheinen. Einmal in der Application anlegen, in der Activity [gatherConsent] aufrufen.
 */
class AdsManager(
    context: Context,
    private val subscriptions: SubscriptionManager,
    private val wallet: Flow<Wallet>,
    val units: AdUnits = AdUnits(),
) {
    private val appContext = context.applicationContext
    private val consent: ConsentInformation = UserMessagingPlatform.getConsentInformation(appContext)
    private val started = AtomicBoolean(false)
    private val canRequest = MutableStateFlow(consent.canRequestAds())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Ändert sich jede Minute, damit „werbefrei bis“ ohne Neustart abläuft. */
    private val clock = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(60_000)
        }
    }

    /** true, wenn auf dem aktuellen Bildschirm Banner erscheinen dürfen. */
    val showAds: StateFlow<Boolean> = combine(subscriptions.state, wallet, canRequest, clock) { sub, w, can, now ->
        AdPolicy.showAds(sub.tier, w.adFreeUntil, can, now)
    }.stateIn(scope, SharingStarted.Eagerly, false)

    /** Muss im Datenschutz-Menü angeboten werden, wenn true (z. B. „Werbe-Einwilligung ändern“). */
    val privacyOptionsRequired: Boolean
        get() = consent.privacyOptionsRequirementStatus == ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED

    /** Einwilligung abfragen (zeigt das Formular nur, wenn nötig) und danach das SDK starten. */
    fun gatherConsent(activity: Activity) {
        val params = ConsentRequestParameters.Builder().build()
        consent.requestConsentInfoUpdate(
            activity,
            params,
            {
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { _ ->
                    canRequest.value = consent.canRequestAds()
                    startIfAllowed()
                }
            },
            { _ ->
                // Ohne Netz: mit der zuletzt gespeicherten Einwilligung weitermachen
                canRequest.value = consent.canRequestAds()
                startIfAllowed()
            },
        )
        startIfAllowed()
    }

    fun showPrivacyOptions(activity: Activity) {
        UserMessagingPlatform.showPrivacyOptionsForm(activity) { _ -> canRequest.value = consent.canRequestAds() }
    }

    private fun startIfAllowed() {
        if (!consent.canRequestAds() || !started.compareAndSet(false, true)) return
        // Erwachsene Inhalte erlaubt (App ab 18), aber keine Anzeigen über die Einstufung „MA“ hinaus
        MobileAds.setRequestConfiguration(
            RequestConfiguration.Builder().setMaxAdContentRating(RequestConfiguration.MAX_AD_CONTENT_RATING_MA).build(),
        )
        MobileAds.initialize(appContext) { }
    }

    // ---------- Start-Anzeige (App Open) ----------

    /**
     * Zeigt beim App-Start eine App-Open-Anzeige, wenn sie innerhalb von [AdPolicy.APP_OPEN_WAIT_MS] lädt.
     * Kehrt zurück, sobald die Anzeige geschlossen ist oder nicht kam; danach zeigt die App ihren Inhalt.
     * Länge und Überspringen bestimmt die Anzeige selbst (AdMob-Richtlinie).
     */
    suspend fun showAppOpenIfAllowed(activity: Activity, firstLaunch: Boolean) {
        if (firstLaunch || !consent.canRequestAds()) return
        // Erst den echten Abo-Status abwarten, damit Social Club nie eine Start-Anzeige sieht
        val sub = kotlinx.coroutines.withTimeoutOrNull(2_000) { subscriptions.state.first { !it.isLoading } } ?: return
        val coins = kotlinx.coroutines.withTimeoutOrNull(1_500) { wallet.first() }
        val allowed = AdPolicy.showAds(sub.tier, coins?.adFreeUntil ?: 0, consent.canRequestAds(), System.currentTimeMillis())
        if (!AdPolicy.showAppOpen(allowed, firstLaunch)) return
        startIfAllowed()
        val ad = kotlinx.coroutines.withTimeoutOrNull(AdPolicy.APP_OPEN_WAIT_MS) {
            suspendCancellableCoroutine<AppOpenAd?> { cont ->
                AppOpenAd.load(activity, units.appOpen, AdRequest.Builder().build(), object : AppOpenAd.AppOpenAdLoadCallback() {
                    override fun onAdLoaded(ad: AppOpenAd) {
                        if (cont.isActive) cont.resume(ad)
                    }

                    override fun onAdFailedToLoad(error: LoadAdError) {
                        if (cont.isActive) cont.resume(null)
                    }
                })
            }
        } ?: return
        suspendCancellableCoroutine<Unit> { cont ->
            ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                override fun onAdDismissedFullScreenContent() {
                    if (cont.isActive) cont.resume(Unit)
                }

                override fun onAdFailedToShowFullScreenContent(error: AdError) {
                    if (cont.isActive) cont.resume(Unit)
                }
            }
            ad.show(activity)
        }
    }

    // ---------- Belohnungsvideos ----------

    sealed interface RewardOutcome {
        /** Angesehen; die Coins schreibt der Server gut, sobald AdMob ihn benachrichtigt. */
        data object Earned : RewardOutcome
        data object Skipped : RewardOutcome
        data class Failed(val message: String) : RewardOutcome
    }

    /**
     * Lädt und zeigt ein Belohnungsvideo. Die Firebase-UID geht als `user_id` an AdMob; die Gutschrift
     * passiert serverseitig (admobRewardCallback), damit niemand Coins ohne Video bekommt.
     */
    suspend fun showRewarded(activity: Activity, uid: String): RewardOutcome {
        if (!consent.canRequestAds()) return RewardOutcome.Failed("Für Belohnungsvideos ist die Werbe-Einwilligung nötig.")
        val ad = suspendCancellableCoroutine<RewardedAd?> { cont ->
            RewardedAd.load(activity, units.rewarded, AdRequest.Builder().build(), object : RewardedAdLoadCallback() {
                override fun onAdLoaded(ad: RewardedAd) {
                    if (cont.isActive) cont.resume(ad)
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    if (cont.isActive) cont.resume(null)
                }
            })
        } ?: return RewardOutcome.Failed("Gerade ist kein Video verfügbar. Versuch es später noch einmal.")

        ad.setServerSideVerificationOptions(ServerSideVerificationOptions.Builder().setUserId(uid).setCustomData("coins").build())
        return suspendCancellableCoroutine { cont ->
            var earned = false
            ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                override fun onAdDismissedFullScreenContent() {
                    if (cont.isActive) cont.resume(if (earned) RewardOutcome.Earned else RewardOutcome.Skipped)
                }

                override fun onAdFailedToShowFullScreenContent(error: AdError) {
                    if (cont.isActive) cont.resume(RewardOutcome.Failed("Das Video konnte nicht gezeigt werden."))
                }
            }
            ad.show(activity) { _ -> earned = true }
        }
    }
}
