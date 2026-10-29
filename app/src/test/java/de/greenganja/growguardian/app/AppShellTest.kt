package de.greenganja.growguardian.app

import com.revenuecat.purchases.models.Period
import de.greenganja.growguardian.ads.AdPolicy
import de.greenganja.growguardian.billing.FarmerTier
import de.greenganja.growguardian.billing.PurchaseOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppShellTest {
    @Test
    fun `E-Mail-Pruefung und Fehlermeldungen`() {
        assertTrue(SignInRules.isEmail("max@beispiel.de"))
        assertFalse(SignInRules.isEmail("max@beispiel"))
        assertFalse(SignInRules.isEmail("max beispiel.de"))
        assertEquals("E-Mail oder Passwort stimmt nicht.", SignInRules.message("ERROR_WRONG_PASSWORD"))
        assertEquals("Anmelden hat nicht geklappt. Bitte versuch es noch einmal.", SignInRules.message(null))
    }

    @Test
    fun `Kaufseite beschriftet Zeitraeume und Ergebnisse`() {
        assertEquals("pro Monat", PaywallText.period(Period(1, Period.Unit.MONTH, "P1M")))
        assertEquals("pro Jahr", PaywallText.period(Period(1, Period.Unit.YEAR, "P1Y")))
        assertEquals("", PaywallText.period(null))
        assertNull(PaywallText.outcome(PurchaseOutcome.Cancelled))
        assertEquals("Willkommen bei Premium Farmer!", PaywallText.outcome(PurchaseOutcome.Success(FarmerTier.PREMIUM)))
        assertTrue(PaywallText.benefits(FarmerTier.SOCIAL_CLUB).contains("Werbefrei"))
    }

    @Test
    fun `Start-Anzeige nie beim ersten Start`() {
        assertFalse(AdPolicy.showAppOpen(showAds = true, firstLaunch = true))
        assertTrue(AdPolicy.showAppOpen(showAds = true, firstLaunch = false))
        assertFalse(AdPolicy.showAppOpen(showAds = false, firstLaunch = false))
    }
}
