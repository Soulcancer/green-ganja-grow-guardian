package de.greenganja.growguardian.rewards

import de.greenganja.growguardian.ads.AdPolicy
import de.greenganja.growguardian.billing.FarmerTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RewardsTest {
    private val now = 1_800_000_000_000L

    @Test
    fun `Werbung nur fuer Freemium und Premium, mit Einwilligung, ohne Coins-Pause`() {
        assertTrue(AdPolicy.showAds(FarmerTier.FREEMIUM, 0, canRequestAds = true, now = now))
        assertTrue(AdPolicy.showAds(FarmerTier.PREMIUM, 0, canRequestAds = true, now = now))
        assertFalse(AdPolicy.showAds(FarmerTier.SOCIAL_CLUB, 0, canRequestAds = true, now = now))
        assertFalse(AdPolicy.showAds(FarmerTier.FREEMIUM, 0, canRequestAds = false, now = now))
        assertFalse(AdPolicy.showAds(FarmerTier.FREEMIUM, now + 1000, canRequestAds = true, now = now))
        assertTrue(AdPolicy.showAds(FarmerTier.FREEMIUM, now - 1, canRequestAds = true, now = now))
    }

    @Test
    fun `Serie und Tagesbelohnung wie im Backend`() {
        assertEquals(listOf(5, 6, 7, 8, 10, 12, 20, 5), (1..8).map(Rewards::dailyReward))
        val wallet = Wallet(streak = 3, lastCheckIn = "2026-10-28")
        assertEquals(4, Rewards.nextStreak(wallet, today = "2026-10-29", yesterday = "2026-10-28"))
        assertEquals(1, Rewards.nextStreak(wallet, today = "2026-10-30", yesterday = "2026-10-29"))
        assertEquals(3, Rewards.nextStreak(wallet.copy(lastCheckIn = "2026-10-29"), today = "2026-10-29", yesterday = "2026-10-28"))
    }

    @Test
    fun `Videos pro Tag`() {
        val wallet = Wallet(rewardedDay = "2026-10-29", rewardedCount = 3)
        assertEquals(2, wallet.videosLeft("2026-10-29"))
        assertEquals(5, wallet.videosLeft("2026-10-30"))
    }

    @Test
    fun `Coins schalten Alarme fuer Freemium frei, auf Zeit`() {
        val free = FarmerTier.FREEMIUM.features
        assertFalse(free.alerts)
        assertTrue(Rewards.effectiveFeatures(free, Wallet(alertsUntil = now + 1), now).alerts)
        assertFalse(Rewards.effectiveFeatures(free, Wallet(alertsUntil = now - 1), now).alerts)
        assertFalse(Rewards.effectiveFeatures(free, null, now).alerts)
        assertTrue(Rewards.effectiveFeatures(FarmerTier.PREMIUM.features, null, now).alerts)
    }

    @Test
    fun `Shop zeigt nur Sinnvolles`() {
        val free = RewardsUiState(tier = FarmerTier.FREEMIUM)
        assertTrue(free.offered(Rewards.ShopItem.ALERTS_3D))
        assertTrue(free.offered(Rewards.ShopItem.AD_FREE_1D))
        val premium = RewardsUiState(tier = FarmerTier.PREMIUM)
        assertFalse(premium.offered(Rewards.ShopItem.ALERTS_3D))
        assertTrue(premium.offered(Rewards.ShopItem.AD_FREE_7D))
        val club = RewardsUiState(tier = FarmerTier.SOCIAL_CLUB)
        assertFalse(club.offered(Rewards.ShopItem.AD_FREE_1D))
        assertFalse(club.offered(Rewards.ShopItem.BOOST_LISTING))
    }
}
