package de.greenganja.growguardian.ui.wizard

import de.greenganja.growguardian.ads.AdPolicy
import de.greenganja.growguardian.app.VerifyRules
import de.greenganja.growguardian.club.ClubRole
import de.greenganja.growguardian.club.MemberStatus
import de.greenganja.growguardian.club.Membership
import de.greenganja.growguardian.club.ui.ClubWizardContent
import de.greenganja.growguardian.club.ui.ClubWizardRole
import de.greenganja.growguardian.grow.GrowBox
import de.greenganja.growguardian.grow.GrowBoxState
import de.greenganja.growguardian.grow.light.LampUse
import de.greenganja.growguardian.grow.light.LightSetup
import de.greenganja.growguardian.grow.plants.Plant
import de.greenganja.growguardian.grow.ui.AppWizardContent
import de.greenganja.growguardian.grow.ui.AppWizardMode
import de.greenganja.growguardian.grow.ui.BoxItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WizardTest {
    private fun item(box: GrowBox, plants: List<Plant> = emptyList()) =
        BoxItem(GrowBoxState.of(box, emptyMap()), locked = false, plants = plants, plantsLoaded = true)

    @Test
    fun `Blaettern und Fortschritt`() {
        assertEquals(0, WizardFlow.clamp(-3, 5))
        assertEquals(4, WizardFlow.clamp(9, 5))
        assertEquals("Schritt 2 von 5", WizardFlow.label(1, 5))
        assertEquals(1.0, WizardFlow.progress(4, 5).toDouble(), 0.0001)
        assertTrue(WizardFlow.isLast(4, 5))
        val pages = listOf(WizardPage("a", "A", "", done = true), WizardPage("b", "B", ""), WizardPage("c", "C", "", done = false))
        assertEquals(2, WizardFlow.firstOpen(pages))
        assertEquals(1, WizardFlow.doneCount(pages))
        assertEquals(2, WizardFlow.taskCount(pages))
    }

    @Test
    fun `App-Einfuehrung ohne Club, Box-Einrichtung als Teil`() {
        val full = AppWizardContent.pages(AppWizardMode.FULL, null, alertsAllowed = false, automationsAllowed = false, allLampTypes = false)
        assertEquals(listOf("welcome", "box", "plants", "light", "sensors", "targets", "alerts", "analysis", "coins", "account", "done"), full.map { it.key })
        assertTrue(full.none { it.key.startsWith("club") })
        assertFalse(full.first { it.key == "box" }.done!!)

        val box = GrowBox(id = "b", name = "Zelt", deviceIds = listOf("d1"), light = LightSetup(listOf(LampUse("daylight"))))
        val setup = AppWizardContent.pages(AppWizardMode.BOX, item(box, listOf(Plant(name = "P1"))), alertsAllowed = true, automationsAllowed = true, allLampTypes = true)
        assertEquals(listOf("plants", "light", "sensors", "targets", "alerts", "done"), setup.map { it.key })
        assertEquals(0, WizardFlow.firstOpen(setup)) // alles erledigt: von vorn
        assertEquals(4 to 4, AppWizardContent.boxProgress(item(box, listOf(Plant(name = "P1"))), alertsAllowed = true))
        assertEquals(1 to 3, AppWizardContent.boxProgress(item(box.copy(deviceIds = emptyList())), alertsAllowed = false))
        assertEquals(2, WizardFlow.firstOpen(AppWizardContent.pages(AppWizardMode.BOX, item(box.copy(deviceIds = emptyList()), listOf(Plant(name = "P1"))), false, false, false)))
    }

    @Test
    fun `Club-Einfuehrung je Rolle`() {
        fun m(role: ClubRole, status: MemberStatus = MemberStatus.ACTIVE) = Membership("c", "Club", role, status)
        assertEquals(ClubWizardRole.NONE, ClubWizardRole.of(null))
        assertEquals(ClubWizardRole.PENDING, ClubWizardRole.of(m(ClubRole.MEMBER, MemberStatus.PENDING)))
        assertEquals(ClubWizardRole.OWNER, ClubWizardRole.of(m(ClubRole.OWNER)))
        assertEquals(ClubWizardRole.STAFF, ClubWizardRole.of(m(ClubRole.STAFF)))
        assertEquals(ClubWizardRole.MEMBER, ClubWizardRole.of(m(ClubRole.MEMBER)))

        val member = ClubWizardContent.pages(ClubWizardRole.MEMBER, dispensing = true).map { it.key }
        assertTrue("club-reserve" in member)
        assertFalse("club-reserve" in ClubWizardContent.pages(ClubWizardRole.MEMBER, dispensing = false).map { it.key })
        assertTrue("club-seats" in ClubWizardContent.pages(ClubWizardRole.OWNER, true).map { it.key })
        assertFalse("club-seats" in ClubWizardContent.pages(ClubWizardRole.STAFF, true).map { it.key })
        ClubWizardRole.entries.forEach { role -> assertEquals("club-done", ClubWizardContent.pages(role, true).last().key) }
        val reserve = ClubWizardContent.pages(ClubWizardRole.MEMBER, true).first { it.key == "club-reserve" }.bullets.joinToString()
        assertTrue(reserve.contains("25 g") && reserve.contains("50 g") && reserve.contains("30 g") && reserve.contains("10 % THC"))
    }

    @Test
    fun `Werbung in Einfuehrungen und Bestaetigungs-Mail`() {
        assertTrue(AdPolicy.showInWizard(canRequestAds = true))
        assertFalse(AdPolicy.showInWizard(canRequestAds = false))
        assertEquals(60, VerifyRules.secondsLeft(1_000, 1_000))
        assertEquals(0, VerifyRules.secondsLeft(1_000, 70_000))
        assertTrue(VerifyRules.sendError("ERROR_TOO_MANY_REQUESTS").contains("warte"))
    }
}
