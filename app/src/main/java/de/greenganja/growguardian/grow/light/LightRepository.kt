package de.greenganja.growguardian.grow.light

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import de.greenganja.growguardian.backend.Backend
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.tasks.await

/** Woher eine Messung stammt. */
enum class LightSource(val id: String, val label: String) {
    PHONE("phone", "Handy-Sensor"),
    TUYA("tuya", "Tuya-Lichtsensor"),
    MANUAL_LUX("manual_lux", "Luxmeter (von Hand)"),
    MANUAL_PPFD("manual_ppfd", "PAR-Meter (von Hand)"),
    ;

    companion object {
        fun from(id: String?): LightSource = entries.firstOrNull { it.id == id } ?: PHONE
    }
}

/** Eine gespeicherte Lichtmessung (Mittel über alle Messpunkte). */
data class LightReading(
    val id: String = "",
    val createdAtMillis: Long? = null,
    val source: LightSource,
    val lux: Double?,
    val ppfd: Double,
    val dli: Double?,
    val hours: Double?,
    val points: Int,
    val minPpfd: Double?,
    val maxPpfd: Double?,
    val lampIds: List<String>,
    val phase: String?,
)

/** Prüfregeln wie in backend/functions/src/rules/light.ts und firestore.rules (validLightReading). */
object LightRules {
    const val MAX_POINTS = 25
    const val MAX_PPFD = 5000.0
    const val MAX_LUX = 300_000.0

    /** Deutsche Fehlermeldung oder null. */
    fun validate(setup: LightSetup, allLampTypes: Boolean): String? {
        if (setup.lamps.isEmpty()) return "Wähl mindestens eine Lampe."
        if (setup.lamps.size > LightSetup.MAX_LAMPS) return "Höchstens ${LightSetup.MAX_LAMPS} Lampen je Box."
        for (use in setup.lamps) {
            val lamp = use.lamp ?: return "Unbekannte Lampe."
            if (!LampCatalog.allowed(lamp, allLampTypes)) return "„${lamp.name}“ gibt es ab Premium Farmer."
            if (use.count !in 1..LightSetup.MAX_COUNT) return "Anzahl 1 bis ${LightSetup.MAX_COUNT}."
            val w = use.watts
            if (w != null && (w <= 0 || w > LightSetup.MAX_WATTS)) return "Leistung je Lampe 1 bis ${LightSetup.MAX_WATTS.toInt()} W."
        }
        setup.areaM2?.let { if (it !in LightSetup.AREA_RANGE) return "Fläche 0,05 bis 100 m²." }
        setup.dimPercent?.let { if (it !in LightSetup.DIM_RANGE) return "Dimmer 10 bis 100 %." }
        setup.hoursPerDay?.let { if (it !in 0.0..24.0) return "Lichtstunden 0 bis 24." }
        if (setup.calibration !in LightSetup.CALIBRATION_RANGE) return "Kalibrierfaktor 0,2 bis 5."
        return null
    }

    fun toPayload(boxId: String, setup: LightSetup): Map<String, Any?> = mapOf(
        "boxId" to boxId,
        "lamps" to setup.lamps.map { mapOf("lampId" to it.lampId, "watts" to it.watts, "count" to it.count) },
        "areaM2" to setup.areaM2,
        "dimPercent" to setup.dimPercent,
        "hoursPerDay" to setup.hoursPerDay,
        "calibration" to setup.calibration,
    )

    /** Aus dem Feld `light` des Box-Dokuments. */
    fun parseSetup(raw: Any?): LightSetup? {
        val map = raw as? Map<*, *> ?: return null
        val lamps = (map["lamps"] as? List<*>).orEmpty().mapNotNull { item ->
            val m = item as? Map<*, *> ?: return@mapNotNull null
            val id = m["lampId"] as? String ?: return@mapNotNull null
            if (LampCatalog.get(id) == null) return@mapNotNull null
            LampUse(id, (m["watts"] as? Number)?.toDouble(), (m["count"] as? Number)?.toInt()?.coerceIn(1, LightSetup.MAX_COUNT) ?: 1)
        }
        if (lamps.isEmpty()) return null
        return LightSetup(
            lamps = lamps,
            areaM2 = (map["areaM2"] as? Number)?.toDouble(),
            dimPercent = (map["dimPercent"] as? Number)?.toDouble(),
            hoursPerDay = (map["hoursPerDay"] as? Number)?.toDouble(),
            calibration = (map["calibration"] as? Number)?.toDouble()?.takeIf { it in LightSetup.CALIBRATION_RANGE } ?: 1.0,
        )
    }

    fun readingData(reading: LightReading): Map<String, Any?> = mapOf(
        "createdAt" to FieldValue.serverTimestamp(),
        "source" to reading.source.id,
        "lux" to reading.lux,
        "ppfd" to reading.ppfd,
        "dli" to reading.dli,
        "hours" to reading.hours,
        "points" to reading.points,
        "minPpfd" to reading.minPpfd,
        "maxPpfd" to reading.maxPpfd,
        "lampIds" to reading.lampIds,
        "phase" to reading.phase,
    )

    fun validateReading(reading: LightReading): String? = when {
        !reading.ppfd.isFinite() || reading.ppfd !in 0.0..MAX_PPFD -> "Der PPFD-Wert ist unplausibel."
        reading.lux?.let { !it.isFinite() || it !in 0.0..MAX_LUX } == true -> "Der Lux-Wert ist unplausibel."
        reading.points !in 1..MAX_POINTS -> "1 bis $MAX_POINTS Messpunkte."
        reading.lampIds.isEmpty() || reading.lampIds.size > LightSetup.MAX_LAMPS -> "Ungültige Lampenauswahl."
        else -> null
    }
}

/**
 * Lampen-Setup (über die Function saveLightSetup, die die Stufe prüft) und Messungen unter
 * users/{uid}/growBoxes/{boxId}/lightReadings (schreibt die App, die Regeln prüfen Format und Lampen).
 */
class LightRepository(
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val backend: Backend = Backend(),
) {
    private fun readingsRef(boxId: String) = auth.currentUser?.uid?.let {
        db.collection("users").document(it).collection("growBoxes").document(boxId).collection("lightReadings")
    }

    suspend fun saveSetup(boxId: String, setup: LightSetup): Result<Unit> =
        backend.call("saveLightSetup", LightRules.toPayload(boxId, setup)).map { }

    /** Neueste zuerst, höchstens [limit]. */
    fun readings(boxId: String, limit: Long = 30): Flow<List<LightReading>> {
        val ref = readingsRef(boxId) ?: return flowOf(emptyList())
        return callbackFlow {
            val registration = ref.orderBy("createdAt", Query.Direction.DESCENDING).limit(limit)
                .addSnapshotListener { snapshot, error ->
                    when {
                        error?.code == FirebaseFirestoreException.Code.PERMISSION_DENIED -> trySend(emptyList())
                        error != null -> close(error)
                        else -> trySend(snapshot?.documents.orEmpty().mapNotNull { it.toLightReading() })
                    }
                }
            awaitClose { registration.remove() }
        }
    }

    suspend fun saveReading(boxId: String, reading: LightReading) {
        val ref = readingsRef(boxId) ?: error("Nicht angemeldet")
        ref.document().set(LightRules.readingData(reading)).await()
    }

    suspend fun deleteReading(boxId: String, readingId: String) {
        readingsRef(boxId)?.document(readingId)?.delete()?.await()
    }
}

internal fun DocumentSnapshot.toLightReading(): LightReading? {
    val ppfd = getDouble("ppfd") ?: return null
    return LightReading(
        id = id,
        createdAtMillis = getTimestamp("createdAt")?.toDate()?.time,
        source = LightSource.from(getString("source")),
        lux = getDouble("lux"),
        ppfd = ppfd,
        dli = getDouble("dli"),
        hours = getDouble("hours"),
        points = getLong("points")?.toInt() ?: 1,
        minPpfd = getDouble("minPpfd"),
        maxPpfd = getDouble("maxPpfd"),
        lampIds = (get("lampIds") as? List<*>)?.filterIsInstance<String>().orEmpty(),
        phase = getString("phase"),
    )
}
