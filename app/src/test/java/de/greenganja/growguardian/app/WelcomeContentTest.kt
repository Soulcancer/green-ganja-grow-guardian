package de.greenganja.growguardian.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WelcomeContentTest {
    @Test
    fun `Startseite erklaert und warnt`() {
        assertEquals(6, WelcomeContent.features.size)
        assertEquals(3, WelcomeContent.tiers.size)
        val text = WelcomeContent.disclaimer.joinToString(" ")
        listOf("18 Jahren", "§ 9 KCanG", "drei Pflanzen", "50 g", "weitergegeben", "Kinder", "Keine Rechts- oder Medizinberatung").forEach {
            assertTrue(it, text.contains(it))
        }
    }

    @Test
    fun `weiter nur mit beiden Bestaetigungen`() {
        assertTrue(WelcomeContent.canContinue(adult = true, read = true))
        assertFalse(WelcomeContent.canContinue(adult = true, read = false))
        assertFalse(WelcomeContent.canContinue(adult = false, read = true))
    }
}
