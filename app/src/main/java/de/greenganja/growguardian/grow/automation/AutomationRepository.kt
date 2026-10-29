package de.greenganja.growguardian.grow.automation

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import de.greenganja.growguardian.backend.Backend
import de.greenganja.growguardian.grow.DpDefinition
import de.greenganja.growguardian.grow.Metric
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.tasks.await

/**
 * Regeln unter `users/{uid}/automations/{id}`.
 *
 * Anlegen, Ändern und Löschen laufen über die Cloud Functions `saveAutomation` und `deleteAutomation`,
 * die Premium und das Limit prüfen. Nur die Sync-Felder (IDs der Tuya-Automationen, Fehler) schreibt
 * die App direkt, das erlauben die Firestore-Regeln.
 */
class AutomationRepository(
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val backend: Backend = Backend(),
) : AutomationStore {
    private fun collection() = auth.currentUser?.uid?.let { db.collection("users").document(it).collection("automations") }

    override fun automations(): Flow<List<Automation>> {
        val ref = collection() ?: return flowOf(emptyList())
        return callbackFlow {
            val registration = ref.addSnapshotListener { snapshot, error ->
                when {
                    error?.code == FirebaseFirestoreException.Code.PERMISSION_DENIED -> trySend(emptyList())
                    error != null -> close(error)
                    else -> trySend(snapshot?.documents.orEmpty().mapNotNull { it.toAutomation() }.sortedBy { it.name.lowercase() })
                }
            }
            awaitClose { registration.remove() }
        }
    }

    override suspend fun automationsOnce(): List<Automation> =
        collection()?.get()?.await()?.documents.orEmpty().mapNotNull { it.toAutomation() }

    /** Anlegen (leere ID) oder speichern; liefert die ID. Das Backend prüft Stufe, Limit und Werte. */
    override suspend fun save(automation: Automation): Result<String> =
        backend.call("saveAutomation", automation.toPayload()).mapCatching { it["automationId"] as? String ?: error("automationId fehlt") }

    override suspend fun delete(id: String): Result<Unit> = backend.call("deleteAutomation", mapOf("automationId" to id)).map { }

    /** Ergebnis der Übertragung in die Tuya-Cloud festhalten. */
    override suspend fun recordSync(id: String, sceneIds: List<String>, error: String?) {
        val ref = collection()?.document(id) ?: return
        ref.update(mapOf("sceneIds" to sceneIds, "syncError" to error, "syncedAt" to FieldValue.serverTimestamp())).await()
    }
}

internal fun Automation.toPayload(): Map<String, Any?> {
    val base = mapOf(
        "automationId" to id.ifEmpty { null },
        "boxId" to boxId,
        "name" to name.trim(),
        "enabled" to enabled,
        "actuatorDeviceId" to actuatorDeviceId,
        "actuatorDpId" to actuatorDpId,
        "actuatorLabel" to actuatorLabel,
        "actuatorCode" to actuatorCode,
    )
    return base + when (val t = trigger) {
        is AutomationTrigger.Threshold -> mapOf(
            "type" to "threshold",
            "sensorDeviceId" to t.sensorDeviceId,
            "metric" to t.metric.id,
            "dpId" to t.source.id,
            "dpCode" to t.source.code,
            "scale" to t.source.scale,
            "unit" to t.source.unit,
            "switchOnAbove" to t.switchOnAbove,
            "onAt" to t.onAt,
            "offAt" to t.offAt,
        )
        is AutomationTrigger.Schedule -> mapOf("type" to "schedule", "onTime" to t.onTime, "offTime" to t.offTime, "days" to t.days)
    }
}

internal fun DocumentSnapshot.toAutomation(): Automation? {
    val trigger = when (getString("type")) {
        "threshold" -> AutomationTrigger.Threshold(
            sensorDeviceId = getString("sensorDeviceId") ?: return null,
            metric = Metric.from(getString("metric")) ?: return null,
            source = DpDefinition(
                id = getString("dpId") ?: return null,
                code = getString("dpCode").orEmpty(),
                scale = getLong("scale")?.toInt() ?: 0,
                unit = getString("unit").orEmpty(),
            ),
            switchOnAbove = getBoolean("switchOnAbove") ?: true,
            onAt = getDouble("onAt") ?: return null,
            offAt = getDouble("offAt") ?: return null,
        )
        "schedule" -> AutomationTrigger.Schedule(
            onTime = getString("onTime") ?: return null,
            offTime = getString("offTime") ?: return null,
            days = getString("days") ?: AutomationTrigger.EVERY_DAY,
        )
        else -> return null
    }
    return Automation(
        id = id,
        boxId = getString("boxId") ?: return null,
        name = getString("name").orEmpty(),
        enabled = getBoolean("enabled") != false,
        trigger = trigger,
        actuatorDeviceId = getString("actuatorDeviceId") ?: return null,
        actuatorDpId = getString("actuatorDpId") ?: return null,
        actuatorLabel = getString("actuatorLabel").orEmpty(),
        actuatorCode = getString("actuatorCode").orEmpty(),
        sceneIds = (get("sceneIds") as? List<*>)?.filterIsInstance<String>().orEmpty(),
        syncError = getString("syncError"),
    )
}
