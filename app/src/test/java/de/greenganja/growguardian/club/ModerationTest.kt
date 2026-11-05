package de.greenganja.growguardian.club

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModerationTest {
    private val day = 24L * 60 * 60 * 1000
    private val date: (Long) -> String = { "D$it" }

    @Test
    fun `Rollen trennen Team und Moderation`() {
        val moderator = Membership("c", "Club", ClubRole.MODERATOR, MemberStatus.ACTIVE)
        assertFalse(moderator.isStaff)
        assertTrue(moderator.isModerator && moderator.canModerate)
        assertTrue(Membership("c", "Club", ClubRole.STAFF, MemberStatus.ACTIVE).canModerate)
        assertFalse(Membership("c", "Club", ClubRole.MEMBER, MemberStatus.ACTIVE).canModerate)
        assertFalse(Membership("c", "Club", ClubRole.MODERATOR, MemberStatus.PENDING).canModerate)
        assertEquals(ClubRole.MODERATOR, ClubRole.from("moderator"))
    }

    @Test
    fun `Rechte nur nach unten`() {
        assertTrue(ModerationRules.mayActOn("m", ClubRole.MODERATOR, "a", ClubRole.MEMBER))
        assertFalse(ModerationRules.mayActOn("m", ClubRole.MODERATOR, "m2", ClubRole.MODERATOR))
        assertFalse(ModerationRules.mayActOn("m", ClubRole.MODERATOR, "s", ClubRole.STAFF))
        assertTrue(ModerationRules.mayActOn("s", ClubRole.STAFF, "m", ClubRole.MODERATOR))
        assertFalse(ModerationRules.mayActOn("s", ClubRole.STAFF, "s", ClubRole.MEMBER))
        assertFalse(ModerationRules.canAppoint(ClubRole.MODERATOR))
        assertTrue(ModerationRules.canAppoint(ClubRole.STAFF))
    }

    @Test
    fun `Eingaben pruefen`() {
        assertFalse(ModerationRules.reasonValid(" ok "))
        assertTrue(ModerationRules.reasonValid("Spam im Marktplatz"))
        assertFalse(ModerationRules.reasonValid("x".repeat(301)))
        assertNull(ModerationRules.announcementError("Ausgabe Samstag", "Fällt aus"))
        assertTrue(ModerationRules.announcementError("Hi", "Text") != null)
        assertTrue(ModerationRules.announcementError("Titel", "  ") != null)
        assertEquals("1 Tag", ModerationRules.blockLabel(1))
        assertEquals("7 Tage", ModerationRules.blockLabel(7))
    }

    @Test
    fun `Ankuendigungen angeheftet zuerst ohne abgelaufene`() {
        val now = 100 * day
        val list = listOf(
            Announcement("a", "Alt", "", pinned = false, byName = "", at = 1 * day, expiresAt = now + day),
            Announcement("b", "Neu", "", pinned = false, byName = "", at = 5 * day, expiresAt = now + day),
            Announcement("c", "Wichtig", "", pinned = true, byName = "", at = 2 * day, expiresAt = now + day),
            Announcement("d", "Weg", "", pinned = true, byName = "", at = 9 * day, expiresAt = now - 1),
        )
        assertEquals(listOf("c", "b", "a"), ModerationRules.arrange(list, now).map { it.id })
    }

    @Test
    fun `eigene Hinweise der letzten 90 Tage`() {
        val now = 200 * day
        val notices = listOf(
            ModNotice("old", ModAction.WARN, "Mod", null, "alt", null, now - 91 * day),
            ModNotice("warn", ModAction.WARN, "Mod", null, "Spam", null, now - 2 * day),
            ModNotice("block", ModAction.BLOCK, "Mod", null, "Spam", now + 5 * day, now - day),
        )
        assertEquals(listOf("block", "warn"), ModerationRules.recentNotices(notices, now).map { it.id })
        val block = notices[2]
        assertEquals("Im Marktplatz gesperrt bis D${now + 5 * day}", ModerationText.noticeTitle(block, now, date))
        assertEquals("Marktplatz-Sperre (abgelaufen)", ModerationText.noticeTitle(block, now + 6 * day, date))
        assertEquals("Inserat entfernt: „Zelt“", ModerationText.noticeTitle(ModNotice("r", ModAction.REMOVE, "", "Zelt", null, null, null), now, date))
    }

    @Test
    fun `Protokoll lesbar`() {
        val entry = ModLogEntry("1", ModAction.BLOCK, "Anna", "Max", null, "Spam", 7L, 1L)
        assertEquals("Anna hat Max im Marktplatz gesperrt bis D7", ModerationText.logLine(entry, date))
        assertEquals("Club-Team hat „Lampe“ entfernt", ModerationText.logLine(ModLogEntry("2", ModAction.REMOVE, "", null, "Lampe", null, null, null), date))
        assertEquals("Anna hat etwas geändert", ModerationText.logLine(entry.copy(action = null), date))
    }

    @Test
    fun `Antwort des Backends lesen`() {
        val data = mapOf<String, Any?>(
            "role" to "moderator",
            "members" to listOf(
                mapOf("uid" to "u1", "name" to "Max M.", "role" to "member", "warnings" to 2, "blockedUntil" to 5000L, "blockReason" to "Spam", "mayAct" to true),
                mapOf("name" to "ohne uid"),
            ),
            "reports" to listOf(mapOf("listingId" to "l1", "kind" to "gear", "title" to "Zelt", "sellerName" to "Max M.", "reports" to 3, "reasons" to listOf("Gras", null), "mayRemove" to true)),
            "announcements" to listOf(mapOf("id" to "n1", "title" to "Treffen", "text" to "Freitag", "pinned" to true, "byName" to "Anna", "at" to 10L)),
            "log" to listOf(mapOf("id" to "g1", "action" to "warn", "byName" to "Anna", "targetName" to "Max M.", "at" to 9L)),
        )
        val overview = ModerationParser.overview(data)
        assertEquals(ClubRole.MODERATOR, overview.role)
        assertEquals(1, overview.members.size)
        assertEquals(ModMember("u1", "Max M.", ClubRole.MEMBER, 2, 5000L, "Spam", true), overview.members[0])
        assertEquals(listOf("Gras"), overview.reports[0].reasons)
        assertTrue(overview.reports[0].isGear && overview.reports[0].mayRemove)
        assertTrue(overview.announcements[0].pinned)
        assertEquals(ModAction.WARN, overview.log[0].action)
        assertEquals(ClubRole.MODERATOR, ModerationParser.overview(emptyMap()).role)
    }
}
