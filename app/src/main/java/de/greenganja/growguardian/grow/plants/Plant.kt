package de.greenganja.growguardian.grow.plants

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import java.time.LocalDate
import java.time.temporal.ChronoUnit

enum class PlantOrigin(val id: String, val label: String) {
    SEED("seed", "Samen"),
    CLONE("clone", "Steckling"),
    ;

    companion object {
        fun from(id: String?): PlantOrigin? = entries.firstOrNull { it.id == id }
    }
}

/** Eine Pflanze in einer Box. Mehrere Pflanzen teilen sich Box, Sensoren, Zielwerte und Phase. */
data class Plant(
    val id: String = "",
    val name: String,
    val strain: String? = null,
    val origin: PlantOrigin? = null,
    val autoflower: Boolean = false,
    /** Keimung bzw. Steckling gesetzt, "YYYY-MM-DD". */
    val startedAt: String? = null,
    /** Beginn der Blüte (Umstellung auf 12/12 oder erste Blüten), "YYYY-MM-DD". */
    val floweringAt: String? = null,
    /** Geerntet am; gesetzt = nicht mehr aktiv. */
    val harvestedAt: String? = null,
    /** Trockengewicht in Gramm, beim Ernten. */
    val yieldDryG: Double? = null,
    val notes: String? = null,
    val createdAtMillis: Long? = null,
) {
    val active: Boolean get() = harvestedAt == null
    val label: String get() = name
}

/** Regeln ohne Android. Grenzen wie in firestore.rules (validPlant). */
object PlantRules {
    const val MAX_NAME = 40
    const val MAX_STRAIN = 60
    const val MAX_NOTES = 500
    const val MAX_PLANTS_PER_BOX = 50
    const val MAX_YIELD_G = 5000.0

    /** § 9 KCanG: privat höchstens drei Pflanzen gleichzeitig je volljähriger Person. */
    const val KCANG_PRIVATE_LIMIT = 3

    private fun date(text: String?): LocalDate? = text?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    /** Deutsche Fehlermeldung oder null. */
    fun validate(plant: Plant, today: String, countInBox: Int = 0): String? {
        val name = plant.name.trim()
        val start = date(plant.startedAt)
        val bloom = date(plant.floweringAt)
        val harvest = date(plant.harvestedAt)
        return when {
            name.isEmpty() || name.length > MAX_NAME -> "Der Name muss 1 bis $MAX_NAME Zeichen haben."
            (plant.strain?.trim()?.length ?: 0) > MAX_STRAIN -> "Die Sorte darf höchstens $MAX_STRAIN Zeichen haben."
            (plant.notes?.length ?: 0) > MAX_NOTES -> "Die Notiz darf höchstens $MAX_NOTES Zeichen haben."
            plant.startedAt != null && start == null -> "Ungültiger Starttag."
            plant.floweringAt != null && bloom == null -> "Ungültiger Blütebeginn."
            plant.harvestedAt != null && harvest == null -> "Ungültiger Erntetag."
            listOfNotNull(plant.startedAt, plant.floweringAt, plant.harvestedAt).any { it > today } -> "Daten in der Zukunft gehen nicht."
            start != null && bloom != null && bloom < start -> "Die Blüte kann nicht vor dem Start beginnen."
            start != null && harvest != null && harvest < start -> "Die Ernte kann nicht vor dem Start liegen."
            bloom != null && harvest != null && harvest < bloom -> "Die Ernte kann nicht vor der Blüte liegen."
            plant.yieldDryG?.let { !it.isFinite() || it !in 0.0..MAX_YIELD_G } == true -> "Der Ertrag ist unplausibel."
            plant.id.isEmpty() && countInBox >= MAX_PLANTS_PER_BOX -> "Höchstens $MAX_PLANTS_PER_BOX Pflanzen je Box."
            else -> null
        }
    }

    /** Tag seit [from] (Starttag = Tag 1), null ohne Datum oder davor. */
    fun dayOf(from: String?, today: String): Int? {
        val start = date(from) ?: return null
        val day = date(today) ?: return null
        val days = ChronoUnit.DAYS.between(start, day)
        return if (days < 0) null else days.toInt() + 1
    }

    /** "Tag 32 · Blüte Tag 12" bzw. "Geerntet 01.11.2026 · 85 g". */
    fun status(plant: Plant, today: String): String {
        if (!plant.active) {
            val total = plant.startedAt?.let { s -> dayOf(s, plant.harvestedAt!!)?.let { "nach $it Tagen" } }
            return listOfNotNull("Geerntet ${german(plant.harvestedAt!!)}", total, plant.yieldDryG?.let { "${grams(it)} trocken" }).joinToString(" · ")
        }
        val parts = mutableListOf<String>()
        dayOf(plant.startedAt, today)?.let { parts += "Tag $it" }
        dayOf(plant.floweringAt, today)?.let { parts += "Blüte Tag $it" }
        if (parts.isEmpty()) parts += "Ohne Starttag"
        return parts.joinToString(" · ")
    }

    fun details(plant: Plant): String = listOfNotNull(
        plant.strain?.takeIf { it.isNotBlank() },
        plant.origin?.label,
        "Automatik".takeIf { plant.autoflower },
    ).joinToString(" · ")

    /** Nächster freier Name „Pflanze N“. */
    fun nextName(existing: List<Plant>): String {
        val taken = existing.map { it.name.trim() }.toSet()
        return generateSequence(existing.size + 1) { it + 1 }.map { "Pflanze $it" }.first { it !in taken }
    }

    fun activeCount(plantsByBox: Map<String, List<Plant>>): Int = plantsByBox.values.sumOf { list -> list.count { it.active } }

    /** Hinweis auf § 9 KCanG, wenn privat mehr als drei Pflanzen gleichzeitig wachsen. Nicht für Social Clubs. */
    fun kcangHint(activeCount: Int, isClub: Boolean): String? =
        if (isClub || activeCount <= KCANG_PRIVATE_LIMIT) null
        else "$activeCount Pflanzen gleichzeitig: Privat erlaubt § 9 KCanG höchstens $KCANG_PRIVATE_LIMIT Pflanzen je volljähriger Person im Haushalt."

    /** Pflanze aus den alten Box-Feldern Sorte und Starttag (vor mehreren Pflanzen je Box). */
    fun fromLegacy(strain: String?, plantedAt: String?): Plant? {
        if (strain.isNullOrBlank() && plantedAt == null) return null
        return Plant(name = "Pflanze 1", strain = strain?.trim()?.ifBlank { null }, startedAt = plantedAt)
    }

    private fun german(iso: String) = date(iso)?.let { String.format(java.util.Locale.GERMANY, "%02d.%02d.%d", it.dayOfMonth, it.monthValue, it.year) } ?: iso
    private fun grams(value: Double) = if (value % 1.0 == 0.0) "${value.toLong()} g" else String.format(java.util.Locale.GERMANY, "%.1f g", value)

    fun toData(plant: Plant, isNew: Boolean): Map<String, Any?> {
        val data = mutableMapOf<String, Any?>(
            "name" to plant.name.trim(),
            "strain" to plant.strain?.trim()?.ifBlank { null },
            "origin" to plant.origin?.id,
            "autoflower" to plant.autoflower,
            "startedAt" to plant.startedAt,
            "floweringAt" to plant.floweringAt,
            "harvestedAt" to plant.harvestedAt,
            "yieldDryG" to plant.yieldDryG,
            "notes" to plant.notes?.trim()?.ifBlank { null },
            "updatedAt" to FieldValue.serverTimestamp(),
        )
        if (isNew) data["createdAt"] = FieldValue.serverTimestamp()
        return data
    }
}

/**
 * Pflanzen unter users/{uid}/growBoxes/{boxId}/plants. Die App schreibt direkt, die Firestore-Regeln prüfen das Format.
 */
class PlantRepository(
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance(),
) {
    private fun boxRef(boxId: String) = auth.currentUser?.uid?.let {
        db.collection("users").document(it).collection("growBoxes").document(boxId)
    }

    /** Pflanzen einer Box in Anlegereihenfolge. */
    fun plants(boxId: String): Flow<List<Plant>> {
        val ref = boxRef(boxId)?.collection("plants") ?: return flowOf(emptyList())
        return callbackFlow {
            val registration = ref.orderBy("createdAt", Query.Direction.ASCENDING).addSnapshotListener { snapshot, error ->
                when {
                    error?.code == FirebaseFirestoreException.Code.PERMISSION_DENIED -> trySend(emptyList())
                    error != null -> close(error)
                    else -> trySend(snapshot?.documents.orEmpty().mapNotNull { it.toPlant() })
                }
            }
            awaitClose { registration.remove() }
        }
    }

    /** Pflanzen mehrerer Boxen; eine Box erscheint erst, wenn ihre Pflanzen geladen sind. */
    fun plantsByBox(boxIds: List<String>): Flow<Map<String, List<Plant>>> {
        if (boxIds.isEmpty()) return flowOf(emptyMap())
        return combine(boxIds.map { id -> plantsWithId(id) }) { pairs -> pairs.toMap() }
    }

    private fun plantsWithId(boxId: String): Flow<Pair<String, List<Plant>>> = plants(boxId).map { boxId to it }

    /** Legt an (leere ID) oder speichert. Liefert die ID. */
    suspend fun save(boxId: String, plant: Plant): String {
        val ref = boxRef(boxId)?.collection("plants") ?: error("Nicht angemeldet")
        val doc = if (plant.id.isEmpty()) ref.document() else ref.document(plant.id)
        doc.set(PlantRules.toData(plant, plant.id.isEmpty()), SetOptions.merge()).await()
        return doc.id
    }

    suspend fun delete(boxId: String, plantId: String) {
        boxRef(boxId)?.collection("plants")?.document(plantId)?.delete()?.await()
    }

    /** Tagebuch-Einträge, die nur diese Pflanze betreffen. */
    suspend fun deleteJournalOf(boxId: String, plantId: String) {
        val ref = boxRef(boxId)?.collection("journal") ?: return
        val docs = ref.whereEqualTo("plantId", plantId).get().await().documents
        for (chunk in docs.chunked(400)) {
            val batch = db.batch()
            chunk.forEach { batch.delete(it.reference) }
            batch.commit().await()
        }
    }
}

internal fun DocumentSnapshot.toPlant(): Plant? {
    val name = getString("name") ?: return null
    return Plant(
        id = id,
        name = name,
        strain = getString("strain"),
        origin = PlantOrigin.from(getString("origin")),
        autoflower = getBoolean("autoflower") == true,
        startedAt = getString("startedAt"),
        floweringAt = getString("floweringAt"),
        harvestedAt = getString("harvestedAt"),
        yieldDryG = getDouble("yieldDryG"),
        notes = getString("notes"),
        createdAtMillis = getTimestamp("createdAt")?.toDate()?.time,
    )
}
