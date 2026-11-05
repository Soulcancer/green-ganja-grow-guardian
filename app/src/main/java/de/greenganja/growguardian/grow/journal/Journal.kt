package de.greenganja.growguardian.grow.journal

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import de.greenganja.growguardian.grow.Metric
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.tasks.await
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Ein Tagebuch-Eintrag einer Box: Gießen, Dünger, Werte von Hand gemessen, Notizen. */
data class JournalEntry(
    val id: String = "",
    /** "YYYY-MM-DD" */
    val date: String,
    val watered: Boolean = false,
    val waterMl: Double? = null,
    val nutrientsMl: Double? = null,
    /** Von Hand gemessene Werte, z. B. pH aus dem Tropfentest. */
    val values: Map<Metric, Double> = emptyMap(),
    val notes: String? = null,
) {
    val isEmpty: Boolean get() = !watered && waterMl == null && nutrientsMl == null && values.isEmpty() && notes.isNullOrBlank()
}

/** Regeln für das Tagebuch, ohne Android. Grenzen wie in firestore.rules (validJournal). */
object JournalRules {
    const val MAX_NOTES = 2000
    const val MAX_WATER_ML = 100_000.0
    const val MAX_NUTRIENTS_ML = 10_000.0

    /** Messgrößen, die man von Hand einträgt (VPD berechnet sich, Leistung misst nur die Steckdose). */
    val MANUAL_METRICS = listOf(
        Metric.AIR_TEMPERATURE, Metric.AIR_HUMIDITY, Metric.SOIL_MOISTURE, Metric.WATER_TEMPERATURE,
        Metric.PH, Metric.EC, Metric.TDS, Metric.CO2,
    )

    /** Deutsche Fehlermeldung oder null. */
    fun validate(entry: JournalEntry, today: String): String? = when {
        runCatching { LocalDate.parse(entry.date) }.isFailure -> "Ungültiges Datum."
        entry.date > today -> "Einträge für die Zukunft gehen nicht."
        entry.isEmpty -> "Trag mindestens etwas ein: gegossen, Dünger, einen Wert oder eine Notiz."
        (entry.waterMl ?: 0.0) !in 0.0..MAX_WATER_ML -> "Die Wassermenge ist unplausibel."
        (entry.nutrientsMl ?: 0.0) !in 0.0..MAX_NUTRIENTS_ML -> "Die Düngermenge ist unplausibel."
        (entry.notes?.length ?: 0) > MAX_NOTES -> "Die Notiz darf höchstens $MAX_NOTES Zeichen haben."
        entry.values[Metric.PH]?.let { it !in 0.0..14.0 } == true -> "Der pH-Wert liegt zwischen 0 und 14."
        entry.values[Metric.AIR_HUMIDITY]?.let { it !in 0.0..100.0 } == true ||
            entry.values[Metric.SOIL_MOISTURE]?.let { it !in 0.0..100.0 } == true -> "Feuchte liegt zwischen 0 und 100 %."
        entry.values.values.any { it < -40.0 || !it.isFinite() } -> "Ein Messwert ist unplausibel."
        else -> null
    }

    /** Tag des Grows (Starttag = Tag 1), null ohne Starttag oder bei Einträgen davor. */
    fun growDay(plantedAt: String?, date: String): Int? {
        val start = plantedAt?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
        val day = runCatching { LocalDate.parse(date) }.getOrNull() ?: return null
        val days = ChronoUnit.DAYS.between(start, day)
        return if (days < 0) null else days.toInt() + 1
    }

    /** "Woche 3, Tag 2" aus dem Grow-Tag. */
    fun weekLabel(growDay: Int): String = "Woche ${(growDay - 1) / 7 + 1}, Tag ${(growDay - 1) % 7 + 1}"

    /** Wann zuletzt gegossen, für den Kopf der Seite. */
    fun lastWatered(entries: List<JournalEntry>): String? = entries.filter { it.watered }.maxOfOrNull { it.date }
}

/** Tagebuch unter users/{uid}/growBoxes/{boxId}/journal. Die App schreibt direkt, die Regeln prüfen das Format. */
class JournalRepository(
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance(),
) {
    private fun collection(boxId: String) = auth.currentUser?.uid?.let {
        db.collection("users").document(it).collection("growBoxes").document(boxId).collection("journal")
    }

    /** Neueste zuerst. */
    fun entries(boxId: String): Flow<List<JournalEntry>> {
        val ref = collection(boxId) ?: return flowOf(emptyList())
        return callbackFlow {
            val registration = ref.orderBy("date", Query.Direction.DESCENDING).addSnapshotListener { snapshot, error ->
                when {
                    error?.code == FirebaseFirestoreException.Code.PERMISSION_DENIED -> trySend(emptyList())
                    error != null -> close(error)
                    else -> trySend(snapshot?.documents.orEmpty().mapNotNull { it.toJournalEntry() })
                }
            }
            awaitClose { registration.remove() }
        }
    }

    suspend fun save(boxId: String, entry: JournalEntry) {
        val ref = collection(boxId) ?: error("Nicht angemeldet")
        val doc = if (entry.id.isEmpty()) ref.document() else ref.document(entry.id)
        val data = mutableMapOf<String, Any?>(
            "date" to entry.date,
            "watered" to entry.watered,
            "waterMl" to entry.waterMl,
            "nutrientsMl" to entry.nutrientsMl,
            "values" to entry.values.mapKeys { it.key.id },
            "notes" to entry.notes?.trim()?.ifBlank { null },
            "updatedAt" to FieldValue.serverTimestamp(),
        )
        if (entry.id.isEmpty()) data["createdAt"] = FieldValue.serverTimestamp()
        doc.set(data, SetOptions.merge()).await()
    }

    suspend fun delete(boxId: String, entryId: String) {
        collection(boxId)?.document(entryId)?.delete()?.await()
    }

    /** Alle Einträge einer Box löschen, in Paketen zu höchstens 400 (Grenze eines Batches: 500). */
    suspend fun deleteAll(boxId: String) {
        val ref = collection(boxId) ?: error("Nicht angemeldet")
        val docs = ref.get().await().documents
        for (chunk in docs.chunked(400)) {
            val batch = db.batch()
            chunk.forEach { batch.delete(it.reference) }
            batch.commit().await()
        }
    }
}

internal fun DocumentSnapshot.toJournalEntry(): JournalEntry? {
    val date = getString("date") ?: return null
    val values = (get("values") as? Map<*, *>).orEmpty().mapNotNull { (k, v) ->
        val metric = Metric.from(k as? String) ?: return@mapNotNull null
        val number = (v as? Number)?.toDouble() ?: return@mapNotNull null
        metric to number
    }.toMap()
    return JournalEntry(
        id = id,
        date = date,
        watered = getBoolean("watered") == true,
        waterMl = getDouble("waterMl"),
        nutrientsMl = getDouble("nutrientsMl"),
        values = values,
        notes = getString("notes"),
    )
}
