package de.greenganja.growguardian.push

import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

/** Überwacht der Server gerade? Dann meldet das Handy Alarme nicht noch einmal. */
interface ServerMonitorStatus {
    /** Zeitpunkt des letzten erfolgreichen Server-Laufs (ms) oder null. */
    suspend fun lastOkAt(): Long?

    companion object {
        /** Der Server läuft alle 5 Minuten; nach 20 Minuten ohne Lauf übernimmt wieder das Handy. */
        const val FRESH_MS = 20 * 60 * 1000L

        fun isActive(lastOkAt: Long?, now: Long): Boolean = lastOkAt != null && now - lastOkAt in 0..FRESH_MS
    }
}

class FirestoreServerMonitorStatus : ServerMonitorStatus {
    override suspend fun lastOkAt(): Long? {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return null
        val doc = FirebaseFirestore.getInstance().collection("users").document(uid)
            .collection("serverState").document("monitor").get().await()
        return (doc.get("lastOkAt") as? Timestamp)?.toDate()?.time
    }
}
