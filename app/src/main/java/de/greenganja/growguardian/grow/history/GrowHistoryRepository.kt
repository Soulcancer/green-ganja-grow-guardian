package de.greenganja.growguardian.grow.history

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import de.greenganja.growguardian.grow.Metric
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Messwert-Verlauf unter `users/{uid}/growBoxes/{boxId}/history/{Tag}` und `…/stats/{Jahr}`.
 *
 * Die App schreibt nur Messwerte. Wie lange sie bleiben, bestimmt das Backend nach der Stufe
 * (Freemium 7 Tage, Premium 365 Tage, Social Club unbegrenzt); die App kann das nicht verlängern.
 */
class GrowHistoryRepository(
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance(),
) {
    private fun box(boxId: String): com.google.firebase.firestore.DocumentReference? {
        val uid = auth.currentUser?.uid ?: return null
        return db.collection("users").document(uid).collection("growBoxes").document(boxId)
    }

    /**
     * Einen Messpunkt speichern. Ohne Netz landet er in Firestores Offline-Speicher und wird später
     * übertragen; deshalb wird höchstens 20 Sekunden auf die Bestätigung gewartet.
     */
    suspend fun record(boxId: String, timeMillis: Long, values: Map<Metric, Double>) {
        val encoded = HistoryFormat.encode(values)
        if (encoded.isEmpty()) return
        val ref = box(boxId)?.collection("history")?.document(HistoryFormat.dayId(timeMillis)) ?: return
        val write = ref.set(
            mapOf("day" to ref.id, "s" to mapOf(HistoryFormat.slotKey(timeMillis) to encoded)),
            SetOptions.merge(),
        )
        withTimeoutOrNull(20_000) { write.await() }
    }

    /** Einzelne Messungen von [fromMillis] bis [toMillis] (höchstens einige Tage, sonst [dailyStats]). */
    suspend fun samples(boxId: String, fromMillis: Long, toMillis: Long): List<HistorySample> {
        val history: CollectionReference = box(boxId)?.collection("history") ?: return emptyList()
        val docs = history
            .whereGreaterThanOrEqualTo(FieldPath.documentId(), HistoryFormat.dayId(fromMillis))
            .whereLessThanOrEqualTo(FieldPath.documentId(), HistoryFormat.dayId(toMillis))
            .get().await()
            .documents
        return docs.flatMap { HistoryFormat.decodeDay(it.id, it.get("s") as? Map<*, *>) }
            .filter { it.timeMillis in fromMillis..toMillis }
    }

    /**
     * Tageswerte (Min, Mittel, Max) von [fromMillis] bis [toMillis]. Die letzten zwei Tage stehen noch nicht
     * in der nächtlichen Statistik und werden aus den einzelnen Messungen ergänzt.
     */
    suspend fun dailyStats(boxId: String, fromMillis: Long, toMillis: Long): List<DailyStat> {
        val stats = box(boxId)?.collection("stats") ?: return emptyList()
        val firstYear = HistoryFormat.dayId(fromMillis).take(4).toInt()
        val lastYear = HistoryFormat.dayId(toMillis).take(4).toInt()
        val fromStats = (firstYear..lastYear).flatMap { year ->
            val doc = stats.document(year.toString()).get().await()
            HistoryFormat.decodeStats(year, doc.get("d") as? Map<*, *>)
        }
        val recentFrom = maxOf(fromMillis, toMillis - 2 * HistoryFormat.DAY_MS)
        val recent = HistoryMath.dailyFromSamples(samples(boxId, recentFrom - Math.floorMod(recentFrom, HistoryFormat.DAY_MS), toMillis))
        val recentDays = recent.map { it.dayStartMillis }.toSet()
        return (fromStats.filter { it.dayStartMillis !in recentDays } + recent)
            .filter { it.dayStartMillis + HistoryFormat.DAY_MS > fromMillis && it.dayStartMillis <= toMillis }
            .sortedBy { it.dayStartMillis }
    }
}
