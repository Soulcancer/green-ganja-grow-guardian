package de.greenganja.growguardian.club

import de.greenganja.growguardian.club.ui.MarketFilter
import de.greenganja.growguardian.club.ui.priceLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarketTest {
    private fun gear(type: ListingType, price: Double? = null) = Listing.Gear(
        id = "g", sellerUid = "u", sellerName = "Kim", status = ListingStatus.OPEN, description = "", createdAtMillis = 0,
        type = type, category = ListingCategory.LIGHT, condition = null, title = "LED", priceEur = price,
    )

    private val seeds = Listing.Propagation("p", "staff", "Club", ListingStatus.OPEN, "", 0, Material.SEED, "Haze", 20)

    private fun request(material: Material, count: Int, status: PropagationStatus = PropagationStatus.REQUESTED, month: String = "2026-10") =
        PropagationRequest("r$count$material", "u", "Max M", "Haze", material, count, month, status)

    @Test
    fun `Monatsgrenzen wie im Backend`() {
        assertEquals(7, PropagationLimits.left(Material.SEED, 0, 0))
        assertEquals(5, PropagationLimits.left(Material.CUTTING, 0, 0))
        assertEquals(4, PropagationLimits.left(Material.SEED, 3, 0))
        assertEquals(2, PropagationLimits.left(Material.CUTTING, 3, 0))
        assertEquals(0, PropagationLimits.left(Material.CUTTING, 6, 0))
        assertEquals(1, PropagationLimits.left(Material.SEED, 2, 2))
    }

    @Test
    fun `Gezaehlt werden offene und ausgegebene Anfragen des Monats`() {
        val list = listOf(
            request(Material.SEED, 3),
            request(Material.SEED, 2, PropagationStatus.HANDED_OUT),
            request(Material.CUTTING, 1, PropagationStatus.CANCELLED),
            request(Material.CUTTING, 4, month = "2026-09"),
        )
        assertEquals(5 to 0, PropagationLimits.counted(list, "2026-10"))
    }

    @Test
    fun `Filter und Preise`() {
        assertTrue(MarketFilter.CLUB.matches(seeds))
        assertFalse(MarketFilter.OFFER.matches(seeds))
        assertTrue(MarketFilter.FREE.matches(gear(ListingType.FREE)))
        assertTrue(MarketFilter.ALL.matches(gear(ListingType.SWAP)))
        assertEquals("89 €", priceLabel(gear(ListingType.OFFER, 89.0)))
        assertEquals("12,50 €", priceLabel(gear(ListingType.OFFER, 12.5)))
        assertEquals("Preis auf Anfrage", priceLabel(gear(ListingType.OFFER)))
        assertEquals("Zu verschenken", priceLabel(gear(ListingType.FREE)))
    }
}
