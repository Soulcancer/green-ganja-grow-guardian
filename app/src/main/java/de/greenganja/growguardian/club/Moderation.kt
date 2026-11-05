package de.greenganja.growguardian.club

import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import de.greenganja.growguardian.backend.Backend
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOf

/**
 * Regeln der Club-Moderation für Hinweise in der App. Verbindlich prüft das Backend (rules/moderation.ts).
 *
 * Moderatoren sind aktive Mitglieder, die Inhaber oder Team ernennen. Sie belegen keinen der 5 Team-Plätze und
 * sehen keine Personendaten (vollen Namen, Geburtsdatum), nur die Anzeigenamen aus dem Marktplatz.
 */
object ModerationRules {
    const val MAX_MODERATORS = 10
    val BLOCK_DAYS = listOf(1, 3, 7, 30)
    const val REASON_MIN = 3
    const val REASON_MAX = 300
    const val TITLE_MIN = 3
    const val TITLE_MAX = 60
    const val TEXT_MAX = 1000
    const val MAX_PINNED = 3

    /** Wie lange die eigenen Hinweise vom Club angezeigt werden. */
    const val NOTICE_DAYS = 90

    fun rank(role: ClubRole): Int = when (role) {
        ClubRole.MEMBER -> 0
        ClubRole.MODERATOR -> 1
        ClubRole.STAFF -> 2
        ClubRole.OWNER -> 3
    }

    fun canModerate(role: ClubRole): Boolean = rank(role) >= 1

    /** Moderatoren ernennen und abberufen: Team und Inhaber. */
    fun canAppoint(role: ClubRole): Boolean = rank(role) >= 2

    /** Verwarnen und sperren nur nach unten, nie sich selbst. */
    fun mayActOn(actorUid: String, actorRole: ClubRole, targetUid: String, targetRole: ClubRole): Boolean =
        actorUid != targetUid && canModerate(actorRole) && rank(targetRole) < rank(actorRole)

    /** Moderatoren entfernen nur Zubehör; Club-Angebote (Samen, Stecklinge) gehören dem Team. */
    fun mayRemove(role: ClubRole, listing: Listing): Boolean =
        rank(role) >= 2 || (role == ClubRole.MODERATOR && listing is Listing.Gear)

    fun reasonValid(text: String): Boolean = text.trim().length in REASON_MIN..REASON_MAX

    /** Fehlermeldung für eine Ankündigung oder null. */
    fun announcementError(title: String, text: String): String? = when {
        title.trim().length !in TITLE_MIN..TITLE_MAX -> "Der Titel muss $TITLE_MIN bis $TITLE_MAX Zeichen haben."
        text.isBlank() || text.trim().length > TEXT_MAX -> "Der Text muss 1 bis $TEXT_MAX Zeichen haben."
        else -> null
    }

    fun blockLabel(days: Int): String = if (days == 1) "1 Tag" else "$days Tage"

    fun isBlocked(until: Long?, now: Long): Boolean = until != null && until > now

    /** Eigene Hinweise der letzten [NOTICE_DAYS] Tage, neueste zuerst. */
    fun recentNotices(notices: List<ModNotice>, now: Long): List<ModNotice> {
        val from = now - NOTICE_DAYS * 24L * 60 * 60 * 1000
        return notices.filter { (it.at ?: now) >= from }.sortedByDescending { it.at ?: now }
    }

    /** Ankündigungen: angeheftete zuerst, dann neueste; abgelaufene fallen weg (TTL löscht verzögert). */
    fun arrange(announcements: List<Announcement>, now: Long): List<Announcement> =
        announcements
            .filter { it.expiresAt == null || it.expiresAt > now }
            .sortedWith(compareByDescending<Announcement> { it.pinned }.thenByDescending { it.at ?: 0L })
}

enum class ModAction(val id: String) {
    APPOINT("appoint"),
    DISMISS("dismiss"),
    WARN("warn"),
    BLOCK("block"),
    UNBLOCK("unblock"),
    RESTORE("restore"),
    REMOVE("remove"),
    ANNOUNCE("announce"),
    UNANNOUNCE("unannounce"),
    ;

    companion object {
        fun from(id: String?): ModAction? = entries.firstOrNull { it.id == id }
    }
}

/** Mitglied aus Sicht der Moderation: nur Anzeigename, Rolle, Verwarnungen und Sperre. */
data class ModMember(
    val uid: String,
    val name: String,
    val role: ClubRole,
    val warnings: Int,
    val blockedUntil: Long?,
    val blockReason: String?,
    /** Die eigene Rolle steht über dieser Person. */
    val mayAct: Boolean,
)

data class ReportedListing(
    val listingId: String,
    val isGear: Boolean,
    val title: String,
    val sellerName: String,
    val reports: Int,
    val reasons: List<String>,
    val mayRemove: Boolean,
)

data class Announcement(
    val id: String,
    val title: String,
    val text: String,
    val pinned: Boolean,
    val byName: String,
    val at: Long?,
    val expiresAt: Long? = null,
)

data class ModLogEntry(
    val id: String,
    val action: ModAction?,
    val byName: String,
    val targetName: String?,
    val listingTitle: String?,
    val reason: String?,
    val until: Long?,
    val at: Long?,
)

data class ModerationOverview(
    val role: ClubRole,
    val members: List<ModMember>,
    val reports: List<ReportedListing>,
    val announcements: List<Announcement>,
    val log: List<ModLogEntry>,
)

/** Eintrag über einen selbst: Verwarnung, Sperre, entferntes Inserat. Sichtbar unter „Hinweise vom Club“. */
data class ModNotice(
    val id: String,
    val action: ModAction,
    val byName: String,
    val listingTitle: String?,
    val reason: String?,
    val until: Long?,
    val at: Long?,
)

/** Antworten des Backends lesen, ohne Android (testbar). */
object ModerationParser {
    private fun Map<*, *>.str(key: String): String? = this[key] as? String
    private fun Map<*, *>.long(key: String): Long? = (this[key] as? Number)?.toLong()
    private fun Map<*, *>.list(key: String): List<Map<*, *>> = (this[key] as? List<*>).orEmpty().filterIsInstance<Map<*, *>>()

    fun overview(data: Map<String, Any?>): ModerationOverview = ModerationOverview(
        role = ClubRole.from(data["role"] as? String) ?: ClubRole.MODERATOR,
        members = data.list("members").mapNotNull { m ->
            ModMember(
                uid = m.str("uid") ?: return@mapNotNull null,
                name = m.str("name").orEmpty().ifBlank { "Mitglied" },
                role = ClubRole.from(m.str("role")) ?: ClubRole.MEMBER,
                warnings = m.long("warnings")?.toInt() ?: 0,
                blockedUntil = m.long("blockedUntil"),
                blockReason = m.str("blockReason"),
                mayAct = m["mayAct"] == true,
            )
        },
        reports = data.list("reports").mapNotNull { r ->
            ReportedListing(
                listingId = r.str("listingId") ?: return@mapNotNull null,
                isGear = r.str("kind") == "gear",
                title = r.str("title").orEmpty(),
                sellerName = r.str("sellerName").orEmpty(),
                reports = r.long("reports")?.toInt() ?: 0,
                reasons = (r["reasons"] as? List<*>).orEmpty().filterIsInstance<String>(),
                mayRemove = r["mayRemove"] == true,
            )
        },
        announcements = data.list("announcements").mapNotNull { a ->
            Announcement(
                id = a.str("id") ?: return@mapNotNull null,
                title = a.str("title").orEmpty(),
                text = a.str("text").orEmpty(),
                pinned = a["pinned"] == true,
                byName = a.str("byName").orEmpty(),
                at = a.long("at"),
            )
        },
        log = data.list("log").mapNotNull { e ->
            ModLogEntry(
                id = e.str("id") ?: return@mapNotNull null,
                action = ModAction.from(e.str("action")),
                byName = e.str("byName").orEmpty(),
                targetName = e.str("targetName"),
                listingTitle = e.str("listingTitle"),
                reason = e.str("reason"),
                until = e.long("until"),
                at = e.long("at"),
            )
        },
    )
}

/** Texte der Moderation, ohne Android (testbar). [date] formatiert Millisekunden als Datum. */
object ModerationText {
    fun logLine(entry: ModLogEntry, date: (Long) -> String): String {
        val who = entry.byName.ifBlank { "Club-Team" }
        val target = entry.targetName ?: "jemanden"
        val listing = entry.listingTitle?.let { "„$it“" } ?: "ein Inserat"
        return when (entry.action) {
            ModAction.APPOINT -> "$who hat $target zum Moderator ernannt"
            ModAction.DISMISS -> "$who hat $target als Moderator abberufen"
            ModAction.WARN -> "$who hat $target verwarnt"
            ModAction.BLOCK -> "$who hat $target im Marktplatz gesperrt" + (entry.until?.let { " bis ${date(it)}" } ?: "")
            ModAction.UNBLOCK -> "$who hat die Sperre von $target aufgehoben"
            ModAction.RESTORE -> "$who hat $listing wieder freigegeben"
            ModAction.REMOVE -> "$who hat $listing entfernt"
            ModAction.ANNOUNCE -> "$who hat eine Ankündigung veröffentlicht"
            ModAction.UNANNOUNCE -> "$who hat eine Ankündigung gelöscht"
            null -> "$who hat etwas geändert"
        }
    }

    /** Was das betroffene Mitglied liest. */
    fun noticeTitle(notice: ModNotice, now: Long, date: (Long) -> String): String = when (notice.action) {
        ModAction.WARN -> "Verwarnung"
        ModAction.BLOCK -> if (ModerationRules.isBlocked(notice.until, now)) {
            "Im Marktplatz gesperrt bis ${notice.until?.let(date).orEmpty()}"
        } else {
            "Marktplatz-Sperre (abgelaufen)"
        }
        ModAction.UNBLOCK -> "Marktplatz-Sperre aufgehoben"
        ModAction.REMOVE -> "Inserat entfernt" + (notice.listingTitle?.let { ": „$it“" } ?: "")
        ModAction.RESTORE -> "Inserat wieder freigegeben" + (notice.listingTitle?.let { ": „$it“" } ?: "")
        ModAction.APPOINT -> "Du bist jetzt Moderator"
        ModAction.DISMISS -> "Du bist nicht mehr Moderator"
        ModAction.ANNOUNCE, ModAction.UNANNOUNCE -> "Hinweis vom Club"
    }
}

/**
 * Moderation über die Cloud Functions; Ankündigungen und eigene Hinweise live aus Firestore
 * (die Regeln zeigen jedem Mitglied nur die Einträge über sich).
 */
class ModerationRepository(
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val backend: Backend = Backend(),
) {
    private fun club(clubId: String) = db.collection("clubs").document(clubId)

    /** Ankündigungen des Clubs, für alle aktiven Mitglieder. */
    fun announcements(clubId: String): Flow<List<Announcement>> =
        club(clubId).collection("announcements")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(20)
            .live { it.toAnnouncement() }

    /** Einträge über die angemeldete Person. */
    fun myNotices(clubId: String, uid: String?): Flow<List<ModNotice>> {
        if (uid == null) return flowOf(emptyList())
        return club(clubId).collection("modLog")
            .whereEqualTo("targetUid", uid)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(20)
            .live { it.toNotice() }
    }

    suspend fun overview(): Result<ModerationOverview> =
        backend.call("moderationOverview").map(ModerationParser::overview)

    suspend fun setModerator(memberUid: String, moderator: Boolean): Result<Unit> =
        backend.call("setModerator", mapOf("uid" to memberUid, "moderator" to moderator)).map { }

    suspend fun warn(memberUid: String, reason: String): Result<Unit> =
        backend.call("warnMember", mapOf("uid" to memberUid, "reason" to reason)).map { }

    /** @return Ende der Sperre in Millisekunden */
    suspend fun block(memberUid: String, days: Int, reason: String): Result<Long> =
        backend.call("blockMarket", mapOf("uid" to memberUid, "days" to days, "reason" to reason))
            .map { (it["until"] as? Number)?.toLong() ?: 0L }

    suspend fun unblock(memberUid: String): Result<Unit> =
        backend.call("unblockMarket", mapOf("uid" to memberUid)).map { }

    suspend fun decideReport(listingId: String, restore: Boolean, reason: String? = null): Result<Unit> =
        backend.call(
            "decideReport",
            mapOf("listingId" to listingId, "decision" to if (restore) "restore" else "remove", "reason" to reason),
        ).map { }

    suspend fun postAnnouncement(title: String, text: String, pinned: Boolean): Result<Unit> =
        backend.call("postAnnouncement", mapOf("title" to title, "text" to text, "pinned" to pinned)).map { }

    suspend fun deleteAnnouncement(id: String): Result<Unit> =
        backend.call("deleteAnnouncement", mapOf("announcementId" to id)).map { }
}

/** Live-Daten einer Abfrage; fehlende Rechte ergeben eine leere Liste. */
private fun <T> Query.live(map: (DocumentSnapshot) -> T?): Flow<List<T>> = callbackFlow {
    val registration = addSnapshotListener { snapshot, error ->
        when {
            error?.code == FirebaseFirestoreException.Code.PERMISSION_DENIED -> trySend(emptyList())
            error != null -> close(error)
            else -> trySend(snapshot?.documents.orEmpty().mapNotNull(map))
        }
    }
    awaitClose { registration.remove() }
}

private fun DocumentSnapshot.toAnnouncement(): Announcement? {
    val title = getString("title") ?: return null
    return Announcement(
        id = id,
        title = title,
        text = getString("text").orEmpty(),
        pinned = getBoolean("pinned") ?: false,
        byName = getString("byName").orEmpty(),
        at = getTimestamp("createdAt")?.toDate()?.time,
        expiresAt = getTimestamp("expireAt")?.toDate()?.time,
    )
}

private fun DocumentSnapshot.toNotice(): ModNotice? {
    val action = ModAction.from(getString("action")) ?: return null
    return ModNotice(
        id = id,
        action = action,
        byName = getString("byName").orEmpty(),
        listingTitle = getString("listingTitle"),
        reason = getString("reason"),
        until = getTimestamp("until")?.toDate()?.time,
        at = getTimestamp("createdAt")?.toDate()?.time,
    )
}
