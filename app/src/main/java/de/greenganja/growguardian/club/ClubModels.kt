package de.greenganja.growguardian.club

import java.util.Date

enum class ClubRole(val id: String) {
    /** Hat das Social-Club-Abo gekauft, verwaltet das Team. */
    OWNER("owner"),

    /** Belegt einen der 5 Team-Plätze (Social Club Farmer). */
    STAFF("staff"),

    /** Aktives Mitglied mit Moderations-Werkzeugen (Meldungen, Verwarnungen, Ankündigungen), ohne Team-Platz. */
    MODERATOR("moderator"),

    /** Normales Club-Mitglied, kann Abgabemengen reservieren. */
    MEMBER("member"),
    ;

    companion object {
        fun from(id: String?): ClubRole? = entries.firstOrNull { it.id == id }
    }
}

enum class MemberStatus(val id: String) {
    /** Aufnahme beantragt, wartet auf Freigabe nach Ausweiskontrolle. */
    PENDING("pending"),
    ACTIVE("active"),
    ;

    companion object {
        fun from(id: String?): MemberStatus? = entries.firstOrNull { it.id == id }
    }
}

enum class ReservationStatus(val id: String) {
    REQUESTED("requested"),
    CONFIRMED("confirmed"),
    DECLINED("declined"),
    HANDED_OUT("handedOut"),
    CANCELLED("cancelled"),
    ;

    val isOpen: Boolean get() = this == REQUESTED || this == CONFIRMED

    companion object {
        fun from(id: String?): ReservationStatus? = entries.firstOrNull { it.id == id }
    }
}

/** Die eigene Club-Zugehörigkeit. */
data class Membership(
    val clubId: String,
    val clubName: String,
    val role: ClubRole,
    val status: MemberStatus,
) {
    /** Inhaber oder Team-Platz: volle Club-Verwaltung. */
    val isStaff: Boolean get() = role == ClubRole.OWNER || role == ClubRole.STAFF
    val isOwner: Boolean get() = role == ClubRole.OWNER
    val isModerator: Boolean get() = role == ClubRole.MODERATOR

    /** Moderations-Werkzeuge: Moderatoren und Team. */
    val canModerate: Boolean get() = !isPending && (isStaff || isModerator)
    val isPending: Boolean get() = status == MemberStatus.PENDING
}

/** Club-Daten, nur für das Team lesbar. */
data class Club(
    val id: String,
    val name: String,
    val ownerUid: String,
    val seats: Int,
    val staffUids: List<String>,
    /** Social-Club-Abo des Inhabers läuft. */
    val active: Boolean,
    val expiresAt: Date?,
    /** Code, mit dem Mitglieder ihre Aufnahme beantragen. */
    val memberCode: String,
) {
    val freeSeats: Int get() = (seats - staffUids.size).coerceAtLeast(0)
}

data class ClubMember(
    val uid: String,
    val role: ClubRole,
    val status: MemberStatus,
    val displayName: String,
    val firstName: String?,
    val lastName: String?,
    /** "YYYY-MM-DD" */
    val birthDate: String?,
) {
    /** Erst mit Name und Geburtsdatum kann reserviert und ausgegeben werden. */
    val hasProfile: Boolean get() = firstName != null && lastName != null && birthDate != null
    val fullName: String get() = if (hasProfile) "$firstName $lastName" else displayName
}

data class StaffInvite(val code: String, val expiresAt: Date)

data class Reservation(
    val id: String,
    val memberUid: String,
    val memberName: String,
    val grams: Double,
    val strain: String?,
    /** "YYYY-MM-DD" */
    val pickupDate: String,
    val note: String?,
    val status: ReservationStatus,
    val declineReason: String?,
)

/** Ein Eintrag der Abgabe-Dokumentation nach § 26 KCanG. */
data class Dispensation(
    val id: String,
    val memberUid: String,
    val firstName: String,
    val lastName: String,
    val birthYear: Int,
    val grams: Double,
    val thcPercent: Double,
    val strain: String?,
    /** "YYYY-MM-DD" */
    val date: String,
)

/**
 * Grenzen nach § 19 KCanG für Hinweise in der UI (Stand Oktober 2026).
 * Verbindlich geprüft wird im Backend.
 */
object KcanLimits {
    const val DAILY_G = 25.0
    const val MONTHLY_G = 50.0
    const val MONTHLY_UNDER_21_G = 30.0
    const val MAX_THC_UNDER_21_PERCENT = 10.0
}

/** Welche Nachrichten ein Mitglied bekommen möchte. Gleiche Standardwerte wie DEFAULT_NOTIFY im Backend. */
data class NotifySettings(
    val reservations: Boolean = true,
    val clubBoxes: Boolean = true,
    val marketplace: Boolean = false,
)

/** Was ein Mitglied an sich selbst einstellt. */
data class MemberSettings(
    /** Name, den andere Mitglieder sehen (z. B. im Marktplatz); null = Vorname und Initiale. */
    val publicName: String? = null,
    val notify: NotifySettings = NotifySettings(),
)
