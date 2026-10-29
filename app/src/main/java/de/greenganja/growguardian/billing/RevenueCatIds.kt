package de.greenganja.growguardian.billing

/**
 * Alle Identifier, die in der Google Play Console und im RevenueCat-Dashboard
 * exakt so angelegt sein müssen (siehe README.md, Abschnitt "Dashboard-Konfiguration").
 *
 * Freemium Farmer braucht keinen Eintrag: Wer kein aktives Entitlement hat, ist Freemium.
 */
object RevenueCatIds {

    // Entitlements = Zugriffsstufen in RevenueCat
    const val ENTITLEMENT_PREMIUM = "premium_farmer"
    const val ENTITLEMENT_SOCIAL_CLUB = "social_club_farmer"

    // Offerings = was der Kaufbildschirm für die jeweilige Stufe anbietet
    const val OFFERING_PREMIUM = "premium_farmer"
    const val OFFERING_SOCIAL_CLUB = "social_club_farmer"

    // Google-Play-Abos (Abo-ID), jeweils mit den Basisplänen "monthly" und "yearly"
    const val PLAY_SUBSCRIPTION_PREMIUM = "premium_farmer"
    const val PLAY_SUBSCRIPTION_SOCIAL_CLUB = "social_club_farmer"
}
