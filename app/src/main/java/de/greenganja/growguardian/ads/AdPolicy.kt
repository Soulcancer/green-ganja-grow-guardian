package de.greenganja.growguardian.ads

import de.greenganja.growguardian.billing.FarmerTier

/**
 * Wer Werbung sieht: Freemium und Premium. Social Club ist werbefrei, ebenso wer mit Coins „werbefrei“
 * eingelöst hat. Ohne Einwilligungsabfrage (UMP) oder vor deren Ergebnis zeigt die App keine Anzeigen.
 */
object AdPolicy {
    fun showAds(tier: FarmerTier, adFreeUntil: Long, canRequestAds: Boolean, now: Long): Boolean =
        canRequestAds && tier != FarmerTier.SOCIAL_CLUB && now >= adFreeUntil

    /** Mindestabstand zwischen Banner und Bedienelementen, gegen versehentliche Klicks (AdMob-Richtlinie). */
    const val SPACING_DP = 16

    /** Längstes Warten auf die Start-Anzeige; danach geht es ohne weiter. */
    const val APP_OPEN_WAIT_MS = 4_000L

    /** Start-Anzeige: nicht beim allerersten Start und nur, wenn auch sonst Werbung erscheint. */
    fun showAppOpen(showAds: Boolean, firstLaunch: Boolean): Boolean = showAds && !firstLaunch

    /** Banner im Inhalt erst nach so vielen Einträgen, damit Inhalt immer vor Werbung kommt. */
    const val FIRST_INLINE_AFTER = 2
}

/**
 * Anzeigenblock-IDs aus AdMob. Standardmäßig die offiziellen Test-IDs von Google, damit Entwicklung und
 * Prüfung nie echte Anzeigen anklicken. Für den Release in `local.properties` bzw. als Gradle-Property setzen
 * (siehe app/build.gradle.kts), sie landen dann in BuildConfig.
 */
data class AdUnits(
    val anchoredBanner: String = TEST_BANNER,
    val inlineBanner: String = TEST_BANNER,
    val rewarded: String = TEST_REWARDED,
    val appOpen: String = TEST_APP_OPEN,
) {
    companion object {
        const val TEST_APP_OPEN = "ca-app-pub-3940256099942544/9257395921"
        const val TEST_BANNER = "ca-app-pub-3940256099942544/9214589741"
        const val TEST_REWARDED = "ca-app-pub-3940256099942544/5224354917"
    }
}
