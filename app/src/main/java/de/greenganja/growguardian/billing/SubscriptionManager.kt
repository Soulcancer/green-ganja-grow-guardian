package de.greenganja.growguardian.billing

import android.app.Activity
import android.content.Context
import android.net.Uri
import com.revenuecat.purchases.CacheFetchPolicy
import com.revenuecat.purchases.CustomerInfo
import com.revenuecat.purchases.EntitlementInfo
import com.revenuecat.purchases.LogLevel
import com.revenuecat.purchases.Offerings
import com.revenuecat.purchases.Package
import com.revenuecat.purchases.PurchaseParams
import com.revenuecat.purchases.Purchases
import com.revenuecat.purchases.PurchasesConfiguration
import com.revenuecat.purchases.PurchasesErrorCode
import com.revenuecat.purchases.PurchasesException
import com.revenuecat.purchases.PurchasesTransactionException
import com.revenuecat.purchases.Store
import com.revenuecat.purchases.awaitCustomerInfo
import com.revenuecat.purchases.awaitLogIn
import com.revenuecat.purchases.awaitLogOut
import com.revenuecat.purchases.awaitOfferings
import com.revenuecat.purchases.awaitPurchase
import com.revenuecat.purchases.awaitRestore
import com.revenuecat.purchases.interfaces.UpdatedCustomerInfoListener
import com.revenuecat.purchases.models.Period
import com.revenuecat.purchases.models.StoreReplacementMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Date

/** Abo-Status, wie ihn die UI braucht. */
data class SubscriptionState(
    val tier: FarmerTier = FarmerTier.FREEMIUM,
    /** Ablaufdatum des aktiven Abos, null bei Freemium. */
    val expirationDate: Date? = null,
    /** false = gekündigt, der Zugang endet am [expirationDate]. */
    val willRenew: Boolean = false,
    /** Link zur Abo-Verwaltung im Play Store. */
    val managementUrl: Uri? = null,
    /**
     * true, wenn der Nutzer einen der Plätze eines Social-Club-Abos belegt, das jemand anderes
     * bezahlt. Dann keinen Kauf- oder Verwalten-Button zeigen, sondern "Zugang über deinen Club".
     */
    val isClubSeat: Boolean = false,
    /** true, bis der erste Status von RevenueCat da ist. */
    val isLoading: Boolean = true,
) {
    val features: TierFeatures get() = tier.features
}

/** Ein kaufbarer Plan, z. B. "Premium Farmer, jährlich". */
data class FarmerPlan(
    val tier: FarmerTier,
    val rcPackage: Package,
) {
    /** Lokalisierter Preis aus Google Play, z. B. "4,99 €". */
    val price: String get() = rcPackage.product.price.formatted

    /** Abrechnungszeitraum, z. B. 1 Monat oder 1 Jahr. */
    val billingPeriod: Period? get() = rcPackage.product.period
}

sealed interface PurchaseOutcome {
    data class Success(val tier: FarmerTier) : PurchaseOutcome

    data object Cancelled : PurchaseOutcome

    /** Zahlung steht noch aus (z. B. Barzahlung). Der Zugang kommt automatisch, sobald bezahlt ist. */
    data object Pending : PurchaseOutcome

    data object AlreadyOwned : PurchaseOutcome

    /** Käufe sind auf diesem Gerät gesperrt (z. B. Kindermodus oder kein Google-Konto). */
    data object NotAllowed : PurchaseOutcome

    data object NetworkError : PurchaseOutcome

    data class Failed(val message: String) : PurchaseOutcome
}

/**
 * Die einzige Stelle der App, die mit RevenueCat spricht.
 * Eine Instanz für die ganze App, z. B. in der Application-Klasse oder als @Singleton per Hilt.
 */
class SubscriptionManager {

    private val _state = MutableStateFlow(SubscriptionState())

    /** Aktueller Status. Aktualisiert sich bei Kauf, Restore, Login und App-Start von selbst. */
    val state: StateFlow<SubscriptionState> = _state.asStateFlow()

    /** Kurzform für Feature-Gates außerhalb von Compose. */
    val tier: FarmerTier get() = _state.value.tier

    @Volatile
    private var lastCustomerInfo: CustomerInfo? = null

    private val purchases: Purchases get() = Purchases.sharedInstance

    /**
     * Einmal in Application.onCreate() aufrufen.
     *
     * @param apiKey Öffentlicher SDK-Key aus RevenueCat: "goog_…" für Google Play, "test_…" für den Test Store.
     * @param appUserId Eure User-ID, falls der Nutzer schon eingeloggt ist (z. B. die Tuya-UID).
     *   null = anonym; später mit [logIn] verknüpfen.
     */
    fun configure(
        context: Context,
        apiKey: String,
        appUserId: String? = null,
        debugLogs: Boolean = false,
    ) {
        if (Purchases.isConfigured) return
        // Alpha-Builds ohne Key (Secret fehlt): App läuft, Käufe sind aus
        if (apiKey.isBlank()) return
        if (debugLogs) Purchases.logLevel = LogLevel.DEBUG
        Purchases.configure(
            PurchasesConfiguration.Builder(context.applicationContext, apiKey)
                .appUserID(appUserId)
                .build(),
        )
        // Liefert sofort den zwischengespeicherten Status und danach jede Änderung.
        purchases.updatedCustomerInfoListener = UpdatedCustomerInfoListener { publish(it) }
    }

    /**
     * Status neu laden, z. B. per Pull-to-Refresh.
     * @param forceFetch true = Cache umgehen, etwa nachdem ein Team-Platz vergeben oder entzogen wurde.
     */
    suspend fun refresh(forceFetch: Boolean = false): Result<FarmerTier> = revenueCatCall {
        val policy = if (forceFetch) CacheFetchPolicy.FETCH_CURRENT else CacheFetchPolicy.default()
        publish(purchases.awaitCustomerInfo(policy))
        tier
    }

    /**
     * Nach eurem App-Login aufrufen. Käufe hängen dann am Konto statt am Gerät
     * und gelten auf allen Geräten des Nutzers.
     */
    suspend fun logIn(appUserId: String): Result<FarmerTier> = revenueCatCall {
        if (purchases.appUserID != appUserId) {
            publish(purchases.awaitLogIn(appUserId).customerInfo)
        }
        tier
    }

    /** Bei eurem App-Logout aufrufen. */
    suspend fun logOut(): Result<FarmerTier> = revenueCatCall {
        if (!purchases.isAnonymous) {
            publish(purchases.awaitLogOut())
        }
        tier
    }

    /** Alle kaufbaren Pläne von Premium Farmer und Social Club Farmer. */
    suspend fun loadPlans(): Result<List<FarmerPlan>> = revenueCatCall {
        val offerings = purchases.awaitOfferings()
        plansOf(offerings, RevenueCatIds.OFFERING_PREMIUM, FarmerTier.PREMIUM) +
            plansOf(offerings, RevenueCatIds.OFFERING_SOCIAL_CLUB, FarmerTier.SOCIAL_CLUB)
    }

    /**
     * Startet den Kauf über Google Play.
     *
     * Hat der Nutzer schon ein Abo (z. B. Premium, und er wechselt zu Social Club),
     * wird es ersetzt statt doppelt abgerechnet. Die Restlaufzeit wird angerechnet.
     */
    suspend fun purchase(activity: Activity, plan: FarmerPlan): PurchaseOutcome {
        val params = PurchaseParams.Builder(activity, plan.rcPackage)
        val current = activePlaySubscription()
        if (current != null) {
            val currentPlan = "${current.productIdentifier}:${current.productPlanIdentifier}"
            if (currentPlan == plan.rcPackage.product.id) return PurchaseOutcome.AlreadyOwned
            params
                .oldProductId(current.productIdentifier)
                .replacementMode(StoreReplacementMode.WITH_TIME_PRORATION)
        }
        return try {
            publish(purchases.awaitPurchase(params.build()).customerInfo)
            PurchaseOutcome.Success(tier)
        } catch (e: PurchasesTransactionException) {
            if (e.userCancelled) PurchaseOutcome.Cancelled else e.toOutcome()
        } catch (e: PurchasesException) {
            e.toOutcome()
        }
    }

    /** "Käufe wiederherstellen", z. B. nach einer Neuinstallation ohne Login. */
    suspend fun restore(): PurchaseOutcome =
        try {
            publish(purchases.awaitRestore())
            PurchaseOutcome.Success(tier)
        } catch (e: PurchasesException) {
            e.toOutcome()
        }

    private fun publish(customerInfo: CustomerInfo) {
        lastCustomerInfo = customerInfo
        val active = customerInfo.entitlements.active
        val newTier = FarmerTier.fromActiveEntitlements(active.keys)
        val entitlement = newTier.entitlementId?.let { active[it] }
        _state.value = SubscriptionState(
            tier = newTier,
            expirationDate = entitlement?.expirationDate,
            willRenew = entitlement?.willRenew ?: false,
            managementUrl = customerInfo.managementURL,
            // Club-Plätze vergibt das Backend als zeitlich begrenzten Zugang ("Promotional").
            isClubSeat = newTier == FarmerTier.SOCIAL_CLUB && entitlement?.store == Store.PROMOTIONAL,
            isLoading = false,
        )
    }

    /** Das aktive Google-Play-Abo mit der höchsten Stufe, falls vorhanden. */
    private fun activePlaySubscription(): EntitlementInfo? =
        lastCustomerInfo?.entitlements?.active?.values
            ?.filter { it.store == Store.PLAY_STORE }
            ?.maxByOrNull { FarmerTier.forEntitlement(it.identifier)?.ordinal ?: -1 }

    private fun plansOf(offerings: Offerings, offeringId: String, tier: FarmerTier): List<FarmerPlan> =
        offerings[offeringId]?.availablePackages.orEmpty().map { FarmerPlan(tier, it) }

    private fun PurchasesException.toOutcome(): PurchaseOutcome =
        when (code) {
            PurchasesErrorCode.PurchaseCancelledError -> PurchaseOutcome.Cancelled
            PurchasesErrorCode.PaymentPendingError -> PurchaseOutcome.Pending
            PurchasesErrorCode.ProductAlreadyPurchasedError -> PurchaseOutcome.AlreadyOwned
            PurchasesErrorCode.PurchaseNotAllowedError -> PurchaseOutcome.NotAllowed
            PurchasesErrorCode.InsufficientPermissionsError -> PurchaseOutcome.NotAllowed
            PurchasesErrorCode.NetworkError -> PurchaseOutcome.NetworkError
            else -> PurchaseOutcome.Failed(message)
        }

    /** Fängt nur RevenueCat-Fehler ab. Ein Coroutine-Abbruch läuft normal weiter. */
    private inline fun <T> revenueCatCall(block: () -> T): Result<T> =
        if (!Purchases.isConfigured) {
            Result.failure(IllegalStateException("Käufe sind in dieser App-Version nicht eingerichtet."))
        } else {
            try {
                Result.success(block())
            } catch (e: PurchasesException) {
                Result.failure(e)
            }
        }
}
