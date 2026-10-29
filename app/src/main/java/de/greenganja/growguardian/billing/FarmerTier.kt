package de.greenganja.growguardian.billing

/**
 * Die drei Benutzergruppen der App.
 *
 * Die Reihenfolge ist der Rang: Jede Stufe enthält alles, was die Stufen davor dürfen.
 * Hat jemand mehrere Entitlements aktiv, gilt die höchste Stufe.
 *
 * Die Werte in [features] sind Platzhalter. Passt sie an euer Pricing an.
 */
enum class FarmerTier(
    val displayName: String,
    /** Das RevenueCat-Entitlement, das diese Stufe freischaltet. Freemium braucht keins. */
    val entitlementId: String?,
    val features: TierFeatures,
) {
    FREEMIUM(
        displayName = "Freemium Farmer",
        entitlementId = null,
        features = TierFeatures(
            maxGrowBoxes = 1,
            maxDevices = 5,
            historyDays = 7,
            alerts = false,
            automations = false,
            maxAutomations = 0,
            dataExport = false,
            seats = 1,
        ),
    ),
    PREMIUM(
        displayName = "Premium Farmer",
        entitlementId = RevenueCatIds.ENTITLEMENT_PREMIUM,
        features = TierFeatures(
            maxGrowBoxes = 3,
            maxDevices = 30,
            historyDays = 365,
            alerts = true,
            automations = true,
            maxAutomations = 20,
            dataExport = true,
            seats = 1,
        ),
    ),
    SOCIAL_CLUB(
        displayName = "Social Club Farmer",
        entitlementId = RevenueCatIds.ENTITLEMENT_SOCIAL_CLUB,
        features = TierFeatures(
            maxGrowBoxes = null,
            maxDevices = null,
            historyDays = null,
            alerts = true,
            automations = true,
            maxAutomations = null,
            dataExport = true,
            // 5 festgelegte Personen pro Club-Abo, der Käufer zählt mit.
            // Muss mit SEATS_PER_CLUB im Backend übereinstimmen.
            seats = 5,
        ),
    );

    fun isAtLeast(other: FarmerTier): Boolean = ordinal >= other.ordinal

    companion object {
        /** Höchste Stufe, deren Entitlement aktiv ist. Kein aktives Entitlement = Freemium. */
        fun fromActiveEntitlements(activeEntitlementIds: Set<String>): FarmerTier =
            entries.lastOrNull { tier ->
                tier.entitlementId != null && tier.entitlementId in activeEntitlementIds
            } ?: FREEMIUM

        /** Stufe zu einer Entitlement-ID, null wenn die ID keiner Stufe gehört. */
        fun forEntitlement(entitlementId: String): FarmerTier? =
            entries.firstOrNull { it.entitlementId == entitlementId }
    }
}

/**
 * Was eine Stufe darf. `null` bei einem Limit bedeutet unbegrenzt.
 */
data class TierFeatures(
    val maxGrowBoxes: Int?,
    val maxDevices: Int?,
    /** Wie viele Tage Messwerte im Verlauf sichtbar bleiben. */
    val historyDays: Int?,
    /** Benachrichtigung, wenn ein Wert aus dem Zielbereich läuft oder ein Sensor offline geht. */
    val alerts: Boolean,
    /** Regeln wie "Entfeuchter an über 60 % Luftfeuchte" oder "Licht 18/6". */
    val automations: Boolean,
    /** Höchstzahl Regeln, null = unbegrenzt. Muss zu MAX_AUTOMATIONS im Backend passen. */
    val maxAutomations: Int? = 0,
    /** Export von Messwerten und Grow-Tagebuch (CSV/PDF). */
    val dataExport: Boolean,
    /** Personen, die ein Abo dieser Stufe nutzen dürfen, der Käufer eingeschlossen. */
    val seats: Int,
) {
    /** Mehrere Personen teilen sich Räume und Geräte. */
    val teamAccess: Boolean get() = seats > 1

    fun canAddGrowBox(currentCount: Int): Boolean = maxGrowBoxes == null || currentCount < maxGrowBoxes

    fun canAddDevice(currentCount: Int): Boolean = maxDevices == null || currentCount < maxDevices
}
