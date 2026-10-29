package de.greenganja.growguardian.grow

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
import kotlinx.coroutines.tasks.await

/**
 * Grow-Boxen des Nutzers unter `users/{uid}/growBoxes`.
 *
 * Gelesen wird live aus Firestore, geschrieben über die Cloud Functions `saveGrowBox` und `deleteGrowBox`.
 * Die prüfen die Limits der Stufe (Boxen, Sensoren) gegen RevenueCat, die App kann sie nicht umgehen.
 */
class GrowBoxRepository(
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val backend: Backend = Backend(),
) {
    /** Boxen in Anlegereihenfolge (die Reihenfolge bestimmt, welche bei einem Limit aktiv bleiben); null ohne Login. */
    fun boxes(): Flow<List<GrowBox>?> {
        val uid = auth.currentUser?.uid ?: return flowOf(null)
        val query = db.collection("users").document(uid).collection("growBoxes")
            .orderBy("createdAt", Query.Direction.ASCENDING)
        return callbackFlow {
            val registration = query.addSnapshotListener { snapshot, error ->
                when {
                    error?.code == FirebaseFirestoreException.Code.PERMISSION_DENIED -> trySend(emptyList())
                    error != null -> close(error)
                    else -> trySend(snapshot?.documents.orEmpty().mapNotNull { it.toGrowBox() })
                }
            }
            awaitClose { registration.remove() }
        }
    }

    /** Einmal lesen, für den Hintergrund-Job. */
    suspend fun boxesOnce(): List<GrowBox> {
        val uid = auth.currentUser?.uid ?: return emptyList()
        return db.collection("users").document(uid).collection("growBoxes")
            .orderBy("createdAt", Query.Direction.ASCENDING)
            .get().await()
            .documents.mapNotNull { it.toGrowBox() }
    }

    /** Legt eine Box an (leere [GrowBox.id]) oder speichert sie. Liefert die ID. */
    suspend fun save(box: GrowBox): Result<String> =
        backend.call("saveGrowBox", box.toPayload()).mapCatching { it["boxId"] as? String ?: error("boxId fehlt") }

    suspend fun delete(boxId: String): Result<Unit> =
        backend.call("deleteGrowBox", mapOf("boxId" to boxId)).map { }
}

internal fun GrowBox.toPayload(): Map<String, Any?> = mapOf(
    "boxId" to id.ifEmpty { null },
    "name" to name.trim(),
    "phase" to phase.id,
    "medium" to medium.id,
    "leafOffsetC" to leafOffsetC,
    "deviceIds" to deviceIds,
    "targets" to customTargets.entries.associate { (metric, range) ->
        metric.id to mapOf("min" to range.min, "max" to range.max)
    },
    "alerts" to alertsEnabled,
    "strain" to strain?.trim()?.ifBlank { null },
    "plantedAt" to plantedAt,
)

internal fun DocumentSnapshot.toGrowBox(): GrowBox? {
    val name = getString("name") ?: return null
    val targets = (get("targets") as? Map<*, *>).orEmpty().mapNotNull { (key, value) ->
        val metric = Metric.from(key as? String) ?: return@mapNotNull null
        val range = value as? Map<*, *> ?: return@mapNotNull null
        val min = (range["min"] as? Number)?.toDouble() ?: return@mapNotNull null
        val max = (range["max"] as? Number)?.toDouble() ?: return@mapNotNull null
        if (min > max) null else metric to TargetRange(min, max)
    }.toMap()
    return GrowBox(
        id = id,
        name = name,
        phase = GrowPhase.from(getString("phase")),
        medium = GrowMedium.from(getString("medium")),
        leafOffsetC = getDouble("leafOffsetC") ?: Vpd.DEFAULT_LEAF_OFFSET,
        deviceIds = (get("deviceIds") as? List<*>)?.filterIsInstance<String>().orEmpty(),
        customTargets = targets,
        alertsEnabled = getBoolean("alerts") != false,
        strain = getString("strain"),
        plantedAt = getString("plantedAt"),
    )
}
