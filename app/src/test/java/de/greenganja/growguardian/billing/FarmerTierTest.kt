package de.greenganja.growguardian.billing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FarmerTierTest {

    @Test
    fun `ohne Entitlement ist man Freemium Farmer`() {
        assertEquals(FarmerTier.FREEMIUM, FarmerTier.fromActiveEntitlements(emptySet()))
    }

    @Test
    fun `Premium-Entitlement ergibt Premium Farmer`() {
        val tier = FarmerTier.fromActiveEntitlements(setOf(RevenueCatIds.ENTITLEMENT_PREMIUM))
        assertEquals(FarmerTier.PREMIUM, tier)
    }

    @Test
    fun `Social-Club-Entitlement ergibt Social Club Farmer`() {
        val tier = FarmerTier.fromActiveEntitlements(setOf(RevenueCatIds.ENTITLEMENT_SOCIAL_CLUB))
        assertEquals(FarmerTier.SOCIAL_CLUB, tier)
    }

    @Test
    fun `bei beiden Entitlements gilt die hoehere Stufe`() {
        val tier = FarmerTier.fromActiveEntitlements(
            setOf(RevenueCatIds.ENTITLEMENT_PREMIUM, RevenueCatIds.ENTITLEMENT_SOCIAL_CLUB),
        )
        assertEquals(FarmerTier.SOCIAL_CLUB, tier)
    }

    @Test
    fun `unbekannte Entitlements werden ignoriert`() {
        assertEquals(FarmerTier.FREEMIUM, FarmerTier.fromActiveEntitlements(setOf("pro_legacy")))
    }

    @Test
    fun `Limits greifen und null heisst unbegrenzt`() {
        assertTrue(FarmerTier.FREEMIUM.features.canAddGrowBox(currentCount = 0))
        assertFalse(FarmerTier.FREEMIUM.features.canAddGrowBox(currentCount = 1))
        assertTrue(FarmerTier.SOCIAL_CLUB.features.canAddGrowBox(currentCount = 10_000))
    }

    @Test
    fun `Social Club hat 5 Plaetze, die anderen Stufen einen`() {
        assertEquals(5, FarmerTier.SOCIAL_CLUB.features.seats)
        assertTrue(FarmerTier.SOCIAL_CLUB.features.teamAccess)
        assertEquals(1, FarmerTier.PREMIUM.features.seats)
        assertFalse(FarmerTier.PREMIUM.features.teamAccess)
    }

    @Test
    fun `hoehere Stufen enthalten die niedrigeren`() {
        assertTrue(FarmerTier.SOCIAL_CLUB.isAtLeast(FarmerTier.PREMIUM))
        assertTrue(FarmerTier.PREMIUM.isAtLeast(FarmerTier.PREMIUM))
        assertFalse(FarmerTier.FREEMIUM.isAtLeast(FarmerTier.PREMIUM))
    }
}
