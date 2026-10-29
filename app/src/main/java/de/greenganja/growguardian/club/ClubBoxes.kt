package de.greenganja.growguardian.club

import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import de.greenganja.growguardian.backend.Backend
import de.greenganja.growguardian.grow.BoxStatus
import de.greenganja.growguardian.grow.GrowBox
import de.greenganja.growguardian.grow.GrowBoxState
import de.greenganja.growguardian.grow.GrowEvaluation
import de.greenganja.growguardian.grow.GrowMedium
import de.greenganja.growguardian.grow.GrowPhase
import de.greenganja.growguardian.grow.Metric
import de.greenganja.growguardian.grow.MetricReading
import de.greenganja.growguardian.grow.TargetRange
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import java.util.Date

/** Zuletzt hochgeladene Werte einer gemeinsamen Box. */
data class ClubBoxLatest(val atMillis: Long, val online: Boolean, val values: Map<Metric, Double>)

/**
 * Eine Box, die das Club-Team für alle Mitglieder teilt. Die Werte lädt das Handy des Betreuers hoch;
 * Mitglieder sehen sie nur an. Sensoren und Automationen bleiben im Tuya-Konto des Betreuers.
 */
data class ClubBox(
    val id: String,
    val name: String,
    val phase: GrowPhase,
    val medium: GrowMedium,
    val leafOffsetC: Double,
    val targets: Map<Metric, TargetRange>,
    val keeperUid: String,
    val keeperName: String,
    val sourceBoxId: String,
    val latest: ClubBoxLatest?,
)

object ClubBoxRules {
    /** Nach dieser Zeit ohne neue Werte gilt die Box als offline (der Betreuer-Job läuft alle 15 Minuten). */
    const val STALE_MS = 60 * 60 * 1000L

    private fun grow(box: ClubBox) = GrowBox(
        id = box.id, name = box.name, phase = box.phase, medium = box.medium,
        leafOffsetC = box.leafOffsetC, customTargets = box.targets,
    )

    fun readings(box: ClubBox): List<MetricReading> {
        val latest = box.latest ?: return emptyList()
        return GrowEvaluation.readings(latest.values, grow(box).targets)
    }

    fun status(box: ClubBox, now: Long): BoxStatus {
        val latest = box.latest ?: return BoxStatus.NO_DATA
        if (!latest.online || now - latest.atMillis > STALE_MS) return BoxStatus.OFFLINE
        return GrowEvaluation.boxStatus(1, 1, readings(box))
    }

    /** "gerade eben", "vor 5 Min.", "vor 2 Std.", "vor 3 Tagen". */
    fun ageLabel(atMillis: Long, now: Long): String {
        val minutes = ((now - atMillis) / 60_000).coerceAtLeast(0)
        return when {
            minutes < 2 -> "gerade eben"
            minutes < 60 -> "vor $minutes Min."
            minutes < 24 * 60 -> "vor ${minutes / 60} Std."
            else -> "vor ${minutes / (24 * 60)} Tagen"
        }
    }

    /** Angepinnte zuerst, dann nach Name; ausgeblendete fallen weg, außer [showHidden]. */
    fun arrange(boxes: List<ClubBox>, pinned: Set<String>, hidden: Set<String>, showHidden: Boolean): List<ClubBox> =
        boxes.filter { showHidden || it.id !in hidden }
            .sortedWith(compareBy({ it.id !in pinned }, { it.name.lowercase() }))

    /** Werte für den Upload: gemittelt über die Sensoren der Box, nur aktuelle Geräte. */
    fun latestOf(state: GrowBoxState, now: Long): ClubBoxLatest? {
        val values = state.readings.associate { it.metric to it.value }
        if (values.isEmpty()) return null
        return ClubBoxLatest(now, state.devices.any { it.online }, values)
    }
}

/** Persönliche Ordnung der Club-Boxen: angepinnte zuerst, ausgeblendete nur auf Wunsch. Bleibt auf dem Gerät. */
interface ClubBoxPrefs {
    fun pinned(): Set<String>
    fun hidden(): Set<String>
    fun setPinned(ids: Set<String>)
    fun setHidden(ids: Set<String>)
}

class SharedClubBoxPrefs(context: android.content.Context) : ClubBoxPrefs {
    private val prefs = context.applicationContext.getSharedPreferences("club_boxes", android.content.Context.MODE_PRIVATE)
    private fun read(key: String): Set<String> = prefs.getString(key, null)?.split(',')?.filter { it.isNotBlank() }?.toSet().orEmpty()
    private fun write(key: String, ids: Set<String>) = prefs.edit().putString(key, ids.joinToString(",")).apply()
    override fun pinned() = read("pinned")
    override fun hidden() = read("hidden")
    override fun setPinned(ids: Set<String>) = write("pinned", ids)
    override fun setHidden(ids: Set<String>) = write("hidden", ids)
}

/** Lädt die Werte geteilter Boxen hoch. Der Hintergrund-Job ruft das nach jeder Messung auf. */
interface ClubBoxPublisher {
    suspend fun publish(states: List<GrowBoxState>, now: Long)
}

class ClubBoxRepository(
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val backend: Backend = Backend(),
) : ClubBoxPublisher {

    /** Gemeinsame Boxen des Clubs, live. Ohne Rechte (z. B. Antrag offen) eine leere Liste. */
    fun boxes(clubId: String): Flow<List<ClubBox>> = callbackFlow {
        val registration = db.collection("clubs").document(clubId).collection("growBoxes")
            .addSnapshotListener { snapshot, error ->
                when {
                    error?.code == FirebaseFirestoreException.Code.PERMISSION_DENIED -> trySend(emptyList())
                    error != null -> close(error)
                    else -> trySend(snapshot?.documents.orEmpty().mapNotNull { it.toClubBox() }.sortedBy { it.name.lowercase() })
                }
            }
        awaitClose { registration.remove() }
    }

    /** Eigene Box für den Club teilen oder die geteilte Fassung aktualisieren. Nur Team. */
    suspend fun share(boxId: String): Result<String> =
        backend.call("shareClubBox", mapOf("boxId" to boxId)).mapCatching { it["clubBoxId"] as? String ?: error("clubBoxId fehlt") }

    suspend fun unshare(clubBoxId: String): Result<Unit> =
        backend.call("unshareClubBox", mapOf("clubBoxId" to clubBoxId)).map { }

    override suspend fun publish(states: List<GrowBoxState>, now: Long) {
        val uid = auth.currentUser?.uid ?: return
        val membership = db.collection("memberships").document(uid).get().await()
        val clubId = membership.getString("clubId") ?: return
        if (membership.getString("role") == "member") return
        val mine = db.collection("clubs").document(clubId).collection("growBoxes")
            .whereEqualTo("keeperUid", uid).get().await().documents
        for (doc in mine) {
            val state = states.firstOrNull { it.box.id == doc.getString("sourceBoxId") } ?: continue
            val latest = ClubBoxRules.latestOf(state, now) ?: continue
            doc.reference.update(
                mapOf(
                    "latest" to mapOf(
                        "at" to Timestamp(Date(latest.atMillis)),
                        "online" to latest.online,
                        "values" to latest.values.mapKeys { it.key.id },
                    ),
                ),
            ).await()
        }
    }
}

internal fun DocumentSnapshot.toClubBox(): ClubBox? {
    val name = getString("name") ?: return null
    val targets = (get("targets") as? Map<*, *>).orEmpty().mapNotNull { (key, value) ->
        val metric = Metric.from(key as? String) ?: return@mapNotNull null
        val range = value as? Map<*, *> ?: return@mapNotNull null
        val min = (range["min"] as? Number)?.toDouble() ?: return@mapNotNull null
        val max = (range["max"] as? Number)?.toDouble() ?: return@mapNotNull null
        if (min > max) null else metric to TargetRange(min, max)
    }.toMap()
    val latestMap = get("latest") as? Map<*, *>
    val latest = latestMap?.let { map ->
        val at = (map["at"] as? Timestamp)?.toDate()?.time ?: return@let null
        val values = (map["values"] as? Map<*, *>).orEmpty().mapNotNull { (k, v) ->
            val metric = Metric.from(k as? String) ?: return@mapNotNull null
            val number = (v as? Number)?.toDouble()?.takeIf { it.isFinite() } ?: return@mapNotNull null
            metric to number
        }.toMap()
        ClubBoxLatest(at, map["online"] as? Boolean ?: false, values)
    }
    return ClubBox(
        id = id,
        name = name,
        phase = GrowPhase.from(getString("phase")),
        medium = GrowMedium.from(getString("medium")),
        leafOffsetC = getDouble("leafOffsetC") ?: -2.0,
        targets = targets,
        keeperUid = getString("keeperUid") ?: return null,
        keeperName = getString("keeperName").orEmpty(),
        sourceBoxId = getString("sourceBoxId").orEmpty(),
        latest = latest,
    )
}
