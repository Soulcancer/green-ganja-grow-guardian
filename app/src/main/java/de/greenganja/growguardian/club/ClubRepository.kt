package de.greenganja.growguardian.club

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import de.greenganja.growguardian.backend.Backend
import de.greenganja.growguardian.billing.SubscriptionManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transform
import java.util.Date

/**
 * Club-Verwaltung für die App: Team-Plätze, Mitglieder, Reservierungen und Abgabe-Dokumentation.
 *
 * Gelesen wird live aus Firestore (die Sicherheitsregeln zeigen jedem nur, was er sehen darf),
 * geschrieben wird ausschließlich über die Cloud Functions, die Rechte und KCanG-Grenzen prüfen.
 * Alle Aktionen liefern bei Fehlern eine BackendException mit deutscher Meldung.
 */
class ClubRepository(
    private val subscriptions: SubscriptionManager,
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val backend: Backend = Backend(),
) {
    private val uid: String? get() = auth.currentUser?.uid

    /** UID des angemeldeten Nutzers. */
    val currentUid: String? get() = uid

    @Volatile
    private var lastKnownRole: ClubRole? = null

    // ---------- Lesen (live) ----------

    /**
     * Eigene Club-Zugehörigkeit, null ohne Club. Nach dem Login neu sammeln.
     * Ändert sich die Rolle (z. B. Team-Platz erhalten oder entzogen), wird der Abo-Status neu geladen.
     */
    fun membership(): Flow<Membership?> {
        val uid = uid ?: return flowOf(null)
        return db.collection("memberships").document(uid)
            .snapshots { it.toMembership() }
            .distinctUntilChanged()
            .transform { membership ->
                emit(membership)
                if (membership?.role != lastKnownRole) {
                    lastKnownRole = membership?.role
                    subscriptions.refresh(forceFetch = true)
                }
            }
    }

    /** Club-Daten inkl. Mitglieder-Code. Nur für das Team. */
    fun club(clubId: String): Flow<Club?> =
        clubDoc(clubId).snapshots { it.toClub() }

    /** Alle Mitglieder, offene Anträge zuerst. Nur für das Team. */
    fun members(clubId: String): Flow<List<ClubMember>> =
        clubDoc(clubId).collection("members")
            .snapshots { it.toMember() }
            .map { members ->
                members.sortedWith(compareBy({ it.status != MemberStatus.PENDING }, { it.fullName.lowercase() }))
            }

    /** Offene Reservierungen nach Abholtag. Nur für das Team. */
    fun openReservations(clubId: String): Flow<List<Reservation>> =
        clubDoc(clubId).collection("reservations")
            .whereIn("status", listOf(ReservationStatus.REQUESTED.id, ReservationStatus.CONFIRMED.id))
            .orderBy("pickupDate", Query.Direction.ASCENDING)
            .snapshots { it.toReservation() }

    /** Eigene Reservierungen, neueste zuerst. */
    fun myReservations(clubId: String): Flow<List<Reservation>> {
        val uid = uid ?: return flowOf(emptyList())
        return clubDoc(clubId).collection("reservations")
            .whereEqualTo("memberUid", uid)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .snapshots { it.toReservation() }
    }

    /** Abgaben eines Monats ("YYYY-MM"), neueste zuerst. Nur für das Team. */
    fun dispensations(clubId: String, month: String): Flow<List<Dispensation>> =
        clubDoc(clubId).collection("dispensations")
            .whereEqualTo("month", month)
            .orderBy("date", Query.Direction.DESCENDING)
            .snapshots { it.toDispensation() }

    // ---------- Club und Team ----------

    /** Club gründen (braucht ein aktives Social-Club-Abo). Liefert den Mitglieder-Code. */
    suspend fun createClub(name: String): Result<String> =
        backend.call("createClub", mapOf("name" to name)).map { it["memberCode"] as? String ?: "" }

    /** Einladung für einen der 5 Team-Plätze. Nur für den Inhaber. */
    suspend fun createStaffInvite(): Result<StaffInvite> =
        backend.call("createStaffInvite").map {
            StaffInvite(it["code"] as? String ?: "", Date((it["expiresAt"] as? Number)?.toLong() ?: 0L))
        }

    /** Team-Einladung oder Mitglieder-Code einlösen. */
    suspend fun joinClub(code: String): Result<ClubRole> =
        backend.call("joinClub", mapOf("code" to code))
            .map { ClubRole.from(it["role"] as? String) ?: ClubRole.MEMBER }
            .onSuccess { role -> if (role != ClubRole.MEMBER) subscriptions.refresh(forceFetch = true) }

    suspend fun leaveClub(): Result<Unit> =
        backend.call("leaveClub").map { }.onSuccess { subscriptions.refresh(forceFetch = true) }

    /** Team-Platz entziehen, die Person bleibt Mitglied. Nur für den Inhaber. */
    suspend fun removeStaff(memberUid: String): Result<Unit> =
        backend.call("removeStaff", mapOf("uid" to memberUid)).map { }

    // ---------- Mitglieder (Team) ----------

    /**
     * Aufnahme nach Ausweiskontrolle freigeben oder Profil eintragen (auch fürs eigene Team-Profil).
     * @param birthDate "YYYY-MM-DD"
     */
    suspend fun approveMember(memberUid: String, firstName: String, lastName: String, birthDate: String): Result<Unit> =
        backend.call(
            "approveMember",
            mapOf("uid" to memberUid, "firstName" to firstName, "lastName" to lastName, "birthDate" to birthDate),
        ).map { }

    suspend fun removeMember(memberUid: String): Result<Unit> =
        backend.call("removeMember", mapOf("uid" to memberUid)).map { }

    /** Neuer Mitglieder-Code, der alte wird ungültig. */
    suspend fun rotateMemberCode(): Result<String> =
        backend.call("rotateMemberCode").map { it["memberCode"] as? String ?: "" }

    // ---------- Reservierungen ----------

    /**
     * Abgabemenge zur Abholung im Club anfragen.
     * @param pickupDate "YYYY-MM-DD", heute bis in 30 Tagen
     * @return Gramm, die in diesem Monat danach noch möglich sind
     */
    suspend fun requestReservation(grams: Double, pickupDate: String, strain: String? = null, note: String? = null): Result<Double> =
        backend.call(
            "requestReservation",
            mapOf("grams" to grams, "pickupDate" to pickupDate, "strain" to strain, "note" to note),
        ).map { (it["remainingMonthG"] as? Number)?.toDouble() ?: 0.0 }

    /**
     * Eigene Reservierung ändern, solange das Team sie noch nicht bestätigt hat.
     * @return Gramm, die in diesem Monat danach noch möglich sind
     */
    suspend fun updateReservation(
        reservationId: String,
        grams: Double,
        pickupDate: String,
        strain: String? = null,
        note: String? = null,
    ): Result<Double> =
        backend.call(
            "updateReservation",
            mapOf("reservationId" to reservationId, "grams" to grams, "pickupDate" to pickupDate, "strain" to strain, "note" to note),
        ).map { (it["remainingMonthG"] as? Number)?.toDouble() ?: 0.0 }

    suspend fun cancelReservation(reservationId: String): Result<Unit> =
        backend.call("cancelReservation", mapOf("reservationId" to reservationId)).map { }

    suspend fun confirmReservation(reservationId: String): Result<Unit> =
        backend.call("decideReservation", mapOf("reservationId" to reservationId, "decision" to "confirm")).map { }

    suspend fun declineReservation(reservationId: String, reason: String? = null): Result<Unit> =
        backend.call(
            "decideReservation",
            mapOf("reservationId" to reservationId, "decision" to "decline", "reason" to reason),
        ).map { }

    // ---------- Abgabe-Dokumentation (§ 26 KCanG) ----------

    /**
     * Ausgabe im Club dokumentieren, mit Reservierung oder direkt für ein Mitglied.
     * @return Gramm, die das Mitglied in diesem Monat danach noch bekommen kann
     */
    suspend fun recordHandout(
        grams: Double,
        thcPercent: Double,
        reservationId: String? = null,
        memberUid: String? = null,
        strain: String? = null,
    ): Result<Double> {
        require(reservationId != null || memberUid != null) { "reservationId oder memberUid angeben" }
        return backend.call(
            "recordHandout",
            mapOf(
                "reservationId" to reservationId,
                "memberUid" to memberUid,
                "grams" to grams,
                "thcPercent" to thcPercent,
                "strain" to strain,
            ),
        ).map { (it["remainingMonthG"] as? Number)?.toDouble() ?: 0.0 }
    }

    /** Alle Abgaben eines Jahres als CSV (Semikolon, UTF-8) für die Behörde. */
    suspend fun exportDispensations(year: Int): Result<String> =
        backend.call("exportDispensations", mapOf("year" to year)).map { it["csv"] as? String ?: "" }

    // ---------- Eigene Einstellungen und Daten ----------

    /** Eigene Einstellungen aus dem Mitglieds-Eintrag. */
    fun mySettings(clubId: String): Flow<MemberSettings> {
        val uid = uid ?: return flowOf(MemberSettings())
        return clubDoc(clubId).collection("members").document(uid)
            .snapshots { it.toSettings() }
            .map { it ?: MemberSettings() }
    }

    suspend fun updateSettings(settings: MemberSettings): Result<Unit> =
        backend.call(
            "updateMemberSettings",
            mapOf(
                "publicName" to settings.publicName,
                "notify" to mapOf(
                    "reservations" to settings.notify.reservations,
                    "clubBoxes" to settings.notify.clubBoxes,
                    "marketplace" to settings.notify.marketplace,
                ),
            ),
        ).map { }

    /** Alle eigenen Daten als JSON (Auskunft nach Art. 15 DSGVO). */
    suspend fun exportMyData(): Result<String> =
        backend.call("exportMyData").map { it["json"] as? String ?: "{}" }

    // ---------- intern ----------

    private fun clubDoc(clubId: String): DocumentReference = db.collection("clubs").document(clubId)
}

/** Live-Daten eines Dokuments; fehlende Rechte (z. B. Team-Platz entzogen) ergeben null statt Absturz. */
private fun <T> DocumentReference.snapshots(map: (DocumentSnapshot) -> T?): Flow<T?> = callbackFlow {
    val registration = addSnapshotListener { snapshot, error ->
        when {
            error?.code == FirebaseFirestoreException.Code.PERMISSION_DENIED -> trySend(null)
            error != null -> close(error)
            else -> trySend(snapshot?.takeIf { it.exists() }?.let(map))
        }
    }
    awaitClose { registration.remove() }
}

/** Live-Daten einer Abfrage; fehlende Rechte ergeben eine leere Liste. */
private fun <T> Query.snapshots(map: (DocumentSnapshot) -> T?): Flow<List<T>> = callbackFlow {
    val registration = addSnapshotListener { snapshot, error ->
        when {
            error?.code == FirebaseFirestoreException.Code.PERMISSION_DENIED -> trySend(emptyList())
            error != null -> close(error)
            else -> trySend(snapshot?.documents.orEmpty().mapNotNull(map))
        }
    }
    awaitClose { registration.remove() }
}

private fun DocumentSnapshot.toMembership(): Membership? {
    val clubId = getString("clubId") ?: return null
    val role = ClubRole.from(getString("role")) ?: return null
    val status = MemberStatus.from(getString("status")) ?: return null
    return Membership(clubId, getString("clubName").orEmpty(), role, status)
}

private fun DocumentSnapshot.toClub(): Club? {
    val ownerUid = getString("ownerUid") ?: return null
    return Club(
        id = id,
        name = getString("name").orEmpty(),
        ownerUid = ownerUid,
        seats = getLong("seats")?.toInt() ?: 0,
        staffUids = (get("staffUids") as? List<*>)?.filterIsInstance<String>().orEmpty(),
        active = getBoolean("active") ?: false,
        expiresAt = getTimestamp("expiresAt")?.toDate(),
        memberCode = getString("memberCode").orEmpty(),
    )
}

internal fun DocumentSnapshot.toSettings(): MemberSettings {
    val notify = get("notify") as? Map<*, *>
    val defaults = NotifySettings()
    return MemberSettings(
        publicName = getString("publicName")?.takeIf { it.isNotBlank() },
        notify = NotifySettings(
            reservations = notify?.get("reservations") as? Boolean ?: defaults.reservations,
            clubBoxes = notify?.get("clubBoxes") as? Boolean ?: defaults.clubBoxes,
            marketplace = notify?.get("marketplace") as? Boolean ?: defaults.marketplace,
        ),
    )
}

private fun DocumentSnapshot.toMember(): ClubMember? {
    val role = ClubRole.from(getString("role")) ?: return null
    val status = MemberStatus.from(getString("status")) ?: return null
    return ClubMember(
        uid = id,
        role = role,
        status = status,
        displayName = getString("displayName").orEmpty(),
        firstName = getString("firstName"),
        lastName = getString("lastName"),
        birthDate = getString("birthDate"),
    )
}

private fun DocumentSnapshot.toReservation(): Reservation? {
    val status = ReservationStatus.from(getString("status")) ?: return null
    return Reservation(
        id = id,
        memberUid = getString("memberUid") ?: return null,
        memberName = getString("memberName").orEmpty(),
        grams = getDouble("grams") ?: return null,
        strain = getString("strain"),
        pickupDate = getString("pickupDate") ?: return null,
        note = getString("note"),
        status = status,
        declineReason = getString("declineReason"),
    )
}

private fun DocumentSnapshot.toDispensation(): Dispensation? {
    return Dispensation(
        id = id,
        memberUid = getString("memberUid") ?: return null,
        firstName = getString("firstName").orEmpty(),
        lastName = getString("lastName").orEmpty(),
        birthYear = getLong("birthYear")?.toInt() ?: 0,
        grams = getDouble("grams") ?: return null,
        thcPercent = getDouble("thcPercent") ?: 0.0,
        strain = getString("strain"),
        date = getString("date") ?: return null,
    )
}
