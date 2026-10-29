package de.greenganja.growguardian.push

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import de.greenganja.growguardian.grow.alarm.BoxAlert
import de.greenganja.growguardian.grow.alarm.GrowNotifier
import kotlinx.coroutines.tasks.await

/**
 * Push-Alarme vom Server (serverMonitor). Das Token des Geräts liegt unter users/{uid}/pushTokens/{token};
 * der Server schickt dorthin und räumt ungültige Token selbst weg.
 */
object PushTokens {
    /** Nach dem Login aufrufen; mehrfach unschädlich. */
    suspend fun register() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val token = runCatching { FirebaseMessaging.getInstance().token.await() }.getOrNull() ?: return
        save(uid, token)
    }

    /** Beim Abmelden: dieses Gerät soll keine Alarme des alten Kontos mehr bekommen. */
    suspend fun unregister(uid: String) {
        val token = runCatching { FirebaseMessaging.getInstance().token.await() }.getOrNull() ?: return
        runCatching { doc(uid, token).delete().await() }
    }

    internal fun save(uid: String, token: String) {
        doc(uid, token).set(mapOf("platform" to "android", "updatedAt" to FieldValue.serverTimestamp()))
    }

    private fun doc(uid: String, token: String) =
        FirebaseFirestore.getInstance().collection("users").document(uid).collection("pushTokens").document(token)
}

/**
 * Empfängt Push-Nachrichten. Im Hintergrund zeigt Android sie selbst an (Kanal „grow_alarms“); ist die App offen,
 * zeigt dieser Dienst sie über [GrowNotifier] an. Im Manifest eingetragen.
 */
class GrowMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        PushTokens.save(uid, token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val notification = message.notification ?: return
        val boxId = message.data["boxId"] ?: return
        GrowNotifier(this).show(BoxAlert(boxId, notification.title.orEmpty(), notification.body.orEmpty().lines()))
    }
}
