package de.greenganja.growguardian.club

import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import de.greenganja.growguardian.backend.Backend
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOf

enum class ListingType(val id: String, val label: String) {
    OFFER("offer", "Biete"),
    WANTED("wanted", "Suche"),
    SWAP("swap", "Tausche"),
    FREE("free", "Zu verschenken"),
    ;

    companion object {
        fun from(id: String?) = entries.firstOrNull { it.id == id }
    }
}

enum class ListingCategory(val id: String, val label: String) {
    LIGHT("light", "Licht"),
    TENT("tent", "Zelt"),
    AIR("air", "Lüftung & Klima"),
    POTS("pots", "Töpfe & Erde"),
    TECH("tech", "Sensoren & Technik"),
    TOOLS("tools", "Werkzeug"),
    OTHER("other", "Sonstiges"),
    ;

    companion object {
        fun from(id: String?) = entries.firstOrNull { it.id == id } ?: OTHER
    }
}

enum class ListingCondition(val id: String, val label: String) {
    NEW("new", "Neu"),
    GOOD("good", "Sehr gut"),
    USED("used", "Gebraucht"),
    DEFECT("defect", "Defekt"),
    ;

    companion object {
        fun from(id: String?) = entries.firstOrNull { it.id == id }
    }
}

enum class Material(val id: String, val label: String, val plural: String) {
    SEED("seed", "Samen", "Samen"),
    CUTTING("cutting", "Steckling", "Stecklinge"),
    ;

    companion object {
        fun from(id: String?) = entries.firstOrNull { it.id == id }
    }
}

enum class ListingStatus(val id: String, val label: String) {
    OPEN("open", "Offen"),
    RESERVED("reserved", "Reserviert"),
    CLOSED("closed", "Erledigt"),
    REPORTED("reported", "Gemeldet"),
    REMOVED("removed", "Entfernt"),
    ;

    companion object {
        fun from(id: String?) = entries.firstOrNull { it.id == id } ?: OPEN
    }
}

/** Ein Inserat: Zubehör eines Mitglieds oder ein Club-Angebot für Samen oder Stecklinge. */
sealed interface Listing {
    val id: String
    val sellerUid: String
    val sellerName: String
    val status: ListingStatus
    val description: String
    val createdAtMillis: Long

    data class Gear(
        override val id: String,
        override val sellerUid: String,
        override val sellerName: String,
        override val status: ListingStatus,
        override val description: String,
        override val createdAtMillis: Long,
        val type: ListingType,
        val category: ListingCategory,
        val condition: ListingCondition?,
        val title: String,
        val priceEur: Double?,
        /** Mit Coins hervorgehoben bis (ms). */
        val boostedUntil: Long = 0,
    ) : Listing

    data class Propagation(
        override val id: String,
        override val sellerUid: String,
        override val sellerName: String,
        override val status: ListingStatus,
        override val description: String,
        override val createdAtMillis: Long,
        val material: Material,
        val strain: String,
        val stock: Int,
    ) : Listing
}

data class Interest(val uid: String, val name: String, val message: String?)

enum class PropagationStatus(val id: String, val label: String) {
    REQUESTED("requested", "Angefragt"),
    HANDED_OUT("handedOut", "Ausgegeben"),
    DECLINED("declined", "Abgelehnt"),
    CANCELLED("cancelled", "Zurückgezogen"),
    ;

    companion object {
        fun from(id: String?) = entries.firstOrNull { it.id == id }
    }
}

data class PropagationRequest(
    val id: String,
    val memberUid: String,
    val memberName: String,
    val strain: String,
    val material: Material,
    val count: Int,
    val month: String,
    val status: PropagationStatus,
)

/** Grenzen nach § 20 KCanG, gleiche Werte wie rules/market.ts. Nur für Hinweise; geprüft wird im Backend. */
object PropagationLimits {
    const val SEEDS = 7
    const val CUTTINGS = 5
    const val MIXED = 5

    /** Wie viele noch gehen, wenn schon [seeds] Samen und [cuttings] Stecklinge in diesem Monat gezählt sind. */
    fun left(material: Material, seeds: Int, cuttings: Int): Int = when {
        seeds == 0 && cuttings == 0 -> if (material == Material.SEED) SEEDS else CUTTINGS
        cuttings == 0 -> if (material == Material.SEED) SEEDS - seeds else MIXED - seeds
        seeds == 0 -> if (material == Material.CUTTING) CUTTINGS - cuttings else MIXED - cuttings
        else -> MIXED - seeds - cuttings
    }.coerceAtLeast(0)

    /** Was in diesem Monat schon zählt: offene und ausgegebene Anfragen. */
    fun counted(requests: List<PropagationRequest>, month: String): Pair<Int, Int> {
        val relevant = requests.filter { it.month == month && (it.status == PropagationStatus.REQUESTED || it.status == PropagationStatus.HANDED_OUT) }
        return relevant.filter { it.material == Material.SEED }.sumOf { it.count } to
            relevant.filter { it.material == Material.CUTTING }.sumOf { it.count }
    }
}

/**
 * Marktplatz des Clubs unter `clubs/{clubId}/listings`. Lesen live aus Firestore, schreiben über die Functions,
 * die Mitgliedschaft, Wortfilter und die Grenzen nach § 20 KCanG prüfen.
 */
class MarketRepository(
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val backend: Backend = Backend(),
) {
    val currentUid: String? get() = auth.currentUser?.uid

    private fun club(clubId: String) = db.collection("clubs").document(clubId)

    /** Offene und reservierte Inserate, neueste zuerst. */
    fun listings(clubId: String): Flow<List<Listing>> =
        club(clubId).collection("listings")
            .whereIn("status", listOf(ListingStatus.OPEN.id, ListingStatus.RESERVED.id))
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .live { it.toListing() }

    /** Gemeldete Inserate. Nur für das Team. */
    fun reported(clubId: String): Flow<List<Listing>> =
        club(clubId).collection("listings").whereEqualTo("status", ListingStatus.REPORTED.id).live { it.toListing() }

    /** Interessenten an einem eigenen Inserat. */
    fun interests(clubId: String, listingId: String): Flow<List<Interest>> =
        club(clubId).collection("listings").document(listingId).collection("interests")
            .live { d -> d.getString("uid")?.let { Interest(it, d.getString("name").orEmpty(), d.getString("message")) } }

    fun myPropagationRequests(clubId: String): Flow<List<PropagationRequest>> {
        val uid = currentUid ?: return flowOf(emptyList())
        return club(clubId).collection("propagationRequests")
            .whereEqualTo("memberUid", uid)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .live { it.toPropagationRequest() }
    }

    /** Offene Anfragen nach Samen und Stecklingen. Nur für das Team. */
    fun openPropagationRequests(clubId: String): Flow<List<PropagationRequest>> =
        club(clubId).collection("propagationRequests")
            .whereEqualTo("status", PropagationStatus.REQUESTED.id)
            .orderBy("createdAt", Query.Direction.ASCENDING)
            .live { it.toPropagationRequest() }

    suspend fun createListing(
        type: ListingType,
        category: ListingCategory,
        condition: ListingCondition?,
        title: String,
        description: String,
        priceEur: Double?,
    ): Result<String> = backend.call(
        "createListing",
        mapOf(
            "type" to type.id, "category" to category.id, "condition" to condition?.id,
            "title" to title, "description" to description, "priceEur" to priceEur,
        ),
    ).mapCatching { it["listingId"] as? String ?: error("listingId fehlt") }

    suspend fun createPropagationOffer(material: Material, strain: String, description: String, stock: Int): Result<String> =
        backend.call(
            "createPropagationOffer",
            mapOf("material" to material.id, "strain" to strain, "description" to description, "stock" to stock),
        ).mapCatching { it["listingId"] as? String ?: error("listingId fehlt") }

    suspend fun setStatus(listingId: String, status: ListingStatus): Result<Unit> =
        backend.call("setListingStatus", mapOf("listingId" to listingId, "status" to status.id)).map { }

    suspend fun expressInterest(listingId: String, message: String?): Result<Unit> =
        backend.call("expressInterest", mapOf("listingId" to listingId, "message" to message)).map { }

    suspend fun report(listingId: String, reason: String?): Result<Unit> =
        backend.call("reportListing", mapOf("listingId" to listingId, "reason" to reason)).map { }

    suspend fun requestPropagation(listingId: String, count: Int): Result<Unit> =
        backend.call("requestPropagation", mapOf("listingId" to listingId, "count" to count)).map { }

    /** Eigenes Inserat 3 Tage ganz oben zeigen, für 20 Coins. */
    suspend fun boost(listingId: String): Result<Unit> =
        backend.call("redeemCoins", mapOf("item" to "boost_listing", "listingId" to listingId)).map { }

    suspend fun cancelPropagation(requestId: String): Result<Unit> =
        backend.call("cancelPropagation", mapOf("requestId" to requestId)).map { }

    suspend fun decidePropagation(requestId: String, handOut: Boolean): Result<Unit> =
        backend.call("decidePropagation", mapOf("requestId" to requestId, "decision" to if (handOut) "handOut" else "decline")).map { }
}

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

internal fun DocumentSnapshot.toListing(): Listing? {
    val sellerUid = getString("sellerUid") ?: return null
    val created = (get("createdAt") as? Timestamp)?.toDate()?.time ?: 0L
    val status = ListingStatus.from(getString("status"))
    val description = getString("description").orEmpty()
    return when (getString("kind")) {
        "gear" -> Listing.Gear(
            id = id, sellerUid = sellerUid, sellerName = getString("sellerName").orEmpty(), status = status,
            description = description, createdAtMillis = created,
            type = ListingType.from(getString("type")) ?: return null,
            category = ListingCategory.from(getString("category")),
            condition = ListingCondition.from(getString("condition")),
            title = getString("title") ?: return null,
            priceEur = getDouble("priceEur"),
            boostedUntil = (get("boostedUntil") as? Timestamp)?.toDate()?.time ?: 0L,
        )
        "propagation" -> Listing.Propagation(
            id = id, sellerUid = sellerUid, sellerName = getString("sellerName").orEmpty(), status = status,
            description = description, createdAtMillis = created,
            material = Material.from(getString("material")) ?: return null,
            strain = getString("strain").orEmpty(),
            stock = getLong("stock")?.toInt() ?: 0,
        )
        else -> null
    }
}

internal fun DocumentSnapshot.toPropagationRequest(): PropagationRequest? {
    return PropagationRequest(
        id = id,
        memberUid = getString("memberUid") ?: return null,
        memberName = getString("memberName").orEmpty(),
        strain = getString("strain").orEmpty(),
        material = Material.from(getString("material")) ?: return null,
        count = getLong("count")?.toInt() ?: return null,
        month = getString("month").orEmpty(),
        status = PropagationStatus.from(getString("status")) ?: return null,
    )
}
